import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import AppSidebar from './AppSidebar.vue'
import { allRoutes } from '../nav'

function mountWithRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: allRoutes().map((r) => ({ path: r.path, name: r.name, component: { template: '<div/>' } })),
  })
  return mount(AppSidebar, { global: { plugins: [router] } })
}

describe('侧边栏', () => {
  it('渲染四个分组标题', () => {
    const w = mountWithRouter()
    const labels = w.findAll('.group-label').map((n) => n.text())
    expect(labels).toEqual(['记录', '分析', '助手', '我的'])
  })

  it('渲染全部 9 个入口', () => {
    const w = mountWithRouter()
    expect(w.findAll('.nav-item')).toHaveLength(9)
  })

  it('「我的」分组带 pinned 类（由它决定分隔线与贴底）', () => {
    const w = mountWithRouter()
    const pinnedGroups = w.findAll('.nav-group.pinned')
    expect(pinnedGroups).toHaveLength(1)
    expect(pinnedGroups[0].find('.group-label').text()).toBe('我的')
  })

  it('人体图那项显示「预留」角标', () => {
    const w = mountWithRouter()
    const marker = w.findAll('.marker').map((n) => n.text())
    expect(marker).toEqual(['预留'])
  })

  it('分隔线在「我的」分组内部，不在功能区', () => {
    // 设计文档：「我的」用分隔线与功能区隔开。
    // 分隔线若跑到功能区里，视觉上会变成「记录」被切了一刀。
    const w = mountWithRouter()
    const pinned = w.find('.nav-group.pinned')
    expect(pinned.find('.divider').exists()).toBe(true)
    expect(w.findAll('.nav-group:not(.pinned) .divider')).toHaveLength(0)
  })

  it('「我的」在 DOM 里排在所有功能区之后', () => {
    // 贴底是靠 flex 布局实现的，但顺序错了 flex 也救不回来。
    const w = mountWithRouter()
    const groups = w.findAll('.nav-group')
    expect(groups[groups.length - 1].classes()).toContain('pinned')
  })
})
