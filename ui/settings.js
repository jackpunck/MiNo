// 设置面板：字体选择、背景、快捷键与数据目录信息。

import { state, call, invoke, toast } from './state.js';
import * as fonts from './fonts.js';
import { checkUpdate, openReleasePage } from './update.js';
import {
  TEXT_COLORS,
  applyTextColor,
  applyMaskOpacity,
  applyBlur,
  DEFAULT_TEXT,
  DEFAULT_MASK,
  DEFAULT_BLUR,
} from './appearance.js';

let filter = '';

/** 当前版本号，由 renderInfo() 从 runtime_info 填。检查更新时用来比对。 */
let currentVersion = '';

// ---------------------------------------------------------------------------
// 外观：文字颜色、遮罩、模糊
// ---------------------------------------------------------------------------

function buildSwatches() {
  const box = document.getElementById('color-swatches');
  if (!box || box.childElementCount) return;

  const frag = document.createDocumentFragment();

  for (const c of TEXT_COLORS) {
    const el = document.createElement('button');
    el.type = 'button';
    el.className = 'swatch';
    el.dataset.color = c.value;
    el.setAttribute('role', 'radio');
    el.setAttribute('aria-checked', 'false');
    el.setAttribute('aria-label', c.label);
    el.title = c.label;
    // 值来自本地常量而非用户输入，用 style 是安全的
    el.style.background = c.value;

    el.addEventListener('click', async () => {
      // 先乐观生效，视觉上零延迟；call() 回来后的 render() 会再写一次（幂等）
      applyTextColor(c.value);
      renderAppearancePanel();

      try {
        await call('set_text_color', { color: c.value });
      } catch {
        // 失败就回到真实设置，别把界面留在一个没落盘的颜色上
        applyTextColor(state.data.settings?.textColor);
        renderAppearancePanel();
      }
    });

    frag.append(el);
  }

  box.replaceChildren(frag);
}

/**
 * 绑定一个滑块。
 *
 * `input` 事件在一次拖动里能来几十个，每个都走 command 就是几十次
 * 「读盘 → 改 → fsync 整份 AppData」。所以拆成两段：input 只改 CSS 变量给即时
 * 反馈，change（松手 / 键盘调整结束）才落盘。
 */
function bindSlider(id, valueId, format, toValue, apply, commit) {
  const el = document.getElementById(id);
  const label = document.getElementById(valueId);

  el.addEventListener('input', () => {
    const v = Number(el.value);
    label.textContent = format(v);
    apply(toValue(v));
  });

  el.addEventListener('change', () => {
    commit(toValue(Number(el.value)));
  });
}

/** 打开面板时把控件同步到真实设置。applyAppearance 负责反向的 CSS 同步。 */
function renderAppearancePanel() {
  const s = state.data.settings || {};

  const mask = Number.isFinite(s.maskOpacity) ? s.maskOpacity : DEFAULT_MASK;
  const maskEl = document.getElementById('mask-opacity');
  maskEl.value = String(Math.round(mask * 100));
  document.getElementById('mask-opacity-value').textContent = `${maskEl.value}%`;

  const blur = Number.isFinite(s.blur) ? s.blur : DEFAULT_BLUR;
  const blurEl = document.getElementById('blur-level');
  blurEl.value = String(Math.round(blur));
  document.getElementById('blur-level-value').textContent = `${blurEl.value}px`;

  // 显示出来的必须是归一化后的当前色（可能与用户点的不完全一致）
  const current = parseCurrentColor(s.textColor);
  for (const el of document.querySelectorAll('#color-swatches .swatch')) {
    el.setAttribute('aria-checked', String(el.dataset.color === current));
  }
}

function parseCurrentColor(value) {
  return TEXT_COLORS.some((c) => c.value === value) ? value : DEFAULT_TEXT;
}

// ---------------------------------------------------------------------------
// 字体列表
// ---------------------------------------------------------------------------

/**
 * 当前选中项是否匹配这个字体选项。
 *
 * 直接比对整条 CSS 链：`fontFamily` 是我们选择时原样存进去的 `option.css`，
 * 由 Rust 的 chain_for 确定性生成，所以两者必然逐字符相等。
 * 比解析字体链的第一段可靠得多 —— 字体族名本身可以含逗号。
 */
function isActive(option) {
  const s = state.data.settings || {};
  return !!s.fontFamily && option.css === s.fontFamily;
}

function currentFontLabel() {
  const s = state.data.settings || {};

  const option = (state.ui.fontList || []).find((o) => o.css === s.fontFamily);
  if (option) return option.name;

  if (s.fontId) {
    const found = (s.customFonts || []).find((f) => f.id === s.fontId);
    if (found) return found.label;
  }

  return '默认字体';
}

function fontItem(option) {
  const el = document.createElement('div');
  el.className = 'font-item' + (isActive(option) ? ' active' : '');
  el.setAttribute('role', 'option');
  el.setAttribute('aria-selected', String(isActive(option)));

  // 预览直接用该字体渲染 —— WebView2 已经持有全部系统字体，无需加载任何文件
  el.style.fontFamily = option.css;
  el.title = option.name;

  const label = document.createElement('span');
  // 用户文件名是不可信字符串，必须 textContent
  label.textContent = option.name;
  el.append(label);

  if (!option.builtin && option.id) {
    const del = document.createElement('span');
    del.className = 'font-item-del';
    del.textContent = '×';
    del.title = '移除这个字体';
    del.addEventListener('click', async (e) => {
      e.stopPropagation(); // 别把点击当成选择
      try {
        await fonts.removeFont(option.id);
        renderFontList();
      } catch {
        /* 已经提示过 */
      }
    });
    el.append(del);
  }

  el.addEventListener('click', async () => {
    try {
      await fonts.chooseFont(option);
      renderFontList();
      updateCurrentLabel();
    } catch {
      /* 已经提示过 */
    }
  });

  return el;
}

export function renderFontList() {
  const box = document.getElementById('font-list');
  const q = filter.trim().toLowerCase();

  const all = state.ui.fontList || [];
  const matched = q
    ? all.filter(
        (o) =>
          o.name.toLowerCase().includes(q) || String(o.css).toLowerCase().includes(q),
      )
    : all;

  const custom = matched.filter((o) => !o.builtin);
  const builtin = matched.filter((o) => o.builtin);

  const frag = document.createDocumentFragment();

  if (matched.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'font-item';
    empty.style.color = 'rgba(255,255,255,.38)';
    empty.textContent = q ? '没有匹配的字体' : '没有可用字体';
    frag.append(empty);
  }

  if (custom.length) {
    const head = document.createElement('div');
    head.className = 'font-group';
    head.textContent = '已导入';
    frag.append(head);
    for (const o of custom) frag.append(fontItem(o));
  }

  if (builtin.length) {
    const head = document.createElement('div');
    head.className = 'font-group';
    head.textContent = `系统字体 (${builtin.length})`;
    frag.append(head);
    for (const o of builtin) frag.append(fontItem(o));
  }

  box.replaceChildren(frag);
}

function updateCurrentLabel() {
  document.getElementById('font-current').textContent = currentFontLabel();
  // 用当前字体渲染这一行，给用户一个直观的确认
  document.getElementById('font-current').style.fontFamily =
    state.data.settings?.fontFamily || fonts.DEFAULT_CHAIN;
}

// ---------------------------------------------------------------------------
// 面板
// ---------------------------------------------------------------------------

export function openSettings() {
  document.getElementById('settings').hidden = false;
  state.ui.settingsOpen = true;

  // 首次打开时才拉字体列表：枚举系统字体有一定开销，不该拖慢启动
  if (!state.ui.fontsLoaded) {
    fonts
      .loadFontList()
      .then(() => renderFontList())
      .catch(() => toast('字体列表加载失败'));
  } else {
    renderFontList();
  }

  updateCurrentLabel();
  renderAppearancePanel();
  renderInfo();
}

export function closeSettings() {
  document.getElementById('settings').hidden = true;
  state.ui.settingsOpen = false;
}

export function toggleSettings() {
  if (state.ui.settingsOpen) closeSettings();
  else openSettings();
}

async function renderInfo() {
  try {
    const info = await invoke('runtime_info');

    const shortcut = document.getElementById('shortcut-hint');
    shortcut.textContent = info.shortcutActive
      ? `${info.shortcut}（已生效）`
      : `${info.shortcut} 未能注册，可能被其他程序占用 —— 请改用托盘图标`;

    const dir = document.getElementById('data-dir');
    dir.textContent = info.dataDir || '—';
    dir.title = info.dataDir || '';

    // 版本号的唯一来源是 Rust 的 CARGO_PKG_VERSION，前端不再存第二份
    const version = document.getElementById('app-version');
    version.textContent = `MiNo v${info.version}`;
    currentVersion = info.version;
  } catch {
    /* 信息区是锦上添花，失败就不显示 */
  }
}

export function initSettings() {
  document.getElementById('btn-settings').addEventListener('click', toggleSettings);
  document.getElementById('btn-settings-close').addEventListener('click', closeSettings);

  // 外观控件。色块只需建一次，之后靠 renderAppearancePanel 同步选中态。
  buildSwatches();
  // 落盘写成显式的 call('...') 字面量而不是把命令名当参数传：
  // tools/check-commands.mjs 靠正则扫这个字面量，传参形式会让这两个名字
  // 悄悄逃过「未注册 command」的静态校验。
  bindSlider(
    'mask-opacity',
    'mask-opacity-value',
    (v) => `${v}%`,
    (v) => v / 100, // 滑块用整数百分比，存的是 0–0.95
    applyMaskOpacity,
    (value) => call('set_mask_opacity', { value }).catch(() => {}),
  );
  bindSlider(
    'blur-level',
    'blur-level-value',
    (v) => `${v}px`,
    (v) => v,
    applyBlur,
    (value) => call('set_blur', { value }).catch(() => {}),
  );

  document.getElementById('font-search').addEventListener('input', (e) => {
    filter = e.target.value;
    renderFontList();
  });

  document.getElementById('btn-font-import').addEventListener('click', () => {
    document.getElementById('file-font').click();
  });

  document.getElementById('file-font').addEventListener('change', async (e) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // 允许连续选择同一个文件
    if (!file) return;

    const btn = document.getElementById('btn-font-import');
    btn.disabled = true;

    try {
      await fonts.importFontFile(file);
      await fonts.loadFontList();
      renderFontList();
      updateCurrentLabel();
    } catch {
      /* call() 已经弹过提示 */
    } finally {
      btn.disabled = false;
    }
  });

  document.getElementById('btn-font-reset').addEventListener('click', async () => {
    try {
      await fonts.resetFont();
      renderFontList();
      updateCurrentLabel();
    } catch {
      /* 已经提示过 */
    }
  });

  // 把当前字体盖到所有已有任务上。call() 返回的是完整快照，render 会自动重绘。
  document.getElementById('btn-font-apply-all').addEventListener('click', async (e) => {
    const btn = e.currentTarget;
    btn.disabled = true;

    try {
      const data = await call('apply_font_to_all');
      toast(`已把当前字体应用到 ${data.todos.length} 条任务`);
    } catch {
      /* call() 已经弹过提示 */
    } finally {
      btn.disabled = false;
    }
  });

  document.getElementById('btn-bg-reset').addEventListener('click', async () => {
    const { resetBackground } = await import('./background.js');
    try {
      await resetBackground();
    } catch {
      /* 已经提示过 */
    }
  });

  document.getElementById('btn-bg-import').addEventListener('click', () => {
    document.getElementById('file-bg').click();
  });

  // 检查更新。只在用户点的时候联网 —— 不做启动自动检查：那会让「启动就联网」
  // 成为常态，也会白白消耗 GitHub 对未认证请求的限流额度。
  document.getElementById('btn-check-update').addEventListener('click', () => {
    checkUpdate(currentVersion);
  });

  document.getElementById('btn-open-release').addEventListener('click', () => {
    openReleasePage();
  });
}
