//! `get_health` 的**真 socket** 测试。
//!
//! 纯解析逻辑（状态行、chunked、畸形 JSON…）的测试在 `src/http.rs` 里，与实现
//! 同一个文件——那里的私有用具（`chunked_decode`、`split_response`）在集成测试里
//! 是看不见的。这个文件只测**能从一个 socket 真的跑完一整轮**的那条路径，
//! 以及两类只有在真 socket 上才存在的失败：**超时**与**连接被拒**。
//!
//! 放在 `tests/` 而不是 `src/` 是有意的：它只能碰 `change_lib` 的公开 API，
//! 所以它同时是「`get_health` 这条路对外可用」的证据。

use change_lib::http::{get_health, HEALTH_TIMEOUT};

/// 一个**接受连接但永不回包**的假后端。
///
/// 这是唯一能测到「超时」这条路径的办法：连一个没人监听的端口拿到的是
/// 「connection refused」（另一条测试），**不是超时**。要造超时就必须有一个
/// 真的接受连接、把请求读走、之后什么都不发的服务端。
struct SilentServer {
    port: u16,
    stop: std::sync::mpsc::Sender<()>,
    handle: Option<std::thread::JoinHandle<()>>,
}

impl SilentServer {
    fn start() -> Self {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("绑定一个临时端口");
        let port = listener.local_addr().expect("读取本地地址").port();
        let (stop, stop_rx) = std::sync::mpsc::channel::<()>();
        let handle = std::thread::spawn(move || {
            let Ok((mut s, _)) = listener.accept() else {
                return;
            };
            // 把请求读掉（清空内核缓冲区），然后**什么都不发**
            let mut buf = [0u8; 1024];
            let _ = std::io::Read::read(&mut s, &mut buf);
            // 连接保持打开，直到测试喊停：不发字节 = 「活着但不回话」。
            // 上限给得比 HEALTH_TIMEOUT 宽得多，这样被测代码一定是先超时的那一方。
            let _ = stop_rx.recv_timeout(HEALTH_TIMEOUT * 4);
            drop(s);
        });
        Self {
            port,
            stop,
            handle: Some(handle),
        }
    }
}

impl Drop for SilentServer {
    fn drop(&mut self) {
        // 先喊停（`recv_timeout` 会立刻返回），再 join。
        // **join 不能省**：线程还在挂着的时候测试就结束的话，
        // 它可能和下一个测试的临时端口撞上，制造偶发红。
        let _ = self.stop.send(());
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

#[test]
fn get_health_times_out_instead_of_hanging() {
    // **这是「去掉超时」那个变异的杀手。**
    //
    // 后端活着但不回话时（线程池打满、GC 停顿、socket 卡住），
    // `set_read_timeout` / `set_write_timeout` 那两行如果没有，`read_to_end`
    // 会一直阻塞在 recv 上——前端的 5 秒轮询会一个个堆起来，永远不返回。
    //
    // 变异实测（2026-10-16，`_healthcheck/m1_probe.py` 把两行超时整段删掉再跑本文件）：
    // `FAILED ... finished in 10.10s`，**是一条正常失败，不是挂死**。
    // 原因是这个假服务自己在 `HEALTH_TIMEOUT * 4`（8 秒）之后关掉了连接，
    // 于是阻塞的 recv 拿到 EOF、`read_to_end` 返回了一条空响应，
    // 「应当在上限附近放弃（< 6 秒）」与「消息该说后端没回话」两条断言同时红。
    // 也就是说：**这个变异确实被杀，但杀它的不是「挂死」而是这两条断言。**
    // 想让它真的挂死，假服务必须永不关连接——那会把整个测试套件拖成偶发挂起，
    // 代价大于收益，所以这里刻意选了「有界地失败」。
    let server = SilentServer::start();
    let start = std::time::Instant::now();
    let err = get_health(server.port).expect_err("假服务不回包，必须失败");
    let took = start.elapsed();

    assert!(
        err.contains("没有回话"),
        "超时的消息该说的是「后端没回话」，而不是一句泛泛的 IO 错误：{err}"
    );
    assert!(
        took < HEALTH_TIMEOUT * 3,
        "应当在上限附近就放弃（{HEALTH_TIMEOUT:?}），实际用了 {took:?}"
    );
    assert!(
        took >= HEALTH_TIMEOUT,
        "不该比上限还早就放弃——那说明放弃的理由不是超时，实际 {took:?}"
    );
}

#[test]
fn get_health_reports_connection_refused_when_nothing_is_listening() {
    // 关掉的端口：`connect` 立刻被拒。这条与超时**必须报不同的话**——
    // 「连不上」（后端还没起来，可以重试）与「不回话」（卡住了，下一步不同）
    // 是两个不同的事实。
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener); // 端口现在是空的

    let err = get_health(port).expect_err("没人监听时必须失败");
    assert!(
        err.contains("连不上"),
        "该报「连不上」而不是超时或别的：{err}"
    );
    assert!(
        !err.contains("没有回话"),
        "连不上 ≠ 不回话，不许混：{err}"
    );
}

#[test]
fn get_health_reads_a_real_http_response_from_a_local_socket() {
    // 这条走的是**完整的 socket 路径**（connect → 写请求 → 读整条响应 → 解析），
    // 与上面两条失败路径合起来把 `get_health` 整个函数覆盖住，
    // 而不只是它调用的那些纯函数。
    use std::io::{Read as _, Write as _};

    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        // 把请求读出来并交回去断言形状（否则「测试过了」可能只是服务端
        // 随便发了点东西，而请求压根没发对）
        let mut buf = [0u8; 512];
        let n = s.read(&mut buf).unwrap();
        let req = String::from_utf8_lossy(&buf[..n]).to_string();
        let body = "{\"cache\":\"缓存：未启用（配置为关闭）\",\"status\":\"up\"}";
        let resp = format!(
            "HTTP/1.1 200 \r\nContent-Type: application/json\r\n\
             Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n\
             {:x}\r\n{}\r\n0\r\n\r\n",
            body.len(),
            body
        );
        s.write_all(resp.as_bytes()).unwrap();
        s.flush().unwrap();
        req
    });

    let h = get_health(port).expect("本地假服务应当被正确解析");
    let req = server.join().unwrap();
    assert_eq!(h.status, "up");
    assert_eq!(h.cache, "缓存：未启用（配置为关闭）");
    assert!(
        req.starts_with("GET /api/health HTTP/1.1\r\n"),
        "实际请求：{req:?}"
    );
    assert!(
        req.contains(&format!("Host: 127.0.0.1:{port}\r\n")),
        "实际请求：{req:?}"
    );
}

#[test]
fn get_health_rejects_a_500_from_a_real_socket() {
    // 非 2xx 的**语义**（旧版前端 `if (!res.ok) throw` 那条）在新位置上
    // 也要能从真 socket 上走通——不是只有纯函数层面成立。
    use std::io::{Read as _, Write as _};

    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        let mut buf = [0u8; 512];
        let _ = s.read(&mut buf).unwrap();
        // Content-Length 恰好覆盖 `{}` 两个字节，多出来的 `#` 是刻意的噪声
        s.write_all(
            b"HTTP/1.1 500 \r\nContent-Type: application/json\r\n\
              Content-Length: 2\r\nConnection: close\r\n\r\n{}#",
        )
        .unwrap();
        s.flush().unwrap();
    });

    let err = get_health(port).expect_err("500 必须失败");
    server.join().unwrap();
    assert!(err.contains("500"), "消息里应当有状态码：{err}");
    assert!(err.contains("200"), "消息该说明期望值：{err}");
}
