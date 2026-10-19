import { callApi } from './training'

/**
 * 饮食域的 API 客户端。
 *
 * **它不复用 `fetch`，也不自己发 HTTP**：`callApi`（来自 `training.ts`）是前端唯一
 * 发后端请求的地方，走的是外壳的 `api_request` 命令。错误分流（传输失败 / 204 /
 * 非 2xx → `ApiError`）在那一层已经做完了，这里只负责把 path 与类型对上。
 */

/** 一份营养值。钠的单位是**毫克**，其余是克。与后端 `calc.Nutrition` 的分量同名同序。 */
export interface Nutrition {
  kcal: number
  protein: number
  carb: number
  fat: number
  fiber: number
  sodium: number
}

/** 一个份量单位（「碗 = 150 克」）。 */
export interface Portion {
  unitName: string
  grams: number
}

/** `GET /api/foods` 里的一行。 */
export interface FoodSummary {
  id: number
  nameZh: string
  nameEn: string | null
  source: string
  /** 每 100 克基准值——前端预览要用它，**不是**这份份量的值。 */
  per100: Nutrition
  portions: Portion[]
}

/** 一餐里的一个条目（读回来的）。 */
export interface MealItemDetail {
  mealItemId: number
  foodId: number
  nameZh: string
  orderIndex: number
  amount: number
  /** null 表示这一条是**按克**录的（amount 本身就是克数）。 */
  unitName: string | null
  grams: number
  nutrition: Nutrition
}

export interface MealDetail {
  id: number
  mealDate: string
  slot: string
  eatenAt: string | null
  note: string | null
  items: MealItemDetail[]
  nutrition: Nutrition
}

export interface DailyNutrition {
  date: string
  nutrition: Nutrition
  meals: number
  items: number
}

/** 要写的一个条目。**没有 grams 字段**——克数由后端按 `food_portion` 算。 */
export interface MealItemInput {
  foodId: number
  amount: number
  unitName: string | null
}

/** `POST /api/meals` 的请求体。 */
export interface MealInput {
  mealDate: string
  slot: string
  eatenAt: string | null
  note: string | null
  items: MealItemInput[]
}

export interface MealResult {
  mealId: number
  items: number
  nutrition: Nutrition
}

/** 合法的餐次，与后端 `Meal.VALID_SLOTS` 和库里 `meal.slot` 的 CHECK 一致。 */
export const MEAL_SLOTS = ['breakfast', 'lunch', 'dinner', 'snack'] as const

export type MealSlot = (typeof MEAL_SLOTS)[number]

/** 餐次的中文名。 */
export const SLOT_LABELS: Record<MealSlot, string> = {
  breakfast: '早餐',
  lunch: '午餐',
  dinner: '晚餐',
  snack: '加餐',
}

/** 搜食物。`q` 省略 = 列出前若干条；`limit` **不传**就用后端的默认值。 */
export async function searchFoods(params: { q?: string; limit?: number } = {}): Promise<FoodSummary[]> {
  const query: string[] = []
  if (params.q !== undefined && params.q !== '') query.push(`q=${encodeURIComponent(params.q)}`)
  if (params.limit !== undefined) query.push(`limit=${encodeURIComponent(String(params.limit))}`)
  const suffix = query.length > 0 ? `?${query.join('&')}` : ''
  return (await callApi('GET', `/api/foods${suffix}`)) as FoodSummary[]
}

/** 记一餐。返回的 `nutrition` 是**后端实时算**的合计。 */
export async function recordMeal(input: MealInput): Promise<MealResult> {
  return (await callApi('POST', '/api/meals', input)) as MealResult
}

/** 一餐的详情；不存在时抛 `ApiError(404)`。 */
export async function mealDetail(id: number): Promise<MealDetail> {
  return (await callApi('GET', `/api/meals/${id}`)) as MealDetail
}

/** 某一天的餐（`date` 省略 = 今天，由后端决定）。没记过就是空数组。 */
export async function mealsOn(date?: string): Promise<MealDetail[]> {
  return (await callApi('GET', `/api/meals${date === undefined ? '' : `?date=${date}`}`)) as MealDetail[]
}

/** 某一天的合计。**空的一天是全 0，不是 404。** */
export async function dailyNutrition(date?: string): Promise<DailyNutrition> {
  return (await callApi('GET', `/api/nutrition/daily${date === undefined ? '' : `?date=${date}`}`)) as DailyNutrition
}
