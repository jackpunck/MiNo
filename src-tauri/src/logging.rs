//! 落盘日志。
//!
//! 存在的理由只有一个：窗口收缩那类 bug 在实机上偶发、本地复现不了，事后没有
//! 现场就只能猜。而 release 构建是 `windows_subsystem = "windows"`，stderr 无处
//! 可去，`env_logger` 只写 stderr —— 装出来的包里一行日志都没有，等于没有日志。
//!
//! 手写 `log::Log` 是为了不引新依赖：`log` facade 本来就在（托盘、吸附那边已经
//! 在用 `info!` / `warn!`），这里只是给它接一个文件 sink。

use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::Path;
use std::sync::Mutex;

use log::{LevelFilter, Log, Metadata, Record};
use tauri::AppHandle;

use crate::state;

/// 日志文件超过这个大小，就在下次启动时轮转一次。
/// 日志是无界的，不封顶就会一直长 —— 用户目录里不该出现一个几百 MB 的文本文件。
const MAX_LOG_BYTES: u64 = 1024 * 1024;

const LOG_NAME: &str = "mino.log";

struct FileLogger {
    file: Mutex<File>,
}

impl Log for FileLogger {
    fn enabled(&self, _metadata: &Metadata) -> bool {
        true
    }

    fn log(&self, record: &Record) {
        let line = format!(
            "[{}] {:<5} {}\n",
            chrono::Local::now().format("%Y-%m-%d %H:%M:%S"),
            record.level(),
            record.args()
        );

        // debug 构建下同时打到 stderr，`tauri dev` 里不用去翻文件
        #[cfg(debug_assertions)]
        eprint!("{line}");

        // 日志写不出来绝不能把应用带走：release profile 是 `panic = "abort"`，
        // 一次 unwrap 就等于整个进程没了 —— 而日志恰恰是用来查「进程怎么没的」
        // 的东西，它自己成为死因就太荒唐了。锁中毒同理，静默放弃即可。
        if let Ok(mut f) = self.file.lock() {
            let _ = f.write_all(line.as_bytes());
            // 每条都 flush：日志要活得过它记录的那次崩溃，攒在缓冲区里就没意义了。
            let _ = f.flush();
        }
    }

    fn flush(&self) {
        if let Ok(mut f) = self.file.lock() {
            let _ = f.flush();
        }
    }
}

/// 接管全局 logger。放在 `setup()` 里、一切初始化之前调用。
///
/// 拿不到目录或者文件开不了就静默放弃 —— **没有日志也必须能启动**，
/// 这是这个应用一以贯之的底线（规格 §21）。
pub fn init(app: &AppHandle) {
    // 复用 state 的路径解析，不自己拼 app_data_dir()：那个取的是 identifier
    // 而不是 productName，硬编码或猜错都会日志落到别的目录去。
    let Ok(dir) = state::data_dir(app) else { return };
    let path = dir.join(LOG_NAME);

    rotate(&path);

    let Ok(file) = OpenOptions::new().create(true).append(true).open(&path) else {
        return;
    };

    let _ = log::set_boxed_logger(Box::new(FileLogger {
        file: Mutex::new(file),
    }));
    log::set_max_level(LevelFilter::Info);
}

/// 超限就把当前日志改名成 `.old` 重开。只留一代 —— 够查最近这次复现就行，
/// 留多了又变成另一种形式的无界增长。
fn rotate(path: &Path) {
    let too_big = fs::metadata(path)
        .map(|m| m.len() > MAX_LOG_BYTES)
        .unwrap_or(false);

    if too_big {
        let _ = fs::rename(path, path.with_extension("log.old"));
    }
}
