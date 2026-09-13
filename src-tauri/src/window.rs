//! 窗口控制：位置恢复、多显示器兜底、置顶、边缘吸附。
//!
//! 两处容易出错的地方，都在这里集中处理：
//!
//! 1. **单位**。显示器信息是物理像素，而窗口尺寸按逻辑像素书写。混用会在
//!    125% 缩放的屏幕上产生 25% 的偏差。本模块统一先换算成物理像素再计算。
//!
//! 2. **自触发**。吸附时我们自己调用 `set_position`/`set_size`，这会再次触发
//!    `Moved` 事件，进而又要重新判定吸附 —— 不加以区分就会陷入抖动。
//!    `PROGRAMMATIC` 标志用来标记这些由程序发起的移动。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use log::{info, warn};
// Emitter 提供 `emit` —— 它不在 Manager trait 上，漏掉会编译失败
use tauri::{AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize, WebviewWindow};

use crate::state;

pub const WIDTH: i32 = 280;
pub const HEIGHT: i32 = 380;

/// 程序正在自行调整窗口，忽略随之而来的 Moved 事件。
static PROGRAMMATIC: AtomicBool = AtomicBool::new(false);

/// 当前吸附在哪一侧；None 表示未吸附。
static SNAPPED: Mutex<Option<Edge>> = Mutex::new(None);

/// 拖动时 Moved 事件非常密集，落盘要节流。
static LAST_SAVE: Mutex<Option<Instant>> = Mutex::new(None);

const SAVE_INTERVAL: Duration = Duration::from_millis(600);

/// 吸附相关的设置缓存。
///
/// 拖动过程中 Moved 事件每秒会来几十次，每次都去读盘 + 解析 data.json 是没必要的
/// 开销。这些值只在启动和设置变更时刷新。
#[derive(Clone, Copy)]
struct SnapConfig {
    enabled: bool,
    threshold: i32,
}

static SNAP_CFG: Mutex<Option<SnapConfig>> = Mutex::new(None);

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Edge {
    Left,
    Right,
}

/// 解除吸附时往屏幕内侧让开的距离（物理像素）。
/// 不让开的话，刚恢复的完整宽度会立刻又被判定成贴边、重新吸附回去。
const SNAP_MARGIN: i32 = 40;

fn main_window(app: &AppHandle) -> Option<WebviewWindow> {
    app.get_webview_window("main")
}

// ---------------------------------------------------------------------------
// 显示 / 隐藏
// ---------------------------------------------------------------------------

pub fn show(app: &AppHandle) {
    let Some(w) = main_window(app) else { return };
    let _ = w.show();
    let _ = w.set_focus();
    // 让前端把光标放回输入框
    let _ = w.emit("mino://focus-input", ());
}

/// 显示并把输入框聚焦。托盘双击、全局快捷键、单实例唤醒都走这里。
pub fn show_and_focus(app: &AppHandle) {
    let Some(w) = main_window(app) else { return };
    let _ = w.show();
    let _ = w.unminimize();
    let _ = w.set_focus();
    let _ = w.emit("mino://focus-input", ());
}

pub fn hide(app: &AppHandle) {
    if let Some(w) = main_window(app) {
        let _ = w.hide();
    }
}

pub fn toggle_visibility(app: &AppHandle) {
    let Some(w) = main_window(app) else { return };
    if w.is_visible().unwrap_or(false) {
        // 已经可见但焦点在别的应用时，仍然应该把它拉到前台
        if w.is_focused().unwrap_or(false) {
            let _ = w.hide();
        } else {
            show_and_focus(app);
        }
    } else {
        show_and_focus(app);
    }
}

pub fn apply_always_on_top(app: &AppHandle, value: bool) -> Result<(), String> {
    let w = main_window(app).ok_or("找不到主窗口")?;
    w.set_always_on_top(value).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// 位置
// ---------------------------------------------------------------------------

/// 把窗口放到工作区右上角，避开屏幕中央。
fn default_position(mon_x: i32, mon_y: i32, mon_w: i32, win_w: i32) -> (i32, i32) {
    (mon_x + mon_w - win_w - 20, mon_y + 80)
}

fn monitor_of(w: &WebviewWindow) -> Option<tauri::Monitor> {
    w.current_monitor()
        .ok()
        .flatten()
        .or_else(|| w.primary_monitor().ok().flatten())
}

/// 恢复上次位置；若已不可见（换了显示器或改了分辨率）则回退到主屏默认位。
pub fn restore_position(app: &AppHandle) {
    let Some(w) = main_window(app) else { return };

    let saved = state::load(app).settings.window_position;

    let Some(mon) = monitor_of(&w) else {
        // 拿不到显示器信息时只能照搬保存值，至少不会更糟
        if let Some(p) = saved {
            set_position(&w, p.x, p.y);
        }
        return;
    };

    let mpos = mon.position();
    let msize = mon.size();
    let scale = mon.scale_factor();

    // 逻辑尺寸 → 物理尺寸，才能和显示器坐标同量纲比较
    let win_w = (WIDTH as f64 * scale).round() as i32;
    let win_h = (HEIGHT as f64 * scale).round() as i32;

    let max_x = mpos.x + msize.width as i32 - win_w;
    let max_y = mpos.y + msize.height as i32 - win_h;

    let (x, y) = match saved {
        Some(p) if p.x >= mpos.x && p.x <= max_x && p.y >= mpos.y && p.y <= max_y => (p.x, p.y),
        Some(p) => {
            warn!(
                "保存的窗口位置 ({}, {}) 已不在可见区域，回退到默认位置",
                p.x, p.y
            );
            default_position(mpos.x, mpos.y, msize.width as i32, win_w)
        }
        None => default_position(mpos.x, mpos.y, msize.width as i32, win_w),
    };

    info!("窗口位置恢复到 ({x}, {y})");
    set_position(&w, x, y);
}

fn set_position(w: &WebviewWindow, x: i32, y: i32) {
    PROGRAMMATIC.store(true, Ordering::SeqCst);
    let _ = w.set_position(PhysicalPosition::new(x, y));
    PROGRAMMATIC.store(false, Ordering::SeqCst);
}

fn set_size(w: &WebviewWindow, width: i32, height: i32) {
    PROGRAMMATIC.store(true, Ordering::SeqCst);
    let _ = w.set_size(PhysicalSize::new(width.max(1) as u32, height.max(1) as u32));
    PROGRAMMATIC.store(false, Ordering::SeqCst);
}

// ---------------------------------------------------------------------------
// 边缘吸附
// ---------------------------------------------------------------------------

/// 重新读取吸附设置。启动时以及设置变更后调用。
pub fn refresh_snap_config(app: &AppHandle) {
    let s = state::load(app).settings;
    *SNAP_CFG.lock().unwrap() = Some(SnapConfig {
        enabled: s.edge_snap,
        threshold: s.edge_threshold,
    });
}

fn snap_config(app: &AppHandle) -> SnapConfig {
    if let Some(cfg) = *SNAP_CFG.lock().unwrap() {
        return cfg;
    }
    refresh_snap_config(app);
    (*SNAP_CFG.lock().unwrap()).unwrap_or(SnapConfig {
        enabled: false,
        threshold: 10,
    })
}

/// 窗口移动后调用。足够靠近屏幕边缘就吸附，离开则解除。
pub fn handle_moved(app: &AppHandle) {
    if PROGRAMMATIC.load(Ordering::SeqCst) {
        return;
    }

    let Some(w) = main_window(app) else { return };
    let Ok(pos) = w.outer_position() else { return };
    let Ok(size) = w.outer_size() else { return };

    let cfg = snap_config(app);

    if !cfg.enabled {
        release_snap(&w);
        save_position_throttled(app, pos.x, pos.y);
        return;
    }

    let Some(mon) = monitor_of(&w) else { return };
    let scale = mon.scale_factor();
    let threshold = (cfg.threshold as f64 * scale).round() as i32;

    let mpos = mon.position();
    let mon_right = mpos.x + mon.size().width as i32;
    let win_right = pos.x + size.width as i32;

    let dist_right = mon_right - win_right;
    let dist_left = pos.x - mpos.x;

    // 取更近的一侧，且必须在阈值内
    let edge = if dist_right.abs() <= threshold && dist_right.abs() <= dist_left.abs() {
        Some(Edge::Right)
    } else if dist_left.abs() <= threshold {
        Some(Edge::Left)
    } else {
        None
    };

    match edge {
        Some(e) => {
            let already = *SNAPPED.lock().unwrap() == Some(e);
            if !already {
                info!("吸附到屏幕{edge:?}边缘");
                *SNAPPED.lock().unwrap() = Some(e);
            }
            snap_flush(&w, e, mpos.x, mon_right);
            // 吸附后位置由边缘决定，不再记为用户拖出来的位置
        }
        None => {
            if SNAPPED.lock().unwrap().is_some() {
                info!("离开边缘，解除吸附");
                release_snap(&w);
            }
            save_position_throttled(app, pos.x, pos.y);
        }
    }
}

/// 把窗口贴平到边缘。
fn snap_flush(w: &WebviewWindow, edge: Edge, mon_left: i32, mon_right: i32) {
    let Ok(size) = w.outer_size() else { return };
    let Ok(pos) = w.outer_position() else { return };

    let x = match edge {
        Edge::Right => mon_right - size.width as i32,
        Edge::Left => mon_left,
    };

    if x != pos.x {
        set_position(w, x, pos.y);
    }
}

/// 当前是否吸附在某条边上。最小化按钮据此决定是收缩还是隐藏窗口。
pub fn is_snapped() -> bool {
    SNAPPED.lock().unwrap().is_some()
}

/// 解除吸附：恢复完整宽度并离开边缘。
pub fn unsnap(app: &AppHandle) -> Result<(), String> {
    let w = main_window(app).ok_or("找不到主窗口")?;
    release_snap(&w);
    Ok(())
}

fn release_snap(w: &WebviewWindow) {
    let edge = SNAPPED.lock().unwrap().take();

    let Ok(size) = w.outer_size() else { return };
    let Ok(pos) = w.outer_position() else { return };

    let scale = w.scale_factor().unwrap_or(1.0);
    let full_w = (WIDTH as f64 * scale).round() as i32;

    // 收缩态是就地改宽度、不动位置，所以恢复时要按原样长回去。
    let collapsed = (size.width as i32) < full_w;
    if edge.is_none() && !collapsed {
        // 宽度本来就是满的 —— 说明用户是自己把窗口拖离边缘的，位置不能动
        return;
    }

    let mon = monitor_of(w);
    let mon_left = mon.as_ref().map(|m| m.position().x);
    let mon_right = mon
        .as_ref()
        .map(|m| m.position().x + m.size().width as i32);

    // 恢复宽度必须以「当前贴边的那条边」为锚，往屏幕内侧长，而不是沿用 pos.x。
    // 右侧吸附时 cur_right 就等于 mon_right，直接沿用 pos.x 会把 280px 宽的窗口
    // 顶到屏幕外（只留感应条那十来像素可见）。左侧则恰好相反，x 不变才是对的。
    let cur_right = pos.x + size.width as i32;

    let x = match edge {
        Some(Edge::Right) => {
            let anchored = cur_right - full_w;
            // 展开后仍然贴着右缘（或已经出界）才需要让位；
            // 取不到显示器信息时按「贴着」处理，宁可往内让也不要留在屏幕外。
            let hugging = collapsed || mon_right.map_or(true, |r| cur_right >= r - SNAP_MARGIN);
            if hugging {
                mon_left.map_or(anchored, |l| (anchored - SNAP_MARGIN).max(l))
            } else {
                pos.x
            }
        }
        Some(Edge::Left) => {
            let hugging = collapsed || mon_left.map_or(true, |l| pos.x <= l + SNAP_MARGIN);
            if hugging {
                mon_left.map_or(pos.x, |l| l + SNAP_MARGIN)
            } else {
                pos.x
            }
        }
        // 没有吸附记录时只把宽度还回去
        None => pos.x,
    };

    set_size(w, full_w, size.height as i32);
    set_position(w, x, pos.y);
}

/// 收紧成一条感应条 / 从感应条展开。
pub fn set_edge_collapsed(app: &AppHandle, collapsed: bool) -> Result<(), String> {
    let w = main_window(app).ok_or("找不到主窗口")?;

    let edge = { *SNAPPED.lock().unwrap() };
    let Some(edge) = edge else {
        // 没吸附就不该有收缩行为
        return Ok(());
    };

    let mon = monitor_of(&w).ok_or("找不到显示器")?;
    let scale = mon.scale_factor();
    let mpos = mon.position();
    let mon_left = mpos.x;
    let mon_right = mpos.x + mon.size().width as i32;

    let Ok(size) = w.outer_size() else {
        return Err("无法读取窗口尺寸".into());
    };
    let Ok(pos) = w.outer_position() else {
        return Err("无法读取窗口位置".into());
    };

    let full_w = (WIDTH as f64 * scale).round() as i32;
    let handle_w = (state::load(app).settings.edge_handle_size as f64 * scale)
        .round()
        .max(1.0) as i32;

    if collapsed {
        let x = match edge {
            Edge::Right => mon_right - handle_w,
            Edge::Left => mon_left,
        };
        set_size(&w, handle_w, size.height as i32);
        set_position(&w, x, pos.y);
    } else {
        let x = match edge {
            Edge::Right => mon_right - full_w,
            Edge::Left => mon_left,
        };
        set_size(&w, full_w, size.height as i32);
        set_position(&w, x, pos.y);
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// 位置持久化
// ---------------------------------------------------------------------------

fn save_position_throttled(app: &AppHandle, x: i32, y: i32) {
    let mut last = LAST_SAVE.lock().unwrap();
    let now = Instant::now();

    if let Some(t) = *last {
        if now.duration_since(t) < SAVE_INTERVAL {
            return;
        }
    }
    *last = Some(now);
    drop(last);

    // 这里跑在 `WindowEvent::Moved` 的回调里，也就是主线程上。
    // `#[tauri::command(async)]` 只改变 IPC 的派发方式，宏生成的原函数体仍是同步的，
    // 直接调用就等于在主线程上做「读盘 → 解析 → 写盘 → fsync」，
    // 拖动窗口时每 600ms 卡一下。丢到后台线程去，顺带也不占用 DATA_LOCK 的主线程。
    let app = app.clone();
    std::thread::spawn(move || {
        if let Err(e) = crate::commands::save_window_position(app, x, y) {
            warn!("保存窗口位置失败: {e}");
        }
    });
}

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------

/// 窗口初始化：恢复位置 → 应用置顶 → 显示。
/// 顺序不能反，否则会先出现在屏幕中央再跳到右上角，很扎眼。
pub fn init(app: &AppHandle) {
    restore_position(app);
    refresh_snap_config(app);

    let data = state::load(app);
    if let Err(e) = apply_always_on_top(app, data.settings.always_on_top) {
        warn!("应用置顶设置失败: {e}");
    }

    show(app);
}
