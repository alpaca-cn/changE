import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BackendStatusBar from './BackendStatusBar.vue'

// invoke 与 fetch 都是跨进程边界，必须 mock——但它们之间的**时序**
// 才是这个组件的核心行为，所以每个用例都显式排好返回顺序。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

const STARTING = { status: 'starting', port: null, message: '正在启动后端…' }
const READY = { status: 'ready', port: 51234, message: '后端已连接 · 端口 51234' }

/** 让 `get_backend_status` 依次返回给定状态，用完之后一直返回最后一个。 */
function shellReturns(...states: unknown[]) {
  invokeMock.mockReset()
  states.forEach((s) => invokeMock.mockResolvedValueOnce(s))
  const last = states[states.length - 1]
  invokeMock.mockResolvedValue(last)
}

/** 让 /api/health 返回一个正常的健康响应。 */
function healthOk(cache = '缓存：未启用（配置为关闭）') {
  return vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'up', cache }) })
}

/** 推进 fake 时间并把这期间排队的 promise 全部冲干净。 */
async function advance(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
  await flushPromises()
}

describe('后端状态栏', () => {
  beforeEach(() => {
    // 必须先装 fake timers 再 mount：mount 里就会调一次 tick 并注册 interval。
    vi.useFakeTimers()
    vi.stubGlobal('fetch', healthOk())
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('初始是加载态', async () => {
    shellReturns(STARTING)
    const w = mount(BackendStatusBar)
    await flushPromises()

    expect(w.text()).toContain('正在启动后端…')
    expect(w.find('.status').classes()).toContain('loading')
  })

  it('拿到端口后变就绪，并带上端口号', async () => {
    shellReturns(STARTING, READY)
    const w = mount(BackendStatusBar)
    await flushPromises()
    expect(w.find('.status').classes()).toContain('loading')

    await advance(500)

    expect(w.text()).toContain('51234')
    expect(w.find('.status').classes()).toContain('ok')
  })

  it('显示 /api/health 返回的缓存文案', async () => {
    vi.stubGlobal('fetch', healthOk('缓存：未启用（配置为关闭）'))
    shellReturns(READY)
    const w = mount(BackendStatusBar)
    await advance(0)

    expect(w.text()).toContain('缓存：未启用（配置为关闭）')
  })

  it('health 失败时不崩，并说明缓存状态取不到', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('connect ECONNREFUSED')))
    shellReturns(READY)
    const w = mount(BackendStatusBar)
    await advance(0)

    // 外壳那条路径仍然正常显示，窗口没被拖垮
    expect(w.text()).toContain('51234')
    expect(w.text()).toContain('缓存状态取不到')
    expect(w.text()).toContain('ECONNREFUSED')
  })

  it('与外壳通信失败时显示失败态与原因', async () => {
    invokeMock.mockReset()
    invokeMock.mockRejectedValue(new Error('外壳没响应'))
    const w = mount(BackendStatusBar)
    await advance(0)

    expect(w.find('.status').classes()).toContain('error')
    expect(w.text()).toContain('外壳没响应')
  })

  it('就绪之后不再问外壳状态（它只会一直是 ready）', async () => {
    // 每 500ms 问一次、每次再打一次 health，就是每秒 2 个请求永远不停。
    // 后端的状态是单向的（Rust 侧 a_ready_state_is_never_overwritten_by_a_later_failure 守着），
    // 所以就绪之后就没有必要再问了。
    shellReturns(STARTING, READY)
    const w = mount(BackendStatusBar)
    await flushPromises() // 第一问：starting
    await advance(500)    // 第二问：ready → 停掉外壳轮询
    expect(w.find('.status').classes()).toContain('ok')
    const callsAtReady = invokeMock.mock.calls.length

    await advance(60_000) // 稳态跑一分钟

    expect(invokeMock.mock.calls.length).toBe(callsAtReady)
    expect(w.find('.status').classes()).toContain('ok')
  })

  it('稳态下 health 按 5 秒节奏刷新，而不是 500ms', async () => {
    // 反面断言：如果 health 跟着 500ms 的节拍走，60 秒里会打 120 次。
    const fetchMock = healthOk()
    vi.stubGlobal('fetch', fetchMock)
    shellReturns(READY)
    mount(BackendStatusBar)
    await advance(0)

    await advance(60_000)

    // 就绪那一刻 1 次 + 60 秒 / 5 秒 = 12 次，共 13 次；留一点余量容忍边界。
    expect(fetchMock.mock.calls.length).toBeLessThanOrEqual(14)
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(12)
  })

  it('失败之后也不再问外壳状态（失败同样是单向的）', async () => {
    // 变异测试发现这条缺口：`tick` 里那句 stopShellPolling 在 ready 路径上
    // 被 onReady() 内部的停表**掩盖**了，只有 failed 路径能证明它真的在起作用。
    // 外壳的失败状态同样是终态——Rust 侧没有任何按时间重试的机制，
    // 再问一万次也只会得到同一个 message。
    shellReturns(STARTING, { status: 'failed', port: null, message: '后端启动超时' })
    const w = mount(BackendStatusBar)
    await flushPromises() // 第一问：starting
    await advance(500)    // 第二问：failed
    expect(w.find('.status').classes()).toContain('error')
    const callsAtFailed = invokeMock.mock.calls.length
    expect(callsAtFailed).toBe(2)

    await advance(60_000)

    expect(invokeMock.mock.calls.length).toBe(callsAtFailed)
  })

  it('卸载后不再轮询', async () => {
    shellReturns(STARTING) // 一直停在启动态：不做这条断言的话轮询会自停，测试就失去意义
    const w = mount(BackendStatusBar)
    await advance(1000)
    const callsBeforeUnmount = invokeMock.mock.calls.length
    // 这条前置断言很重要：若轮询已经自己停了，后面的「不再增加」就是恒真的。
    expect(callsBeforeUnmount).toBeGreaterThan(1)

    w.unmount()
    await advance(10_000)

    expect(invokeMock.mock.calls.length).toBe(callsBeforeUnmount)
  })
})
