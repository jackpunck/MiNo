//! 开机自启动：读写 HKCU 下的 Run 项。
//!
//! **两条键一起看，缺一条就会骗人：**
//!
//! - `...\CurrentVersion\Run` —— 启动项本身。
//! - `...\Explorer\StartupApproved\Run` —— 任务管理器的启用/禁用叠加层。
//!   任务管理器「禁用」一个启动项**不会删掉 Run 项**，只在那边写一条 12 字节的
//!   REG_BINARY，首字节 `0x03`。只读 Run 项的话，用户明明在任务管理器里关掉了，
//!   我们的开关还显示「已开启」。
//!
//! **注册表是唯一权威，`Settings` 上没有对应字段。** 一个持久化的 bool 会和注册表
//! 各说各话（用户在任务管理器里改一次就分叉了），而这个功能的全部意义就是如实反映
//! 系统状态。附带的好处是整个 `state.rs` 一行都不用改 —— 加 `Settings` 字段是这个
//! 仓库后果最重的错误类（漏一个 `#[serde(default)]` 就清空用户待办，编译期毫无征兆）。
//!
//! **为什么不用 `tauri-plugin-autostart`：** 它只有「写 Run 项」这一半，读不出上面
//! 那个叠加层 —— 而读不出叠加层正是本模块存在的理由。为一个读不出来的状态多引一棵
//! 依赖树不划算。
//!
//! 与 [`crate::window`] 的一处刻意差异：那边唯一的 FFI 函数把 `use windows_sys::...`
//! 写在**函数里**（只有一个函数用得上）；这里四个薄封装共用同一批符号，放**模块级**
//! 少一半噪音。别照着那边改。

use std::iter;

use log::{info, warn};
use serde::Serialize;
use windows_sys::Win32::Foundation::ERROR_FILE_NOT_FOUND;
use windows_sys::Win32::System::Registry::{
    RegCloseKey, RegDeleteValueW, RegOpenKeyExW, RegQueryValueExW, RegSetValueExW, HKEY,
    HKEY_CURRENT_USER, KEY_QUERY_VALUE, KEY_SET_VALUE, REG_BINARY, REG_SZ,
};

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

/// Run 项下的值名。**唯一来源，别处不许再写字面量。**
///
/// 这个名字是给用户看的：任务管理器「启动应用」和 msconfig 都拿它当条目名，
/// 所以用产品名而不是 identifier（对照：`app_data_dir` 用 identifier 是因为那是
/// 一个**目录路径**，README 专门解释过一次）。
///
/// **改它等于一次迁移** —— 必须同时读新旧两个名字、并在写入成功后删掉旧名，
/// 否则老用户的开关会显示成关闭，再点一下又会多出一条重复项。和改数据目录同一类。
const VALUE_NAME: &str = "MiNo";

const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";

/// 任务管理器的叠加层。这条键**可能整个不存在**（Explorer 第一次动它时才建），
/// 不存在等于「没被禁用」。
const APPROVED_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";

/// 首字节是禁用的那几个码：`0x03` 是任务管理器关的，`0x07` / `0x09` 是策略锁的
/// （`0x09` = 锁定且禁用）。对应的启用码是 `0x02` / `0x06` / `0x08`（`0x08` = 锁定且启用）。
///
/// **只列禁用集合**，判据是「认识它、且它是禁用」，其余一律放行 —— 理由见
/// [`approval_is_disabled`]。
const APPROVAL_DISABLED: [u8; 3] = [0x03, 0x07, 0x09];

/// 我们要写回去的「已启用」字节。
const APPROVAL_ENABLED: u8 = 0x02;

/// 叠加层的值固定 12 字节：首字节状态，1–3 保留，4–11 一个 FILETIME。
const APPROVAL_LEN: usize = 12;

/// 单条注册表值的字节上限。64KB 以上基本只可能是别人塞的垃圾；这个上限是防一次
/// 荒唐的分配，**不是安全边界** —— HKCU 本来就在用户自己手里。
const MAX_VALUE_BYTES: usize = 64 * 1024;

// ---------------------------------------------------------------------------
// 对外的状态
// ---------------------------------------------------------------------------

/// 开机自启动状态。字段名走 camelCase 进前端。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    /// 开关该显示成开还是关。判据是「Run 项在，且没被叠加层标成禁用」。
    pub enabled: bool,
    /// Run 项在，但被「任务管理器 → 启动应用」禁用了。此时 `enabled` 必为 `false`
    /// —— 这个组合正是界面要额外给一句提示的那种情况。
    pub blocked: bool,
    /// Run 项指向的不是当前这个 exe（便携版被挪过位置，而启动自愈没修成）。
    pub stale: bool,
    /// 出错时的中文说明（含原始错误码）。`None` = 一切正常。
    ///
    /// 前端把它当提示文案显示，**必须用 textContent**。
    pub error: Option<String>,
}

impl Status {
    /// 「什么都不知道」。读不到注册表时用它，`enabled` 一律 `false`。
    ///
    /// 方向是刻意的：声称「已开启」而实际没开，正是这个功能最该避免的静默失效；
    /// 反过来显示「已关闭」最多让用户多点一次开关，而那一次就能把它修好。
    fn unknown(err: String) -> Self {
        Self {
            enabled: false,
            blocked: false,
            stale: false,
            error: Some(err),
        }
    }

    /// 确定「没有这条启动项」—— 最常见的情况，不是错误。
    fn absent() -> Self {
        Self {
            enabled: false,
            blocked: false,
            stale: false,
            error: None,
        }
    }
}

/// 读当前状态。**永不返回 `Err`** —— 查询失败体现在 `Status::error` 上，
/// 这样调用方（command 与前端）只有一条渲染路径。
///
/// 这里的两条读路径**失败方向相反，而且是刻意的**，见下面的注释。
pub fn status() -> Status {
    let Some(exe) = current_exe_path() else {
        return Status::unknown("读不到当前程序的路径".into());
    };

    // --- 主判据：Run 项在不在 ---
    let raw = match read_value(RUN_KEY, VALUE_NAME) {
        Ok(Some(v)) => v,
        Ok(None) => return Status::absent(),
        // 主观测失败 → 如实报「关闭 + 原因」。
        //
        // **方向是这里最要紧的东西：** 谎报「已开启」会让用户永远不去查，自启动
        // 就静默坏了；报「已关闭」最坏是让他多点一次开关，而那一次正好会修好它。
        Err(e) => return Status::unknown(e),
    };

    let target = decode_reg_sz(&raw);
    let stale = !same_exe_path(run_value_target(&target), &exe);

    // --- 叠加层：任务管理器有没有禁用 ---
    //
    // 这一条的失败方向**与上面相反，而且是有意的**：查不到（键不存在、长度不对、
    // 字节含义不认识、乃至读失败）一律按「没被禁用」处理。理由和
    // window::foreground_is_fullscreen 同源 —— 不确定的探测结果不能拿来**声称**
    // 一个更坏的状态。这里谎报「被禁用」的代价是：用户看到一句「去任务管理器里
    // 启用」，而那边明明是启用的，他无从下手。
    //
    // 读失败也不往 error 里塞：它只是叠加层，真正的判据是上面那个 Run 项。
    let blocked =
        matches!(read_value(APPROVED_KEY, VALUE_NAME), Ok(Some(b)) if approval_is_disabled(&b));

    Status {
        enabled: !blocked,
        blocked,
        stale,
        error: None,
    }
}

/// 打开/关闭。**返回写完重读之后的状态。**
///
/// 返回状态而不是 `bool` / `Result`：写注册表不报错不等于真的生效 —— 组策略可能
/// 把它锁着（叠加层的 `0x08` / `0x09` 就是「锁定」），杀软或注册表重定向也可能把
/// 这次写吞掉。重读一遍，前端拿到的就是地面真相，不会停在一个乐观的假象上。
pub fn set(enabled: bool) -> Status {
    let outcome = if enabled {
        write_run_value()
    } else {
        remove_run_value()
    };

    // 无论成败都重读：界面上那个开关的位置**永远**等于注册表的真实状态。
    let mut st = status();
    if st.error.is_none() {
        if let Err(e) = outcome {
            st.error = Some(e);
        }
    }
    st
}

/// 启动自愈：Run 项在、但指向的不是当前这个 exe，就改写成当前路径。
///
/// **为什么必须做：** 注册表里那条记录**没有任何东西会自动更新它**。用户把便携版
/// `MiNo.exe` 挪个目录（便携版最常见的用法），自启动就永久失效 —— 而设置面板仍然
/// 显示「已开启」，因为 Run 项确实还在。这个静默失效是本功能最容易留下的坑，而它
/// 恰好能被一次启动时的比对挡掉。
///
/// **只修路径，绝不动 StartupApproved。** 那边记的是用户在任务管理器里的选择，
/// 每次开机覆盖它等于跟用户对着干 —— 自愈把用户明确关掉的东西又打开，是最讨人厌的
/// 一类「智能」。见 [`clear_approval`]。
///
/// 失败只记日志：自启修不好不该阻止启动。**没事可做时一行都不记** —— 这个函数每次
/// 开机都跑，无脑记一笔会把日志淹掉（CLAUDE.md 第 13 条的频率纪律：日志留给异常）。
pub fn self_heal() {
    let Some(exe) = current_exe_path() else {
        warn!("自启动自愈跳过：读不到当前程序路径");
        return;
    };

    let raw = match read_value(RUN_KEY, VALUE_NAME) {
        Ok(Some(v)) => v,
        // 没有 Run 项是最常见的情况（用户根本没开自启动），不该记日志
        Ok(None) => return,
        Err(e) => {
            warn!("自启动自愈跳过：读取 Run 项失败 {e}");
            return;
        }
    };

    let target = decode_reg_sz(&raw);
    if same_exe_path(run_value_target(&target), &exe) {
        return;
    }

    // target 是从注册表读出来的 = 不可信字符串，进日志前必须收拾
    let shown = sanitize_for_log(&target);
    match write_value(RUN_KEY, VALUE_NAME, REG_SZ, &encode_reg_sz(&run_value_for(&exe))) {
        Ok(()) => info!("自启动项指向的是 {shown}，已改写成当前路径"),
        Err(e) => warn!("自启动项指向的路径已失效（{shown}），改写失败: {e}"),
    }
}

// ---------------------------------------------------------------------------
// 写路径
// ---------------------------------------------------------------------------

/// 写 Run 项，并清掉任务管理器的禁用标记。
fn write_run_value() -> Result<(), String> {
    let Some(exe) = current_exe_path() else {
        return Err("读不到当前程序的路径".into());
    };

    write_value(RUN_KEY, VALUE_NAME, REG_SZ, &encode_reg_sz(&run_value_for(&exe)))?;
    clear_approval();
    Ok(())
}

/// 关掉：删掉 Run 项。
fn remove_run_value() -> Result<(), String> {
    // 先看一眼在不在。`RegDeleteValueW` 对不存在的值也返回错误码，直接当成失败会
    // 向用户弹一句莫名其妙的「删除失败」，而他要的目标状态（关闭）其实已经成立了。
    match read_value(RUN_KEY, VALUE_NAME) {
        Ok(None) => Ok(()),
        _ => delete_value(RUN_KEY, VALUE_NAME),
    }
}

/// 把「任务管理器禁用了它」这个叠加层清掉。**只在用户主动打开开关时调用。**
///
/// 启动自愈绝不调用它 —— 那等于每次开机都把用户在任务管理器里的选择悄悄改回去。
///
/// 只在值**已经存在**时才写：不存在本身就是「启用」（微软的约定），凭空造一条
/// 反而多一份要维护的状态。
///
/// 失败只 `warn!` 不阻断：Run 项已经写好了，重启后**大概率仍然会启动**；万一真的
/// 没启动，`status()` 会读回来显示成「被禁用」+ 提示，用户看得见。把这一步做成硬
/// 失败，会让一个次要的清理动作否掉整个操作。
fn clear_approval() {
    let Ok(Some(blob)) = read_value(APPROVED_KEY, VALUE_NAME) else {
        return;
    };
    if !approval_is_disabled(&blob) {
        return;
    }

    let fixed = approval_enabled_blob(&blob);
    if let Err(e) = write_value(APPROVED_KEY, VALUE_NAME, REG_BINARY, &fixed) {
        warn!("清除启动项禁用标记失败: {e}");
    }
}

// ---------------------------------------------------------------------------
// 纯函数
//
// 本机没有 Rust 工具链，`cargo test` 只能靠 CI 跑 —— 这些纯函数是这个模块里唯一
// 能被真正验证逻辑的部分，所以凡是能从 IO 里摘出来的都摘出来了。
// ---------------------------------------------------------------------------

/// 当前这个可执行文件的路径，已剥掉动词前缀。
fn current_exe_path() -> Option<String> {
    let p = std::env::current_exe().ok()?;
    Some(strip_verbatim_prefix(&p.to_string_lossy()))
}

/// 剥掉 `\\?\` 动词前缀。
///
/// `std::env::current_exe()` 在某些情况下会带上它，而**带这个前缀的路径写进 Run 项
/// 是启动不起来的** —— 那是给文件 API 用的前缀，不是给命令行用的。宁可多剥一次，
/// 代价为零。
///
/// `\\?\UNC\srv\share\x.exe` 要还原成 `\\srv\share\x.exe`：直接砍掉前 4 个字符会得到
/// `UNC\srv\share\x.exe`，那是个**相对路径**，启动必然失败。所以 UNC 那一条必须
/// 排在前面先判。
///
/// 返回 owned 而不是 `&str`：UNC 那条分支要拼字符串，`String` 比 `Cow` 少一层心智
/// 负担，而这几个函数都不在热路径上。
fn strip_verbatim_prefix(p: &str) -> String {
    if let Some(rest) = p.strip_prefix(r"\\?\UNC\") {
        return format!(r"\\{rest}");
    }
    if let Some(rest) = p.strip_prefix(r"\\?\") {
        return rest.to_string();
    }
    p.to_string()
}

/// 把 Run 项里存的整串数据，还原成「它要启动的那个程序」。
///
/// 我们写的总是带引号的路径，但这条值用户能手动改、别的安装程序也能改，所以要容忍
/// 三种形状：
///
/// - `"C:\a\MiNo.exe"` → `C:\a\MiNo.exe`
/// - `"C:\a\MiNo.exe" --foo` → `C:\a\MiNo.exe`。**手动加了参数不该被判成失效** ——
///   判成失效，自愈就会连他加的参数一起抹掉。
/// - `C:\a\MiNo.exe`（不带引号）→ 原样，路径不含空格时这样写是合法的。
/// - `"C:\a\MiNo.exe`（引号不配对，畸形）→ 整串原样返回。这样比出来的结果必然不等，
///   于是它会被改写成规范形式 —— 那正好就是修复。
///
/// 不带引号**又**带参数（`C:\a\MiNo.exe --foo`）分不清路径在哪结束，会整串原样返回
/// 从而被判成失效并改写。这是刻意接受的：我们从不写这种形状，而改写的结果指向的
/// 仍然是正确的那条路径。
fn run_value_target(raw: &str) -> &str {
    let t = raw.trim();
    let Some(rest) = t.strip_prefix('"') else {
        return t;
    };
    match rest.find('"') {
        Some(end) => &rest[..end],
        None => t,
    }
}

/// Run 项该写什么：**带引号**的 exe 路径。
///
/// 引号不是洁癖：不带引号时 Windows 在第一个空格处把命令行切开，
/// `C:\Program Files\...` 就变成要去启动 `C:\Program`。便携版被放进带空格的目录
/// 是很常见的事。
///
/// 这个函数本身没有单测 —— 它就是一行 `format!`，契约由 [`encode_reg_sz`] 的测试
/// 和上面这段注释承担。别以为漏了。
fn run_value_for(exe: &str) -> String {
    format!("\"{exe}\"")
}

/// 两个路径是不是指同一个程序。两侧都先剥动词前缀再比。
///
/// 只做 ASCII 大小写折叠：Windows 路径不区分大小写，`eq_ignore_ascii_case` 覆盖了
/// 绝大多数情况。非 ASCII 的大小写差异（带重音的用户名）会漏判，后果是我们用当前
/// 路径把那条值**改写一遍** —— 结果仍然是对的，属于能自愈的误判，不值得为它引一次
/// Unicode 折叠。
fn same_exe_path(a: &str, b: &str) -> bool {
    strip_verbatim_prefix(a.trim()).eq_ignore_ascii_case(&strip_verbatim_prefix(b.trim()))
}

/// UTF-16 + 结尾 NUL。Win32 的 `...W` 系列全要这个形状。
///
/// **漏掉结尾 NUL 是最阴的一种错**：值名会被当成一段没有边界的内存去读，而
/// `cargo check` 完全看不出来。所以它配了单测。
fn to_wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(iter::once(0)).collect()
}

/// `REG_SZ` 的字节序列：UTF-16LE + 结尾一个 `0x0000`。
///
/// **返回字节而不是 u16 数组**，因为 `RegSetValueExW` 吃的是**字节数**：手写字节序
/// 能把「cbData 该不该 ×2、该不该含结尾 NUL」这两件最容易错的事变成可以在
/// `mod tests` 里断言的东西。少一个 NUL 在实机上表现为「值写进去了但读出来是乱码 /
/// 启动不了」，本机根本看不见。
fn encode_reg_sz(s: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity((s.len() + 1) * 2);
    for unit in s.encode_utf16() {
        out.extend_from_slice(&unit.to_le_bytes());
    }
    out.extend_from_slice(&0u16.to_le_bytes());
    out
}

/// `REG_SZ` 的字节序列 → `String`。
///
/// 用 `chunks_exact(2)`：长度是奇数时**丢掉最后一个孤字节**而不是切一刀 ——
/// release 是 `panic = "abort"`，在这里越界一次等于整个进程没了。
///
/// 用 `from_utf16_lossy` 而不是 `from_utf16`：注册表里可能有孤立代理项（手改文件、
/// 或别的程序写坏了），那时唯一合逻辑的选择是替换字符，而不是让进程出事。
fn decode_reg_sz(bytes: &[u8]) -> String {
    let mut units: Vec<u16> = Vec::with_capacity(bytes.len() / 2);
    for pair in bytes.chunks_exact(2) {
        let unit = u16::from_le_bytes([pair[0], pair[1]]);
        if unit == 0 {
            // `REG_SZ` 到第一个 NUL 为止，后面的字节不属于这个字符串
            break;
        }
        units.push(unit);
    }
    String::from_utf16_lossy(&units)
}

/// 叠加层说它是「被禁用」吗。
///
/// 判据只有一条：**长度正好 12，且首字节在已知的禁用集合里**。
///
/// **长度不对、字节不认识，一律返回 `false`（= 没被禁用）。** 这跟 [`status`] 里
/// 那条失败方向是同一条规矩：不确定的探测结果不能拿来声称一个更坏的状态。
/// 顺带挡住了「Explorer 换了一套字节含义」这种情况 —— 我们只会退回显示「已开启」，
/// 也就是没有这个功能时的行为，而不是凭空告诉用户一个错的原因。
fn approval_is_disabled(blob: &[u8]) -> bool {
    blob.len() == APPROVAL_LEN && APPROVAL_DISABLED.contains(&blob[0])
}

/// 把「已启用」这个字节写回去，**保留其余 11 字节**。
///
/// 后面 8 个字节是 Windows 的 FILETIME 簿记（什么时候被禁用的）。整条覆盖成固定的
/// 12 字节也能用，但那是无谓的信息破坏 —— 只动我们确实要动的那一个字节。
///
/// 空切片时原样返回（防越界）。真走到那儿也写不出什么有意义的东西，但至少不会
/// 让进程出事。
fn approval_enabled_blob(current: &[u8]) -> Vec<u8> {
    let mut out = current.to_vec();
    if let Some(first) = out.first_mut() {
        *first = APPROVAL_ENABLED;
    }
    out
}

/// 把注册表里读出来的串收拾成能安全写进日志的样子。
///
/// **注册表的值是不可信输入。** 任何人都能往 HKCU 的 Run 里塞一个带换行的字符串，
/// 原样记进日志就等于让别人伪造日志行 —— 而这份日志恰恰是我们事后判断「进程怎么
/// 没的」的唯一现场。截断 + 剔控制字符，和 `minimize_to_edge` 的 `reason` 是同一条
/// 规矩。
///
/// 截断处补一个 `…`：不补的话，日志里那个路径和真实路径对不上，会把人带偏。
fn sanitize_for_log(s: &str) -> String {
    /// 日志里一个路径的显示上限。
    const LIMIT: usize = 200;

    let filtered: String = s.chars().filter(|c| !c.is_control()).collect();
    if filtered.chars().count() <= LIMIT {
        return filtered;
    }

    let mut out: String = filtered.chars().take(LIMIT).collect();
    out.push('…');
    out
}

// ---------------------------------------------------------------------------
// FFI 薄封装
// ---------------------------------------------------------------------------

/// 打开着的注册表键。**`Drop` 时一定关** —— 这就是「每条路径都要关句柄」的答案：
/// 把关闭做成结构体生命周期的一部分，任何提前 `return`、任何 `?` 都不会漏掉一次
/// `RegCloseKey`。全模块**只有这一处**调用它。
struct Key(HKEY);

impl Drop for Key {
    fn drop(&mut self) {
        // 关句柄失败无处可去，也无所谓：进程还在跑，句柄会随进程回收。
        let _ = unsafe { RegCloseKey(self.0) };
    }
}

enum OpenError {
    /// 键整个不存在。**这不是错误** —— 「查不到」和「查失败」必须分开，否则用户会
    /// 看到一个自己既解释不了、也修不掉的提示。
    NotFound,
    Failed(String),
}

/// 打开 HKCU 下的一个子键。
///
/// `access` 的实参永远是 `KEY_QUERY_VALUE` / `KEY_SET_VALUE` 之一，**永远不用 `|`
/// 组合**：掩码常量的类型在 windows-sys 的不同版本里是 `u32` 还是专用别名变过，
/// 直接传一个常量两种都能编过，位或就多赌一次。
///
/// 刻意**不用 `RegCreateKeyExW`**：`...\CurrentVersion\Run` 是每份 Windows 用户配置
/// 的标准键，正常情况下它一定在。真不在的话，报个错比悄悄重建系统状态更诚实。
fn open_key(subkey: &str, access: u32) -> Result<Key, OpenError> {
    let wide = to_wide(subkey);
    // windows-sys 的结构体不实现 Default，用 zeroed（同 window.rs）
    let mut hkey: HKEY = std::mem::zeroed();

    let rc = unsafe { RegOpenKeyExW(HKEY_CURRENT_USER, wide.as_ptr(), 0, access, &mut hkey) };

    if rc == ERROR_FILE_NOT_FOUND {
        return Err(OpenError::NotFound);
    }
    if rc != 0 {
        return Err(OpenError::Failed(format!("打开 {subkey} 失败（错误码 {rc}）")));
    }

    Ok(Key(hkey))
}

/// 读一个值，拿原始字节。
///
/// - `Ok(None)` = 值不存在 / 键不存在 / 大到不像话（都不是错误）
/// - `Err(msg)` = 查询本身失败，`msg` 里带原始错误码
///
/// 返回**字节**而不是 `String`：Run 项是 `REG_SZ`、叠加层是 `REG_BINARY`，共用一个
/// 函数，解码交给调用方的纯函数。
fn read_value(subkey: &str, name: &str) -> Result<Option<Vec<u8>>, String> {
    let key = match open_key(subkey, KEY_QUERY_VALUE) {
        Ok(k) => k,
        Err(OpenError::NotFound) => return Ok(None),
        Err(OpenError::Failed(e)) => return Err(e),
    };

    let name_wide = to_wide(name);

    // 第一次调用只问长度：lpData 传 null。
    //
    // `lpreserved` / `lptype` 两次都传空指针 —— 我们按字节解码，不关心回来的是
    // REG_SZ 还是 REG_EXPAND_SZ（别人写成 EXPAND_SZ 时解码结果仍是一条路径，
    // 比对着类型报错有用）。
    let mut size: u32 = 0;
    let rc = unsafe {
        RegQueryValueExW(
            key.0,
            name_wide.as_ptr(),
            std::ptr::null(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            &mut size,
        )
    };

    if rc == ERROR_FILE_NOT_FOUND {
        return Ok(None);
    }
    if rc != 0 {
        return Err(format!("读取 {name} 失败（错误码 {rc}）"));
    }

    // 空值。REG_SZ 的空串理论上也带一个结尾 NUL，但不必为这个赌一次。
    if size == 0 {
        return Ok(Some(Vec::new()));
    }
    if size as usize > MAX_VALUE_BYTES {
        return Ok(None);
    }

    let mut buf = vec![0u8; size as usize];
    let mut read: u32 = size;
    let rc = unsafe {
        RegQueryValueExW(
            key.0,
            name_wide.as_ptr(),
            std::ptr::null(),
            std::ptr::null_mut(),
            buf.as_mut_ptr(),
            &mut read,
        )
    };
    if rc != 0 {
        return Err(format!("读取 {name} 失败（错误码 {rc}）"));
    }

    // `read` 可能比 `size` 小：值在两次调用之间被改短了
    buf.truncate(read as usize);
    Ok(Some(buf))
}

/// 写一个值。`ty` 是 `REG_SZ` 或 `REG_BINARY`。
fn write_value(subkey: &str, name: &str, ty: u32, bytes: &[u8]) -> Result<(), String> {
    let key = match open_key(subkey, KEY_SET_VALUE) {
        Ok(k) => k,
        Err(OpenError::NotFound) => return Err(format!("注册表项不存在：{subkey}")),
        Err(OpenError::Failed(e)) => return Err(e),
    };

    let name_wide = to_wide(name);
    let rc = unsafe {
        RegSetValueExW(
            key.0,
            name_wide.as_ptr(),
            0,
            ty,
            bytes.as_ptr(),
            bytes.len() as u32,
        )
    };

    if rc != 0 {
        return Err(format!("写入 {name} 失败（错误码 {rc}）"));
    }
    Ok(())
}

/// 删一个值。调用方负责确认它存在（见 [`remove_run_value`]）。
fn delete_value(subkey: &str, name: &str) -> Result<(), String> {
    let key = match open_key(subkey, KEY_SET_VALUE) {
        Ok(k) => k,
        // 键都没了，值自然也没了 —— 目标状态已经成立
        Err(OpenError::NotFound) => return Ok(()),
        Err(OpenError::Failed(e)) => return Err(e),
    };

    let name_wide = to_wide(name);
    let rc = unsafe { RegDeleteValueW(key.0, name_wide.as_ptr()) };

    // 值恰好在我们读过之后被别人删掉了：同样不算失败
    if rc != 0 && rc != ERROR_FILE_NOT_FOUND {
        return Err(format!("删除 {name} 失败（错误码 {rc}）"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    //! 全部的注册表 IO 都在上面那几个薄封装里，这里**一个测试都不碰真实注册表** ——
    //! CI 上跑的是纯逻辑。
    //!
    //! 注意 `run_value_for` 没有单测：它就是一行 `format!`，契约由 `encode_reg_sz`
    //! 的测试承担。别以为漏了。

    use super::*;

    // ---------------------------------------------------------------- 路径

    #[test]
    fn strips_verbatim_prefix() {
        assert_eq!(strip_verbatim_prefix(r"\\?\C:\a\MiNo.exe"), r"C:\a\MiNo.exe");
        // 直接砍前 4 个字符会得到 `UNC\srv\...`，那是个相对路径
        assert_eq!(
            strip_verbatim_prefix(r"\\?\UNC\srv\share\MiNo.exe"),
            r"\\srv\share\MiNo.exe"
        );
    }

    #[test]
    fn leaves_ordinary_paths_untouched() {
        for p in [
            r"C:\a\MiNo.exe",
            r"\\srv\share\MiNo.exe",
            "MiNo.exe",
            "",
        ] {
            assert_eq!(strip_verbatim_prefix(p), p, "{p:?} 不该被改动");
        }
    }

    #[test]
    fn unwraps_the_path_out_of_a_run_value() {
        assert_eq!(run_value_target(r#""C:\a\MiNo.exe""#), r"C:\a\MiNo.exe");
        assert_eq!(run_value_target(r"C:\a\MiNo.exe"), r"C:\a\MiNo.exe");
        // 两边空白是手改留下的，不算差异
        assert_eq!(run_value_target(r#"  "C:\a\MiNo.exe"  "#), r"C:\a\MiNo.exe");
    }

    /// 用户在注册表里手动加了启动参数，不该被自愈当成「路径失效」而抹掉
    #[test]
    fn ignores_arguments_after_a_quoted_path() {
        assert_eq!(
            run_value_target(r#""C:\a\MiNo.exe" --minimized"#),
            r"C:\a\MiNo.exe"
        );
    }

    /// 引号不配对 = 畸形值。原样返回 → 必然比不等 → 被改写成规范形式，那正是修复
    #[test]
    fn keeps_a_malformed_value_whole() {
        let malformed = r#""C:\a\MiNo.exe"#;
        assert_eq!(run_value_target(malformed), malformed);
    }

    #[test]
    fn compares_paths_case_insensitively() {
        assert!(same_exe_path(r"C:\MiNo.exe", r"c:\mino.exe"));
    }

    #[test]
    fn compares_paths_after_stripping_the_verbatim_prefix() {
        assert!(same_exe_path(r"\\?\C:\a\MiNo.exe", r"C:\a\MiNo.exe"));
    }

    /// 自愈的触发条件
    #[test]
    fn rejects_paths_in_different_directories() {
        assert!(!same_exe_path(r"C:\old\MiNo.exe", r"C:\new\MiNo.exe"));
    }

    // ------------------------------------------------------------- REG_SZ

    #[test]
    fn reg_sz_bytes_are_utf16le_and_nul_terminated() {
        assert_eq!(encode_reg_sz("AB"), vec![0x41, 0x00, 0x42, 0x00, 0x00, 0x00]);
    }

    /// **这就是 cbData 契约**：少算或多算这个结尾 NUL，写进去的就是截断或带垃圾的值
    #[test]
    fn reg_sz_bytes_count_the_terminator() {
        for s in ["", "A", r"C:\a\MiNo.exe", "中文路径"] {
            let bytes = encode_reg_sz(s);
            assert_eq!(bytes.len(), s.encode_utf16().count() * 2 + 2, "{s:?}");
            assert_eq!(&bytes[bytes.len() - 2..], &[0, 0], "{s:?} 结尾必须是 NUL");
        }
    }

    #[test]
    fn reg_sz_round_trips() {
        for s in ["", "A", r"C:\用户\MiNo.exe", "带 空格 的 目录"] {
            assert_eq!(decode_reg_sz(&encode_reg_sz(s)), s, "{s:?}");
        }
    }

    #[test]
    fn decode_stops_at_the_first_nul() {
        let mut bytes = encode_reg_sz("AB");
        bytes.extend_from_slice(&encode_reg_sz("CD"));
        assert_eq!(decode_reg_sz(&bytes), "AB");
    }

    /// 奇数长度：丢掉最后一个孤字节，不 panic（release 是 panic = "abort"）
    #[test]
    fn decode_survives_a_truncated_buffer() {
        assert_eq!(decode_reg_sz(&[0x41, 0x00, 0x42]), "A");
        assert_eq!(decode_reg_sz(&[]), "");
        assert_eq!(decode_reg_sz(&[0x41]), "");
    }

    // ------------------------------------------------------------- 叠加层

    /// 首字节 12 字节的已知含义。三个禁用码、三个启用码，一个不漏
    #[test]
    fn approval_reads_the_known_status_bytes() {
        for (first, disabled) in [
            (0x03u8, true),
            (0x07, true),
            (0x09, true),
            (0x02, false),
            (0x06, false),
            (0x08, false),
        ] {
            let mut blob = vec![0u8; APPROVAL_LEN];
            blob[0] = first;
            assert_eq!(approval_is_disabled(&blob), disabled, "首字节 {first:#04x}");
        }
    }

    /// 「查不到一律放行」的回归测试 —— 字节含义不认识时只退回「已开启」，
    /// 不凭空告诉用户一个错的原因
    #[test]
    fn approval_treats_unknown_bytes_as_enabled() {
        for first in [0x00u8, 0x01, 0x04, 0x05, 0xFF] {
            let mut blob = vec![0u8; APPROVAL_LEN];
            blob[0] = first;
            assert!(!approval_is_disabled(&blob), "首字节 {first:#04x} 不该被判成禁用");
        }
    }

    #[test]
    fn approval_rejects_blobs_of_the_wrong_length() {
        for len in [0usize, 1, 2, 11, 13, 16] {
            let blob = vec![0x03u8; len];
            assert!(!approval_is_disabled(&blob), "长度 {len} 不该被判成禁用");
        }
    }

    /// 只动首字节 —— 后面 8 个字节是 Windows 的 FILETIME 簿记
    #[test]
    fn approval_flip_keeps_the_timestamp_bytes() {
        let blob = vec![0x03u8, 0x00, 0x00, 0x00, 0xF4, 0x0B, 0x28, 0xC9, 0x9D, 0x79, 0xD1, 0x01];
        let fixed = approval_enabled_blob(&blob);
        assert_eq!(fixed[0], APPROVAL_ENABLED);
        assert_eq!(&fixed[1..], &blob[1..]);
    }

    #[test]
    fn approval_flip_on_an_empty_blob_is_a_noop() {
        assert!(approval_enabled_blob(&[]).is_empty());
    }

    /// 连空 blob 都翻转得动的话，下一次写进去的就是一条长度不对的值
    #[test]
    fn approval_flip_then_read_is_a_fixed_point() {
        let mut blob = vec![0u8; APPROVAL_LEN];
        blob[0] = 0x03;
        let fixed = approval_enabled_blob(&blob);
        assert!(!approval_is_disabled(&fixed));
        assert_eq!(approval_enabled_blob(&fixed), fixed);
    }

    // ---------------------------------------------------------- wide 串

    /// 漏掉 NUL 会让值名读越界，而 cargo check 完全看不出来
    #[test]
    fn wide_string_ends_with_a_nul() {
        for s in ["", "A", "MiNo", "中文"] {
            let w = to_wide(s);
            assert_eq!(w.len(), s.encode_utf16().count() + 1, "{s:?}");
            assert_eq!(w[w.len() - 1], 0, "{s:?}");
        }
    }

    // -------------------------------------------------------------- 日志

    /// 注册表的值是不可信输入：带换行的串能伪造日志行，而日志是唯一的现场
    #[test]
    fn log_sanitizer_strips_control_chars() {
        let dirty = "C:\\a\\MiNo.exe\r\n伪造的一行\u{7}\t结束";
        let clean = sanitize_for_log(dirty);
        assert!(!clean.contains('\n'), "{clean:?}");
        assert!(!clean.contains('\r'), "{clean:?}");
        assert!(!clean.contains('\t'), "{clean:?}");
        assert!(!clean.contains('\u{7}'), "{clean:?}");
        assert!(clean.contains("伪造的一行"), "{clean:?}");
    }

    #[test]
    fn log_sanitizer_truncates_long_values() {
        let long = "a".repeat(500);
        let out = sanitize_for_log(&long);
        // 上限 200 个字符 + 一个省略号
        assert_eq!(out.chars().count(), 201, "{}", out.chars().count());
        assert!(out.ends_with('…'));
    }

    #[test]
    fn log_sanitizer_leaves_chinese_paths_alone() {
        let p = r"C:\用户\文档\MiNo.exe";
        assert_eq!(sanitize_for_log(p), p);
    }
}
