mod backend;
pub mod http;

use backend::{Backend, BackendState, BackendStatus};
use std::path::PathBuf;
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

/// 找后端 jar。优先环境变量，其次相对仓库的默认位置。
///
/// 候选路径是**相对当前工作目录**的：开发时 `cargo tauri dev` 从 `src-tauri`
/// 起进程，`../../backend/target/...` 就是仓库根下的 jar。找不到时把当前工作目录
/// 写进错误消息里，用户才知道为什么没命中。
fn locate_jar() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("CHANGE_BACKEND_JAR") {
        let path = PathBuf::from(&p);
        return if path.is_file() {
            Ok(path)
        } else {
            Err(format!("CHANGE_BACKEND_JAR 指向的文件不存在：{p}"))
        };
    }
    // 开发时的默认位置：src-tauri 往上三层是仓库根
    let candidates = [
        "../../backend/target/change-backend-0.1.0-SNAPSHOT.jar",
        "../backend/target/change-backend-0.1.0-SNAPSHOT.jar",
    ];
    for c in candidates {
        let path = PathBuf::from(c);
        if path.is_file() {
            return Ok(path);
        }
    }
    let cwd = std::env::current_dir()
        .map(|p| p.display().to_string())
        .unwrap_or_else(|e| format!("<拿不到工作目录：{e}>"));
    Err(format!(
        "找不到后端 jar。请先在 backend/ 下执行 `mvn -B -q -DskipTests package`，\
         或设环境变量 CHANGE_BACKEND_JAR 指向它。\
         （当前工作目录：{cwd}；已尝试的相对路径：{}）",
        candidates.join(" 与 ")
    ))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![get_backend_status, get_health])
        .setup(|app| {
            // 注意：`resolve_java` 返回 `PathBuf`，**不是 `Option`**。
            // 它刻意不做存在性检查——真是 Java 找不到，`Command::spawn` 抛出的
            // OS 错误比我们在这里猜的任何话都准，那条错误会经 `from_failed`
            // 显示给用户。所以这里不需要（也不能）`?` 或 `.ok_or(...)`。
            let java = backend::resolve_java(
                std::env::var("CHANGE_JAVA").ok().as_deref(),
                std::env::var("JAVA_HOME").ok().as_deref(),
            );

            let backend = match locate_jar() {
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
    #[test]
    fn both_commands_are_registered_in_the_invoke_handler() {
        const SRC: &str = include_str!("lib.rs");

        let line = SRC
            .lines()
            .find(|l| l.contains("generate_handler!["))
            .expect("lib.rs 里找不到 generate_handler! 调用——注册表不见了");
        assert!(
            !line.trim_start().starts_with("//"),
            "generate_handler! 那一行不该是注释：{line}"
        );

        for cmd in ["get_backend_status", "get_health"] {
            assert!(
                line.contains(cmd),
                "命令 {cmd:?} 没有注册进 invoke_handler——前端 invoke 它会直接 reject。\
                 实际那一行是：{line}"
            );
        }

        // 反面：`get_health` 必须真的在**宏的参数表**里，而不是出现在行尾注释里
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
            2,
            "命令清单恰好是两条；实际参数：{args}"
        );
    }
}
