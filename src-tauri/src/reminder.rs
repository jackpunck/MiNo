//! 任务提醒：轮询到点判定 + 一个独立的置顶浮窗。
//!
//! **为什么是轮询而不是定时器。** 跨日归档那节已经为同一个问题写过理由：应用可能
//! 休眠或隐藏数天，`setTimeout` 之类的假设全都不成立。提醒撞上的是同一件事，而且
//! 更糟 —— MiNo 的设计就是长期隐藏，而隐藏窗口里的 WebView2 计时器会被浏览器按
//! 后台页面节流乃至冻结。**提醒最可能失败的场景，恰恰是它最该工作的场景。**
//! 所以这里只做一件事：定期比对「现在」和「该提醒的任务集合」。
//!
//! 这个形状顺带把「补提醒」做成了免费的：判据是「第一个 ≥ 提醒时刻的轮询」，而不是
//! 「提醒时刻那一瞬间」。合盖一整天，第二天打开时只要还是同一天、`reminded_on` 还没
//! 写，就照响。**刻意不设迟到的上限** —— 一条还在列表里的任务，晚上 11 点打开 MiNo
//! 时告诉你「今天这事还没做」，这句话仍然是对的。
//!
//! # ⚠️ 这个模块活在 `panic = "abort"` 的二进制里
//!
//! release 是 `panic = "abort"`，没有 `catch_unwind` 可用 —— **任何一次 panic 都是
//! 整个进程消失**，而这里的线程每 2 秒无人值守地跑一次、永远不停。所以本模块的每
//! 一个函数都必须**由构造保证不 panic**：不索引切片、不 `unwrap`、不裸 `+1`
//! （用 `wrapping_add`）、锁一律 `unwrap_or_else(|e| e.into_inner())`（对齐
//! `commands::data_guard`）。加代码之前先想清楚这一条。
//!
//! # ⚠️ 数据访问只能走 `commands`
//!
//! 落盘必须经由 [`crate::commands::read_state`] / [`crate::commands::mark_reminded`]
//! 这两个入口（它们持有 `DATA_LOCK`），**不要**在这里直接 `state::load` + `state::save`。
//! 而且这两个调用**必须顺序进行，不能嵌在同一个 guard 里** —— `DATA_LOCK` 是普通
//! 的非重入 `Mutex<()>`，嵌套直接死锁。

use std::sync::atomic::Ordering;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use chrono::Local;
use log::{info, warn};
use tauri::{AppHandle, Emitter, Manager};

use crate::state::{self, Todo};

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

/// 浮窗的窗口标签。`tauri.conf.json` 里那一项、以及 `capabilities/reminder.json`
/// 的 `windows` 都必须是这个名字，改一处就要改三处。
const POPUP_LABEL: &str = "reminder";

/// 主循环的睡眠间隔。它同时是「收起浮窗」的精度。
const TICK: Duration = Duration::from_secs(2);

/// 每多少个 tick 才读一次盘。2s × 15 = 30s 一次到点判定。
///
/// 读盘 + 解析 JSON 有成本，而提醒的准时性要求是分钟级的，30s 绰绰有余。
/// **别再往下调**：首次 poll 发生在这个间隔之后，这正是弹窗页面来得及加载完的保证。
const POLL_EVERY: u32 = 15;

/// 浮窗停留多久后自动收起。
const DISPLAY: Duration = Duration::from_secs(12);

// 浮窗布局，逻辑像素。**这里是唯一的计算处**，前端只负责把内容画进去。
const POPUP_W: f64 = 268.0;
const POPUP_HDR: f64 = 34.0;
const POPUP_ROW: f64 = 30.0;
const POPUP_PAD: f64 = 14.0;

/// 浮窗最多列几条。居中的卡片，多了就太挡 —— 超出只显示「还有 N 条」。
const MAX_ROWS: usize = 3;

/// 浮窗该收起的时刻。`None` = 当前没有浮窗。
///
/// **单一所有者**：只有 [`run`] 那个线程会读它并真正收起窗口。所以不需要「代次计数」
/// 那种东西 —— 新提醒来了只是把截止时刻往后推，上一条自然作废，这正是「不要提前收起
/// 新提醒」的性质，而且不用发明一个计数器。
static HIDE_AT: Mutex<Option<Instant>> = Mutex::new(None);

// ---------------------------------------------------------------------------
// 纯函数
//
// 本机没有 Rust 工具链，`cargo test` 只能靠 CI 跑 —— 这些纯函数是这个模块里唯一
// 能被真正验证逻辑的部分。
// ---------------------------------------------------------------------------

/// 校验并归一化一个 `"HH:MM"`。
///
/// **归一化（补零）不是洁癖，是正确性的前提。** 判「到点没有」用的是字符串比较，
/// 而 `"9:00" < "10:00"` 在字典序上是**真**的 —— 一个没补零的 `"9:00"` 会让这条提醒
/// 永远不响（它要等到 `"9:00"` 之后的某个字符串，而那一天的 10 点、11 点全都小于）。
///
/// 接受 `"9:00"` 这种输入（`u32::parse` 不要求前导零），产出永远是补零形式。
/// 其余一律 `None`：`"25:00"` / `"12:60"` / `"9"` / `"09:00:00"` / `""` / 带空格以外
/// 的任何杂物。
pub fn parse_hhmm(s: &str) -> Option<String> {
    let s = s.trim();
    let (h, m) = s.split_once(':')?;
    // 只允许纯数字：`u32::parse` 会接受 `"+9"`，那不是我们想要的
    if !h.bytes().all(|b| b.is_ascii_digit()) || !m.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let h: u32 = h.parse().ok()?;
    let m: u32 = m.parse().ok()?;
    if h > 23 || m > 59 {
        return None;
    }
    Some(format!("{h:02}:{m:02}"))
}

/// 把存盘的模式归一成 `"once"` / `"daily"`。
///
/// **认不出来的一律当 `"once"`。** 方向是刻意的：一次性的打扰远小于天天误报。
/// 一个值语义不明的字段被解释成「每天都要弹」是最坏的降级方向。
pub fn mode_or_once(mode: &str) -> &'static str {
    if mode.eq_ignore_ascii_case("daily") {
        "daily"
    } else {
        "once"
    }
}

/// 这条任务算不算「每日提醒任务」。
///
/// **定义只此一处**，`state::rollover`（决定要不要免归档）和 [`is_due`]（决定要不要
/// 弹）共用它。两处各写一份迟早会漂，而漂了的表现是「提醒还在响，任务却被归档了」
/// 这种自相矛盾的状态。
///
/// **要求时间也非空**：手改文件改出来的「daily 但没时间」不该拿到免归档的待遇 ——
/// 它根本不会响，没有理由为它破坏归档规则。
pub fn is_daily(t: &Todo) -> bool {
    t.remind_mode.eq_ignore_ascii_case("daily")
        && matches!(t.remind_at.as_deref(), Some(s) if !s.is_empty())
}

/// 这一轮该不该为它弹提醒。`today` = `YYYY-MM-DD`，`hhmm` = 补零的 `HH:MM`。
///
/// 五条全满足才算到点。每一条都有理由：
///
///   - `!completed` —— 做完了就别再念叨。用户在提醒到点前把它勾了，那是最好的结果，
///     不该反过来弹一条「你已经做完的事」。
///   - `remind_at` 非空 —— 没设提醒（老数据、或清空过）当然不弹。
///   - `reminded_on != today` —— 今天已经弹过了。这一条同时挡住了「重启重放」。
///   - `hhmm >= at` —— 还没到点。**用 ≥ 而不是 =**：轮询是离散的，要求恰好相等就
///     等于永远不触发。这也就是「补提醒」的来源。
///   - 每日，或者 `remind_date == today` —— `once` 只在自己那天响。**过了那天就不再
///     补**：跨天补一次「一次性提醒」会让人莫名其妙（「我昨天设的 3 点，今天才弹？」）。
///
/// 假定 `remind_at` 已经归一化（写入前一定过 [`parse_hhmm`]）。手改文件塞进去的
/// `"9:00"` 会在字典序上吃亏而永不触发 —— 那是可接受的降级，不是要防的路径。
pub fn is_due(t: &Todo, today: &str, hhmm: &str) -> bool {
    if t.completed {
        return false;
    }
    let Some(at) = t.remind_at.as_deref() else {
        return false;
    };
    if at.is_empty() || t.reminded_on == today || hhmm < at {
        return false;
    }
    if is_daily(t) {
        return true;
    }
    // `once`：没写日期就回落到创建日 —— 常见情况（建任务时顺手设提醒）不必多写一个键
    let day = if t.remind_date.is_empty() {
        t.created_date.as_str()
    } else {
        t.remind_date.as_str()
    };
    day == today
}

// ---------------------------------------------------------------------------
// 调度
// ---------------------------------------------------------------------------

/// 起调度线程。**失败只记日志，不阻断启动** —— `setup()` 的每一步都要能降级。
pub fn spawn(app: &AppHandle) {
    let app = app.clone();
    let spawned = std::thread::Builder::new()
        .name("mino-reminder".into())
        .spawn(move || run(app));

    if let Err(e) = spawned {
        warn!("提醒线程启动失败，本次运行不会有任何提醒: {e}");
    }
}

fn run(app: AppHandle) {
    let mut n: u32 = 0;
    loop {
        std::thread::sleep(TICK);

        // 退出前先看一眼。不检查的话这个线程会跟着进程一起消失，
        // 但显式的出口让它有机会在托盘退出时干净地停下来。
        if state::QUITTING.load(Ordering::SeqCst) {
            return;
        }

        tick_hide(&app);

        // `wrapping_add` 而不是 `+=`：debug 构建下溢出是 panic，而 abort 下 panic
        // 就是进程消失。这个计数器连续跑几十年才会回绕，代价为零，何必留个雷。
        n = n.wrapping_add(1);
        if n % POLL_EVERY == 0 {
            poll(&app);
        }
    }
}

/// 到点就把浮窗收起来。返回本次是否收起了。
fn tick_hide(app: &AppHandle) -> bool {
    let expired = {
        let g = HIDE_AT.lock().unwrap_or_else(|e| e.into_inner());
        matches!(*g, Some(t) if Instant::now() >= t)
    };
    if !expired {
        return false;
    }

    hide_popup(app);
    true
}

/// 立刻收起浮窗并取消自动收起计时。
pub fn hide_popup(app: &AppHandle) {
    *HIDE_AT.lock().unwrap_or_else(|e| e.into_inner()) = None;
    if let Some(w) = app.get_webview_window(POPUP_LABEL) {
        let _ = w.hide();
    }
}

/// 一轮到点判定。
fn poll(app: &AppHandle) {
    // 每次都重新算：这个线程会跨越零点，任何缓存都会变成「昨天」。
    let today = state::today_string();
    let hhmm = Local::now().format("%H:%M").to_string();

    let due: Vec<Todo> = {
        let data = crate::commands::read_state(app);
        data.todos
            .iter()
            .filter(|t| is_due(t, &today, &hhmm))
            .cloned()
            .collect()
    };
    if due.is_empty() {
        return;
    }

    let ids: Vec<String> = due.iter().map(|t| t.id.clone()).collect();

    // ⚠️ **先落盘，再弹窗。顺序不能颠倒。**
    //
    // 反过来是：显示成功但落盘失败 → 30 秒后同一批又会命中 → 无限重弹。
    // 当前顺序最坏的情况是「少提醒一次」（落盘失败就 return，下一轮重试），
    // 而弹窗轰炸会让人直接卸载这个应用。
    //
    // 注意 read_state 与 mark_reminded 是**顺序**调用的，各自取放一次 DATA_LOCK。
    // 别为了「少一次读盘」把两者塞进同一个 guard —— 那是死锁。
    if let Err(e) = crate::commands::mark_reminded(app, &ids, &today) {
        warn!("提醒状态落盘失败，本轮跳过（下一轮会重试）: {e}");
        return;
    }

    // 只记条数。**绝不记 todo.text** —— 任务正文在用户目录里，不该在日志里多存一份。
    info!("提醒触发 {} 条", due.len());
    show_popup(app, &due);
}

/// 摆好位置、显示浮窗、把内容推给页面。
fn show_popup(app: &AppHandle, due: &[Todo]) {
    let Some(w) = app.get_webview_window(POPUP_LABEL) else {
        // 窗口没了（配置里删了？被销毁了？）—— 提醒已经落盘，所以不会重弹，
        // 只是这一次看不见。记一条日志，别 panic。
        warn!("找不到提醒浮窗（label {POPUP_LABEL}），本次提醒只留下日志");
        return;
    };

    // 高度要**算准**：算少了内容被裁掉，算多了下面露出一块透明区域 ——
    // 而那块透明区域在 Windows 上照样吃掉鼠标点击。
    //
    // 超出 MAX_ROWS 时会多出一行「还有 N 条」，它也得占一格高度，
    // 所以这里比前端的 `.item` 数量多算一行。前端的 `.more` 用的是同一个
    // `--row-h`，两边的数字由 ui/reminder.css 顶部那段注释互相钉住。
    let shown = due.len().min(MAX_ROWS);
    let overflow_row = usize::from(due.len() > MAX_ROWS);
    let logical_h = POPUP_HDR + POPUP_PAD + (shown + overflow_row) as f64 * POPUP_ROW;

    // 逻辑 → 物理，同 window.rs 的单位纪律：混用会在 125% 缩放的屏幕上偏差 25%。
    let scale = w.scale_factor().unwrap_or(1.0);
    let pw = (POPUP_W * scale).round().max(1.0) as u32;
    let ph = (logical_h * scale).round().max(1.0) as u32;

    let _ = w.set_size(tauri::PhysicalSize::new(pw, ph));
    if let Some((x, y)) = popup_pos(app, pw as i32, ph as i32) {
        let _ = w.set_position(tauri::PhysicalPosition::new(x, y));
    }

    // **刻意不调 `set_focus()`。** 提醒是通知，不是模态框：抢走用户正在输入的焦点
    // 很讨厌，而且那次焦点变化还会让主窗口收到 `Focused(false)` 而自己收起来。
    let _ = w.show();

    let items: Vec<serde_json::Value> = due
        .iter()
        .take(MAX_ROWS)
        .map(|t| {
            serde_json::json!({
                "id": t.id,
                "text": t.text,
                "mode": mode_or_once(&t.remind_mode),
            })
        })
        .collect();

    // 用 `app.emit` 广播，**不用 `emit_to`**：tools/check-commands.mjs 的 Rust 侧
    // 正则只认 `.emit(`，`emit_to` 会让前端的 `listen` 匹配不到、CI 直接红。
    // 广播到主窗口也无害 —— 那边没有监听 `mino://reminder`。
    let _ = app.emit(
        "mino://reminder",
        serde_json::json!({
            "items": items,
            "more": due.len().saturating_sub(MAX_ROWS),
        }),
    );

    *HIDE_AT.lock().unwrap_or_else(|e| e.into_inner()) = Some(Instant::now() + DISPLAY);
}

/// 浮窗在**主窗口所在那台显示器**的正中。
///
/// 跟主窗口走而不是固定主屏：多显示器下用户正在副屏工作，把提醒弹到主屏等于没提醒。
/// 显示器的 `position` / `size` 都是物理像素，所以传进来的尺寸也必须是物理的。
fn popup_pos(app: &AppHandle, pw: i32, ph: i32) -> Option<(i32, i32)> {
    let main = app.get_webview_window("main")?;
    let mon = crate::window::monitor_of(&main)?;
    let p = mon.position();
    let s = mon.size();
    Some((
        p.x + (s.width as i32 - pw) / 2,
        p.y + (s.height as i32 - ph) / 2,
    ))
}

// ---------------------------------------------------------------------------
// 测试
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    //! 全部是纯函数。`parse_hhmm` / `is_due` / `is_daily` 是本模块唯一能在 CI 上被
    //! 真正验证的部分 —— 轮询线程、浮窗、IPC 本机都验不了。

    use super::*;
    // 显式写一遍，不依赖 `use super::*` 是否顺带带出父模块那次 private use。
    use crate::state::Todo;

    /// 造一条「什么都没设」的任务，再按需覆盖字段。
    fn todo() -> Todo {
        Todo {
            id: "t1".into(),
            text: "吃药".into(),
            completed: false,
            priority: 1,
            created_date: "2026-10-13".into(),
            created_at: 1,
            completed_at: None,
            archived_at: None,
            order: 0,
            font: String::new(),
            font_id: None,
            remind_at: None,
            remind_mode: String::new(),
            remind_date: String::new(),
            reminded_on: String::new(),
        }
    }

    fn once(at: &str, date: &str) -> Todo {
        Todo {
            remind_at: Some(at.into()),
            remind_mode: "once".into(),
            remind_date: date.into(),
            ..todo()
        }
    }

    fn daily(at: &str) -> Todo {
        Todo {
            remind_at: Some(at.into()),
            remind_mode: "daily".into(),
            ..todo()
        }
    }

    #[test]
    fn hhmm_is_zero_padded() {
        assert_eq!(parse_hhmm("9:00").as_deref(), Some("09:00"));
        assert_eq!(parse_hhmm("9:5").as_deref(), Some("09:05"));
        assert_eq!(parse_hhmm("23:59").as_deref(), Some("23:59"));
        assert_eq!(parse_hhmm("00:00").as_deref(), Some("00:00"));
    }

    #[test]
    fn hhmm_is_idempotent() {
        assert_eq!(parse_hhmm("09:00").as_deref(), Some("09:00"));
    }

    #[test]
    fn hhmm_trims_surrounding_space() {
        assert_eq!(parse_hhmm(" 8:30 ").as_deref(), Some("08:30"));
    }

    #[test]
    fn hhmm_rejects_everything_else() {
        for bad in [
            "", "9", "09", "24:00", "25:00", "12:60", "09:00:00", "9:0:0", "abc", ":", ":30",
            "09:", "-1:00", "+9:00", "1e:00", "9：00",
        ] {
            assert!(parse_hhmm(bad).is_none(), "{bad:?} 不该被接受");
        }
    }

    #[test]
    fn mode_falls_back_to_once() {
        assert_eq!(mode_or_once("daily"), "daily");
        assert_eq!(mode_or_once("DAILY"), "daily");
        assert_eq!(mode_or_once("once"), "once");
        assert_eq!(mode_or_once(""), "once");
        assert_eq!(mode_or_once("每周"), "once");
        assert_eq!(mode_or_once("garbage"), "once");
    }

    #[test]
    fn daily_needs_both_mode_and_a_time() {
        assert!(is_daily(&daily("09:00")));
        assert!(is_daily(&Todo {
            remind_mode: "DAILY".into(),
            remind_at: Some("09:00".into()),
            ..todo()
        }));

        // 模式是 daily 但没时间 —— 它根本不会响，不给它免归档的待遇
        assert!(!is_daily(&Todo {
            remind_mode: "daily".into(),
            remind_at: None,
            ..todo()
        }));
        assert!(!is_daily(&Todo {
            remind_mode: "daily".into(),
            remind_at: Some(String::new()),
            ..todo()
        }));

        assert!(!is_daily(&once("09:00", "2026-10-13")));
        assert!(!is_daily(&todo()));
    }

    /// 这条是「老数据行为不变」的回归测试：四个新字段全默认值时，
    /// 任何一天的任何时刻都不该为它弹提醒。
    #[test]
    fn nothing_is_due_without_a_reminder() {
        assert!(!is_due(&todo(), "2026-10-13", "23:59"));
    }

    #[test]
    fn once_fires_on_its_own_day() {
        assert!(is_due(&once("09:00", "2026-10-13"), "2026-10-13", "09:00"));
        assert!(is_due(&once("09:00", "2026-10-13"), "2026-10-13", "23:00"));
    }

    /// 边界：轮询间隔是 30s，恰好落在提醒时刻上必须算到点。
    #[test]
    fn exactly_on_the_minute_is_due() {
        assert!(is_due(&daily("09:00"), "2026-10-13", "09:00"));
    }

    #[test]
    fn not_yet_due_before_the_time() {
        assert!(!is_due(&daily("09:00"), "2026-10-13", "08:59"));
    }

    /// 补提醒：MiNo 早上 9 点没开着，中午才启动 —— 仍然要响。
    #[test]
    fn catches_up_later_the_same_day() {
        assert!(is_due(&daily("09:00"), "2026-10-13", "12:30"));
    }

    /// 一次性提醒过了那一天就不再补 —— 跨天补弹会让人莫名其妙。
    #[test]
    fn once_is_not_caught_up_on_a_later_day() {
        assert!(!is_due(&once("09:00", "2026-10-12"), "2026-10-13", "23:00"));
    }

    #[test]
    fn once_falls_back_to_the_creation_date() {
        let t = Todo {
            remind_at: Some("09:00".into()),
            remind_mode: "once".into(),
            remind_date: String::new(),
            created_date: "2026-10-13".into(),
            ..todo()
        };
        assert!(is_due(&t, "2026-10-13", "09:00"));
        assert!(!is_due(&t, "2026-10-14", "09:00"));
    }

    #[test]
    fn daily_fires_again_the_next_day() {
        let mut t = daily("09:00");
        t.reminded_on = "2026-10-13".into();
        assert!(!is_due(&t, "2026-10-13", "10:00"));
        assert!(is_due(&t, "2026-10-14", "10:00"));
    }

    #[test]
    fn already_reminded_today_is_skipped() {
        let mut t = daily("09:00");
        t.reminded_on = "2026-10-13".into();
        assert!(!is_due(&t, "2026-10-13", "23:59"));
    }

    #[test]
    fn completed_is_never_due() {
        let mut t = daily("09:00");
        t.completed = true;
        assert!(!is_due(&t, "2026-10-13", "23:59"));

        let mut o = once("09:00", "2026-10-13");
        o.completed = true;
        assert!(!is_due(&o, "2026-10-13", "23:59"));
    }

    #[test]
    fn empty_remind_at_is_never_due() {
        let t = Todo {
            remind_at: Some(String::new()),
            remind_mode: "daily".into(),
            ..todo()
        };
        assert!(!is_due(&t, "2026-10-13", "23:59"));
    }

    /// 未知模式降级成 once —— 也就是「只在自己那天响」。
    #[test]
    fn unknown_mode_behaves_like_once() {
        let t = Todo {
            remind_at: Some("09:00".into()),
            remind_mode: "每周".into(),
            remind_date: "2026-10-13".into(),
            ..todo()
        };
        assert!(is_due(&t, "2026-10-13", "09:00"));
        assert!(!is_due(&t, "2026-10-14", "09:00"));
    }
}
