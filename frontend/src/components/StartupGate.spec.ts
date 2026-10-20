import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import StartupGate from './StartupGate.vue'
import type { BackendState } from '../api/backend'
// 用 `?raw` 读源码而不是 node:fs：jsdom 下 Vite 会把 node:fs 换成空壳
// （见 styles/theme.spec.ts 顶部那段注释），而 `?raw` 在构建/测试两个环境都只是字符串。
import startupGateSource from './StartupGate.vue?raw'
import appSource from '../App.vue?raw'

const STARTING: BackendState = { status: 'starting', port: null, message: '正在启动后端…' }
const FAILED: BackendState = { status: 'failed', port: null, message: '后端启动超时' }

function mountGate(state: BackendState) {
  return mount(StartupGate, { props: { state } })
}

async function advance(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
}

describe('启动门', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('启动中显示启动态：转圈 + 启动文案，没有重试按钮', () => {
    const w = mountGate(STARTING)

    expect(w.find('.startup').classes()).toContain('starting')
    expect(w.text()).toContain('正在启动服务…')
    expect(w.find('.spinner').exists()).toBe(true)
    expect(w.find('.retry').exists()).toBe(false)
    // 一上来不提「首次启动」，否则每次启动都像出了事
    expect(w.text()).not.toContain('首次启动')
  })

  it('超过 5 秒补一句「首次启动需要导入动作库」', async () => {
    const w = mountGate(STARTING)

    await advance(4999)
    expect(w.text()).not.toContain('首次启动')

    await advance(1)
    expect(w.text()).toContain('首次启动需要导入动作库，可能要十几秒')
  })

  it('失败态显示错误与重试按钮，且不是启动态的转圈文案', () => {
    const w = mountGate(FAILED)

    expect(w.find('.startup').classes()).toContain('failed')
    expect(w.text()).toContain('后端启动超时')
    expect(w.text()).toContain('重试')
    expect(w.find('.retry').exists()).toBe(true)

    // **不能永远转圈**：失败是终态，转圈会让人以为在等
    expect(w.find('.spinner').exists()).toBe(false)
    expect(w.text()).not.toContain('正在启动服务…')
    expect(w.text()).not.toContain('首次启动')
  })

  it('从启动中变成失败时，收掉慢启动提示并给出错误', async () => {
    const w = mountGate(STARTING)
    await advance(5000)
    expect(w.text()).toContain('首次启动需要导入动作库')

    await w.setProps({ state: FAILED })

    expect(w.text()).not.toContain('首次启动需要导入动作库')
    expect(w.text()).toContain('后端启动超时')
    expect(w.find('.spinner').exists()).toBe(false)
  })

  it('失败后点「重试」会发出 retry（由 App 重新开始轮询）', async () => {
    const w = mountGate(FAILED)

    await w.find('.retry').trigger('click')

    expect(w.emitted('retry')).toHaveLength(1)
  })

  it('从失败回到启动中时，慢启动提示重新计时', async () => {
    // 重试之后不该立刻挂着上一轮的「十几秒」提示
    const w = mountGate(FAILED)
    await w.setProps({ state: STARTING })

    await advance(4999)
    expect(w.text()).not.toContain('首次启动')

    await advance(1)
    expect(w.text()).toContain('首次启动需要导入动作库')
  })

  it('失败但没有 message 时给一句兜底文案', () => {
    const w = mountGate({ status: 'failed', port: null, message: '' })
    expect(w.text()).toContain('后端启动失败')
  })
})

describe('启动态 · 视觉 token', () => {
  it('颜色只来自 theme.css 的 token', () => {
    // 设计文档「九」：theme.css 是唯一颜色来源。App.vue 也一起扫——
    // 谁在启动门或整壳里写死一个色值（十六进制 / rgb / hsl），这条就红。
    for (const source of [startupGateSource, appSource]) {
      expect(source).not.toMatch(/#[0-9a-f]{3,8}\b/i)
      expect(source).not.toMatch(/\brgba?\s*\(/i)
      expect(source).not.toMatch(/\bhsla?\s*\(/i)
    }

    const tokenUses = startupGateSource.match(/var\(--[a-z0-9-]+\)/g) ?? []
    expect(tokenUses.length).toBeGreaterThanOrEqual(10)
  })
})
