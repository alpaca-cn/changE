//! 后端子进程的拉起与端口协商。
//!
//! 协议（由计划 1 的 `PortReporter` 定义，改动等于改协议）：
//! 后端把一行 `CHANGE_PORT=<端口>` 打到**标准输出**，外壳读它来定位后端。
//!
//! 这里刻意不依赖 Tauri 运行时——端口解析是本计划唯一有真实逻辑的地方，
//! 抽成纯函数才能被 `cargo test` 直接覆盖（起 Tauri 运行时的测试跑不动 CI）。

use std::path::PathBuf;

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

/// 解析 Java 可执行文件的位置。
///
/// 顺序：显式配置 → `JAVA_HOME` → PATH 上的 `java`。
///
/// **返回 `PathBuf` 而不是 `Option`**：最后那一档（交给 PATH）永远不会失败——
/// 真找不到的话，是 `Command::spawn` 抛出的 OS 错误，而且那条消息比我们在这里
/// 猜的任何话都准。返回 `Option` 只会制造一个「找不到」的假分支。
///
/// **不做 `is_file()` 检查**：`JAVA_HOME` 指向一个 JDK 是很强的信号，而且
/// 检查存在性会让这个函数**没法用假路径测试**（测试里那个路径当然不存在）。
///
/// 参数是显式传入的，方便测试——不要在函数里直接读 `std::env`。
pub fn resolve_java(configured: Option<&str>, java_home: Option<&str>) -> PathBuf {
    if let Some(p) = configured {
        if !p.is_empty() {
            return PathBuf::from(p);
        }
    }
    if let Some(home) = java_home {
        if !home.is_empty() {
            let exe = if cfg!(windows) { "java.exe" } else { "java" };
            return PathBuf::from(home).join("bin").join(exe);
        }
    }
    // 最后一档：交给 PATH。真找不到的话，spawn 会抛出比我们猜的更准的 OS 错误。
    PathBuf::from("java")
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
    fn tolerates_the_windows_carriage_return() {
        // 实测：Windows 上 println 写出 \r\n，read_line 只剥 \n，留下 \r。
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

    // ---- resolve_java ----

    #[test]
    fn prefers_the_configured_java() {
        assert_eq!(
            resolve_java(Some("/custom/bin/java"), Some("/jh")),
            PathBuf::from("/custom/bin/java")
        );
    }

    #[test]
    fn ignores_an_empty_configured_value() {
        // 环境变量设成空串是常见的（`$env:CHANGE_JAVA = ''`），不该当成配置
        assert_eq!(resolve_java(Some(""), Some("/jh")), java_in("/jh"));
    }

    #[test]
    fn falls_back_to_java_home() {
        // JAVA_HOME 指向 JDK 根目录，java 在 bin/ 下
        assert_eq!(resolve_java(None, Some("/jh")), java_in("/jh"));
    }

    #[test]
    fn falls_back_to_java_on_path() {
        // 最后一档：交给操作系统去找。刻意不做 is_file 检查——
        // 那会让上面两条用假路径的测试没法写。
        assert_eq!(resolve_java(None, None), PathBuf::from("java"));
    }

    /// `JAVA_HOME` 下 java 的期望路径。Windows 上带 `.exe`。
    fn java_in(home: &str) -> PathBuf {
        let exe = if cfg!(windows) { "java.exe" } else { "java" };
        PathBuf::from(home).join("bin").join(exe)
    }
}
