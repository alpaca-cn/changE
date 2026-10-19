import { callApi } from './training'

/**
 * 进步曲线域的 API 客户端。
 *
 * **它不复用 `fetch`，也不自己发 HTTP**：`callApi`（来自 `training.ts`）是前端唯一
 * 发后端请求的地方，走的是外壳的 `api_request` 命令。错误分流（传输失败 / 204 /
 * 非 2xx → `ApiError`）在那一层已经做完了，这里只负责把 path 与类型对上。
 *
 * 这一域的两条边界**不是错误**：
 * - 动作从没练过 → `200` + `points: []`（「还没开始」是常态）；
 * - 某天估不出 1RM → 点仍然在，`e1rm` 为 `null`（不是 0，也不是缺字段）。
 * 只有动作不存在才是 404，由 `ApiError` 带着后端那句中文抛出来。
 */

/**
 * 一个训练日上的三条序列。
 *
 * `date` 是 `"YYYY-MM-DD"` 字符串（后端 `LocalDate` 的 ISO 序列化），不是数组也不是毫秒。
 *
 * `e1rm` **可以为 null**：那天所有组都估不出 1RM（次数超过 `OneRepMax.MAX_REPS`，
 * 或者重量不为正）时是 null，但 `maxWeight` / `totalVolume` 仍然有值——那是真实练过的一天。
 * 页面必须把它当「没有这个点」跳过，不能当 0 画（0 会在图上多一个假的下跌）。
 *
 * `totalVolume` 是吨位（Σ 重量×次数），不是组数；与「周容量」的组数是两件事。
 */
export interface ProgressPoint {
  date: string
  e1rm: number | null
  maxWeight: number
  totalVolume: number
}

/**
 * `GET /api/progress/{exerciseId}` 的响应。
 *
 * `nameZh` 可以为 null（手工录入的动作可能没有中文名），界面回退到 `nameEn`。
 */
export interface ExerciseProgress {
  exerciseId: number
  nameEn: string
  nameZh: string | null
  points: ProgressPoint[]
}

/**
 * 一个动作的整条曲线，点按训练日**升序**。
 *
 * 暂时不暴露 `from` / `to`：页面还没有日期筛选，多两个参数就多两条没有调用方的路径。
 * 后端已经支持，等界面真的需要时再加——那时它的形状会由界面决定，不是现在猜。
 */
export async function progress(exerciseId: number): Promise<ExerciseProgress> {
  return (await callApi('GET', `/api/progress/${exerciseId}`)) as ExerciseProgress
}
