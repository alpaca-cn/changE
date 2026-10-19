import { describe, expect, it } from 'vitest'
import {
  emptyRow,
  lastPerformanceNote,
  rowsFromLastPerformance,
  toNumber,
  toSessionInput,
  toSetInput,
  todayIso,
  validationError,
} from './training-form'
import type { LastPerformance } from '../api/training'

function lp(sets: Array<[number | null, number | null]>): LastPerformance {
  return {
    sessionDate: '2026-10-19',
    sessionExerciseId: 1,
    sets: sets.map(([weight, repetitions]) => ({
      setType: 'normal',
      weight,
      repetitions,
      durationSeconds: null,
      perSide: false,
    })),
  }
}

describe('预填：上次表现 → 组行', () => {
  it('重量与次数照抄', () => {
    const rows = rowsFromLastPerformance(lp([[60, 8], [62.5, 6]]))
    expect(rows.map((r) => r.weight)).toEqual(['60', '62.5'])
    expect(rows.map((r) => r.repetitions)).toEqual(['8', '6'])
  })

  it('预填的组**一个都不打勾**', () => {
    // 「上次做了 60×8」不等于「这次也做了」。这条是那个区别的杀手：
    // 一旦有人把 completed 预填成 true，未做的组会静默计入周容量。
    const rows = rowsFromLastPerformance(lp([[60, 8], [60, 8]]))
    expect(rows.every((r) => r.completed === false)).toBe(true)
  })

  it('上次没记数值的那组留空，不是 0', () => {
    const rows = rowsFromLastPerformance(lp([[60, 8], [null, null]]))
    expect(rows[1].weight).toBe('')
    expect(rows[1].repetitions).toBe('')
  })

  it('从没做过 → 一行空组', () => {
    const rows = rowsFromLastPerformance(null)
    expect(rows).toHaveLength(1)
    expect(rows[0].weight).toBe('')
    expect(rows[0].completed).toBe(false)
  })

  it('每行的 id 唯一（删行之后 key 不会错位）', () => {
    const rows = rowsFromLastPerformance(lp([[60, 8], [60, 8]]))
    expect(new Set(rows.map((r) => r.id)).size).toBe(2)
    expect(rows[0].id).not.toBe(rows[1].id)
  })
})

describe('空串必须是 null，不能是 0', () => {
  it('toNumber：空串 → null，而不是 0', () => {
    // `Number('') === 0`。把「没填重量」写成「做了 0 公斤」是一条假数据，
    // 而容量核算会照单全收——这是这条断言存在的全部理由。
    expect(toNumber('')).toBeNull()
    expect(toNumber('   ')).toBeNull()
    expect(toNumber('60')).toBe(60)
    expect(toNumber('62.5')).toBe(62.5)
  })

  it('toNumber：写不成数字的文本 → null，不是 NaN', () => {
    expect(toNumber('abc')).toBeNull()
  })

  it('toSetInput：空重量与空次数落到 null', () => {
    const s = toSetInput({ id: 1, weight: '', repetitions: '', completed: true })
    expect(s.weight).toBeNull()
    expect(s.repetitions).toBeNull()
    expect(s.completed).toBe(true)
  })
})

describe('表单 → 请求体', () => {
  it('空白的标题 / 状态 / 备注落到 null', () => {
    const input = toSessionInput({
      sessionDate: '2026-10-19',
      title: '  ',
      perceivedState: '',
      note: '',
      exerciseId: 16,
      rows: [emptyRow()],
    })
    expect(input.title).toBeNull()
    expect(input.perceivedState).toBeNull()
    expect(input.note).toBeNull()
  })

  it('一次训练 = 1 个动作，动作下是全部组行', () => {
    const rows = rowsFromLastPerformance(lp([[60, 8], [60, 8]]))
    rows[0].completed = true
    const input = toSessionInput({
      sessionDate: '2026-10-19',
      title: '胸 + 三头',
      perceivedState: '睡得好',
      note: '测试',
      exerciseId: 16,
      rows,
    })
    expect(input.exercises).toHaveLength(1)
    expect(input.exercises[0].exerciseId).toBe(16)
    expect(input.exercises[0].sets).toHaveLength(2)
    expect(input.exercises[0].sets[0].completed).toBe(true)
    expect(input.exercises[0].sets[1].completed).toBe(false)
  })

  it('todayIso 用本地日期（不是 UTC 的 toISOString）', () => {
    // 跨时区时 `toISOString()` 会差一天，而 sessionDate 是「用户认为的今天」。
    expect(todayIso(new Date(2026, 9, 19, 0, 30))).toBe('2026-10-19')
    // 本地时间 00:30 在 UTC 下可能是前一天；用本地字段拼就不会错
    expect(todayIso(new Date(2026, 0, 1, 0, 0))).toBe('2026-01-01')
  })
})

describe('提交前的前端校验', () => {
  it('没选动作 → 提示', () => {
    expect(validationError(null, [emptyRow()])).toContain('动作')
  })

  it('一组都没有 → 提示', () => {
    expect(validationError(16, [])).toContain('组')
  })

  it('有动作有组 → 通过', () => {
    expect(validationError(16, [emptyRow()])).toBeNull()
  })

  it('全空的组也算通过：记了但没填数是合法状态', () => {
    expect(validationError(16, [emptyRow()])).toBeNull()
  })
})

describe('上次表现的说明文案', () => {
  it('从没做过时说清楚', () => {
    expect(lastPerformanceNote(null)).toContain('没记录过')
  })

  it('做过时列出日期与每组', () => {
    const note = lastPerformanceNote(lp([[60, 8], [null, null]]))
    expect(note).toContain('2026-10-19')
    expect(note).toContain('60×8')
    expect(note).toContain('—×—')
  })
})
