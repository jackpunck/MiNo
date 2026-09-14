// 任务列表渲染与交互。
//
// 安全约束（规格 §20.1）：任务文本一律通过 textContent 写入，
// 绝不拼接 innerHTML —— 输入 `<img src=x onerror=alert(1)>` 必须原样显示。

import { state, call, toast } from './state.js';
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
  const own = (todo.font || '').trim();
  // 指向导入字体、但字节没注册进来的：写这条链也只会静默回退到链尾的
  // sans-serif，不如改用全局字体，结果确定。空串同理（没见过，但手改文件可能造出来）。
  const usable = own && (!todo.fontId || isLoaded(todo.fontId));
  span.style.setProperty(
    'font-family',
    usable ? own : state.data.settings?.fontFamily || DEFAULT_CHAIN,
  );

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

export function renderList() {
  const list = document.getElementById('list');
  const todos = state.data.todos || [];

  // 兜底：整表重建会把正在拖的那一行从 DOM 摘掉，pointer capture 随之隐式
  // 释放，之后 pointerup 就再也收不到了。正常路径上 app.js 会跳过拖动中的
  // 重绘，这里是防止有别的调用方绕过去。
  cancelDrag();

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
