mod backend;

use backend::{Backend, BackendState};
use std::path::PathBuf;
use tauri::{Manager, RunEvent};

/// 前端轮询它。**端口只从这里出去**——前端不许硬编码端口。
#[tauri::command]
fn get_backend_status(backend: tauri::State<'_, Backend>) -> BackendState {
    backend.state.lock().unwrap().clone()
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
        .invoke_handler(tauri::generate_handler![get_backend_status])
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
