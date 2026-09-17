// 任务列表渲染与交互。
//
// 安全约束（规格 §20.1）：任务文本一律通过 textContent 写入，
// 绝不拼接 innerHTML —— 输入 `<img src=x onerror=alert(1)>` 必须原样显示。

import { state, call, notify, toast } from './state.js';
import { isLoaded, DEFAULT_CHAIN } from './fonts.js';
import { initDrag, cancelDrag } from './dnd.js';

/**
 * 今天的本地日期（`YYYY-MM-DD`），与 Rust 的 `state::today_string()` 同源同格式。
 *
 * **别改回读 `settings.lastCheckDate`。** 那个值看着像「今天」，其实是「上次跨日
 * 结算发生在哪天」，而且它在前端只会被更新一次 —— `load_state` 只在启动时调
 * （[ui/app.js](ui/app.js) 的 boot），之后 `state.data` 要靠别的 command 返回快照
 * 才会刷新。页面跨夜开着时它停在前一天，于是「昨天建的」和「今天」被判成同一天，
 * 日期前缀整个不出现。
 *
 * 这不是理论推演：2026-09-14 实机复现过 —— 日志显示应用从 09-13 16:04 起就没重启
 * 过，09-13 建的那条未完成待办因此一直不带前缀，看着像功能坏了。旧的两条（09-11
 * 建）反而正常，所以表现是「时灵时不灵」，最容易被误判成偶发。
 *
 * 「今天」只有客户端自己知道。问服务端要一个顺带更新的字段，早晚会漂。
 */
function todayKey() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/**
 * 这条任务该用哪条字体链。
 *
 * `buildItem` 和 `startEdit` 共用 —— 编辑框必须和它替换掉的那个 span 长得一模一样，
 * 否则进出编辑态时字体会跳一下。
 *
 * 指向导入字体、但字节没注册进来的：写这条链也只会静默回退到链尾的
 * `sans-serif`，不如改用全局字体，结果确定、可预期。空串同理（没见过，
 * 但手改文件可能造出来）。
 */
function fontChainFor(todo) {
  const own = (todo.font || '').trim();
  const usable = own && (!todo.fontId || isLoaded(todo.fontId));
  return usable ? own : state.data.settings?.fontFamily || DEFAULT_CHAIN;
}

/**
 * 正在编辑的那一条。`{ id, input, original }`，没有编辑时为 `null`。
 *
 * 是**瞬态**的：不进 `state.data`、不落盘、不跨重绘存活（重绘会被 renderList 跳过，
 * 见那里的注释）。和 dnd.js 的 `drag` 是同一路数。
 */
let edit = null;

/** renderList 和 window.js 用它决定要不要保护当前这一轮的 DOM。 */
export function isEditing() {
  return edit !== null;
}

function buildItem(todo) {
  const li = document.createElement('li');
  li.className = 'todo' + (todo.completed ? ' completed' : '');
  li.dataset.id = todo.id;

  const box = document.createElement('input');
  box.type = 'checkbox';
  box.checked = !!todo.completed;
  box.setAttribute('aria-label', todo.completed ? '标记为未完成' : '标记为已完成');
  box.addEventListener('change', () => {
    call('toggle_todo', { id: todo.id }).catch(() => {
      // 失败时把勾选状态恢复成与数据一致
      box.checked = !box.checked;
    });
  });

  const span = document.createElement('span');
  span.className = 'todo-text';
  span.title = todo.text; // 被省略号截断时，悬停仍可看到全文

  // 每条任务用自己的字体（改全局字体只影响此后新建的任务）。
  //
  // 走 inline style 而不是 --note-font 变量：变量是全局的，表达不了「每条不一样」。
  // inline 的优先级高于 styles.css 里那条 font-family: var(--note-font)，正好盖掉。
  //
  // 用 style.setProperty 而不是拼 cssText / setAttribute('style', ...) —— 后者才是
  // 有注入风险的写法，前者是 CSSOM 属性赋值，非法值会被解析器直接丢弃。
  span.style.setProperty('font-family', fontChainFor(todo));

  // 历史遗留的未完成项（创建日期早于今天）加一个轻量前缀
  const created = todo.createdDate || '';
  if (!todo.completed && created && created !== todayKey()) {
    const stale = document.createElement('span');
    stale.className = 'stale';
    stale.textContent = `[${created.slice(5)}]`;
    span.append(stale);
  }

  // 关键：用户输入永远走 textContent
  span.append(document.createTextNode(todo.text));

  // 二次编辑的入口。双击不会和拖动打架：dnd.js 的 pointerdown 只认
  // `closest('input, button')`，在 span 上确实会建出 drag 对象，但没越过
  // DRAG_THRESHOLD 就 cleanup() 直接 return，既不 setPointerCapture 也不跑 IPC，
  // 更不碰 DOM 结构 —— 两次 click 命中同一个元素，dblclick 照常成立。
  span.addEventListener('dblclick', () => startEdit(todo, span));

  const del = document.createElement('button');
  del.className = 'todo-del';
  del.textContent = '×';
  del.title = '删除';
  del.setAttribute('aria-label', '删除任务');
  del.addEventListener('click', () => {
    call('delete_todo', { id: todo.id }).catch(() => {});
  });

  li.append(box, span, del);
  return li;
}

/**
 * 就地把这条任务的 `span` 换成输入框。
 *
 * 瞬态：不写 `state.data`，改动只在 `commitEdit` 里才过 IPC。
 */
function startEdit(todo, span, initial = null) {
  // 保险。正常路径上双击第二行时，第一次 mousedown 已经把当前编辑提交掉了
  //（blur 那条路），走不到这里；但「异步重绘插进一次用户手势里」这类缝隙是
  // 真实存在的，多一道判断比递归调用 commitEdit 便宜得多。
  if (edit) return;

  const input = document.createElement('input');
  input.className = 'todo-edit';
  input.type = 'text';
  // `initial` 只在「保存失败后还原」时给：那时输入框里该是用户刚敲的那份文本，
  // 而 `original`（下面 edit 里存的）仍然是磁盘上那份 —— 之后再清空保存，
  // 恢复的是磁盘上的原文，不是这次失败的那份。
  input.value = initial ?? todo.text;
  // #input 有先例：WebView2 会画拼写波浪线，还可能弹自动填充
  input.spellcheck = false;
  input.autocomplete = 'off';
  input.setAttribute('aria-label', '编辑任务');
  input.style.setProperty('font-family', fontChainFor(todo));

  // 连 .stale 前缀一起换掉 —— 那是「这条是从哪天拖过来的」的标记，不属于正文，
  // 进了编辑框就会被当成文本改坏。
  span.replaceWith(input);

  edit = { id: todo.id, input, original: todo.text };

  focusEditInput(input);

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      // 输入法确认候选的那次 Enter 不是提交。两个条件都要：`isComposing` 覆盖
      // 大多数，`keyCode === 229` 覆盖「compositionend 之后才到」那种 ——
      // 那时 `isComposing` 已经是 false 了。
      if (e.isComposing || e.keyCode === 229) return;
      e.preventDefault();
      commitEdit(true);
      return;
    }

    if (e.key !== 'Escape') return;
    // 设置面板开着时 Esc 该去关面板（window.js 那条会处理），别吞掉
    if (state.ui.settingsOpen) return;

    // **必须吞掉。** window.js 那个全局 Esc 挂在 document 上、不看 e.target
    // 也不看 e.defaultPrevented，放它冒上去窗口就没了 —— 比「白按一下 Esc」
    // 严重得多。组合中的 Esc 是「关掉候选窗」，同样要吞。
    e.stopPropagation();
    if (e.isComposing || e.keyCode === 229) return;

    commitEdit(false);
  });

  // 点到别处、切走、点到标题栏都算「改完了」
  input.addEventListener('blur', () => commitEdit(true));
}

/** 聚焦编辑框并**全选**。理由见 startEdit 里那段注释。 */
function focusEditInput(input) {
  input.focus();
  try {
    input.select();
  } catch {
    /* 忽略：某些状态下标点设置会抛错，但不影响聚焦（同 focusInput） */
  }
}

/**
 * 保存失败之后把用户刚敲的那份文本还给用户，重新进编辑态。
 *
 * 同 `add_todo` 的失败处理：不能让一句 toast 之后内容凭空消失。
 *
 * ⚠️ **不能去找 `.todo-text` 那个 span**：`startEdit` 已经把它从 DOM 里换掉了，
 * 输入框才是当前在位的元素。这正是「保存失败 → 内容静默丢失」最容易发生的地方。
 */
function reopenEdit(id, text) {
  const li = document.querySelector(`li.todo[data-id="${CSS.escape(id)}"]`);
  if (!li) return; // 行已经没了（比如用户先点了 ×），没什么可还原的

  const todo = (state.data.todos || []).find((t) => t.id === id);
  if (!todo) return;

  // 常见路径：`call()` 失败意味着没有 setData，也就没有重绘 —— 输入框还在原地，
  // 监听也都还挂着。只要把 readOnly 撤掉、重新登记进 `edit` 就复活了，
  // **不需要重建任何 DOM**。
  const live = li.querySelector('.todo-edit');
  if (live) {
    live.readOnly = false;
    live.value = text;
    edit = { id, input: live, original: todo.text };
    focusEditInput(live);
    return;
  }

  // 兜底：这中间有别的 command 返回、触发过一轮重绘（那时 edit 已是 null，
  // renderList 把输入框换回了 span）。重新进一次编辑态。
  const span = li.querySelector('.todo-text');
  if (span) startEdit(todo, span, text);
}

/**
 * 结束编辑。`save` 为 false 表示取消（Esc）。
 *
 * 结构照抄 dnd.js 的 `finish()` —— 尤其是「先清位再 call」那一条。
 */
export function commitEdit(save) {
  // 重入闸。回车提交之后 IPC 要往返一次，这期间输入框还在 DOM 里、监听还挂着：
  // 用户再按一次回车、或者渲染把它摘掉时触发的那次 blur，都会再进来一次。
  if (!edit) return;

  const { id, input, original } = edit;
  // 顺序很重要：**先**清位，下面 call() 返回触发的那轮重绘才不会被 renderList
  // 当成「编辑中」跳过。同 dnd.js 的 finish()。
  edit = null;

  const next = input.value.trim();
  if (!save || !next || next === original) {
    // 取消、清空（= 恢复原文，同 add_todo 的「空白不是错误」）、或压根没改 ——
    // 重绘回 span 就行，不必白跑一次 IPC + fsync
    notify();
    return;
  }

  // 往返期间别再让用户往里敲，那些字会被重绘一起吃掉。
  // 用 readOnly 而不是 disabled：disabled 会在设置的那一瞬间触发 blur，绕回重入。
  input.readOnly = true;

  call('edit_todo', { id, text: next }).catch(() => {
    // 失败时把内容还给用户，同 add_todo。#toast 只有一个元素，所以两个 toast
    // 里用户看到的是后一个。
    reopenEdit(id, next);
    toast('保存失败，内容已保留');
  });
}

export function renderList() {
  const list = document.getElementById('list');
  const todos = state.data.todos || [];

  // 兜底：整表重建会把正在拖的那一行从 DOM 摘掉，pointer capture 随之隐式
  // 释放，之后 pointerup 就再也收不到了。正常路径上 app.js 会跳过拖动中的
  // 重绘，这里是防止有别的调用方绕过去。
  cancelDrag();

  // 编辑中不重建列表：replaceChildren 会把正在编辑的 input 从 DOM 摘掉，用户的
  // 输入和光标一起没。和 app.js 跳过拖动中重绘是同一类保护，但**这一条必须写在
  // renderList 里，不能写成 app.js 的 render() 顶部一个 `if (isEditing()) return`**
  // —— render() 还管着 renderWindowUI() 和 applyAppearance()，那样写会让编辑期间
  // 点 📌 没反应、「应用到全部任务」弹了提示却一条都没变。那些跟列表无关。
  //
  // 已知代价：编辑期间拖**别的**行，reorder_todos 会成功但这一轮不重绘，用户看到
  // 行弹回旧顺序，等编辑结束才跳到新顺序。可接受 —— 另一条路（重绘后按 edit.id
  // 重建输入框并恢复光标）要把正在组合的 input 从 DOM 摘掉，输入法组合态直接丢，
  // 那是本仓库刻意躲开的失败形态（CLAUDE.md 第 9 条），代价大得多。
  if (!isEditing()) {
    list.replaceChildren();

    if (todos.length === 0) {
      const empty = document.createElement('li');
      empty.className = 'empty';
      empty.textContent = '今天还没有任务';
      list.append(empty);
    } else {
      for (const todo of todos) {
        list.append(buildItem(todo));
      }
    }
  }

  // 下面两件**永远**要做：编辑期间勾掉别的行（或删掉一条），进度和禁用态必须跟着动。
  const done = todos.filter((t) => t.completed).length;
  document.getElementById('progress').textContent = `进度: ${done}/${todos.length}`;

  const clearBtn = document.getElementById('btn-clear');
  clearBtn.disabled = done === 0;
}

export function initTodos() {
  const input = document.getElementById('input');

  initDrag(document.getElementById('list'));

  input.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;

    const text = input.value.trim();
    if (!text) {
      // 空输入按 Enter 不做任何事，也不给出错提示
      input.value = '';
      return;
    }

    // 先清空再发送：添加是高频操作，等 IPC 往返再清空会有肉眼可见的迟滞
    input.value = '';

    call('add_todo', { text }).catch(() => {
      // 失败时把内容还给用户，避免白打一遍
      input.value = text;
      toast('添加失败，内容已保留');
    });
  });

  document.getElementById('btn-clear').addEventListener('click', () => {
    if ((state.data.todos || []).some((t) => t.completed)) {
      call('clear_completed').catch(() => {});
    }
  });
}

export function focusInput() {
  const input = document.getElementById('input');
  input.focus();
  // 保持在末尾，符合「继续输入」的直觉
  const len = input.value.length;
  try {
    input.setSelectionRange(len, len);
  } catch {
    /* 忽略：某些状态下标点设置会抛错，但不影响聚焦 */
  }
}
