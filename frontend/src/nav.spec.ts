import { describe, it, expect } from 'vitest'
import { NAV_GROUPS, allRoutes } from './nav'

describe('导航分组', () => {
  it('四个分组，顺序与设计文档一致', () => {
    expect(NAV_GROUPS.map((g) => g.label)).toEqual(['记录', '分析', '助手', '我的'])
  })

  it('「我的」钉在底部且与功能区分隔', () => {
    const mine = NAV_GROUPS.find((g) => g.label === '我的')!
    expect(mine.pinnedBottom).toBe(true)
    // 其余三组不钉底
    expect(NAV_GROUPS.filter((g) => !g.pinnedBottom).map((g) => g.label))
      .toEqual(['记录', '分析', '助手'])
  })

  it('人体图那一项**不再**带「预留」标记（第一阶段已实现）', () => {
    // 这条测试原来断言 marker === '预留'，因为那时页面还是占位。
    // 计划 6b 把 2D 静态热力图做出来之后，那个角标会骗用户：「这一项还没做」——
    // 点进去却发现有图、有面板、能点。所以标记删了，测试改成守「它确实没有」。
    // 设计文档决定 5 的「界面位第一阶段就冻结」仍然成立：冻结的是**路由与布局**，
    // 不是「永远显示没做」。3D 排第二阶段，但那不该用一个假角标表达。
    const item = allRoutes().find((r) => r.path === '/body-map')!
    expect(item.label).toBe('人体图')
    expect(item.marker).toBeUndefined()
  })

  it('每个路由的 path 唯一，且都有非空 label', () => {
    const routes = allRoutes()
    expect(new Set(routes.map((r) => r.path)).size).toBe(routes.length)
    for (const r of routes) expect(r.label.length).toBeGreaterThan(0)
  })

  it('覆盖设计文档列出的全部 9 项', () => {
    expect(allRoutes().map((r) => r.label)).toEqual([
      '今日', '训练记录', '饮食记录',
      '人体图', '进步曲线', '身体数据',
      '智能体对话',
      '设置', '关于与致谢',
    ])
  })
})
