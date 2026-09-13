//! 系统托盘。
//!
//! 托盘是应用唯一的退出入口 —— 标题栏的 ✕ 只隐藏窗口（规格 §16），
//! 这样才能保住后台驻留和全局快捷键。

use std::sync::atomic::Ordering;
use std::sync::Mutex;

use log::{info, warn};
use tauri::{
    menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager,
};

use crate::{state, window};

const TRAY_ID: &str = "mino-tray";

/// 「窗口置顶」复选框的句柄。
///
/// Tauri 2 的 `TrayIcon` 只有 `set_menu`，没有 `menu()` getter —— 菜单一旦建好就
/// 拿不回来了，`AppHandle::menu()` 取的是应用菜单（这里根本没设过），不是托盘菜单。
/// 所以勾选状态只能自己留一份句柄来回写。`CheckMenuItem` 本身就是可克隆的轻量句柄。
static ONTOP_ITEM: Mutex<Option<CheckMenuItem<tauri::Wry>>> = Mutex::new(None);

pub fn build(app: &AppHandle) -> tauri::Result<()> {
    let always_on_top = state::load(app).settings.always_on_top;

    let show_i = MenuItem::with_id(app, "show", "显示 MiNo", true, None::<&str>)?;
    let hide_i = MenuItem::with_id(app, "hide", "隐藏 MiNo", true, None::<&str>)?;
    let ontop_i = CheckMenuItem::with_id(
        app,
        "ontop",
        "窗口置顶",
        true,
        always_on_top,
        None::<&str>,
    )?;
    // 留下来用于回写勾选状态，见 ONTOP_ITEM 的说明
    *ONTOP_ITEM.lock().unwrap() = Some(ontop_i.clone());

    let bg_i = MenuItem::with_id(app, "reset_bg", "恢复默认背景", true, None::<&str>)?;
    let font_i = MenuItem::with_id(app, "reset_font", "恢复默认字体", true, None::<&str>)?;
    let settings_i = MenuItem::with_id(app, "settings", "设置", true, None::<&str>)?;
    let quit_i = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;

    let menu = Menu::with_items(
        app,
        &[
            &show_i,
            &hide_i,
            &PredefinedMenuItem::separator(app)?,
            &ontop_i,
            &bg_i,
            &font_i,
            &PredefinedMenuItem::separator(app)?,
            &settings_i,
            &quit_i,
        ],
    )?;

    let mut builder = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip("MiNo")
        .menu(&menu)
        // 左键用来切换显隐，右键才弹菜单
        .show_menu_on_left_click(false)
        .on_menu_event(on_menu_event)
        .on_tray_icon_event(on_tray_event);

    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }

    builder.build(app)?;
    info!("托盘已初始化");
    Ok(())
}

fn on_menu_event(app: &AppHandle, event: tauri::menu::MenuEvent) {
    match event.id.as_ref() {
        "show" => window::show_and_focus(app),
        "hide" => window::hide(app),
        "ontop" => toggle_always_on_top(app),
        "reset_bg" => {
            if let Err(e) = crate::commands::reset_background(app.clone()) {
                warn!("恢复默认背景失败: {e}");
            }
        }
        "reset_font" => {
            if let Err(e) = crate::commands::reset_font(app.clone()) {
                warn!("恢复默认字体失败: {e}");
            }
        }
        "settings" => {
            window::show_and_focus(app);
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.emit("mino://open-settings", ());
            }
        }
        "quit" => {
            info!("用户从托盘退出");
            state::QUITTING.store(true, Ordering::SeqCst);
            app.exit(0);
        }
        _ => {}
    }
}

/// 置顶开关：以落盘设置为准，而不是以点击为准。
/// 复选框的真实状态有时会和配置脱节，统一从权威值回写才不会越点越乱。
fn toggle_always_on_top(app: &AppHandle) {
    let next = !state::load(app).settings.always_on_top;

    if let Err(e) = crate::commands::set_always_on_top(app.clone(), next) {
        warn!("切换置顶失败: {e}");
        return;
    }

    // 回写勾选状态
    if let Some(check) = ONTOP_ITEM.lock().unwrap().as_ref() {
        let _ = check.set_checked(next);
    }
}

fn on_tray_event(tray: &tauri::tray::TrayIcon, event: TrayIconEvent) {
    match event {
        TrayIconEvent::Click {
            button: MouseButton::Left,
            button_state: MouseButtonState::Up,
            ..
        } => {
            window::toggle_visibility(tray.app_handle());
        }
        TrayIconEvent::DoubleClick {
            button: MouseButton::Left,
            ..
        } => {
            // 双击落在单击之后，这里只负责把窗口确定性地拉到前台并聚焦
            window::show_and_focus(tray.app_handle());
        }
        _ => {}
    }
}
