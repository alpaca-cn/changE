import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// `checkHealth` 现在走 Tauri 的 invoke，不再自己发 HTTP。
//
// 这条改动的原因是**真机验收发现的缺陷**：页面的源（dev 是
// `http://127.0.0.1:1420`，打包后是 `tauri://localhost`）与后端随机端口不同源，
// 而响应没有 `Access-Control-Allow-Origin`，浏览器把响应拦掉，前端只看到
// `TypeError: Failed to fetch`。修法是把这条请求交给外壳（Rust）去发——
// 详见 `src-tauri/src/http.rs` 的模块注释。
//
// 所以这个文件不再 stub `fetch`，改为 mock `@tauri-apps/api/core` 的 `invoke`。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { checkHealth, describeState, type BackendState } from './backend'

const invokeMock = vi.mocked(invoke)

describe('describeState', () => {
  it('starting 时给出加载中文案', () => {
    const s: BackendState = { status: 'starting', port: null, message: '正在启动后端…' }
    expect(describeState(s).tone).toBe('loading')
  })

  it('ready 时带上端口', () => {
    const s: BackendState = { status: 'ready', port: 51234, message: '' }
    const d = describeState(s)
    expect(d.tone).toBe('ok')
    expect(d.text).toContain('51234')
  })

  it('failed 时显示原因', () => {
    const s: BackendState = { status: 'failed', port: null, message: '后端启动超时' }
    const d = describeState(s)
    expect(d.tone).toBe('error')
    expect(d.text).toBe('后端启动超时')
  })

  it('遇到没见过的状态时显示出来，而不是渲染成空白', () => {
    // Rust 侧加了第四个 BackendStatus 变体而忘了同步这里时，
    // 旧的穷尽 switch 会返回 undefined：状态栏一片空白、没有任何提示。
    // 这条测试守的是「宁可显示看不懂，也不要静默变空」。
    //
    // **它与「喂想象出来的对象去测正常路径」不同**：那种测试是拿假数据
    // 冒充真实输入（假数据一变，测试仍然绿，等于没测）。这里喂的假状态
    // 不是要冒充哪个真实状态，而是**专门去走 `default` 这个真实存在的
    // 防御分支**——它测的是代码里那条防御路径本身，不是某条正常路径。
    const weird = { status: 'hibernating', port: null, message: '' } as unknown as BackendState
    const d = describeState(weird)
    expect(d.tone).toBe('error')
    expect(d.text).toContain('hibernating')
  })

  // 跨语言契约：Rust 的 BackendState 与这里的 interface 是**两处手写定义**，
  // 它们可以漂移而没有任何东西会报警（TS 不校验线上来的 JSON）。
  // 唯一能证明两边形状一致的方式，是拿 Rust 真的序列化出来的字符串喂进来。
  //
  // 这三段 JSON 是从 Rust 侧实测拿到的（见 src-tauri/src/backend.rs 的
  // the_state_serializes_with_status_as_a_plain_string），不是手写的。
  // 原文（原样复制，未改一个字符）：
  //   {"status":"starting","port":null,"message":"正在启动后端…"}
  //   {"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}
  //   {"status":"failed","port":null,"message":"后端启动失败：进程已退出（退出码 1），但没有输出 CHANGE_PORT=<端口> 这一行。请确认 jar 路径与 Java 可用，或查看后端自己的日志。"}
  it('Rust 实际输出的三种 JSON 都能被正确解读', () => {
    const fromRust: string[] = [
      '{"status":"starting","port":null,"message":"正在启动后端…"}',
      '{"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}',
      '{"status":"failed","port":null,"message":"后端启动失败：进程已退出（退出码 1），但没有输出 CHANGE_PORT=<端口> 这一行。请确认 jar 路径与 Java 可用，或查看后端自己的日志。"}',
    ]
    const tones = fromRust.map((j) => describeState(JSON.parse(j) as BackendState).tone)
    expect(tones).toEqual(['loading', 'ok', 'error'])
  })

  it('Rust 的 ready 里的端口能读出来（不是 undefined）', () => {
    // 单独再钉一次字段名与类型：如果 Rust 那边把 `port` 改名成 `backendPort`
    // 或加进 `#[serde(skip_serializing_if)]`，上面那条只断言 tone 的测试
    // 依然会绿（tone 只由 status 决定），而这里会红。
    const s = JSON.parse(
      '{"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}',
    ) as BackendState
    expect(s.port).toBe(8080)
    expect(s.message).toBe('后端已连接 · 端口 8080')
    expect(describeState(s).text).toBe('后端已连接 · 端口 8080')
  })
})

describe('checkHealth', () => {
  beforeEach(() => {
    invokeMock.mockReset()
  })
  afterEach(() => {
    invokeMock.mockReset()
    vi.restoreAllMocks()
  })

  it('调用 get_health 命令（不再自己发 HTTP）', async () => {
    invokeMock.mockResolvedValue({ status: 'up', cache: '缓存：未启用（配置为关闭）' })

    const r = await checkHealth()

    expect(r).toEqual({ status: 'up', cache: '缓存：未启用（配置为关闭）' })
    // 命令名是**跨语言契约**：Rust 侧 `lib.rs` 的 `generate_handler![]` 里
    // 少注册 `get_health`，前端这里就会 reject（Tauri 报 command not found）。
    // 变异测试里「lib.rs 里不注册 get_health」就是靠这一条（以及组件那条）抓的。
    expect(invokeMock).toHaveBeenCalledWith('get_health')
  })

  it('不再传端口——端口是外壳的事，前端不该经手', async () => {
    // 这条是这次改动**语义上的重点**：旧接口是 `checkHealth(port)`，
    // 端口从 `get_backend_status` 流到前端、再由前端拼进 URL。
    // 现在端口留在 Rust 侧（`get_health` 自己从 state 里取），
    // 所以这个函数**一个参数都不收**。把签名改回去会让这条红。
    invokeMock.mockResolvedValue({ status: 'up', cache: 'x' })
    expect(checkHealth).toHaveLength(0)
    await checkHealth()
    expect(invokeMock).toHaveBeenCalledTimes(1)
    // 任何参数都不该出现在调用里
    expect(invokeMock.mock.calls[0]).toEqual(['get_health'])
  })

  it('invoke reject 时抛错（对应旧的「HTTP 非 2xx」那条）', async () => {
    // **保留旧断言的语义**：以前 500 会让 `fetch` 那条路径抛
    // `Error("后端返回 500")`；现在非 2xx 由 Rust 侧判掉并变成 invoke 的 reject，
    // 前端这条路径的职责只剩下「原样把错误抛给调用方」。
    // 所以这里断言的是「reject 会穿透」，而不是某个具体文案。
    invokeMock.mockRejectedValue(new Error('后端健康检查返回了 HTTP 500（期望 200）'))
    await expect(checkHealth()).rejects.toThrow('500')
  })

  it('通信失败时的原因原样穿透（状态栏要把它显示出来）', async () => {
    // 旧的「网络失败时抛错（端口不对或后端还没起）」那条的等价物：
    // 现在是外壳报回来的话——「连不上」「超时」都是它给的。
    invokeMock.mockRejectedValue(new Error('连不上后端 127.0.0.1:51234：拒绝连接'))
    await expect(checkHealth()).rejects.toThrow('连不上后端')
  })

  it('Rust 真的序列化出来的 Health JSON 字段名，前端能读出来', async () => {
    // **跨语言契约**（与 describeState 那两条同一个套路）。
    //
    // 下面这行 JSON 是 Rust 侧**实测**拿到的，原样复制**一个字符都没改**：
    // 来自 `src-tauri/src/http.rs` 的
    // `the_health_struct_serializes_with_the_field_names_the_frontend_reads`
    // （`serde_json::to_string(&Health{...})` 的输出），以及真机 e2e
    // `end_to_end_reads_a_real_health_response_through_the_shell_http_path`
    // 里 `GET /api/health` 打印出来的 `cache` 值。
    //
    // 这条专门抓**跨语言字段名漂移**：Rust 那边把 `cache` 改成 `cach`、
    // 或给它加上 `#[serde(skip_serializing_if)]`，则 JSON 里就没有 `cache` 键，
    // 而 `as Health` 是个**类型断言、不做任何校验**——不报错，直接是 undefined。
    // 状态栏右侧于是渲染成空白（`v-if="cacheText"` 为假），没有任何提示。
    const fromRust = '{"status":"up","cache":"缓存：未启用（配置为关闭）"}'
    invokeMock.mockResolvedValue(JSON.parse(fromRust))

    const h = await checkHealth()

    expect(h.status).toBe('up')
    expect(h.cache).toBe('缓存：未启用（配置为关闭）')
    // 逐键校验：漏一个键、或键名漂了，这里就红
    expect(Object.keys(h).sort()).toEqual(['cache', 'status'])
  })

  it('字段名漂移（cache → cach）时不会静默当成空文案', async () => {
    // 反面：真的漂了的时候，前端**读不到**（`undefined`）。
    // 这条不是在夸现有的实现——它是在把「漂移的后果」钉下来：
    // 后果是 undefined，而不是一个看起来正常的空串。
    // **Rust 侧那一条测试（rejects_a_misspelled_cache_field）是真正的守卫**，
    // 它在 JSON 解析那一步就报错；这里只是证明「万一漏过去，前端不会自作多情」。
    invokeMock.mockResolvedValue({ status: 'up', cach: '缓存：未启用（配置为关闭）' })
    const h = await checkHealth()
    expect(h.cache).toBeUndefined()
  })
})
