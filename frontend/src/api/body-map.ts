import { callApi, type VolumeStatus } from './training'

/**
 * 人体图的数据契约 —— `GET /api/body-map/volume` 的客户端。
 *
 * <h2>为什么这里另起一个 `MuscleBodyMap`，而不是复用 `training.ts` 的 `MuscleVolume`</h2>
 *
 * `api/training.ts` 里的 `MuscleVolume` 属于**另一个端点**（`/api/volume/weekly`），
 * 只有 7 个字段，没有 `regionIds` / `daysSince` / `lastTrainedOn`。两个响应形状
 * **同名不同形**是最坏的一种重名：导入错的那个不会报错，只会在用 `regionIds` 时
 * 拿到 `undefined`——于是整张人体图静默不着色，控制台干干净净。
 *
 * 所以这里用后端记录自己的名字 `MuscleBodyMap`（`BodyMapService.MuscleBodyMap`）。
 * 名字不同、形状不同、各有出处；不让两个端点互相将就。
 *
 * 唯一共用的是 `VolumeStatus`：那是同一个枚举（`VolumeCalculator.Status`），
 * 不是同一份形状，复用类型不会把两个端点绑在一起。
 */

/** 人体图上的一块肌群。字段与后端 `BodyMapService.MuscleBodyMap` 的 10 个记录字段一一对应。 */
export interface MuscleBodyMap {
  muscleGroupId: number
  nameZh: string
  nameEn: string
  /** 本周折算组数（主肌群 +1/组、次要 +0.5/组）。0 也要渲染，只是不着色。 */
  sets: number
  /** 参考区间。`null` = 没配目标（`neck` 就是），不是 0。 */
  min: number | null
  max: number | null
  status: VolumeStatus
  lastTrainedOn: string | null
  /**
   * `today - lastTrainedOn`。`null` = 从没练过。
   * **可能是负数**：后端 `lastTrainedOn` 的 SQL 不带日期范围，参照日更早时就会出现。
   * 本页不传 `date`（见 {@link bodyMapVolume}），所以正常情况下不会遇到。
   */
  daysSince: number | null
  /** 该肌群在 SVG 里的区域 id。不在这里的区域不参与着色，也不可点。 */
  regionIds: string[]
}

/** `GET /api/body-map/volume` 的响应。 */
export interface BodyMapVolume {
  weekStart: string
  weekEnd: string
  /** 后端决定的「今天」。本页不自己算日期。 */
  today: string
  /** 固定 17 块，含 0 组的——「本周 0 组」不能从列表里消失。 */
  muscles: MuscleBodyMap[]
}

/** 三种着色口径。同一份数据换一种「怎么算强度」，不换数据。 */
export type BodyMapMode = 'volume' | 'recovering' | 'neglected'

/**
 * 本周人体图数据。
 *
 * `date` **本页刻意不传**：这一页看的是「现在」。后端 `daysSince = 参照日 - lastTrainedOn`
 * 且 `lastTrainedOn` 的 SQL 不带日期范围，所以传一个比最近一次训练更早的日期会让
 * `daysSince` 变成负数（`<BodyMap />` 虽然把负数并入 0，但那是给「翻看往期」预留的
 * 兜底，不是让这一页去用它）。省略 `date` = 后端决定今天，前端不自己算。
 *
 * 查询串只拼一个可选参数，所以不像 `training.ts` 的 `query()` 那样通用；
 * 语义保持一致：`undefined` / 空串**不出现**在 URL 里。
 */
export async function bodyMapVolume(date?: string): Promise<BodyMapVolume> {
  const suffix = date === undefined || date === '' ? '' : `?date=${encodeURIComponent(date)}`
  return (await callApi('GET', `/api/body-map/volume${suffix}`)) as BodyMapVolume
}
