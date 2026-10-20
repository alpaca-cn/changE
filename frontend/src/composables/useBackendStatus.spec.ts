import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { useBackendStatus } from './useBackendStatus'

// 与 BackendStatusBar.spec.ts 同一个跨进程边界：两条命令都走 invoke，
// 必须按命令名分派，否则分不清是哪条命令被调了。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

const STARTING = { status: 'starting', port: null, message: '正在启动后端…' }
const READY = { status: 'ready', port: 51234, message: '后端已连接 · 端口 51234' }
const FAILED = { status: 'failed', port: null, message: '后端启动超时' }

function healthOk(cache = '缓存：未启用（配置为关闭）') {
  return { status: 'up', cache }
}

/**
 * 与页脚 spec 里同款的按命令名分派。
 *
 * `health` 省略时**默认 reject**：启动期一次都不该调用 `get_health`，
 * 被调到就应该以错误的形式暴露出来，而不是被喂饱的 mock 掩盖。
 */
let shellCalls: unknown[] = []

function dispatch(
  shell: unknown[],
  health: unknown | (() => unknown) = new Error('测试没准备 get_health 的应答（说明它被调用了）'),
) {
  shellCalls = []
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (cmd: unknown) => {
    if (cmd === 'get_backend_status') {
      // 用下标取序列，用完一直返回最后一个
      const i = Math.min(shellCalls.length, shell.length - 1)
      shellCalls.push(shell[i])
      return shell[i]
    }
    if (cmd === 'get_health') {
      const v = typeof health === 'function' ? (health as () => unknown)() : health
      if (v instanceof Error) throw v
      return v
    }
    throw new Error(`测试没有为命令 ${String(cmd)} 准备应答`)
  })
  return shellCalls
}

function callCount(cmd: string) {
  return invokeMock.mock.calls.filter((c) => c[0] === cmd).length
}

async function advance(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
  await flushPromises()
}

/**
 * 用 render 函数而不是模板字符串：测试环境没有运行时模板编译器
 * （App.spec.ts 的路由桩同理），字符串模板会静默渲染成空。
 */
function makeHost(healthPolling = true) {
  return defineComponent({
    setup() {
      const { state, desc, cacheText, cacheError, retry } = useBackendStatus({ healthPolling })
      return () =>
        h('div', [
          h('span', { class: 'probe-status' }, state.value.status),
          h('span', { class: 'probe-text' }, desc.value.text),
          h('span', { class: 'probe-cache' }, cacheText.value),
          h('span', { class: 'probe-cache-error' }, cacheError.value),
          h('button', { class: 'probe-retry', onClick: retry }, 'retry'),
        ])
    },
  })
}

describe('useBackendStatus', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    invokeMock.mockReset()
  })

  it('挂载后立刻问一次外壳状态，启动期按 500ms 继续问', async () => {
    dispatch([STARTING])
    const w = mount(makeHost())
    await flushPromises()

    expect(w.find('.probe-status').text()).toBe('starting')
    expect(callCount('get_backend_status')).toBe(1)

    await advance(1000) // 500ms + 1000ms 两个节拍
    expect(callCount('get_backend_status')).toBe(3)
  })

  it('就绪后停掉外壳轮询，并立刻查一次 health（之后按 5 秒）', async () => {
    dispatch([STARTING, READY], healthOk('缓存：已连接'))
    const w = mount(makeHost())
    await flushPromises()
    expect(w.find('.probe-status').text()).toBe('starting')

    await advance(500)

    expect(w.find('.probe-status').text()).toBe('ready')
    expect(w.find('.probe-text').text()).toContain('51234')
    expect(w.find('.probe-cache').text()).toBe('缓存：已连接')
    expect(callCount('get_backend_status')).toBe(2)

    // 外壳状态不再问；health 按 5 秒节奏走
    await advance(10_000)
    expect(callCount('get_backend_status')).toBe(2)
    expect(callCount('get_health')).toBe(3) // 就绪那一刻 1 次 + 5s、10s 各 1 次
  })

  it('healthPolling: false 时到了就绪也不查 health，外壳轮询照样停', async () => {
    // 启动门用这个模式：只要「能渲染了吗」。页脚自己会查缓存，
    // 两边都查就会把稳态开销从 0.2 次/秒翻成 0.4 次/秒。
    dispatch([STARTING, READY]) // health 默认 reject
    const w = mount(makeHost(false))
    await flushPromises()

    await advance(500)
    expect(w.find('.probe-status').text()).toBe('ready')

    await advance(60_000)
    expect(callCount('get_health')).toBe(0)
    expect(callCount('get_backend_status')).toBe(2)
    // 没有错误文案：不是「health 调了又失败」，是压根没调
    expect(w.find('.probe-cache-error').text()).toBe('')
  })

  it('失败即终态：不再问外壳状态，也不查 health', async () => {
    dispatch([STARTING, FAILED])
    const w = mount(makeHost())
    await flushPromises()
    await advance(500)

    expect(w.find('.probe-status').text()).toBe('failed')
    expect(w.find('.probe-text').text()).toContain('后端启动超时')
    expect(callCount('get_backend_status')).toBe(2)

    await advance(60_000)
    expect(callCount('get_backend_status')).toBe(2)
    expect(callCount('get_health')).toBe(0)
  })

  it('invoke 抛错时落成失败态，并把原因写进 message', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async (cmd: unknown) => {
      if (cmd === 'get_backend_status') throw new Error('外壳没响应')
      return healthOk()
    })
    const w = mount(makeHost())
    await advance(0)

    expect(w.find('.probe-status').text()).toBe('failed')
    expect(w.find('.probe-text').text()).toContain('与外壳通信失败')
    expect(w.find('.probe-text').text()).toContain('外壳没响应')
  })

  it('retry 从失败态重新开始轮询（不是等下一次自动重试）', async () => {
    dispatch([STARTING, FAILED, READY], healthOk('缓存：已连接'))
    const w = mount(makeHost())
    await flushPromises()
    await advance(500) // 第二问：failed
    expect(w.find('.probe-status').text()).toBe('failed')
    const before = callCount('get_backend_status')

    await w.find('.probe-retry').trigger('click')
    await flushPromises()

    // 点下那一刻就又问了一次，而不是等 500ms 的定时器
    expect(callCount('get_backend_status')).toBeGreaterThan(before)
    expect(w.find('.probe-status').text()).toBe('ready')
    // health 轮询也重新起来了（这一份实例默认 healthPolling: true）
    expect(w.find('.probe-cache').text()).toBe('缓存：已连接')

    // 恢复就绪后外壳轮询停住，不再继续增长
    const afterReady = callCount('get_backend_status')
    await advance(5000)
    expect(callCount('get_backend_status')).toBe(afterReady)
  })

  it('卸载后不再轮询', async () => {
    dispatch([STARTING]) // 一直停在启动态，否则轮询会自停
    const w = mount(makeHost())
    await advance(1000)
    const before = invokeMock.mock.calls.length
    expect(before).toBeGreaterThan(1)

    w.unmount()
    await advance(10_000)

    expect(invokeMock.mock.calls.length).toBe(before)
  })
})
