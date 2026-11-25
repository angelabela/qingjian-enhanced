//! tracing 接到 logcat（标签 `qingjian`），并按手机本地日期写进 `files/logs/`（留 [`KEEP_DAYS`] 天），设置里「打包日志」交的就是它；
//! panic 也记一条（安卓上 stderr 看不到）。文件缺省只记 info 及以上，开了详细日志才记 debug（会带上打的字）。
//! Kotlin 的日志与没接住的异常经 [`Java_app_qingjian_inputmethod_core_Native_log`] 写进同一份文件。
//! 非 Android 目标（本机测试）什么都不做。

use ::jni::JNIEnv;
use ::jni::objects::{JObject, JString};
#[cfg(target_os = "android")]
use ::jni::sys::JNI_FALSE;
use ::jni::sys::{jboolean, jint};

/// 日志文件保留天数（按手机本地日期命名）。
#[cfg(target_os = "android")]
const KEEP_DAYS: usize = 30;

#[cfg(target_os = "android")]
struct LocalDailyAppender {
    dir: std::path::PathBuf,
    state: std::sync::Mutex<LocalDailyState>,
}
#[cfg(target_os = "android")]
struct LocalDailyState {
    date: String,
    file: std::fs::File,
}
#[cfg(target_os = "android")]
struct LocalDailyWriter<'a> {
    state: std::sync::MutexGuard<'a, LocalDailyState>,
}
#[cfg(target_os = "android")]
impl LocalDailyAppender {
    fn new(dir: &std::path::Path) -> std::io::Result<Self> {
        std::fs::create_dir_all(dir)?;
        let date = jiff::Zoned::now().date().to_string();
        let file = Self::open_file(dir, &date)?;
        Self::prune(dir);
        Ok(Self { dir: dir.to_path_buf(), state: std::sync::Mutex::new(LocalDailyState { date, file }) })
    }
    fn open_file(dir: &std::path::Path, date: &str) -> std::io::Result<std::fs::File> {
        std::fs::OpenOptions::new().create(true).append(true).open(dir.join(format!("ime.{date}.log")))
    }
    fn prune(dir: &std::path::Path) {
        let mut files: Vec<_> = std::fs::read_dir(dir).into_iter().flatten().flatten()
            .filter_map(|entry| {
                let path = entry.path();
                let name = path.file_name()?.to_str()?;
                (path.is_file() && name.starts_with("ime.") && name.ends_with(".log"))
                    .then_some((name.to_owned(), path))
            }).collect();
        files.sort_by(|a, b| b.0.cmp(&a.0));
        for (_, path) in files.into_iter().skip(KEEP_DAYS) { let _ = std::fs::remove_file(path); }
    }
}
#[cfg(target_os = "android")]
impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for LocalDailyAppender {
    type Writer = LocalDailyWriter<'a>;
    fn make_writer(&'a self) -> Self::Writer {
        // 每条事件持有同一把锁完成写入，避免跨本地零点时一行日志被拆分。
        let today = jiff::Zoned::now().date().to_string();
        let mut state = self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if state.date != today {
            if let Ok(file) = Self::open_file(&self.dir, &today) {
                state.date = today;
                state.file = file;
                Self::prune(&self.dir);
            }
        }
        LocalDailyWriter { state }
    }
}
#[cfg(target_os = "android")]
impl std::io::Write for LocalDailyWriter<'_> {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> { std::io::Write::write(&mut self.state.file, buffer) }
    fn flush(&mut self) -> std::io::Result<()> { std::io::Write::flush(&mut self.state.file) }
}

/// 详细日志开着：文件也记 debug。
#[cfg(target_os = "android")]
static VERBOSE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// 装上日志。只有第一次调用算数：应用一起来 Kotlin 就带着日志目录调（`initLogging`），
/// 别的出口兜底调时不带目录，只接 logcat。
#[cfg(target_os = "android")]
pub fn init(log_dir: Option<&std::path::Path>) {
    use tracing::level_filters::LevelFilter;
    use tracing::subscriber::Interest;
    use tracing_subscriber::Layer;
    use tracing_subscriber::filter::DynFilterFn;
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;

    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        // jni crate 每次调用都打 trace，只留 debug 及以上
        let logcat = tracing_android::layer("qingjian")
            .ok()
            .map(|layer| layer.with_filter(LevelFilter::DEBUG));
        let file = log_dir.and_then(|dir| {
            // 按 Android 当前系统时区的本地日期滚动，而不是按 UTC 日期边界滚动。
            let appender = LocalDailyAppender::new(dir).ok()?;
            // 开关随时会变，每条都问，不按调用点缓存
            let filter = DynFilterFn::new(|metadata, _| {
                *metadata.level() <= tracing::Level::INFO
                    || (*metadata.level() <= tracing::Level::DEBUG
                        && VERBOSE.load(std::sync::atomic::Ordering::Relaxed))
            })
            .with_callsite_filter(|_| Interest::sometimes());
            // 不经后台线程：没接住的异常记完进程就没了，要当场落盘
            Some(
                tracing_subscriber::fmt::layer()
                    .with_ansi(false)
                    .with_writer(appender)
                    .with_filter(filter),
            )
        });
        let _ = tracing_subscriber::registry()
            .with(logcat)
            .with(file)
            .try_init();
        // 不记正文、也不接回默认 hook（它会打出正文）：切片越界之类的消息会带上用户打的字
        std::panic::set_hook(Box::new(|info| {
            let location = info.location();
            tracing::error!(
                file = location.map(|location| location.file()),
                line = location.map(|location| location.line()),
                "panic; payload omitted"
            );
        }));
    });
}

#[cfg(not(target_os = "android"))]
pub fn init(_log_dir: Option<&std::path::Path>) {}

/// 应用一起来就调：日志写进 `log_dir`。
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_inputmethod_core_Native_initLogging<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    log_dir: JString<'local>,
) {
    let Ok(log_dir) = env.get_string(&log_dir).map(String::from) else {
        return;
    };
    let _ = std::fs::create_dir_all(&log_dir);
    let _ = std::panic::catch_unwind(|| init(Some(std::path::Path::new(&log_dir))));
}

/// 详细日志开关：开着文件也记 debug。
#[unsafe(no_mangle)]
#[cfg_attr(not(target_os = "android"), allow(unused_variables))]
pub extern "system" fn Java_app_qingjian_inputmethod_core_Native_setVerboseLog<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    on: jboolean,
) {
    #[cfg(target_os = "android")]
    VERBOSE.store(on != JNI_FALSE, std::sync::atomic::Ordering::Relaxed);
}

/// Kotlin 侧的一条日志：`level` 0 错误、1 警告、2 信息、其余调试。
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_inputmethod_core_Native_log<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    level: jint,
    message: JString<'local>,
) {
    let Ok(message) = env.get_string(&message).map(String::from) else {
        return;
    };
    match level {
        0 => tracing::error!(target: "kotlin", "{message}"),
        1 => tracing::warn!(target: "kotlin", "{message}"),
        2 => tracing::info!(target: "kotlin", "{message}"),
        _ => tracing::debug!(target: "kotlin", "{message}"),
    }
}

