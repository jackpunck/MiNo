// 提醒浮窗的渲染与交互。
//
// 这是一个**独立文档**，由 label 为 "reminder" 的那个窗口加载。所以：
//
//   - 它不加载 ui/window.js，主窗口那个挂在 document 上、不看 e.target 的全局 Esc
//     在这里不存在 —— 本文件里的 Esc 不需要 stopPropagation。
//   - 它不读用户配的便签配色与自定字体。浮窗属于**窗口 chrome**，用固定配色，
//     与 CLAUDE.md 第 7 条「用户的文字颜色只写 --note-*」是同一条分工。
//   - 位置、尺寸、显示、隐藏全部由 Rust 侧 `reminder.rs` 决定（它也负责自动收起）。
//     这个文件只管「把给过来的内容画出来」。
//
// 安全约束（规格 §20.1）：任务正文一律 textContent，绝不拼 innerHTML。

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

/** 每一条到点的任务。点它 → 收起浮窗并把主窗口叫到前面、高亮这一条。 */
function buildRow(item) {
  const li = document.createElement('li');
  li.className = 'item';

  const dot = document.createElement('span');
  dot.className = 'dot' + (item.mode === 'daily' ? ' daily' : '');
  dot.setAttribute('aria-hidden', 'true');

  const text = document.createElement('span');
  text.className = 'text';
  // 用户输入永远走 textContent
  text.textContent = item.text;
  text.title = item.text;

  li.append(dot, text);
  li.addEventListener('click', () => {
    // 失败也不提示：浮窗会自己按计时收起，用户不至于被卡住。
    // 而且 toast 在主窗口里，这会儿多半根本看不见。
    invoke('open_reminder', { id: item.id }).catch(() => {});
  });

  return li;
}

function render(payload) {
  const items = Array.isArray(payload?.items) ? payload.items : [];
  const more = Number(payload?.more) || 0;

  const frag = document.createDocumentFragment();
  for (const item of items) frag.append(buildRow(item));

  if (more > 0) {
    const rest = document.createElement('li');
    rest.className = 'more';
    rest.textContent = `还有 ${more} 条`;
    frag.append(rest);
  }

  document.getElementById('rem-list').replaceChildren(frag);
}

listen('mino://reminder', (e) => render(e.payload));

document.getElementById('rem-close').addEventListener('click', () => {
  invoke('dismiss_reminder').catch(() => {});
});

document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  invoke('dismiss_reminder').catch(() => {});
});
