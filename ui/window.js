// 窗口行为：焦点、快捷键、置顶按钮、边缘吸附的悬停展开。

import { invoke, call, state } from './state.js';
import { focusInput } from './todos.js';
import { closeSettings, openSettings } from './settings.js';

/**
 * 鼠标离开后延迟最小化。
 *
 * 0.5 秒：够把指针挪到相邻窗口而不误触发，又不会让窗口「赖着不走」。
 */
const COLLAPSE_DELAY = 500;

/**
 * 拖动兜底时长。Windows 的原生移动循环期间页面收不到任何鼠标事件，松开时那个
 * mouseup 根本到不了我们手里，只能靠它兜住「清位信号永远不来」的情形
 * （比如松手时指针已经在窗口外）。
 */
const DRAG_SAFETY_MS = 20000;

/**
 * 在输入框里动过手之后的宽限窗口，见 holdWindowOpen。
 *
 * 2000 不是新拍的数：v0.1.2 用的就是这个值，实机跑过没问题。连续录入时相邻
 * 两次按键的间隔远小于它，所以只要还在敲就永远不会收缩。
 */
const INPUT_GRACE_MS = 2000;

/**
 * 窗口是不是系统级的当前活动窗口。由 Rust 的 WindowEvent::Focused 推送。
 *
 * 它决定的是「切走就收缩、切回来就展开」，**不再是**悬停门控的判据 ——
 * 那个改成了 `fullscreenAhead`，原因见 refreshHoverBlock。
 */
let active = true;

/**
 * 前台有没有全屏程序压着。悬停门控用，由 refreshHoverBlock 在 mouseenter 时刷新。
 *
 * 初值 `false`（放行）是刻意的：这个值只用来**抑制**悬停，万一查询那条路整个断掉，
 * 退化成「鼠标碰到就展开」远好过退化成「感应条永远叫不醒」—— 后者正是要修的 bug。
 */
let fullscreenAhead = false;

/** 输入框。模块级是因为收缩判据要用它，见 hasPendingInput。 */
let inputEl = null;

/** 最后一次在输入框里动过手的时刻。 */
let lastInputAt = Number.NEGATIVE_INFINITY;

/** 用户是不是正按着鼠标拖窗口。拖动期间不自动收缩，见 beginWindowDrag。 */
let windowDragging = false;

let dragSafetyTimer = null;
let collapseTimer = null;

/**
 * 窗口操作的串行队列。
 *
 * `set_edge_collapsed` 和 `minimize_to_edge` 都是 `#[tauri::command(async)]`，
 * 跑在线程池上，**两次 invoke 的执行顺序没有保证**。快速来回切窗口时收缩和展开
 * 几乎同时发出，乱序落地就会停在错误状态（窗口失焦却展开着）。串成一条链最便宜。
 */
let opChain = Promise.resolve();

function enqueue(op) {
  opChain = opChain.then(op).catch(() => {});
  return opChain;
}

/**
 * 记日志用的原因串。
 *
 * 收缩是个不可复现的 bug 的多发地：事后只看 Rust 日志知道「窗口缩了」，但不知道
 * 是**哪条路径**缩的。把前端这三个判据一并带过去，下一次复现就能直接读出来。
 * 只用于记录，Rust 侧不拿它做任何分支。
 */
function why(tag) {
  const s = `active=${active},pending=${hasPendingInput()},drag=${windowDragging},full=${fullscreenAhead}`;
  return `${tag}(${s})`;
}

/**
 * 刷新 fullscreenAhead：问 Rust「前台是不是压着一个全屏程序」。
 *
 * 悬停门控的判据从「MiNo 必须是活动窗口」换成了这个。原来那条判据的问题不是
 * 太严，而是**自锁**：感应条只在 MiNo 是活动窗口时才响应悬停，可它缩在屏幕边上
 * 的时候恰恰不可能是活动窗口 —— 于是每一次都得先按 Alt+Space 把它叫活，感应条
 * 自己永远醒不过来。用户看到的「鼠标碰上去没反应」就是这么来的。
 *
 * 真正会被浮窗打扰的场景其实只有全屏内容（全屏视频、全屏演示、F11 网页），
 * 而那正是这个函数在问的事。桌面和普通窗口下用户把鼠标挪上来就是想用它。
 *
 * 查不到就当作「没有全屏程序」（放行），理由同 fullscreenAhead 的初值。
 */
async function refreshHoverBlock() {
  try {
    fullscreenAhead = !!(await invoke('foreground_is_fullscreen'));
  } catch {
    fullscreenAhead = false;
  }
  return fullscreenAhead;
}

/**
 * 输入框里有没有还没提交的内容。
 *
 * 有就不自动收缩 —— 用户敲了一半正要去别处查个东西，窗口不该带着他的字一起消失。
 *
 * 判据是**输入框里有没有东西**，不是「最近有没有敲键盘」：后者答的是「此刻在不在
 * 打字」，敲完停手一会儿保护就失效了，正好漏掉「写了一半搁下不管」这个真实场景。
 * 也就是说这里读的是意图的**结果**，不是意图发生的时间。
 *
 * 不能用 `document.activeElement === #input` 代替 —— 输入框在启动时和每次添加后
 * 都会被 `focusInput()` 聚焦，那个判断几乎恒为真，会让自动收缩永久失效。
 *
 * 清空或提交之后不需要任何额外的唤醒动作：定时器到点会把自己重新排程，那本身就是
 * 一次轮询，下一个 tick 就会恢复正常收缩。
 */
function hasPendingInput() {
  return !!inputEl && inputEl.value.trim().length > 0;
}

/**
 * 现在要不要拦住自动收缩？
 *
 * 两个条件，**都必须有**：
 *
 * 1. 输入框里有没提交的内容 —— 用户敲了一半搁下去别处，窗口不该带着他的字消失。
 *    这一条一直挡着，直到他自己清空或提交。
 * 2. 刚刚在输入框里动过手（`INPUT_GRACE_MS` 之内）—— **回车提交之后那一下的余地**。
 *
 * 第二条看着像冗余，其实不是：回车提交走的是 `input.value = ''`（[ui/todos.js](ui/todos.js)
 * 的 keydown 处理），**程序化赋值不触发 `input` 事件**，那一刻输入框判据会瞬间翻成
 * 「没有内容」。而收缩定时器一旦被 mouseleave 武装过，就会一直每 500ms 重排程轮询
 * 下去（见 scheduleCollapse），所以「清空」到「收缩」之间可能只隔几十毫秒 ——
 * 表现就是用户刚按下回车，窗口立刻收走了。
 *
 * 回车本身是 keydown，会刷新第二条，于是「正在连续录入」的整段时间都被护住；
 * 停手之后宽限期一过，正常收缩恢复。
 */
function holdWindowOpen() {
  if (hasPendingInput()) return true;
  return performance.now() - lastInputAt < INPUT_GRACE_MS;
}

function cancelCollapse() {
  if (collapseTimer) {
    clearTimeout(collapseTimer);
    collapseTimer = null;
  }
}

/** 展开到正常尺寸。未吸附时 Rust 侧会直接返回，不需要前端先判断。 */
function expand(reason) {
  cancelCollapse();
  enqueue(() => invoke('set_edge_collapsed', { collapsed: false, reason: why(reason) }));
}

/** 收缩成边缘感应条。**只在已吸附时有效**，没吸附时 Rust 侧是 no-op。 */
function setCollapsed(collapsed, reason) {
  const r = why(reason);
  enqueue(() => invoke('set_edge_collapsed', { collapsed, reason: r }));
}

/**
 * 吸附了就缩成感应条，没吸附就隐藏窗口。分流整个在 Rust 侧做。
 *
 * 和标题栏那个 — 按钮是同一个行为，所以走同一个入口。
 */
function collapseToEdge(reason) {
  const r = why(reason);
  enqueue(() => invoke('minimize_to_edge', { reason: r }));
}

/**
 * 用户开始拖窗口了。
 *
 * `data-tauri-drag-region` 由 Tauri 注入的脚本处理，JS 侧本来一个监听器都没有；
 * 我们只需要知道「拖动开始了」这一件事，因为拖动是**唯一**一段前端收不到鼠标
 * 事件的时间 —— 原生移动循环会把它们全吞掉。
 *
 * 后果就是：拖动开始时那次 mouseleave 武装的收缩定时器，会在用户还按着鼠标的时候
 * 照常到点，把窗口从他手里收成感应条（吸附状态下 minimize_to_edge 走的是收缩
 * 分支而不是隐藏，所以看起来像「程序直接关闭」）。
 */
function beginWindowDrag() {
  if (windowDragging) return;
  windowDragging = true;
  dragSafetyTimer = setTimeout(endWindowDrag, DRAG_SAFETY_MS);
}

/**
 * 拖动结束了。
 *
 * 清位信号有好几路（mouseup / pointerup / 指针已松开 / 窗口失焦 / 兜底超时），
 * 因为正常那一路（mouseup）恰恰是最不可靠的：走原生移动循环时它不会到达页面。
 * 任何一路先到都算数。
 */
function endWindowDrag() {
  if (!windowDragging) return;
  windowDragging = false;
  if (dragSafetyTimer) {
    clearTimeout(dragSafetyTimer);
    dragSafetyTimer = null;
  }
}

function scheduleCollapse() {
  cancelCollapse();
  collapseTimer = setTimeout(() => {
    collapseTimer = null;

    // 设置面板开着、输入框那点事还没完（见 holdWindowOpen）、或者用户正拖着
    // 窗口时不要收缩。
    //
    // 这里是**重新排程**而不是直接 return：这条 timer 链是自动收缩唯一的触发源，
    // 直接返回等于把它掐断 —— 用户关掉设置面板 / 清空输入框 / 松手之后，窗口就
    // 再也不自动收缩了。重排程本身也构成了对这几个条件的轮询，不需要另开定时器。
    if (state.ui.settingsOpen || holdWindowOpen() || windowDragging) {
      scheduleCollapse();
      return;
    }

    // 走 minimize_to_edge，**不是** set_edge_collapsed(true)。
    //
    // 后者在窗口没吸附时会直接 return（window.rs 里那句「没吸附就不该有收缩
    // 行为」），于是「鼠标离开就最小化」实际只在贴着屏幕边缘时才成立 ——
    // 窗口停在屏幕中间时按什么都没反应，这正是之前那个 bug。
    //
    // minimize_to_edge 自己分流：吸附了收缩成感应条，没吸附就隐藏窗口。
    collapseToEdge('mouseleave-timer');
  }, COLLAPSE_DELAY);
}

/**
 * 窗口的活跃状态变了。
 *
 * 判定源只有 Rust 的 WindowEvent::Focused 一处，不用 DOM 的 focus/blur ——
 * `document.hasFocus()` 受 webview 内部焦点（点到输入框、点到按钮）影响，
 * 和「这个窗口是不是系统当前活动窗口」不是一回事。
 *
 * 它管的是「切走就收缩、切回来就展开」。**悬停门控已经不看它了** —— 那条判据
 * 会让感应条自锁（缩在屏幕边上的时候它恰恰不可能是活动窗口），是「鼠标碰上去
 * 没反应」的根因。现在看 fullscreenAhead，见 refreshHoverBlock。
 */
function setActive(next) {
  active = next;

  cancelCollapse();

  if (next) {
    // 用户切回来了：保持出现
    expand('focus-gained');
    return;
  }

  // 切到别的程序了：若吸附则立刻收缩。
  //
  // 但输入框里还压着没提交的内容、或者刚提交完还在宽限期里时**不收缩** ——
  // 用户常常是敲了一半切去别处抄点东西再回来，把窗口收走等于把他的字一起收走；
  // 回车提交之后立刻收走则等于不让人连续录入。同理，正拖着窗口时也不收，
  // 那是同一次操作被拆成了两个事件。
  if (holdWindowOpen() || windowDragging) return;

  // 复用 set_edge_collapsed 而不是 minimize_to_edge —— 后者在没吸附时会
  // 把窗口整个藏起来，那是灾难；前者没吸附时什么都不做，正合需求。
  setCollapsed(true, 'focus-lost');
}

/** 置顶按钮的视觉状态要跟着真实设置走，而不是跟着点击动作走。 */
export function renderWindowUI() {
  const on = !!state.data.settings?.alwaysOnTop;
  const pin = document.getElementById('btn-pin');

  pin.setAttribute('aria-pressed', String(on));
  pin.title = on ? '取消置顶' : '窗口置顶';
  pin.style.opacity = on ? '1' : '0.5';
}

export function initWindow() {
  const { listen } = window.__TAURI__.event;

  // Rust 侧要求我们把输入框聚焦
  listen('mino://focus-input', () => focusInput());
  listen('mino://open-settings', () => openSettings());
  listen('mino://focus-changed', (e) => setActive(!!e.payload));

  inputEl = document.getElementById('input');

  // 记录「刚刚在输入框里动过手」，给 holdWindowOpen 的第二条用。
  // 必须挂在输入框上、且必须收 keydown：回车提交走的是程序化清空，
  // 那条路径不触发 input 事件，只有 keydown 能捕捉到。
  // compositionstart 是中文输入法的补充 —— 拼音候选阶段不触发 input。
  for (const ev of ['input', 'keydown', 'compositionstart']) {
    inputEl.addEventListener(ev, () => {
      lastInputAt = performance.now();
    });
  }

  document.getElementById('btn-pin').addEventListener('click', () => {
    const next = !state.data.settings?.alwaysOnTop;
    call('set_always_on_top', { value: next }).catch(() => {});
  });

  document.getElementById('btn-min').addEventListener('click', () => {
    // 已吸附就收缩成边缘感应条，没吸附就隐藏窗口。这个分流整个在 Rust 侧做
    // （minimize_to_edge），前端不先问一次再调一次 —— 中间那个往返窗口期里
    // 状态可能已经变了（比如刚按过 Esc），前端会拿着过期判断做错事。
    //
    // 用户主动按的，不受输入框内容或拖动状态的影响。
    collapseToEdge('min-btn');
  });

  document.getElementById('btn-hide').addEventListener('click', () => {
    // ✕ 只隐藏窗口，不退出进程 —— 退出只能走托盘菜单。同样是用户主动动作。
    call('hide_window', { reason: why('hide-btn') }).catch(() => {});
  });

  // 拖动窗口的开始 / 结束。置位看的是事件目标带不带拖拽区属性，用 closest
  // 是因为 .title 盖住了 .titlebar 中段，光判断目标自身会漏掉一部分。
  // 注意 Tauri 的注入脚本也监听 mousedown，两边各收各的、互不干扰。
  document.addEventListener('mousedown', (e) => {
    if (e.target?.closest?.('[data-tauri-drag-region]')) beginWindowDrag();
  });

  for (const ev of ['mouseup', 'pointerup', 'pointercancel']) {
    document.addEventListener(ev, endWindowDrag);
  }
  // 原生移动循环结束之后，页面收到的第一个事件就是它 —— 指针已经松开。
  // 拖动期间收不到事件，所以这条不会误清位。
  document.addEventListener('mousemove', (e) => {
    if (e.buttons === 0) endWindowDrag();
  });
  window.addEventListener('blur', endWindowDrag);

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;

    if (state.ui.settingsOpen) {
      closeSettings();
      return;
    }

    // 吸附状态下先脱离边缘，否则窗口收成一条缝后很难再抓回来。
    // 用户主动按的，不受输入框内容或拖动状态的影响。
    invoke('unsnap_window')
      .catch(() => {})
      .finally(() => call('hide_window', { reason: why('escape') }).catch(() => {}));
  });

  // 边缘吸附：窗口缩成感应条后，鼠标进入即展开。
  //
  // 门控条件是「前台有没有全屏程序」，**不是**「MiNo 是不是活动窗口」。
  // 后者会自锁（见 refreshHoverBlock），是「感应条碰不出来」的根因。
  //
  // 判据要问 Rust，所以这里是异步的：mouseenter → 查一次 → 再决定展开。多出来
  // 的那几毫秒对 4px 感应条的手感没有影响。
  document.documentElement.addEventListener('mouseenter', () => {
    refreshHoverBlock().then((blocked) => {
      if (!blocked) expand('hover');
    });
  });
  document.documentElement.addEventListener('mouseleave', (e) => {
    // 鼠标键还按着 = 用户在拖东西（拖窗口，或者拖待办排序），不是「离开」。
    //
    // 这道判断不依赖任何状态机，是 windowDragging 之外的冗余保险：拖窗口走的是
    // 同一段代码，而真正的根因还没被证实，多盖一层比少盖一层划算。它只做抑制 ——
    // 即使 WebView2 下 e.buttons 不可靠，也只是退回没有这道保险的行为。
    if (e.buttons !== 0) return;

    // 全屏程序压着时不收缩：这种情况下上面压根没展开过，再走一遍 minimize_to_edge
    // 会在「没吸附」分支把窗口整个藏掉 —— 那是同一类骚扰。用上一次 mouseenter
    // 查到的值，不在这儿再问一次：收缩本来就还有 500ms 的延迟，再插一次往返
    // 只会让时序更难讲清楚，而这一小段时间里前台换人的可能性可以忽略。
    if (fullscreenAhead) return;

    scheduleCollapse();
  });
}
