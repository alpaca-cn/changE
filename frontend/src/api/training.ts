import { invoke } from '@tauri-apps/api/core'

/**
 * 训练域的 API 客户端。
 *
 * **这是前端唯一发后端请求的地方，而且它不发 HTTP。**
 *
 * 页面源（dev 的 `http://127.0.0.1:1420`、打包后的 `tauri://localhost`）与后端的
 * **随机端口**不同源，响应里没有 `Access-Control-Allow-Origin`，浏览器会把响应拦掉
 * （CSP 与 CORS 是两道独立且都要过的门；计划 1 只放行了 CSP，真机上仍失败）。
 * 所以请求由外壳（Rust 的 `api_request` 命令）代发——它不经浏览器，
 * 天生不在同源策略的管辖范围内。详见 `src-tauri/src/http.rs` 的模块注释。
 *
 * 这一层的职责就是把「外壳返回的 `{status, body}`」翻译成「给业务用的对象或异常」。
 */

/** 后端自报的容量状态，与 `com.change.calc.VolumeCalculator.Status` 一一对应。 */
export type VolumeStatus = 'NOT_CONFIGURED' | 'UNTRAINED' | 'BELOW' | 'IN_RANGE' | 'ABOVE'

/** `GET /api/volume/weekly` 里的一块肌群。 */
export interface MuscleVolume {
  muscleGroupId: number
  nameZh: string
  nameEn: string
  /** 折算组数：主肌群 +1/组、次要肌群 +0.5/组，只算已完成的组。可能是 .5。 */
  sets: number
  /** 建议区间。`null` = 没配目标（`neck` 就是），不是 0。 */
  min: number | null
  max: number | null
  status: VolumeStatus
  /** 父组 name_en；null = 顶层。容量面板靠它缩进。 */
  parentEn: string | null
}

export interface WeeklyVolume {
  weekStart: string
  weekEnd: string
  /** **固定 17 块**，含 0 组的——「本周 0 组」这类提醒不能从列表里消失。 */
  muscles: MuscleVolume[]
}

/** `GET /api/exercises` 选择器里的一行。 */
export interface ExerciseSummary {
  id: number
  nameEn: string
  nameZh: string | null
  equipment: string | null
  isCompound: boolean
  category: string | null
}

export interface SessionSummary {
  id: number
  sessionDate: string
  title: string | null
  perceivedState: string | null
  /** 这次训练里有几个动作。首页卡片用。 */
  exercises: number
  /** 总共几组（含未完成的）。 */
  sets: number
  /** 最重的一组来自哪个动作；全是自重时为 null。 */
  topExerciseName: string | null
  /** 最重的一组多少 kg；全是自重时为 null（**不是 0**）。 */
  topWeight: number | null
}

export interface SetSnapshot {
  setType: string
  weight: number | null
  repetitions: number | null
  durationSeconds: number | null
  perSide: boolean
}

/** `GET /api/exercises/{id}/last-performance`（从没做过时后端回 204，这里是 `null`）。 */
export interface LastPerformance {
  sessionDate: string
  sessionExerciseId: number
  sets: SetSnapshot[]
}

export interface SetDetail {
  id: number
  setIndex: number
  setType: string
  weight: number | null
  repetitions: number | null
  durationSeconds: number | null
  perSide: boolean
  rir: number | null
  restSeconds: number | null
  completed: boolean
  targetWeight: number | null
  targetReps: number | null
}

export interface ExerciseDetail {
  sessionExerciseId: number
  exerciseId: number
  nameZh: string | null
  nameEn: string | null
  orderIndex: number
  note: string | null
  supersetGroup: string | null
  sets: SetDetail[]
}

export interface SessionDetail {
  id: number
  sessionDate: string
  title: string | null
  perceivedState: string | null
  note: string | null
  startedAt: string | null
  endedAt: string | null
  exercises: ExerciseDetail[]
}

/** 写一组。除 `completed` 外都允许为 null——「还没记」是一个真实状态。 */
export interface SetInput {
  setType: string
  weight: number | null
  repetitions: number | null
  durationSeconds: number | null
  perSide: boolean
  rir: number | null
  restSeconds: number | null
  completed: boolean
  targetWeight: number | null
  targetReps: number | null
}

export interface ExerciseInput {
  exerciseId: number
  note: string | null
  supersetGroup: string | null
  sets: SetInput[]
}

/** `POST /api/sessions` 的请求体。与后端 `WorkoutService.SessionInput` 字段名一致。 */
export interface SessionInput {
  sessionDate: string
  title: string | null
  perceivedState: string | null
  note: string | null
  startedAt: string | null
  endedAt: string | null
  exercises: ExerciseInput[]
}

export interface RecordResult {
  sessionId: number
  exercises: number
  sets: number
}

/**
 * 后端**明确回了一个状态码**的错误（400 校验失败、404 找不到……）。
 *
 * 与 {@link ApiTransportError} 分开：这一类的 `message` 通常可以直接显示给用户
 * （后端 400 的 `{error}` 就是中文的校验文案），而传输失败那句话是外壳给的。
 */
export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** 连不上 / 超时 / 响应读不懂 / 后端还没起来——**不是**任何状态码能表达的东西。 */
export class ApiTransportError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ApiTransportError'
  }
}

// `invoke` 的返回值形状，与 Rust `http::ApiResponse` 对应。
interface RawResponse {
  status: number
  body: string
}

/**
 * 把参数拼成查询串。`undefined` / `null` / 空串一律**不出现**在 URL 里——
 * 后端对「省略」和「空串」的解释可能不同（比如 `date` 省略是「今天」），
 * 前端不许替它做决定。
 */
function query(params: Record<string, string | number | undefined | null>): string {
  const parts: string[] = []
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    parts.push(`${k}=${encodeURIComponent(String(v))}`)
  }
  return parts.length > 0 ? `?${parts.join('&')}` : ''
}

/**
 * 唯一发请求的地方。
 *
 * 三条分流（每一条都有测试钉住）：
 * - `invoke` reject → `ApiTransportError`（连不上/超时/后端没起来）；
 * - `204` → `null`（「这个动作没做过」不是一个错误）；
 * - 2xx → 解析后的对象；**其它** → `ApiError`，`message` 取后端 `{error}`。
 */
export async function callApi(
  method: 'GET' | 'POST',
  path: string,
  body?: unknown,
  /**
   * 可选超时（毫秒）。省略 = 外壳的默认 10 秒。
   *
   * **只有「要等外部模型」的请求才该传它。** 普通请求 10 秒足够，
   * 而模型调用十几到几十秒——用默认值去等，只会在拿到结果前把自己掐断
   * （2026-10-25 的「识别不了图片」就是这么来的）。外壳会把它夹在 100ms-180s。
   */
  timeoutMs?: number,
): Promise<unknown> {
  let res: RawResponse
  try {
    res = await invoke<RawResponse>('api_request', {
      method,
      path,
      body: body === undefined ? undefined : JSON.stringify(body),
      timeoutMs,
    })
  } catch (e) {
    throw new ApiTransportError(String(e))
  }

  if (res.status === 204) return null

  let parsed: unknown
  try {
    parsed = res.body ? JSON.parse(res.body) : null
  } catch {
    // **绝不能**把读不懂的正文当成 undefined 放过去：界面会静默渲染成空白，
    // 而调用方以为拿到了数据（`as T` 是个不做校验的类型断言）。
    throw new ApiError(res.status, `后端返回了读不懂的正文（HTTP ${res.status}）`)
  }

  if (res.status >= 200 && res.status < 300) return parsed

  const message = (parsed as { error?: string } | null)?.error
  throw new ApiError(res.status, message ?? `后端返回 HTTP ${res.status}`)
}

/** 本周容量（`date` 省略 = 今天，由后端决定，前端不自己算日期）。 */
export async function weeklyVolume(date?: string): Promise<WeeklyVolume> {
  return (await callApi('GET', `/api/volume/weekly${query({ date })}`)) as WeeklyVolume
}

/** 最近 5 次训练，新的在前。 */
export async function recentSessions(): Promise<SessionSummary[]> {
  return (await callApi('GET', '/api/sessions/recent')) as SessionSummary[]
}

/** 一次训练的完整内容。不存在时后端回 404 → `ApiError(404)`。 */
export async function sessionDetail(id: number): Promise<SessionDetail> {
  return (await callApi('GET', `/api/sessions/${id}`)) as SessionDetail
}

/** 动作选择器。`category` 不给时后端默认排除 `stretching`。 */
export async function searchExercises(params: {
  muscleGroupId?: number
  q?: string
  category?: string
  limit?: number
}): Promise<ExerciseSummary[]> {
  return (await callApi('GET', `/api/exercises${query(params)}`)) as ExerciseSummary[]
}

/** 某动作上次的表现；**从没做过**时返回 `null`（不是异常）。 */
export async function lastPerformance(exerciseId: number): Promise<LastPerformance | null> {
  return (await callApi('GET', `/api/exercises/${exerciseId}/last-performance`)) as LastPerformance | null
}

/** 记一次完整训练。校验失败时后端回 400，`message` 就是它的中文说明。 */
export async function recordSession(input: SessionInput): Promise<RecordResult> {
  return (await callApi('POST', '/api/sessions', input)) as RecordResult
}
