import { beforeEach, describe, expect, it, vi } from 'vitest'

// 与其它前端 spec 同一套：前端不发 HTTP，mock `invoke`（走 api_request 命令）。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { ApiError, ApiTransportError } from './training'
import { dailyNutrition, mealDetail, mealsOn, recordMeal, searchFoods } from './nutrition'
import { DAILY, FOODS_TOFU, MEAL_DETAIL } from './__fixtures__/nutrition-responses'

const invokeMock = vi.mocked(invoke)

function reply(status: number, body: unknown) {
  invokeMock.mockResolvedValue({
    status,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

function lastCall() {
  const c = invokeMock.mock.calls.at(-1)!
  return { cmd: c[0] as string, args: c[1] as { method: string; path: string; body?: string } }
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('请求形状', () => {
  it('搜食物：q 要 URL 编码，limit 只在给了的时候出现', async () => {
    reply(200, '[]')
    await searchFoods({ q: '豆腐' })
    expect(lastCall().args.path).toBe('/api/foods?q=%E8%B1%86%E8%85%90')

    reply(200, '[]')
    await searchFoods({ q: 'tofu', limit: 5 })
    expect(lastCall().args.path).toBe('/api/foods?q=tofu&limit=5')
  })

  it('搜食物：什么参数都不给就是裸路径（列出前若干条）', async () => {
    reply(200, '[]')
    await searchFoods()
    expect(lastCall().args.path).toBe('/api/foods')
    reply(200, '[]')
    await searchFoods({})
    expect(lastCall().args.path).toBe('/api/foods')
  })

  it('读一餐：路径带 id', async () => {
    reply(200, MEAL_DETAIL)
    await mealDetail(1)
    expect(lastCall().args.path).toBe('/api/meals/1')
  })

  it('某天的餐：date 省略时**不带查询串**（今天由后端决定）', async () => {
    reply(200, '[]')
    await mealsOn()
    expect(lastCall().args.path).toBe('/api/meals')

    reply(200, '[]')
    await mealsOn('2026-10-19')
    expect(lastCall().args.path).toBe('/api/meals?date=2026-10-19')
  })

  it('当天合计：同上——前端不自己算今天', async () => {
    reply(200, DAILY)
    await dailyNutrition()
    expect(lastCall().args.path).toBe('/api/nutrition/daily')

    reply(200, DAILY)
    await dailyNutrition('2026-10-19')
    expect(lastCall().args.path).toBe('/api/nutrition/daily?date=2026-10-19')
  })
})

describe('写一餐', () => {
  it('POST 到 /api/meals，body 里没有 grams', async () => {
    const input = {
      mealDate: '2026-10-19',
      slot: 'lunch',
      eatenAt: null,
      note: '食堂',
      items: [{ foodId: 1, amount: 1, unitName: '碗' }],
    }
    reply(200, { mealId: 1, items: 1, nutrition: DAILY })

    await recordMeal(input)

    const { args } = lastCall()
    expect(args.method).toBe('POST')
    expect(args.path).toBe('/api/meals')
    expect(JSON.parse(args.body!)).toEqual(input)
    expect(args.body).not.toContain('grams')
  })

  it('400 带着后端那句中文校验文案', async () => {
    reply(400, '{"error":"一餐至少要有一个条目"}')
    const err = await recordMeal({
      mealDate: '2026-10-19',
      slot: 'lunch',
      eatenAt: null,
      note: null,
      items: [],
    }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).message).toBe('一餐至少要有一个条目')
  })
})

describe('解析真机夹具', () => {
  it('搜食物的结果是带 per100 与 portions 的数组', async () => {
    reply(200, FOODS_TOFU)
    const foods = await searchFoods({ q: '豆腐' })

    expect(foods).toHaveLength(2)
    expect(foods[0].nameZh).toBe('豆腐（北）')
    expect(foods[0].per100.kcal).toBe(98)
    expect(foods[0].portions[0]).toEqual({ unitName: '块', grams: 100 })
    // 没有单位的食物给空数组，前端可以直接 .length
    expect(Array.isArray(foods[0].portions)).toBe(true)
  })

  it('一餐的详情带每一项的 grams 与 nutrition', async () => {
    reply(200, MEAL_DETAIL)
    const d = await mealDetail(1)

    expect(d.items).toHaveLength(2)
    expect(d.items[0].grams).toBe(150)
    expect(d.items[1].unitName).toBeNull() // 按克录的那一条
    expect(d.items[0].nutrition.kcal).toBe(174)
    expect(d.nutrition.kcal).toBe(421.5)
  })

  it('当天的合计：空的一天是 200 + 全 0，**不是错误**', async () => {
    reply(200, '{"date":"2026-08-20","nutrition":{"kcal":0,"protein":0,"carb":0,"fat":0,"fiber":0,"sodium":0},"meals":0,"items":0}')
    const d = await dailyNutrition('2026-08-20')

    expect(d.meals).toBe(0)
    expect(d.nutrition.kcal).toBe(0)
  })

  it('不存在的餐是 404（ApiError，不是传输失败）', async () => {
    reply(404, '{"error":"找不到这一餐: 999999"}')
    const err = await mealDetail(999999).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(404)
    expect((err as Error).message).toContain('999999')
  })

  it('传输失败原样穿透（后端没起、连不上）', async () => {
    invokeMock.mockRejectedValue(new Error('连不上后端 127.0.0.1:1：拒绝连接'))
    const err = await searchFoods({ q: 'x' }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiTransportError)
    expect((err as Error).message).toContain('连不上')
  })
})
