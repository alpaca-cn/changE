import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BodyMapSidePanel from './BodyMapSidePanel.vue'
// 用 `?raw` 读源码而不是 node:fs（jsdom 下 node:fs 是空壳；见 styles/theme.spec.ts）。
import panelSource from './BodyMapSidePanel.vue?raw'
import type { MuscleBodyMap } from '../api/body-map'
import type { ExerciseSummary, VolumeStatus } from '../api/training'

/**
 * 右侧面板是**纯展示组件**：数据由 `BodyMapView` 取好传进来（与 `VolumePanel`、
 * `ExerciseDetailPanel` 同一分工），所以这里的测试不需要 mock `invoke`——
 * 「用哪个肌群 id、什么时候拉动作、连点怎么防串台」留在页面里。
 */

function muscle(overrides: Partial<MuscleBodyMap> = {}): MuscleBodyMap {
  return {
    muscleGroupId: 1,
    nameZh: '胸部',
    nameEn: 'chest',
    sets: 3,
    min: 10,
    max: 20,
    status: 'BELOW',
    lastTrainedOn: '2026-10-20',
    daysSince: 0,
    regionIds: ['chest-upper-left', 'chest-lower-left', 'chest-upper-right', 'chest-lower-right'],
    ...overrides,
  }
}

/** 颈部：后端**没配目标**（min/max 为 null），0 组是另一个独立事实。 */
function neck(overrides: Partial<MuscleBodyMap> = {}): MuscleBodyMap {
  return muscle({
    muscleGroupId: 17,
    nameZh: '颈部',
    nameEn: 'neck',
    sets: 0,
    min: null,
    max: null,
    status: 'NOT_CONFIGURED',
    lastTrainedOn: null,
    daysSince: null,
    regionIds: ['neck-left', 'neck-right', 'nape'],
    ...overrides,
  })
}

const EXERCISES: ExerciseSummary[] = [
  {
    id: 101,
    nameEn: 'ZZ Body Map Bench Press',
    nameZh: '杠铃卧推',
    equipment: 'barbell',
    isCompound: true,
    category: 'strength',
  },
  {
    // 手工录入的动作可能没有中文名：显示时退回英文名，不留空白
    id: 102,
    nameEn: 'ZZ Body Map Dumbbell Press',
    nameZh: null,
    equipment: 'dumbbell',
    isCompound: false,
    category: 'strength',
  },
]

interface PanelProps {
  muscle: MuscleBodyMap | null
  exercises?: ExerciseSummary[]
  loading?: boolean
  error?: string | null
}

function mountPanel(overrides: Partial<PanelProps> = {}) {
  return mount(BodyMapSidePanel, {
    props: { muscle: muscle(), exercises: EXERCISES, ...overrides },
  })
}

describe('人体图右侧面板 · 一块肌群的数字', () => {
  it('shows_the_chinese_name_sets_range_and_status_label', () => {
    const wrapper = mountPanel()

    expect(wrapper.find('.panel-title').text()).toBe('胸部')
    expect(wrapper.find('.subtitle').text()).toBe('chest')
    expect(wrapper.find('[data-fact="sets"] dd').text()).toBe('3 组')
    expect(wrapper.find('[data-fact="target"] dd').text()).toBe('10–20')
    // status 是「本周组数落在参考区间哪一段」的直接信息，不能只透传不显示
    expect(wrapper.find('[data-fact="status"] dd').text()).toBe('低于参考区间')
  })

  it('every_backend_status_has_a_descriptive_label', () => {
    // 五个状态各一句。措辞只描述「和参考区间比落在哪」，
    // 不做「练得够不够」的判断（见组件里的注释）。
    const expected: Record<VolumeStatus, string> = {
      IN_RANGE: '在参考区间内',
      BELOW: '低于参考区间',
      ABOVE: '高于参考区间',
      UNTRAINED: '本周 0 组',
      NOT_CONFIGURED: '未设参考区间',
    }

    for (const [status, label] of Object.entries(expected) as [VolumeStatus, string][]) {
      const wrapper = mountPanel({ muscle: muscle({ status }) })
      expect(wrapper.find('[data-fact="status"] dd').text(), status).toBe(label)
    }
  })

  it('a_null_target_shows_unset_instead_of_0_0', () => {
    const wrapper = mountPanel({ muscle: neck() })

    expect(wrapper.find('.panel-title').text()).toBe('颈部')
    // `null` 是「我们没给这块肌肉定建议量」，不是「建议练 0 组」
    expect(wrapper.find('[data-fact="target"] dd').text()).toBe('未设目标')

    // 写成 0–0 / 0-0 / null 都是把两件事说成了一件
    const text = wrapper.text()
    expect(text).not.toContain('0–0')
    expect(text).not.toContain('0-0')
    expect(text).not.toContain('null')
    // 组数照常显示 0：本周没练是真的，只是不该和「没设目标」混在一起
    expect(wrapper.find('[data-fact="sets"] dd').text()).toBe('0 组')
    expect(wrapper.find('[data-fact="status"] dd').text()).toBe('未设参考区间')
  })

  it('a_half_set_is_shown_with_one_decimal', () => {
    // 折算组数可能是 1.5（主肌群 +1、次要 +0.5），不能被取整成 2
    const wrapper = mountPanel({ muscle: muscle({ sets: 1.5, status: 'BELOW' }) })
    expect(wrapper.find('[data-fact="sets"] dd').text()).toBe('1.5 组')
  })
})

describe('人体图右侧面板 · 动作列表', () => {
  it('lists_the_exercise_names_and_falls_back_to_english', () => {
    const wrapper = mountPanel()

    const names = wrapper.findAll('.exercise').map((li) => li.text())
    expect(names).toEqual(['杠铃卧推', 'ZZ Body Map Dumbbell Press'])
  })

  it('an_empty_list_says_so_instead_of_rendering_nothing', () => {
    const wrapper = mountPanel({ exercises: [] })

    expect(wrapper.find('.empty').text()).toContain('这个肌群还没有动作')
    expect(wrapper.findAll('.exercise')).toHaveLength(0)
    // 空列表不是错误，也不是加载中
    expect(wrapper.find('.err').exists()).toBe(false)
  })

  it('loading_and_failure_states_are_visible', () => {
    const loading = mountPanel({ exercises: [], loading: true })
    expect(loading.find('.dim').text()).toContain('正在读动作')
    expect(loading.find('.empty').exists()).toBe(false)

    const failed = mountPanel({ exercises: [], error: '读动作列表失败' })
    expect(failed.find('.err').text()).toBe('读动作列表失败')
    expect(failed.find('.empty').exists()).toBe(false)
  })
})

describe('人体图右侧面板 · 未选中与关闭', () => {
  it('no_muscle_selected_shows_guidance_not_a_blank_panel', () => {
    const wrapper = mountPanel({ muscle: null })

    expect(wrapper.text()).not.toBe('')
    expect(wrapper.find('.guide').text()).toContain('点人体图')
    // 没选中就没有数字可显示，也不该请求/显示动作列表
    expect(wrapper.find('[data-fact="sets"]').exists()).toBe(false)
    expect(wrapper.find('.exercise').exists()).toBe(false)
  })

  it('the_close_button_emits_close', async () => {
    const wrapper = mountPanel()

    await wrapper.find('.close').trigger('click')
    expect(wrapper.emitted('close')).toEqual([[]])
  })
})

describe('人体图右侧面板 · 视觉 token', () => {
  it('colors_come_from_theme_tokens_only', () => {
    // 设计文档「九」：theme.css 是唯一颜色来源。
    expect(panelSource).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(panelSource).not.toMatch(/\brgba?\s*\(/i)
    expect(panelSource).not.toMatch(/\bhsla?\s*\(/i)

    const tokenUses = panelSource.match(/var\(--[a-z0-9-]+\)/g) ?? []
    expect(tokenUses.length).toBeGreaterThanOrEqual(15)
  })
})
