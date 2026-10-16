/**
 * 侧边栏与路由的单一数据源。
 *
 * 布局是设计文档冻结的决定：左侧分组导航 + 顶部工具条 + 右侧工作区。
 * 「设置」与「关于与致谢」收进底部「我的」分组，用分隔线与功能区隔开——
 * 系统级入口不该和日常功能混在一起。
 *
 * 这里刻意用数据而不是手写 9 个 <RouterLink>：分组、顺序、是否钉底、
 * 有没有「预留」标记全部在一处，改布局不用翻模板。
 */

export interface NavItem {
  path: string
  label: string
  /** 路由名，供 router.push({ name }) 用。 */
  name: string
  /** 角标文案。目前只有「人体图」用它（设计文档决定 5）。 */
  marker?: string
  /** 图标的占位字符。第一阶段不引入图标库，先用单字。 */
  glyph: string
}

export interface NavGroup {
  label: string
  /** 钉在侧边栏底部（设计文档：「我的」用分隔线隔开）。 */
  pinnedBottom: boolean
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    label: '记录',
    pinnedBottom: false,
    items: [
      { path: '/today',    label: '今日',     name: 'today',    glyph: '今' },
      { path: '/training', label: '训练记录', name: 'training', glyph: '训' },
      { path: '/diet',     label: '饮食记录', name: 'diet',     glyph: '食' },
    ],
  },
  {
    label: '分析',
    pinnedBottom: false,
    items: [
      // 设计文档决定 5：3D 人体图排在第二阶段，但界面位第一阶段就冻结，
      // 免得将来加它时要重排整个导航。
      { path: '/body-map', label: '人体图',   name: 'bodyMap',  glyph: '体', marker: '预留' },
      { path: '/progress', label: '进步曲线', name: 'progress', glyph: '曲' },
      { path: '/body',     label: '身体数据', name: 'body',     glyph: '数' },
    ],
  },
  {
    label: '助手',
    pinnedBottom: false,
    items: [
      { path: '/agent', label: '智能体对话', name: 'agent', glyph: '智' },
    ],
  },
  {
    label: '我的',
    pinnedBottom: true,
    items: [
      { path: '/settings', label: '设置',       name: 'settings', glyph: '设' },
      { path: '/about',    label: '关于与致谢', name: 'about',    glyph: '谢' },
    ],
  },
]

/** 拍平成一个列表，路由与测试都用它。 */
export function allRoutes(): NavItem[] {
  return NAV_GROUPS.flatMap((g) => g.items)
}
