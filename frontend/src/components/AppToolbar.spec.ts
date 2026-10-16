import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { nextTick } from 'vue'
import AppToolbar from './AppToolbar.vue'
import { allRoutes } from '../nav'

async function mountAt(path: string) {
  const router = createRouter({
    history: createWebHistory(),
    routes: allRoutes().map((r) => ({
      path: r.path,
      name: r.name,
      component: { template: '<div/>' },
    })),
  })
  router.push(path)
  await router.isReady()
  const w = mount(AppToolbar, { global: { plugins: [router] } })
  await nextTick()
  return w
}

describe('顶部工具条', () => {
  it('显示当前页名', async () => {
    expect((await mountAt('/training')).find('h1').text()).toBe('训练记录')
  })

  it('切页之后跟着变', async () => {
    // 页名来自 nav.ts 的查表，不是模板里写死的字符串——
    // 这条测试守住「它确实在跟着路由动」。
    const w = await mountAt('/diet')
    expect(w.find('h1').text()).toBe('饮食记录')
  })

  it('认识的路径才会显示页名，不认识的回退到产品名', async () => {
    const w = await mountAt('/today')
    expect(w.find('h1').text()).toBe('今日')
  })
})
