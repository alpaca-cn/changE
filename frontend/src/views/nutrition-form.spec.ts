import { describe, expect, it } from 'vitest'
import type { FoodSummary, MealDetail } from '../api/nutrition'
import {
  FOODS_TOFU,
  FOOD_CHICKEN,
  FOOD_RICE,
  MEAL_DETAIL,
} from '../api/__fixtures__/nutrition-responses'
import {
  ZERO,
  draftItem,
  draftNutrition,
  draftTotal,
  scaleNutrition,
  sumNutrition,
  toGrams,
  toMealInput,
  validationError,
  type DraftItem,
} from './nutrition-form'

const detail: MealDetail = JSON.parse(MEAL_DETAIL)
const rice: FoodSummary = JSON.parse(FOOD_RICE)[0]
const chicken: FoodSummary = JSON.parse(FOOD_CHICKEN)[0]
const tofu: FoodSummary = JSON.parse(FOODS_TOFU)[0]

/** 造一行草稿。 */
function draft(food: FoodSummary, amount: string, unitName: string | null): DraftItem {
  const base = draftItem(food)
  return { ...base, amount, unitName }
}

describe('份量换算（与后端 PortionConverter.toGrams 同口径）', () => {
  it('单位名给了就按 food_portion 换算', () => {
    expect(toGrams('1', '碗', rice.portions)).toBe(150)
    expect(toGrams('1.5', '碗', rice.portions)).toBe(225)
  })

  it('单位名为 null 或空串时，amount 本身就是克', () => {
    // 与后端 toGrams 的 unit == null 分支是同一个语义。
    expect(toGrams('150', null, rice.portions)).toBe(150)
    expect(toGrams('150', '  ', rice.portions)).toBe(150)
  })

  it('单位表里查不到的单位返回 null，而不是 NaN', () => {
    // 界面会把 null 显示成「—」。返回 NaN 的话整行会变成 NaN kcal，
    // 而那看起来像「食物数据有问题」，实际是选错了单位。
    expect(toGrams('1', '盆', rice.portions)).toBeNull()
  })

  it('没填份量、或份量不是正数，都返回 null', () => {
    expect(toGrams('', '碗', rice.portions)).toBeNull()
    expect(toGrams('   ', '碗', rice.portions)).toBeNull()
    expect(toGrams('0', '碗', rice.portions)).toBeNull()
    expect(toGrams('-1', '碗', rice.portions)).toBeNull()
    expect(toGrams('abc', '碗', rice.portions)).toBeNull()
  })

  it('克数保持未定标（定标只在预览那一步）', () => {
    // calc/package-info 约定二：先舍再累加会有方向性偏差。
    // 后端 1.5 × 55.5 = 83.25 是**未舍入的乘积**，前端也一样。
    const odd: FoodSummary = { ...rice, portions: [{ unitName: '个', grams: 55.5 }] }
    expect(toGrams('1.5', '个', odd.portions)).toBe(83.25)
  })
})

describe('契约：前端预览必须复现后端的数字', () => {
  /**
   * **这是本文件里最重要的两条。** 真机夹具里每一项都同时带着
   * 「后端算好的 nutrition」与「后端存下来的 grams」，而食物夹具带着 per100——
   * 于是前端可以只用 per100 + grams 复算一遍，和后端的结果逐字段比对。
   *
   * 两边一旦分叉（舍入位数不同、把每 100 克的值当成份量的值、忘记乘、乘错单位……），
   * 界面上就会显示 421.49 而存进去是 421.5，用户会以为程序算错了。
   */
  it('每一项的预览热量与后端算的一致', () => {
    const pairs = [
      { item: detail.items[0], food: rice },
      { item: detail.items[1], food: chicken },
    ]

    for (const { item, food } of pairs) {
      // 1) 克数：前端按「份量 + 单位」算出来的，必须等于后端存下来的那一列
      expect(toGrams(String(item.amount), item.unitName, food.portions)).toBe(item.grams)
      // 2) 每一项的营养值：逐字段比对（carb 是 38.85——只保留一位小数就会露馅）
      expect(scaleNutrition(food.per100, item.grams)).toEqual(item.nutrition)
    }
  })

  it('合计也与后端算的一致', () => {
    const each = [
      scaleNutrition(rice.per100, detail.items[0].grams),
      scaleNutrition(chicken.per100, detail.items[1].grams),
    ]
    expect(sumNutrition(each)).toEqual(detail.nutrition)
    expect(detail.nutrition.kcal).toBe(421.5)
  })

  it('两位小数的口径与后端 Round.to2 一致', () => {
    // 后端是 Math.round(v * 100) / 100。只保留一位的话这里会得到 123.5。
    expect(scaleNutrition({ ...ZERO, kcal: 123.456 }, 100).kcal).toBe(123.46)
  })
})

describe('草稿', () => {
  it('选了一个食物，默认是「1 个它的第一个单位」', () => {
    const d = draftItem(rice)
    expect(d.amount).toBe('1')
    expect(d.unitName).toBe('碗')
  })

  it('没有单位表的食物退回「按克」', () => {
    const d = draftItem({ ...rice, portions: [] })
    expect(d.unitName).toBeNull()
  })

  it('每一行的 id 唯一（删行之后 key 不会错位）', () => {
    expect(draftItem(rice).id).not.toBe(draftItem(rice).id)
  })

  it('draftNutrition 算不出来时返回 null，而不是 0', () => {
    // 「还算不出来」与「这份是 0 千卡」是两件事：前者界面显示「—」，
    // 后者显示 0。混成一个会让用户以为某个食物没有热量。
    expect(draftNutrition(draft(rice, '', '碗'))).toBeNull()
    expect(draftNutrition(draft(rice, '1', '盆'))).toBeNull()
    expect(draftNutrition(draft(rice, '1', '碗'))?.kcal).toBe(174)
  })

  it('draftTotal 把算不出来的行按 0 计，不抛错也不出 NaN', () => {
    const items = [draft(rice, '1', '碗'), draft(chicken, '', null)]
    const total = draftTotal(items)
    expect(total.kcal).toBe(174)
    expect(Number.isNaN(total.kcal)).toBe(false)
  })

  it('draftTotal 是两项之和', () => {
    const items = [draft(rice, '1', '碗'), draft(chicken, '150', null)]
    expect(draftTotal(items).kcal).toBe(421.5)
  })
})

describe('请求体', () => {
  it('**不带 grams**：克数由后端算', () => {
    const input = toMealInput({
      mealDate: '2026-10-19',
      slot: 'lunch',
      eatenAt: null,
      note: '食堂',
      items: [draft(rice, '1', '碗'), draft(chicken, '150', null)],
    })

    expect(input.items).toEqual([
      { foodId: 1, amount: 1, unitName: '碗' },
      { foodId: 9, amount: 150, unitName: null },
    ])
    for (const item of input.items) {
      expect(Object.keys(item)).not.toContain('grams')
    }
  })

  it('份量是数字，不是字符串', () => {
    const input = toMealInput({
      mealDate: '2026-10-19',
      slot: 'lunch',
      eatenAt: null,
      note: '',
      items: [draft(tofu, '1.5', '块')],
    })
    expect(input.items[0].amount).toBe(1.5)
    expect(typeof input.items[0].amount).toBe('number')
  })

  it('空备注写 null 而不是空串', () => {
    const input = toMealInput({
      mealDate: '2026-10-19',
      slot: 'snack',
      eatenAt: null,
      note: '   ',
      items: [draft(rice, '1', '碗')],
    })
    expect(input.note).toBeNull()
  })
})

describe('提交前的校验', () => {
  it('没有条目 → 提示', () => {
    expect(validationError('2026-10-19', [])).toContain('条目')
  })

  it('没选日期 → 提示', () => {
    expect(validationError('', [draft(rice, '1', '碗')])).toContain('日期')
  })

  it('份量没填或不合法 → 指出是哪一条食物', () => {
    expect(validationError('2026-10-19', [draft(rice, '1', '碗'), draft(chicken, '', null)]))
      .toContain('鸡胸肉（熟）')
  })

  it('都填好了 → 通过', () => {
    expect(validationError('2026-10-19', [draft(rice, '1', '碗')])).toBeNull()
  })
})
