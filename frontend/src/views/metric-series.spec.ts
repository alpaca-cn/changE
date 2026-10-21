import { describe, expect, it } from 'vitest'
import { deltaText, groupByType, polyline } from './metric-series'
import type { Metric } from '../api/body'

function m(over: Partial<Metric> = {}): Metric {
  return {
    id: 1,
    measuredOn: '2026-10-01',
    metricType: 'weight',
    value: 72,
    unit: 'kg',
    note: null,
    ...over,
  }
}

describe('身体数据的分组', () => {
  it('按类型分组，并保持后端给的顺序', () => {
    const series = groupByType([
      m({ id: 1, metricType: 'weight', measuredOn: '2026-10-01', value: 72 }),
      m({ id: 2, metricType: 'waist', measuredOn: '2026-10-01', value: 80 }),
      m({ id: 3, metricType: 'weight', measuredOn: '2026-10-08', value: 71 }),
    ])
    expect(series.map((s) => s.metricType)).toEqual(['weight', 'waist'])
    expect(series[0].points).toHaveLength(2)
  })

  it('同一天记了两次时只留最后一条（否则折线在一天里来回跳）', () => {
    const [weight] = groupByType([
      m({ id: 1, measuredOn: '2026-10-01', value: 72 }),
      m({ id: 5, measuredOn: '2026-10-01', value: 71.6 }),
    ])
    expect(weight.points).toEqual([{ on: '2026-10-01', value: 71.6 }])
  })

  it('按日期升序，不受输入顺序影响', () => {
    const [weight] = groupByType([
      m({ id: 3, measuredOn: '2026-10-20', value: 71 }),
      m({ id: 1, measuredOn: '2026-10-01', value: 72 }),
    ])
    expect(weight.points.map((p) => p.on)).toEqual(['2026-10-01', '2026-10-20'])
  })

  it('算最新值与变化', () => {
    const [weight] = groupByType([
      m({ id: 1, measuredOn: '2026-10-01', value: 72 }),
      m({ id: 2, measuredOn: '2026-10-20', value: 70.5 }),
    ])
    expect(weight.latest).toBe(70.5)
    expect(weight.delta).toBe(-1.5)
    expect(weight.deltaPercent).toBe(-2.08)
  })

  it('最早值是 0 时不给百分比（除零没有意义，也不该显示 Infinity）', () => {
    const [waist] = groupByType([
      m({ id: 1, measuredOn: '2026-10-01', metricType: 'waist', value: 0, unit: 'cm' }),
      m({ id: 2, measuredOn: '2026-10-20', metricType: 'waist', value: 80, unit: 'cm' }),
    ])
    expect(waist.deltaPercent).toBeNull()
  })
})

describe('折线', () => {
  it('一个点画不出来——返回空串而不是一条歪线', () => {
    expect(polyline([{ value: 72 }], 220, 48)).toBe('')
  })

  it('两个点画一条线，x 均匀分布', () => {
    const p = polyline([{ value: 72 }, { value: 71 }], 100, 50)
    const xs = p.split(' ').map((pt) => Number(pt.split(',')[0]))
    expect(xs).toEqual([0, 100])
  })

  it('所有值相同时画在中间（max-min 是 0，直接除会得到 NaN）', () => {
    const p = polyline([{ value: 72 }, { value: 72 }, { value: 72 }], 100, 50)
    expect(p).not.toContain('NaN')
    for (const pt of p.split(' ')) {
      expect(Number(pt.split(',')[1])).toBe(25)
    }
  })

  it('值大的点在上面（y 更小）', () => {
    const p = polyline([{ value: 80 }, { value: 70 }], 100, 50)
    const ys = p.split(' ').map((pt) => Number(pt.split(',')[1]))
    expect(ys[0]).toBeLessThan(ys[1])
  })
})

describe('变化的那句话', () => {
  it('只有一个点时不编造变化', () => {
    // 一个点的「变化」是没有意义的。显示 0 或 ▲0 会让用户以为已经稳定了。
    const [s] = groupByType([m()])
    expect(deltaText(s)).toBe('只有一个点')
  })

  it('上涨用 ▲，下降用 ▼，持平用 —', () => {
    const [up] = groupByType([m({ id: 1, value: 70 }), m({ id: 2, measuredOn: '2026-10-02', value: 72 })])
    const [down] = groupByType([m({ id: 1, value: 72 }), m({ id: 2, measuredOn: '2026-10-02', value: 70 })])
    const [flat] = groupByType([m({ id: 1, value: 72 }), m({ id: 2, measuredOn: '2026-10-02', value: 72 })])
    expect(deltaText(up)).toContain('▲')
    expect(deltaText(down)).toContain('▼')
    expect(deltaText(flat)).toContain('持平')
  })
})
