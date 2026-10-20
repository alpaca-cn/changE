import { callApi } from './training'

/**
 * 动作详情域的 API 客户端。
 *
 * 与 `body.ts` / `progress.ts` 同一个形状：**不自己发 HTTP**，`callApi`（来自
 * `./training`）是前端唯一的出口。错误分流（传输失败 / 204 / 非 2xx → `ApiError`）
 * 在那一层已经做完，这里只负责把 path 与类型对上。
 *
 * 端点：`GET /api/exercises/{id}`（计划 7 · Task 6）。它把计划 2 导入了、却一直
 * 没有任何端点返回的 `instructions` 暴露出来，并带上中文做法与目标肌群。
 *
 * <h2>三个字段可以是 null，而且都不是错误</h2>
 *
 * - `nameZh`：手工录入的动作可能没有中文名；
 * - `instructions` / `instructionsZh`：数据集里 5 条动作（Iron Cross、
 *   One-Arm Kettlebell Swings、Push Press、Side Bridge、Side Jackknife）原文为空，
 *   两个都是 null。后端对它们照常回 200——「这条没有做法」是数据事实，不是请求错误。
 *
 * 所以这里**不对 null 做任何拦截**：把 null 变成异常会让界面多出一句吓人的话，
 * 而真正该说的是「这条没有做法说明」。
 */

/**
 * 详情里的一个目标肌群。
 *
 * `role` 用 `string` 而不是 `'primary' | 'secondary'`：读路径是开放词汇。
 * DDL 的 CHECK 只允许两个值，但后端 SQL 里也留了 `ELSE 2` 的防御；
 * 万一将来多出第三个值，这里不该让页面因为一个类型断言就崩掉。
 */
export interface MuscleRef {
  muscleGroupId: number
  nameZh: string
  nameEn: string
  role: string
}

/**
 * `GET /api/exercises/{id}` 的响应。
 *
 * 字段与后端 `ExerciseDetailService.ExerciseDetail` 的 record 一一对应，
 * **不包含** `sourceId` / `note` / `archived`：后端刻意没返回它们（每多一个键
 * 都是要维护的契约）。键集合由 `exercise-detail.spec.ts` 逐字钉住。
 */
export interface ExerciseDetail {
  id: number
  nameEn: string
  nameZh: string | null
  equipment: string | null
  isCompound: boolean
  category: string | null
  /** 英文分步做法。段落之间用 `\n` 分隔；原文为空时是 null。 */
  instructions: string | null
  /** 中文分步做法。**一个 `\n` 一步**；没有中文时是 null。 */
  instructionsZh: string | null
  /** 主要肌群在前、次要在后（后端 ORDER BY 保证）；可能为空数组（手工动作没标）。 */
  muscles: MuscleRef[]
}

/** 一个动作的详情。不存在时后端回 404 → `ApiError`，`message` 是它的中文文案。 */
export async function exerciseDetail(id: number): Promise<ExerciseDetail> {
  return (await callApi('GET', `/api/exercises/${id}`)) as ExerciseDetail
}
