// 发布版不弹控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod commands;
mod fonts;
mod logging;
mod shortcut;
mod state;
mod tray;
mod window;

use std::sync::atomic::Ordering;

use log::{error, info, warn};
// emit 在 Emitter trait 上，不在 Manager 上 —— 两个都要 use。
use tauri::{Emitter, Manager, RunEvent, WindowEvent};

fn main() {
    tauri::Builder::default()
        // 单实例必须最先注册：第二次启动应唤醒已有窗口，而不是再开一个应用
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            window::show_and_focus(app);
        }))
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(shortcut::handle)
                .build(),
        )
        .invoke_handler(tauri::generate_handler![
            commands::load_state,
            commands::add_todo,
            commands::toggle_todo,
            commands::delete_todo,
            commands::clear_completed,
            commands::reorder_todos,
            commands::set_always_on_top,
            commands::hide_window,
            commands::show_window,
            commands::quit_app,
            commands::set_edge_collapsed,
            commands::unsnap_window,
            commands::minimize_to_edge,
            commands::upload_background,
            commands::reset_background,
            commands::read_background,
            commands::set_text_color,
            commands::set_mask_opacity,
            commands::set_blur,
            commands::list_fonts,
            commands::import_font,
            commands::read_font,
            commands::remove_font,
            commands::set_font,
            commands::reset_font,
            commands::apply_font_to_all,
            commands::runtime_info,
            commands::foreground_is_fullscreen,
            commands::open_release_page,
            commands::save_window_position,
        ])
        .setup(|app| {
            let handle = app.handle().clone();

            // 数据目录接管必须排在**日志之前**：日志文件也住在数据目录里，先搬完
            // 再开日志，老日志才能跟着一起过来。顺序反了会先在新目录建出一个空
            // 日志，而搬迁会因为「新目录已存在」放弃 —— 用户的待办就真没了。
            //
            // 也正因为它跑在 logger 装好之前，它不自己打日志，只把结果带回来 ——
            // 在这里 info!/warn! 会被丢掉，连「接管失败、数据还在老目录」那句也
            // 一样，而那句是用户唯一的救援线索。所以结果留到下面 report()。
            let adopted = state::adopt_legacy_data_dir(&handle);

            // 日志要尽早接管：下面每一步的失败都得留下痕迹，包括「根本没走到
            // 这一步」。它自己拿不到目录时只是不记日志，不影响启动。
            logging::init(&handle);
            info!("MiNo {} 启动", env!("CARGO_PKG_VERSION"));

            // 现在日志能接住了，补记搬迁结果。
            adopted.report();

            // 启动顺序（规格 §21）。以下每一步失败都不得阻止应用启动 ——
            // 背景丢了、字体文件没了，都不该让用户连窗口都看不到。
            let mut data = state::load(&handle);

            if fonts::prune_missing(&handle, &mut data) {
                let _ = state::save(&handle, &data);
            }

            if state::rollover(&mut data) > 0 {
                let _ = state::save(&handle, &data);
            }

            if let Err(e) = tray::build(&handle) {
                error!("托盘初始化失败: {e}");
            }

            // 快捷键可能抢不到，apply 会退回备选组合；这里把实际生效的写回配置，
            // 让界面上显示的和真正生效的一致。
            let requested = data.settings.shortcut.clone();
            let actual = shortcut::apply(&handle, &requested);
            if actual != requested {
                data.settings.shortcut = actual;
                if let Err(e) = state::save(&handle, &data) {
                    warn!("保存快捷键设置失败: {e}");
                }
            }

            window::init(&handle);
            info!("MiNo 启动完成");
            Ok(())
        })
        .on_window_event(|window, event| match event {
            WindowEvent::Moved(_) => {
                window::handle_moved(window.app_handle());
            }
            // 把系统级的窗口激活状态推给前端，由前端决定「鼠标扫过屏幕边缘
            // 要不要展开窗口」（需求 1）。
            //
            // 这里只发信号、不做决策：收缩要走 state::load（文件 IO），而这个
            // 回调在主线程上，不能在这里干。emit 只是往 webview 推一条 IPC 消息。
            //
            // 用 app_handle().emit 而不是 window.emit：AppHandle 确定实现了
            // Emitter，不必纠结 Window 那边的 trait 覆盖。整个应用只有一个
            // webview（label "main"），所以不需要判断来源。
            WindowEvent::Focused(focused) => {
                // 记一笔「失焦发生过」。这里确实在主线程上写文件，但焦点变化是
                // 人手动触发的（一次 Alt+Tab 一条），不是 Moved 那种每秒几十次
                // 的热路径 —— 一次追加写换一条时间线，值。绝不要往这里加
                // handle_moved 那类高频事件。
                info!("窗口焦点{}", if *focused { "进入" } else { "离开" });
                let _ = window.app_handle().emit("mino://focus-changed", *focused);
            }
            WindowEvent::CloseRequested { api, .. } => {
                // ✕ 只隐藏，不退出。真正的退出走托盘菜单。
                if !state::QUITTING.load(Ordering::SeqCst) {
                    info!("窗口关闭请求被拦截，改为隐藏");
                    api.prevent_close();
                    window::hide(window.app_handle());
                }
            }
            _ => {}
        })
        .build(tauri::generate_context!())
        .expect("构建 Tauri 应用失败")
        .run(|_app, event| match event {
            // 「窗口消失」到底是收缩成了感应条，还是进程真的退了 —— 分界就在
            // 有没有这一行。用户报的「拖窗口时程序直接关闭」正是要问这个。
            RunEvent::ExitRequested { api, .. } => {
                // 窗口全关了也不退出进程，否则托盘和全局快捷键会一起消失
                if state::QUITTING.load(Ordering::SeqCst) {
                    info!("退出请求放行（用户从托盘主动退出）");
                } else {
                    info!("退出请求被拦截（非主动退出）");
                    api.prevent_exit();
                }
            }
            RunEvent::Exit => info!("进程退出"),
            _ => {}
        });
}
