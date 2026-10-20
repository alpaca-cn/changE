mod backend;
pub mod http;

use backend::{Backend, BackendState, BackendStatus};
use std::path::{Path, PathBuf};
use tauri::{Manager, RunEvent};

/// 前端轮询它。**端口只从这里出去**——前端不许硬编码端口。
#[tauri::command]
fn get_backend_status(backend: tauri::State<'_, Backend>) -> BackendState {
    backend.state.lock().unwrap().clone()
}

/// 代前端去问后端的 `/api/health`。
///
/// **为什么是外壳代发而不是前端 `fetch`**：页面源（dev 的 `http://127.0.0.1:1420`、
/// 打包后的 `tauri://localhost`）与后端的随机端口不同源，而响应里没有
/// `Access-Control-Allow-Origin`，浏览器会把响应拦掉——前端只能看到
/// `TypeError: Failed to fetch`。详见 `src/http.rs` 的模块注释。
///
/// # 为什么返回 `Result<_, String>`
///
/// Tauri 会把 `Err` 变成 invoke 的 reject，前端现有的 `catch` 分支直接接得住，
/// 不需要为「健康检查失败」再造一条带错误字段的返回类型。
///
/// # 就绪守卫
///
/// 端口只在**状态是 `Ready`** 时才有意义（`with_port` 是唯一给它赋值的地方）。
/// `Starting` 时端口还是 `None`——那时去连 `127.0.0.1:0` 只会拿到一个
/// 「连不上」的假错误，把「后端还在起」误报成「后端坏了」。`Failed` 时同理，
/// 而且失败原因已经在 `message` 里，比任何连接错误都准。
#[tauri::command]
fn get_health(backend: tauri::State<'_, Backend>) -> Result<backend::Health, String> {
    let (status, port, message) = {
        let s = backend.state.lock().unwrap();
        (s.status.clone(), s.port, s.message.clone())
    };
    match status {
        BackendStatus::Ready => {
            // `Ready` 必然带端口；真拿不到说明状态机被改坏了，如实报出来而不是猜一个端口
            let port = port.ok_or_else(|| {
                "后端状态是 ready，但没有端口——这是外壳内部的矛盾，请报告".to_string()
            })?;
            http::get_health(port)
        }
        BackendStatus::Starting => {
            Err("后端还在启动中，暂时没有健康状态可读".to_string())
        }
        BackendStatus::Failed => {
            Err(format!("后端没有起来，读不到健康状态：{message}"))
        }
    }
}

/// 代前端发一条到后端的请求（**通用代理**）。
///
/// **为什么前端不发 HTTP**：页面源（dev 的 `http://127.0.0.1:1420`、打包后的
/// `tauri://localhost`）与后端的**随机端口**不同源，响应里没有
/// `Access-Control-Allow-Origin`，浏览器会把响应拦掉——前端只能看到
/// `TypeError: Failed to fetch`。详见 `http.rs` 的模块注释。
///
/// **为什么返回 `Ok` 而不把 400/404 变成 `Err`**：前端要根据状态码分流——
/// 400 显示服务端的校验文案（`{error}`）、404 显示「找不到」、204 显示
/// 「这个动作没做过」。把状态码压成一句错误消息，这三条就再也分不开了。
/// `Err` 只留给传输层失败（连不上 / 超时 / 响应读不懂）。
///
/// # 就绪守卫
///
/// 与 [`get_health`] 同一道：端口只在状态是 `Ready` 时才有意义。
/// `Starting` 时去连 `127.0.0.1:0` 只会得到一个「连不上」的假错误，
/// 把「后端还在起」误报成「后端坏了」。
#[tauri::command]
fn api_request(
    backend: tauri::State<'_, Backend>,
    method: String,
    path: String,
    body: Option<String>,
) -> Result<http::ApiResponse, String> {
    let (status, port, message) = {
        let s = backend.state.lock().unwrap();
        (s.status.clone(), s.port, s.message.clone())
    };
    match status {
        BackendStatus::Ready => {
            let port = port.ok_or_else(|| {
                "后端状态是 ready，但没有端口——这是外壳内部的矛盾，请报告".to_string()
            })?;
            http::api_request(port, &method, &path, body.as_deref())
        }
        BackendStatus::Starting => Err("后端还在启动中，请稍候".to_string()),
        BackendStatus::Failed => Err(format!("后端没有起来：{message}")),
    }
}

/// 找后端 jar：随包 > `CHANGE_BACKEND_JAR` > 开发时的相对仓库路径。
///
/// `bundled_root` 是 [`tauri::path::PathResolver::resource_dir`] 给出的资源根；
/// `exists` 是可注入的存在性判定——测试靠它造「随包有/没有」，不必真放文件。
///
/// # 为什么随包排在环境变量前面
///
/// 安装包里的 jar 与随包的 JRE 是配套的。让一个环境变量把二者拆开（比如老机器上
/// 残留的 `CHANGE_BACKEND_JAR` 指向一个旧 jar）只会在别人的机器上炸，而且看起来
/// 像「装好了却起不来」。所以随包存在时**完全不看**环境变量——与 `resolve_java`
/// 的「随包 > 环境变量」是同一个理由。
///
/// # 顺序的代价（登记在案）
///
/// 开发时 `tauri-build` 也会把资源复制到 `target/debug`，而 `resource_dir()`
/// 正好是 exe 所在目录——于是开发机上可能命中「随包」那一条（目标是构建产物里的
/// 副本，不是 `resources/` 原件）。只要资源变了就重新构建，副本会跟着更新。
///
/// 候选路径是**相对当前工作目录**的：开发时 `cargo tauri dev` 从 `src-tauri`
/// 起进程，`../../backend/target/...` 就是仓库根下的 jar。找不到时把当前工作目录
/// 与试过的所有路径写进错误消息，用户才知道为什么没命中。
fn locate_jar(
    bundled_root: Option<&Path>,
    exists: &dyn Fn(&Path) -> bool,
) -> Result<PathBuf, String> {
    let bundled = bundled_root.map(|root| root.join("change-backend.jar"));
    if let Some(path) = &bundled {
        if exists(path) {
            return Ok(path.clone());
        }
    }
    // 显式指定了却不存在：如实说，别悄悄去用开发路径（那会让人以为环境变量生效了）
    if let Ok(p) = std::env::var("CHANGE_BACKEND_JAR") {
        let path = PathBuf::from(&p);
        return if exists(&path) {
            Ok(path)
        } else {
            Err(format!("CHANGE_BACKEND_JAR 指向的文件不存在：{p}"))
        };
    }
    // 开发时的默认位置：src-tauri 往上三层是仓库根
    let candidates = [
        PathBuf::from("../../backend/target/change-backend-0.1.0-SNAPSHOT.jar"),
        PathBuf::from("../backend/target/change-backend-0.1.0-SNAPSHOT.jar"),
    ];
    for c in &candidates {
        if exists(c) {
            return Ok(c.clone());
        }
    }
    let cwd = std::env::current_dir()
        .map(|p| p.display().to_string())
        .unwrap_or_else(|e| format!("<拿不到工作目录：{e}>"));
    let tried = bundled
        .iter()
        .chain(candidates.iter())
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join("；");
    Err(format!(
        "找不到后端 jar。请先在 backend/ 下执行 `mvn -B -q -DskipTests package`，\
         或设环境变量 CHANGE_BACKEND_JAR 指向它。\
         （当前工作目录：{cwd}；已尝试：{tried}）"
    ))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // 单行：下面的 `every_command_is_registered_in_the_invoke_handler`
        // 按「一行」读注册表（`lines().find(...)`）。改成多行会让它读不到命令名。
        .invoke_handler(tauri::generate_handler![get_backend_status, get_health, api_request])
        .setup(|app| {
            // 随包资源的根。安装后（Windows/NSIS）就是 exe 所在目录；
            // 开发时是 `target/debug` 之类，里面通常没有 resources，
            // 于是候选自然落到环境变量 / 仓库里的开发路径。
            let bundled_root = app.path().resource_dir().ok();
            let exists = |path: &std::path::Path| path.is_file();

            // `resolve_java` 现在返回 `Result`：加了随包候选之后必须做存在性检查，
            // 「一个都找不到」就成了真实分支。真的找不到时用 `from_failed` 造一个
            // 只有失败状态的句柄，让前端显示原因，而不是 panic 或拉起一个瞎猜的进程。
            let java = match backend::resolve_java(
                bundled_root.as_deref(),
                std::env::var("CHANGE_JAVA").ok().as_deref(),
                std::env::var("JAVA_HOME").ok().as_deref(),
                &exists,
            ) {
                Ok(java) => java,
                Err(msg) => {
                    app.manage(Backend::from_failed(msg));
                    return Ok(());
                }
            };

            let backend = match locate_jar(bundled_root.as_deref(), &exists) {
                Ok(jar) => {
                    let extra = std::env::var("CHANGE_BACKEND_ARGS")
                        .map(|s| s.split_whitespace().map(String::from).collect())
                        .unwrap_or_default();
                    match Backend::spawn(java, jar, extra) {
                        Ok(b) => b,
                        Err(e) => Backend::from_failed(format!("拉起后端进程失败：{e}")),
                    }
                }
                // 没有 jar 就没有子进程——`from_failed` 是唯一能造出这种句柄的方式
                // （`child` 字段对 backend 模块之外是私有的，结构体更新语法用不了）。
                Err(msg) => Backend::from_failed(msg),
            };

            app.manage(backend);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("Tauri 应用启动失败")
        .run(|app_handle, event| {
            // 窗口关闭时把后端一起带走，别留孤儿进程
            if let RunEvent::ExitRequested { .. } | RunEvent::Exit = event {
                if let Some(b) = app_handle.try_state::<Backend>() {
                    b.shutdown();
                }
            }
        });
}

#[cfg(test)]
mod tests {
    /// 命令注册表——**前端唯二能调到的两个名字**。
    ///
    /// # 为什么要有这条测试
    ///
    /// `tauri::generate_handler![]` 里漏掉一个命令，**编译照样通过**
    /// （最坏只是一条 `function is never used` 警告），`cargo test` 也照样全绿：
    /// 没有任何 Rust 测试会去碰 `.invoke_handler(...)` 这个调用。
    /// 而真机上的表现是前端 `invoke('get_health')` 直接 reject
    /// （`command get_health not found`）——正是本任务要修的那一类静默失效。
    ///
    /// 变异实测（Task 8 的变异表）：把 `get_health` 从注册表里删掉，
    /// 加这条测试之前 **75 passed / 0 failed，变异存活**；加完之后它变红。
    /// 前端那侧杀不了这个变异：`backend.spec.ts` 与 `BackendStatusBar.spec.ts`
    /// 都 mock 掉了 `invoke`，命令注册表不在它们的观察范围内。
    ///
    /// 用 `include_str!("lib.rs")` 而不是读磁盘：这样测试与大写文件**逐字同源**，
    /// 不存在「读到了别的路径下另一个 lib.rs」的可能。
    /// 断言取的是宏调用那一行，而不是整个文件——避免把注释里提到命令名也算作注册
    /// （本文件的注释里就写了好几次 `get_health`）。
    ///
    /// **注册表必须是单行**：下面用 `lines().find(...)` 只取一行。加命令时若把它
    /// 拆成多行，这条会读不到命令名而变红——那是刻意的（与其静默漏检，不如响亮地提醒）。
    /// 原名 `both_commands_are_registered_in_the_invoke_handler`（那时只有两条命令），
    /// 计划 4 加第三条时改名。
    #[test]
    fn every_command_is_registered_in_the_invoke_handler() {
        const SRC: &str = include_str!("lib.rs");

        let line = SRC
            .lines()
            .find(|l| l.contains("generate_handler!["))
            .expect("lib.rs 里找不到 generate_handler! 调用——注册表不见了");
        assert!(
            !line.trim_start().starts_with("//"),
            "generate_handler! 那一行不该是注释：{line}"
        );

        for cmd in ["get_backend_status", "get_health", "api_request"] {
            assert!(
                line.contains(cmd),
                "命令 {cmd:?} 没有注册进 invoke_handler——前端 invoke 它会直接 reject。\
                 实际那一行是：{line}"
            );
        }

        // 反面：命令必须真的在**宏的参数表**里，而不是出现在行尾注释里
        let args = line
            .split_once("generate_handler![")
            .and_then(|(_, rest)| rest.split_once(']'))
            .map(|(args, _)| args)
            .expect("generate_handler![...] 的括号不配对");
        assert!(
            !args.contains("//"),
            "注册表的参数里混进了注释，说明这行已经不像一个命令清单了：{args}"
        );
        assert_eq!(
            args.split(',').map(|s| s.trim()).filter(|s| !s.is_empty()).count(),
            3,
            "命令清单恰好是三条（get_backend_status / get_health / api_request）；实际参数：{args}"
        );
    }

    // ---- locate_jar：随包 > CHANGE_BACKEND_JAR > 开发路径 ----

    use super::locate_jar;
    use std::ffi::OsString;
    use std::path::{Path, PathBuf};
    use std::sync::{Mutex, MutexGuard};

    /// `CHANGE_BACKEND_JAR` 是**进程级全局状态**，而测试默认并行跑。
    /// 所有会读/写它的测试都先拿这把锁，避免 A 测试设的值被 B 测试读到。
    ///
    /// 锁被 panic 毒化时照样拿（`into_inner`）：测试已经红了，
    /// 不该让第二重 panic 把真正的失败原因盖住。
    static ENV_LOCK: Mutex<()> = Mutex::new(());

    fn lock_env() -> MutexGuard<'static, ()> {
        ENV_LOCK
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// 测试结束时把 `CHANGE_BACKEND_JAR` 恢复原值的 RAII 守卫。
    ///
    /// 不恢复的话，一个测试留下的值会改变同进程里后面所有测试的行为，
    /// 而那种失败看起来会像是在别的测试里、且时有时无。
    struct RestoreChangeBackendJar(Option<OsString>);

    impl Drop for RestoreChangeBackendJar {
        fn drop(&mut self) {
            match self.0.take() {
                Some(value) => std::env::set_var("CHANGE_BACKEND_JAR", value),
                None => std::env::remove_var("CHANGE_BACKEND_JAR"),
            }
        }
    }

    fn set_change_backend_jar(value: Option<&str>) -> RestoreChangeBackendJar {
        let previous = std::env::var_os("CHANGE_BACKEND_JAR");
        match value {
            Some(value) => std::env::set_var("CHANGE_BACKEND_JAR", value),
            None => std::env::remove_var("CHANGE_BACKEND_JAR"),
        }
        RestoreChangeBackendJar(previous)
    }

    /// 与 `locate_jar` 里那两个开发候选保持同一份字面量。
    const DEV_JAR_A: &str = "../../backend/target/change-backend-0.1.0-SNAPSHOT.jar";
    const DEV_JAR_B: &str = "../backend/target/change-backend-0.1.0-SNAPSHOT.jar";

    #[test]
    fn prefers_the_bundled_jar_over_change_backend_jar() {
        // 随包 jar 存在时**完全不看**环境变量——即使环境变量指向一个同样存在的文件。
        let _guard = lock_env();
        let _env = set_change_backend_jar(Some("/env/change-backend.jar"));
        let root = Path::new("/install");
        let bundled = root.join("change-backend.jar");
        let exists = |p: &Path| {
            p == bundled.as_path() || p == Path::new("/env/change-backend.jar")
        };
        assert_eq!(locate_jar(Some(root), &exists), Ok(bundled.clone()));
    }

    #[test]
    fn uses_change_backend_jar_when_the_bundled_jar_is_missing() {
        // 随包路径的存在性检查没过就必须往下走。若实现不做检查、直接返回随包路径，
        // 这条会拿到一个根本不存在的 jar。
        let _guard = lock_env();
        let _env = set_change_backend_jar(Some("/env/change-backend.jar"));
        let exists = |p: &Path| p == Path::new("/env/change-backend.jar");
        assert_eq!(
            locate_jar(Some(Path::new("/install")), &exists),
            Ok(PathBuf::from("/env/change-backend.jar"))
        );
    }

    #[test]
    fn falls_back_to_the_development_jar_path() {
        // 开发机上的正常路径：没有随包资源、没设环境变量，仓库里那个 jar 还在。
        // 第二个候选命中，同时也证明第一个候选**先被检查过**（exists 只认第二个）。
        let _guard = lock_env();
        let _env = set_change_backend_jar(None);
        let exists = |p: &Path| p == Path::new(DEV_JAR_B);
        assert_eq!(locate_jar(None, &exists), Ok(PathBuf::from(DEV_JAR_B)));
    }

    #[test]
    fn reports_every_tried_jar_path_when_nothing_exists() {
        // 「朋友的机器上出问题」时唯一能救命的信息：试过哪些路径。
        let _guard = lock_env();
        let _env = set_change_backend_jar(None);
        let root = Path::new("/install");
        let err = locate_jar(Some(root), &|_| false).unwrap_err();
        for expected in [
            root.join("change-backend.jar"),
            PathBuf::from(DEV_JAR_A),
            PathBuf::from(DEV_JAR_B),
        ] {
            let needle = expected.display().to_string();
            assert!(
                err.contains(&needle),
                "错误消息里必须列出试过的路径 {needle:?}；实际：{err}"
            );
        }
        // 原来那条可操作的错误不能丢：用户要能照着做。
        assert!(err.contains("mvn"), "应当告诉人先跑 mvn package；实际：{err}");
        assert!(
            err.contains("CHANGE_BACKEND_JAR"),
            "应当告诉人可以用环境变量指定；实际：{err}"
        );
    }

    #[test]
    fn reports_a_missing_change_backend_jar_instead_of_silently_falling_through() {
        // 保留原来的行为：显式指定了却不存在，要说出来（而不是悄悄去用开发路径）。
        let _guard = lock_env();
        let _env = set_change_backend_jar(Some("/typo/change-backend.jar"));
        let err = locate_jar(None, &|_| false).unwrap_err();
        assert!(
            err.contains("/typo/change-backend.jar"),
            "错误里要带上是哪个路径不存在；实际：{err}"
        );
        assert!(err.contains("CHANGE_BACKEND_JAR"), "实际：{err}");
    }

    #[test]
    fn does_not_panic_when_the_resource_dir_is_unavailable() {
        // 开发时 `resource_dir()` 未必拿得到：缺随包根只是一个候选缺席。
        let _guard = lock_env();
        let _env = set_change_backend_jar(None);
        assert!(locate_jar(None, &|_| false).is_err());
    }
}
