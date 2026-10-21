/**
 * 侧边栏与路由的单一数据源。
 *
 * 布局是冻结的决定：左侧分组导航 + 顶部工具条 + 右侧工作区。
 * 「设置」与「关于与致谢」收进底部「我的」分组，用分隔线与功能区隔开——
 * 系统级入口不该和日常功能混在一起。
 *
 * 这里刻意用数据而不是手写 9 个 <RouterLink>：分组、顺序、是否钉底、
 * 编号、英文副名全部在一处，改布局不用翻模板。
 *
 * **2026-10-21 换过视觉语言**：从「单字图标 + 中文」改成「编号 + 中文 + 小字母英文」。
 * 参考项目 LZheng0411/Lzheng-fitness 的导航是 01..06 + 中文 + 8px 全大写英文，
 * 编号比单字图标更安静，也更容易一眼定位。编号是**跨分组连续**的（01..09），
 * 不是每组从 01 重开——那样会出现两个 01，反而更难指代。
 */

export interface NavItem {
  path: string
  label: string
  /** 路由名，供 router.push({ name }) 用。 */
  name: string
  /** 跨分组连续的两位编号（01..09），显示在导航最左侧。 */
  no: string
  /** 英文副名，8px 全大写字距 .16em 显示在中文下面。 */
  labelEn: string
  /** 角标文案。目前没有项目用它（人体图第一阶段已实现，撤掉了「预留」）。 */
  marker?: string
}

export interface NavGroup {
  label: string
  /** 钉在侧边栏底部（「我的」用分隔线隔开）。 */
  pinnedBottom: boolean
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    label: '记录',
    pinnedBottom: false,
    items: [
      { path: '/today',    label: '今日',     name: 'today',    no: '01', labelEn: 'Today' },
      { path: '/training', label: '训练记录', name: 'training', no: '02', labelEn: 'Training' },
      { path: '/diet',     label: '饮食记录', name: 'diet',     no: '03', labelEn: 'Nutrition' },
    ],
  },
  {
    label: '分析',
    pinnedBottom: false,
    items: [
      // 设计文档决定 5：3D 人体图排在第二阶段，但界面位第一阶段就冻结，
      // 免得将来加它时要重排整个导航。
      { path: '/body-map', label: '人体图',   name: 'bodyMap',  no: '04', labelEn: 'Body Map' },
      { path: '/progress', label: '进步曲线', name: 'progress', no: '05', labelEn: 'Progress' },
      { path: '/body',     label: '身体数据', name: 'body',     no: '06', labelEn: 'Body' },
    ],
  },
  {
    label: '助手',
    pinnedBottom: false,
    items: [
      { path: '/agent', label: '智能体对话', name: 'agent', no: '07', labelEn: 'Agent' },
    ],
  },
  {
    label: '我的',
    pinnedBottom: true,
    items: [
      { path: '/settings', label: '设置',       name: 'settings', no: '08', labelEn: 'Settings' },
      { path: '/about',    label: '关于与致谢', name: 'about',    no: '09', labelEn: 'About' },
    ],
  },
]

/** 拍平成一个列表，路由与测试都用它。 */
export function allRoutes(): NavItem[] {
  return NAV_GROUPS.flatMap((g) => g.items)
}
