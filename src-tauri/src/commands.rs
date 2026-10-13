//! 暴露给前端的 Tauri command。
//!
//! 约定：所有会修改数据的 command 都返回完整的 `AppData`，前端拿到后整体重绘。
//! 这样前端不需要自己推演状态变化，也就不会和落盘的数据产生分歧。
//!
//! 标了 `(async)` 的 command 会在线程池上执行 —— 它们都做文件 IO，
//! 放在主线程上会卡住窗口。

use std::os::windows::process::CommandExt;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

use base64::Engine;
use log::{info, warn};
use serde::Serialize;
// `emit` 在 Emitter trait 上，不在 Manager 上 —— 少了它 `app.emit(...)` 是 E0599。
use tauri::{ipc::Response, AppHandle, Emitter};

use crate::fonts;
use crate::state::{self, AppData, Position};
use crate::window;

/// 背景图片的体积上限。
const MAX_IMAGE_BYTES: usize = 32 * 1024 * 1024;

/// 串行化「读 → 改 → 写」。
///
/// 每个 command 都标了 `#[tauri::command(async)]`，Tauri 会把它们派发到多线程
/// 运行时上，所以两次 invoke 是真并行的。而 `state::load` 和 `state::save` 是两个
/// 独立步骤，且落盘写的是整份 `AppData`：两个命令同时从同一份快照出发，后落盘的
/// 那个会把前一个的改动整份覆盖掉。前端有若干不 await 就连发的调用（勾选框、
/// 连按两次回车），所以这不是理论问题。
///
/// 锁必须一直持到 `save` 返回 —— 松手太早就等于没锁。
/// 临界区里含 fsync，不能从主线程进来；拖动窗口那条路径已经把落盘丢到后台线程了
/// （见 `window::save_position_throttled`）。锁中毒时直接取回数据继续用：这个应用
/// 宁可带着上一位的状态往前走，也不该在一次 panic 之后彻底打不开。
static DATA_LOCK: Mutex<()> = Mutex::new(());

// ---------------------------------------------------------------------------
// 辅助
// ---------------------------------------------------------------------------

fn data_guard() -> std::sync::MutexGuard<'static, ()> {
    DATA_LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

/// 读取当前状态，顺带执行跨日检查与排序。
fn current(app: &AppHandle) -> AppData {
    let _guard = data_guard();

    let mut data = state::load(app);

    if state::rollover(&mut data) > 0 {
        if let Err(e) = state::save(app, &data) {
            warn!("跨日归档后保存失败: {e}");
        }
    }

    state::sort_todos(&mut data.todos);
    data
}

/// 读 → 改 → 排序 → 落盘，返回最新状态。
fn mutate<F>(app: &AppHandle, f: F) -> Result<AppData, String>
where
    F: FnOnce(&mut AppData) -> Result<(), String>,
{
    let _guard = data_guard();

    let mut data = state::load(app);
    state::rollover(&mut data);
    f(&mut data)?;
    state::sort_todos(&mut data.todos);
    state::save(app, &data)?;
    Ok(data)
}

// ---------------------------------------------------------------------------
// 给后台线程用的入口
//
// `crate::reminder` 的轮询线程需要读数据、也需要写「今天已经提醒过」。它**必须**
// 走上面这两个入口（它们持有 DATA_LOCK），而不是自己 `state::load` + `state::save`
// —— 那会把「读 → 改 → 写」的临界区拆开，和正在跑的 command 互相整份覆盖。
//
// ⚠️ **这两个函数要顺序调用，不能嵌在同一个 guard 里**：DATA_LOCK 是普通的非重入
// `Mutex<()>`，嵌套直接死锁。所以它们是两个独立的入口，而不是「一个入口里回调两次」。
// ---------------------------------------------------------------------------

/// 读一份当前状态快照（含跨日检查与排序）。跑在调用方的线程上。
pub(crate) fn read_state(app: &AppHandle) -> AppData {
    current(app)
}

/// 把这批任务的 `reminded_on` 记成今天。
///
/// 拒绝空列表：没有要写的就别去动盘 —— 那次落盘带着一次 fsync，而且会让整个
/// 文件重写一遍。
pub(crate) fn mark_reminded(
    app: &AppHandle,
    ids: &[String],
    today: &str,
) -> Result<(), String> {
    if ids.is_empty() {
        return Ok(());
    }

    let ids = ids.to_vec();
    let today = today.to_string();
    mutate(app, move |data| {
        for todo in data.todos.iter_mut() {
            if ids.iter().any(|i| i == &todo.id) {
                todo.reminded_on = today.clone();
            }
        }
        Ok(())
    })
    .map(|_| ())
}

fn new_id(prefix: &str) -> String {
    use std::sync::atomic::AtomicU64;
    static SEQ: AtomicU64 = AtomicU64::new(0);
    // 毫秒 + 自增序号：同一毫秒内连续添加也不会撞 id
    format!(
        "{prefix}_{}_{}",
        state::now_ms(),
        SEQ.fetch_add(1, Ordering::Relaxed)
    )
}

fn decode_b64(payload: &str) -> Result<Vec<u8>, String> {
    base64::engine::general_purpose::STANDARD
        .decode(payload)
        .map_err(|e| format!("数据解码失败: {e}"))
}

// ---------------------------------------------------------------------------
// 状态读写
// ---------------------------------------------------------------------------

#[tauri::command(async)]
pub fn load_state(app: AppHandle) -> AppData {
    current(&app)
}

#[tauri::command(async)]
pub fn add_todo(app: AppHandle, text: String) -> Result<AppData, String> {
    let text = text.trim().to_string();
    if text.is_empty() {
        // 空白输入不是错误，只是什么都不做
        return Ok(current(&app));
    }

    mutate(&app, |data| {
        // 新任务排到最前：取全局最小 order 再减一。含已完成项一起取最小，
        // 结果一定小于所有未完成项，排序后必然落在未完成组的顶部。
        let order = data.todos.iter().map(|t| t.order).min().unwrap_or(0) - 1;

        data.todos.push(state::Todo {
            id: new_id("todo"),
            text,
            completed: false,
            priority: 1,
            created_date: state::today_string(),
            created_at: state::now_ms(),
            completed_at: None,
            archived_at: None,
            order,
            // 新任务把「当前」字体快照到自己身上，此后用户再改全局字体就与它无关了。
            // 这里能直接读 settings：`mutate` 走的是 state::load，而 migrate 已经
            // 保证 font_family 非空（空值会被补成 DEFAULT_FONT_CHAIN）。
            font: data.settings.font_family.clone(),
            font_id: data.settings.font_id.clone(),
            // 新任务不带提醒。这里是 `Todo { .. }` 字面量，加字段时编译器会点名 ——
            // 但本机没有 Rust 工具链，所以这句话是给以后改这里的人看的。
            remind_at: None,
            remind_mode: String::new(),
            remind_date: String::new(),
            reminded_on: String::new(),
        });
        Ok(())
    })
}

/// 按前端给出的显示顺序重写排序键。
///
/// `ids` 只包含**未完成**的任务：排序键以 `completed` 打头，已完成项永远沉底，
/// 所以拖动跨不过这条边界，让它们参与排序在语义上没有意义。
///
/// 未出现在 `ids` 里的任务（已完成项、并发新增的、并发归档的）会被接在末尾并
/// 保持原有相对次序 —— 这样残缺或过期的列表只会降级，不会报错、不会交错。
#[tauri::command(async)]
pub fn reorder_todos(app: AppHandle, ids: Vec<String>) -> Result<AppData, String> {
    mutate(&app, |data| {
        for (pos, id) in ids.iter().enumerate() {
            if let Some(i) = data.todos.iter().position(|t| &t.id == id) {
                data.todos[i].order = pos as i64;
            }
        }

        // 剩下的接在末尾。n 一定大于上面写进去的最大下标，两组区间不会交错；
        // 完成后 `mutate` 结尾还会再 sort 一次，所以这里必须交出 sort 的不动点。
        let n = data.todos.len() as i64;
        let mut rest: Vec<usize> = (0..data.todos.len())
            .filter(|&i| !ids.iter().any(|id| id == &data.todos[i].id))
            .collect();
        rest.sort_by_key(|&i| data.todos[i].order);
        for (k, i) in rest.into_iter().enumerate() {
            data.todos[i].order = n + k as i64;
        }

        Ok(())
    })
}

#[tauri::command(async)]
pub fn toggle_todo(app: AppHandle, id: String) -> Result<AppData, String> {
    mutate(&app, |data| {
        let todo = data
            .todos
            .iter_mut()
            .find(|t| t.id == id)
            .ok_or_else(|| format!("找不到任务 {id}"))?;

        todo.completed = !todo.completed;
        todo.completed_at = if todo.completed {
            Some(state::now_ms())
        } else {
            None
        };
        Ok(())
    })
}

/// 改写一条待办的正文字。
///
/// 找不到 id 时**降级为什么都不做**，不报错。编辑期间用户完全可能先点了那一行的
/// `×`：两条 command 都跑在线程池上，抢 `DATA_LOCK` 的顺序没有保证（见上面那段
/// 注释）。那时「改文本」这个意图已经没有意义，而 `toggle_todo` 那句
/// `找不到任务 {id}` 是给**真的**找不到用的 —— 不该在「删除」这个正常动作里弹一句
/// 带内部 id 的报错给用户看。同 `reorder_todos` 的取舍。
#[tauri::command(async)]
pub fn edit_todo(app: AppHandle, id: String, text: String) -> Result<AppData, String> {
    let text = text.trim().to_string();
    if text.is_empty() {
        // 同 add_todo：空白不是错误，只是什么都不做。
        // 前端在保存之前就把「清空」当「恢复原文」处理了，这里是第二道兜底。
        return Ok(current(&app));
    }

    mutate(&app, |data| {
        // 返回值（找没找到）刻意丢掉：找不到就是降级，不是错误，见上面的注释。
        state::set_todo_text(data, &id, &text);
        Ok(())
    })
}

/// 设定或清除一条任务的提醒。
///
/// `at` 用 `String` 而不是 `Option<String>`：Tauri 对 `Option<T>` 参数要求调用方
/// **显式传这个键**（键整个缺失是 `missing_arg` 错误，不是 `None`，只有显式传 `null`
/// 才是 `None`）—— 少一个坑。空串表达「清除」。
///
/// **`at` 与 `mode` 都在这里校验，不信 UI。** 渲染层传什么都是不可信输入，而且
/// `remind_at` 会被拿去和 `%H:%M` 做字符串比较 —— 一个没补零的 `"9:00"` 会让这条
/// 提醒永远不响（见 `reminder::parse_hhmm` 的注释）。
///
/// 找不到 id 时降级为什么都不做，理由同 `edit_todo`。
#[tauri::command(async)]
pub fn set_reminder(
    app: AppHandle,
    id: String,
    at: String,
    mode: String,
) -> Result<AppData, String> {
    let mode = if at.trim().is_empty() || mode.trim().is_empty() {
        // 清除：两个都空就是清除，只有一个空是调用方写错了 —— 一并当成清除，
        // 因为「半条提醒」没有任何合理语义。
        String::new()
    } else {
        crate::reminder::mode_or_once(mode.trim()).to_string()
    };

    let at = if mode.is_empty() {
        String::new()
    } else {
        crate::reminder::parse_hhmm(&at)
            .ok_or_else(|| format!("提醒时间格式不对：{at}"))?
    };

    mutate(&app, |data| {
        // 返回值（找没找到）刻意丢掉：找不到就是降级，不是错误。
        state::set_todo_reminder(data, &id, &at, &mode);
        Ok(())
    })
}

/// 收起提醒浮窗（弹窗里按 Esc 或点 ×）。
///
/// 它不碰 `AppData`，所以**不走** `mutate`，也就不需要 `DATA_LOCK`。
/// `(async)` 让它跑在线程池上，别压主线程。
#[tauri::command(async)]
pub fn dismiss_reminder(app: AppHandle) {
    crate::reminder::hide_popup(&app);
}

/// 点提醒浮窗：收起它，把主窗口叫到前面，并让那条任务闪一下。
///
/// 顺序是先收浮窗、再叫主窗口 —— 反过来的话浮窗会短暂地压在刚被叫出来的主窗口上面。
#[tauri::command(async)]
pub fn open_reminder(app: AppHandle, id: String) {
    crate::reminder::hide_popup(&app);

    window::show_and_focus(&app);

    // 广播给主窗口的前端去高亮。找不到那条任务时前端会静默忽略 ——
    // 用户可能已经把它删了，那不是错误。
    if let Err(e) = app.emit("mino://reminder-open", id) {
        warn!("提醒高亮事件发送失败: {e}");
    }
}

#[tauri::command(async)]
pub fn delete_todo(app: AppHandle, id: String) -> Result<AppData, String> {
    mutate(&app, |data| {
        data.todos.retain(|t| t.id != id);
        Ok(())
    })
}

#[tauri::command(async)]
pub fn clear_completed(app: AppHandle) -> Result<AppData, String> {
    mutate(&app, |data| {
        data.todos.retain(|t| !t.completed);
        Ok(())
    })
}

// ---------------------------------------------------------------------------
// 窗口
// ---------------------------------------------------------------------------

#[tauri::command(async)]
pub fn set_always_on_top(app: AppHandle, value: bool) -> Result<AppData, String> {
    window::apply_always_on_top(&app, value)?;
    mutate(&app, |data| {
        data.settings.always_on_top = value;
        Ok(())
    })
}

/// 把前端传来的 `reason` 拼成日志后缀。
///
/// `reason` 是前端为了记录而拼的短串（哪条路径、当时的判据状态），内容是我们
/// 自己的常量，但仍然按不可信输入处理：截断长度、剔掉控制字符，免得一条日志被
/// 塞进换行伪造出别的行。**只用于记录，不参与任何分支判断。**
///
/// ⚠️ 类型是 `Option<String>`，但**调用方必须把这个键传出来** ——
/// Tauri 取参数是按 key 查 payload，键整个缺失会是 `missing_arg` 错误，而不是
/// `None`；只有显式传 `null` 才是 `None`。`set_font` 的 `id` 就是这么用的
/// （`option.id ?? null`）。所以这里不要靠「省略参数」来走 None 分支。
fn fmt_reason(reason: &Option<String>) -> String {
    match reason {
        Some(r) => {
            let clean: String = r.chars().filter(|c| !c.is_control()).take(120).collect();
            format!(" [{clean}]")
        }
        None => String::new(),
    }
}

#[tauri::command(async)]
pub fn hide_window(app: AppHandle, reason: Option<String>) {
    info!("hide_window{}", fmt_reason(&reason));
    window::hide(&app);
}

#[tauri::command(async)]
pub fn show_window(app: AppHandle) {
    info!("show_window");
    window::show_and_focus(&app);
}

/// 真正退出。托盘菜单调用它，标题栏的 ✕ 只会隐藏窗口。
#[tauri::command(async)]
pub fn quit_app(app: AppHandle) {
    info!("quit_app（用户从托盘主动退出）");
    state::QUITTING.store(true, Ordering::SeqCst);
    app.exit(0);
}

/// 边缘态展开/收缩。前端在鼠标进出感应条时调用。
#[tauri::command(async)]
pub fn set_edge_collapsed(
    app: AppHandle,
    collapsed: bool,
    reason: Option<String>,
) -> Result<(), String> {
    // snapped 一并记下来：没吸附时这个调用是 no-op，日志里得看得出「它什么
    // 都没做」，否则一条「收缩了」的记录会把不存在的事件说成发生了。
    info!(
        "set_edge_collapsed collapsed={collapsed} snapped={}{}",
        window::is_snapped(),
        fmt_reason(&reason)
    );
    window::set_edge_collapsed(&app, collapsed)
}

/// 把窗口从边缘拉回来（Esc 或托盘菜单用）。
#[tauri::command(async)]
pub fn unsnap_window(app: AppHandle) -> Result<(), String> {
    info!("unsnap_window");
    window::unsnap(&app)
}

/// 标题栏的最小化按钮：已吸附就收缩成边缘感应条，没吸附就退化为隐藏窗口。
///
/// 「当前是否吸附」整个判断留在 Rust 侧，前端不参与 —— 否则前端得先问一次、
/// 再调一次，中间那个往返窗口期里用户刚按过的 Esc 会让它拿着过期状态做错事。
#[tauri::command(async)]
pub fn minimize_to_edge(app: AppHandle, reason: Option<String>) -> Result<(), String> {
    let snapped = window::is_snapped();
    // 分流结果都记下来：吸附 → 缩成感应条（在用户眼里可能像「窗口消失了」），
    // 没吸附 → 隐藏。用户报的 bug 要区分的正是这两者。
    info!("minimize_to_edge snapped={snapped}{}", fmt_reason(&reason));

    if snapped {
        window::set_edge_collapsed(&app, true)
    } else {
        window::hide(&app);
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// 背景
// ---------------------------------------------------------------------------

fn sniff_image(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a]) {
        return Some("png");
    }
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some("jpg");
    }
    if bytes.len() > 12 && bytes.starts_with(b"RIFF") && &bytes[8..12] == b"WEBP" {
        return Some("webp");
    }
    None
}

/// 接收前端上传的图片字节，复制进 app data 并设为背景。
#[tauri::command(async)]
pub fn upload_background(app: AppHandle, data: String) -> Result<AppData, String> {
    let bytes = decode_b64(&data)?;

    if bytes.is_empty() {
        return Err("图片内容为空".into());
    }
    if bytes.len() > MAX_IMAGE_BYTES {
        return Err("图片过大（上限 32 MB）".into());
    }

    let ext = sniff_image(&bytes).ok_or("无法识别的图片格式，仅支持 png / jpg / webp")?;

    let dir = state::data_dir(&app)?;
    let filename = format!("bg_image.{ext}");
    let dest = dir.join(&filename);

    // 换扩展名时要清掉上一张，否则会在数据目录里留下孤儿文件
    for old in ["png", "jpg", "webp"] {
        let stale = dir.join(format!("bg_image.{old}"));
        if stale != dest && stale.exists() {
            let _ = std::fs::remove_file(stale);
        }
    }

    std::fs::write(&dest, &bytes).map_err(|e| format!("保存背景失败: {e}"))?;
    info!("背景已更新为 {filename}");

    mutate(&app, |d| {
        d.settings.bg_type = "image".into();
        d.settings.bg_path = Some(filename.clone());
        Ok(())
    })
}

#[tauri::command(async)]
pub fn reset_background(app: AppHandle) -> Result<AppData, String> {
    let dir = state::data_dir(&app)?;
    for ext in ["png", "jpg", "webp"] {
        let stale = dir.join(format!("bg_image.{ext}"));
        if stale.exists() {
            let _ = std::fs::remove_file(stale);
        }
    }

    mutate(&app, |d| {
        d.settings.bg_type = "mica".into();
        d.settings.bg_path = None;
        Ok(())
    })
}

/// 读取背景图片原始字节，前端转成 blob URL 显示。
#[tauri::command(async)]
pub fn read_background(app: AppHandle) -> Result<Response, String> {
    let data = state::load(&app);
    let name = data
        .settings
        .bg_path
        .ok_or("当前没有自定义背景")?;

    // 只允许读数据目录下的 bg_image.*，杜绝路径逃逸
    if !name.starts_with("bg_image.") || name.contains(['/', '\\']) {
        return Err("非法的背景路径".into());
    }

    let path = state::data_dir(&app)?.join(&name);
    let bytes = std::fs::read(&path).map_err(|e| format!("读取背景失败: {e}"))?;
    Ok(Response::new(bytes))
}

// ---------------------------------------------------------------------------
// 外观
// ---------------------------------------------------------------------------

/// 归一化 `#rgb` / `#rrggbb`（大小写不限）成小写 `#rrggbb`，其余一律拒绝。
///
/// 这个值最终会进 CSS 变量。前端虽然只给预设色块，但 command 是公开的 IPC 入口，
/// 不能假设调用方是自家界面 —— 放行任意字符串等于开了个 CSS 注入口子。
fn normalize_hex_color(input: &str) -> Option<String> {
    let hex = input.trim().strip_prefix('#')?;
    if hex.len() != 3 && hex.len() != 6 {
        return None;
    }
    if !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }

    if hex.len() == 3 {
        // #abc → #aabbcc
        let mut out = String::with_capacity(7);
        out.push('#');
        for c in hex.chars() {
            let c = c.to_ascii_lowercase();
            out.push(c);
            out.push(c);
        }
        Some(out)
    } else {
        Some(format!("#{}", hex.to_ascii_lowercase()))
    }
}

/// 设置便签正文颜色。投影方向由前端按这个颜色的亮度自动反转。
#[tauri::command(async)]
pub fn set_text_color(app: AppHandle, color: String) -> Result<AppData, String> {
    let color = normalize_hex_color(&color).ok_or("颜色格式无效，只接受 #rgb 或 #rrggbb")?;

    mutate(&app, |d| {
        d.settings.text_color = color.clone();
        Ok(())
    })
}

#[tauri::command(async)]
pub fn set_mask_opacity(app: AppHandle, value: f64) -> Result<AppData, String> {
    // NaN 的 clamp 结果是 NaN，落盘会变成 JSON null。提前挡住。
    let v = if value.is_finite() {
        value.clamp(0.0, 0.95)
    } else {
        state::DEFAULT_MASK_OPACITY
    };

    mutate(&app, |d| {
        d.settings.mask_opacity = v;
        Ok(())
    })
}

#[tauri::command(async)]
pub fn set_blur(app: AppHandle, value: f64) -> Result<AppData, String> {
    let v = if value.is_finite() { value.clamp(0.0, 24.0) } else { 0.0 };

    mutate(&app, |d| {
        d.settings.blur = v;
        Ok(())
    })
}

// ---------------------------------------------------------------------------
// 字体
// ---------------------------------------------------------------------------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FontOption {
    /// 下拉列表里显示的名字。
    pub name: String,
    /// 可直接赋给 CSS 的完整回退链，前端不必自己拼。
    pub css: String,
    pub builtin: bool,
    /// 导入字体的 id；选择它之后前端需要先 ensureFont 加载字节。
    pub id: Option<String>,
}

/// 返回可选字体：系统字体（builtin=true）+ 已导入字体（builtin=false）。
#[tauri::command(async)]
pub fn list_fonts(app: AppHandle) -> Vec<FontOption> {
    let data = state::load(&app);

    let mut out: Vec<FontOption> = fonts::list_families()
        .into_iter()
        .map(|name| FontOption {
            css: fonts::chain_for(&name),
            name,
            builtin: true,
            id: None,
        })
        .collect();

    // 已导入的排在前面：它们数量少、且是用户主动添加的
    let mut custom: Vec<FontOption> = data
        .settings
        .custom_fonts
        .iter()
        .map(|f| FontOption {
            name: f.label.clone(),
            css: fonts::chain_for(&f.family),
            builtin: false,
            id: Some(f.id.clone()),
        })
        .collect();

    custom.append(&mut out);
    custom
}

/// 导入字体。`data` 是前端 `<input type="file">` 读出的字节（base64）。
#[tauri::command(async)]
pub fn import_font(app: AppHandle, name: String, data: String) -> Result<AppData, String> {
    let bytes = decode_b64(&data)?;
    let font = fonts::import_bytes(&app, &name, &bytes)?;
    let css = fonts::chain_for(&font.family);

    mutate(&app, |d| {
        // 同内容重复导入时只保留一条
        d.settings.custom_fonts.retain(|f| f.id != font.id);
        d.settings.custom_fonts.push(font.clone());

        // 导入后立即启用
        d.settings.font_family = css.clone();
        d.settings.font_id = Some(font.id.clone());
        Ok(())
    })
}

/// 读取已导入字体的原始字节 → 前端得到 ArrayBuffer → new FontFace(family, buf)。
#[tauri::command(async)]
pub fn read_font(app: AppHandle, id: String) -> Result<Response, String> {
    let data = state::load(&app);
    let font = data
        .settings
        .custom_fonts
        .iter()
        .find(|f| f.id == id)
        .ok_or_else(|| format!("找不到字体 {id}"))?;

    fonts::read(&app, &font.file)
}

#[tauri::command(async)]
pub fn remove_font(app: AppHandle, id: String) -> Result<AppData, String> {
    let data = state::load(&app);
    if let Some(font) = data.settings.custom_fonts.iter().find(|f| f.id == id) {
        if let Ok(dir) = state::fonts_dir(&app) {
            let name = font.file.strip_prefix("fonts/").unwrap_or(&font.file);
            let _ = std::fs::remove_file(dir.join(name));
        }
    }

    mutate(&app, |d| {
        d.settings.custom_fonts.retain(|f| f.id != id);
        if d.settings.font_id.as_deref() == Some(id.as_str()) {
            d.settings.font_id = None;
            d.settings.font_family = state::DEFAULT_FONT_CHAIN.into();
        }

        // 引用了这个字体、但全局字体不是它的那些任务，也得一起清干净。
        // 字体文件已经删了，再留着那个链就是指向一个注册不进来的 family ——
        // 浏览器会静默回退到链尾的 sans-serif，用户看到的是「字体自己变了」。
        // 清空 = 回到「没盖章」状态，前端按全局字体渲染，结果确定。
        //
        // 这里只清 todos：archives 不再渲染，留着不影响显示。
        for t in d.todos.iter_mut() {
            if t.font_id.as_deref() == Some(id.as_str()) {
                t.font_id = None;
                t.font = String::new();
            }
        }

        Ok(())
    })
}

/// 设置正文字体。`family` 为完整 CSS 回退链，`id` 为导入字体 id（可选）。
#[tauri::command(async)]
pub fn set_font(app: AppHandle, family: String, id: Option<String>) -> Result<AppData, String> {
    let family = family.trim().to_string();
    if family.is_empty() {
        return Err("字体不能为空".into());
    }

    mutate(&app, |d| {
        d.settings.font_family = family.clone();
        d.settings.font_id = id.clone();
        Ok(())
    })
}

/// 恢复默认字体。**只影响此后新建的任务** —— 已有任务把字体记在自己身上了。
#[tauri::command(async)]
pub fn reset_font(app: AppHandle) -> Result<AppData, String> {
    mutate(&app, |d| {
        d.settings.font_family = state::DEFAULT_FONT_CHAIN.into();
        d.settings.font_id = None;
        Ok(())
    })
}

/// 把当前字体盖到所有已有任务上（设置面板的「应用到全部」）。
///
/// 与 `set_font` 分开是刻意的：那个改的是 settings（影响未来），这个改的是
/// 每一条 todos（一次批量数据改写）。影响半径完全不同，不该靠一个 bool 分流。
#[tauri::command(async)]
pub fn apply_font_to_all(app: AppHandle) -> Result<AppData, String> {
    mutate(&app, |d| {
        state::apply_font_to_all(d);
        Ok(())
    })
}

// ---------------------------------------------------------------------------
// 运行时信息
// ---------------------------------------------------------------------------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeInfo {
    pub data_dir: String,
    pub shortcut: String,
    pub shortcut_active: bool,
    pub today: String,
    pub version: String,
    /// 开机自启动状态。**它不在 `Settings` 里** —— 注册表才是唯一权威，
    /// 见 [crate::autostart] 的模块注释。
    pub autostart: crate::autostart::Status,
}

#[tauri::command(async)]
pub fn runtime_info(app: AppHandle) -> RuntimeInfo {
    let data = state::load(&app);
    RuntimeInfo {
        data_dir: state::data_dir(&app)
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_default(),
        shortcut: data.settings.shortcut.clone(),
        shortcut_active: crate::shortcut::REGISTERED.load(Ordering::SeqCst),
        today: state::today_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
        autostart: crate::autostart::status(),
    }
}

/// 打开/关闭开机自启动。返回**写完重读之后**的真实状态。
///
/// 为什么返回状态而不是 `Result`：前端只需要一条路径 —— 「拿返回的状态去渲染」。
/// 写失败时返回的是「真实状态 + error 文案」，开关会自己弹回真实位置，而不是停在
/// 一个乐观的假象上；要提示就看 `error`。
///
/// 它不碰 `AppData`，所以**不走** `current` / `mutate`，也就不需要 `DATA_LOCK`。
/// `(async)` 让它跑在线程池上 —— 注册表 IO 和文件 IO 一样，不该压在主线程。
#[tauri::command(async)]
pub fn set_autostart(enabled: bool) -> crate::autostart::Status {
    crate::autostart::set(enabled)
}

/// 前台是不是压着一个全屏程序。感应条的悬停门控用，见 [ui/window.js](ui/window.js)。
///
/// 之所以要绕到 Rust 来问：浏览器里看不到别的窗口，「前台窗口有没有铺满显示器」
/// 只有 Win32 知道。前端在 `mouseenter` 里查一次，据此决定要不要展开。
///
/// 它**零 IO、零锁**，纯粹是一次查询 —— 悬停是热路径，不能像数据类 command 那样
/// 走 `current`/`mutate`。
#[tauri::command(async)]
pub fn foreground_is_fullscreen() -> bool {
    crate::window::foreground_is_fullscreen()
}

/// 用系统默认浏览器打开发布页面。
///
/// 前端拿不到 `tauri-plugin-opener` 的 JS API（插件是独立 npm 包，而本项目没有
/// 打包器，`withGlobalTauri` 的全局包里也没有它），而 `<a target="_blank">` 在
/// Tauri 的 webview 里不会交给系统浏览器 —— 要么没反应，要么直接在 webview 内
/// 导航、把整个界面顶掉。所以这一步必须落到 Rust。
#[tauri::command(async)]
pub fn open_release_page(tag: String) -> Result<(), String> {
    // `tag` 来自 GitHub API 的响应，是**不可信输入**。它会被拼进一条 cmd 命令行，
    // 所以拼之前必须把字符集卡死 —— cmd 的元字符（& ^ | < > " 空格）在这里就是
    // 命令注入。合法版本号只可能是 v1.2.3 / v0.2.0-beta.1 这类形状。
    let ok = !tag.is_empty()
        && tag.len() <= 64
        && tag
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'));

    if !ok {
        return Err("版本号格式非法".into());
    }

    let url = format!("https://github.com/jackpunck/MiNo/releases/tag/{tag}");

    // `""` 那个空参数是 start 的窗口标题占位。省掉它，start 会把 URL 当成标题，
    // 结果是弹一个空的 cmd 窗口而不是浏览器。
    //
    // 这里用 .args() 而不是 raw_arg：URL 已经过白名单校验，不含空格和引号，
    // Rust 的 Windows 参数转义不会给它加上任何引号，拼出来的就是
    // `cmd /C start "" https://…`，正是我们要的。
    //
    // CREATE_NO_WINDOW：本进程是 windows_subsystem = "windows"，自己没有控制台。
    // 不带上这个标志，Windows 会给 cmd.exe 新分配一个控制台 —— 用户眼前会闪一下
    // 黑框。它只影响 cmd 自己，start 拉起来的浏览器是 GUI 进程，照常有窗口。
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    std::process::Command::new("cmd")
        .args(["/C", "start", "", url.as_str()])
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("打开浏览器失败: {e}"))
}

/// 保存窗口位置。前端在窗口移动后调用；位置校验在 window 模块里做。
#[tauri::command(async)]
pub fn save_window_position(app: AppHandle, x: i32, y: i32) -> Result<(), String> {
    mutate(&app, |d| {
        d.settings.window_position = Some(Position { x, y });
        Ok(())
    })
    .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::normalize_hex_color;

    #[test]
    fn normalizes_valid_hex() {
        assert_eq!(normalize_hex_color("#ABC").as_deref(), Some("#aabbcc"));
        assert_eq!(normalize_hex_color("#F5F5F7").as_deref(), Some("#f5f5f7"));
        assert_eq!(normalize_hex_color(" #fff ").as_deref(), Some("#ffffff"));
    }

    /// 这个值会直接进 CSS，放行任意字符串等于开了个注入口子
    #[test]
    fn rejects_anything_else() {
        for bad in [
            "red",          // 颜色关键字
            "#gg0000",      // 非十六进制
            "#ff00",        // 长度不对
            "#ff00008",     // 长度不对
            "",             // 空
            "#",            // 只有井号
            "1b1b1f",       // 缺井号
            "#ff0000;}",    // 想结束声明再补一条
        ] {
            assert!(normalize_hex_color(bad).is_none(), "{bad:?} 不该被接受");
        }
    }
}
