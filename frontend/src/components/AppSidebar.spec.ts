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

  it('侧边栏**没有**「预留」角标（人体图第一阶段已实现）', () => {
    // 原来这条断言 marker === ['预留']。计划 6b 之后人体图能用了，
    // 再挂「预留」就是在骗用户。现在守「一个都没有」。
    const w = mountWithRouter()
    expect(w.findAll('.marker')).toHaveLength(0)
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

  it('每个入口都渲染「编号 + 中文 + 小字母英文」三件套', () => {
    // 2026-10-21 换语言：单字图标（今/训/食…）换成 01..09 编号 + 英文副名。
    // 编号是跨分组连续的，不是每组从 01 重开——那样会出现两个 01。
    const w = mountWithRouter()
    const items = w.findAll('.nav-item')
    expect(items.map((n) => n.find('.no').text())).toEqual([
      '01', '02', '03', '04', '05', '06', '07', '08', '09',
    ])
    const ens = items.map((n) => n.find('.tw em').text())
    expect(ens).toEqual([
      'Today', 'Training', 'Nutrition', 'Body Map', 'Progress', 'Body',
      'Agent', 'Settings', 'About',
    ])
    // 中文名仍然在（编号不能替代它）
    expect(items.map((n) => n.find('.tw b').text())).toEqual(
      allRoutes().map((r) => r.label),
    )
  })

  it('编号跨分组唯一（不是每组 01 重开）', () => {
    const w = mountWithRouter()
    const nos = w.findAll('.no').map((n) => n.text())
    expect(new Set(nos).size).toBe(nos.length)
  })

  it('品牌块有英文全大写副标（视觉方向的一半靠这行 8px 小字）', () => {
    const w = mountWithRouter()
    expect(w.find('.brand .nm').text().replace(/\s/g, '')).toBe('changE')
    expect(w.find('.brand .cap').text()).toBe('Personal Training System')
  })
})
