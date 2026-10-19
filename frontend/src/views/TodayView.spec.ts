import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import TodayView from './TodayView.vue'
import { RECENT, WEEKLY_17 } from '../api/__fixtures__/training-responses'
import { DAILY, EMPTY_DAY } from '../api/__fixtures__/nutrition-responses'

// 今日页不直接发请求：它调 `api/training.ts`，那边才 `invoke('api_request')`。
// 所以这里 mock 的仍是 `invoke`，按 **path** 分派——笼统的 mockResolvedValue 会让
// 两个请求拿到同一份响应，测试就分不清是哪个端点坏了。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

type Reply = { status: number; body: unknown } | 'reject'

/** 按 path 前缀分派。没准备的 path 直接抛错——比返回空对象更容易发现测试写错了。 */
function dispatch(map: Record<string, Reply>) {
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (_cmd: unknown, args: unknown) => {
    const path = (args as { path: string }).path
    const key = Object.keys(map).find((k) => path.startsWith(k))
    if (key === undefined) throw new Error(`测试没有为 ${path} 准备应答`)
    const r = map[key]
    if (r === 'reject') throw new Error('连不上后端 127.0.0.1:1：拒绝连接')
    return { status: r.status, body: typeof r.body === 'string' ? r.body : JSON.stringify(r.body) }
  })
}

/** 正常的三个端点。 */
function okMap(overrides: Record<string, Reply> = {}): Record<string, Reply> {
  return {
    '/api/volume/weekly': { status: 200, body: WEEKLY_17 },
    '/api/sessions/recent': { status: 200, body: RECENT },
    '/api/nutrition/daily': { status: 200, body: DAILY },
    ...overrides,
  }
}

async function mountToday(map: Record<string, Reply>) {
  dispatch(map)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/today', component: TodayView },
      { path: '/training', component: { template: '<div/>' } },
    ],
  })
  router.push('/today')
  await router.isReady()
  const wrapper = mount(TodayView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('今日页 · 本周容量在最显眼的位置', () => {
  it('shows_the_weekly_volume_before_the_recent_sessions', async () => {
    // 设计文档「两处刻意的界面设计」第 1 条。这条守的是**顺序**，
    // 而顺序正是「躲不掉」的实现方式——把容量挪到最近训练下面，这条就红。
    const { wrapper } = await mountToday(okMap())
    const volume = wrapper.find('.volume').element
    const sessions = wrapper.find('.sessions').element
    expect(volume.compareDocumentPosition(sessions) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('renders_all_seventeen_muscles_including_the_zero_set_ones', async () => {
    // 17 块全渲染。**这一条就是「0 组的肌群不许被藏起来」的杀手**：
    // 任何过滤（`v-if="m.sets > 0"`）都会让行数变成 16 或更少。
    const { wrapper } = await mountToday(okMap())
    const rows = wrapper.findAll('.muscles li')
    expect(rows).toHaveLength(17)
    expect(wrapper.text()).toContain('胸部')
    expect(wrapper.text()).toContain('颈部')
  })

  it('an_untrained_muscle_is_visibly_marked_not_hidden', async () => {
    const { wrapper } = await mountToday(okMap())
    const rows = wrapper.findAll('.muscles li')
    const lats = rows.find((r) => r.text().includes('背阔肌'))!

    expect(lats.classes()).toContain('untrained')
    // 颜色之外还要有文字：色盲用户也得能读出「本周 0 组」
    expect(lats.text()).toContain('0 组')
    expect(lats.text()).toContain('本周 0 组')
  })

  it('shows_the_target_range_and_marks_below', async () => {
    const { wrapper } = await mountToday(okMap())
    const chest = wrapper.findAll('.muscles li').find((r) => r.text().includes('胸部'))!

    expect(chest.text()).toContain('2 组')       // 主肌群 +1×2（只算已完成的组）
    expect(chest.text()).toContain('10–20')      // 目标区间
    expect(chest.classes()).toContain('below')
    expect(chest.text()).toContain('偏少')
  })

  it('shows_a_not_configured_muscle_as_no_target_and_never_prints_null', async () => {
    const { wrapper } = await mountToday(okMap())
    const neck = wrapper.findAll('.muscles li').find((r) => r.text().includes('颈部'))!

    expect(neck.text()).toContain('未设目标')
    expect(neck.text()).not.toContain('null')
    expect(neck.classes()).toContain('not-configured')
  })

  it('shows_the_week_span', async () => {
    const { wrapper } = await mountToday(okMap())
    expect(wrapper.text()).toContain('2026-10-19')
    expect(wrapper.text()).toContain('2026-10-25')
  })
})

describe('今日页 · 最近训练', () => {
  it('lists_recent_sessions', async () => {
    const { wrapper } = await mountToday(okMap())
    expect(wrapper.find('.sessions').text()).toContain('2026-10-19')
    expect(wrapper.find('.sessions').text()).toContain('胸 + 三头')
  })

  it('an_empty_recent_list_shows_a_readable_hint', async () => {
    const { wrapper } = await mountToday(okMap({ '/api/sessions/recent': { status: 200, body: '[]' } }))
    expect(wrapper.find('.sessions').text()).toContain('还没有训练记录')
  })
})

describe('今日页 · 开始训练', () => {
  it('starting_a_workout_navigates_to_the_training_page', async () => {
    const { wrapper, router } = await mountToday(okMap())
    await wrapper.find('.primary').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/training')
  })
})

describe('今日页 · 失败不能白屏', () => {
  it('a_transport_failure_shows_the_reason_instead_of_a_blank_page', async () => {
    const { wrapper } = await mountToday(okMap({ '/api/volume/weekly': 'reject' }))
    expect(wrapper.text()).toContain('连不上')
    // 页面骨架还在（没白屏）
    expect(wrapper.find('h2').text()).toBe('今日')
  })

  it('a_400_from_the_backend_shows_the_server_message', async () => {
    // 后端 400 的 `{error}` 是中文的校验文案；显示它比显示「加载失败」有用得多。
    const { wrapper } = await mountToday(
      okMap({ '/api/volume/weekly': { status: 400, body: { error: '参数 date 格式不对' } } }),
    )
    expect(wrapper.text()).toContain('参数 date 格式不对')
  })

  it('one_panel_failing_does_not_blank_the_other', async () => {
    // 两个请求各自 try/catch：容量挂了，最近训练照样显示。
    const { wrapper } = await mountToday(okMap({ '/api/volume/weekly': 'reject' }))
    expect(wrapper.find('.volume').text()).toContain('连不上')
    expect(wrapper.find('.sessions').text()).toContain('胸 + 三头')
  })
})

describe('今日页 · 今天吃了多少', () => {
  it('shows_the_intake_from_the_backend', async () => {
    const { wrapper } = await mountToday(okMap())
    const intake = wrapper.find('.intake')

    expect(intake.text()).toContain('421.5')
    expect(intake.text()).toContain('千卡')
    expect(intake.text()).toContain('50.4')
    expect(intake.text()).toContain('1 餐')
    expect(intake.text()).toContain('2 条目')
  })

  it('an_empty_day_shows_zeros_rather_than_nothing', async () => {
    // 「今天还没吃」是刚装上时的常态：显示 0，不是空白，也不是错误。
    const { wrapper } = await mountToday(okMap({ '/api/nutrition/daily': { status: 200, body: EMPTY_DAY } }))
    const intake = wrapper.find('.intake')

    expect(intake.text()).toContain('0 千卡')
    expect(intake.text()).toContain('0 餐')
    expect(intake.find('.err').exists()).toBe(false)
  })

  it('does_not_ask_for_a_specific_date', async () => {
    // 「今天」由后端决定：前端自己算日期会在跨零点/跨时区时和后端不一致。
    const { wrapper } = await mountToday(okMap())
    await flushPromises()
    expect(wrapper.find('.intake').exists()).toBe(true)
    // 断言请求路径里没有 date 参数
    const call = invokeMock.mock.calls.find(
      (c) => (c[1] as { path: string }).path.startsWith('/api/nutrition/daily'),
    )
    expect((call![1] as { path: string }).path).toBe('/api/nutrition/daily')
  })

  it('an_intake_failure_is_isolated_from_the_training_panels', async () => {
    const { wrapper } = await mountToday(okMap({ '/api/nutrition/daily': 'reject' }))

    expect(wrapper.find('.intake').text()).toContain('连不上')
    // 训练那两块照常
    expect(wrapper.find('.volume').text()).toContain('胸部')
    expect(wrapper.find('.sessions').text()).toContain('胸 + 三头')
  })
})
