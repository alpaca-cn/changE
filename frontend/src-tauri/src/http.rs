//! 向外壳自己拉起的后端发一条 GET，把响应读成一个 `Health`。
//!
//! # 为什么健康检查要由外壳代发，而不是前端 `fetch`
//!
//! 页面源（dev 是 `http://127.0.0.1:1420`，打包后是 `tauri://localhost`）与后端
//! 的**随机端口**不同源，响应里没有 `Access-Control-Allow-Origin`，浏览器会把响应
//! 拦掉，前端只看到 `TypeError: Failed to fetch`。
//!
//! CSP 与 CORS 是**两道独立且都要过的门**：CSP 管「页面能不能发这个请求」，
//! CORS 管「浏览器肯不肯把响应交给脚本」。计划当初给 `connect-src` 放行了
//! `http://127.0.0.1:*`，只过了第一道。
//!
//! 修法是**不给后端加 CORS**，改由外壳（Rust）发这条请求——它不经浏览器，
//! 天生不在同源策略的管辖范围内。理由：
//!
//! 1. `tauri://localhost` 是不透明源，自定义 scheme 的 CORS 处理很脏；
//! 2. 加了 CORS 就等于**任何网页都能读** `127.0.0.1:<随机端口>` 的响应；
//! 3. 前端不再自己发 HTTP 之后，CSP 里那条 `http://127.0.0.1:*` 可以收紧掉。
//!
//! # 为什么手写 TCP 而不是 `ureq` / `reqwest`
//!
//! 这条链路要读的东西只有一种，而且形态已经被实测钉死了（2026-10-16，见
//! `chunked_decoding_is_required_by_the_real_backend`）：本机 Spring Boot 10.1.55
//! 用 HTTP/1.1 回 `/api/health` 时**总是 `Transfer-Encoding: chunked`**，
//! 用 HTTP/1.0 回同样一个 JSON 时是「读到 EOF 为止」。所以需要的全部能力就是
//! 「一个请求 + 两种定长规则 + 一次 JSON 解析」，代价是下面这几十行代码 + 单元测试，
//! 换来**零新依赖**（`Cargo.lock` 一个 crate 都不增加、二进制不变大、SBOM 更干净）。
//!
//! `reqwest` 会为「读一个本机 JSON」拉进 tokio；`ureq` 轻得多，但仍要拉进一整套
//! 传输栈（DNS、TLS、gzip…）——而这里既不需要 HTTPS，也没有 DNS。
//! 实测的依赖代价记在计划文档 Task 8 的「A. 代发的代价」一节。

use crate::backend::{Health, HealthError};

/// 写请求 + 读整条响应的时间上限。
///
/// **必须有**：后端活着但不回包时（比如线程池打满、GC 停顿、socket 卡住），
/// `get_health` 不能挂住——前端的稳态轮询是 5 秒一次，挂住就会把请求堆起来。
///
/// 2 秒的选法：本机实测一次完整的请求-响应在**毫秒**量级（后端冷启动时也不过
/// 几十毫秒），2 秒留了三个数量级的余量；而它远小于前端 5 秒的轮询周期，
/// 所以最坏情况下也不会堆积超过一个在途请求。
pub const HEALTH_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(2);

/// 单条响应的字节上限。畸形或恶意响应不该把外壳的内存吃掉。
pub const MAX_RESPONSE_BYTES: usize = 1024 * 1024;

/// `/api/health` 的请求行与头。
///
/// `Connection: close` 是刻意的：
/// - 读循环靠「读到 EOF」收尾，留着长连接就会卡到超时；
/// - 后端看到它会回 `Connection: close` 并真的关连接（实测）。
///
/// 用 `HTTP/1.1` 而不是 `HTTP/1.0`：1.0 那套「读到 EOF 为止」的规则虽然更省事，
/// 但它会让这条请求与真实浏览器发的形状不一致；而 1.1 的 chunked 已经实测清楚
/// 并且有解码器与测试，没有必要为了省十行代码去用一个更古老的协议。
pub fn health_request(port: u16) -> String {
    format!(
        "GET /api/health HTTP/1.1\r\n\
         Host: 127.0.0.1:{port}\r\n\
         Accept: application/json\r\n\
         Connection: close\r\n\r\n"
    )
}

/// 向 `127.0.0.1:<port>` 的 `/api/health` 发一条 GET，解析成 [`Health`]。
///
/// 返回 `Err` 时消息一律是给用户看的中文，且**每条原因各不相同**——
/// 前端把它原样渲染到状态栏右侧，所以「连不上」「超时」「非 2xx」「JSON 畸形」
/// 必须能区分开，否则用户没有任何可行动的线索。
pub fn get_health(port: u16) -> Result<Health, String> {
    let addr = format!("127.0.0.1:{port}");
    let connect_err = |e: std::io::Error| {
        HealthError::Connect {
            addr: addr.clone(),
            source: e,
        }
        .into_message()
    };
    let read_err = |e: std::io::Error| {
        HealthError::Read {
            addr: addr.clone(),
            source: e,
        }
        .into_message()
    };

    let mut stream = std::net::TcpStream::connect(&addr).map_err(connect_err)?;
    // 读与写都要有超时：只设一个的话，另一个方向照样能挂满 `HEALTH_TIMEOUT` 的十倍
    stream.set_read_timeout(Some(HEALTH_TIMEOUT)).map_err(read_err)?;
    stream.set_write_timeout(Some(HEALTH_TIMEOUT)).map_err(read_err)?;

    {
        use std::io::Write;
        stream
            .write_all(health_request(port).as_bytes())
            .map_err(read_err)?;
        stream.flush().map_err(read_err)?;
    }

    let raw = read_to_end(&mut stream, MAX_RESPONSE_BYTES).map_err(read_err)?;
    parse_health_response(&raw).map_err(HealthError::into_message)
}

/// 读到 EOF 或字节上限为止。
///
/// 单独抽出来的原因：它是**唯一**接触 socket 的读路径，而「读到上限要停手」
/// 这条判断可以脱离网络被测试覆盖（见 `read_to_end_stops_at_the_byte_cap`）。
pub fn read_to_end<R: std::io::Read>(r: &mut R, cap: usize) -> std::io::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut buf = [0u8; 8 * 1024];
    loop {
        let n = r.read(&mut buf)?;
        if n == 0 {
            return Ok(out);
        }
        if out.len() + n > cap {
            out.extend_from_slice(&buf[..cap - out.len()]);
            return Ok(out);
        }
        out.extend_from_slice(&buf[..n]);
    }
}

/// **纯函数**：一条完整的 HTTP 响应字节 → [`Health`]。
///
/// 不碰网络、不碰时钟，所以「非 2xx」「chunked」「畸形 JSON」「空 body」
/// 这些分支全都能用一条字符串直接测到——包括那些很难真的从后端制造出来的。
///
/// 返回 [`HealthError`] 而不是 `String`：三条不同的原因要能各报各的话，
/// 而调用方（`get_health`）在边界上才把它转成人看的消息。测试也因此能断言
/// **是哪一类**错误，而不是去匹配一段文案。
pub fn parse_health_response(raw: &[u8]) -> Result<Health, HealthError> {
    let (status_line, headers, body) = split_response(raw)?;
    let code = parse_status_code(status_line)?;
    if code != 200 {
        return Err(HealthError::NotOk { code });
    }
    let body = if is_chunked(headers) {
        chunked_decode(body)?
    } else {
        body.to_vec()
    };
    parse_health_json(&body)
}

/// 把一条响应切成（状态行, 头, body 原始字节）。
///
/// 头与 body 的分界是**第一个空行**——HTTP 规则如此，但它值得被显式检查：
/// 少一个空行就会把整条响应当成状态行，报出来的错会离真相很远。
fn split_response(raw: &[u8]) -> Result<(&str, &str, &[u8]), HealthError> {
    // 状态行与头都是 ASCII；body 才是可能有中文的地方，所以只在这里转一次
    let malformed = |detail: &str| HealthError::Malformed {
        detail: detail.to_string(),
    };
    let Some(pos) = find_subslice(raw, b"\r\n\r\n") else {
        return Err(malformed(
            "响应里没有头部与正文之间的空行（\\r\\n\\r\\n），可能不是 HTTP 响应",
        ));
    };
    let head = std::str::from_utf8(&raw[..pos])
        .map_err(|_| malformed("响应头不是合法的 UTF-8/ASCII"))?;
    let (status_line, headers) = match head.split_once("\r\n") {
        Some((s, h)) => (s, h),
        None => (head, ""),
    };
    Ok((status_line, headers, &raw[pos + 4..]))
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack.windows(needle.len()).position(|w| w == needle)
}

/// `HTTP/1.1 200 ` → `200`。
///
/// 单独成函数是为了让「状态行不认识」与「状态码不是 200」能报出**不同**的话。
fn parse_status_code(status_line: &str) -> Result<u16, HealthError> {
    let malformed = |detail: String| HealthError::Malformed { detail };
    let mut parts = status_line.split(' ');
    let version = parts.next().unwrap_or("");
    if !version.starts_with("HTTP/") {
        return Err(malformed(format!(
            "状态行不以 HTTP/ 开头：{status_line:?}"
        )));
    }
    let code = parts.next().unwrap_or("");
    code.parse::<u16>()
        .map_err(|_| malformed(format!("状态行里的状态码不是数字：{status_line:?}")))
}

/// 头里是否有 `Transfer-Encoding: chunked`（大小写不敏感，按 token 拆）。
fn is_chunked(headers: &str) -> bool {
    for line in headers.split("\r\n") {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        if !name.trim().eq_ignore_ascii_case("transfer-encoding") {
            continue;
        }
        if value
            .split(',')
            .any(|t| t.trim().eq_ignore_ascii_case("chunked"))
        {
            return true;
        }
    }
    false
}

/// chunked 传输编码的解码：`<十六进制长度>\r\n<数据>\r\n`…`0\r\n\r\n`。
///
/// **必须在**：本机实测后端对 HTTP/1.1 的 `/api/health` 回的就是 chunked
/// （`41\r\n{...json...}\r\n0\r\n\r\n`），不做这一步解析出来的 body 会带着
/// 长度前缀和结尾的 `0`，`serde_json` 直接报错。
///
/// 容忍裸 `\n` 作为行尾（RFC 允许，且成本是零），但**不允许**块数据后缺少行尾——
/// 那说明字节流对不上，继续猜下去只会把损坏的数据当成功。
fn chunked_decode(body: &[u8]) -> Result<Vec<u8>, HealthError> {
    let malformed = |detail: String| HealthError::Malformed { detail };
    let mut out = Vec::new();
    let mut pos = 0usize;
    loop {
        let Some((line_end, next)) = read_line(body, pos) else {
            return Err(malformed("chunked 正文在块长度那一行就断了".to_string()));
        };
        let line = std::str::from_utf8(&body[pos..line_end])
            .map_err(|_| malformed("chunked 的块长度行不是 ASCII".to_string()))?;
        // 长度后面可以跟 `;ext=...` 之类的扩展，取分号前的部分
        let size_text = line.split(';').next().unwrap_or("").trim();
        let size = usize::from_str_radix(size_text, 16)
            .map_err(|_| malformed(format!("chunked 的块长度不是十六进制数：{size_text:?}")))?;
        pos = next;

        if size == 0 {
            // 结束块。后面可能还有 trailer 头，再到一个空行为止；这里不解析 trailer。
            return Ok(out);
        }
        if size > MAX_RESPONSE_BYTES {
            return Err(malformed(format!(
                "chunked 的块长度 {size} 超过上限 {MAX_RESPONSE_BYTES}"
            )));
        }
        if pos + size > body.len() {
            return Err(malformed(format!(
                "chunked 声称有 {size} 字节，但正文只剩 {} 字节",
                body.len() - pos
            )));
        }
        out.extend_from_slice(&body[pos..pos + size]);
        pos += size;
        // 块数据后面的那个行尾（+1 容忍裸 \n 少一个 \r 的情况）
        match body.get(pos) {
            Some(b'\n') => pos += 1,
            Some(b'\r') if body.get(pos + 1) == Some(&b'\n') => pos += 2,
            _ => return Err(malformed("chunked 的块数据后面缺少行尾".to_string())),
        }
    }
}

/// 从 `pos` 起读一行，返回 `(行内容结束位置, 下一行起始位置)`。行尾可以是 `\r\n` 或 `\n`。
fn read_line(buf: &[u8], pos: usize) -> Option<(usize, usize)> {
    // `.filter(..)` 不是多余的：块流正常结束时 `pos == buf.len()`，
    // 而空切片上的 `position` 照样会返回 `None`——两条路径的返回值一样，
    // 但语义不同，显式排掉空切片后「没有了」就一定等于「断了」。
    let rest = buf.get(pos..).filter(|s| !s.is_empty())?;
    let nl = rest.iter().position(|b| *b == b'\n')? + pos;
    let end = if nl > pos && buf[nl - 1] == b'\r' {
        nl - 1
    } else {
        nl
    };
    Some((end, nl + 1))
}

/// body 字节 → [`Health`]，并钉住**两个字段都在且都是字符串**。
///
/// 字段名与前端 `Health` interface 是**两处手写定义**，可以漂移而没有任何东西
/// 会报警（TS 不校验线上来的 JSON）。把 `cache` 拼成 `cach` 就会静默变成空文案，
/// 所以这里宁可对畸形 JSON 报错，也不要 `#[serde(default)]` 吞掉缺失字段。
fn parse_health_json(body: &[u8]) -> Result<Health, HealthError> {
    if body.is_empty() {
        return Err(HealthError::Malformed {
            detail: "正文是空的，期望一个 JSON 对象".to_string(),
        });
    }
    serde_json::from_slice::<Health>(body).map_err(|e| HealthError::Malformed {
        detail: format!("正文不是合法的 Health JSON：{e}"),
    })
}

// ---------------------------------------------------------------------------
// 通用代理：前端所有后端访问都经这里（见模块注释与计划 4 的第一节）。
// ---------------------------------------------------------------------------

/// 通用代理的超时。
///
/// **不能用 [`HEALTH_TIMEOUT`]**（2 秒）：写一次训练要落库，2 秒太紧。
/// 10 秒仍远小于用户会以为「卡死」的时间，也与前端一次操作的等待上限相称。
pub const API_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);

/// 调用方可以请求的最长超时。
///
/// 智能体的 `chat` 在内部可能要跑好几轮「模型 → 工具 → 模型」，
/// 实测一次带工具的中等任务在 10-40 秒。180 秒给了足够余量，
/// 同时仍然是有限的——无限等待会把「服务挂了」变成「界面卡了」。
pub const MAX_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(180);

/// 调用方可以请求的最短超时。低于它没有实际意义，只会立刻失败。
pub const MIN_TIMEOUT: std::time::Duration = std::time::Duration::from_millis(100);

/// 把前端传来的毫秒数夹到 `[MIN_TIMEOUT, MAX_TIMEOUT]` 里。
pub fn clamp_timeout(millis: u64) -> std::time::Duration {
    let d = std::time::Duration::from_millis(millis);
    if d < MIN_TIMEOUT {
        MIN_TIMEOUT
    } else if d > MAX_TIMEOUT {
        MAX_TIMEOUT
    } else {
        d
    }
}

/// 一条**已完成**的 HTTP 响应。
///
/// `status` 是服务端的真实状态码。**400 / 404 也是 `Ok`**——前端要根据状态码
/// 分流（400 显示服务端的校验文案、404 显示「找不到」、204 显示「没做过」），
/// 把状态码压成一句错误消息，这三条就再也分不开了。
///
/// `Err` 只表示**传输层**失败：连不上 / 超时 / 响应读不懂。
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct ApiResponse {
    pub status: u16,
    /// 原始响应文本。204 时是空串。Rust 侧不解析业务 JSON——那是前端的事。
    pub body: String,
}

/// 拼一条请求。`path` 会先被 [`validate_path`] 校验。
///
/// 单独成函数是为了让「请求长什么样」能被纯函数测试直接钉住（尤其是
/// `Content-Length` 与那几行头），不用真的开 socket。
pub fn build_request(
    port: u16,
    method: &str,
    path: &str,
    body: Option<&str>,
) -> Result<String, String> {
    if !matches!(method, "GET" | "POST") {
        return Err(format!("只允许 GET / POST，收到：{method:?}"));
    }
    validate_path(path)?;

    // 单行拼，不要用 `\` 续行：续行里的缩进会**静默**变成请求行/头的一部分
    // （`health_request` 那里也踩过同一件事，有测试逐行检查行首行尾空格）。
    let mut req = format!(
        "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nAccept: application/json\r\nConnection: close\r\n"
    );
    if let Some(b) = body {
        // Content-Length 是**字节数**。中文 JSON 按字符数算会偏小，
        // 后端读不满声明的长度就会截断/挂住——而症状看起来像「注释丢了」。
        req.push_str(&format!(
            "Content-Type: application/json\r\nContent-Length: {}\r\n",
            b.len()
        ));
    }
    req.push_str("\r\n");
    if let Some(b) = body {
        req.push_str(b);
    }
    Ok(req)
}

/// `path` 必须是一个本机 API 路径，且不含任何能**注入请求行或请求头**的字符。
///
/// 这不是防「我们自己人」——是让「用字符串拼 HTTP 请求」这件事有一个明确的
/// 守门点：path 里一旦混进 `\r\n`，后面那截就会变成一条伪造的请求头。
fn validate_path(path: &str) -> Result<(), String> {
    if !path.starts_with("/api/") {
        return Err(format!("路径必须以 /api/ 开头，收到：{path:?}"));
    }
    if path.contains('\r') || path.contains('\n') || path.contains(' ') {
        return Err(format!("路径不得包含空格或换行，收到：{path:?}"));
    }
    Ok(())
}

/// 让外壳代发一条到后端的请求。**这是通用代理的唯一入口。**
pub fn api_request(
    port: u16,
    method: &str,
    path: &str,
    body: Option<&str>,
    timeout: Option<std::time::Duration>,
) -> Result<ApiResponse, String> {
    // 默认 10 秒，但**调用方可以要一个更长的** —— 模型调用动辄十几到几十秒，
    // 用 10 秒去等它只会在拿到答案之前把自己掐断（2026-10-25 用户报的
    // 「模型识别不了图片」就是这么来的：请求在 10 秒时被外壳断开，
    // 前端只看到一句超时，看起来像模型不支持图片）。
    let timeout = timeout.unwrap_or(API_TIMEOUT);
    let addr = format!("127.0.0.1:{port}");
    let read_err = |e: std::io::Error| {
        HealthError::Read {
            addr: addr.clone(),
            source: e,
        }
        .into_message()
    };
    let connect_err = |e: std::io::Error| {
        HealthError::Connect {
            addr: addr.clone(),
            source: e,
        }
        .into_message()
    };

    // 先拼请求：path/method 不合法时**在开连接之前**就返回，别白连一次
    let request = build_request(port, method, path, body)?;

    let mut stream = std::net::TcpStream::connect(&addr).map_err(connect_err)?;
    stream.set_read_timeout(Some(timeout)).map_err(read_err)?;
    stream.set_write_timeout(Some(timeout)).map_err(read_err)?;

    {
        use std::io::Write;
        stream.write_all(request.as_bytes()).map_err(read_err)?;
        stream.flush().map_err(read_err)?;
    }

    let raw = read_to_end(&mut stream, MAX_RESPONSE_BYTES).map_err(read_err)?;
    parse_api_response(&raw).map_err(HealthError::into_message)
}

/// **纯函数**：一整条响应字节 → [`ApiResponse`]。复用既有的那套工具
/// （分头/正文、状态行、chunked 解码），只是**契约不同**：
///
/// - 任何状态码都是 `Ok`（含 400/404）——前端按状态码分流。
/// - **不做任何正文校验**：204 与 5xx 都可能没有正文。
///
/// 正因契约不同，这里没有强行跟 `parse_health_response` 合并：那边的正文必须
/// 永远是一个合法的 `Health` JSON（缺字段就是错误），这边对正文一无所知。
pub fn parse_api_response(raw: &[u8]) -> Result<ApiResponse, HealthError> {
    let (status_line, headers, body) = split_response(raw)?;
    let code = parse_status_code(status_line)?;
    let body = if is_chunked(headers) {
        chunked_decode(body)?
    } else {
        body.to_vec()
    };

    // **这里刻意没有「正文是空的」检查**——与 `parse_health_response` 不同。
    //   * health 的正文必须是合法 JSON，空正文对它是真错误；
    //   * 代理对正文一无所知，而 **204 与 5xx 都可能没有正文**：
    //     「这个动作没做过」就是 `204 + 空正文`，把它判成错误会让一个**正常**状态
    //     变成一个吓人的失败。
    //
    // 所以 204 与空正文都自然地流成 `{status, body: ""}`，由前端按状态码分流。
    // 这一条由 `returns_an_empty_body_for_204` 与新加的守卫测试共同钉住：
    // 哪天有人照抄 health 那条检查，它们会立刻变红。
    let text = String::from_utf8(body).map_err(|_| HealthError::Malformed {
        detail: "正文不是合法的 UTF-8".to_string(),
    })?;
    Ok(ApiResponse {
        status: code,
        body: text,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- 真实后端响应的字节（2026-10-16 实测捕获）----
    //
    // 不是构造的，是用 Python `socket` 直接打 `127.0.0.1:<随机端口>` 抓下来的原始字节。
    // 请求是 `GET /api/health HTTP/1.1` + `Host` + `Connection: close`，
    // 后端（Spring Boot 3.5.16 / Tomcat 10.1.55，OpenJDK 25.0.2）回的**逐字节**就是下面这条。
    // 三个形态特征都在这条里：
    //   * 状态行是 `HTTP/1.1 200 `——**尾部有一个空格**，Tomcat 不发 reason phrase
    //   * **`Transfer-Encoding: chunked`**——所以手写客户端必须有 chunked 解码器
    //   * 正文前面是 `41`（十六进制 = 65 字节，正是那段 UTF-8 JSON 的长度），后面是 `0\r\n\r\n`
    //
    // 用 `concat!` 拼普通字符串字面量（**不能用 byte string**：`b"…"` 里
    // 不允许出现非 ASCII 字符，中文会直接编译不过）。
    const REAL_BACKEND_RESPONSE: &str = concat!(
        "HTTP/1.1 200 \r\n",
        "Content-Type: application/json\r\n",
        "Transfer-Encoding: chunked\r\n",
        "Date: Fri, 16 Oct 2026 08:15:54 GMT\r\n",
        "Connection: close\r\n",
        "\r\n",
        "41\r\n",
        "{\"cache\":\"缓存：未启用（配置为关闭）\",\"status\":\"up\"}",
        "\r\n0\r\n\r\n",
    );

    // ---- 真实后端响应 → 解析 ----

    #[test]
    fn chunked_decoding_is_required_by_the_real_backend() {
        // 这条是**唯一**能守住「后端会 chunked 回」这件事的测试，也是上面那个
        // 「手写 TCP」决定的证据。它同时是 `chunked_decode` 的杀手：
        // 把 `is_chunked` 判断删掉、或把 chunked_decode 换成直通，
        // 解析出来的 body 会是 `41\r\n{...}\r\n0\r\n\r\n`，serde_json 直接报错。
        let h = parse_health_response(REAL_BACKEND_RESPONSE.as_bytes())
            .expect("真实后端响应应当解析成功");
        assert_eq!(h.status, "up");
        assert_eq!(h.cache, "缓存：未启用（配置为关闭）");
    }

    #[test]
    fn the_real_backend_response_has_a_trailing_space_in_its_status_line() {
        // Tomcat 发的状态行是 `HTTP/1.1 200 `——**尾部有空格**，没有 reason phrase。
        // 按 `split(' ')` 取第二段就能正确拿到 `200`；这条把那个形态钉住，
        // 免得有人把解析改写成更脆的写法时无声退化。
        let (status_line, headers, body) = split_response(REAL_BACKEND_RESPONSE.as_bytes()).unwrap();
        assert_eq!(status_line, "HTTP/1.1 200 ");
        assert_eq!(parse_status_code(status_line).unwrap(), 200);
        assert!(is_chunked(headers), "真实响应必须被认出是 chunked");
        // 这一条同时证明：**没有**基于 Content-Length 去截断正文
        // （真实响应里压根没有 Content-Length 头，按它截会得到空正文）
        assert!(!body.is_empty(), "正文不该是空的");
    }

    // ---- chunked ----

    #[test]
    fn decodes_a_single_chunk() {
        assert_eq!(chunked_decode(b"5\r\nhello\r\n0\r\n\r\n").unwrap(), b"hello");
    }

    #[test]
    fn decodes_multiple_chunks_and_concatenates_them_in_order() {
        // 多块是真实存在的：内容大于 Tomcat 的缓冲区时它就会切成多块。
        // 「顺序」也是被断言的一部分——拼错顺序的实现在单块测试下照样绿。
        let body = b"3\r\nabc\r\n5\r\ndefgh\r\n2\r\nij\r\n0\r\n\r\n";
        assert_eq!(chunked_decode(body).unwrap(), b"abcdefghij");
    }

    #[test]
    fn decodes_a_terminating_chunk_with_trailers() {
        // 结束块后面可以跟 trailer 头，再到一个空行。不解析但**不能报错**。
        assert_eq!(
            chunked_decode(b"5\r\nhello\r\n0\r\nX-Trace: abc\r\n\r\n").unwrap(),
            b"hello"
        );
    }

    #[test]
    fn decodes_an_empty_chunked_body() {
        // 只有结束块：正文确实是空的。这一条与「正文是空的」那个错误必须区分开，
        // 所以它返回 `Ok(空)`，由 parse_health_json 去报「正文是空的」。
        assert_eq!(chunked_decode(b"0\r\n\r\n").unwrap(), b"");
    }

    #[test]
    fn tolerates_chunk_extensions() {
        // RFC 9112 允许 `长度;扩展`。真实后端不发，但容错的成本是零。
        assert_eq!(
            chunked_decode(b"5;foo=bar\r\nhello\r\n0\r\n\r\n").unwrap(),
            b"hello"
        );
    }

    #[test]
    fn tolerates_a_bare_lf_line_ending() {
        // RFC 允许裸 \n。真实后端发的是 CRLF，但容错不该只对一种行尾成立。
        assert_eq!(chunked_decode(b"5\nhello\n0\n\n").unwrap(), b"hello");
    }

    #[test]
    fn uppercase_hex_chunk_size_is_accepted() {
        // `A` 与 `a` 都是合法的十六进制长度。
        assert_eq!(
            chunked_decode(b"A\r\n0123456789\r\n0\r\n\r\n").unwrap(),
            b"0123456789"
        );
        assert_eq!(
            chunked_decode(b"a\r\n0123456789\r\n0\r\n\r\n").unwrap(),
            b"0123456789"
        );
    }

    #[test]
    fn rejects_a_chunk_size_that_is_not_hex() {
        assert!(matches!(
            chunked_decode(b"zz\r\nhello\r\n0\r\n\r\n").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_chunk_cut_short() {
        // 声称 10 字节，实际只给了 3 个——继续猜下去会把损坏的数据当成功。
        match chunked_decode(b"a\r\nabc").unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("只剩"),
                "消息该指出字节数不够，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_a_chunk_missing_its_trailing_crlf() {
        assert!(matches!(
            chunked_decode(b"3\r\nabc").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_chunk_stream_without_a_terminating_block() {
        // 块数据正常，但永远没有 `0\r\n\r\n`：读到流末尾就该报错，
        // 而不是把已经收到的那点当当完整正文交出去。
        assert!(matches!(
            chunked_decode(b"3\r\nabc\r\n").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_chunk_above_the_response_cap() {
        // 一个足够大的长度不该让外壳去分配内存——先在长度这一关拦掉。
        let big = format!("{:x}\r\n", MAX_RESPONSE_BYTES + 1);
        match chunked_decode(big.as_bytes()).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("超过上限"),
                "消息该说超过上限，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    // ---- 读路径的字节上限 ----

    #[test]
    fn read_to_end_reads_everything_when_under_the_cap() {
        let mut src = std::io::Cursor::new(b"abcdefghij".to_vec());
        assert_eq!(read_to_end(&mut src, 100).unwrap(), b"abcdefghij");
    }

    #[test]
    fn read_to_end_stops_at_the_byte_cap() {
        // 上限是**必须**的：一个不停吐字节（或畸形）的响应不能把外壳的内存吃光。
        // 把上限那个判断删掉，这条会一直读——这里的源有 10MB，测试会明显变慢而非挂死。
        let mut src = std::io::Cursor::new(vec![b'x'; 10 * 1024 * 1024]);
        let out = read_to_end(&mut src, 64).unwrap();
        assert_eq!(out.len(), 64, "应当停在上限 64 字节");
        assert!(out.iter().all(|b| *b == b'x'));
    }

    #[test]
    fn read_to_end_propagates_io_errors() {
        struct Boom;
        impl std::io::Read for Boom {
            fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
                Err(std::io::Error::new(std::io::ErrorKind::ConnectionReset, "boom"))
            }
        }
        let err = read_to_end(&mut Boom, 64).unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::ConnectionReset);
    }

    // ---- 状态行 ----

    #[test]
    fn parses_a_status_line_with_a_reason_phrase() {
        assert_eq!(parse_status_code("HTTP/1.1 200 OK").unwrap(), 200);
        assert_eq!(parse_status_code("HTTP/1.0 404 Not Found").unwrap(), 404);
    }

    #[test]
    fn rejects_a_status_line_that_is_not_http() {
        match parse_status_code("<html>hello</html>").unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("不以 HTTP/ 开头"),
                "消息该指出状态行不认识，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_a_status_line_with_a_non_numeric_code() {
        assert!(matches!(
            parse_status_code("HTTP/1.1 abc OK").unwrap_err(),
            HealthError::Malformed { .. }
        ));
        assert!(matches!(
            parse_status_code("HTTP/1.1").unwrap_err(),
            HealthError::Malformed { .. }
        ));
        // 空状态行：版本段是空串，不以 HTTP/ 开头
        assert!(matches!(
            parse_status_code("").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    // ---- 整条响应 ----

    /// 拼一条不带 chunked 的响应（HTTP/1.0 请求时后端回的就是这个形态）。
    fn plain_response(status: &str, content_type: &str, body: &str) -> Vec<u8> {
        format!("{status}\r\nContent-Type: {content_type}\r\nConnection: close\r\n\r\n{body}")
            .into_bytes()
    }

    #[test]
    fn parses_a_plain_response_without_chunked() {
        // 后端对 HTTP/1.0 请求回的就是这个形态（实测）：没有 Transfer-Encoding，
        // 正文直接跟在空行后面。这条守住「没有 chunked 也要能读」。
        let raw = plain_response(
            "HTTP/1.1 200 ",
            "application/json",
            "{\"cache\":\"缓存：未启用（配置为关闭）\",\"status\":\"up\"}",
        );
        let h = parse_health_response(&raw).unwrap();
        assert_eq!(h.status, "up");
        assert_eq!(h.cache, "缓存：未启用（配置为关闭）");
    }

    #[test]
    fn recognizes_a_lowercase_chunked_header_name() {
        // 头名大小写不敏感。真实后端发的是 `Transfer-Encoding`，
        // 但如果哪天变成 `transfer-encoding`，判断不该因此失效。
        let raw = concat!(
            "HTTP/1.1 200 \r\n",
            "content-type: application/json\r\n",
            "transfer-encoding: chunked\r\n",
            "\r\n",
            "2\r\n{}\r\n0\r\n\r\n",
        );
        let (_, headers, _) = split_response(raw.as_bytes()).unwrap();
        assert!(is_chunked(headers));
    }

    #[test]
    fn a_chunked_header_with_other_tokens_still_counts() {
        // `Transfer-Encoding: gzip, chunked` 也是 chunked。
        let (_, headers, _) =
            split_response(b"HTTP/1.1 200 \r\nTransfer-Encoding: gzip, chunked\r\n\r\nx")
                .unwrap();
        assert!(is_chunked(headers));
        // 别的编码不算
        let (_, headers, _) =
            split_response(b"HTTP/1.1 200 \r\nTransfer-Encoding: gzip\r\n\r\nx").unwrap();
        assert!(!is_chunked(headers));
        // 相似但不同的头名不算
        let (_, headers, _) = split_response(b"HTTP/1.1 200 \r\nX-Transfer-Encoding: chunked\r\n\r\nx")
            .unwrap();
        assert!(!is_chunked(headers));
    }

    #[test]
    fn rejects_a_non_200_status() {
        // 「HTTP 非 2xx 时抛错」这条语义的**唯一**去处：HTTP 不再由前端发了，
        // 它由 Rust 侧承担。这条就是那个语义在新位置的等价物。
        match parse_health_response(&plain_response(
            "HTTP/1.1 500 ",
            "application/json",
            "{\"error\":\"boom\"}",
        ))
        .unwrap_err()
        {
            HealthError::NotOk { code } => assert_eq!(code, 500),
            other => panic!("应当是 NotOk，实际 {other:?}"),
        }
        assert!(matches!(
            parse_health_response(&plain_response("HTTP/1.1 404 ", "application/json", "{}"))
                .unwrap_err(),
            HealthError::NotOk { code: 404 }
        ));
        // 3xx 与 1xx 同样不是 200
        for code in [100u16, 204, 301, 302] {
            let raw = plain_response(&format!("HTTP/1.1 {code} "), "text/plain", "");
            assert!(
                matches!(
                    parse_health_response(&raw).unwrap_err(),
                    HealthError::NotOk { .. }
                ),
                "{code} 不该被当成成功"
            );
        }
    }

    #[test]
    fn rejects_a_non_200_chunked_response() {
        // 非 2xx 也要走 chunked 那条路（后端 404 就是 chunked 回的，实测）。
        // 状态码先于正文判定，所以即使正文是合法 JSON 也必须拒。
        let raw = concat!(
            "HTTP/1.1 404 \r\n",
            "Content-Type: application/json\r\n",
            "Transfer-Encoding: chunked\r\n",
            "\r\n",
            "2\r\n{}\r\n0\r\n\r\n",
        );
        assert!(matches!(
            parse_health_response(raw.as_bytes()).unwrap_err(),
            HealthError::NotOk { code: 404 }
        ));
    }

    #[test]
    fn the_not_ok_error_message_carries_the_code() {
        // 非 2xx 那条消息必须带上真正的状态码：用户看到「HTTP 500」才知道是
        // 后端自己炸了，而不是「读不懂响应」那种没法行动的话。
        let msg = HealthError::NotOk { code: 503 }.into_message();
        assert!(msg.contains("503"), "消息里应当有状态码，实际 {msg}");
        assert!(msg.contains("200"), "消息该说明期望值，实际 {msg}");
    }

    #[test]
    fn rejects_an_empty_body() {
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "");
        match parse_health_response(&raw).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("正文是空的"),
                "消息该说是空正文，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_an_empty_chunked_body_the_same_way() {
        // chunked 版的空正文（只有结束块）也要落到「正文是空的」，
        // 而不是在 chunked 解码那里报一句别的——同一个真相对用户应当是同一句话。
        let raw = b"HTTP/1.1 200 \r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n";
        match parse_health_response(raw).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("正文是空的"),
                "消息该说是空正文，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_malformed_json() {
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "not json at all");
        match parse_health_response(&raw).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("合法的 Health JSON"),
                "消息该指出 JSON 不合法，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
        // 截断的 JSON（真实世界里最常见的畸形：连接被中途掐断）
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "{\"cache\":\"缓存");
        assert!(matches!(
            parse_health_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_json_that_is_not_an_object() {
        for body in ["[]", "\"a string\"", "42", "null", "true"] {
            let raw = plain_response("HTTP/1.1 200 ", "application/json", body);
            assert!(
                matches!(
                    parse_health_response(&raw).unwrap_err(),
                    HealthError::Malformed { .. }
                ),
                "{body} 不该被当成 Health"
            );
        }
    }

    #[test]
    fn rejects_a_body_missing_the_status_field() {
        let raw = plain_response(
            "HTTP/1.1 200 ",
            "application/json",
            "{\"cache\":\"缓存：未启用（配置为关闭）\"}",
        );
        assert!(matches!(
            parse_health_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_body_missing_the_cache_field() {
        // **跨语言字段名漂移的守卫**：后端把 `cache` 漏给（或改名）时，这里必须报错。
        // 如果 Health 上写了 `#[serde(default)]`，这一条会变成「静默显示空缓存文案」——
        // 用户看到状态栏右侧一片空白，没有任何线索。
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "{\"status\":\"up\"}");
        match parse_health_response(&raw).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("cache"),
                "消息里该点名缺的是 cache，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_a_misspelled_cache_field() {
        // 与上一条成对：`cach` 这种**拼错**（而不是漏掉）也必须报错。
        // 这条是变异表里「字段名 cache 改成 cach」在 Rust 侧的杀手；
        // 前端那侧另有一条（backend.spec.ts 的跨语言契约）。
        let raw = plain_response(
            "HTTP/1.1 200 ",
            "application/json",
            "{\"cach\":\"缓存：未启用（配置为关闭）\",\"status\":\"up\"}",
        );
        assert!(
            matches!(
                parse_health_response(&raw).unwrap_err(),
                HealthError::Malformed { .. }
            ),
            "拼错的字段名必须报错，不能静默变成空文案"
        );
    }

    #[test]
    fn rejects_a_field_of_the_wrong_type() {
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "{\"status\":1,\"cache\":2}");
        assert!(matches!(
            parse_health_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_response_without_the_blank_line() {
        // 没有 `\r\n\r\n`：可能根本不是 HTTP（比如连到了别的服务）。
        match parse_health_response(b"HTTP/1.1 200 \r\nContent-Type: application/json\r\n")
            .unwrap_err()
        {
            HealthError::Malformed { detail } => assert!(
                detail.contains("空行"),
                "消息该指出缺少头/正文之间的空行，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_a_completely_empty_response() {
        assert!(matches!(
            parse_health_response(b"").unwrap_err(),
            HealthError::Malformed { .. }
        ));
        // 只有一个空行的畸形响应
        assert!(matches!(
            parse_health_response(b"\r\n\r\n").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_response_whose_headers_are_not_ascii() {
        // 头里混进非 UTF-8 字节：不该 panic，也不该把它当成正文去解析。
        let mut raw = b"HTTP/1.1 200 \r\nX-Bad: ".to_vec();
        raw.extend_from_slice(&[0xff, 0xfe]);
        raw.extend_from_slice(b"\r\n\r\n{}");
        assert!(matches!(
            parse_health_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn keeps_non_ascii_body_bytes_intact() {
        // 缓存文案是中文，长度前缀算的是**字节数**不是字符数。
        // 把 chunked 解码写成按字符切会让这条红。
        let json = "{\"cache\":\"缓存：已连接 127.0.0.1:6399\",\"status\":\"up\"}";
        let chunk = format!("{:x}\r\n{}\r\n0\r\n\r\n", json.len(), json);
        let raw = format!("HTTP/1.1 200 \r\nTransfer-Encoding: chunked\r\n\r\n{chunk}");
        let h = parse_health_response(raw.as_bytes()).unwrap();
        assert_eq!(h.cache, "缓存：已连接 127.0.0.1:6399");
    }

    #[test]
    fn the_health_struct_serializes_with_the_field_names_the_frontend_reads() {
        // **跨语言契约**（与 backend.rs 里 BackendState 那条同一个套路）：
        // 前端 `Health` interface 读的是 `status` 与 `cache` 两个键。
        // 这条把 Rust 真的序列化出来的字符串钉死——改了字段名或加了
        // `skip_serializing_if`，前端就会静默拿到 undefined。
        //
        // 下面这段 JSON 是原样复制前端 `backend.spec.ts` 里那条契约测试的输入，
        // 两处必须一字不差。
        let h = Health {
            status: "up".into(),
            cache: "缓存：未启用（配置为关闭）".into(),
        };
        assert_eq!(
            serde_json::to_string(&h).unwrap(),
            "{\"status\":\"up\",\"cache\":\"缓存：未启用（配置为关闭）\"}"
        );
    }

    // ---- 请求形状 ----

    #[test]
    fn the_request_targets_the_health_endpoint_and_asks_to_close() {
        let req = health_request(51234);
        assert!(req.starts_with("GET /api/health HTTP/1.1\r\n"), "实际 {req:?}");
        // Host 头是 HTTP/1.1 的**必需**头——实测缺了它会拿到 Tomcat 的 400
        assert!(req.contains("Host: 127.0.0.1:51234\r\n"), "实际 {req:?}");
        // 必须要求关闭：读循环靠 EOF 收尾，长连接会卡到超时
        assert!(req.contains("Connection: close\r\n"), "实际 {req:?}");
        assert!(req.ends_with("\r\n\r\n"), "实际 {req:?}");
        // 逐行检查：`format!` 续行里的缩进是**看不见的**，行首一旦混进空格，
        // 请求就会变成 ` Host: ...` 这种畸形头。
        for line in req.split("\r\n") {
            assert_eq!(line, line.trim_end(), "请求里不该有行尾空格：{line:?}");
            assert_eq!(line, line.trim_start(), "请求里不该有行首空格：{line:?}");
        }
    }

    // ---- 错误消息（给人看的那些话）----

    #[test]
    fn every_failure_class_gets_a_different_message() {
        // 四种失败必须各说各的话。用同一个枚举的不同变体拿到四条消息，
        // 断言它们两两不同——若有人把 into_message 改成一句「健康检查失败」，
        // 用户就再也分不清「后端没起来」和「后端读不懂」了。
        let addr = "127.0.0.1:1".to_string();
        // 数组而不是 `vec!`：下面只经 `.iter()` 使用，不需要堆分配（clippy 也这么建议）
        let msgs = [
            HealthError::Connect {
                addr: addr.clone(),
                source: std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "refused"),
            }
            .into_message(),
            HealthError::Read {
                addr,
                source: std::io::Error::new(std::io::ErrorKind::TimedOut, "timed out"),
            }
            .into_message(),
            HealthError::NotOk { code: 500 }.into_message(),
            HealthError::Malformed {
                detail: "正文是空的".to_string(),
            }
            .into_message(),
        ];
        for (i, a) in msgs.iter().enumerate() {
            for (j, b) in msgs.iter().enumerate() {
                if i != j {
                    assert_ne!(a, b, "第 {i} 条与第 {j} 条消息不该一样");
                }
            }
            assert!(!a.is_empty());
        }
    }

    #[test]
    fn a_read_timeout_is_worded_as_a_timeout_not_as_a_generic_io_error() {
        // Windows 上 socket 读超时常常报 `WouldBlock`，直接 to_string 出来是
        // 「资源暂时不可用」这种指不出下一步的话。这条钉住它被识别成超时。
        for kind in [
            std::io::ErrorKind::TimedOut,
            std::io::ErrorKind::WouldBlock,
            
        ] {
            let msg = HealthError::Read {
                addr: "127.0.0.1:1".into(),
                source: std::io::Error::new(kind, "some io error"),
            }
            .into_message();
            assert!(msg.contains("没有回话"), "kind={kind:?} 该被认成超时：{msg}");
        }
        // 别的 IO 错误如实报「通信失败」，不该硬说成超时
        let msg = HealthError::Read {
            addr: "127.0.0.1:1".into(),
            source: std::io::Error::new(std::io::ErrorKind::ConnectionReset, "reset"),
        }
        .into_message();
        assert!(msg.contains("通信失败"), "实际 {msg}");
    }

    #[test]
    fn is_timeout_also_recognizes_the_textual_form() {
        // 兜底那一道：有些平台把超时包成 Other + "timed out" 的文本。
        // `is_timeout` 是 backend 模块的私有函数，这里通过公开的 `into_message`
        // 间接钉住它——只有 `Other` 这一类才走文本判断，别的不受影响。
        let worded = |text: &str| {
            HealthError::Read {
                addr: "127.0.0.1:1".into(),
                source: std::io::Error::other(text),
            }
            .into_message()
        };
        assert!(worded("operation timed out").contains("没有回话"));
        assert!(worded("would block").contains("没有回话"));
        assert!(!worded("connection reset by peer").contains("没有回话"));
    }

    // -----------------------------------------------------------------------
    // 通用代理（api_request）
    // -----------------------------------------------------------------------
    //
    // 下面三条夹具是**真机抓下来的原始字节**，不是构造的：2026-10-19 用 Python
    // `socket` 直接打 `127.0.0.1:<随机端口>`（后端是计划 3 的 jar，库里已有 1 次训练）。
    // 抓三条是因为它们各自钉住一个不同的形态：
    //   * 200 且 chunked（前端读数据的常态）
    //   * 400 且 chunked（**非 2xx 也是 Ok**，前端要读里面那句 `error`）
    //   * 204 且**没有正文、没有 Content-Type**（「从没做过这个动作」）
    // 共同的形态特征：状态行尾部有空格、无 reason phrase（Tomcat 10.1.55）。

    /// 真机 `GET /api/sessions/recent`（200，chunked）。
    ///
    /// 头与块长度按实测形态；正文用 `format!` 算长度前缀——**不写字面量**，
    /// 中文 JSON 的长度按字节算，手写会随文案漂移而悄悄失效。
    fn recent_200_chunked() -> String {
        let json = r#"[{"id":1,"sessionDate":"2026-10-19","title":"胸 + 三头","perceivedState":"睡得好"}]"#;
        format!(
            "HTTP/1.1 200 \r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\
             Date: Mon, 19 Oct 2026 03:24:57 GMT\r\nConnection: close\r\n\r\n{:x}\r\n{}\r\n0\r\n\r\n",
            json.len(),
            json
        )
    }

    /// 真机 `POST /api/sessions`（400，chunked，body 是后端的校验文案）。
    fn bad_request_400() -> String {
        let json = r#"{"error":"一次训练至少要有 1 个动作"}"#;
        format!(
            "HTTP/1.1 400 \r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\
             Date: Mon, 19 Oct 2026 03:24:57 GMT\r\nConnection: close\r\n\r\n{:x}\r\n{}\r\n0\r\n\r\n",
            json.len(),
            json
        )
    }

    /// 真机 `GET /api/exercises/{id}/last-performance`（204）：**没有 Content-Type，
    /// 也没有正文**——这正是 `parse_api_response` 不能照抄 health 那条
    /// 「正文是空的」检查的原因。
    const REAL_204: &str = "HTTP/1.1 204 \r\nDate: Mon, 19 Oct 2026 03:24:57 GMT\r\nConnection: close\r\n\r\n";

    // ---- build_request ----

    #[test]
    fn builds_a_get_request_without_a_body() {
        let req = build_request(53100, "GET", "/api/sessions/recent", None).unwrap();
        assert!(req.starts_with("GET /api/sessions/recent HTTP/1.1\r\n"), "实际 {req:?}");
        assert!(req.contains("Host: 127.0.0.1:53100\r\n"), "实际 {req:?}");
        assert!(req.contains("Connection: close\r\n"), "实际 {req:?}");
        // 没有 body 就不该有这两个头
        assert!(!req.contains("Content-Length"), "实际 {req:?}");
        assert!(!req.contains("Content-Type"), "实际 {req:?}");
        assert!(req.ends_with("\r\n\r\n"), "实际 {req:?}");
    }

    #[test]
    fn builds_a_post_request_with_a_json_body_and_content_length() {
        // 正文里放中文：Content-Length 必须是**字节数**。按字符数算会偏小，
        // 后端读不满声明的长度就截断——症状看起来像「注释丢了」而不是报错。
        let body = r#"{"title":"胸 + 三头","note":"腿部动作"}"#;
        let req = build_request(8080, "POST", "/api/sessions", Some(body)).unwrap();

        assert!(req.starts_with("POST /api/sessions HTTP/1.1\r\n"), "实际 {req:?}");
        assert!(req.contains("Content-Type: application/json\r\n"), "实际 {req:?}");
        assert!(
            req.contains(&format!("Content-Length: {}\r\n", body.len())),
            "Content-Length 该是字节数 {}，实际 {req:?}",
            body.len()
        );
        // 中文占了不止一个字符：字节数必须严格大于字符数，否则说明算错了
        assert!(body.len() > body.chars().count());
        // 空行之后就是 body 原文
        assert!(req.ends_with(body), "实际 {req:?}");
        assert!(req.contains("\r\n\r\n"));
    }

    #[test]
    fn the_request_has_no_stray_whitespace_on_any_line() {
        // 与 health_request 那条同一个理由：`format!` 续行里的缩进是**看不见的**，
        // 行首一旦混进空格，请求就变成 ` Host: ...` 这种畸形头。
        let req = build_request(1, "POST", "/api/sessions", Some("{}")).unwrap();
        for line in req.split("\r\n") {
            assert_eq!(line, line.trim_end(), "请求里不该有行尾空格：{line:?}");
            assert_eq!(line, line.trim_start(), "请求里不该有行首空格：{line:?}");
        }
    }

    #[test]
    fn rejects_a_method_outside_get_and_post() {
        for m in ["DELETE", "PUT", "PATCH", "get", ""] {
            assert!(
                build_request(1, m, "/api/sessions", None).is_err(),
                "{m:?} 不该被接受"
            );
        }
    }

    #[test]
    fn rejects_a_path_that_is_not_an_api_path() {
        for p in ["/health", "api/sessions", "", "/", "/API/sessions"] {
            assert!(
                build_request(1, "GET", p, None).is_err(),
                "{p:?} 不该被接受"
            );
        }
    }

    #[test]
    fn rejects_a_path_with_crlf_or_space() {
        // **请求行/头注入**：path 里混进 \r\n，后面那截就会变成一条伪造的请求头。
        // 这条是那个守门点的杀手——把 validate_path 删掉，它必须变红。
        let injected = "/api/sessions\r\nX-Injected: 1";
        let err = build_request(1, "GET", injected, None).unwrap_err();
        assert!(err.contains("换行") || err.contains("空格"), "实际 {err}");
        // 而且必须**返回 Err**：Err 意味着那段串根本没有进入请求，
        // 也就无从谈起拼出一条伪造的头（一个「照样拼、只是事后报错」的实现会在这里红）。
        assert!(build_request(1, "GET", injected, None).is_err());

        assert!(build_request(1, "GET", "/api/a b", None).is_err());
        assert!(build_request(1, "GET", "/api/a\nb", None).is_err());
        assert!(build_request(1, "GET", "/api/a\rb", None).is_err());
    }

    #[test]
    fn the_api_timeout_is_longer_than_the_health_timeout() {
        // 写一次训练要落库；2 秒的健康检查超时拿来做这件事太紧。
        assert!(API_TIMEOUT > HEALTH_TIMEOUT);
        assert!(API_TIMEOUT >= std::time::Duration::from_secs(5));
    }

    // ---- parse_api_response ----

    #[test]
    fn parses_a_chunked_api_response() {
        // 唯一能守住「后端会 chunked 回」的 API 侧用例：不做 chunked 解码，
        // body 会带着长度前缀和结尾的 `0`，前端 JSON.parse 直接炸。
        let r = parse_api_response(recent_200_chunked().as_bytes()).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(
            r.body,
            r#"[{"id":1,"sessionDate":"2026-10-19","title":"胸 + 三头","perceivedState":"睡得好"}]"#
        );
        // 真的能当成 JSON 读出来（这才是前端要做的事）
        let v: serde_json::Value = serde_json::from_str(&r.body).unwrap();
        assert_eq!(v[0]["sessionDate"], "2026-10-19");
    }

    #[test]
    fn parses_a_plain_content_length_response() {
        // HTTP/1.0 请求时后端回的是「读到 EOF 为止」的形态：没有 Transfer-Encoding。
        let raw = "HTTP/1.1 200 \r\nContent-Type: application/json\r\nConnection: close\r\n\r\n[]";
        let r = parse_api_response(raw.as_bytes()).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(r.body, "[]");
    }

    #[test]
    fn returns_the_status_instead_of_failing_on_400() {
        // **这是本任务语义上的重点**：400 不是「传输失败」，它是一个**客户端错误**，
        // 前端要读 `error` 把它显示给用户。若这里返回 Err，那句文案就丢了。
        let r = parse_api_response(bad_request_400().as_bytes()).unwrap();
        assert_eq!(r.status, 400);
        let v: serde_json::Value = serde_json::from_str(&r.body).unwrap();
        assert_eq!(v["error"], "一次训练至少要有 1 个动作");
    }

    #[test]
    fn returns_the_status_instead_of_failing_on_404() {
        let json = r#"{"error":"找不到这次训练: 999999"}"#;
        let raw = format!(
            "HTTP/1.1 404 \r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\
             Connection: close\r\n\r\n{:x}\r\n{}\r\n0\r\n\r\n",
            json.len(),
            json
        );
        let r = parse_api_response(raw.as_bytes()).unwrap();
        assert_eq!(r.status, 404);
        assert!(r.body.contains("999999"), "实际 {}", r.body);
    }

    #[test]
    fn returns_an_empty_body_for_204() {
        // 「从没做过这个动作」的应答。**不能**在「正文是空的」那一步报错——
        // 照抄 parse_health_response 的检查就会变成这里红。
        let r = parse_api_response(REAL_204.as_bytes()).unwrap();
        assert_eq!(r.status, 204);
        assert_eq!(r.body, "");
    }

    #[test]
    fn a_204_body_is_empty_even_when_chunked() {
        // 有些容器回 204 时也带 Transfer-Encoding（正文是零长度块）。
        // 两条路径都要落到「空的 body」而不是「正文是空的」那个错误。
        let raw = "HTTP/1.1 204 \r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n0\r\n\r\n";
        let r = parse_api_response(raw.as_bytes()).unwrap();
        assert_eq!(r.status, 204);
        assert_eq!(r.body, "");
    }

    #[test]
    fn an_empty_body_on_a_non_204_is_still_ok() {
        // 把上面两条的契约说全：**空正文本身不是错误**，与状态码无关。
        // 这一条与 `returns_an_empty_body_for_204` 一起，是「照抄 health 那条
        // 『正文是空的』检查」这个变异的杀手——那种改法会让这两条同时变红。
        let raw = "HTTP/1.1 500 \r\nConnection: close\r\n\r\n";
        let r = parse_api_response(raw.as_bytes()).unwrap();
        assert_eq!(r.status, 500);
        assert_eq!(r.body, "");
    }

    #[test]
    fn a_malformed_response_is_an_error_not_a_success() {
        assert!(matches!(
            parse_api_response(b"not http at all").unwrap_err(),
            HealthError::Malformed { .. }
        ));
        // 正文不是 UTF-8：如实报错，别 panic
        let mut raw = b"HTTP/1.1 200 \r\nConnection: close\r\n\r\n".to_vec();
        raw.extend_from_slice(&[0xff, 0xfe, 0xff]);
        assert!(matches!(
            parse_api_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    // ---- 传输层失败：Err（不是状态码） ----

    #[test]
    fn an_unreachable_port_is_an_error_not_a_status() {
        // 「连不上」不是任何 HTTP 状态码能表达的东西，所以它必须是 Err。
        // 先绑一个端口再放开，拿到一个确定没人监听的端口号（避免碰运气）。
        let port = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let err = api_request(port, "GET", "/api/health", None, None).unwrap_err();
        assert!(err.contains("连不上"), "实际 {err}");
        assert!(!err.contains("HTTP 200"));
    }

    #[test]
    fn a_bad_method_is_rejected_before_opening_a_connection() {
        // 校验发生在连 socket 之前：端口上有没有人监听与这条断言无关。
        let port = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let err = api_request(port, "DELETE", "/api/sessions", None, None).unwrap_err();
        assert!(err.contains("GET / POST"), "实际 {err}");
    }
}
