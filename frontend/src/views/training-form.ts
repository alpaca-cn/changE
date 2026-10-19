import type { LastPerformance, SessionInput, SetInput, SetSnapshot } from '../api/training'

/**
 * 训练记录页的表单逻辑。
 *
 * 单独成模块的理由与 `volume-status.ts` 相同：`<script setup>` **不允许具名导出**，
 * 这些纯函数塞进组件就没法被单测直接调用，而它们恰好是这一页最容易写错的地方
 * （预填、空串、删除行）。
 */

/** 界面上的一行组。重量/次数用**字符串**存——输入框给的就是字符串。 */
export interface SetRow {
  /** 稳定标识，用作 `:key`。**不用数组下标**：删掉中间一行后下标会错位。 */
  id: number
  weight: string
  repetitions: string
  completed: boolean
}

let nextId = 1

/** 一行空组。 */
export function emptyRow(): SetRow {
  return { id: nextId++, weight: '', repetitions: '', completed: false }
}

/**
 * 上次表现 → 界面上的若干行。
 *
 * **重量与次数照抄，但全部不打勾**：「上次做了 60×8」不等于「这次也做了」。
 * 预填的是草稿，勾选是「这次真的做了」的声明——而只有勾了的组才计入周容量。
 */
export function rowsFromLastPerformance(lp: LastPerformance | null): SetRow[] {
  if (lp === null || lp.sets.length === 0) return [emptyRow()]
  return lp.sets.map((s: SetSnapshot) => ({
    id: nextId++,
    weight: s.weight === null ? '' : String(s.weight),
    repetitions: s.repetitions === null ? '' : String(s.repetitions),
    completed: false,
  }))
}

/**
 * 输入框文本 → 数字。
 *
 * **空串必须是 `null`，不能是 `0`。** `Number('') === 0`，把「没填重量」写成
 * 「做了 0 公斤」是一条假数据，而容量核算会照单全收。
 */
export function toNumber(text: string): number | null {
  const t = text.trim()
  if (t === '') return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

/** 一行 → `SetInput`。 */
export function toSetInput(row: SetRow): SetInput {
  return {
    setType: 'normal',
    weight: toNumber(row.weight),
    repetitions: toNumber(row.repetitions),
    durationSeconds: null,
    perSide: false,
    rir: null,
    restSeconds: null,
    completed: row.completed,
    targetWeight: null,
    targetReps: null,
  }
}

/** 可选文本：空白 → `null`（后端对 null 与 "" 的处理未必相同，别替它决定）。 */
function textOrNull(text: string): string | null {
  const t = text.trim()
  return t === '' ? null : t
}

/** 本地日期 `YYYY-MM-DD`。**不用 `toISOString()`**——那是 UTC，跨时区会差一天。 */
export function todayIso(d: Date = new Date()): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 表单 → `POST /api/sessions` 的请求体。 */
export function toSessionInput(args: {
  sessionDate: string
  title: string
  perceivedState: string
  note: string
  exerciseId: number
  rows: SetRow[]
}): SessionInput {
  return {
    sessionDate: args.sessionDate,
    title: textOrNull(args.title),
    perceivedState: textOrNull(args.perceivedState),
    note: textOrNull(args.note),
    startedAt: null,
    endedAt: null,
    exercises: [
      {
        exerciseId: args.exerciseId,
        note: null,
        supersetGroup: null,
        sets: args.rows.map(toSetInput),
      },
    ],
  }
}

/**
 * 前端的第一层校验（只为**即时反馈**，服务端仍是权威——它的 400 会被显示出来）。
 *
 * 不校验「重量填了没有」：一组全是 null 是合法的真实状态（记了但没填数）。
 */
export function validationError(exerciseId: number | null, rows: SetRow[]): string | null {
  if (exerciseId === null) return '先选一个动作'
  if (rows.length === 0) return '至少要有一组'
  return null
}

/** 上次表现的一句话摘要。 */
export function lastPerformanceNote(lp: LastPerformance | null): string {
  if (lp === null) return '这个动作没记录过，从空组开始'
  const sets = lp.sets.map((s) => `${s.weight ?? '—'}×${s.repetitions ?? '—'}`).join('，')
  return `上次 ${lp.sessionDate}：${sets}`
}
