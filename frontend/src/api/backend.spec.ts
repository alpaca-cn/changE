import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { checkHealth, describeState, type BackendState } from './backend'

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
  beforeEach(() => { vi.restoreAllMocks() })
  afterEach(() => { vi.restoreAllMocks() })

  it('把 /api/health 的 cache 字段取出来', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ status: 'up', cache: '缓存：未启用（配置为关闭）' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const r = await checkHealth(51234)
    expect(r).toEqual({ status: 'up', cache: '缓存：未启用（配置为关闭）' })
    expect(fetchMock).toHaveBeenCalledWith('http://127.0.0.1:51234/api/health')
  })

  it('HTTP 非 2xx 时抛错', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }))
    await expect(checkHealth(1)).rejects.toThrow('500')
  })

  it('网络失败时抛错（端口不对或后端还没起）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('connect ECONNREFUSED')))
    await expect(checkHealth(1)).rejects.toThrow('ECONNREFUSED')
  })
})
