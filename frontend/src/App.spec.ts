import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import { allRoutes } from './nav'

// 整壳测试：**唯一 mount 完整 App.vue 的地方**。
// AppToolbar.spec.ts 是单独 mount 组件的，不经过 App.vue——
// 所以「App.vue 的模板里漏掉了 <AppToolbar />」这个改动
// 曾经能让全部测试保持绿色，而顶部工具条在真机上静默消失。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

async function mountApp() {
  const router = createRouter({
    history: createWebHistory(),
    routes: allRoutes().map((r) => ({
      path: r.path,
      name: r.name,
      component: { template: '<div/>' },
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
    invokeMock.mockReset()
    // 结构测试不关心数据，给个 starting 就够。
    invokeMock.mockResolvedValue({ status: 'starting', port: null, message: '正在启动后端…' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
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
