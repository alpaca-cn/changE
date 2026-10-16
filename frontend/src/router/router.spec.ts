import { describe, it, expect } from 'vitest'
import type { RouteLocationRaw } from 'vue-router'
import { router } from './index'
import { allRoutes } from '../nav'

describe('路由表', () => {
  it('每条导航项都有对应的路由，且都能解析出组件', () => {
    // nav.ts 与 router/index.ts 的 componentByPath 是两处手写映射，
    // 漏一个不会编译失败——只会在点到那一页时白屏。
    for (const item of allRoutes()) {
      const resolved = router.resolve(item.path)
      expect(resolved.name, `路由 ${item.path} 没解析出名字`).toBe(item.name)
      expect(resolved.matched.length, `路由 ${item.path} 没匹配到任何记录`).toBeGreaterThan(0)
      // component 拿到的是 () => Promise 才说明真的绑上了组件，不是 undefined
      const comp = resolved.matched[0]?.components?.default
      expect(comp, `路由 ${item.path} 没有绑定组件`).toBeTruthy()
    }
  })

  it('9 个入口各绑到不同的组件', () => {
    // 上一条只要求「有组件」——把 /body 也指向 BodyMapView.vue 它照样绿。
    // BodyDataView.vue 与 BodyMapView.vue 是最容易搞混的一对，
    // 这条断言守住每个入口拿到的是自己的那一页。
    const comps = allRoutes().map(
      (item) => router.resolve(item.path).matched[0]?.components?.default,
    )
    expect(new Set(comps).size).toBe(comps.length)
  })

  it('根路径重定向到今日', () => {
    // 少了这条，应用启动会落在没匹配任何记录的 /，右侧工作区一片空白。
    const root = router.getRoutes().find((r) => r.path === '/')
    expect(root, '根路径没有路由记录').toBeTruthy()
    expect(root!.redirect, '根路径没有配重定向').toBeTruthy()
    expect(router.resolve(root!.redirect as RouteLocationRaw).name).toBe('today')
  })
})
