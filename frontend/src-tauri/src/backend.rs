//! 后端子进程的拉起与端口协商。
//!
//! 协议（由计划 1 的 `PortReporter` 定义，改动等于改协议）：
//! 后端把一行 `CHANGE_PORT=<端口>` 打到**标准输出**，外壳读它来定位后端。
//!
//! 这里刻意不依赖 Tauri 运行时——端口解析是本计划唯一有真实逻辑的地方，
//! 抽成纯函数才能被 `cargo test` 直接覆盖（起 Tauri 运行时的测试跑不动 CI）。

use serde::Serialize;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// 协议前缀。**改动等于改协议**，会让外壳找不到后端。
pub const PORT_PREFIX: &str = "CHANGE_PORT=";

/// 从后端 stdout 的一行里解析端口。
///
/// 返回 `None` 表示这一行不是协议行（外壳应当继续读下一行）。
///
/// **为什么必须 `trim`**（以下三条是 2026-10-16 在本机实测出来的，不是推断）：
///
/// 1. 后端输出的那一行以 `\r\n` 结尾。按字节捕获真实后端 stdout 实测为
///    `b'CHANGE_PORT=54791\r\n'`（19 字节 = 17 个可见字节 + `0x0d 0x0a`）。
/// 2. 同一个函数、同一份输入，两种调用方拿到的字符串**不一样**：
///    - `BufRead::lines()` 剥掉 `\r\n` → `"CHANGE_PORT=54791"`（17 字节）
///    - `BufRead::read_line` **什么都不剥** → `"CHANGE_PORT=54791\r\n"`（19 字节）
///      （实测：`n=19, len=19`；多行输入下循环读到 `n=18 …\r\n` 与 `n=5 "more\n"`）
/// 3. 这个函数是 `pub` 的纯函数，它的正确性不该取决于谁调用它、以及那个人用了
///    `lines()` 还是 `read_line`。所以它自己 `trim`，把这件事关在函数内部——
///    调用方随便用哪种，行为都一样。
///
/// > 计划里曾有三处说法不成立，均已按实测改写：
/// > (a) 「Windows 上是 18 字节」——实测 **19** 字节（含 `\n`；不含 `\n` 是 18）。
/// > (b) 「Java 的 `Integer.parseInt` 恰好容忍尾部 `\r`」——实测**抛
/// >     `NumberFormatException`**（OpenJDK 25.0.2 直接 `javac`/`java` 跑）。
/// > (c) 「`read_line` 只剥 `\n`」——实测**它一个都不剥**，`\n` 与 `\r` 都留在
/// >     字符串里。剥掉 `\r\n` 的只有 `lines()`。
/// > 另外本链路里**根本没有 `parseInt`**：`PortReporter` 是生产者，它 `println` 的
/// > 是 Spring 给它的 `local.server.port` 字符串，不做任何解析——(b) 与这条链路无关。
/// > 详见 `docs/superpowers/plans/2026-10-16-change-frontend-shell.md` Task 4 的实测记录。
pub fn parse_port_line(line: &str) -> Option<u16> {
    let rest = line.trim().strip_prefix(PORT_PREFIX)?;
    let port: u16 = rest.trim().parse().ok()?;
    // 0 不是操作系统会分配的端口，出现它说明这不是一条有效协议行
    if port == 0 {
        None
    } else {
        Some(port)
    }
}

/// 找 Java：随包 > `CHANGE_JAVA` > `JAVA_HOME`。
///
/// `bundled_root` 是安装目录下的资源根（`resource_dir()`）；`exists` 是可注入的
/// 存在性判定——测试靠它造「随包有/没有」而不用真去建一个 48 MB 的 JRE。
///
/// # 为什么返回 `Result`（这里是**刻意**从 `PathBuf` 改过来的）
///
/// 加了「随包优先」之后**必须**先判断随包路径在不在——否则那个路径会永远被选中，
/// 哪怕它不存在（装了一半的安装目录、或开发机上根本没有 `resources/`）。
/// 而一旦做了存在性检查，「一个都找不到」就成了一个真实分支：旧实现最后一档
/// 是交给 PATH 的裸命令 `java`，它的正确性只能由 `Command::spawn` 的 OS 错误
/// 兜底；现在候选都是可检查的真实路径，全试过仍找不到时，一句**列出试过哪些
/// 路径**的 `Err` 比让 spawn 去猜有用得多——那是朋友机器上唯一能救命的信息。
///
/// 裸 `java` 因此不再作为候选：它无法用同一个 `exists` 判定检查，留在候选里
/// 只会让「存在的路径才会被返回」这条不变量出现例外。
///
/// 参数是显式传入的，方便测试——不要在函数里直接读 `std::env`。
pub fn resolve_java(
    bundled_root: Option<&Path>,
    change_java: Option<&str>,
    java_home: Option<&str>,
    exists: &dyn Fn(&Path) -> bool,
) -> Result<PathBuf, String> {
    let bundled = bundled_root.map(|root| root.join("runtime").join("bin").join(java_exe()));
    // 空串在 Windows 环境里很常见（`$env:CHANGE_JAVA = ''`），它不是配置
    let configured = change_java.filter(|p| !p.is_empty()).map(PathBuf::from);
    let home = java_home
        .filter(|home| !home.is_empty())
        .map(|home| Path::new(home).join("bin").join(java_exe()));

    let mut tried: Vec<PathBuf> = Vec::new();
    for candidate in [bundled, configured, home].into_iter().flatten() {
        if exists(&candidate) {
            return Ok(candidate);
        }
        tried.push(candidate);
    }

    if tried.is_empty() {
        return Err(
            "找不到可用的 Java：没有随包运行时，也没有设置 CHANGE_JAVA 或 JAVA_HOME。"
                .to_string(),
        );
    }
    Err(format!(
        "找不到可用的 Java。已尝试：{}。\
         随包运行时可能损坏（可以重新安装）；开发时请把 JAVA_HOME 指向一个 JDK，\
         或用 CHANGE_JAVA 指向 java 可执行文件。",
        tried
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join("；")
    ))
}

/// `JAVA_HOME/bin/` 下 java 的文件名。Windows 上带 `.exe`。
fn java_exe() -> &'static str {
    if cfg!(windows) {
        "java.exe"
    } else {
        "java"
    }
}

/// 等协议行的上限。计划 1 实测后端启动 3.8–6.8 秒，30 秒足够宽松。
///
/// 它只是 [`Backend::spawn_with_timeout`] 的默认值来源——超时能被注入，测试才测得了。
pub const STARTUP_TIMEOUT: Duration = Duration::from_secs(30);

/// EOF 之后为了拿到真实退出码而重试 `try_wait` 的次数与间隔。
///
/// 读到 EOF 时子进程**通常**已经退出，但 `try_wait` 可能还要过一两毫秒才看得到状态。
/// 重试总时长约 1 秒：再长就是「进程没退但关了 stdout」这种畸形情况了，
/// 那时如实说「拿不到退出码」比继续等更有用。
const EXIT_STATUS_RETRIES: u32 = 20;
const EXIT_STATUS_RETRY_INTERVAL: Duration = Duration::from_millis(50);

/// 后端状态。前端轮询它决定显示什么。
///
/// **不要加 `#[serde(tag = ...)]`**——那会把它序列化成 `{"state":"starting"}`（对象），
/// 而前端的 `BackendState.status` 期望的是字符串 `"starting"`。
/// 单元枚举的默认表示就是字符串，这里只改大小写风格即可。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum BackendStatus {
    /// 正在拉起后端（前端显示加载提示）。
    Starting,
    /// 后端已就绪，`port` 可用。
    Ready,
    /// 起不来。`message` 是给人看的原因。
    Failed,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendState {
    pub status: BackendStatus,
    pub port: Option<u16>,
    pub message: String,
}

impl BackendState {
    pub fn starting() -> Self {
        Self {
            status: BackendStatus::Starting,
            port: None,
            message: "正在启动后端…".into(),
        }
    }

    /// 拿到端口 → 就绪。**已就绪时不再改状态**（就绪是单向的）。
    pub fn with_port(mut self, port: u16) -> Self {
        if matches!(self.status, BackendStatus::Ready) {
            return self;
        }
        self.status = BackendStatus::Ready;
        self.port = Some(port);
        self.message = format!("后端已连接 · 端口 {port}");
        self
    }

    /// 子进程的 stdout 关掉了，而协议行始终没出现——**若还没就绪，这就是失败**。
    ///
    /// `code` 是 `Option`：`Some(n)` 是我们真的从 `try_wait` 拿到了退出状态；
    /// `None` 表示**拿不到退出码**——可能是进程还活着却关了 stdout（畸形），
    /// 也可能是系统还没把退出状态交给我们。`None` **不等于**「被信号终止」，
    /// 消息措辞对这两种情况都必须成立。
    pub fn child_exited(mut self, code: Option<i32>) -> Self {
        if matches!(self.status, BackendStatus::Ready) {
            return self;
        }
        let how = match code {
            Some(c) => format!("已退出（退出码 {c}）"),
            None => "已关闭 stdout（拿不到退出码）".to_string(),
        };
        self.status = BackendStatus::Failed;
        self.message = format!(
            "后端启动失败：进程{how}，但没有输出 {PORT_PREFIX}<端口> 这一行。\
             请确认 jar 路径与 Java 可用，或查看后端自己的日志。"
        );
        self
    }

    pub fn timed_out(mut self) -> Self {
        if matches!(self.status, BackendStatus::Ready) {
            return self;
        }
        self.status = BackendStatus::Failed;
        self.message = format!("后端启动超时：在超时前没有输出 {PORT_PREFIX}<端口> 这一行。");
        self
    }

    pub fn failed(msg: impl Into<String>) -> Self {
        Self {
            status: BackendStatus::Failed,
            port: None,
            message: msg.into(),
        }
    }
}

/// 后端健康端点 `/api/health` 的响应，形状与前端 `Health` interface 一致。
///
/// **两个字段都不可省、都不可给默认值**：这是**跨语言契约**——Rust 这边
/// 序列化出来的字段名，就是前端 `src/api/backend.ts` 里那个 `Health` interface
/// 读的字段名。两处是手写定义的，可以漂移而没有任何东西会报警（TS 不校验
/// 线上来的 JSON）。所以这里刻意不留 `#[serde(default)]`：
/// 后端少给一个字段时应当**报错**（用户能看见一句话），而不是静默显示空文案。
///
/// `camelCase` 是防御性的、一个字符都不改：`status` 与 `cache` 本来就是单数小写词，
/// 而写上它能让「以后加一个多词字段时忘了转换」这件事在代码里有个锚点。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Health {
    /// 后端自报的状态。当前恒为 `"up"`。
    pub status: String,
    /// 缓存的一句话描述，形如「缓存：未启用（配置为关闭）」。
    pub cache: String,
}

/// 健康检查失败的**分类**。
///
/// 分类而不是直接拼字符串的原因：错误消息是给用户看的（状态栏右侧原样渲染），
/// 所以「连不上」「超时」「非 2xx」「JSON 畸形」必须各说各的话；而测试要断言的是
/// **哪一类**错误，不该去匹配一段会随风改动的文案。
///
/// 用一个自有枚举而不是 `String` 或 `Box<dyn Error>`：`String` 会让上面那条
/// 「按类断言」变成不可能，而 `Box<dyn Error>` 对这三条自带消息的路径毫无增益。
#[derive(Debug)]
pub enum HealthError {
    /// `TcpStream::connect` 都没成功——端口上没人监听，或后端还没起来。
    Connect {
        addr: String,
        source: std::io::Error,
    },
    /// 连上了，但写请求或读响应出错。**超时走的就是这一条**。
    Read {
        addr: String,
        source: std::io::Error,
    },
    /// HTTP 状态码不是 200。
    NotOk { code: u16 },
    /// 状态行/分块编码/JSON 有一处对不上。
    Malformed { detail: String },
}

impl HealthError {
    /// 转成给用户看的一句话。
    ///
    /// `is_timeout()` 那一步是刻意的：超时在 `std::io` 里只是一个
    /// `ErrorKind::WouldBlock`/`TimedOut` 的 `io::Error`，如果直接把它 `to_string()`
    /// 出来，用户看到的是「资源暂时不可用」这种**完全指不出下一步**的话，
    /// 而真正的意思很简单：「后端没在 2 秒内回话」。
    pub fn into_message(self) -> String {
        match self {
            HealthError::Connect { addr, source } => {
                format!("连不上后端 {addr}：{source}")
            }
            HealthError::Read { addr, source } => {
                if is_timeout(&source) {
                    format!(
                        "后端 {addr} 超过 {} 毫秒没有回话",
                        crate::http::HEALTH_TIMEOUT.as_millis()
                    )
                } else {
                    format!("与后端 {addr} 通信失败：{source}")
                }
            }
            HealthError::NotOk { code } => {
                format!("后端健康检查返回了 HTTP {code}（期望 200）")
            }
            HealthError::Malformed { detail } => {
                format!("后端健康检查的响应读不懂：{detail}")
            }
        }
    }
}

/// 这个 `io::Error` 是不是超时。
///
/// Windows 上的 socket 读超时**不一定**报 `TimedOut`（实测常见的是
/// `WouldBlock` 或 `ConnectionAborted`），所以三种都算，并且按 `to_string()` 里的
/// 关键词兜一道底——多认一种不会把非超时说成超时，漏认一种却会让用户看到
/// 「资源暂时不可用」这种没有下一步的话。
fn is_timeout(e: &std::io::Error) -> bool {
    use std::io::ErrorKind;
    if matches!(
        e.kind(),
        ErrorKind::TimedOut | ErrorKind::WouldBlock | ErrorKind::Interrupted
    ) {
        return true;
    }
    let text = e.to_string().to_ascii_lowercase();
    text.contains("timed out") || text.contains("timeout") || text.contains("would block")
}

/// 后端子进程的句柄与状态。放在 Tauri 的 managed state 里。
pub struct Backend {
    pub state: Arc<Mutex<BackendState>>,
    /// `Option`：`from_failed` 造出来的句柄没有子进程；`shutdown` 取走后也不会再有。
    ///
    /// `Arc` 是为了在 EOF 时能问出真实退出码（`try_wait`）——见 `wait_for_exit_status`。
    /// 读线程**不会**在阻塞的读循环期间持有这把锁（只在自己结束前短暂持有），
    /// 所以窗口退出时 `shutdown` 一定能拿到锁把子进程杀掉，不会留孤儿。
    child: Arc<Mutex<Option<Child>>>,
}

impl Backend {
    /// 拉起后端，并在后台线程里逐行读它的 stdout 找协议行。
    pub fn spawn(java: PathBuf, jar: PathBuf, extra_args: Vec<String>) -> std::io::Result<Self> {
        Self::spawn_with_timeout(java, jar, extra_args, STARTUP_TIMEOUT)
    }

    /// 同 [`Backend::spawn`]，但超时可注入。
    ///
    /// 超时做成参数：否则「超时」这条路径要么测不了（等 30 秒），
    /// 要么只能靠读代码相信它。测不了的分支等于没有。
    ///
    /// **stdout 会同时转发到外壳自己的 stdout**——调试时能在终端看到后端的日志，
    /// 而不会因为「被外壳吃掉了」而什么都看不到。
    pub fn spawn_with_timeout(
        java: PathBuf,
        jar: PathBuf,
        extra_args: Vec<String>,
        timeout: Duration,
    ) -> std::io::Result<Self> {
        let state = Arc::new(Mutex::new(BackendState::starting()));

        let mut cmd = Command::new(&java);
        cmd.arg("-jar")
            .arg(&jar)
            .args(&extra_args)
            .stdout(Stdio::piped())
            // **stderr 必须 null 而不是 inherit。** Java 是控制台程序，而 release
            // 外壳是 GUI 程序、自己没有控制台；inherit 时 Windows 会给子进程
            // （或者它的父进程）配一个可见的 console。用户看到的就是每次启动都弹
            // 一个黑窗口刷 JVM 的 WARNING —— 那看起来像病毒，不像一个应用。
            //
            // 丢掉 stderr 的代价很小：Spring Boot 的日志走 stdout（下面那个读线程
            // 会转发），而 stderr 里通常只有 JVM 的 native-access / Unsafe 警告。
            .stderr(Stdio::null())
            .stdin(Stdio::null());

        // 再钉一次：即使某个 Windows 版本仍然分配了 console，CREATE_NO_WINDOW
        // 也会让它不显示。0x08000000 是 Win32 的 CREATE_NO_WINDOW。
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = cmd.spawn()?;

        let stdout = child.stdout.take().expect("stdout 已设为 piped");
        let child = Arc::new(Mutex::new(Some(child)));

        // 看门狗：读循环会阻塞在 lines() 上，所以超时不能写在循环里。
        // 后端「输出几行日志后挂住」是完全可能的（比如 JVM 卡在启动、或磁盘 IO 停住），
        // 那时循环取不到下一行，写在循环里的超时检查永远执行不到。
        let watchdog_state = Arc::clone(&state);
        std::thread::spawn(move || {
            std::thread::sleep(timeout);
            let mut s = watchdog_state.lock().unwrap();
            // 已经就绪/已经失败都不再改——就绪是单向的，而失败的首因也更有信息量
            if matches!(s.status, BackendStatus::Starting) {
                *s = s.clone().timed_out();
            }
        });

        let reader_state = Arc::clone(&state);
        let reader_child = Arc::clone(&child);
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);

            for line in reader.lines() {
                let Ok(line) = line else { break };
                // 转发给外壳自己的 stdout，方便调试（后端的日志走的就是 stdout）
                println!("{line}");

                if let Some(port) = parse_port_line(&line) {
                    let mut s = reader_state.lock().unwrap();
                    *s = s.clone().with_port(port);
                    return;
                }
            }

            // 走到这里说明 stdout 到 EOF 了：子进程结束了自己的输出（通常就是退出了），
            // 而协议行从未出现。**这是失败，不是「继续等」**——外壳只等前缀会永远等下去。
            //
            // 计划在这里写的是 timed_out()，那是错的诊断：EOF 说的是「进程结束了输出」，
            // 不是「我们等太久了」。两者对用户是不同的下一步动作。
            //
            // 先取退出状态再拿状态锁：`wait_for_exit_status` 可能重试到约 1 秒，
            // 那段时间绝不能占着 `state` 的锁——前端正在轮询 `get_backend_status`。
            let status = wait_for_exit_status(&reader_child);

            let mut s = reader_state.lock().unwrap();
            if matches!(s.status, BackendStatus::Starting) {
                *s = match status {
                    // 拿到退出状态 —— 消息里报的是实测出来的退出码
                    // （Windows 上 code() 总有值；Unix 上被信号杀掉时是 None，
                    //  那时 child_exited(None) 的措辞——「拿不到退出码」——同样成立）
                    Some(st) => s.clone().child_exited(st.code()),
                    // 没拿到（畸形：进程没退却关了 stdout，或系统还没给出状态）——
                    // 这时不能说「被信号终止」，只能说「拿不到退出码」
                    None => s.clone().child_exited(None),
                };
            }
        });

        Ok(Self {
            state,
            child,
        })
    }

    /// 造一个「没有子进程」的后端句柄，只用来承载失败状态。
    ///
    /// **必须用这个构造函数，不要用结构体更新语法**——`child` 字段对 `backend`
    /// 模块之外是私有的，结构体更新会编译不过。
    pub fn from_failed(msg: impl Into<String>) -> Self {
        Self {
            state: Arc::new(Mutex::new(BackendState::failed(msg))),
            child: Arc::new(Mutex::new(None)),
        }
    }

    /// 关闭时调用。
    ///
    /// **这是硬杀（`TerminateProcess`），不是优雅关闭。**
    /// 设计文档的启动时序第 4 步写的是「先通知后台把数据落盘，再退出」——
    /// 那需要一个后端侧的关闭端点（比如 `POST /api/shutdown`），而**后端现在没有**。
    ///
    /// 为什么这一步先用硬杀：SQLite 在 WAL 模式下是**崩溃安全**的——强杀只会留下
    /// `-wal`/`-shm` 两个旁挂文件，下次打开时自动恢复，不会损坏数据库。
    /// 而「优雅关闭」要动后端（本计划的范围是前端与外壳）。
    ///
    /// **这是一条登记在案的缺口，不是忘了**：真正的做法是后端加一个关闭端点
    /// （或监听 stdin 的 EOF），外壳先请求它、等一小会儿、再兜底强杀。
    /// 那件事应该和「数据落盘时机」一起在计划 7（打包与分发）之前解决。
    ///
    /// 返回 `child.wait()` 收到的退出状态：`None` 表示本来就没有子进程或已经被关过。
    /// 这不是为了调用方「处理结果」——关闭时没人能做什么——而是**为了让「真的杀掉了」
    /// 有一个可断言的事实**：本机实测强杀的退出码是 `Some(1)`，自然退出是 `Some(0)`，
    /// 于是「把 `child.kill()` 删掉」这种变异会被测试抓住（见
    /// `shutdown_actually_kills_the_child`）。否则这件事只能靠读代码相信。
    pub fn shutdown(&self) -> Option<ExitStatus> {
        let mut guard = self.child.lock().unwrap();
        let mut child = guard.take()?;
        let _ = child.kill();
        child.wait().ok()
    }
}

/// 非阻塞地问「退了吗、退出码多少」。
///
/// 读到 EOF 的那一刻子进程**通常**已经退出，但系统把它变成可收割状态可能还差一两毫秒，
/// 所以头几次 `try_wait` 返回 `None` 是有可能的。这里有限重试，
/// 上限约 1 秒——再长就是「进程没退却关了 stdout」这种畸形情况，
/// 那时返回 `None` 让调用方如实说「拿不到退出码」，比继续等更有用。
///
/// **不会长时间持锁**：每轮取一次锁、问一次、立刻释放，中间 sleep 时不持锁。
/// 否则 `shutdown()` 会被一个正在等的读线程挡住。
fn wait_for_exit_status(child: &Arc<Mutex<Option<Child>>>) -> Option<ExitStatus> {
    for attempt in 0..=EXIT_STATUS_RETRIES {
        if attempt > 0 {
            std::thread::sleep(EXIT_STATUS_RETRY_INTERVAL);
        }
        // `?`：子进程已被 shutdown() 取走并收割过了，这里问不出什么
        let mut guard = child.lock().unwrap();
        let c = guard.as_mut()?;
        match c.try_wait() {
            Ok(Some(status)) => return Some(status),
            // 还没退：可能是「关了 stdout 但还活着」，也可能是收割状态还没就绪
            Ok(None) => {}
            Err(_) => return None,
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- parse_port_line ----

    #[test]
    fn parses_a_plain_protocol_line() {
        assert_eq!(parse_port_line("CHANGE_PORT=8080"), Some(8080));
    }

    #[test]
    fn tolerates_whitespace_right_after_the_prefix() {
        // 真实后端输出是 `CHANGE_PORT=54791\r\n`，经 line.trim() 后 \r 已经没了，
        // 所以 rest.trim() 对它是冗余的。它实际多容忍的是「前缀后带空格」——
        // 如果后端将来改成 `PREFIX + " " + port` 之类，这一步能救回来。
        assert_eq!(parse_port_line("CHANGE_PORT= 8080"), Some(8080));
        assert_eq!(parse_port_line("CHANGE_PORT=\t8080"), Some(8080));
    }

    #[test]
    fn tolerates_the_windows_carriage_return() {
        // 实测：Windows 上这一行是 19 字节且以 \r\n 结尾；read_line 什么都不剥
        // （\r 与 \n 都留着），所以行尾的 \r 只能靠 line.trim() 处理。
        assert_eq!(parse_port_line("CHANGE_PORT=56934\r"), Some(56934));
    }

    #[test]
    fn tolerates_surrounding_whitespace() {
        assert_eq!(parse_port_line("  CHANGE_PORT=1  \r"), Some(1));
    }

    #[test]
    fn accepts_the_full_port_range() {
        assert_eq!(parse_port_line("CHANGE_PORT=1"), Some(1));
        assert_eq!(parse_port_line("CHANGE_PORT=65535"), Some(65535));
    }

    #[test]
    fn rejects_a_port_above_u16() {
        assert_eq!(parse_port_line("CHANGE_PORT=65536"), None);
    }

    #[test]
    fn rejects_zero_because_os_never_assigns_it() {
        // 后端用 port=0 让操作系统分配，所以它上报的一定是分配后的真实端口，不会是 0。
        assert_eq!(parse_port_line("CHANGE_PORT=0"), None);
    }

    #[test]
    fn rejects_non_protocol_lines() {
        // 后端日志走的是 stdout，外壳会读到几十行 INFO，必须全部忽略。
        assert_eq!(parse_port_line("2026-10-16T12:00:00  INFO ... Started ChangeApplication"), None);
        assert_eq!(parse_port_line(""), None);
        assert_eq!(parse_port_line("CHANGE_PORT"), None);
        assert_eq!(parse_port_line("CHANGE_PORT="), None);
        assert_eq!(parse_port_line("CHANGE_PORT=abc"), None);
        assert_eq!(parse_port_line("CHANGE_PORT=-1"), None);
        assert_eq!(parse_port_line("CHANGE_PORT=80 80"), None);
        assert_eq!(parse_port_line("CHANGE_PORT=80.5"), None);
    }

    #[test]
    fn rejects_a_lookalike_prefix() {
        // 改一个字符就是改协议——这条测试守住前缀本身。
        assert_eq!(parse_port_line("CHANGE_PORTX=8080"), None);
        assert_eq!(parse_port_line("change_port=8080"), None);
    }

    #[test]
    fn every_valid_port_round_trips() {
        // 65535 次迭代，毫秒级。比抽几个点更能守住 u16 的边界。
        for p in 1u16..=u16::MAX {
            assert_eq!(
                parse_port_line(&format!("{PORT_PREFIX}{p}")),
                Some(p),
                "端口 {p} 解析失败"
            );
        }
    }

    #[test]
    fn every_port_above_u16_is_rejected() {
        for p in 65_536u32..=100_000 {
            assert_eq!(
                parse_port_line(&format!("{PORT_PREFIX}{p}")),
                None,
                "{p} 不该被接受"
            );
        }
    }

    // ---- 与真实后端输出的对照（额外要求 C）----

    /// 真实后端 stdout 里的协议行。
    ///
    /// 不是构造的：2026-10-16 用 Python `subprocess`（`stdout=PIPE`）起
    /// `change-backend-0.1.0-SNAPSHOT.jar`，按字节读到的那一行就是
    /// `b'CHANGE_PORT=54791\r\n'`——19 字节，末两字节 `0x0d 0x0a`，耗时 5.70 秒。
    /// 端口是随机的（`server.port=0`），所以下面用 `PORT_PREFIX` 拼接来断言，
    /// 只钉死**行尾形态**与解析结果。
    const REAL_BACKEND_LINE: &[u8] = b"CHANGE_PORT=54791\r\n";

    #[test]
    fn parses_the_real_backend_bytes_when_using_lines() {
        use std::io::{BufRead, BufReader, Cursor};

        // lines() 剥掉 \r\n，调用方拿到干净的 17 字节。
        let first = BufReader::new(Cursor::new(REAL_BACKEND_LINE))
            .lines()
            .next()
            .unwrap()
            .unwrap();
        assert_eq!(first, format!("{PORT_PREFIX}54791"));
        assert_eq!(parse_port_line(&first), Some(54791));
    }

    #[test]
    fn parses_the_real_backend_bytes_when_using_read_line() {
        use std::io::{BufRead, BufReader, Cursor};

        // read_line 什么都不剥：\r 与 \n 都留在字符串里（实测 len=19）。
        // 注意 reader 必须绑定到变量，否则读到的是别的语义。
        let mut reader = BufReader::new(Cursor::new(REAL_BACKEND_LINE));
        let mut line = String::new();
        let n = reader.read_line(&mut line).unwrap();
        assert_eq!(n, 19);
        assert_eq!(line, format!("{PORT_PREFIX}54791\r\n"));
        assert_eq!(line.parse::<u16>().ok(), None); // 不 trim 就解析不出来
        assert_eq!(parse_port_line(&line), Some(54791)); // 函数自己 trim，解析得出
    }

    // ---- resolve_java：随包 > CHANGE_JAVA > JAVA_HOME ----

    #[test]
    fn prefers_the_bundled_runtime_over_everything_else() {
        // 随包存在时**完全不看** CHANGE_JAVA / JAVA_HOME——所以这里让两者都
        // 「存在」，只要结果是随包路径，就证明优先级对。
        let root = Path::new("/install");
        let bundled = bundled_java(root);
        let exists = |p: &Path| {
            p == bundled.as_path()
                || p == Path::new("/custom/bin/java")
                || p == java_in("/jh").as_path()
        };
        assert_eq!(
            resolve_java(Some(root), Some("/custom/bin/java"), Some("/jh"), &exists),
            Ok(bundled.clone())
        );
    }

    #[test]
    fn uses_change_java_when_the_bundled_runtime_is_missing() {
        // 随包路径存在性检查没过就必须往下走。若实现不检查存在性、
        // 直接返回 bundled，这条会拿到一个根本不存在的路径。
        let root = Path::new("/install");
        let exists = |p: &Path| p == Path::new("/custom/bin/java");
        assert_eq!(
            resolve_java(Some(root), Some("/custom/bin/java"), Some("/jh"), &exists),
            Ok(PathBuf::from("/custom/bin/java"))
        );
    }

    #[test]
    fn falls_back_to_java_home_when_bundled_and_change_java_are_missing() {
        // JAVA_HOME 指向 JDK 根目录，java 在 bin/ 下
        let exists = |p: &Path| p == java_in("/jh").as_path();
        assert_eq!(
            resolve_java(None, None, Some("/jh"), &exists),
            Ok(java_in("/jh"))
        );
    }

    #[test]
    fn reports_every_candidate_when_no_java_can_be_found() {
        // 这条是「朋友的机器上出问题」时唯一能救命的信息：错误里必须能看出
        // 我们到底找过哪几个地方。旧实现最后一档是 PATH 上的裸 `java`，
        // 那条路已经去掉（见 `resolve_java` 的文档注释），所以这里必须报错。
        let root = Path::new("/install");
        let err =
            resolve_java(Some(root), Some("/custom/bin/java"), Some("/jh"), &|_| false)
                .unwrap_err();
        for expected in [
            bundled_java(root),
            PathBuf::from("/custom/bin/java"),
            java_in("/jh"),
        ] {
            let needle = expected.display().to_string();
            assert!(
                err.contains(&needle),
                "错误消息里必须列出试过的路径 {needle:?}；实际：{err}"
            );
        }
    }

    #[test]
    fn does_not_panic_when_there_is_no_bundled_root() {
        // 开发时 resource_dir() 未必拿得到；缺随包根只是一个候选缺席，不是 panic。
        let err = resolve_java(None, None, None, &|_| false).unwrap_err();
        assert!(!err.is_empty(), "找不到 Java 必须给出原因");
    }

    #[test]
    fn ignores_an_empty_configured_value() {
        // 环境变量设成空串是常见的（`$env:CHANGE_JAVA = ''`），不该当成配置
        let exists = |p: &Path| p == java_in("/jh").as_path();
        assert_eq!(
            resolve_java(None, Some(""), Some("/jh"), &exists),
            Ok(java_in("/jh"))
        );
    }

    #[test]
    fn ignores_an_empty_java_home() {
        // 与 configured 那条同理：`$env:JAVA_HOME = ''` 是常见的，
        // 空串不该被当成一个有效的 JDK 根目录（那会拼出 `bin/java` 这种相对路径）。
        let configured = PathBuf::from("/custom/bin/java");
        let exists = |p: &Path| p == configured.as_path();
        assert_eq!(
            resolve_java(None, Some("/custom/bin/java"), Some(""), &exists),
            Ok(configured.clone())
        );
    }

    /// 随包 JRE 里 java 的期望路径：`<resource_dir>/runtime/bin/java[.exe]`。
    fn bundled_java(root: &Path) -> PathBuf {
        root.join("runtime").join("bin").join(java_exe_name())
    }

    fn java_exe_name() -> &'static str {
        if cfg!(windows) {
            "java.exe"
        } else {
            "java"
        }
    }

    /// `JAVA_HOME` 下 java 的期望路径。Windows 上带 `.exe`。
    fn java_in(home: &str) -> PathBuf {
        PathBuf::from(home).join("bin").join(java_exe_name())
    }

    // ---- BackendState 的状态机 ----

    use std::path::Path;
    use std::time::{Duration, Instant};

    #[test]
    fn starts_in_the_starting_state() {
        let s = BackendState::starting();
        assert!(matches!(s.status, BackendStatus::Starting));
        assert_eq!(s.port, None);
    }

    #[test]
    fn becomes_ready_when_a_port_arrives() {
        let s = BackendState::starting().with_port(12345);
        assert!(matches!(s.status, BackendStatus::Ready));
        assert_eq!(s.port, Some(12345));
    }

    #[test]
    fn becomes_failed_when_the_child_exits_without_a_protocol_line() {
        // 实测过这个场景：加 --spring.main.web-application-type=none 时，
        // 后端跑完初始化、4.6 秒后 exit 0，且**不打协议行**（只打一行不含前缀的 WARN）。
        // 外壳若只匹配前缀就会永远等下去——所以「子进程退出而没有协议行」必须判失败。
        let s = BackendState::starting().child_exited(Some(0));
        assert!(matches!(s.status, BackendStatus::Failed));
    }

    #[test]
    fn becomes_failed_on_timeout() {
        let s = BackendState::starting().timed_out();
        assert!(matches!(s.status, BackendStatus::Failed));
        assert!(s.message.contains("超时"));
    }

    #[test]
    fn a_ready_state_is_never_overwritten_by_a_later_failure() {
        // 后端起来了之后子进程才崩，不该把已经就绪的状态改回失败——
        // 那时前端已经在用它了。这个守卫让「就绪」是单向的。
        let s = BackendState::starting().with_port(1000).child_exited(Some(1));
        assert!(matches!(s.status, BackendStatus::Ready));
    }

    #[test]
    fn the_timeout_is_long_enough_for_a_cold_start() {
        // 计划 1 实测后端启动 3.8–6.8 秒。超时给 30 秒足够宽松，
        // 但**不能**短到把慢启动误判成失败。
        assert!(STARTUP_TIMEOUT >= Duration::from_secs(15));
    }

    #[test]
    fn a_failed_state_carries_the_reason_it_was_given() {
        // from_failed / spawn 出错分支走的都是这条。它不能是空话。
        let s = BackendState::failed("拉起后端进程失败：系统找不到指定的文件。");
        assert!(matches!(s.status, BackendStatus::Failed));
        assert_eq!(s.port, None);
        assert!(s.message.contains("系统找不到指定的文件"));
    }

    #[test]
    fn a_ready_state_is_never_overwritten_by_a_timeout() {
        // 就绪是单向的：看门狗醒来时若已经 Ready，不许把它改回 Failed。
        // 这正是「端口已经拿到、但看门狗恰好同时醒来」那一刻的竞态。
        let s = BackendState::starting().with_port(1000).timed_out();
        assert!(matches!(s.status, BackendStatus::Ready));
        assert_eq!(s.port, Some(1000));
    }

    #[test]
    fn a_ready_state_is_not_knocked_back_by_a_second_port() {
        // 反复调用 with_port 也不该让状态抖动（状态是幂等的）
        let s = BackendState::starting().with_port(1000).with_port(2000);
        assert!(matches!(s.status, BackendStatus::Ready));
        assert_eq!(s.port, Some(1000), "第一个端口就是那个真的在监听的端口");
    }

    #[test]
    fn the_state_serializes_with_status_as_a_plain_string() {
        // 这条钉死**对前端的线上形状**：`status` 必须是字符串 `"starting"`，
        // 不能是一个对象。计划原文点名警告过 `#[serde(tag = "state")]` 会把
        // 它变成 `{"state":"starting"}`，而前端的 `BackendState.status` 期望的是字符串——
        // 那种改动不会有任何编译错误，只会在运行时让前端判错分支。
        //
        // 顺带钉住 `port`：`None` 也要序列化成 `null` 出现（而不是被 skip 掉），
        // 前端才能用 `status.port === null` 这种直白判断。
        let json = serde_json::to_string(&BackendState::starting()).unwrap();
        assert_eq!(
            json,
            r#"{"status":"starting","port":null,"message":"正在启动后端…"}"#
        );

        let json = serde_json::to_string(&BackendState::starting().with_port(8080)).unwrap();
        assert_eq!(
            json,
            r#"{"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}"#
        );

        // 三个变体的大小写风格都要是 camelCase 的单元字符串
        for (status, want) in [
            (BackendStatus::Starting, "\"starting\""),
            (BackendStatus::Ready, "\"ready\""),
            (BackendStatus::Failed, "\"failed\""),
        ] {
            assert_eq!(serde_json::to_string(&status).unwrap(), want);
        }
    }

    // ---- 真实子进程：状态机绝不能卡在 Starting ----

    /// 轮询到状态不再是 `Starting`，或到 deadline 为止。
    ///
    /// 返回 `(最终状态, 从调用到离开 Starting 的耗时)`。
    fn poll_until_settled(
        backend: &Backend,
        deadline: Duration,
        interval: Duration,
    ) -> (BackendState, std::time::Duration) {
        let start = Instant::now();
        loop {
            let st = backend.state.lock().unwrap().clone();
            if !matches!(st.status, BackendStatus::Starting) {
                return (st, start.elapsed());
            }
            assert!(
                start.elapsed() < deadline,
                "状态卡在 Starting 超过 {deadline:?} —— 正是设计文档点名要避免的失效（永远转圈）"
            );
            std::thread::sleep(interval);
        }
    }

    /// D1 用的子进程：**立刻退出、且不输出协议行**。
    ///
    /// 选 (b) 而不是 (a)：`cmd /c exit <n>` 是操作系统自带的东西，
    /// 不依赖 `JAVA_HOME`、不依赖 PATH 上有 java、不需要下载任何东西。
    /// 用 java 的话，测试在没装 JDK 的机器上要么变红、要么得静默跳过——
    /// 而这条测试守的是「外壳绝不能卡在 Starting」，它不该被环境决定跑不跑。
    ///
    /// 退出码做成参数：这样能证明**真实退出码真的被读出来并写进了消息**，
    /// 否则一个「反正拿不到码，写个泛泛的失败」的实现也能过。
    ///
    /// 非 Windows 上留一条 `sh -c` 的等价物，这样这条测试在别的平台上也一样成立
    /// （本项目目前只在 Windows 上开发）。
    fn immediately_exiting_program(exit_code: i32) -> (PathBuf, Vec<String>) {
        if cfg!(windows) {
            ("cmd".into(), vec!["/c".into(), format!("exit {exit_code}")])
        } else {
            ("sh".into(), vec!["-c".into(), format!("exit {exit_code}")])
        }
    }

    /// D2 用的子进程：**会活着超过给出的超时、且不输出协议行**。
    ///
    /// `ping -n 10 127.0.0.1` 在本机实测约 9 秒（10 次、每次间隔 1 秒），
    /// 远超测试用的 300ms 超时；`>nul` 把它的输出丢掉，stdout 干干净净。
    fn stays_alive_without_protocol_line() -> (PathBuf, Vec<String>) {
        if cfg!(windows) {
            // >nul 是 cmd 的重定向：ping 的输出不会进入我们的 stdout
            (
                "cmd".into(),
                vec!["/c".into(), "ping -n 10 127.0.0.1 >nul".into()],
            )
        } else {
            ("sh".into(), vec!["-c".into(), "sleep 5".into()])
        }
    }

    /// 这些集成测试要真的拉起进程，所以用 `spawn_with_timeout` 而不是 `spawn`。
    fn spawn_program(program: &Path, args: &[String], timeout: Duration) -> Backend {
        // 复用 spawn 的 `-jar` 前缀不方便，这里直接走 Command——但那样就绕开了
        // 被测代码。所以：用 `java` 位传程序名、`jar` 位传第一个参数，其余进 extra_args。
        let (first, rest) = args.split_first().expect("至少一个参数");
        Backend::spawn_with_timeout(
            program.to_path_buf(),
            PathBuf::from(first),
            rest.to_vec(),
            timeout,
        )
        .expect("spawn 本身应当成功（进程能起，只是会立刻退出或不打协议行）")
    }

    #[test]
    fn a_child_that_exits_without_the_protocol_line_ends_in_failed() {
        // `cmd /c exit 0` 立刻退出、stdout 是空的：stdout 立刻 EOF，
        // 而协议行从未出现——状态必须是 Failed，绝不能停在 Starting。
        let (program, args) = immediately_exiting_program(0);
        let b = spawn_program(&program, &args, Duration::from_secs(10));

        let (st, took) = poll_until_settled(&b, Duration::from_secs(10), Duration::from_millis(50));
        assert!(
            matches!(st.status, BackendStatus::Failed),
            "实际: {st:?}（耗时 {took:?}）"
        );
        assert!(!st.message.is_empty(), "失败必须带一句人能读懂的原因");
        assert_eq!(st.port, None);
        // EOF 路径**不能**报成超时：那是误导人的诊断（进程结束了输出 ≠ 我们等太久了）。
        // 这条断言就是 E 表里「EOF 分支改回 timed_out()」那个变异的杀手。
        assert!(
            !st.message.contains("超时"),
            "EOF 不该报成超时，实际: {}",
            st.message
        );
        // 真实退出码必须出现在消息里：这就是 `try_wait()` 那条路真的走通了，
        // 而不是「反正拿不到码，随口说一句 stdout 关了」。
        assert!(
            st.message.contains("退出码 0"),
            "消息里应当出现实测出来的退出码，实际: {}",
            st.message
        );
    }

    #[test]
    fn the_real_exit_code_reaches_the_message_not_a_placeholder() {
        // 换一个非零退出码：如果实现偷懒（永远报 None / 编一个码），这条就会红。
        let (program, args) = immediately_exiting_program(5);
        let b = spawn_program(&program, &args, Duration::from_secs(10));

        let (st, _) = poll_until_settled(&b, Duration::from_secs(10), Duration::from_millis(50));
        assert!(matches!(st.status, BackendStatus::Failed), "实际: {st:?}");
        assert!(
            st.message.contains("退出码 5"),
            "消息里应当出现真正的退出码 5，实际: {}",
            st.message
        );
        assert!(
            !st.message.contains("拿不到退出码"),
            "拿得到的时候不许说自己拿不到，实际: {}",
            st.message
        );
    }

    #[test]
    fn a_child_that_never_prints_the_protocol_line_times_out() {
        // 子进程活着、但一直不打协议行。300ms 超时。
        //
        // 这条测试专门抓「超时检查写在读循环里」那个缺陷：`BufRead::lines()` 是阻塞的，
        // 循环取不到下一行，写在循环里的 `if elapsed > timeout` 永远执行不到。
        // 把看门狗线程删掉，这条必须变红。
        let timeout = Duration::from_millis(300);
        let (program, args) = stays_alive_without_protocol_line();
        let b = spawn_program(&program, &args, timeout);

        let st = b.state.lock().unwrap().clone();
        assert!(
            matches!(st.status, BackendStatus::Starting),
            "spawn 返回时还没到超时，状态应当仍是 Starting，实际: {st:?}"
        );

        let (st, took) = poll_until_settled(&b, Duration::from_secs(10), Duration::from_millis(50));
        assert!(
            matches!(st.status, BackendStatus::Failed),
            "实际: {st:?}（耗时 {took:?}）"
        );
        assert!(st.message.contains("超时"), "实际: {}", st.message);
        assert!(
            took >= timeout,
            "不该在超时之前就判失败：耗时 {took:?} < {timeout:?}"
        );

        // 证明这个子进程确实活着超过那个超时——否则「超时」这条断言可能只是
        // 「进程提前退出了」的巧合。ping -n 10 要跑约 9 秒，300ms 时它一定还在。
        let mut guard = b.child.lock().unwrap();
        let alive = guard
            .as_mut()
            .expect("子进程还没被 shutdown")
            .try_wait()
            .expect("try_wait 不该出错")
            .is_none();
        assert!(alive, "超时发生时子进程应当还活着（否则测的不是超时）");
        drop(guard);

        // 收尾，别把 ping 留在系统里
        b.shutdown();
    }

    #[test]
    fn shutdown_actually_kills_the_child() {
        // 用 `shutdown()` 返回的退出状态把「真的杀掉了」钉住：这个方法唯一的职责
        // 就是杀子进程，而它此前把 `wait()` 的结果丢掉了——于是「删掉 child.kill()」
        // 这件事没有任何测试能发现（E6 变异当时是存活的）。
        //
        // 本机实测（OpenJDK 25.0.2 所在的 Windows 环境，用 `cmd /c ping` 当子进程）：
        //   强杀 → wait() = Ok(ExitStatus(1))，code() = Some(1)
        //   自然退出 → wait() = Ok(ExitStatus(0))，code() = Some(0)
        // 所以「拿到状态」= 杀掉了、「拿不到」= 没杀。
        let (program, args) = stays_alive_without_protocol_line();
        let b = spawn_program(&program, &args, Duration::from_secs(10));
        // 200ms 足够 spawn 落地、又远早于 ping 自己结束（约 9 秒）
        std::thread::sleep(Duration::from_millis(200));
        // 先确认它真的还活着，否则下面那条断言可能只是「它自己退了」
        {
            let mut guard = b.child.lock().unwrap();
            let alive = guard
                .as_mut()
                .expect("还没 shutdown")
                .try_wait()
                .expect("try_wait 不该出错")
                .is_none();
            assert!(alive, "准备阶段子进程就该还活着");
        }

        let status = b.shutdown();
        let status = status.expect(
            "shutdown() 应当返回被收割的子进程状态——返回 None 说明它压根没等子进程，\
             也就是 kill/wait 至少有一处没了",
        );
        // 强杀的退出码：Windows 上是 1。这条断言把「真的杀了」与「自己退了」区分开。
        assert_eq!(
            status.code(),
            Some(1),
            "强杀后拿到的状态应当是异常退出（本机实测 Some(1)），实际 {status:?}"
        );
        assert!(b.shutdown().is_none(), "第二次 shutdown 没有子进程可关");
    }

    // ---- F: 端到端，真的拉起后端 jar ----

    /// 找后端 jar：与 `lib.rs::locate_jar` 同一套候选路径，
    /// 但以 `CARGO_MANIFEST_DIR` 为基准，这样不管从哪个目录跑 cargo test 都对。
    fn find_backend_jar() -> Result<PathBuf, String> {
        if let Ok(p) = std::env::var("CHANGE_BACKEND_JAR") {
            let path = PathBuf::from(&p);
            if path.is_file() {
                return Ok(path);
            }
            return Err(format!("CHANGE_BACKEND_JAR 指向的文件不存在：{p}"));
        }
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("..");
        let candidates = [
            root.join("backend/target/change-backend-0.1.0-SNAPSHOT.jar"),
            PathBuf::from("backend/target/change-backend-0.1.0-SNAPSHOT.jar"),
        ];
        for c in &candidates {
            if c.is_file() {
                return Ok(c.clone());
            }
        }
        Err(format!(
            "找不到后端 jar。请先在 backend/ 下执行 `mvn -B -q -DskipTests package`，\
             或设环境变量 CHANGE_BACKEND_JAR 指向它。（已尝试：{candidates:?}）"
        ))
    }

    fn java_for_e2e() -> PathBuf {
        let from_env = std::env::var("CHANGE_JAVA").ok();
        let mut java_home = std::env::var("JAVA_HOME").ok();
        if java_home.is_none() {
            // 本机 JAVA_HOME 未必设了；用一个已知的 JDK 兜底，让 e2e 能跑起来
            java_home = std::env::var("CHANGE_JAVA_HOME").ok();
        }
        resolve_java(
            None,
            from_env.as_deref(),
            java_home.as_deref(),
            &|p| p.is_file(),
        )
        // e2e 是 #[ignore] 的手动测试；真找不到时仍按旧行为交给 PATH，
        // 让 spawn 自己报错（那比在测试里 panic 一句「找不到 java」更有用）。
        .unwrap_or_else(|_| PathBuf::from("java"))
    }

    /// 真的拉起后端 jar，确认端口协商、端口可访问、关掉后不留孤儿。
    ///
    /// `#[ignore]`：它需要 79 MB 的 jar 与一个 JDK，不该在每次 `cargo test` 时跑。
    /// 跑它：`cargo test -- --ignored --nocapture`
    #[test]
    #[ignore = "需要 backend/target 下的 jar 与一个 JDK；用 cargo test -- --ignored 跑"]
    fn end_to_end_spawns_the_real_backend_and_reports_a_reachable_port() {
        use std::io::{Read, Write};
        use std::net::TcpStream;

        let jar = find_backend_jar().unwrap_or_else(|e| panic!("{e}"));
        let java = java_for_e2e();
        println!("[e2e] java = {}", java.display());
        println!("[e2e] jar  = {}", jar.display());

        // status 是公开字段，可以断言；child 是私有的，这里在模块内所以也能碰。
        let b = Backend::spawn(java, jar, vec![]).expect("后端进程应当能起（java 本身找不到时这里会红）");

        let (st, took) = poll_until_settled(&b, Duration::from_secs(30), Duration::from_millis(50));
        println!("[e2e] spawn → 离开 Starting 用了 {:.2} 秒", took.as_secs_f64());

        assert!(
            matches!(st.status, BackendStatus::Ready),
            "后端没能就绪：{st:?}"
        );
        let port = st.port.expect("Ready 状态必须带端口");
        assert!(port > 0);
        println!("[e2e] 协议行端口 = {port}，message = {}", st.message);

        // 端口必须真的能连上
        let addr = format!("127.0.0.1:{port}");
        let mut stream = TcpStream::connect(&addr)
            .unwrap_or_else(|e| panic!("端口 {port} 连不上：{e}"));
        // 顺手打一个 HTTP 请求，确认那后面真的坐着后端（而不是别的什么东西占了这个端口）
        let req = format!(
            "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"
        );
        stream.write_all(req.as_bytes()).expect("写请求失败");
        let mut response = String::new();
        stream.read_to_string(&mut response).expect("读响应失败");
        let head = response.lines().next().unwrap_or("").to_string();
        println!("[e2e] GET /api/health → {head}");
        assert!(
            head.contains("200"),
            "/api/health 没有回 200：{head}"
        );

        b.shutdown();
        println!("[e2e] shutdown() 已调用");

        // 关掉之后子进程必须真的没了
        let guard = b.child.lock().unwrap();
        assert!(guard.is_none(), "shutdown 之后不该还留着子进程句柄");
        drop(guard);
    }

    /// 真的拉起后端 jar，用**外壳自己那条 HTTP 路径**（`crate::http::get_health`）
    /// 打一次 `/api/health`，并断言解析出来的 `cache` 与计划 1 的格式一致。
    ///
    /// 这条是 Task 8 的核心 e2e：它走的是 `get_health` 会走的全部代码
    /// （真 socket → 真 chunked → 真 JSON → `Health`），所以「外壳代发」这件事
    /// 不只是纯函数层面成立。
    ///
    /// `#[ignore]`：与上面那条同理，它要 79 MB 的 jar 与一个 JDK。
    /// 跑它：`cargo test -- --ignored --nocapture`
    #[test]
    #[ignore = "需要 backend/target 下的 jar 与一个 JDK；用 cargo test -- --ignored 跑"]
    fn end_to_end_reads_a_real_health_response_through_the_shell_http_path() {
        let jar = find_backend_jar().unwrap_or_else(|e| panic!("{e}"));
        let java = java_for_e2e();
        println!("[e2e-health] java = {}", java.display());
        println!("[e2e-health] jar  = {}", jar.display());

        let b = Backend::spawn(java, jar, vec![]).expect("后端进程应当能起");
        let (st, took) = poll_until_settled(&b, Duration::from_secs(30), Duration::from_millis(50));
        println!("[e2e-health] spawn → 离开 Starting 用了 {:.2} 秒", took.as_secs_f64());
        assert!(matches!(st.status, BackendStatus::Ready), "后端没能就绪：{st:?}");
        let port = st.port.expect("Ready 状态必须带端口");

        // 打的这一刻起一个表：这条路径的超时上限是 2 秒，
        // 真的走通要在毫秒级，把实测值打出来供计划文档引用。
        let start = Instant::now();
        let h = crate::http::get_health(port).unwrap_or_else(|e| {
            panic!("外壳代发 GET /api/health 失败：{e}（端口 {port}）")
        });
        println!("[e2e-health] GET /api/health 用了 {:?}", start.elapsed());
        println!("[e2e-health] status = {:?}", h.status);
        println!("[e2e-health] cache  = {:?}", h.cache);

        assert_eq!(h.status, "up", "后端自报的状态");
        // **格式断言**：计划 1 的降级设计规定了缓存文案的两种形态之一。
        // 本机没配 Redis，所以一定是「未启用」那一支；但这里不断言具体哪一种，
        // 而是断言它**必须**是这两种之一——否则缓存那一层的描述漂了。
        assert!(
            h.cache.starts_with("缓存："),
            "缓存文案该以「缓存：」开头，实际 {:?}",
            h.cache
        );
        assert!(
            h.cache.contains("未启用") || h.cache.contains("已连接") || h.cache.contains("熔断"),
            "缓存文案不在计划 1 规定的形态里，实际 {:?}",
            h.cache
        );

        b.shutdown();
        println!("[e2e-health] shutdown() 已调用");
    }

    /// 真的拉起后端 jar，用**外壳的通用代理**（`crate::http::api_request`）走一遍，
    /// 核对 200 / 204 / 400 三种形态在**真实链路**上成立。
    ///
    /// # 为什么必须有这一条（而不是只靠 `http.rs` 的纯函数测试）
    ///
    /// `http.rs` 的测试喂的是**抓下来的字节**——它们证明「给对了字节就能解析对」，
    /// 但证明不了「我们拼出去的请求，真实后端愿意按我们期望的方式回答」。
    /// 这一条补的就是那一截：真 socket、真 Tomcat、真 chunked、真 JSON。
    ///
    /// # 为什么只读不写
    ///
    /// `Backend::spawn` 不设 `CHANGE_DATA_DIR`，所以后端会用**使用者真实的库**。
    /// 这一条里刻意**不做成功的写入**：它会把一次训练留在别人的数据里。
    /// 写入路径中属于外壳的那部分（POST + `Content-Length` + body）由下面那个
    /// **400** 覆盖——Spring 必须真的把请求体读进来解析过，才可能回
    /// 「一次训练至少要有 1 个动作」；一次成功的写入多出来的是后端自己的事务行为，
    /// 那由计划 3 的端到端验收覆盖。
    ///
    /// `#[ignore]`：它要 79 MB 的 jar 与一个 JDK。
    /// 跑它：`cargo test end_to_end_proxy -- --ignored --nocapture`
    #[test]
    #[ignore = "需要 backend/target 下的 jar 与一个 JDK；用 cargo test -- --ignored 跑"]
    fn end_to_end_proxy_reads_and_reports_statuses_from_the_real_backend() {
        let jar = find_backend_jar().unwrap_or_else(|e| panic!("{e}"));
        let java = java_for_e2e();
        println!("[e2e-proxy] java = {}", java.display());
        println!("[e2e-proxy] jar  = {}", jar.display());

        let b = Backend::spawn(java, jar, vec![]).expect("后端进程应当能起");
        let (st, took) = poll_until_settled(&b, Duration::from_secs(30), Duration::from_millis(50));
        println!("[e2e-proxy] spawn → 就绪用了 {:.2} 秒", took.as_secs_f64());
        assert!(matches!(st.status, BackendStatus::Ready), "后端没能就绪：{st:?}");
        let port = st.port.expect("Ready 状态必须带端口");

        // (1) 200 + chunked + 中文 JSON：读路径
        let start = Instant::now();
        let weekly = crate::http::api_request(port, "GET", "/api/volume/weekly", None)
            .unwrap_or_else(|e| panic!("GET /api/volume/weekly 失败：{e}"));
        println!("[e2e-proxy] weekly 用了 {:?}，status={}", start.elapsed(), weekly.status);
        assert_eq!(weekly.status, 200);
        let v: serde_json::Value = serde_json::from_str(&weekly.body).expect("正文该是 JSON");
        assert_eq!(
            v["muscles"].as_array().expect("muscles 该是数组").len(),
            17,
            "17 块肌群"
        );
        assert_eq!(v["muscles"][0]["nameZh"], "胸部");

        // (2) **查询串**要能原样穿过代理（中文已百分号编码，不含空格所以过得了
        //     `validate_path`）。这一条是「选择器」那条路的真实证据。
        let search = crate::http::api_request(
            port,
            "GET",
            "/api/exercises?q=%E5%8D%A7%E6%8E%A8&limit=1",
            None,
        )
        .unwrap_or_else(|e| panic!("GET /api/exercises 失败：{e}"));
        assert_eq!(search.status, 200);
        let rows: serde_json::Value = serde_json::from_str(&search.body).unwrap();
        println!("[e2e-proxy] 搜索命中的第一条 = {}", rows[0]["nameEn"]);
        assert!(rows.as_array().unwrap().len() <= 1, "limit=1 应当生效");

        // (3) 204：一个不存在的动作 → 没有正文，且**不是**错误
        let never = crate::http::api_request(port, "GET", "/api/exercises/999999/last-performance", None)
            .unwrap_or_else(|e| panic!("last-performance 失败：{e}"));
        assert_eq!(never.status, 204);
        assert_eq!(never.body, "", "204 的正文必须是空的（不是 '0' 也不是 '[]'）");

        // (4) 400：非法请求体 → 状态码与后端的校验文案都要原样带回来
        let bad = crate::http::api_request(
            port,
            "POST",
            "/api/sessions",
            Some(r#"{"sessionDate":"2026-10-19","exercises":[]}"#),
        )
        .unwrap_or_else(|e| panic!("POST /api/sessions 失败：{e}"));
        println!("[e2e-proxy] 400 正文 = {}", bad.body);
        assert_eq!(bad.status, 400);
        assert!(
            bad.body.contains("error") && bad.body.contains("动作"),
            "400 的正文该带着后端那句校验文案，实际 {}",
            bad.body
        );

        // (5) 一个不存在的路径 → 404 也是 Ok（不是 Err），且正文得是**我们自己那句话**
        //
        // 这一条是真机跑出来的缺口：原来这里只能拿到 Spring 的默认错误体
        // `{"timestamp":…,"error":"Not Found","path":…}`——`TrainingController` reason 里那句
        // 中文根本不出现，前端界面上只有英文的 `Not Found`。ApiErrors 补了
        // ResponseStatusException 的 handler 之后，正文才变成 `{"error":"找不到这次训练: …"}`。
        let missing = crate::http::api_request(port, "GET", "/api/sessions/999999", None)
            .unwrap_or_else(|e| panic!("不存在的训练应当回 404 而不是传输失败：{e}"));
        assert_eq!(missing.status, 404);
        println!("[e2e-proxy] 404 正文 = {}", missing.body);
        assert!(
            missing.body.contains("找不到这次训练"),
            "404 的正文该带我们自己那句话（不是 Spring 默认的 Not Found），实际 {}",
            missing.body
        );
        assert!(
            !missing.body.contains("timestamp"),
            "404 的正文不该是 Spring 的默认错误体（那说明 ApiErrors 那个 handler 没生效），实际 {}",
            missing.body
        );

        b.shutdown();
        println!("[e2e-proxy] shutdown() 已调用");
    }
}
