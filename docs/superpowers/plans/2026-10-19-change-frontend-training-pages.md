# 前端计划：今日与训练记录页实施计划

> **命名**：这**不是**总览里的「计划 4」——**计划 4 是饮食记录与拍照识别**（总览的「计划清单与依赖」表格）。前端的工作按**主题**命名（见总览里「计划 1 与计划 2 之间：Tauri 外壳与 Vue 前端脚手架」那一节），不占计划编号。初稿误用了「计划 4」，2026-10-19 改正。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打开窗口就能记一次训练——今日页在最显眼的位置给出「本周容量」与最近几次训练；训练记录页能从选肌群/搜索挑动作、自动带出该动作上次的重量、填组并一次写入，再回看历史。

**Architecture:** 前端**不发任何 HTTP**。跨源问题沿用计划 1 的结论：由 Tauri 外壳（Rust）代发请求（`src-tauri/src/http.rs` 已有一套手写 TCP + chunked 解码 + 分类错误），本计划把它从「只会 GET /api/health」扩展成一条**通用代理命令** `api_request(method, path, body)`，前端只 `invoke`。后端**零改动**、CSP 保持 `connect-src 'self'`、capabilities 不动。读侧数据全部来自计划 3 已冻结的 5 个端点；**不新增后端端点**。

**Tech Stack:** Vue 3 + TypeScript + vue-router + vitest 3 / jsdom / `@vue/test-utils`；Tauri 2 外壳（Rust，serde + serde_json，无新依赖）。

---

## 前置事实（已实测/已存在，不需要重新验证）

写计划前把会用到的既有事实核过一遍（计划 2/3 里「计划自己的重述失真」出现过 8 次）。

| 事实 | 值 / 位置 |
|---|---|
| 页面源 | dev `http://127.0.0.1:1420`；打包后 `tauri://localhost`。与后端**随机端口**不同源 |
| 为什么必须由外壳代发 | CSP 与 CORS 是**两道独立且都要过的门**。计划 1 只放行了 CSP，真机上仍被 CORS 拦成 `TypeError: Failed to fetch`。修法（Task 8）是**不给后端加 CORS**，改由外壳发——它不经浏览器，不在同源策略管辖内 |
| 既有外壳 HTTP 能力 | `frontend/src-tauri/src/http.rs`：`health_request(port)` / `get_health(port)` / `parse_health_response(raw)` / `chunked_decode` / `read_to_end` / `split_response` / `parse_status_code` / `is_chunked` / `HealthError`（4 类：Connect / Read / NotOk / Malformed） |
| 本机实测的响应形态 | Spring Boot 3.5.16 / Tomcat 10.1.55：HTTP/1.1 回 `/api/health` **总是 `Transfer-Encoding: chunked`**；状态行是 `HTTP/1.1 200 `（**尾部有空格**，无 reason phrase）；HTTP/1.0 请求则是「读到 EOF 为止」。非 2xx 也是 chunked 回的 |
| `HEALTH_TIMEOUT` | `2s`（`http.rs`）。**通用代理不能用它**——写一次训练要落库，2 秒太紧，见 Task 1 |
| 命令注册表测试 | `frontend/src-tauri/src/lib.rs` 的 `both_commands_are_registered_in_the_invoke_handler` **硬断言命令恰好 2 条**、且参数表里不含注释。加第三条命令**必须同时改这条测试**（否则它变红——这正是它的价值） |
| `Health` 的字段名契约 | Rust `backend::Health` 与 TS `api/backend.ts` 的 `Health` 是**两处手写定义**，各自有契约测试钉住 |
| capabilities | `src-tauri/capabilities/default.json` 只有 `core:default`。**应用自定义命令不受 capability 限制**（capability 管的是插件权限），所以加命令**不需要**改它 |
| CSP | `tauri.conf.json`：`connect-src 'self'`。**本计划不动它**——壳内代发不需要放开任何源 |
| 前端测试写法 | `src/**/*.spec.ts`，vitest + jsdom；mock `@tauri-apps/api/core` 的 `invoke`（见 `api/backend.spec.ts` / `BackendStatusBar.spec.ts` 的按命令名分派）。只读文件、不碰 DOM 的 spec 首行加 `// @vitest-environment node` |
| 当前测试数 | `cd frontend && pnpm test` → **50 passed（8 files）**；`pnpm build` → 退 0；`cd src-tauri && cargo test` → **76 passed + 4 integration，2 ignored** |
| 后端接口 | 计划 3 已冻结，见下「接口契约」。**本计划一行后端代码都不改** |
| 设计 token | `src/styles/theme.css` + `theme.spec.ts`。文件注释明确写着：**语义色（`--danger`/`--warning`）、肌肉色阶「加它们的时机是消费它们的界面出现时」**——本计划就是那个时机（Task 3） |
| 占位页 | `views/TodayView.vue`（「计划 3 会在这里放本周容量」）与 `views/TrainingView.vue` 是纯占位，本计划替换 |

---

## 一、必须先定下来的：跨源怎么走（本计划冻结）

**决定：扩展「外壳代发」为通用代理（handoff 的选项 1）。**

- 新增一条 Tauri 命令 `api_request(method, path, body?)`，`method ∈ {GET, POST}`、`path` 必须以 `/api/` 开头。前端对后端的所有读写都走它。
- **后端不加 CORS、CSP 不放宽、capabilities 不动。** 理由与 Task 8 一致：加了 CORS 等于**任何网页都能读** `127.0.0.1:<随机端口>` 的响应；而 `tauri://localhost` 是不透明源，自定义 scheme 的 CORS 处理很脏。
- 被否掉的另外两条：②后端加本机源白名单（把口子开大了一点，且要动计划 3 已冻结的后端）；③Tauri HTTP 插件（要新依赖 + capability 白名单，比复用既有的 `http.rs` 更重）。

**这条不定下来，实现到一半会卡住**（前端 `fetch` 会被浏览器拦掉，且**测试全绿**——因为测试 mock 掉了网络）。

---

## 二、接口契约（前端要用的，全部来自计划 3）

基地址由外壳持有；前端只给 **path**。所有响应 JSON。

| 方法 | 路径 | 请求 | 响应要点 |
|---|---|---|---|
| GET | `/api/volume/weekly` | `?date=YYYY-MM-DD`（省略=今天） | `{weekStart, weekEnd, muscles:[{muscleGroupId,nameZh,nameEn,sets,min,max,status}]}`，**固定 17 块**；`status ∈ NOT_CONFIGURED/UNTRAINED/BELOW/IN_RANGE/ABOVE`；`min/max` 可为 null |
| GET | `/api/sessions/recent` | — | `[{id, sessionDate, title, perceivedState}]`（最近 5 次，新的在前） |
| GET | `/api/sessions/{id}` | — | `{id, sessionDate, title, perceivedState, note, startedAt, endedAt, exercises:[{sessionExerciseId, exerciseId, nameZh, nameEn, orderIndex, note, supersetGroup, sets:[...]}]}`；不存在 → **404** |
| GET | `/api/exercises` | `?muscleGroupId=&q=&category=&limit=` | `[{id,nameEn,nameZh,equipment,isCompound,category}]`；不给 category 时排除 stretching；limit 默认 50、封顶 200 |
| GET | `/api/exercises/{id}/last-performance` | — | `{sessionDate, sessionExerciseId, sets:[{setType,weight,repetitions,durationSeconds,perSide}]}`；**从没做过 → 204（空 body）** |
| POST | `/api/sessions` | `SessionInput` | `{sessionId, exercises, sets}`；不合法 → **400** `{error}` |

`SessionInput`（POST body）：

```json
{
  "sessionDate": "2026-10-19",
  "title": "胸 + 三头",
  "perceivedState": "睡得好",
  "note": "自由文本",
  "startedAt": "2026-10-19T19:30",
  "endedAt": null,
  "exercises": [
    { "exerciseId": 16, "note": "主项", "supersetGroup": null,
      "sets": [ { "setType": "normal", "weight": 60, "repetitions": 8,
                  "durationSeconds": null, "perSide": false, "rir": null,
                  "restSeconds": null, "completed": true,
                  "targetWeight": null, "targetReps": null } ] }
  ]
}
```

校验：`sessionDate` 必填；至少 1 个动作；每个动作至少 1 组。不合法 → 400 `{error}`。

**肌群列表从哪来**：**复用 `GET /api/volume/weekly` 的那 17 块**（`muscleGroupId` / `nameZh` / `nameEn` 齐全），**不新增后端端点**。理由：计划 3 已冻结后端，而这份数据正是「选肌群」要的全集；今日页本来就要调它，顺带缓存下来即可。代价是「选肌群」隐含要求当天能取到周容量——不可达时选择器给出明确错误而不是空列表（Task 5 有一条测试钉这个）。

---

## 文件结构

**新建**

| 文件 | 职责 |
|---|---|
| `frontend/src/api/training.ts` | 训练域的类型 + 走 `invoke('api_request')` 的调用封装（唯一发请求的地方） |
| `frontend/src/api/training.spec.ts` | 上者的测试（status/204/400/404/传输失败） |
| `frontend/src/components/VolumePanel.vue` | 本周容量面板：17 块肌群 + 状态着色 + 目标区间 |
| `frontend/src/components/SessionList.vue` | 训练列表（最近 / 历史共用）；空态 |
| `frontend/src/views/TodayView.spec.ts` | 今日页测试 |
| `frontend/src/views/TrainingView.spec.ts` | 训练记录页测试 |

**修改**

| 文件 | 改什么 |
|---|---|
| `frontend/src-tauri/src/http.rs` | 加通用请求：`ApiResponse`、`api_request(port, method, path, body)`、`build_request(...)`、`parse_api_response(...)`、`API_TIMEOUT`；复用既有的 chunked / 状态行 / 读上限工具 |
| `frontend/src-tauri/src/lib.rs` | 加 `api_request` 命令（含就绪守卫）；**同步更新** `both_commands_are_registered_in_the_invoke_handler`（2 → 3 条） |
| `frontend/src/styles/theme.css` | 加语义色 token（`--ok` / `--warn` / `--danger` / `--muted-strong`）——`theme.spec.ts` 一并补断言 |
| `frontend/src/styles/theme.spec.ts` | 钉住新 token 与新语义色 |
| `frontend/src/views/TodayView.vue` | 占位 → 真正的今日页 |
| `frontend/src/views/TrainingView.vue` | 占位 → 真正的训练记录页 |

> **不改**：`src-tauri/tauri.conf.json`（CSP 不动）、`src-tauri/capabilities/default.json`、`src/nav.ts`、`src/router/index.ts`（两个路由已存在）、后端任何文件。

---

## Task 1: 外壳通用代理命令 `api_request`

**Files:**
- Modify: `frontend/src-tauri/src/http.rs`
- Modify: `frontend/src-tauri/src/lib.rs`

### 设计（写清楚，免得实现时反复）

- **成功路径一律 `Ok`**，**任何**服务端状态码（含 400/404）都算「请求完成」——前端要读 400 里的 `{error}` 文案。`Err` 只表示**传输层**失败：连不上 / 超时 / 响应读不懂。
- 返回 `ApiResponse { status: u16, body: String }`（`body` 是**原始响应文本**）。204 → `status=204, body=""`。前端在 TS 侧统一 `JSON.parse`，Rust 侧对业务 JSON 一无所知。
- 超时 `API_TIMEOUT = 10s`（**不是** `HEALTH_TIMEOUT=2s`：写一次训练要落库，2 秒太紧；而 10 秒仍远小于用户会以为「卡死」的时间）。
- `path` 校验：必须以 `/api/` 开头、**不得含 `\r`/`\n`/空格**。这一条是防**请求行注入**（把 `\r\n` 塞进 path 就能往请求里插头）——不是防我们自己人，是让「拼接字符串造 HTTP 请求」这件事有一个明确的守门点。
- 请求头：`Accept: application/json`、`Connection: close`（读循环靠 EOF 收尾）；有 body 时加 `Content-Type: application/json` + `Content-Length: <字节数>`。

- [ ] **Step 1: 写失败的测试**（追加到 `http.rs` 的 `mod tests` 里）

要覆盖（每条一个 `#[test]`，命名即断言）：

| 测试 | 断言 |
|---|---|
| `builds_a_get_request_without_a_body` | 首行 `GET /api/sessions/recent HTTP/1.1`；有 `Host`；有 `Connection: close`；**没有** `Content-Length`；以 `\r\n\r\n` 结尾 |
| `builds_a_post_request_with_a_json_body_and_content_length` | 有 `Content-Type: application/json`；`Content-Length: <UTF-8 字节数>`（用一个含中文的 body，钉住**按字节不按字符**）；body 原样在空行之后 |
| `rejects_a_method_outside_get_and_post` | `DELETE` / `PUT` / 空串 → `Err` |
| `rejects_a_path_that_is_not_an_api_path` | `/health`、`api/sessions`、`` → `Err` |
| `rejects_a_path_with_crlf_or_space` | `/api/x\r\nX-Injected: 1`、`/api/a b` → `Err`；**并断言返回的请求里绝不出现 `X-Injected`** |
| `parses_a_chunked_api_response` | 用**计划 3 真机抓下来的字节**（见下）→ `status=200`、`body` 是那段 JSON |
| `parses_a_plain_content_length_response` | HTTP/1.0 形态（无 chunked）→ 同样解出 body |
| `returns_the_status_instead_of_failing_on_400_and_404` | 400/404 的响应 → **`Ok`**，`status` 分别是 400/404，`body` 带 `{"error":"…"}` |
| `returns_an_empty_body_for_204` | `HTTP/1.1 204 ` → `Ok{status:204, body:""}`（**不能在「正文是空的」那一步报错**） |
| `a_transport_failure_is_an_error_not_a_status` | 未监听端口 → `Err`（走 `HealthError::Connect` 那条） |

`parses_a_chunked_api_response` 必须用**真机抓下来的原始字节**（原样复制，改一个字符就失去意义）。2026-10-19 本机实测，`GET /api/sessions/recent`（准备库里已有 1 次训练）回的逐字节是：

```text
HTTP/1.1 200 \r\n
Content-Type: application/json\r\n
Transfer-Encoding: chunked\r\n
Date: Sun, 19 Oct 2026 03:11:05 GMT\r\n
Connection: close\r\n
\r\n
<十六进制长度>\r\n
[{"id":1,"sessionDate":"2026-10-19","title":"胸 + 三头","perceivedState":"睡得好"}]
\r\n0\r\n\r\n
```

> 长度前缀在测试里用 `format!("{:x}", json.len())` 现算，**不要写字面量**——中文 JSON 的长度按字节算，手写字面量会随文案漂移而悄悄失效。

- [ ] **Step 2: 跑测试，确认失败**

```powershell
cd frontend/src-tauri
cargo test api_request -- --nocapture
```

Expected: 编译失败 —— `cannot find function api_request`（以及 `ApiResponse` 未定义）。

- [ ] **Step 3: 写实现**

在 `http.rs` 里加（放在 `get_health` 之后）：

```rust
/// 通用代理的超时。**不能用 [`HEALTH_TIMEOUT`]**：写一次训练要落库，
/// 2 秒太紧；而 10 秒仍远小于用户会以为「卡死」的时间。
pub const API_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);

/// 一条已完成的 HTTP 响应。**`status` 是服务端的真实状态码**——
/// 400/404 也是 `Ok`，因为前端要读 400 响应体里的 `{error}` 文案。
/// `Err` 只表示传输层失败（连不上 / 超时 / 读不懂）。
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct ApiResponse {
    pub status: u16,
    /// 原始响应文本。204 时是空串。Rust 侧不解析业务 JSON。
    pub body: String,
}

/// 拼一条请求。`path` 会先被校验（见 [`validate_path`]）。
pub fn build_request(port: u16, method: &str, path: &str, body: Option<&str>) -> Result<String, String> {
    if !matches!(method, "GET" | "POST") {
        return Err(format!("只允许 GET / POST，收到：{method:?}"));
    }
    validate_path(path)?;

    let mut req = format!(
        "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n\
         Accept: application/json\r\nConnection: close\r\n"
    );
    if let Some(b) = body {
        // Content-Length 是**字节数**：中文 JSON 按字符算会短，后端会截断请求体
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
fn validate_path(path: &str) -> Result<(), String> {
    if !path.starts_with("/api/") {
        return Err(format!("路径必须以 /api/ 开头，收到：{path:?}"));
    }
    if path.contains('\r') || path.contains('\n') || path.contains(' ') {
        return Err(format!("路径不得包含空格或换行，收到：{path:?}"));
    }
    Ok(())
}
```

`api_request` 本体（复用既有工具，不重写解码）：

```rust
/// 让外壳代发一条到后端的请求。
pub fn api_request(port: u16, method: &str, path: &str, body: Option<&str>) -> Result<ApiResponse, String> {
    let addr = format!("127.0.0.1:{port}");
    let read_err = |e: std::io::Error| {
        HealthError::Read { addr: addr.clone(), source: e }.into_message()
    };
    let request = build_request(port, method, path, body)?;

    let mut stream = std::net::TcpStream::connect(&addr).map_err(|e| {
        HealthError::Connect { addr: addr.clone(), source: e }.into_message()
    })?;
    stream.set_read_timeout(Some(API_TIMEOUT)).map_err(read_err)?;
    stream.set_write_timeout(Some(API_TIMEOUT)).map_err(read_err)?;

    {
        use std::io::Write;
        stream.write_all(request.as_bytes()).map_err(read_err)?;
        stream.flush().map_err(read_err)?;
    }

    let raw = read_to_end(&mut stream, MAX_RESPONSE_BYTES).map_err(read_err)?;
    parse_api_response(&raw).map_err(HealthError::into_message)
}

/// **纯函数**：一整条响应字节 → `ApiResponse`。与 [`parse_health_response`] 同一套工具。
pub fn parse_api_response(raw: &[u8]) -> Result<ApiResponse, HealthError> {
    let (status_line, headers, body) = split_response(raw)?;
    let code = parse_status_code(status_line)?;
    let body = if is_chunked(headers) { chunked_decode(body)? } else { body.to_vec() };

    // 204（以及 1xx/304）本来就没有正文：**不能**走「正文是空的」那个错误
    if code == 204 {
        return Ok(ApiResponse { status: code, body: String::new() });
    }
    let text = String::from_utf8(body).map_err(|_| HealthError::Malformed {
        detail: "正文不是合法的 UTF-8".to_string(),
    })?;
    Ok(ApiResponse { status: code, body: text })
}
```

> 注意 `parse_health_response` 里那句「正文是空的」检查**不能**被抄进 `parse_api_response`：204 是正常应答。这也正是为什么两条解析函数各留一份、不强行合并——它们的**契约不同**（health 必须永远有 JSON 正文）。

- [ ] **Step 4: 跑测试，确认通过**

```powershell
cargo test api_request -- --nocapture
cargo test            # 确认既有的 76 条没被弄坏
```

Expected: 新增 10 条全绿；既有全绿。

- [ ] **Step 5: 在 `lib.rs` 里注册命令**

```rust
/// 代前端发一条到后端的请求（通用代理）。
///
/// **为什么前端不发 HTTP**：页面源与后端随机端口不同源，会被 CORS 拦掉。
/// 详见 `http.rs` 的模块注释与计划 1 的 Task 8。
///
/// **为什么返回 `Ok` 而不把 400/404 变成 `Err`**：前端要根据状态码分流
/// （400 显示服务端的校验文案、404 显示「找不到」、204 显示「没做过」）。
/// 把状态码压成一句错误消息，这三条就再也分不开了。
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
        // 与 get_health 同一道就绪守卫：Starting/Failed 时没有可用的端口
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
```

并把注册表改成：

```rust
.invoke_handler(tauri::generate_handler![get_backend_status, get_health, api_request])
```

**同时必须更新** `both_commands_are_registered_in_the_invoke_handler`：
- 命令名数组 `["get_backend_status", "get_health"]` → 加 `"api_request"`
- `assert_eq!(…count(), 2, …)` → `3`
- 函数名与注释里的「两条」→「三条」

- [ ] **Step 6: 跑测试，确认通过**

```powershell
cargo test
```

Expected: 全绿，且注册表那条在**改坏时确实会红**（把 `api_request` 从注册表里删掉 → 它变红，再还原）。

- [ ] **Step 7: 提交**

```bash
git add frontend/src-tauri/src/http.rs frontend/src-tauri/src/lib.rs
git commit -m "feat(shell): 通用代理命令 api_request（外壳代发 GET/POST）"
```

---

## Task 2: 前端 API 客户端 `api/training.ts`

**Files:**
- Create: `frontend/src/api/training.ts`
- Test: `frontend/src/api/training.spec.ts`

### 设计

- **唯一**发请求的地方：内部一个 `callApi(method, path, body?)`，调 `invoke('api_request', { method, path, body })`。
- `invoke` **reject** → 抛 `ApiTransportError`（连不上/超时/后端没起来）。这不是状态码能表达的东西。
- 拿到 `{status, body}` 后：
  - `204` → 返回 `null`（`lastPerformance` 用）。
  - `2xx` → `JSON.parse(body)`。
  - **其它** → 抛 `ApiError(status, message)`，`message` 取 body 里的 `{error}`（后端 400 就是这么给的），取不到则给一句带状态码的兜底。
- 类型：`WeeklyVolume` / `MuscleVolume` / `VolumeStatus` / `ExerciseSummary` / `SessionSummary` / `SessionDetail` / `LastPerformance` / `SessionInput`，字段名**逐字对齐**计划 3 的 JSON（`muscleGroupId`、`nameZh`、`sessionExerciseId`…）。

- [ ] **Step 1: 写失败的测试**（`training.spec.ts`，mock `@tauri-apps/api/core`）

| 测试 | 断言 |
|---|---|
| `weekly_volume_passes_the_date_as_a_query_parameter` | `invoke` 收到 `('api_request', { method:'GET', path:'/api/volume/weekly?date=2026-10-19', body: undefined })`（或 body 省略） |
| `weekly_volume_without_a_date_sends_no_query` | path 恰为 `/api/volume/weekly`——「省略=今天」由后端决定，前端不许自己算日期 |
| `parses_the_weekly_volume_shape_the_backend_sends` | 用**真机 JSON**（Task 4 的 e2e 抄一份进来）喂进去，读出 `muscles` 17 条、`chest.sets===2.0`、`status==='BELOW'`、`min===10` |
| `recent_sessions_parses_an_array` | 数组、字段名 `sessionDate`/`perceivedState` |
| `last_performance_returns_null_for_204` | 走 204 → `null`，**不抛错**（「没做过」不是错误） |
| `last_performance_parses_200` | `sets[0].weight===60` |
| `search_exercises_builds_the_query_from_defined_params_only` | 传 `{muscleGroupId: 1, q: '卧推', category: undefined, limit: undefined}` → path 只有 `muscleGroupId=1&q=卧推`；中文要 URL 编码（断言 path 里是 `q=%E5%8D%A7%E6%8E%A8`） |
| `record_session_serializes_the_input_as_the_body` | `invoke` 收到的 `body` 是 `JSON.stringify(input)`；`JSON.parse(body)` 深等于传入对象 |
| `a_400_becomes_an_ApiError_carrying_the_server_message` | `{status:400, body:'{"error":"一次训练至少要有 1 个动作"}'}` → 抛出的 `message` 含「至少要有 1 个动作」，且 `status===400` |
| `a_404_becomes_an_ApiError` | `message` 里有 `404`，不会静默变成 `undefined` |
| `a_transport_rejection_propagates_as_an_error` | `invoke` reject `new Error('连不上后端…')` → 抛出，`message` 含「连不上」 |
| `a_malformed_body_is_an_error_not_a_silent_null` | `{status:200, body:'not json'}` → 抛错（**不能**返回 `undefined` 让界面渲染成空白） |
| `the_command_name_is_api_request` | `invoke` 第一个参数恒为 `'api_request'`（跨语言契约：Rust 侧少注册它前端就废） |

- [ ] **Step 2: 跑测试，确认失败**

```powershell
cd frontend
pnpm test src/api/training.spec.ts
```

Expected: 失败 —— 模块不存在。

- [ ] **Step 3: 写实现**

导出：`ApiError` / `ApiTransportError`、`callApi`（可导出以便复用）、以及下面这些函数：

```ts
export type VolumeStatus = 'NOT_CONFIGURED' | 'UNTRAINED' | 'BELOW' | 'IN_RANGE' | 'ABOVE'

export interface MuscleVolume {
  muscleGroupId: number; nameZh: string; nameEn: string
  sets: number; min: number | null; max: number | null; status: VolumeStatus
}
export interface WeeklyVolume { weekStart: string; weekEnd: string; muscles: MuscleVolume[] }

export interface ExerciseSummary {
  id: number; nameEn: string; nameZh: string | null
  equipment: string | null; isCompound: boolean; category: string | null
}
export interface SessionSummary {
  id: number; sessionDate: string; title: string | null; perceivedState: string | null
}
export interface SetSnapshot {
  setType: string; weight: number | null; repetitions: number | null
  durationSeconds: number | null; perSide: boolean
}
export interface LastPerformance { sessionDate: string; sessionExerciseId: number; sets: SetSnapshot[] }

export interface SetInput {
  setType: string; weight: number | null; repetitions: number | null
  durationSeconds: number | null; perSide: boolean; rir: number | null
  restSeconds: number | null; completed: boolean
  targetWeight: number | null; targetReps: number | null
}
export interface ExerciseInput { exerciseId: number; note: string | null; supersetGroup: string | null; sets: SetInput[] }
export interface SessionInput {
  sessionDate: string; title: string | null; perceivedState: string | null; note: string | null
  startedAt: string | null; endedAt: string | null; exercises: ExerciseInput[]
}

export function weeklyVolume(date?: string): Promise<WeeklyVolume>
export function recentSessions(): Promise<SessionSummary[]>
export function sessionDetail(id: number): Promise<SessionDetail>
export function searchExercises(p: { muscleGroupId?: number; q?: string; category?: string; limit?: number }): Promise<ExerciseSummary[]>
export function lastPerformance(exerciseId: number): Promise<LastPerformance | null>
export function recordSession(input: SessionInput): Promise<{ sessionId: number; exercises: number; sets: number }>
```

`callApi` 的核心（体现上面四条分流）：

```ts
async function callApi(method: 'GET' | 'POST', path: string, body?: unknown): Promise<unknown> {
  let res: { status: number; body: string }
  try {
    res = await invoke<{ status: number; body: string }>('api_request', {
      method, path, body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (e) {
    // 传输层失败：连不上 / 超时 / 后端没起来。**不是**状态码能表达的东西。
    throw new ApiTransportError(String(e))
  }
  if (res.status === 204) return null
  let parsed: unknown
  try {
    parsed = res.body ? JSON.parse(res.body) : null
  } catch {
    // 兜底：**绝不能**把读不懂的正文当成 undefined 放过去——界面会渲染成空白
    throw new ApiError(res.status, `后端返回了读不懂的正文（HTTP ${res.status}）`)
  }
  if (res.status >= 200 && res.status < 300) return parsed
  const msg = (parsed as { error?: string } | null)?.error
  throw new ApiError(res.status, msg ?? `后端返回 HTTP ${res.status}`)
}
```

- [ ] **Step 4: 跑测试，确认通过**

```powershell
pnpm test src/api/training.spec.ts
```

Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/api/training.ts frontend/src/api/training.spec.ts
git commit -m "feat(frontend): 训练域 API 客户端（走外壳代理）"
```

---

## Task 3: 语义色 token（容量的状态要用）

**Files:**
- Modify: `frontend/src/styles/theme.css`
- Modify: `frontend/src/styles/theme.spec.ts`

### 设计

`theme.css` 的注释明确把「语义色（`--danger`/`--warning`）」留到**消费它的界面出现时**再加——现在就是。容量有 5 个状态，需要 5 种视觉区分，但设计文档只给了**一个**强调色 `#e0453a`。取舍：

| 状态 | 视觉 | token | 值 |
|---|---|---|---|
| `BELOW` | 提醒（练少了） | `--warn` | `#d99a2b` |
| `IN_RANGE` | 达标 | `--ok` | `#4f9d5d` |
| `ABOVE` | 过量 | `--danger` | `#e0453a`（**复用 accent 的值**，但不复用变量——语义不同） |
| `UNTRAINED` | 0 组，未练 | `--muted-strong` | `#3a424b`（比 `--line` 亮、比 `--text-dim` 暗） |
| `NOT_CONFIGURED` | 没配目标 | `--text-dim` | 已有 |

> `--danger` 的值与 `--accent` 相同是**刻意**的：设计文档说「唯一的暖红」用于「肌肉高亮、主按钮、进度条」，过量训练属于同一族的「需要立刻看见」。但**必须另起一个 token**，否则哪天主按钮改色会连带把「过量」的语义改掉。**`theme.spec.ts` 的「`#e0453a` 全文件只出现一次」因此要放宽**：改成「只在 `--accent` 与 `--danger` 两处出现」。

- [ ] **Step 1: 先改测试（会红）**

在 `theme.spec.ts` 里：
- 把 `强调色只在 --accent 定义一次` 改成断言 `#e0453a` 恰好出现 **2** 次，且其中一次是 `--danger`。
- 新增：
  - `语义色齐了四个`：`--ok` / `--warn` / `--danger` / `--muted-strong` 都非空。
  - `四个语义色互不相同，且不等于 --text`：防止有人图省事把 `--warn` 也设成 `#e0453a`——那样「练少了」与「练过量」在界面上一模一样。
  - `没有引入阴影`（既有断言，确认仍然成立）。

- [ ] **Step 2: 跑，确认红**

```powershell
pnpm test src/styles/theme.spec.ts
```

Expected: 失败 —— `--ok` / `--warn` / `--danger` / `--muted-strong` 是 `undefined`。

- [ ] **Step 3: 加 token**

在 `theme.css` 的 `:root` 里，`--accent` 之后加：

```css
  /* 语义色。设计文档只给了一个强调色，但「本周容量」需要区分
     「练少了 / 达标 / 练过量 / 没练 / 没配目标」五种状态。
     --danger 与 --accent 同值、但**不同变量**：语义不同，将来可能分叉。 */
  --ok: #4f9d5d;             /* IN_RANGE：达标 */
  --warn: #d99a2b;           /* BELOW：练少了 */
  --danger: #e0453a;         /* ABOVE：练过量（与 --accent 同值，刻意） */
  --muted-strong: #3a424b;   /* UNTRAINED：0 组，未练 */
```

- [ ] **Step 4: 跑，确认绿**

```powershell
pnpm test src/styles/theme.spec.ts
```

- [ ] **Step 5: 提交**

```bash
git add frontend/src/styles/theme.css frontend/src/styles/theme.spec.ts
git commit -m "feat(frontend): 容量状态的语义色 token"
```

---

## Task 4: 今日页（本周容量 + 最近训练 + 开始训练）

**Files:**
- Create: `frontend/src/components/VolumePanel.vue`
- Create: `frontend/src/components/SessionList.vue`
- Modify: `frontend/src/views/TodayView.vue`
- Test: `frontend/src/views/TodayView.spec.ts`

### 设计要点

- **「本周容量」放最显眼位置**（设计文档「两处刻意的界面设计」第 1 条）：它在 `<h2>` 之后第一屏，字号/间距都高于「最近训练」。
- **0 组的肌群必须出现**：「腹/核心本周 0 组」这类提醒躲不掉才有用。`VolumePanel` 把 17 块全渲染，`UNTRAINED` 用 `--muted-strong` 但不隐藏、不排到最后（**保持后端给的顺序**，也就是肌群 id 序——固定顺序用户才能形成肌肉记忆）。
- 每块显示：中文名 + `sets`（`12.5` 这种半组要显示小数）+ 目标区间 `min–max`（null 时显示「未设目标」）+ 状态色。
- `SessionList`：列表 + 空态（「还没有训练记录」）+ 点击某次 → 以后接详情（本计划先只读展示，Task 5 复用）。
- 「开始训练」→ `router.push('/training')`。
- 失败处理：两个面板各自显示一句可读的错误（用 `ApiTransportError`/`ApiError` 的 message），**不白屏**。

- [ ] **Step 1: 写失败的测试**（`TodayView.spec.ts`，mock invoke 按 path 分派）

| 测试 | 断言 |
|---|---|
| `shows_the_weekly_volume_before_anything_else` | `VolumePanel` 在 DOM 里出现在 `SessionList` **之前**（`compareDocumentPosition` 或索引比较）——守着「最显眼」这条设计决定 |
| `renders_all_seventeen_muscles_including_zero_set_ones` | 喂 17 块（其中 `abdominals` 是 0 组/`UNTRAINED`）→ 页面文本里**有**「腹部/核心」那一块且带 `0` |
| `an_untrained_muscle_is_visibly_marked_not_hidden` | 该行有 `untrained` class；且**不在** `display:none` 的容器里（断言它被渲染，不是被过滤掉） |
| `shows_the_target_range_and_marks_below` | `chest` sets=2.0 min=10 max=20 status=BELOW → 文本含 `10`、`20`，该行 class 含 `below` |
| `shows_a_not_configured_muscle_as_no_target` | `neck` min/max=null → 文本含「未设目标」，**不显示 `null`** |
| `lists_recent_sessions` | 两条 → 文本含两次标题与日期 |
| `an_empty_recent_list_shows_a_readable_hint` | `[]` → 含「还没有训练记录」，不是空白 |
| `starting_a_workout_navigates_to_the_training_page` | 点「开始训练」→ `router.currentRoute.value.path === '/training'` |
| `a_transport_failure_shows_the_reason_instead_of_a_blank_page` | invoke reject → 文本含「连不上」（或该 msg），且 h2「今日」仍在 |
| `a_400_from_the_backend_shows_the_server_message` | 周容量返回 400 `{error:…}` → 文本含那句 error |

- [ ] **Step 2: 跑，确认红** → `pnpm test src/views/TodayView.spec.ts`

- [ ] **Step 3: 写三个文件**（`VolumePanel.vue` / `SessionList.vue` / `TodayView.vue`）

`VolumePanel.vue` 的 props 契约（Task 5 的「选肌群」也复用它拿的那份数据）：

```ts
const props = defineProps<{ volume: WeeklyVolume | null; error: string | null }>()
```

状态 → class 的映射集中在一个纯函数里（可被测试直接调用）：

```ts
export function statusClass(s: VolumeStatus): string {
  switch (s) {
    case 'IN_RANGE': return 'in-range'
    case 'BELOW': return 'below'
    case 'ABOVE': return 'above'
    case 'UNTRAINED': return 'untrained'
    case 'NOT_CONFIGURED': return 'not-configured'
    default: return 'not-configured'  // 未知状态宁可显得「未配置」，也不静默变空
  }
}
```

- [ ] **Step 4: 跑，确认绿** → `pnpm test src/views/TodayView.spec.ts`

- [ ] **Step 5: 提交**

```bash
git add frontend/src/components/VolumePanel.vue frontend/src/components/SessionList.vue \
        frontend/src/views/TodayView.vue frontend/src/views/TodayView.spec.ts
git commit -m "feat(frontend): 今日页（本周容量 + 最近训练 + 开始训练）"
```

---

## Task 5: 训练记录页（选肌群 → 选动作 → 带出上次重量 → 填组 → 写入）

**Files:**
- Modify: `frontend/src/views/TrainingView.vue`
- Test: `frontend/src/views/TrainingView.spec.ts`

### 设计要点（对照设计文档流程①）

```
选肌群 → 出动作列表 → 选动作 → 填 60 × 8 · 打勾 → 自动带出上次重量
→ 继续下一组… → 结束训练 → 写入 Session / Exercise / Set
```

- **肌群列表** = `weeklyVolume()` 的那 17 块（见「接口契约」的说明）。取不到时选择器显示错误，**不显示空列表**。
- 选中肌群 → `searchExercises({ muscleGroupId })`；搜索框输入 → `searchExercises({ q })`（**防抖 300ms**；空查询按肌群走）。
- 选中动作 → `lastPerformance(id)`：
  - 返回对象 → **预填**：按它给的组，逐组建一行，`weight`/`repetitions`/`setType` 照抄，`completed` 默认 **false**（带出的是「上次做了多少」，不是「这次已经做完」）。
  - 返回 `null`（204）→ 建一行空组，并提示「这个动作没记录过」。
- 组行：重量 / 次数 / 完成勾选 / 删除。`completed` 的勾选**就是** `completed` 字段——它决定是否计入周容量（计划 3：只算 `completed=1`）。界面上要写清这一点，否则用户不会知道「没打勾 = 不算训练量」。
- 「结束训练」→ `sessionDate` 默认今天、`title`/`perceivedState`/`note` 可选 → `recordSession(input)`：
  - 校验放在**前端也放一层**（至少 1 个动作、每个动作至少 1 组）以便即时反馈，但**服务端仍是权威**：400 的 `{error}` 要显示出来。
- 成功后：清空表单 + 显示 `已记录：1 个动作 / 3 组` + 刷新「最近训练」。
- 历史：`recentSessions()` 列表；点击 → `sessionDetail(id)` 展开只读详情。

- [ ] **Step 1: 写失败的测试**

| 测试 | 断言 |
|---|---|
| `muscle_picker_comes_from_the_weekly_volume_muscles_not_a_new_endpoint` | 只调了 `/api/volume/weekly` 与 `/api/exercises*`，**没有**调任何 `/api/muscles` |
| `a_failed_muscle_load_shows_an_error_not_an_empty_picker` | 周容量 400/传输失败 → 提示含原因；`<select>` 不存在或带 `disabled` |
| `picking_a_muscle_searches_exercises_by_muscle_group_id` | 选中 chest(1) → `invoke` 收到 path 含 `muscleGroupId=1` |
| `typing_a_query_debounces_and_searches_by_q` | 用 fake timers：连续输入 3 次，300ms 内只发 **1** 次请求，且 path 含 `q=` |
| `picking_an_exercise_prefills_the_last_performance_weights` | 204 之外：`last-performance` 返回 `[{60×8},{60×8}]` → 重量输入框的值是 `60`、`60`，次数是 `8`、`8` |
| `a_fresh_exercise_starts_with_one_empty_set_and_says_so` | 204 → 1 行空组 + 文本含「没记录过」 |
| `prefilled_sets_are_not_marked_completed_by_default` | 预填后所有勾选是**未勾**——「上次做了」≠「这次做了」 |
| `submitting_posts_the_session_input_and_shows_the_counts` | 填 2 组、勾 2 个完成 → 断言 `JSON.parse(body)` 的 `exercises[0].sets` 长度 2、`completed` 都是 `true`、`sessionDate` 是今天 |
| `a_400_shows_the_server_validation_message` | 服务端 400 `{error:'一次训练至少要有 1 个动作'}` → 文本含这句；**表单不被清空**（用户不该丢输入） |
| `a_successful_save_clears_the_form_and_refreshes_the_history` | 成功后：组行清空、文本含「已记录」、`/api/sessions/recent` 被重新调用 |
| `the_set_rows_delete_button_removes_just_that_row` | 3 行删中间那行 → 剩 2 行且重量是第 1、3 行的值（**守着「删除的是对的那一行」**，索引错位会在这条红） |
| `the_completed_checkbox_is_explained_in_the_ui` | 文本含「打勾的组才算训练量」之类说明——这条守的是设计里那条容易被误解的口径 |
| `history_detail_opens_on_click` | 点最近训练 → 调 `/api/sessions/{id}`，显示动作名与组数 |

- [ ] **Step 2: 跑，确认红** → `pnpm test src/views/TrainingView.spec.ts`

- [ ] **Step 3: 写实现**

`TrainingView.vue` 的组件内状态（不引入 store——单页用完即弃，Pinia 在这里只会增加跨页耦合）：

```ts
type Row = { weight: string; repetitions: string; completed: boolean }
const muscles = ref<MuscleVolume[]>([])
const selectedMuscleId = ref<number | null>(null)
const query = ref('')
const exercises = ref<ExerciseSummary[]>([])
const selectedExercise = ref<ExerciseSummary | null>(null)
const rows = ref<Row[]>([])
const lastPerformanceNote = ref<string>('')
const title = ref(''); const perceivedState = ref(''); const note = ref('')
const saveError = ref<string>(''); const savedSummary = ref<string>('')
const recent = ref<SessionSummary[]>([])
```

关键纯函数（便于测试、也避免模板里塞逻辑）：

```ts
export function rowsFromLastPerformance(sets: SetSnapshot[]): Row[] {
  return sets.map((s) => ({
    weight: s.weight === null ? '' : String(s.weight),
    repetitions: s.repetitions === null ? '' : String(s.repetitions),
    completed: false,   // 「上次做了」不等于「这次做了」
  }))
}

export function toSessionInput(args: {...}): SessionInput { /* 空串 → null；'' → null，不是 0 */ }
```

> **`''` → `null` 是硬要求**：`Number('') === 0`，把空重量写成 `0` 会变成一条「做了 0 公斤」的假数据，而容量核算会照单全收。

- [ ] **Step 4: 跑，确认绿** → `pnpm test src/views/TrainingView.spec.ts`

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/TrainingView.vue frontend/src/views/TrainingView.spec.ts
git commit -m "feat(frontend): 训练记录页（选肌群 → 带出上次重量 → 填组 → 写入）"
```

---

## Task 6: 端到端验收与文档

**Files:**
- Modify: `docs/superpowers/plans/2026-10-14-change-phase1-overview.md`

- [ ] **Step 1: 全绿检查**

```powershell
cd frontend
pnpm test            # 报告实际条数（计划开始时 50）
pnpm build           # 必须退 0
cd src-tauri
cargo test           # 报告实际条数（计划开始时 76 passed + 4 integration, 2 ignored）
```

- [ ] **Step 2: 真机走一遍（**必须真的读界面上的字**）**

```powershell
cd backend; mvn -B -q -DskipTests package     # 外壳要拉起 jar
cd ..\frontend; pnpm tauri dev
```

逐条核对（照着计划 1 Task 8 的教训：mock 全绿的测试证明不了真机上能跑）：

| # | 操作 | 期望 |
|---|---|---|
| 1 | 窗口起来 | 状态栏「后端已连接 · 端口 <n>」；今日页出现本周容量（17 块，含 0 组的） |
| 2 | 点「开始训练」 | 跳到训练记录页；肌群下拉有 17 项 |
| 3 | 选「胸部」 | 出现动作列表（拉伸不在其中） |
| 4 | 选一个有历史的动作 | 重量/次数被**上次的值**预填，勾选框**都没勾** |
| 5 | 勾 2 组、填 60×8 | — |
| 6 | 点「结束训练」 | 「已记录：1 个动作 / 2 组」；回今日页，本周容量里「胸部」多了 2.0 |
| 7 | 刷新窗口 | 数据还在（说明真的落库了，不是内存态） |
| 8 | 断网/杀后端后随便点一下 | 界面出现可读的错误句，**不白屏**；状态栏反映失败 |

> 第 2、6、7 条是**跨源这条硬约束的最终证明**：如果代理没接好，第 1 条之后就会是 `TypeError: Failed to fetch`——而单测因为 mock 了 `invoke` 会**全绿**。

- [ ] **Step 3: 把结果记进总览**

在「计划 3」一节后补「前端计划：今日与训练记录页」：跨源怎么定的、真机 8 条的真实结果、两个数字（测试条数、构建时间）。

- [ ] **Step 4: 提交**

```bash
git add docs/superpowers/plans/2026-10-14-change-phase1-overview.md
git commit -m "docs: 总览补前端计划的实测结果"
```

---

## 完成标准

- [ ] `cd frontend && pnpm test` 全绿，**报告实际条数**（≥ 50 + 新增）
- [ ] `cd frontend && pnpm build` 退 0
- [ ] `cd frontend/src-tauri && cargo test` 全绿，**报告实际条数**（注册表那条已同步成 3 条命令）
- [ ] 未新增后端端点；**迁移仍是 V1–V8**。后端只改了**一处**（`ApiErrors` 加 `ResponseStatusException` 的 handler）——这是「不动后端」那条的**唯一例外**，见「实施修正」1
- [ ] 未给后端加 CORS；CSP 仍是 `connect-src 'self'`；capabilities 未改
- [ ] 前端**没有**任何 `fetch`——所有后端访问都经 `invoke('api_request')`
- [ ] 今日页的「本周容量」在最近训练**之前**，且 **17 块肌群含 0 组的全部渲染**
- [ ] 训练记录页：预填的是**上次**的值、预填后**不勾**、`''` 变成 `null` 而不是 `0`
- [ ] 真机 8 条逐条核对，**把界面上的字读出来**（不是只看测试绿）
- [ ] 总览里补上前端计划的真实结果
- [ ] `git status --short` 无输出（`.commandcode/` 除外）

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| **测试全绿但真机白屏** | 单测 mock 掉了 `invoke`，跨源/CSP/capability 的问题在单测里**不可见** | Task 6 Step 2 必须真的开窗口、读界面上的字；这是计划 1 Task 8 用真机换来的教训 |
| 加了第三条命令却忘了改注册表测试 | 那条测试硬断言「恰好 2 条」，会让 `cargo test` 变红 | Task 1 Step 5 明写了要同步改；**改完顺手做一次变异**（删掉注册 → 确认红） |
| 通用代理把「有 body 的 POST」的 `Content-Length` 按字符算 | 中文注释算短了，后端会截断请求体，**看起来像「注释丢了」而不是报错** | Task 1 有一条测试专门用含中文的 body 钉字节数 |
| `path` 拼接被注入 `\r\n` | 请求行/头注入 | `validate_path` 拦掉 `\r`/`\n`/空格；有一条测试断言注入串**不出现在**请求里 |
| 把 204 当成「空正文」错误 | 「从没做过」会变成一个吓人的失败 | `parse_api_response` 先判 204 再判正文；前端 `lastPerformance` 返回 `null` |
| 空输入变成 `0` | `Number('')===0`，「没填重量」会变成「做了 0 公斤」并计入容量 | `toSessionInput` 里 `'' → null`，有一条测试钉 |
| 肌群列表依赖周容量端点 | 那个端点挂了选择器就没数据 | Task 5 明确「失败显示错误，不显示空列表」并有一条测试 |
| 容量面板把 0 组肌群藏起来 | 设计文档要求这类提醒「躲不掉」 | Task 4 有一条测试断言 0 组肌群**被渲染**且带 `untrained` class |
| 语义色把 `--warn` 与 `--danger` 设成同值 | 「练少了」与「练过量」在界面上一模一样 | `theme.spec.ts` 有一条断言四个语义色两两不同 |

## 非目标（本计划不做）

- **不做饮食记录页**（`DietView.vue` 仍是占位）——那是另一份计划
- **不做人体图 / 进步曲线 / 身体数据 / 智能体页**——各自成计划
- **不做训练的修改与删除**——设计的安全线是「只增改不删」；改训练等智能体那一步一起做
- **不做增量保存**（训练中逐字段 PATCH）——本计划仍是「一次写完」，与计划 3 的后端一致
- **不做离线缓存 / 乐观更新**——本地服务，先要正确
- **不做昼夜主题、不做图表库**——设计文档封板「只做深色主题」；容量用文字 + 颜色，不引图表依赖
- **不动后端**：不加 CORS、不加端点、不改迁移

---

## 实施修正（2026-10-19，实现过程中发现，逐条对照上文）

计划自己也会失真——这一节记下**实际做的时候**与上面写的不一样的地方，以及原因。

1. **404 的中文原因到不了前端（真机发现 → 已修）。** 上面 Task 5 的测试里写的是
   「404 → 文本含『找不到这次训练』」。真机实测（headless 代理 e2e，见 backend.rs）拿到的
   404 正文是 Spring 的**默认错误体**：`{"timestamp":…,"status":404,"error":"Not Found","path":…}`
   ——`TrainingController` 那句中文 reason **根本不出现**（Spring 默认
   `server.error.include-message=never`），前端界面上只有英文的 `Not Found`。

   **修法（与本节初稿写的不同）**：初稿写「① 后端 `include-message: always`；② 前端 `callApi`
   也读 `message`」。实际**没有**走那条——它会让 404 的正文长成 Spring 自己的字段布局，与 400 的
   `{error}` 不一致，前端得同时认两种形状。改成给 `ApiErrors` 补第三个 handler：把
   `ResponseStatusException` 收进**同一套** `{error}` 形状、并**保留原状态码**（404 还得是 404）。
   **前端一行没改**（它读的 `error` 键从此有值了）。真机复测：
   400 正文 = `{"error":"一次训练至少要有 1 个动作"}`、404 正文 = `{"error":"找不到这次训练: 999999"}`。

   ⚠️ **这是本计划「不动后端」那条完成标准的唯一例外**。理由：真机跑出来的用户可见缺陷，
   修法只有一处、且让错误契约变得更一致。后端测试 213 → 214。
2. **0 组肌群的样本选错了。** 上面 Task 4 写「其中 `abdominals` 是 0 组/`UNTRAINED`」。
   按真机夹具（地板卧推的**次要**肌群是腹直肌与肱三头肌），`abdominals` 实际是
   **1.0/BELOW**。0 组的样本改用 **`lats`（背阔肌，0.0/UNTRAINED）**，
   没配目标的样本仍是 `neck`（null/NOT_CONFIGURED）。夹具本身是从真后端抓的，不是手写。
3. **`parse_api_response` 的 204 提前返回是死代码（E 型）。** 初版写的是先判
   `if code == 204 { return 空 }`。实测发现：没有那段，204 也一样得到空 body
   （空正文本来就是合法的 UTF-8），所以**没有任何测试能杀掉它**——典型
   「死代码冒充保护」。改成：**不做任何正文校验**（与 `parse_health_response` 的契约不同），
   并补一条「非 204 的空正文也必须 `Ok`」的测试。这样那两条 204 测试才真正是
   「有人照抄 health 那条空正文检查」的杀手（变异实测：加回检查 → 3 条红）。
4. **注册表测试改名，并要求注册表保持单行。** `both_commands_are_registered_in_the_invoke_handler`
   → `every_command_is_registered_in_the_invoke_handler`（现在是三条命令）。它是按
   `lines().find(...)` 读**一行**的，所以 `generate_handler![...]` 必须写在一行里——
   拆成多行会让它读不到命令名而变红。已在 `lib.rs` 的注册表上方注明。
5. **Task 6 Step 2「真机开窗口读界面上的字」没执行。** 本会话没有可视窗口，而前端访问后端
   全靠 `invoke`，纯浏览器里跑不起来。**能自动化的那一半做了**：新增 `#[ignore]` 的
   `end_to_end_proxy_reads_and_reports_statuses_from_the_real_backend`，真的拉起 jar、
   用外壳自己的传输代码核对 200/204/400/404 与查询串穿透——这正是「代理接错了整条链路就瘫」
   的那部分。**GUI 的 8 条仍待人工**（`pnpm tauri dev`）。
6. **写入路径的 e2e 只覆盖到 400。** `Backend::spawn` 不设 `CHANGE_DATA_DIR`，会落到使用者
   **真实的库**；成功的写入会把一次训练留在别人的数据里，所以那条 e2e 里刻意不写成功。
   写入路径中属于外壳的部分（POST + `Content-Length` + body）由 400 覆盖——Spring 必须真的
   把请求体读进来解析过才回得出那句校验文案。成功写入多出来的是后端事务行为，由计划 3 覆盖。

