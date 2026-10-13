// 应用状态与后端通信。
//
// Rust 是数据的权威来源：这个模块不做任何状态推演，只是把 command 返回的
// 完整快照存下来再通知订阅者重绘。前端因此永远不会和落盘的数据产生分歧。

const { invoke } = window.__TAURI__.core;

export { invoke };

export const state = {
  data: { version: 1, settings: {}, todos: [], archives: [] },
  ui: {
    settingsOpen: false,
    // 提醒编辑浮层开着吗。它和 settingsOpen 是并列的一道**自动收缩抑制闸**
    // （见 ui/window.js）：浮层开着时鼠标移出窗口不该把窗口收走。
    remindOpen: false,
    fontList: [],
    fontsLoaded: false,
  },
};

const listeners = new Set();

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function emit() {
  // 复制一份再遍历，避免订阅者在回调里取消订阅导致迭代出错
  for (const fn of [...listeners]) {
    try {
      fn(state);
    } catch (e) {
      console.error('渲染回调出错', e);
    }
  }
}

export function setData(data) {
  state.data = data;
  emit();
}

export function notify() {
  emit();
}

/**
 * 调用一个会修改数据的 command，并把返回的快照写回状态。
 * 失败时弹提示并抛出，让调用方决定是否要继续。
 */
export async function call(cmd, args) {
  try {
    const result = await invoke(cmd, args);

    if (result && typeof result === 'object' && Array.isArray(result.todos)) {
      setData(result);
    }
    return result;
  } catch (e) {
    toast(normalizeError(e));
    throw e;
  }
}

export function normalizeError(e) {
  if (typeof e === 'string') return e;
  if (e && typeof e.message === 'string') return e.message;
  return String(e);
}

let toastTimer = null;

export function toast(message, ms = 2600) {
  const el = document.getElementById('toast');
  if (!el) return;

  el.textContent = message;
  el.hidden = false;

  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.hidden = true;
  }, ms);
}
