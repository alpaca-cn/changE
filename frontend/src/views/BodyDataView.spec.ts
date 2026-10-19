import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { DOMWrapper } from '@vue/test-utils'
import BodyDataView from './BodyDataView.vue'
// 用 `?raw` 读源码而不是 node:fs：jsdom 下 Vite 会把 node:fs 换成空壳
// （见 styles/theme.spec.ts 顶部那段注释），而 `?raw` 在构建/测试两个环境都只是字符串。
import bodyDataViewSource from './BodyDataView.vue?raw'
import { METRIC_TYPES } from '../api/body'

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

/** 按「方法 + 路径」匹配：GET 与 POST 都打在 `/api/body-metrics` 上，必须区分。 */
const on = (method: string, path: string) => (r: Req) =>
  r.method === method && (r.path === path || r.path.startsWith(`${path}?`))

function dispatch(rules: Rule[]) {
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (_cmd: unknown, args: unknown) => {
    const req = args as Req
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

/** 真机形状：`types` 按枚举序（weight 在前），时间线**按日期升序**（页面要倒过来）。 */
const TYPES =
  '[{"metricType":"weight","unit":"kg","latestValue":72.5,"latestOn":"2026-10-19","count":3},' +
  '{"metricType":"waist","unit":"cm","latestValue":80,"latestOn":"2026-10-18","count":1}]'

const TIMELINE =
  '[{"id":1,"measuredOn":"2026-10-01","metricType":"weight","value":73,"unit":"kg","note":null},' +
  '{"id":2,"measuredOn":"2026-10-19","metricType":"weight","value":72.5,"unit":"kg","note":"早上空腹"}]'

function baseRules(overrides: Rule[] = []): Rule[] {
  return [
    ...overrides,
    [on('GET', '/api/body-metrics/types'), { status: 200, body: TYPES }],
    [on('GET', '/api/body-metrics'), { status: 200, body: TIMELINE }],
    [on('POST', '/api/body-metrics'), { status: 200, body: { id: 7 } }],
  ]
}

async function mountBody(rules: Rule[] = baseRules()) {
  dispatch(rules)
  const wrapper = mount(BodyDataView)
  await flushPromises()
  return wrapper
}

function reqs(): Req[] {
  // `InvokeArgs` 与我们这里的 `Req` 没有重叠，直接断言会被 TS 拒绝（TS2352）：
  // 先过一道 `unknown`，表明「我知道这条 mock 调用是我们自己造的」。
  return invokeMock.mock.calls.map((c) => c[1] as unknown as Req)
}

function value(el: DOMWrapper<Element>): string {
  return (el.element as HTMLInputElement).value
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('身体数据页 · 空态不是错误', () => {
  it('an_empty_library_shows_guidance_rather_than_an_error', async () => {
    const wrapper = await mountBody(
      baseRules([
        [on('GET', '/api/body-metrics/types'), { status: 200, body: '[]' }],
        [on('GET', '/api/body-metrics'), { status: 200, body: '[]' }],
      ]),
    )

    // 「空」不是错误：第一次打开看到的是引导，不是红色报错
    expect(wrapper.find('.empty').text()).toContain('还没有记录')
    expect(wrapper.find('.err').exists()).toBe(false)

    // 九个固定卡片不消失，没记过的显示「—」，**不是 0**
    const cards = wrapper.findAll('.metric-card')
    expect(cards).toHaveLength(9)
    for (const card of cards) {
      expect(card.find('.metric-value').text()).toBe('—')
    }
  })

  it('an_empty_types_table_does_not_crash_and_still_offers_the_first_record', async () => {
    // 全新库里 `types()` 就是 `[]`（计划 6a Task 2 的 HTTP 实测）。
    // 若页面直接读 `types[0].latestValue` 就会崩在第一次使用；而且录入区
    // 不能因此没有选项——否则第一批数据永远记不进去。
    const wrapper = await mountBody(
      baseRules([
        [on('GET', '/api/body-metrics/types'), { status: 200, body: '[]' }],
        [on('GET', '/api/body-metrics'), { status: 200, body: '[]' }],
      ]),
    )

    expect(wrapper.find('h2').text()).toBe('身体数据')
    expect(wrapper.find('.empty').text()).toContain('还没有记录')
    expect(wrapper.find('.err').exists()).toBe(false)
    expect(wrapper.findAll('.metric-type option')).toHaveLength(9)
  })

  it('cards_show_the_latest_value_and_dash_for_types_never_recorded', async () => {
    const wrapper = await mountBody()

    const weight = wrapper.find('[data-metric="weight"]')
    expect(weight.find('.metric-value').text()).toContain('72.5')
    expect(weight.find('.metric-value').text()).toContain('kg')
    expect(weight.find('.metric-meta').text()).toContain('2026-10-19')
    expect(weight.find('.metric-meta').text()).toContain('3 条')

    const fat = wrapper.find('[data-metric="body_fat"]')
    expect(fat.find('.metric-value').text()).toBe('—')
    expect(fat.find('.metric-meta').text()).toBe('还没记过')
  })

  it('a_failed_types_request_shows_the_reason_instead_of_a_blank_page', async () => {
    const wrapper = await mountBody(baseRules([[on('GET', '/api/body-metrics/types'), 'reject']]))

    expect(wrapper.find('.err').text()).toContain('连不上')
    expect(wrapper.find('h2').text()).toBe('身体数据')
  })
})

describe('身体数据页 · 时间线', () => {
  it('lists_measurements_newest_first', async () => {
    const wrapper = await mountBody()

    const rows = wrapper.findAll('.timeline-row')
    expect(rows).toHaveLength(2)
    // 后端按日期**升序**返回，页面要倒过来：最近的在最上面
    expect(rows[0].find('.date').text()).toBe('2026-10-19')
    expect(rows[1].find('.date').text()).toBe('2026-10-01')

    // 备注可选：没写的那条显示「—」，不是空白
    expect(rows[0].find('.note').text()).toBe('早上空腹')
    expect(rows[1].find('.note').text()).toBe('—')

    // 数值必须带单位：体重与围度混在一张表里，光有数字会被读错
    expect(rows[1].find('.value').text()).toContain('73')
    expect(rows[1].find('.value').text()).toContain('kg')
  })

  it('an_empty_timeline_is_not_an_error', async () => {
    const wrapper = await mountBody(
      baseRules([
        [on('GET', '/api/body-metrics/types'), { status: 200, body: '[]' }],
        [on('GET', '/api/body-metrics'), { status: 200, body: '[]' }],
      ]),
    )

    expect(wrapper.find('.timeline').exists()).toBe(false)
    expect(wrapper.find('.err').exists()).toBe(false)
  })
})

describe('身体数据页 · 录入', () => {
  it('submitting_posts_the_measurement_and_refreshes_the_list', async () => {
    const wrapper = await mountBody()

    await wrapper.find('.metric-type').setValue('waist')
    await wrapper.find('.value-input').setValue('80.5')
    await wrapper.find('.note-input').setValue('腰')
    await wrapper.find('.save').trigger('click')
    await flushPromises()

    const posts = reqs().filter((r) => r.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(JSON.parse(posts[0].body!)).toEqual({
      // 日期默认今天，但可以改（补录昨天的体重是常见需求）
      measuredOn: new Date().toLocaleDateString('sv-SE'),
      metricType: 'waist',
      value: 80.5,
      // 前端不填单位：后端按类型补，填了就等于给用户一个弄错的机会
      unit: null,
      note: '腰',
    })

    // 成功后列表刷新：时间线被重新取了一次（挂载 1 次 + 提交后 1 次）
    expect(reqs().filter((r) => r.method === 'GET' && r.path === '/api/body-metrics')).toHaveLength(2)
    expect(value(wrapper.find('.value-input'))).toBe('')
    expect(wrapper.find('.ok').text()).toContain('已记录')
  })

  it('an_empty_note_is_sent_as_null', async () => {
    const wrapper = await mountBody()

    await wrapper.find('.value-input').setValue('72.4')
    await wrapper.find('.save').trigger('click')
    await flushPromises()

    const post = reqs().find((r) => r.method === 'POST')!
    expect(JSON.parse(post.body!).note).toBeNull()
  })

  it('negative_values_are_stopped_before_the_request', async () => {
    // 最轻的一层拦：负数不发请求（但 0 放过去——「0 公斤」是用户自己的事）
    const wrapper = await mountBody()

    await wrapper.find('.value-input').setValue('-5')
    await wrapper.find('.save').trigger('click')
    await flushPromises()

    expect(wrapper.find('.save-error').text()).toContain('不能为负')
    expect(reqs().some((r) => r.method === 'POST')).toBe(false)
  })

  it('a_backend_400_is_shown_with_the_servers_own_message', async () => {
    // 体脂 110% 前端不知道算不算错——那是后端的类型知识，所以放它过去；
    // 后端回 400 后界面必须显示**它那句**，不是写死的「请求失败」。
    const serverMessage = '体脂率必须 ≤ 100：110'
    const wrapper = await mountBody(
      baseRules([[on('POST', '/api/body-metrics'), { status: 400, body: { error: serverMessage } }]]),
    )

    await wrapper.find('.metric-type').setValue('body_fat')
    await wrapper.find('.value-input').setValue('110')
    await wrapper.find('.save').trigger('click')
    await flushPromises()

    expect(wrapper.find('.save-error').text()).toBe(serverMessage)
    expect(wrapper.find('.save-error').text()).not.toContain('请求失败')
    // 服务端拒绝时表单不清空：把用户填的东西丢掉是最气人的事
    expect(value(wrapper.find('.value-input'))).toBe('110')
  })
})

describe('身体数据页 · 指标选择', () => {
  it('the_type_dropdown_lists_the_nine_known_types_in_enum_order', async () => {
    const wrapper = await mountBody()

    const options = wrapper.findAll('.metric-type option')
    expect(options.map((o) => (o.element as HTMLOptionElement).value)).toEqual([...METRIC_TYPES])
    expect(options.map((o) => o.text())).toEqual([
      '体重',
      '体脂率',
      '腰围',
      '胸围',
      '臀围',
      '臂围',
      '大腿围',
      '小腿围',
      '肩围',
    ])
  })

  it('the_unit_hint_follows_the_selected_type', async () => {
    // 界面上**显示**单位，但没有单位输入框
    const wrapper = await mountBody()

    expect(wrapper.find('.unit-hint').text()).toBe('kg')
    await wrapper.find('.metric-type').setValue('waist')
    expect(wrapper.find('.unit-hint').text()).toBe('cm')
    await wrapper.find('.metric-type').setValue('body_fat')
    expect(wrapper.find('.unit-hint').text()).toBe('%')
  })
})

describe('身体数据页 · 视觉 token', () => {
  it('colors_come_from_theme_tokens_only', () => {
    // 设计文档「九」：theme.css 是唯一颜色来源。grep 式断言——
    // 谁写死一个 #ffffff 或 rgb(...)，这条就红。
    expect(bodyDataViewSource).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(bodyDataViewSource).not.toMatch(/\brgba?\s*\(/i)
    expect(bodyDataViewSource).not.toMatch(/\bhsla?\s*\(/i)

    const tokenUses = bodyDataViewSource.match(/var\(--[a-z0-9-]+\)/g) ?? []
    expect(tokenUses.length).toBeGreaterThanOrEqual(20)
  })
})
