import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BackendStatusBar from './BackendStatusBar.vue'

// invoke 是唯一的跨进程边界：**两条命令都走它**
// （`get_backend_status` 拿外壳状态、`get_health` 拿缓存文案）。
// 所以 mock 必须按**命令名**分派——一个笼统的 mockResolvedValue 会让两条命令
// 拿到同一个返回值，测试就再也分不清是「哪条命令被调了」。
//
// 这个文件此前 stub 的是 `fetch`（health 由前端自己发 HTTP）。
// 真机验收发现那条路径被 CORS 拦掉（页面的源与后端随机端口不同源），
// 于是 health 改由外壳代发，这里跟着改成 mock invoke。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

const STARTING = { status: 'starting', port: null, message: '正在启动后端…' }
const READY = { status: 'ready', port: 51234, message: '后端已连接 · 端口 51234' }
const FAILED = { status: 'failed', port: null, message: '后端启动超时' }

/** 默认的 health 应答：正常响应。 */
function healthOk(cache = '缓存：未启用（配置为关闭）') {
  return { status: 'up', cache }
}

/**
 * 按命令名分派 invoke。
 *
 * `shell` 是 `get_backend_status` 的返回值序列（用完一直返回最后一个），
 * `health` 省略时**默认让 `get_health` 失败**——这不是偷懒，是刻意的：
 * 这个组件在**没就绪**时一次都不该调用 `get_health`，所以对绝大多数用例来说
 * 「被调到」本身就是缺陷。默认 reject 让它以「界面出现一句错误」的形式
 * 暴露出来，而不是被一个喂饱了的 mock 悄悄掩盖。
 */
function dispatch(
  shell: unknown[],
  health: unknown | (() => unknown) = new Error('测试没准备 get_health 的应答（说明它被调用了）'),
) {
  const shellCalls: unknown[] = []
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (cmd: unknown) => {
    if (cmd === 'get_backend_status') {
      const i = Math.min(shellCalls.length, shell.length - 1)
      shellCalls.push(shell[i])
      return shell[i]
    }
    if (cmd === 'get_health') {
      const v = typeof health === 'function' ? (health as () => unknown)() : health
      // reject 用 Error 表达：invoke 失败时前端拿到的就是它
      if (v instanceof Error) throw v
      return v
    }
    throw new Error(`测试没有为命令 ${String(cmd)} 准备应答`)
  })
  return shellCalls
}

/** 只数某一条命令被调了几次。 */
function callCount(cmd: string) {
  return invokeMock.mock.calls.filter((c) => c[0] === cmd).length
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
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    invokeMock.mockReset()
  })

  it('初始是加载态', async () => {
    dispatch([STARTING])
    const w = mount(BackendStatusBar)
    await flushPromises()

    expect(w.text()).toContain('正在启动后端…')
    expect(w.find('.status').classes()).toContain('loading')
  })

  it('拿到端口后变就绪，并带上端口号', async () => {
    dispatch([STARTING, READY])
    const w = mount(BackendStatusBar)
    await flushPromises()
    expect(w.find('.status').classes()).toContain('loading')

    await advance(500)

    expect(w.text()).toContain('51234')
    expect(w.find('.status').classes()).toContain('ok')
  })

  it('显示 get_health 返回的缓存文案', async () => {
    dispatch([READY], healthOk('缓存：未启用（配置为关闭）'))
    const w = mount(BackendStatusBar)
    await advance(0)

    expect(w.text()).toContain('缓存：未启用（配置为关闭）')
  })

  it('health 失败时不崩，并说明缓存状态取不到', async () => {
    // 旧版这里 stub 的是 `fetch` 的 reject；现在等价物是 `get_health` 的 reject
    // （Rust 侧把 CORS 那条死路换成了「连不上 / 超时 / 非 2xx / JSON 畸形」四句话）。
    dispatch([READY], new Error('连不上后端 127.0.0.1:51234：拒绝连接'))
    const w = mount(BackendStatusBar)
    await advance(0)

    // 外壳那条路径仍然正常显示，窗口没被拖垮
    expect(w.text()).toContain('51234')
    expect(w.text()).toContain('缓存状态取不到')
    expect(w.text()).toContain('连不上后端')
  })

  it('与外壳通信失败时显示失败态与原因', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async (cmd: unknown) => {
      if (cmd === 'get_backend_status') throw new Error('外壳没响应')
      return healthOk()
    })
    const w = mount(BackendStatusBar)
    await advance(0)

    expect(w.find('.status').classes()).toContain('error')
    expect(w.text()).toContain('外壳没响应')
  })

  it('启动期不去问 health（还没就绪，端口还没有意义）', async () => {
    // 守卫曾经写的是 `state.value.port === null`——改成 invoke 之后端口不再由
    // 前端关心，守卫改成看 status。
    //
    // **这条用例到底守住了什么（变异实测后的诚实结论）**：
    // 它**不是** `pollHealth` 里那行 `if (state.value.status !== 'ready') return`
    // 的杀手。那行今天删掉照样全绿——因为 `pollHealth` 的两个调用点都在
    // `onReady` 之后，启动期根本走不到那里，也就是说那行守卫**不可达**。
    // （这一点被专门验证过：把守卫替换成注释后单跑本文件，10 passed / 0 failed；
    //  同时用一份临时 spec 打点确认启动期只有 `get_backend_status` 被调用。）
    //
    // 它真正守的是**调用点的位置**：`pollHealth` 不能落进启动期。谁把它挪进
    // `tick()`（那是个很自然的想法），这条就会红——`get_health` 的调用次数
    // 会从 0 变成 1 以上，而且状态栏右侧会从「后端启动需要几秒，请稍候」
    // 变成「缓存状态取不到：…」。
    //
    // 断言因此分两半：接口上的调用次数（机制）与界面文案（后果）。
    // 只断言前者的话，「样式/文案那侧坏了」不会被发现；只断言后者的话，
    // 一个把 `get_health` 调了却把结果丢掉、只在 catch 里写了别的文案的实现
    // 也能蒙混过去。
    dispatch([STARTING])
    const w = mount(BackendStatusBar)
    await advance(5000)

    expect(callCount('get_health')).toBe(0)
    expect(callCount('get_backend_status')).toBeGreaterThan(1)
    expect(w.text()).toContain('后端启动需要几秒，请稍候')
    expect(w.text()).not.toContain('缓存状态取不到')
    expect(w.text()).not.toContain('缓存：')
  })

  it('就绪之后不再问外壳状态（它只会一直是 ready）', async () => {
    // 每 500ms 问一次外壳状态毫无意义：后端的状态是单向的
    // （Rust 侧 a_ready_state_is_never_overwritten_by_a_later_failure 守着）。
    dispatch([STARTING, READY])
    const w = mount(BackendStatusBar)
    await flushPromises() // 第一问：starting
    await advance(500)    // 第二问：ready → 停掉外壳轮询
    expect(w.find('.status').classes()).toContain('ok')
    const shellAtReady = callCount('get_backend_status')

    await advance(60_000) // 稳态跑一分钟

    expect(callCount('get_backend_status')).toBe(shellAtReady)
    expect(w.find('.status').classes()).toContain('ok')
  })

  it('就绪之后 health 按 5 秒节奏刷新，而不是 500ms', async () => {
    // 反面断言：如果 health 跟着 500ms 的节拍走，60 秒里会打 120 次。
    dispatch([READY])
    mount(BackendStatusBar)
    await advance(0)

    await advance(60_000)

    // 就绪那一刻 1 次 + 60 秒 / 5 秒 = 12 次，共 13 次；留一点余量容忍边界。
    const n = callCount('get_health')
    expect(n).toBeLessThanOrEqual(14)
    expect(n).toBeGreaterThanOrEqual(12)
  })

  it('失败之后也不再问外壳状态（失败同样是单向的）', async () => {
    // 变异测试发现这条缺口：`tick` 里那句 stopShellPolling 在 ready 路径上
    // 被 onReady() 内部的停表**掩盖**了，只有 failed 路径能证明它真的在起作用。
    // 外壳的失败状态同样是终态——Rust 侧没有任何按时间重试的机制，
    // 再问一万次也只会得到同一个 message。
    dispatch([STARTING, FAILED])
    const w = mount(BackendStatusBar)
    await flushPromises() // 第一问：starting
    await advance(500)    // 第二问：failed
    expect(w.find('.status').classes()).toContain('error')
    expect(callCount('get_backend_status')).toBe(2)

    await advance(60_000)

    expect(callCount('get_backend_status')).toBe(2)
    // 失败态没有就绪，health 一次都不该打
    expect(callCount('get_health')).toBe(0)
  })

  it('卸载后不再轮询', async () => {
    dispatch([STARTING]) // 一直停在启动态：不做这条断言的话轮询会自停，测试就失去意义
    const w = mount(BackendStatusBar)
    await advance(1000)
    const before = invokeMock.mock.calls.length
    // 这条前置断言很重要：若轮询已经自己停了，后面的「不再增加」就是恒真的。
    expect(before).toBeGreaterThan(1)

    w.unmount()
    await advance(10_000)

    expect(invokeMock.mock.calls.length).toBe(before)
  })
})
