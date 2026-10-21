import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { h } from 'vue'
import App from './App.vue'
import { allRoutes } from './nav'

// 整壳测试：**唯一 mount 完整 App.vue 的地方**。
// AppToolbar.spec.ts 是单独 mount 组件的，不经过 App.vue——
// 所以「App.vue 的模板里漏掉了 <AppToolbar />」这个改动
// 曾经能让全部测试保持绿色，而顶部工具条在真机上静默消失。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

const STARTING = { status: 'starting', port: null, message: '正在启动后端…' }
const READY = { status: 'ready', port: 51234, message: '后端已连接 · 端口 51234' }
const FAILED = { status: 'failed', port: null, message: '后端启动超时' }

/**
 * 按命令名分派 invoke，`shell` 是 `get_backend_status` 的返回值序列
 * （用完一直返回最后一个）。
 *
 * Task 3 之后 `App.vue` 自己也持有一个状态轮询（启动门只知道「能渲染了吗」，
 * 不查 health），所以这里必须分清是哪条命令，不能笼统 mockResolvedValue。
 */
let shellCalls: unknown[] = []

function dispatch(shell: unknown[]) {
  shellCalls = []
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (cmd: unknown) => {
    if (cmd === 'get_backend_status') {
      const i = Math.min(shellCalls.length, shell.length - 1)
      shellCalls.push(shell[i])
      return shell[i]
    }
    if (cmd === 'get_health') {
      return { status: 'up', cache: '缓存：未启用（配置为关闭）' }
    }
    throw new Error(`测试没有为命令 ${String(cmd)} 准备应答`)
  })
}

function callCount(cmd: string) {
  return invokeMock.mock.calls.filter((c) => c[0] === cmd).length
}

async function advance(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
  await flushPromises()
}

/**
 * 默认让后端**已就绪**：下面那三条结构用例守的是「整壳长什么样」，
 * 而 Task 3 之后整壳只会在就绪时渲染（启动门之外没有侧边栏/工具条/页脚）。
 * 它们断言的内容一个字没改，只是挂载时后端得是起来的。
 */
async function mountApp(shell: unknown[] = [READY]) {
  dispatch(shell)
  const router = createRouter({
    history: createWebHistory(),
    routes: allRoutes().map((r) => ({
      path: r.path,
      name: r.name,
      // render 函数而不是 template 字符串：测试环境没有运行时模板编译器，
      // 字符串模板会静默渲染成空，`.stub-route` 就永远不存在。
      component: { render: () => h('div', { class: 'stub-route' }) },
    })),
  })
  await router.push('/today')
  await router.isReady()
  const w = mount(App, { global: { plugins: [router, createPinia()] } })
  await flushPromises()
  return w
}

describe('整壳结构', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    invokeMock.mockReset()
  })

  it('三块都在：侧边栏、顶部工具条、底部状态栏', async () => {
    const w = await mountApp()
    expect(w.find('.sidebar').exists()).toBe(true)
    expect(w.find('.toolbar').exists()).toBe(true)
    expect(w.find('.status').exists()).toBe(true)
  })

  it('工具条在 workspace 之上，状态栏在 workspace 之下', async () => {
    // 光断言「存在」不够：顺序错了布局就反了（状态栏跑到顶部）。
    const w = await mountApp()
    const main = w.find('.main')
    expect(main.exists()).toBe(true)
    const children = main.element.children
    const classes = Array.from(children).map((el) => el.className)
    expect(classes).toHaveLength(3)
    expect(classes[0]).toContain('toolbar')
    expect(classes[1]).toContain('workspace')
    expect(classes[2]).toContain('status')
  })

  it('侧边栏不在 .main 里，工具条与状态栏是 .main 的直接子节点', async () => {
    const w = await mountApp()
    const main = w.find('.main').element
    expect(main.children[0].className).toContain('toolbar')
    expect(main.children[2].className).toContain('status')
    // 侧边栏必须与 .main 平级，而不是被塞进 .main
    expect(main.querySelector('.sidebar')).toBeNull()
    expect(w.find('.shell').element.children[0].className).toContain('sidebar')
  })
})

describe('启动门', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    invokeMock.mockReset()
  })

  it('启动中：只显示启动态，工作区与业务页一个都不渲染', async () => {
    const w = await mountApp([STARTING])

    expect(w.find('.startup').exists()).toBe(true)
    expect(w.text()).toContain('正在启动服务…')

    // 这就是修复的要点：`RouterView` 不渲染，所以业务页不会在启动期发请求、
    // 不会把红字错误摆到用户面前。
    expect(w.find('.workspace').exists()).toBe(false)
    expect(w.find('.stub-route').exists()).toBe(false)
    expect(w.find('.sidebar').exists()).toBe(false)
    expect(w.find('.toolbar').exists()).toBe(false)
    expect(w.find('.status').exists()).toBe(false)
    // 启动期一次 health 都不查（启动门不查、页脚还没资格出场）
    expect(callCount('get_health')).toBe(0)
  })

  it('就绪后：启动态消失，业务页与页脚出现', async () => {
    const w = await mountApp([STARTING, READY])
    expect(w.find('.startup').exists()).toBe(true)

    await advance(500)

    expect(w.find('.startup').exists()).toBe(false)
    expect(w.find('.stub-route').exists()).toBe(true)
    expect(w.find('.workspace').exists()).toBe(true)
    expect(w.find('.status').exists()).toBe(true)
    // 页脚照常显示「后端已连接 · 端口 51234」
    expect(w.find('.status').text()).toContain('51234')
  })

  it('失败：显示错误与重试按钮，不是启动态的转圈文案', async () => {
    const w = await mountApp([STARTING, FAILED])
    await advance(500)

    expect(w.find('.startup.failed').exists()).toBe(true)
    expect(w.text()).toContain('后端启动超时')
    expect(w.text()).not.toContain('正在启动服务…')
    expect(w.find('.spinner').exists()).toBe(false)
    expect(w.find('.retry').exists()).toBe(true)
    // 失败态同样不渲染业务页
    expect(w.find('.stub-route').exists()).toBe(false)
    expect(w.find('.status').exists()).toBe(false)
  })

  it('启动超过 5 秒后补一句「启动比平时慢」', async () => {
    const w = await mountApp([STARTING])
    expect(w.text()).not.toContain('首次启动')

    await advance(12100)

    expect(w.text()).toContain('启动比平时慢')
  })

  it('失败后点「重试」重新开始轮询，后端起来后进入正常界面', async () => {
    const w = await mountApp([STARTING, FAILED, STARTING, READY])
    await advance(500)
    expect(w.text()).toContain('后端启动超时')
    const before = callCount('get_backend_status')

    await w.find('.retry').trigger('click')
    await flushPromises()

    // 点下那一刻就又问了一次，而不是永远停在错误页
    expect(callCount('get_backend_status')).toBeGreaterThan(before)
    expect(w.find('.startup.failed').exists()).toBe(false)

    await advance(500)
    expect(w.find('.startup').exists()).toBe(false)
    expect(w.find('.stub-route').exists()).toBe(true)
    expect(w.find('.status').text()).toContain('51234')
  })

  it('就绪后只有一个 health 轮询者（启动门不额外打 health）', async () => {
    // 启动门传了 `healthPolling: false`。若它也跟着 5 秒查一次，
    // 这里 60 秒会看到约 26 次而不是 13 次——稳态开销白翻一倍。
    await mountApp([READY])
    await advance(0)
    await advance(60_000)

    const n = callCount('get_health')
    expect(n).toBeGreaterThanOrEqual(12)
    expect(n).toBeLessThanOrEqual(14)
  })
})
