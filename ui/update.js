// 检查更新。
//
// **只检查并提示，不下载、不安装。** 走 Tauri 官方的 updater 插件需要一对
// minisign 密钥、在 CI 里配签名密钥、还要维护一份 latest.json —— 为了「看看
// 有没有新版」不值当。
//
// 这是 MiNo 唯一会主动联网的地方：只有用户点了「检查更新」才会请求一次
// GitHub 的公开 API，只读、不发送任何本地数据、不落盘任何东西。

import { invoke } from './state.js';
import { isNewer } from './version.js';

const RELEASES_API =
  'https://api.github.com/repos/jackpunck/MiNo/releases/latest';

/** 超时。挂太久不如直接告诉用户失败了。 */
const TIMEOUT_MS = 8000;

/** 防止连点：连点不但没意义，还会白白烧掉未认证请求的限流额度（60 次/小时）。 */
let checking = false;

/** 最近一次查到的远端 tag，供「前往下载」按钮使用。 */
let latestTag = '';

function setStatus(text, kind) {
  const el = document.getElementById('update-status');
  el.textContent = text;
  el.dataset.kind = kind || '';
}

/** 取最新 release 的 tag。失败时抛出可直接展示的中文错误。 */
async function fetchLatestTag() {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);

  try {
    // ⚠️ **不要给这个请求加任何自定义头**（尤其是 X-GitHub-Api-Version）。
    // GET + 无自定义头才是 CORS 的「简单请求」，不会触发 OPTIONS 预检；一旦
    // 加了不在 safelist 里的头，浏览器会先发一次预检，而那次能不能过
    // 不在我们控制范围内。
    const res = await fetch(RELEASES_API, { signal: ctl.signal });

    // 仓库一个 release 都没发时，这个接口固定返回 404 且 body 是
    // {"message":"Not Found"} —— 必须单独识别，不能当成网络故障
    if (res.status === 404) {
      throw new Error('GitHub 上还没有已发布的版本');
    }
    // 未认证请求按 IP 限流 60 次/小时，超了是 403 或 429
    if (res.status === 403 || res.status === 429) {
      throw new Error('查询过于频繁（GitHub 限流），请过一会儿再试');
    }
    if (!res.ok) {
      throw new Error(`检查更新失败（HTTP ${res.status}）`);
    }

    const body = await res.json();
    if (!body || typeof body.tag_name !== 'string' || !body.tag_name) {
      throw new Error('GitHub 返回的数据看不懂');
    }
    return body.tag_name;
  } finally {
    clearTimeout(timer);
  }
}

function describeError(e) {
  if (e && e.name === 'AbortError') {
    return '检查更新超时，请稍后再试';
  }
  if (e instanceof TypeError) {
    // fetch 在网络层失败时统一抛 TypeError —— 断网、DNS、被 CSP/CORS 拦下都走这里
    return '检查更新失败：连不上 GitHub';
  }
  return e && e.message ? e.message : String(e);
}

/** 「检查更新」按钮的处理。结果写在面板的状态行里，不用 toast（toast 会飘走）。 */
export async function checkUpdate(currentVersion) {
  if (checking) return;

  // renderInfo() 是异步的，理论上用户能在它回来之前就点到按钮。这时版本号还是
  // 空串，比对结果会莫名其妙 —— 与其显示一个错的结论，不如让他再点一次。
  if (!currentVersion) {
    setStatus('正在读取版本号，请稍后再试');
    return;
  }

  checking = true;

  const btn = document.getElementById('btn-check-update');
  const openBtn = document.getElementById('btn-open-release');
  const original = btn.textContent;

  btn.disabled = true;
  btn.textContent = '检查中…';
  openBtn.hidden = true;
  setStatus('正在连接 GitHub…');

  try {
    latestTag = await fetchLatestTag();

    if (isNewer(latestTag, currentVersion)) {
      setStatus(`发现新版本 ${latestTag}（当前 v${currentVersion}）`, 'new');
      openBtn.hidden = false;
    } else {
      setStatus(`已是最新版本 v${currentVersion}`, 'ok');
    }
  } catch (e) {
    setStatus(describeError(e), 'error');
  } finally {
    checking = false;
    btn.disabled = false;
    btn.textContent = original;
  }
}

/** 「前往下载」：交给系统默认浏览器，不要在 webview 里导航（那会顶掉整个界面）。 */
export async function openReleasePage() {
  if (!latestTag) return;

  try {
    await invoke('open_release_page', { tag: latestTag });
  } catch (e) {
    setStatus(e && e.message ? e.message : String(e), 'error');
  }
}
