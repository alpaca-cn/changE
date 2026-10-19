import { callApi } from './training'

/**
 * 身体数据域的 API 客户端。
 *
 * **它不复用 `fetch`，也不自己发 HTTP**：`callApi`（来自 `training.ts`）是前端唯一
 * 发后端请求的地方，走的是外壳的 `api_request` 命令。错误分流（传输失败 / 204 /
 * 非 2xx → `ApiError`）在那一层已经做完了，这里只负责把 path 与类型对上。
 *
 * 这一域没有「空」用 204 表达的东西：一个类型都没记过时 `types()` 回 `[]`，
 * 时间线也回 `[]`。所以这里的空结果原样返回，不抛错——「空」不是错误。
 */

/**
 * 九个已知指标类型，**顺序与后端 `MetricType` 枚举一致**。
 *
 * 这个顺序是界面的一部分：它同时决定「当前值」卡片与录入区下拉框的先后。
 * 为什么不只从 `types()` 里学：`types()` 只返回**记过的**类型，全新库里它是 `[]`，
 * 若下拉框只认它，第一条体重就永远录不进去。`types()` 负责的是**值**（最新值、
 * 日期、条数），不是类型词汇表。
 */
export const METRIC_TYPES = [
  'weight',
  'body_fat',
  'waist',
  'chest',
  'hip',
  'arm',
  'thigh',
  'calf',
  'shoulder',
] as const

export type MetricType = (typeof METRIC_TYPES)[number]

/** 类型的中文名。 */
export const METRIC_LABELS: Record<MetricType, string> = {
  weight: '体重',
  body_fat: '体脂率',
  waist: '腰围',
  chest: '胸围',
  hip: '臀围',
  arm: '臂围',
  thigh: '大腿围',
  calf: '小腿围',
  shoulder: '肩围',
}

/**
 * 各类型的默认单位，与后端 `MetricType.defaultUnit` 逐字一致。
 *
 * 前端**只用它显示**输入框旁边那个单位，绝不把它放进请求体：请求里 `unit` 传
 * `null`，单位由后端按类型补。让用户填单位等于给他一个弄错的机会，而单位算错的
 * 数值不会报错，只会静悄悄躺在历史里。
 */
export const METRIC_DEFAULT_UNITS: Record<MetricType, string> = {
  weight: 'kg',
  body_fat: '%',
  waist: 'cm',
  chest: 'cm',
  hip: 'cm',
  arm: 'cm',
  thigh: 'cm',
  calf: 'cm',
  shoulder: 'cm',
}

/**
 * `GET /api/body-metrics` 的一行。
 *
 * `metricType` 是 `string` 而不是 {@link MetricType}：读路径是**开放词汇**——
 * 库里可能有绕过 Java 写进去的历史类型，读要为它照常工作，不是让页面打不开。
 */
export interface Metric {
  id: number
  measuredOn: string
  metricType: string
  value: number
  unit: string
  note: string | null
}

/**
 * `GET /api/body-metrics/types` 的一行：某类型的最新值 + 最新日期 + 总条数。
 *
 * `unit` 是**最新那条记录自己的单位**，不一定是枚举默认值（历史上可能有一条
 * 用「斤」记的体重）。卡片照它显示，数字才不会被读错。
 */
export interface MetricTypeSummary {
  metricType: string
  unit: string
  latestValue: number
  latestOn: string
  count: number
}

/**
 * `POST /api/body-metrics` 的请求体。
 *
 * `unit` 传 `null`：后端按 `metricType` 补默认单位（见 {@link METRIC_DEFAULT_UNITS}
 * 的注释）。`note` 为 `null` 表示没写备注——后端接受 null，不需要造一个空串。
 */
export interface MetricInput {
  measuredOn: string
  metricType: string
  value: number
  unit: string | null
  note: string | null
}

/** `POST /api/body-metrics` 的响应。只有 id——这里没有可实时算的派生值。 */
export interface Created {
  id: number
}

/** 类型字面量 → 中文名。认不出的历史类型原样返回，不消失也不报错。 */
export function metricLabel(metricType: string): string {
  return (METRIC_LABELS as Record<string, string>)[metricType] ?? metricType
}

/**
 * 某类型（或全部类型）的时间线。
 *
 * **后端按日期升序返回**（同日按 id 升序）；「最近的在最上面」是页面的责任，
 * 这一层不改顺序，免得同一个端点在不同调用方眼里有两种排序。
 */
export async function listMetrics(type?: string): Promise<Metric[]> {
  const suffix = type === undefined || type === '' ? '' : `?type=${encodeURIComponent(type)}`
  return (await callApi('GET', `/api/body-metrics${suffix}`)) as Metric[]
}

/** 记一条测量。校验失败时后端回 400，`message` 就是它的中文说明。 */
export async function addMetric(input: MetricInput): Promise<Created> {
  return (await callApi('POST', '/api/body-metrics', input)) as Created
}

/** 库里实际出现过的类型 + 每个的最新值。全新库返回 `[]`（不是 404）。 */
export async function types(): Promise<MetricTypeSummary[]> {
  return (await callApi('GET', '/api/body-metrics/types')) as MetricTypeSummary[]
}
