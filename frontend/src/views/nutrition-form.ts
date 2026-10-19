import type { FoodSummary, MealInput, MealSlot, Nutrition, Portion } from '../api/nutrition'
import { toNumber } from './training-form'

/**
 * 饮食记录页的表单逻辑。
 *
 * 单独成模块的理由与 `training-form.ts` / `volume-status.ts` 相同：
 * `<script setup>` **不允许具名导出**，这些纯函数塞进组件就没法被单测直接调用，
 * 而它们恰好是这一页最容易写错的地方（份量换算、空值、合计）。
 *
 * **它的每一处算法都与后端对应的一处逐字对齐**——不是巧合，是必须：
 * 界面上显示的是**预览**，保存后显示的是后端算的权威值；两者只要差一点点，
 * 用户就会以为程序算错了。`nutrition-form.spec.ts` 里有一条**契约测试**
 * 拿真机响应的数字把两边钉在一起。
 */

/** 全 0。空的一餐、空的一天都用它。 */
export const ZERO: Nutrition = { kcal: 0, protein: 0, carb: 0, fat: 0, fiber: 0, sodium: 0 }

/** 草稿里的一行。份量用**字符串**存——输入框给的就是字符串。 */
export interface DraftItem {
  /** 稳定标识，用作 `:key`。**不用数组下标**：删掉中间一行后下标会错位。 */
  id: number
  food: FoodSummary
  amount: string
  /** null 表示**按克**录（与后端 `meal_item.unit_name` 为 null 的语义一致）。 */
  unitName: string | null
}

let nextId = 1

/**
 * 选了一个食物之后的那一行草稿。
 *
 * 默认份量是 `1` + 该食物的**第一个单位**；没有任何单位时退回「按克」。
 * 不默认成「100 克」是因为用户想的是「一碗饭」而不是「150 克饭」。
 */
export function draftItem(food: FoodSummary): DraftItem {
  const first = food.portions.length > 0 ? food.portions[0].unitName : null
  return { id: nextId++, food, amount: '1', unitName: first }
}

/**
 * 份量 → 克数。**与后端 `PortionConverter.toGrams` 同口径**：
 * - `unitName` 为 null（或空白）时，`amount` **本身就是克数**；
 * - 单位名在同一食物的单位表里查不到 → 返回 null（界面显示「—」而不是 NaN）；
 * - 返回值**未定标**——定标只在预览那一步做（`calc/package-info` 约定二）。
 *
 * 返回 null 表示「还算不出来」（没填份量、或份量不是正数）。
 */
export function toGrams(amountText: string, unitName: string | null, portions: Portion[]): number | null {
  const amount = toNumber(amountText)
  if (amount === null || amount <= 0) return null
  if (unitName === null || unitName.trim() === '') return amount
  const portion = portions.find((p) => p.unitName === unitName)
  if (portion === undefined) return null
  return amount * portion.grams
}

/**
 * 每 100 克的值 × 实际克数。**与后端 `NutritionCalculator.forGrams` 同口径**，
 * 包括**两位小数**那一步（后端用的是 `calc.Round.to2`）。
 *
 * 定标这一步必须在预览里也做一遍，否则契约测试永远对不上——用户看到的就是
 * 「界面 421.49999、存进去 421.5」。
 */
export function scaleNutrition(per100: Nutrition, grams: number): Nutrition {
  const k = grams / 100
  return {
    kcal: round2(per100.kcal * k),
    protein: round2(per100.protein * k),
    carb: round2(per100.carb * k),
    fat: round2(per100.fat * k),
    fiber: round2(per100.fiber * k),
    sodium: round2(per100.sodium * k),
  }
}

/** 合计。**与后端 `NutritionCalculator.sum` 同口径**（累加已定标的各项，再定标一次）。 */
export function sumNutrition(items: Nutrition[]): Nutrition {
  const total = items.reduce<Nutrition>(
    (acc, n) => ({
      kcal: acc.kcal + n.kcal,
      protein: acc.protein + n.protein,
      carb: acc.carb + n.carb,
      fat: acc.fat + n.fat,
      fiber: acc.fiber + n.fiber,
      sodium: acc.sodium + n.sodium,
    }),
    { ...ZERO },
  )
  return scale0(total)
}

/**
 * 保留两位小数。
 *
 * 与 Java 的 `Math.round(v * 100) / 100` 等价**只因为这里的值都非负**：
 * 两者的差别只在恰好 .5 的负数上（JS 的 `Math.round` 向 +∞ 取整，Java 的远离零）。
 * 营养值不可能是负数（后端 `Nutrition` 的构造器就拦了），所以这一处不必额外的分支。
 */
function round2(v: number): number {
  return Math.round(v * 100) / 100
}

/** 对合计的六项各自定标（累加之后再定，理由同 `sumNutrition`）。 */
function scale0(n: Nutrition): Nutrition {
  return {
    kcal: round2(n.kcal),
    protein: round2(n.protein),
    carb: round2(n.carb),
    fat: round2(n.fat),
    fiber: round2(n.fiber),
    sodium: round2(n.sodium),
  }
}

/** 这一行的克数；算不出来时 null。 */
export function draftGrams(item: DraftItem): number | null {
  return toGrams(item.amount, item.unitName, item.food.portions)
}

/** 这一行的**预览**营养值；算不出来时 null。 */
export function draftNutrition(item: DraftItem): Nutrition | null {
  const grams = draftGrams(item)
  if (grams === null) return null
  return scaleNutrition(item.food.per100, grams)
}

/** 整份草稿的**预览**合计。算不出来的行按 0 计（界面上那一行会标出来）。 */
export function draftTotal(items: DraftItem[]): Nutrition {
  return sumNutrition(items.map((i) => draftNutrition(i) ?? ZERO))
}

/**
 * 表单 → `POST /api/meals` 的请求体。
 *
 * **不带 grams**：克数由后端按 `food_portion` 算。让前端算就等于把「哪个单位是多少克」
 * 抄一份到前端，两边一旦不一致，写进去的克数就是错的，而热量没有快照可以兜底。
 */
export function toMealInput(args: {
  mealDate: string
  slot: MealSlot
  eatenAt: string | null
  note: string
  items: DraftItem[]
}): MealInput {
  return {
    mealDate: args.mealDate,
    slot: args.slot,
    eatenAt: args.eatenAt,
    note: args.note.trim() === '' ? null : args.note,
    items: args.items.map((d) => {
      const amount = toNumber(d.amount)
      if (amount === null) {
        // 不该发生：提交前 `validationError` 已经拦过。真到了这里说明有人绕过了它，
        // 那时写下去一个 0 或 NaN 比抛出来更坏（库里有 CHECK (amount > 0)）。
        throw new Error('有一条没有填份量')
      }
      return { foodId: d.food.id, amount, unitName: d.unitName }
    }),
  }
}

/**
 * 提交前的前端校验（只为**即时反馈**，服务端仍是权威——它的 400 会被显示出来）。
 */
export function validationError(mealDate: string, items: DraftItem[]): string | null {
  if (mealDate.trim() === '') return '先选一个日期'
  if (items.length === 0) return '这一餐还没有条目'
  for (const item of items) {
    if (toGrams(item.amount, item.unitName, item.food.portions) === null) {
      return `「${item.food.nameZh}」的份量得是一个大于 0 的数`
    }
  }
  return null
}
