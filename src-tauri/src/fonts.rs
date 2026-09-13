//! 字体枚举与自定义字体导入。
//!
//! 两条路径：
//!   - 系统字体：枚举本机已安装的字体族，直接交给 CSS 用 family 名引用。
//!   - 导入字体：把用户的 .ttf/.otf 复制进 app data，再把原始字节喂给
//!     前端的 `new FontFace(family, ArrayBuffer)`。
//!
//! 导入走「读取字节 → FontFace」而不是 `@font-face { src: url(...) }`，
//! 因为后者需要开启 asset protocol、放宽 CSP，并且字体请求是 CORS 模式的，
//! 跨 origin 能否成功没有把握。用 ArrayBuffer 构造 FontFace 不发起任何请求，
//! 因此不需要改任何安全配置。

use log::{info, warn};
use tauri::{ipc::Response, AppHandle};

use crate::state::{self, CustomFont};

/// 单个字体文件上限。CJK 字体动辄十几 MB，64 MB 已经很宽松。
const MAX_FONT_BYTES: u64 = 64 * 1024 * 1024;

/// 枚举失败时的兜底清单。
///
/// 字体枚举是「非关键环节」：即使 DirectWrite 查询出错，用户也应该能选到
/// 常用字体，而不是看到一个空列表。中英文名都列出，保证 CSS 能匹配到。
const FALLBACK_FONTS: &[&str] = &[
    "Microsoft YaHei",
    "微软雅黑",
    "Microsoft YaHei UI",
    "SimSun",
    "宋体",
    "SimHei",
    "黑体",
    "KaiTi",
    "楷体",
    "FangSong",
    "仿宋",
    "DengXian",
    "等线",
    "Microsoft JhengHei",
    "Segoe UI",
    "Segoe UI Variable",
    "Arial",
    "Calibri",
    "Cambria",
    "Consolas",
    "Georgia",
    "Tahoma",
    "Times New Roman",
    "Trebuchet MS",
    "Verdana",
    "Courier New",
    "JetBrains Mono",
    "Fira Code",
    "Cascadia Code",
    "Source Han Sans SC",
    "思源黑体",
    "Noto Sans SC",
];

// ---------------------------------------------------------------------------
// 枚举
// ---------------------------------------------------------------------------

/// 返回本机可用的字体族名。
///
/// 永不返回 Err —— 枚举失败时退化为兜底清单。调用方不必处理错误分支。
pub fn list_families() -> Vec<String> {
    let mut names: Vec<String> = Vec::new();

    match font_kit::source::SystemSource::new().all_families() {
        Ok(families) => {
            info!("枚举到 {} 个系统字体族", families.len());
            names.extend(families);
        }
        Err(e) => {
            warn!("系统字体枚举失败，使用兜底清单: {e}");
        }
    }

    // 兜底清单始终并入：DirectWrite 在部分系统上只返回本地化名，
    // 把英文名一并提供可以提高 CSS 命中的概率。
    for f in FALLBACK_FONTS {
        names.push((*f).to_string());
    }

    names.retain(|n| !n.trim().is_empty());
    names.sort_by_key(|n| n.to_lowercase());
    names.dedup_by(|a, b| a.eq_ignore_ascii_case(b));
    names
}

// ---------------------------------------------------------------------------
// 导入
// ---------------------------------------------------------------------------

/// FNV-1a 64 位。
///
/// 用内容哈希当文件名和去重键：这样用户的原始文件名永远不会出现在磁盘上，
/// 一次性规避了路径穿越、Windows 保留名（CON/NUL/COM1）、非法字符、
/// 大小写不敏感冲突等问题。手写而不用哈希库是为了让 id 跨版本稳定 ——
/// 一旦换算法，已导入的字体就会全部失联。
fn content_id(bytes: &[u8]) -> String {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in bytes {
        h ^= u64::from(*b);
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("{h:016x}")
}

/// 按魔数判断字体类型。绝不信任扩展名 —— 一个 .ttf 后缀的文件可能是任何东西。
/// 返回 None 表示不是我们支持的字体格式。
fn sniff_ext(bytes: &[u8]) -> Option<&'static str> {
    let head = bytes.get(..4)?;
    match head {
        [0x00, 0x01, 0x00, 0x00] | [b't', b'r', b'u', b'e'] => Some("ttf"),
        [b'O', b'T', b'T', b'O'] => Some("otf"),
        [b't', b't', b'c', b'f'] => Some("ttc"),
        _ => None,
    }
}

/// 为一个字体名构造完整 CSS 回退链。
///
/// 单给一个 family 名是不够的：一旦该名字在本机匹配不到（本地化名字就是
/// 常见情形），`font-family` 会静默跳过它。所以尾部永远挂上通用族兜底。
///
/// 名字里的双引号必须剔除：字体族名理论上可以含引号，而引号会提前闭合
/// CSS 字符串，让整条 font-family 声明失效 —— 而且是静默失效。
pub fn chain_for(name: &str) -> String {
    let safe = name.replace(['"', '\\'], "");
    format!("\"{safe}\", {}", state::DEFAULT_FONT_CHAIN)
}

/// 导入字体字节，返回登记信息（尚未写入设置）。
pub fn import_bytes(app: &AppHandle, label: &str, bytes: &[u8]) -> Result<CustomFont, String> {
    if bytes.is_empty() {
        return Err("字体文件是空的".into());
    }
    if bytes.len() as u64 > MAX_FONT_BYTES {
        return Err(format!(
            "字体文件过大（{} MB），上限 {} MB",
            bytes.len() / 1024 / 1024,
            MAX_FONT_BYTES / 1024 / 1024
        ));
    }

    let ext = match sniff_ext(bytes) {
        Some("ttc") => {
            // TTC 是字体集合，一个文件里装着多个字体，而 FontFace 一次只能表达
            // 一个 family/weight/style，浏览器侧的 OTS 校验会直接拒收。
            // 与其导入后静默失败，不如当场说清楚。
            return Err(
                "这是一个字体集合（.ttc），包含多个字体。请选择单独的 .ttf 或 .otf 文件。"
                    .into(),
            );
        }
        Some(e) => e,
        None => {
            return Err("无法识别的字体格式，仅支持 .ttf 与 .otf".into());
        }
    };

    let id = content_id(bytes);
    let rel = format!("fonts/{id}.{ext}");
    let dest = state::fonts_dir(app)?.join(format!("{id}.{ext}"));

    // 内容哈希相同即文件相同，无需重复写入
    if !dest.exists() {
        std::fs::write(&dest, bytes).map_err(|e| format!("保存字体失败: {e}"))?;
        info!("已导入字体 {id}.{ext}");
    }

    let label = if label.trim().is_empty() {
        "未命名字体".to_string()
    } else {
        label.trim().to_string()
    };

    Ok(CustomFont {
        // 自行分配 family 名：保证是 ASCII，且绝不会覆盖同名系统字体。
        // 前缀是常量而不是字面量：它会落到 settings.fontFamily 和每条 todo.font
        // 上，改一次就得配一次迁移（见 state::rename_font_family_prefix）。
        family: format!("{}{}", state::FONT_FAMILY_PREFIX, &id[..8]),
        id,
        label,
        file: rel,
    })
}

/// 读取已导入字体的原始字节。
///
/// 返回 `Response` 而不是 `Vec<u8>`：后者会被序列化成 JSON 数字数组，
/// 体积膨胀数倍并造成明显卡顿。
pub fn read(app: &AppHandle, file: &str) -> Result<Response, String> {
    // 只允许访问 fonts/ 目录下的文件，杜绝 ../ 逃逸
    let name = file
        .strip_prefix("fonts/")
        .ok_or("非法的字体路径")?;
    if name.contains('/') || name.contains('\\') || name.contains("..") {
        return Err("非法的字体路径".into());
    }

    let path = state::fonts_dir(app)?.join(name);
    let bytes = std::fs::read(&path).map_err(|e| format!("读取字体失败: {e}"))?;
    Ok(Response::new(bytes))
}

/// 清理磁盘上文件已丢失的字体登记，避免设置里留下永远加载不出来的条目。
pub fn prune_missing(app: &AppHandle, data: &mut state::AppData) -> bool {
    let dir = match state::fonts_dir(app) {
        Ok(d) => d,
        Err(_) => return false,
    };

    let before = data.settings.custom_fonts.len();
    data.settings.custom_fonts.retain(|f| {
        let name = f.file.strip_prefix("fonts/").unwrap_or(&f.file);
        dir.join(name).is_file()
    });

    let removed = before - data.settings.custom_fonts.len();
    let mut changed = removed > 0;

    if data
        .settings
        .font_id
        .as_ref()
        .is_some_and(|id| !data.settings.custom_fonts.iter().any(|f| &f.id == id))
    {
        // 正在使用的字体消失了，回退到默认字体链
        data.settings.font_id = None;
        data.settings.font_family = state::DEFAULT_FONT_CHAIN.into();
        warn!("当前字体文件已丢失，已恢复默认字体");
    }

    // 引用了已经不存在的导入字体的任务也要一起清掉。留着那个链就等于指向一个
    // 永远注册不进来的 family，浏览器会静默回退到链尾的 sans-serif，用户看到的
    // 是「字体自己变了」。清空 = 回到「没盖章」状态，按全局字体渲染，结果确定。
    //
    // 只清 todos：archives 不再渲染，留着不影响显示。
    // 收成 owned String：这样 known 不借用 data，下面的 iter_mut 就是唯一一个
    // 活跃的可变借用，借用检查没有任何含糊的余地。（字体就几个，启动时算一次。）
    let known: std::collections::HashSet<String> = data
        .settings
        .custom_fonts
        .iter()
        .map(|f| f.id.clone())
        .collect();

    for t in data.todos.iter_mut() {
        if t.font_id.as_ref().is_some_and(|id| !known.contains(id)) {
            t.font_id = None;
            t.font = String::new();
            changed = true;
        }
    }

    changed
}
