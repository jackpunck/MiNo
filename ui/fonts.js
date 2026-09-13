// 字体：系统字体枚举、自定义字体导入、应用到便签正文。
//
// 导入字体的加载方式是 `new FontFace(family, ArrayBuffer)`，而不是
// `@font-face { src: url(...) }`。后者要么走 file:// （被 WebView2 的
// origin 隔离挡掉），要么需要开启 asset protocol 并放宽 CSP，而且字体请求
// 是 CORS 模式的，跨 origin 能否通过并不确定。直接喂字节不发起任何请求，
// 因此不需要任何安全配置变更。

import { invoke, call, toast, state } from './state.js';

export const DEFAULT_CHAIN =
  '"Segoe UI", "Microsoft YaHei", "微软雅黑", system-ui, sans-serif';

/** id → true，避免重复构造 FontFace */
const loaded = new Map();

/**
 * 导入字体在 Rust 侧被分配的名字。
 * 与 `state.rs` 的 `FONT_FAMILY_PREFIX` 必须逐字一致 —— 前端靠它构造 FontFace，
 * 对不上就会静默回退到链尾的 sans-serif（表现成「字体自己变了」）。
 */
function familyOf(id) {
  return `MiNo ${id.slice(0, 8)}`;
}

/**
 * 确保导入字体的字节已经注册进文档。
 * 每次启动都需要重新做一遍 —— 字体字节不会跨进程缓存。
 */
export async function ensureFont(id) {
  if (!id || loaded.has(id)) return;

  const buf = await invoke('read_font', { id });
  const family = familyOf(id);
  const face = new FontFace(family, buf);

  await face.load();
  document.fonts.add(face);
  loaded.set(id, true);
}

/**
 * 这个导入字体的字节是否已经注册进文档。
 *
 * 渲染每条任务时用它判断：指向导入字体但还没注册的，写进 CSS 也只会静默回退到
 * 链尾的 sans-serif，不如直接改用全局字体 —— 结果确定、可预期。
 */
export function isLoaded(id) {
  return loaded.has(id);
}

/** 把字体链写进 CSS 变量，并同步到 boot.js 读取的那份缓存。 */
export function applyChain(chain) {
  const value = chain && chain.trim() ? chain : DEFAULT_CHAIN;
  document.documentElement.style.setProperty('--note-font', value);

  try {
    localStorage.setItem('mino.font', value);
  } catch {
    /* 缓存写不进去不影响使用 */
  }
}

/**
 * 所有被引用到的导入字体 id：全局字体 + 每条任务自己记着的那个。
 *
 * 任务各自带 fontId 之后，光加载全局字体的字节已经不够了 —— 一条用字体 X、
 * 而当前全局字体是 Y 的任务，如果 X 从没被注册进来，它渲染时会静默回退。
 */
function fontIdsInUse() {
  const s = state.data.settings || {};
  const ids = new Set();

  if (s.fontId) ids.add(s.fontId);
  for (const t of state.data.todos || []) {
    if (t.fontId) ids.add(t.fontId);
  }
  return ids;
}

/** 应用当前设置里的字体。启动时和每次改动后都调用。 */
export async function applyCurrentFont() {
  const s = state.data.settings || {};

  // 每个字体单独 catch：一个坏了不该连累其它，更不该把全局字体一起冲掉。
  for (const id of fontIdsInUse()) {
    try {
      await ensureFont(id);
    } catch (e) {
      if (id !== s.fontId) {
        // 只是某几条任务用的导入字体丢了 —— 让那几条回退即可。
        // 数据层的清理在 remove_font / prune_missing 里，这里只是渲染兜底。
        console.warn('任务字体加载失败', id, e);
        continue;
      }

      // 全局字体本身没加载出来，退回默认。
      // 它影响的是输入框和所有没盖过章的任务，范围是全局的，所以要改 --note-font。
      // 注意这里**不能**对 per-todo 的失败也这么做：那会把所有任务一起冲掉。
      toast(`字体加载失败，已使用默认字体：${e}`);
      applyChain(DEFAULT_CHAIN);
      return;
    }
  }

  applyChain(s.fontFamily);
}

export async function loadFontList() {
  const list = await invoke('list_fonts');
  state.ui.fontList = list;
  state.ui.fontsLoaded = true;
  return list;
}

/** 选择字体。option 来自 list_fonts 返回的结构。 */
export async function chooseFont(option) {
  if (option.id) {
    await ensureFont(option.id);
  }
  const data = await call('set_font', { family: option.css, id: option.id ?? null });
  applyChain(data.settings.fontFamily);
}

export async function resetFont() {
  const data = await call('reset_font');
  applyChain(data.settings.fontFamily);
}

export async function removeFont(id) {
  const data = await call('remove_font', { id });
  loaded.delete(id);
  applyChain(data.settings.fontFamily);
}

/** 把 File 读成 base64（去掉 data URL 前缀）。 */
export function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result);
      const comma = result.indexOf(',');
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.onerror = () => reject(reader.error || new Error('读取文件失败'));
    reader.readAsDataURL(file);
  });
}

/** 导入一个字体文件并立即启用。 */
export async function importFontFile(file) {
  const data = await fileToBase64(file);
  const snapshot = await call('import_font', { name: file.name, data });

  await applyCurrentFont();
  toast(`已导入并启用：${file.name}`);
  return snapshot;
}

export async function importBackgroundFile(file) {
  const data = await fileToBase64(file);
  return call('upload_background', { data });
}
