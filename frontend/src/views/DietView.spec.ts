import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { DOMWrapper } from '@vue/test-utils'
import DietView from './DietView.vue'
import {
  DAILY,
  EMPTY_DAY,
  FOODS_TOFU,
  FOOD_RICE,
  MEAL_DETAIL,
} from '../api/__fixtures__/nutrition-responses'

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

/** 按「方法 + 路径」匹配。**这一页必须区分方法**：GET 和 POST 都打在 `/api/meals` 上。 */
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

/** 正常路径：当天的合计、当天的记录、搜到白米饭、保存成功。 */
function baseRules(overrides: Rule[] = []): Rule[] {
  return [
    ...overrides,
    [on('GET', '/api/nutrition/daily'), { status: 200, body: DAILY }],
    [on('GET', '/api/meals'), { status: 200, body: `[${MEAL_DETAIL}]` }],
    [(r) => r.method === 'GET' && r.path.startsWith('/api/foods'), { status: 200, body: FOOD_RICE }],
    [on('POST', '/api/meals'), { status: 200, body: { mealId: 1, items: 1, nutrition: JSON.parse(DAILY).nutrition } }],
  ]
}

async function mountDiet(rules: Rule[] = baseRules()) {
  dispatch(rules)
  const wrapper = mount(DietView)
  await flushPromises()
  return wrapper
}

function reqs(): Req[] {
  // `InvokeArgs` 与我们这里的 `Req` 没有重叠，直接断言会被 TS 拒绝：
  // 先过一道 `unknown`，表明「我知道这条 mock 调用是我们自己造的」。
  return invokeMock.mock.calls.map((c) => c[1] as unknown as Req)
}

function value(el: DOMWrapper<Element>): string {
  return (el.element as HTMLInputElement).value
}

/** 搜一次并选中第一条结果（白米饭）。 */
async function pickRice(wrapper: Awaited<ReturnType<typeof mountDiet>>) {
  await wrapper.find('.query').setValue('白米饭')
  await vi.advanceTimersByTimeAsync(300)
  await flushPromises()
  await wrapper.find('.food').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('饮食记录页 · 那一天的合计', () => {
  it('shows_the_days_total_from_the_backend', async () => {
    const wrapper = await mountDiet()

    const daily = wrapper.find('.daily')
    expect(daily.text()).toContain('421.5')
    expect(daily.text()).toContain('千卡')
    expect(wrapper.find('.macros').text()).toContain('50.4')
    expect(wrapper.find('.counts').text()).toContain('1 餐')
    expect(wrapper.find('.counts').text()).toContain('2 条目')
  })

  it('an_empty_day_shows_zeros_rather_than_an_error', async () => {
    // 「今天还没吃」是刚装上时的常态，不是错误。
    const wrapper = await mountDiet(baseRules([[on('GET', '/api/nutrition/daily'), { status: 200, body: EMPTY_DAY }]]))

    expect(wrapper.find('.daily').text()).toContain('0 千卡')
    expect(wrapper.find('.daily').text()).toContain('0 餐')
    expect(wrapper.find('.err').exists()).toBe(false)
  })

  it('lists_the_meals_of_that_day', async () => {
    const wrapper = await mountDiet()
    expect(wrapper.find('.meals').text()).toContain('午餐')
    expect(wrapper.find('.meals').text()).toContain('421.5')
    expect(wrapper.find('.meals').text()).toContain('白米饭')
  })

  it('changing_the_date_reloads_that_days_totals', async () => {
    const wrapper = await mountDiet(
      baseRules([
        [(r) => r.path === '/api/nutrition/daily?date=2026-08-20', { status: 200, body: EMPTY_DAY }],
      ]),
    )
    expect(wrapper.find('.daily').text()).toContain('421.5')

    await wrapper.find('.meal-date').setValue('2026-08-20')
    await flushPromises()

    expect(wrapper.find('.daily').text()).toContain('0 千卡')
    expect(reqs().some((r) => r.path === '/api/nutrition/daily?date=2026-08-20')).toBe(true)
  })

  it('a_failed_total_shows_the_reason_instead_of_a_blank_page', async () => {
    const wrapper = await mountDiet(baseRules([[on('GET', '/api/nutrition/daily'), 'reject']]))
    expect(wrapper.find('.daily').text()).toContain('连不上')
    expect(wrapper.find('h2').text()).toBe('饮食记录')
  })
})

describe('饮食记录页 · 搜食物与加条目', () => {
  it('searching_debounces_and_lists_matches_with_their_per100', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet(baseRules([
        [(r) => r.method === 'GET' && r.path.startsWith('/api/foods'), { status: 200, body: FOODS_TOFU }],
      ]))
      const q = wrapper.find('.query')

      await q.setValue('豆')
      await q.setValue('豆腐')
      expect(reqs().filter((r) => r.path.startsWith('/api/foods?'))).toHaveLength(0)

      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()

      const searched = reqs().filter((r) => r.path.startsWith('/api/foods?'))
      expect(searched).toHaveLength(1)
      expect(searched[0].path).toBe('/api/foods?q=%E8%B1%86%E8%85%90')
      expect(wrapper.findAll('.food')).toHaveLength(2)
      // 列表里就该带上「每 100 克多少千卡」——那是选食物时要看的
      expect(wrapper.find('.food').text()).toContain('98')
    } finally {
      vi.useRealTimers()
    }
  })

  it('picking_a_food_adds_a_row_with_a_sensible_default_unit', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet()
      await pickRice(wrapper)

      const row = wrapper.find('.draft-row')
      expect(row.find('.name').text()).toBe('白米饭')
      expect(value(row.find('.amount'))).toBe('1')
      // 默认是「它的第一个单位」，不是「100 克」——用户想的是「一碗饭」
      expect(value(row.find('.unit'))).toBe('碗')
      expect(row.find('.grams').text()).toBe('150 克')
      // 预览：150 克 × 116 kcal/100 克 = 174
      expect(row.find('.preview').text()).toBe('174 千卡')
    } finally {
      vi.useRealTimers()
    }
  })

  it('switching_the_unit_to_grams_treats_the_amount_as_grams', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet()
      await pickRice(wrapper)

      const row = wrapper.find('.draft-row')
      await row.find('.amount').setValue('150')
      // 选中「克」那一项。**按它在 DOM 里的实际 value 选**，不猜它是 `''` 还是别的：
      // 这一项绑的是 `null`，而 Vue 把一个非字符串值放进 DOM 时具体表示成什么，
      // 是实现细节（第一次按 `''` 猜就猜错了——那个 select 因此没有选中项，
      // v-model 拿到 undefined，整行渲染不出来）。
      const gramOption = row.findAll('.unit option').find((o) => o.text() === '克')!
      await row.find('.unit').setValue((gramOption.element as HTMLOptionElement).value)
      await flushPromises()

      expect(wrapper.find('.grams').text()).toBe('150 克')
      expect(wrapper.find('.preview').text()).toBe('174 千卡')

      // 而且请求里那一项必须是 **null**（= 按克录），不是空串——后端靠 null 判断
      // 「amount 本身就是克」，空串走的是另一条分支（会被当成单位名去查表）。
      await wrapper.find('.save').trigger('click')
      await flushPromises()
      const post = reqs().find((r) => r.method === 'POST')!
      expect(JSON.parse(post.body!).items[0]).toEqual({ foodId: 1, amount: 150, unitName: null })
    } finally {
      vi.useRealTimers()
    }
  })

  it('the_total_preview_follows_the_rows', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet()
      await pickRice(wrapper)
      expect(wrapper.find('.total').text()).toContain('174')

      // 再加一条同样的
      await wrapper.find('.query').setValue('白米饭')
      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()
      await wrapper.find('.food').trigger('click')
      await flushPromises()

      expect(wrapper.findAll('.draft-row')).toHaveLength(2)
      expect(wrapper.find('.total').text()).toContain('348')
    } finally {
      vi.useRealTimers()
    }
  })

  it('deleting_a_row_removes_just_that_one', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet()
      await pickRice(wrapper)
      // 第二条换成豆腐
      await dispatchRulesWithTofu(wrapper)
      expect(wrapper.findAll('.draft-row')).toHaveLength(2)

      await wrapper.findAll('.remove')[0].trigger('click')
      await flushPromises()

      const rows = wrapper.findAll('.draft-row')
      expect(rows).toHaveLength(1)
      expect(rows[0].find('.name').text()).toBe('豆腐（北）')
    } finally {
      vi.useRealTimers()
    }
  })

  /** 把搜索应答换成豆腐那两条，然后加一条。 */
  async function dispatchRulesWithTofu(wrapper: Awaited<ReturnType<typeof mountDiet>>) {
    dispatch(baseRules([
      [(r) => r.method === 'GET' && r.path.startsWith('/api/foods'), { status: 200, body: FOODS_TOFU }],
    ]))
    await wrapper.find('.query').setValue('豆腐')
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()
    await wrapper.find('.food').trigger('click')
    await flushPromises()
  }
})

describe('饮食记录页 · 保存', () => {
  it('saving_posts_the_meal_and_shows_the_backends_total', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet()
      await pickRice(wrapper)

      await wrapper.find('.save').trigger('click')
      await flushPromises()

      const post = reqs().find((r) => r.method === 'POST')!
      const body = JSON.parse(post.body!)
      expect(body.mealDate).toBe(new Date().toLocaleDateString('sv-SE'))
      expect(body.slot).toBe('lunch')
      expect(body.items).toEqual([{ foodId: 1, amount: 1, unitName: '碗' }])
      // **契约：不带 grams**——克数由后端算
      expect(post.body).not.toContain('grams')

      // 显示的合计是**后端返回**的那个
      expect(wrapper.text()).toContain('已记录：1 个条目')
      expect(wrapper.text()).toContain('421.5 千卡')
      // 草稿清空
      expect(wrapper.findAll('.draft-row')).toHaveLength(0)
      // 当天的合计被重新取了一次
      expect(reqs().filter((r) => r.path.startsWith('/api/nutrition/daily'))).toHaveLength(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a_400_keeps_the_draft_and_shows_the_server_message', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet(
        baseRules([[on('POST', '/api/meals'), { status: 400, body: { error: '一餐至少要有一个条目' } }]]),
      )
      await pickRice(wrapper)

      await wrapper.find('.save').trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('一餐至少要有一个条目')
      // **草稿不清空**：把用户填的东西丢掉是最气人的事
      expect(wrapper.findAll('.draft-row')).toHaveLength(1)
      expect(wrapper.find('.grams').text()).toBe('150 克')
    } finally {
      vi.useRealTimers()
    }
  })

  it('refuses_to_submit_an_empty_draft', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet()
      // 没有草稿时保存按钮不存在（那一段没渲染）
      expect(wrapper.find('.save').exists()).toBe(false)
      expect(reqs().some((r) => r.method === 'POST')).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('饮食记录页 · 把口径写在界面上', () => {
  it('explains_that_per_100g_is_a_property_and_the_number_is_a_preview', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountDiet()
      await pickRice(wrapper)

      expect(wrapper.find('.hint').text()).toContain('食物属性')
      expect(wrapper.find('.hint').text()).toContain('这一次的份量')
      expect(wrapper.find('.total').text()).toContain('预览')
      expect(wrapper.find('.total').text()).toContain('后端算')
    } finally {
      vi.useRealTimers()
    }
  })
})
