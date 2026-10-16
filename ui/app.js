// 启动与装配。

import { call, subscribe, toast } from './state.js';
import { initTodos, renderList, focusInput } from './todos.js';
import { initBackground, applyCurrentBackground } from './background.js';
import { initSettings } from './settings.js';
import { initWindow, renderWindowUI } from './window.js';
import { applyCurrentFont } from './fonts.js';
import { applyAppearance } from './appearance.js';
import { isDragging } from './dnd.js';

// 和 ui/todos.js 一样，从全局的 __TAURI__ 拿（没有打包器，也没有 @tauri-apps/api）
const { listen } = window.__TAURI__.event;

function render() {
  // 拖动进行中不能重绘。renderList 会 replaceChildren，把正在拖的 li 从 DOM
  // 摘掉 —— 元素一移除，pointer capture 就被隐式释放，后续 pointerup 不再投递，
  // 于是 reorder_todos 永远不会被调用，用户看到一行卡在半路。
  //
  // 这里不需要记一笔「待补渲染」：拖动结束的每条路径都会再触发一次 render
  // （成功走 call 返回的 setData，取消走 notify），而 render 每次都从
  // state.data 重新读，不会漏掉拖动期间到达的其它变更。
  if (isDragging()) return;

  renderList();
  renderWindowUI();
  // 每次 command 返回都会走到这里，所以 applyAppearance 必须是幂等的
  applyAppearance(state.data.settings || {});
}

async function boot() {
  // 先把交互装上，这样即使后端初始化慢，界面也已经可点
  initTodos();
  initSettings();
  initBackground();
  initWindow();

  // 在首次加载前订阅，load_state 写回数据时就会自动完成第一轮渲染
  subscribe(render);

  // 别的窗口改了数据 → 重拉一份快照。
  //
  // 目前唯一的来源是提醒浮窗：点它的 × 会把卡片上「仅一次」的任务标记完成
  // （见 `reminder::dismiss_popup`）。**这里必须自己重拉**，而不是指望别的路径顺带
  // 刷新 —— `state.data` 只在「自己调的那个 command 返回了快照」时才更新，别的窗口
  // 写的数据主窗口一无所知。这正是「Rust 是唯一权威」的代价，也是它的好处：
  // 前端不推演状态，只是重读一遍。
  listen('mino://todos-changed', () => {
    // 失败时 call 内部已经弹过提示了，这里没有别的可做
    call('load_state').catch(() => {});
  });

  try {
    await call('load_state');
  } catch (e) {
    toast(`数据加载失败：${e}`);
    return;
  }

  // 由 body.loading 把内容挡住，等字体就位再显示。
  //
  // 系统字体其实不需要这一步（boot.js 已经用缓存抢在首次绘制前设好了），
  // 但导入字体做不到 —— 它的字节每次启动都要重新注册，只能等异步返回。
  // 不挡的话会先按回退字体画一帧，再肉眼可见地跳变。
  try {
    await applyCurrentFont();
  } catch (e) {
    console.warn('字体应用失败', e);
  }

  reveal();
  await applyCurrentBackground();
  render();

  focusInput();
}

function reveal() {
  clearTimeout(revealTimer);
  document.body.classList.remove('loading');
}

// 兜底：万一行程里出了没预料到的异常，也不能让窗口永远空着
const revealTimer = setTimeout(reveal, 2000);

boot();
