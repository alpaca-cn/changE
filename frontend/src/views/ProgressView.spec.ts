import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProgressView from './ProgressView.vue'
// 用 `?raw` 读源码而不是 node:fs：jsdom 下 Vite 会把 node:fs 换成空壳
// （见 styles/theme.spec.ts 顶部那段注释），而 `?raw` 在构建/测试两个环境都只是字符串。
import progressViewSource from './ProgressView.vue?raw'
import { PROGRESS_THREE_DAYS } from '../api/__fixtures__/progress-responses'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

interface Req {
  method: string
  path: string
  body?: string
}
type Reply = { status: number; body: unknown } | 'reject'
type Matcher = string | ((r: Req) => boolean)
type Rule = [Matcher, Reply]

const on = (method: string, path: string) => (r: Req) =>
  r.method === method && (r.path === path || r.path.startsWith(`${path}?`))

/** `GET /api/progress/{exerciseId}`——路径里带 id，不能按 `/api/progress` 前缀匹配。 */
const onProgress = (r: Req) => r.method === 'GET' && r.path.startsWith('/api/progress/')

function dispatch(rules: Rule[]) {
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (_cmd: unknown, args: unknown) => {
    const req = args as unknown as Req
    for (const [m, reply] of rules) {
      const hit = typeof m === 'function' ? m(req) : req.path === m || req.path.startsWith(`${m}?`)
      if (!hit) continue
      if (reply === 'reject') throw new Error('连不上后端 127.0.0.1:1：拒绝连接')
      return {
        status: reply.status,
        body: typeof reply.body === 'string' ? reply.body : JSON.stringify(reply.body),
      }
    }
    throw new Error(`测试没有为 ${req.method} ${req.path} 准备应答`)
  })
}

/** `GET /api/exercises?q=...` 的一行。nameZh 为 null：手工录入的动作可能没有中文名。 */
const EXERCISES =
  '[{"id":10521,"nameEn":"ZZ Progress Http Bench","nameZh":null,"equipment":"barbell","isCompound":true,"category":"strength"}]'

/** 动作存在但从没练过：200 + points: []（不是 404）。 */
const NEVER_TRAINED =
  '{"exerciseId":10521,"nameEn":"ZZ Progress Http Bench","nameZh":null,"points":[]}'

/** 三次数值完全相同的合成点集：只用来压坐标换算（max === min 的除零）。 */
const ALL_EQUAL =
  '{"exerciseId":10521,"nameEn":"ZZ Progress Http Bench","nameZh":null,"points":[' +
  '{"date":"2026-10-14","e1rm":60.0,"maxWeight":60.0,"totalVolume":480.0},' +
  '{"date":"2026-10-15","e1rm":60.0,"maxWeight":60.0,"totalVolume":480.0},' +
  '{"date":"2026-10-16","e1rm":60.0,"maxWeight":60.0,"totalVolume":480.0}]}'

/** 两天的组全是 15 次：e1rm 估不出来（null），但 maxWeight/totalVolume 有值。 */
const E1RM_NEVER =
  '{"exerciseId":10521,"nameEn":"ZZ Progress Http Bench","nameZh":null,"points":[' +
  '{"date":"2026-10-14","e1rm":null,"maxWeight":20.0,"totalVolume":300.0},' +
  '{"date":"2026-10-15","e1rm":null,"maxWeight":25.0,"totalVolume":375.0}]}'

/**
 * 后端把 `e1rm` 改名成 `estimated1rm` 时的响应形状：前端运行时读到的是 `undefined`，不是 null。
 * 这张图必须降级成基线，而不是 min/max 变 NaN、SVG 静默不画。
 */
const RENAMED_E1RM =
  '{"exerciseId":10521,"nameEn":"ZZ Progress Http Bench","nameZh":null,"points":[' +
  '{"date":"2026-10-14","estimated1rm":76.0,"maxWeight":60.0,"totalVolume":480.0},' +
  '{"date":"2026-10-17","estimated1rm":75.83,"maxWeight":65.0,"totalVolume":325.0}]}'

function baseRules(overrides: Rule[] = []): Rule[] {
  return [
    ...overrides,
    [on('GET', '/api/exercises'), { status: 200, body: EXERCISES }],
    [onProgress, { status: 200, body: PROGRESS_THREE_DAYS }],
  ]
}

async function mountProgress(rules: Rule[] = baseRules()) {
  dispatch(rules)
  const wrapper = mount(ProgressView)
  await flushPromises()
  return wrapper
}

function reqs(): Req[] {
  // `InvokeArgs` 与这里的 `Req` 没有重叠，直接断言会被 TS 拒绝（TS2352）：
  // 先过一道 `unknown`，表明「我知道这条 mock 调用是我们自己造的」。
  return invokeMock.mock.calls.map((c) => c[1] as unknown as Req)
}

/** 搜一次「bench」并点第一条结果（夹具里那串真实 JSON 对应的动作）。 */
async function pickBench(wrapper: Awaited<ReturnType<typeof mountProgress>>) {
  await wrapper.find('.query').setValue('bench')
  await flushPromises()
  await wrapper.find('.exercise').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('进步曲线页 · 还没选动作', () => {
  it('shows_guidance_rather_than_a_blank_page', async () => {
    const wrapper = await mountProgress()

    // 第一次打开时还没选动作：给一句「选一个动作看走势」，不是空白
    expect(wrapper.find('.guide').text()).toContain('选一个动作看走势')
    expect(wrapper.find('.chart').exists()).toBe(false)
    expect(wrapper.find('.err').exists()).toBe(false)
    // 没选动作就不该请求曲线
    expect(reqs().some((r) => r.path.startsWith('/api/progress/'))).toBe(false)
  })
})

describe('进步曲线页 · 三条序列', () => {
  it('renders_three_charts_each_with_its_own_name_and_a_line', async () => {
    const wrapper = await mountProgress()
    await pickBench(wrapper)

    const charts = wrapper.findAll('.chart')
    expect(charts).toHaveLength(3)

    // 三条序列各自一张小图：量纲不同，叠在一张图上 totalVolume 会被压平
    expect(charts[0].attributes('data-series')).toBe('e1rm')
    expect(charts[1].attributes('data-series')).toBe('maxWeight')
    expect(charts[2].attributes('data-series')).toBe('totalVolume')

    expect(charts[0].find('.chart-name').text()).toContain('估算 1RM')
    expect(charts[1].find('.chart-name').text()).toContain('最大重量')
    expect(charts[2].find('.chart-name').text()).toContain('总容量')

    // 每张图都真的画了线，不是只有一个标题
    for (const chart of charts) {
      expect(chart.findAll('polyline').length).toBeGreaterThan(0)
    }
    // y 轴给最小/最大刻度
    expect(charts[1].find('.y-max').text()).toBe('65')
    expect(charts[1].find('.y-min').text()).toBe('20')
  })

  it('the_x_axis_is_dates_in_ascending_order', async () => {
    const wrapper = await mountProgress()
    await pickBench(wrapper)

    const chart = wrapper.find('[data-series="maxWeight"]')
    // 首尾日期就是那三个训练日的首尾，升序（不是倒过来）
    expect(chart.find('.x-first').text()).toBe('2026-10-14')
    expect(chart.find('.x-last').text()).toBe('2026-10-17')

    // 点的横坐标也确实从左到右递增（日期标签对了、点画反了同样看不出来）
    const cxs = chart.findAll('circle.dot').map((d) => Number(d.attributes('cx')))
    expect(cxs).toHaveLength(3)
    expect(cxs[0]).toBeLessThan(cxs[1])
    expect(cxs[1]).toBeLessThan(cxs[2])
  })

  it('points_that_are_null_in_e1rm_break_the_line_instead_of_crossing_it', async () => {
    // 真实夹具：10-14 有 e1rm、10-15 估不出（null）、10-17 又有 e1rm。
    const wrapper = await mountProgress()
    await pickBench(wrapper)

    const e1rm = wrapper.find('[data-series="e1rm"]')
    const segments = e1rm.findAll('polyline')

    // **两段**：把 null 当 0 画会合成一段（3 个点、假的下跌），
    // 直接跨过去连起来会合成一段（2 个点、不存在的趋势）——两种变异都会让这条红。
    expect(segments).toHaveLength(2)
    const segmentSizes = segments.map((s) => s.attributes('points')!.trim().split(/\s+/).length)
    expect(segmentSizes).toEqual([1, 1])

    // null 那天没有点：三个训练日只有两个 e1rm 点
    expect(e1rm.findAll('circle.dot')).toHaveLength(2)

    // 另外两张图照常画：它们有值，不该因为 e1rm 的 null 受影响
    const maxWeight = wrapper.find('[data-series="maxWeight"]')
    expect(maxWeight.findAll('polyline')).toHaveLength(1)
    expect(maxWeight.findAll('circle.dot')).toHaveLength(3)
  })

  it('an_all_null_e1rm_series_draws_a_baseline_and_a_note_not_an_empty_plot', async () => {
    const wrapper = await mountProgress(baseRules([[onProgress, { status: 200, body: E1RM_NEVER }]]))
    await pickBench(wrapper)

    const e1rm = wrapper.find('[data-series="e1rm"]')
    expect(e1rm.find('.chart-empty').exists()).toBe(true)
    expect(e1rm.find('.baseline').exists()).toBe(true)
    expect(e1rm.text()).toContain('还没有数据')
    expect(e1rm.findAll('polyline')).toHaveLength(0)

    // maxWeight 有值 → 照常一条线
    expect(wrapper.find('[data-series="maxWeight"]').findAll('polyline')).toHaveLength(1)
  })

  it('a_renamed_e1rm_key_degrades_to_a_baseline_instead_of_nan', async () => {
    // A2 的静态 fixture 是「某个时间点的真实形状」：后端将来改名时它不会自己变，
    // 那条守卫是后端的 ProgressControllerTest。这里守的是前端的运行时容错——
    // 读不到 e1rm（undefined）时不能把 min/max 算成 NaN 然后整张图静默消失。
    const wrapper = await mountProgress(baseRules([[onProgress, { status: 200, body: RENAMED_E1RM }]]))
    await pickBench(wrapper)

    expect(wrapper.html()).not.toContain('NaN')
    expect(wrapper.find('[data-series="e1rm"]').find('.chart-empty').exists()).toBe(true)
    expect(wrapper.find('[data-series="e1rm"]').text()).toContain('还没有数据')

    // 另外两条序列没被连累
    expect(wrapper.find('[data-series="maxWeight"]').findAll('polyline')).toHaveLength(1)
    expect(wrapper.find('[data-series="totalVolume"]').findAll('polyline')).toHaveLength(1)
  })
})

describe('进步曲线页 · 坐标换算', () => {
  it('larger_values_are_higher_on_screen_because_svg_y_grows_downward', async () => {
    const wrapper = await mountProgress()
    await pickBench(wrapper)

    const dots = wrapper.find('[data-series="maxWeight"]').findAll('circle.dot')
    // 60 → 20 → 65：中间那天最低、最后一天最高
    const cy = dots.map((d) => Number(d.attributes('cy')))

    expect(cy).toHaveLength(3)
    expect(cy[1]).toBeGreaterThan(cy[0]) // 20 kg 在 60 kg 下方
    expect(cy[2]).toBeLessThan(cy[0]) // 65 kg 在 60 kg 上方
  })

  it('equal_values_do_not_produce_nan_coordinates', async () => {
    // 三天同一个重量：max - min == 0，不特判就是除以 0 → y 变 NaN →
    // `points="NaN,…"` → SVG 静默不画（不报错，页面只是空着）。
    const wrapper = await mountProgress(baseRules([[onProgress, { status: 200, body: ALL_EQUAL }]]))
    await pickBench(wrapper)

    expect(wrapper.html()).not.toContain('NaN')
    for (const line of wrapper.findAll('polyline')) {
      expect(line.attributes('points')).not.toContain('NaN')
    }
    for (const dot of wrapper.findAll('circle.dot')) {
      expect(dot.attributes('cy')).not.toBe('NaN')
    }

    // 全部画在中线（三张图各 3 个点，同一张图里 cy 全相同）
    const cy = wrapper
      .find('[data-series="maxWeight"]')
      .findAll('circle.dot')
      .map((d) => Number(d.attributes('cy')))
    expect(new Set(cy).size).toBe(1)
  })
})

describe('进步曲线页 · 空态与错误态', () => {
  it('a_never_trained_exercise_says_so_instead_of_showing_an_error', async () => {
    const wrapper = await mountProgress(baseRules([[onProgress, { status: 200, body: NEVER_TRAINED }]]))
    await pickBench(wrapper)

    expect(wrapper.find('.empty').text()).toContain('还没练过这个动作')
    // 「没数据」不是「加载失败」，更不是红色错误
    expect(wrapper.find('.err').exists()).toBe(false)
    expect(wrapper.find('.chart').exists()).toBe(false)
  })

  it('a_404_shows_the_backend_message_not_a_hardcoded_failure', async () => {
    const message = '找不到这个动作: 10521'
    const wrapper = await mountProgress(baseRules([[onProgress, { status: 404, body: { error: message } }]]))
    await pickBench(wrapper)

    expect(wrapper.find('.err').text()).toBe(message)
    expect(wrapper.find('.err').text()).not.toContain('加载失败')
    expect(wrapper.find('.chart').exists()).toBe(false)
  })

  it('a_transport_failure_shows_the_reason_instead_of_a_blank_page', async () => {
    const wrapper = await mountProgress(baseRules([[onProgress, 'reject']]))
    await pickBench(wrapper)

    expect(wrapper.find('.err').text()).toContain('连不上')
    expect(wrapper.find('h2').text()).toBe('进步曲线')
  })
})

describe('进步曲线页 · 动作选择器', () => {
  it('reuses_searchExercises_and_only_needs_the_name', async () => {
    const wrapper = await mountProgress()

    await wrapper.find('.query').setValue('bench')
    await flushPromises()

    // 复用训练记录页那套 `/api/exercises`，没有肌群下拉、没有 /api/volume 请求
    const searches = reqs().filter((r) => r.path.startsWith('/api/exercises'))
    expect(searches).toHaveLength(1)
    expect(searches[0].path).toBe('/api/exercises?q=bench')
    expect(reqs().some((r) => r.path.startsWith('/api/volume'))).toBe(false)
    expect(wrapper.find('select').exists()).toBe(false)

    // 只显示名字：nameZh 为 null 时退到 nameEn
    const buttons = wrapper.findAll('.exercise')
    expect(buttons).toHaveLength(1)
    expect(buttons[0].find('.ex-name').text()).toBe('ZZ Progress Http Bench')
    expect(buttons[0].text()).toBe('ZZ Progress Http Bench')

    // 选中后按那个 id 取曲线
    await buttons[0].trigger('click')
    await flushPromises()
    const calls = reqs().filter((r) => r.path.startsWith('/api/progress/'))
    expect(calls).toHaveLength(1)
    expect(calls[0].path).toBe('/api/progress/10521')
  })
})

describe('进步曲线页 · 视觉 token', () => {
  it('colors_come_from_theme_tokens_only', () => {
    // 设计文档「九」：theme.css 是唯一颜色来源。与 BodyDataView 同一条 ?raw 断言。
    expect(progressViewSource).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(progressViewSource).not.toMatch(/\brgba?\s*\(/i)
    expect(progressViewSource).not.toMatch(/\bhsla?\s*\(/i)

    const tokenUses = progressViewSource.match(/var\(--[a-z0-9-]+\)/g) ?? []
    expect(tokenUses.length).toBeGreaterThanOrEqual(20)
  })
})
