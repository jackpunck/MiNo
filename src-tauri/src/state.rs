//! 数据模型、持久化、损坏容错与跨日迁移。
//!
//! Rust 是数据的唯一权威来源：前端只持有一份用于渲染的镜像，任何修改都通过
//! command 落到这里再返回最新状态。这样可以避免「前端算出来的日期」和
//! 「Rust 落盘的日期」各说各话 —— 跨日归档对一致性要求很高。

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;

use chrono::Local;
use log::{error, info, warn};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

/// 托盘「退出」会把它置为 true，`RunEvent::ExitRequested` 据此放行，
/// 否则一律阻止退出以保持后台驻留。
pub static QUITTING: AtomicBool = AtomicBool::new(false);

pub const DATA_VERSION: u32 = 4;

/// 归档上限。archives 只增不减会随着时间无限膨胀，超出后丢弃最旧的记录。
const ARCHIVE_LIMIT: usize = 500;

// ---------------------------------------------------------------------------
// 数据模型
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Todo {
    pub id: String,
    pub text: String,
    #[serde(default)]
    pub completed: bool,
    #[serde(default)]
    pub priority: i32,
    /// YYYY-MM-DD，本地日期。跨日判断用它。
    #[serde(default)]
    pub created_date: String,
    /// Unix 毫秒。排序用它 —— createdDate 只精确到天，同一天内无法定序。
    #[serde(default)]
    pub created_at: i64,
    #[serde(default)]
    pub completed_at: Option<i64>,
    #[serde(default)]
    pub archived_at: Option<i64>,
    /// 用户拖动排序的键，越小越靠前。
    ///
    /// 老数据没有这个字段，`#[serde(default)]` 会把它们全填成 0 —— 那时它是个
    /// 恒等键，排序自然落回 `created_at` 倒序，与加字段之前逐字节一致。
    /// 换句话说：**不需要迁移，也绝不能把它写成非 0 的默认值**。
    #[serde(default)]
    pub order: i64,
    /// 这条任务自己的字体链（完整 CSS font-family）。
    ///
    /// **必须带字段级 `#[serde(default)]`**：`Todo` 没有容器级 default，老数据里
    /// 根本没有这个键，解析会直接失败 → `load()` 把 data.json 改名成 .broken →
    /// 用户看到的是全部待办凭空消失。这是本仓库后果最重的错误。
    ///
    /// 空串只可能出现在「用户手改过文件」这种场合，前端会回退到全局字体。
    /// 正常数据里它一定是被写死的：老任务在 `migrate` 的 v2 → v3 里盖章，
    /// 新任务在 `add_todo` 里盖章 —— 所以**改全局字体不会牵连任何已有任务**。
    #[serde(default)]
    pub font: String,
    /// 这条任务的字体对应的导入字体 id；系统字体 / 默认字体 / 无法确定时为 None。
    ///
    /// 必须单独存下来：导入字体的字节不跨进程缓存，重启后要靠这个 id 重新
    /// `ensureFont`，否则那条任务会静默回退成默认字体，用户看到的是「字体自己变了」。
    #[serde(default)]
    pub font_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CustomFont {
    /// 内容哈希，同时用作磁盘文件名与去重键。
    pub id: String,
    /// 我们分配给它的 CSS family 名，保证是 ASCII 且不会撞上系统字体。
    pub family: String,
    /// 用户原始文件名，仅用于显示（必须以 textContent 渲染）。
    pub label: String,
    /// 相对于 app data 目录的路径，如 "fonts/3f2a...ttf"。
    pub file: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Position {
    pub x: i32,
    pub y: i32,
}

/// `#[serde(default)]` 必须放在容器上，理由和 `AppData` 一样，但后果严重得多：
/// `AppData` 上的 default 只在 `settings` **整个键**缺失时才生效，而现存用户的
/// data.json 个个都有 settings、个个都没有新加的字段。没有这一行，加任何新字段
/// 都会让 `serde_json::from_str::<AppData>` 返回 Err，`load()` 随即走
/// `backup_broken()` —— 用户的待办会从界面上**整体消失**。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub always_on_top: bool,
    pub window_mode: String,
    /// 上次执行跨日检查的本地日期。空字符串表示从未检查过（首次启动）。
    pub last_check_date: String,
    pub bg_type: String,
    pub bg_path: Option<String>,
    pub mask_opacity: f64,
    pub blur: f64,
    /// 便签正文颜色，`#rrggbb`。由 `set_text_color` 校验后写入，前端落成 CSS 变量。
    ///
    /// 这里**不要**加字段级 `#[serde(default)]`：那样缺字段时取的是
    /// `String::default()`（空串），而不是 `Settings::default()` 里的默认色。
    /// 容器级 default 才会取到 `impl Default` 的值。
    pub text_color: String,
    /// 便签正文使用的完整 CSS font-family 回退链。
    pub font_family: String,
    /// 当前生效的导入字体 id；使用系统字体或默认字体时为 None。
    pub font_id: Option<String>,
    pub custom_fonts: Vec<CustomFont>,
    pub window_position: Option<Position>,
    pub shortcut: String,
    pub edge_snap: bool,
    pub edge_threshold: i32,
    pub edge_handle_size: i32,
}

/// 便签正文的默认字体链。英文名在前、中文名在后：
/// Chromium 通过 DirectWrite 解析 family，中文名在部分系统上匹配不到，
/// 而英文名在所有 locale 下都稳定，最后兜底到 sans-serif。
pub const DEFAULT_FONT_CHAIN: &str =
    "\"Segoe UI\", \"Microsoft YaHei\", \"微软雅黑\", system-ui, sans-serif";

/// 给导入字体分配的 family 名前缀，后面接 id 的前 8 位。
///
/// **这个串会被写进磁盘**：`settings.fontFamily`、`custom_fonts[*].family`、
/// 以及每条 `todo.font` 都带着它。所以改前缀不是改个常量就完事，必须配一次
/// `rename_font_family_prefix` 迁移。
///
/// 与 `ui/fonts.js` 的 `familyOf()` 必须逐字一致 —— 前端靠它构造 `FontFace`，
/// 对不上就会静默回退到链尾的 sans-serif。
pub const FONT_FAMILY_PREFIX: &str = "MiNo ";

/// 改名前的老前缀。**只给迁移用**，别在新代码里引用它。
pub const LEGACY_FONT_FAMILY_PREFIX: &str = "MiniMemo ";

/// 便签正文的默认颜色，与 styles.css 里 `--note-text` 的初始值必须一致。
pub const DEFAULT_TEXT_COLOR: &str = "#f5f5f7";

/// 自定义图片上遮罩的默认浓度。
///
/// 取值偏淡（0.28）是有意的：配套的 text-shadow 与文字颜色选择负责在亮图上
/// 撑住对比度，遮罩只需要把最刺眼的那部分压下去 —— 浓了图片就白设了。
pub const DEFAULT_MASK_OPACITY: f64 = 0.28;

impl Default for Settings {
    fn default() -> Self {
        Self {
            always_on_top: true,
            window_mode: "expanded".into(),
            last_check_date: String::new(),
            bg_type: "mica".into(),
            bg_path: None,
            mask_opacity: DEFAULT_MASK_OPACITY,
            // 6.0 而不是 0：transparent 窗口下这个 blur 模糊的是**桌面**，
            // 无自定义图片时它是亚克力质感的唯一来源。有图片时由 CSS 强制关掉。
            blur: 6.0,
            text_color: DEFAULT_TEXT_COLOR.into(),
            font_family: DEFAULT_FONT_CHAIN.into(),
            font_id: None,
            custom_fonts: Vec::new(),
            window_position: None,
            shortcut: "Alt+Space".into(),
            edge_snap: true,
            edge_threshold: 10,
            edge_handle_size: 4,
        }
    }
}

/// `#[serde(default)]` 放在容器上：缺字段时用 `Default::default()` 补齐，
/// 而不是让整个文件解析失败。这正是规格 §11.4 的「字段缺失 → 使用默认值补齐」。
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct AppData {
    pub version: u32,
    pub settings: Settings,
    pub todos: Vec<Todo>,
    pub archives: Vec<Todo>,
}

// ---------------------------------------------------------------------------
// 时间辅助
// ---------------------------------------------------------------------------

pub fn today_string() -> String {
    Local::now().format("%Y-%m-%d").to_string()
}

pub fn now_ms() -> i64 {
    Local::now().timestamp_millis()
}

// ---------------------------------------------------------------------------
// 路径
// ---------------------------------------------------------------------------

/// 应用数据目录。由 Tauri path API 解析（Windows 上是
/// `%APPDATA%\com.mino.app\`），绝不硬编码 %APPDATA%。
pub fn data_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

pub fn data_file(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(data_dir(app)?.join("data.json"))
}

pub fn fonts_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = data_dir(app)?.join("fonts");
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

/// 改名前的 identifier。数据目录名就是它（`%APPDATA%\com.minimemo.app\`）。
const LEGACY_IDENTIFIER: &str = "com.minimemo.app";

/// 把老 identifier 的数据目录整体接管过来（`com.minimemo.app` → `com.mino.app`）。
///
/// 必须在 `logging::init()` **之前**调用。日志文件也住在这个目录里：先搬完再开
/// 日志，老日志跟着一起过来；顺序反了就会先在**新**目录里建出一个空日志，老日志
/// 留在原地，而搬迁会因为「新目录已存在」直接放弃 —— 用户的待办就真没了。
///
/// 三条规则，合起来保证「要么整体搬成功，要么一个字节都不动」：
///
///   1. 老目录不存在 → 全新安装，什么都不做
///   2. 新目录里已经有 data.json → 认为搬过了，绝不覆盖
///   3. 否则：能 rename 就 rename（同盘，不复制字节、不留半截状态），
///      走不通再退到逐文件复制
///
/// **老目录一律不删。** 任何失败都只 warn、不阻断启动（规格 §21）：最坏情况是用
/// 户看到初始状态，而他的数据原封不动躺在老目录里，日志里写着两个路径。
///
/// 这个函数**没有单元测试覆盖** —— 它要一个真的 `AppHandle`。改它请务必实机走一
/// 遍「装老版 → 产生数据 → 装新版」，`cargo check` 只证明它编译得过。
pub fn adopt_legacy_data_dir(app: &AppHandle) {
    // 刻意不用 state::data_dir()：那个会 create_dir_all，而新目录一旦被建出来，
    // 下面的 rename 在 Windows 上必然失败 —— MoveFileEx 没法把一个目录并进一个
    // 已经存在的目录。
    let Ok(new_dir) = app.path().app_data_dir() else {
        return;
    };
    let Some(parent) = new_dir.parent() else {
        return;
    };
    let old_dir = parent.join(LEGACY_IDENTIFIER);

    if !old_dir.is_dir() {
        return;
    }
    if new_dir.join("data.json").is_file() {
        return;
    }

    if !new_dir.exists() {
        if fs::rename(&old_dir, &new_dir).is_ok() {
            info!(
                "数据目录已接管: {} -> {}",
                old_dir.display(),
                new_dir.display()
            );
            return;
        }
    }

    // rename 走不通（新目录已存在，或者撞上别的实例还没释放的文件句柄）就退到逐
    // 文件复制。复制**不动老目录**，所以这条路只增不减：中途失败下次启动会接着补完。
    match copy_tree(&old_dir, &new_dir) {
        Ok(()) => info!(
            "数据目录已复制: {} -> {}（老目录保留）",
            old_dir.display(),
            new_dir.display()
        ),
        Err(e) => warn!(
            "数据目录接管失败: {e}（老数据仍在 {}，可手动移动到 {}）",
            old_dir.display(),
            new_dir.display()
        ),
    }
}

/// 递归复制目录，已存在的文件直接覆盖。
///
/// 覆盖语义是必需的：它让这个函数可以安全地重复调用，上面那句「新目录里有
/// data.json」也才能兼作「已经搬完了」的判据。
fn copy_tree(src: &Path, dst: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dst)?;

    let mut entries = Vec::new();
    for entry in fs::read_dir(src)? {
        entries.push(entry?);
    }

    // data.json 排最后。它不是普通文件，而是「这一趟搬完了」的完成标记：它一旦
    // 落地，`adopt_legacy_data_dir` 开头那句守卫就会认定一切就绪。反过来说，要是
    // 它在 fonts/ 之前就位，一次半截复制会被误判成成功，而缺了 fonts/ 会让
    // `prune_missing` 把用户导入的字体全部清掉。
    entries.sort_by_key(|e| e.file_name() == "data.json");

    for entry in entries {
        let target = dst.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_tree(&entry.path(), &target)?;
        } else {
            fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// 读写
// ---------------------------------------------------------------------------

/// 把损坏的文件挪到一边，保留现场供排查，绝不阻塞启动。
fn backup_broken(path: &PathBuf) {
    let stamp = Local::now().format("%Y%m%d%H%M%S");
    let target = path.with_file_name(format!("data.json.{stamp}.broken"));
    match fs::rename(path, &target) {
        Ok(()) => warn!("损坏的数据文件已备份到 {}", target.display()),
        Err(e) => error!("备份损坏数据文件失败: {e}"),
    }
}

/// 把老待办「当时正在用的字体」写死到每一条上（v2 → v3 的一部分）。
///
/// 迁移之前字体是全局的，所以迁移这一刻的 `settings.font_family` 就是这些待办
/// 眼睛看到的字体。写死它，显示效果与升级前逐像素一致；此后再改全局字体，
/// 它们也不会跟着变 —— 这正是需求要的「只影响新任务」。
///
/// 只有 `font` 为空（= 从没盖过章）的才处理，所以重复调用是幂等的。
fn stamp_missing_todo_fonts(data: &mut AppData) -> usize {
    // 先把两个值 clone 出来：同一个 data 上不能同时有可变借用和不可变借用
    let family = data.settings.font_family.clone();
    let id = data.settings.font_id.clone();
    let mut n = 0;

    for t in &mut data.todos {
        if t.font.trim().is_empty() {
            t.font = family.clone();
            t.font_id = id.clone();
            n += 1;
        }
    }
    n
}

/// 把当前全局字体盖到所有未归档任务上（设置面板的「应用到全部」）。
///
/// 注意是**写入**而不是清空。清空看着更省事（空串 = 回退到全局，语义上是等价的），
/// 但那样用户之后每改一次字体，这些任务又会跟着变 —— 等于「应用到全部」把用户的
/// 待办打回了「跟随全局」，与这个按钮的用途正好相反。
///
/// `archives` 不动：归档条目不再渲染，改了只是徒增体积。
pub fn apply_font_to_all(data: &mut AppData) -> usize {
    let family = data.settings.font_family.clone();
    let id = data.settings.font_id.clone();
    let mut n = 0;

    for t in &mut data.todos {
        if t.font != family || t.font_id != id {
            n += 1;
        }
        t.font = family.clone();
        t.font_id = id.clone();
    }
    n
}

/// 把导入字体的 family 前缀从 `MiniMemo ` 改写成 `MiNo `（v3 → v4）。
///
/// 这个前缀**写在磁盘上**三处，漏掉任何一处的表现都是「我导入的字体自己变了」：
///
///   - `settings.font_family`：全局字体链
///   - `settings.custom_fonts[*].family`：`list_fonts` 拿它拼预览链，最容易漏 ——
///     它不参与便签正文渲染，出了问题只有字体列表看起来是坏的
///   - `todos[*].font` 与 `archives[*].font`：建任务时盖的那个章
///
/// 不改写的话它们指向的 family 永远注册不进来，浏览器静默回退到链尾的
/// `sans-serif`，而且用户怎么改字体设置都救不回来。
///
/// 幂等（改完就再也匹配不到老前缀），所以**刻意不加版本门控**：这样连手工还原过
/// 老备份的数据也能被顺手修好。
///
/// 代价是每次 `load()` 都会扫一遍 todos —— `load` 不只在启动时跑，每条 command
/// 都要走一次。但那点 `contains` 跟 `load` 本来就得做的整份 JSON 解析比完全是
/// 噪声，不值得为它引入一个「迁移过没有」的状态位（那个状态位自己出错的方式，
/// 恰恰就是「迁移没跑但没人发现」）。
fn rename_font_family_prefix(data: &mut AppData) -> usize {
    fn fix(s: &mut String) -> usize {
        if s.contains(LEGACY_FONT_FAMILY_PREFIX) {
            *s = s.replace(LEGACY_FONT_FAMILY_PREFIX, FONT_FAMILY_PREFIX);
            1
        } else {
            0
        }
    }

    let mut n = fix(&mut data.settings.font_family);

    for f in &mut data.settings.custom_fonts {
        n += fix(&mut f.family);
    }
    for t in &mut data.todos {
        n += fix(&mut t.font);
    }
    // archives 现在不渲染，但留着一条指向失效 family 的记录就是下一次「字体自己
    // 变了」的引信 —— 跟 prune_missing 要把 todos 清干净是同一个理由。
    for t in &mut data.archives {
        n += fix(&mut t.font);
    }

    n
}

fn migrate(data: &mut AppData) {
    // 「填空」类的修复要排在版本迁移**前面**：v2 → v3 会拿 settings.font_family
    // 去给老待办盖章，先把空值补成默认链，否则盖下去的是个空串。
    //
    // 老数据可能没有字体链，补上默认值而不是留空（空字符串会让 CSS 整体失效）
    if data.settings.font_family.trim().is_empty() {
        data.settings.font_family = DEFAULT_FONT_CHAIN.into();
    }

    // 同理：颜色为空会让 `color:` 整条声明失效。正常情况下容器级 default 已经
    // 兜住了，这里防的是手工编辑过的或早期版本写坏的 data.json。
    if data.settings.text_color.trim().is_empty() {
        data.settings.text_color = DEFAULT_TEXT_COLOR.into();
    }

    // 改名迁移：`MiniMemo ` → `MiNo `。位置很讲究 —— 必须在上面两个「填空」
    // **之后**（空链先补成默认链，省得对一个空串做替换），又必须在下面 v2 → v3
    // 的盖章**之前**：盖章是从 `settings.font_family` 拷贝，先把它改对，盖下去的
    // 才是新前缀。两处都做也没关系，但先做更省事。
    let renamed = rename_font_family_prefix(data);
    if renamed > 0 {
        info!("已改写 {renamed} 处字体 family 前缀");
    }

    // v1 → v2：遮罩默认值从 0.65 降到 0.28。
    //
    // 之所以敢直接覆盖老用户的取值：`maskOpacity` 和 `blur` 这两个字段虽然一直
    // 写在 data.json 里，但**前端从来没有读过它们** —— CSS 里是硬编码的
    // `blur(6px)` 和 `var(--mask)`。所以磁盘上那个 0.65 是初始默认值，不是任何
    // 人的真实选择，重写它不可能覆盖谁的心意。
    // 一旦哪天前端开始读这两个字段，这个迁移就不再安全了。
    if data.version < 2 {
        data.settings.mask_opacity = DEFAULT_MASK_OPACITY;
    }

    // v2 → v3：字体从「全局一个」改成「每条任务各自记」。
    //
    // 这一步**不能省**。如果只给 `Todo::font` 一个空默认值、靠前端回退到当前的
    // `settings.font_family`，那用户之后每改一次字体，旧任务还是会一起变 ——
    // 正是这次要修的那个问题。必须在迁移时把当时的字体钉死。
    if data.version < 3 {
        let n = stamp_missing_todo_fonts(data);
        if n > 0 {
            info!("已为 {n} 条历史任务固定字体");
        }
    }

    if data.version < DATA_VERSION {
        info!("数据版本 {} -> {}", data.version, DATA_VERSION);
        data.version = DATA_VERSION;
    }
}

/// 读取状态。任何异常都降级为默认数据并继续启动。
pub fn load(app: &AppHandle) -> AppData {
    let path = match data_file(app) {
        Ok(p) => p,
        Err(e) => {
            error!("无法解析数据目录，使用默认数据: {e}");
            return AppData::default();
        }
    };

    if !path.exists() {
        info!("未找到 data.json，创建默认数据");
        return AppData::default();
    }

    let text = match fs::read_to_string(&path) {
        Ok(t) => t,
        Err(e) => {
            error!("读取 data.json 失败: {e}");
            return AppData::default();
        }
    };

    match serde_json::from_str::<AppData>(&text) {
        Ok(mut data) => {
            migrate(&mut data);
            data
        }
        Err(e) => {
            error!("data.json 解析失败，回退到默认数据: {e}");
            backup_broken(&path);
            AppData::default()
        }
    }
}

/// 原子写入：临时文件 → flush → sync → rename。
/// Rust 的 `fs::rename` 在 Windows 上走 MoveFileEx(MOVEFILE_REPLACE_EXISTING)，
/// 因此覆盖已存在的文件也是原子的，不会出现半截 JSON。
pub fn save(app: &AppHandle, data: &AppData) -> Result<(), String> {
    let path = data_file(app)?;
    let tmp = path.with_file_name("data.json.tmp");

    let json = serde_json::to_string_pretty(data).map_err(|e| e.to_string())?;

    {
        let mut f = fs::File::create(&tmp).map_err(|e| e.to_string())?;
        f.write_all(json.as_bytes()).map_err(|e| e.to_string())?;
        f.flush().map_err(|e| e.to_string())?;
        f.sync_all().map_err(|e| e.to_string())?;
    }

    fs::rename(&tmp, &path).map_err(|e| format!("替换 data.json 失败: {e}"))
}

// ---------------------------------------------------------------------------
// 跨日迁移
// ---------------------------------------------------------------------------

/// 已完成的今日任务归档，未完成的保留。
///
/// 返回归档条数。应用可能休眠或隐藏数天，所以这里只比对日期字符串，
/// 不做任何基于定时器的假设（规格 §10.2）。
pub fn rollover(data: &mut AppData) -> usize {
    let today = today_string();
    if data.settings.last_check_date == today {
        return 0;
    }

    let first_run = data.settings.last_check_date.is_empty();
    let mut archived = 0;

    if !first_run {
        let mut keep = Vec::new();
        for todo in data.todos.drain(..) {
            if todo.completed {
                let mut t = todo;
                t.archived_at = Some(now_ms());
                data.archives.push(t);
                archived += 1;
            } else {
                keep.push(todo);
            }
        }
        data.todos = keep;

        // archives 只增不减会无限膨胀，超出上限时丢最旧的
        if data.archives.len() > ARCHIVE_LIMIT {
            let excess = data.archives.len() - ARCHIVE_LIMIT;
            data.archives.drain(0..excess);
        }
    }

    data.settings.last_check_date = today;

    if archived > 0 {
        info!("跨日归档 {archived} 条已完成任务");
    }
    archived
}

// ---------------------------------------------------------------------------
// 排序
// ---------------------------------------------------------------------------

/// 未完成优先 → 同组内按用户拖出来的顺序 → 再按创建时间倒序（新任务在前）。
///
/// 已完成的任务沉底，不在这里剔除。
///
/// `order` 是中间键，也是老数据的兼容点：没有拖过的任务 order 全是 0，
/// 比较结果恒等，于是自然落回 `created_at` 倒序 —— 与引入 order 之前完全一致。
pub fn sort_todos(todos: &mut [Todo]) {
    todos.sort_by(|a, b| {
        a.completed
            .cmp(&b.completed)
            .then(a.order.cmp(&b.order))
            .then(b.created_at.cmp(&a.created_at))
    });
}

// ---------------------------------------------------------------------------
// 测试
// ---------------------------------------------------------------------------
//
// 这些是仓库里唯一的自动化行为验证。`cargo check` 只能确认代码能编译，
// 而本次改动最危险的地方恰恰是**解析行为** —— 少一个 `#[serde(default)]`
// 就能让用户的待办整体消失，且编译期毫无征兆。

#[cfg(test)]
mod tests {
    use super::*;

    fn todo(id: &str, completed: bool, created_at: i64, order: i64) -> Todo {
        Todo {
            id: id.into(),
            text: id.into(),
            completed,
            priority: 0,
            created_date: "2026-01-01".into(),
            created_at,
            completed_at: None,
            archived_at: None,
            order,
            font: String::new(),
            font_id: None,
        }
    }

    /// 老数据必须落回 `created_at` 倒序：新任务在前。
    #[test]
    fn legacy_order_falls_back_to_created_at() {
        let mut v = vec![todo("a", false, 1, 0), todo("b", false, 2, 0)];
        sort_todos(&mut v);
        assert_eq!(v[0].id, "b");
    }

    #[test]
    fn order_beats_created_at() {
        let mut v = vec![todo("a", false, 2, 0), todo("b", false, 1, 1)];
        sort_todos(&mut v);
        assert_eq!(v[0].id, "a");
    }

    #[test]
    fn completed_sinks_regardless_of_order() {
        let mut v = vec![todo("a", true, 9, -5), todo("b", false, 1, 100)];
        sort_todos(&mut v);
        assert_eq!(v[0].id, "b");
    }

    /// 数据丢失的回归测试。
    ///
    /// 这是本次改动里最要命的一条：`Settings` 一旦丢掉容器级
    /// `#[serde(default)]`，下面这段 JSON 就会解析失败 —— 而它正是每一个
    /// 现存用户磁盘上的样子（有 settings，没有 textColor）。解析失败会让
    /// `load()` 把 data.json 改名成 .broken 并返回默认数据，用户看到的是
    /// 全部待办凭空消失。
    #[test]
    fn legacy_settings_without_text_color_still_parses() {
        let json = r#"{
            "version": 1,
            "settings": { "maskOpacity": 0.65, "blur": 6.0, "shortcut": "Alt+Space" },
            "todos": [],
            "archives": []
        }"#;

        let data: AppData = serde_json::from_str(json).expect("老数据必须能解析");

        // 缺的字段由 Settings::default() 补齐 —— 不是空串
        assert_eq!(data.settings.text_color, DEFAULT_TEXT_COLOR);
        // 已有的字段要原样保留
        assert_eq!(data.settings.shortcut, "Alt+Space");
    }

    /// 老 todo 没有 order，默认 0（0 是恒等键，见 sort_todos 的注释）
    #[test]
    fn legacy_todo_without_order_parses() {
        let json = r#"{
            "id": "t1", "text": "写周报", "completed": false,
            "createdDate": "2026-01-01", "createdAt": 1
        }"#;

        let t: Todo = serde_json::from_str(json).expect("老 todo 必须能解析");
        assert_eq!(t.order, 0);
        assert_eq!(t.text, "写周报");
    }

    /// v1 的遮罩值是没有意义的（前端从没读过），迁移必须把它重置成新默认值
    #[test]
    fn migrate_resets_mask_and_bumps_version() {
        let mut data = AppData {
            version: 1,
            ..Default::default()
        };
        data.settings.mask_opacity = 0.65;

        migrate(&mut data);

        assert_eq!(data.version, DATA_VERSION);
        assert_eq!(data.settings.mask_opacity, DEFAULT_MASK_OPACITY);
    }

    /// migrate 必须幂等，且不能把用户手改过的字体链冲掉
    #[test]
    fn migrate_keeps_existing_font_chain() {
        let mut data = AppData::default();
        data.version = DATA_VERSION;
        data.settings.font_family = "\"Consolas\", monospace".into();

        migrate(&mut data);

        assert_eq!(data.settings.font_family, "\"Consolas\", monospace");
    }

    /// 空颜色会让 CSS 声明整条失效，必须兜底
    #[test]
    fn migrate_fills_blank_text_color() {
        let mut data = AppData::default();
        data.version = DATA_VERSION;
        data.settings.text_color = "   ".into();

        migrate(&mut data);

        assert_eq!(data.settings.text_color, DEFAULT_TEXT_COLOR);
    }

    // -----------------------------------------------------------------------
    // 每条任务自己的字体（v2 → v3）
    // -----------------------------------------------------------------------

    /// 数据丢失的回归测试：老 todo 没有 font / fontId 两个键。
    /// `Todo` 没有容器级 default，字段级 `#[serde(default)]` 少一个，
    /// 整份 data.json 就解析失败，用户的待办会从界面上整体消失。
    #[test]
    fn legacy_todo_without_font_parses() {
        let json = r#"{
            "id": "t1", "text": "写周报", "completed": false,
            "createdDate": "2026-01-01", "createdAt": 1, "order": 0
        }"#;

        let t: Todo = serde_json::from_str(json).expect("老 todo 必须能解析");
        assert_eq!(t.font, "");
        assert_eq!(t.font_id, None);
        assert_eq!(t.text, "写周报");
    }

    /// 上面那条只覆盖了「裸 Todo」。真正会丢数据的是**整份 AppData + 非空 todos**
    /// 这条路径 —— 现有测试里没有一条同时具备这两个条件。
    #[test]
    fn legacy_appdata_with_todos_parses() {
        let json = r#"{
            "version": 2,
            "settings": { "fontFamily": "\"Consolas\", monospace", "shortcut": "Ctrl+Alt+Space" },
            "todos": [
                { "id": "t1", "text": "写周报", "completed": false,
                  "createdDate": "2026-01-01", "createdAt": 1, "order": 0 },
                { "id": "t2", "text": "买牛奶", "completed": true,
                  "createdDate": "2026-01-01", "createdAt": 2, "order": 1 }
            ],
            "archives": []
        }"#;

        let mut data: AppData = serde_json::from_str(json).expect("老数据必须能解析");
        assert_eq!(data.todos.len(), 2);
        assert_eq!(data.todos[0].text, "写周报");

        migrate(&mut data);

        assert_eq!(data.todos.len(), 2, "迁移不能让任何一条任务消失");
        assert_eq!(data.todos[0].font, "\"Consolas\", monospace");
        assert_eq!(data.todos[1].font, "\"Consolas\", monospace");
        assert_eq!(data.todos[1].font_id, None);
    }

    /// 前端读的是 `todo.fontId`，键名一旦变了就静默对不上
    #[test]
    fn todo_font_fields_serialize_as_camel_case() {
        let mut t = todo("a", false, 1, 0);
        t.font = "\"Consolas\", monospace".into();
        t.font_id = Some("abc".into());

        let json = serde_json::to_string(&t).unwrap();

        assert!(json.contains("\"fontId\""), "序列化结果缺少 fontId: {json}");
        assert!(json.contains("\"font\""), "序列化结果缺少 font: {json}");
    }

    /// 迁移必须把老任务**当时正在用的**字体钉死在它自己身上
    #[test]
    fn migrate_stamps_legacy_todos_with_the_font_in_use() {
        let mut data = AppData {
            version: 2,
            ..Default::default()
        };
        data.settings.font_family = "\"Consolas\", monospace".into();
        data.settings.font_id = Some("abc123".into());
        data.todos.push(todo("a", false, 1, 0));
        data.todos.push(todo("b", true, 2, 0));

        migrate(&mut data);

        for t in &data.todos {
            assert_eq!(t.font, "\"Consolas\", monospace");
            assert_eq!(t.font_id.as_deref(), Some("abc123"));
        }
        assert_eq!(data.version, DATA_VERSION);
    }

    /// **这次改动最核心的一条回归测试**：迁移之后用户再改全局字体，
    /// 老任务必须原地不动。需求的原文就是「只会影响新的代办」。
    ///
    /// 如果哪天有人把 font 的默认值改成「空串 = 跟随当前全局字体」并删掉这个
    /// 盖章步骤，这条测试会红 —— 那正是要拦住的退化。
    #[test]
    fn changing_global_font_after_migration_leaves_old_todos_alone() {
        let mut data = AppData {
            version: 2,
            ..Default::default()
        };
        data.todos.push(todo("a", false, 1, 0));
        migrate(&mut data);

        // 用户随后换成了别的字体
        data.settings.font_family = "\"Comic Sans MS\", cursive".into();

        assert_eq!(data.todos[0].font, DEFAULT_FONT_CHAIN);
    }

    /// 已经盖过章的任务不能被后来的迁移覆盖
    #[test]
    fn migrate_does_not_restamp_already_stamped_todos() {
        let mut data = AppData {
            version: 2,
            ..Default::default()
        };
        data.settings.font_family = "\"Consolas\", monospace".into();
        data.todos.push(todo("a", false, 1, 0));
        migrate(&mut data);

        // 模拟迁移被重复执行：版本号退回去，全局字体也换掉
        data.version = 2;
        data.settings.font_family = "\"Comic Sans MS\", cursive".into();
        migrate(&mut data);

        assert_eq!(data.todos[0].font, "\"Consolas\", monospace");
    }

    /// 「应用到全部」要盖住每一条（含已完成的），且已等于目标值的不计入改写数
    #[test]
    fn apply_font_to_all_overwrites_every_todo() {
        let mut data = AppData::default();
        data.settings.font_family = "\"Consolas\", monospace".into();
        data.settings.font_id = Some("abc".into());

        let mut a = todo("a", false, 1, 0);
        a.font = DEFAULT_FONT_CHAIN.into();

        let mut b = todo("b", true, 2, 0);
        b.font = DEFAULT_FONT_CHAIN.into();
        b.font_id = Some("zzz".into());

        let mut c = todo("c", false, 3, 0);
        c.font = "\"Consolas\", monospace".into();
        c.font_id = Some("abc".into()); // 已经是目标值

        data.todos = vec![a, b, c];

        let n = apply_font_to_all(&mut data);

        assert_eq!(n, 2, "只有真正被改写的才计数");
        for t in &data.todos {
            assert_eq!(t.font, "\"Consolas\", monospace");
            assert_eq!(t.font_id.as_deref(), Some("abc"));
        }
    }

    /// 全局是默认字体（没有导入字体 id）时，「应用到全部」要把 id 一起清掉 ——
    /// 否则那些任务还会指向一个已经不再生效的导入字体
    #[test]
    fn apply_font_to_all_clears_ids_when_back_to_default() {
        let mut data = AppData::default(); // settings.font_id == None

        let mut a = todo("a", false, 1, 0);
        a.font = "\"Consolas\", monospace".into();
        a.font_id = Some("abc".into());
        data.todos = vec![a];

        apply_font_to_all(&mut data);

        assert_eq!(data.todos[0].font, DEFAULT_FONT_CHAIN);
        assert_eq!(data.todos[0].font_id, None);
    }

    // -----------------------------------------------------------------------
    // 改名迁移：MiniMemo → MiNo（v3 → v4）
    // -----------------------------------------------------------------------

    /// 一份「改名前」的数据：导入字体的 family 前缀还是老的，而且这个前缀按当时
    /// 的样子散布在三处会落盘的地方。
    fn pre_rename_data() -> AppData {
        let old_id = "3f2a1b0c9d8e";
        let old_family = format!("{LEGACY_FONT_FAMILY_PREFIX}3f2a1b0c");
        // 用真的 `chain_for` 拼老链，跟改名前那一刻写进磁盘的东西逐字一致
        let old_chain = crate::fonts::chain_for(&old_family);

        let mut data = AppData::default();
        data.version = 3;
        data.settings.custom_fonts = vec![CustomFont {
            id: old_id.into(),
            family: old_family,
            label: "霞鹜文楷".into(),
            file: "fonts/3f2a1b0c.ttf".into(),
        }];
        data.settings.font_family = old_chain.clone();
        data.settings.font_id = Some(old_id.into());

        let mut t = todo("a", false, 1, 0);
        t.font = old_chain.clone();
        t.font_id = Some(old_id.into());
        data.todos.push(t);

        let mut a = todo("z", true, 0, 0);
        a.font = old_chain;
        data.archives.push(a);

        data
    }

    /// **改动核心的回归测试**：改名后磁盘上那三处 family 前缀必须全部改写。
    ///
    /// 漏掉任意一处的表现都是「我导入的字体自己变了」—— 那个 family 永远注册不
    /// 进来，浏览器静默回退到链尾的 sans-serif，而且用户怎么改字体设置都救不回。
    /// `custom_fonts[*].family` 是最容易漏的：它不参与正文渲染，坏了只有字体列表
    /// 看起来是坏的。
    #[test]
    fn migrate_rewrites_legacy_font_family_prefix_everywhere() {
        let mut data = pre_rename_data();
        let old = LEGACY_FONT_FAMILY_PREFIX;
        let new = FONT_FAMILY_PREFIX;

        // 前提：构造出来的数据确实是「老」的
        assert!(data.settings.font_family.contains(old));
        assert!(data.settings.custom_fonts[0].family.starts_with(old));

        migrate(&mut data);

        assert!(!data.settings.font_family.contains(old));
        assert!(data.settings.font_family.contains(new));
        assert!(data.settings.custom_fonts[0].family.starts_with(new));
        assert!(data.todos[0].font.contains(new));
        assert!(data.archives[0].font.contains(new));
        assert_eq!(data.version, DATA_VERSION);
    }

    /// 改写前缀**不能顺手弄丢数据** —— 这是本仓库最重的一类回归。
    #[test]
    fn rename_prefix_preserves_everything_else() {
        let mut data = pre_rename_data();
        let before_todos = data.todos.len();
        let before_archives = data.archives.len();

        migrate(&mut data);

        assert_eq!(data.todos.len(), before_todos);
        assert_eq!(data.archives.len(), before_archives);
        assert_eq!(data.todos[0].id, "a");
        assert_eq!(data.todos[0].text, "a");
        assert_eq!(data.todos[0].font_id.as_deref(), Some("3f2a1b0c9d8e"));
        assert_eq!(data.settings.custom_fonts[0].file, "fonts/3f2a1b0c.ttf");
        assert_eq!(data.settings.custom_fonts[0].label, "霞鹜文楷");
        assert_eq!(data.settings.font_id.as_deref(), Some("3f2a1b0c9d8e"));
    }

    /// 幂等：跑第二遍无事可做。
    #[test]
    fn rename_prefix_is_idempotent() {
        let mut data = pre_rename_data();
        migrate(&mut data);

        assert_eq!(rename_font_family_prefix(&mut data), 0);
        assert_eq!(rename_font_family_prefix(&mut data), 0);
    }

    /// 用户的系统字体链里没有那个前缀，一个字节都不该动。
    #[test]
    fn rename_prefix_leaves_system_fonts_alone() {
        let mut data = AppData::default();
        data.version = DATA_VERSION;
        data.settings.font_family = "\"Consolas\", monospace".into();

        assert_eq!(rename_font_family_prefix(&mut data), 0);
        assert_eq!(data.settings.font_family, "\"Consolas\", monospace");
    }
}
