// 任务列表渲染与交互。
//
// 安全约束（规格 §20.1）：任务文本一律通过 textContent 写入，
// 绝不拼接 innerHTML —— 输入 `<img src=x onerror=alert(1)>` 必须原样显示。

import { state, call, notify, toast } from './state.js';
import { isLoaded, DEFAULT_CHAIN } from './fonts.js';
import { initDrag, cancelDrag } from './dnd.js';

// 提醒高亮用。和 ui/window.js 一样，从全局的 __TAURI__ 拿（没有打包器，
// 也没有 @tauri-apps/api 这个依赖）。
const { listen } = window.__TAURI__.event;

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

// ---------------------------------------------------------------------------
// 提醒编辑浮层
//
// 浮层本体是 **#app 的兄弟节点**（见 ui/index.html 那段注释），不是 .todo 的子节点
// —— 浮层里每次改动都会 call()，而 call() 返回后 renderList() 会 replaceChildren()
// 重建整个列表，挂在行里就会被自己引起的重绘当场销毁。
//
// 状态只有 remindFor（那条任务的 id）。**不缓存 todo 对象**：重绘之后它可能已经
// 不是同一条了，用 id 现查最省心。
// ---------------------------------------------------------------------------

/** 正在编辑提醒的那条任务 id；浮层关着时为 null。 */
let remindFor = null;

/** 时间框在「还没设过提醒」时的初值：下一个整点。 */
function defaultRemindTime() {
  const now = new Date();
  const next = new Date(now);
  next.setHours(now.getHours() + 1, 0, 0, 0);

  // 跨过午夜就退回今天最后一刻。`once` 的日期写的是今天，给一个已经过去的时间
  // 会让提醒**立刻**弹出 —— 那不是用户点「明天 0 点」的意思。这个问题本身
  // （一次性的提醒表达不了明天）留给以后，这里只保证不给一个反直觉的初值。
  if (next.getDate() !== now.getDate()) return '23:59';

  const p = (n) => String(n).padStart(2, '0');
  return `${p(next.getHours())}:${p(next.getMinutes())}`;
}

/**
 * 这条任务的提醒该怎么念（`每天 09:00` / `今天 09:00` / `10-17 09:00`）。
 *
 * 一次性提醒的日期可能不是今天 —— 「周一建的任务，周五早上九点提醒我」是它的
 * 主要用法。日期前缀用 `MM-DD`，和列表里那个 `.stale` 前缀同一套写法。
 */
function remindLabel(todo) {
  const at = todo.remindAt || '';
  if (todo.remindMode === 'daily') return `每天 ${at}`;

  // remindDate 为空只有手改文件能造出来，回落到 createdDate 让念法不至于空着
  const day = todo.remindDate || todo.createdDate || '';
  if (!day || day === todayKey()) return `今天 ${at}`;
  return `${day.slice(5)} ${at}`;
}

/** 把一条任务的提醒状态画到铃铛上。 */
function applyBellState(bell, todo) {
  if (!todo.remindAt) {
    bell.removeAttribute('data-remind');
    bell.title = '设置提醒';
    bell.setAttribute('aria-label', '设置提醒');
    return;
  }

  bell.dataset.remind = todo.remindMode === 'daily' ? 'daily' : 'once';
  const when = remindLabel(todo);
  bell.title = `提醒：${when}`;
  bell.setAttribute('aria-label', `提醒：${when}`);
}

/**
 * 这条任务带提醒吗。
 *
 * 带提醒的**不显示** `[MM-DD]` 那个日期前缀。前缀的语义是「这条你昨天没做完」——
 * 一个积压标记。而设了提醒的任务是**排期**：周一为周五建的任务在周二显示
 * `[10-13]`，会让人以为它已经欠了三天。两种模式一视同仁，所以这里只看有没有时间。
 */
function hasReminder(todo) {
  return !!todo.remindAt;
}

function setPressedMode(mode) {
  for (const b of document.querySelectorAll('.remind-mode')) {
    b.setAttribute('aria-pressed', String(b.dataset.mode === mode));
  }
}

function pressedMode() {
  const on = document.querySelector('.remind-mode[aria-pressed="true"]');
  return on ? on.dataset.mode : 'once';
}

/** 切模式时同步按钮的按下态，并收放日期框。 */
function setPopMode(mode) {
  setPressedMode(mode);
  // 每日模式没有起始日期的概念 —— 收起来，顺带说明两种模式的区别不只是重复方式
  document.getElementById('remind-date').hidden = mode === 'daily';
}

/** 开浮层并把它贴到被点那一行旁边。 */
function openRemindEditor(todo, anchor) {
  const pop = document.getElementById('remind-pop');
  const time = document.getElementById('remind-time');
  const date = document.getElementById('remind-date');

  remindFor = todo.id;
  time.value = todo.remindAt || defaultRemindTime();

  // 日期框不接受过去的日期（后端也会拒，但让用户先选到再被驳回是很差的体验）。
  // 一条已经响过的一次性提醒，它的 remindDate 就是过去 —— 这时回落到今天，
  // 正好是「想再设一次」的默认意图。
  const today = todayKey();
  const stored = todo.remindDate || '';
  date.min = today;
  date.value = stored && stored >= today ? stored : today;

  setPopMode(todo.remindMode === 'daily' ? 'daily' : 'once');

  // 先显示再量尺寸 —— 处于 hidden 时拿不到有意义的高度
  pop.hidden = false;
  state.ui.remindOpen = true;

  // 窗口只有 280px 宽，浮层 196px，所以水平方向基本只能贴右边，但要夹紧
  const r = anchor.getBoundingClientRect();
  const pr = pop.getBoundingClientRect();
  const maxLeft = window.innerWidth - pr.width - 6;
  const maxTop = window.innerHeight - pr.height - 6;
  pop.style.left = `${Math.min(Math.max(6, r.right - pr.width), Math.max(6, maxLeft))}px`;
  pop.style.top = `${Math.min(r.bottom + 4, Math.max(6, maxTop))}px`;
}

function closeRemindEditor() {
  const pop = document.getElementById('remind-pop');
  if (!pop || pop.hidden) return;
  pop.hidden = true;
  state.ui.remindOpen = false;
  remindFor = null;
}

/** renderList() 之后调用：被编辑的那条没了（删掉、或别的重绘路径移走）就收起浮层。 */
function syncRemindEditor() {
  if (!remindFor) return;
  if ((state.data.todos || []).some((t) => t.id === remindFor)) return;
  closeRemindEditor();
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

  // 历史遗留的未完成项（创建日期早于今天）加一个轻量前缀。
  // 带提醒的不加 —— 那是排期不是积压，见 hasReminder。
  const created = todo.createdDate || '';
  if (!todo.completed && created && created !== todayKey() && !hasReminder(todo)) {
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

  const bell = document.createElement('button');
  bell.className = 'todo-bell';
  bell.type = 'button';
  bell.textContent = '🔔';
  applyBellState(bell, todo);
  bell.addEventListener('click', () => {
    // 再点一次同一个铃铛就是收起 —— 和 ⚙ 那个按钮同一种手感
    if (remindFor === todo.id) {
      closeRemindEditor();
      return;
    }
    openRemindEditor(todo, bell);
  });

  const del = document.createElement('button');
  del.className = 'todo-del';
  del.textContent = '×';
  del.title = '删除';
  del.setAttribute('aria-label', '删除任务');
  del.addEventListener('click', () => {
    call('delete_todo', { id: todo.id }).catch(() => {});
  });

  // 顺序与 .todo 的 flex 布局一致：勾选 / 正文 / 铃铛 / 删除
  li.append(box, span, bell, del);
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

  // 被编辑的那条任务可能在别处没了（删除、清空已完成、跨日重置……）。
  // 浮层在列表之外，不会被 replaceChildren 带走，所以得自己核对一次。
  syncRemindEditor();
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

  initRemindEditor();
}

// ---------------------------------------------------------------------------
// 提醒浮层的接线
// ---------------------------------------------------------------------------

/**
 * 提交一次提醒改动。两个输入框的值都从 DOM 现读。
 *
 * `at` 为空 = 清除（命令层也认这个语义）。每日模式**不传日期**：那边没有起始
 * 日期的概念，传一个陈旧的日期进去只会被后端丢掉，不如一开始就不发。
 */
function commitReminder(mode) {
  if (!remindFor) return;
  const at = document.getElementById('remind-time').value || '';
  const date = mode === 'daily' ? '' : document.getElementById('remind-date').value || '';
  call('set_reminder', { id: remindFor, at, mode, date }).catch(() => {});
}

function initRemindEditor() {
  const time = document.getElementById('remind-time');
  const date = document.getElementById('remind-date');

  // 改动即生效，没有「保存」按钮 —— 这个仓库里没有一个需要二次确认的设置。
  // 浮层在列表之外，所以这里每次 call() 引起的重绘都不会影响它自己。
  for (const el of [time, date]) {
    el.addEventListener('change', () => {
      if (!remindFor) return;
      commitReminder(pressedMode());
    });
  }

  for (const b of document.querySelectorAll('.remind-mode')) {
    b.addEventListener('click', () => {
      if (!remindFor) return;
      setPopMode(b.dataset.mode);
      commitReminder(b.dataset.mode);
    });
  }

  document.getElementById('remind-clear').addEventListener('click', () => {
    if (!remindFor) return;
    // 先收起浮层再把 id 交出去 —— closeRemindEditor 会把 remindFor 清掉
    const id = remindFor;
    closeRemindEditor();
    call('set_reminder', { id, at: '', mode: '', date: '' }).catch(() => {});
  });

  // 点到浮层外就收起。用 mousedown 而不是 click：click 要等鼠标松开，
  // 中间那段时间用户可能已经点到别的东西上了。
  document.addEventListener('mousedown', (e) => {
    if (!remindFor) return;
    // 可选链：target 理论上可以是 document 这种没有 closest 的节点
    if (e.target?.closest?.('#remind-pop')) return;
    // 点铃铛交给它自己的 click —— 那里是「再点一次收起」的切换语义，
    // 在这里先关掉会让它紧接着又被打开，看着像没反应。
    if (e.target?.closest?.('.todo-bell')) return;
    closeRemindEditor();
  });

  // Esc 收起浮层。
  //
  // **不需要 stopPropagation。** 它拦不住 window.js 那条监听器 —— 两个都挂在
  // 同一个 document 上，而 stopPropagation 只管「继续往外冒」，同元素上的其他
  // 监听器照跑（那是 stopImmediatePropagation 的事）。所以那边是靠
  // `state.ui.remindOpen` 主动让路的，见 ui/window.js。
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!remindFor) return;
    closeRemindEditor();
  });

  // 提醒高亮：Rust 在用户点浮窗时发过来的。
  // 找不到那条任务就静默忽略 —— 用户可能刚把它删了，那不是错误。
  listen('mino://reminder-open', (e) => {
    const id = String(e.payload ?? '');
    if (!id) return;

    const li = document.querySelector(`li.todo[data-id="${CSS.escape(id)}"]`);
    if (!li) return;

    li.classList.add('todo-flash');
    li.scrollIntoView({ block: 'nearest' });
    setTimeout(() => li.classList.remove('todo-flash'), 2000);
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
