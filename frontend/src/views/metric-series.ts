import type { Metric } from '../api/body'

/**
 * 身体数据的走势。与「动作进步曲线」并列，但**不是同一种东西**：
 *
 * - 动作曲线按动作分组，问的是「这个动作我变强了吗」
 * - 身体数据按指标分组，问的是「我的体重/腰围在往哪走」
 *
 * 两者都画折线，但量纲、采样频率、该看的结论完全不同。所以这一页把它们分成两块，
 * 而不是塞进同一个选择器——那样用户得先决定「我现在想看哪类」才能开始。
 */

/** 一个指标的时间序列。`points` 按日期升序。 */
export interface MetricSeries {
  metricType: string
  unit: string
  points: { on: string; value: number }[]
  /** 最新一次的值。没有点时是 null。 */
  latest: number | null
  /** 与最早一次相比的变化。只有一个点时是 0。 */
  delta: number
  /** 变化的百分比。最早值是 0 或没有点时是 null（除零没有意义，也不该显示 Infinity）。 */
  deltaPercent: number | null
}

/** 把扁平的指标列表按类型分组。**保持后端给的顺序**，用户才能形成肌肉记忆。 */
export function groupByType(metrics: Metric[]): MetricSeries[] {
  const order: string[] = []
  const byType = new Map<string, Metric[]>()

  for (const m of metrics) {
    if (!byType.has(m.metricType)) {
      byType.set(m.metricType, [])
      order.push(m.metricType)
    }
    byType.get(m.metricType)!.push(m)
  }

  return order.map((type) => {
    const list = byType.get(type)!.slice()
    // 同一天可能记了两次：保留最后一条（id 更大的那次），否则折线会在同一天来回跳
    list.sort((a, b) => (a.measuredOn === b.measuredOn ? a.id - b.id : a.measuredOn.localeCompare(b.measuredOn)))
    const dedup: Metric[] = []
    for (const m of list) {
      const prev = dedup[dedup.length - 1]
      if (prev !== undefined && prev.measuredOn === m.measuredOn) {
        dedup[dedup.length - 1] = m
      } else {
        dedup.push(m)
      }
    }

    const points = dedup.map((m) => ({ on: m.measuredOn, value: m.value }))
    const first = points.length > 0 ? points[0].value : null
    const latest = points.length > 0 ? points[points.length - 1].value : null
    const delta = first === null || latest === null ? 0 : round2(latest - first)
    const deltaPercent =
      first === null || latest === null || first === 0
        ? null
        : round2(((latest - first) / Math.abs(first)) * 100)

    return { metricType: type, unit: dedup[0]?.unit ?? '', points, latest, delta, deltaPercent }
  })
}

/** 保留两位小数，与后端 `calc.Round.to2` 同口径（这里的值都非负）。 */
function round2(v: number): number {
  return Math.round(v * 100) / 100
}

/**
 * 把点映射成 SVG 折线的 `points` 属性。
 *
 * 三个刻意的决定：
 *   1. 只有一个点时画不出来——返回空串，让调用方去显示「只有一个点」而不是一条歪线
 *   2. 所有值相同时（比如体重一直是 72.0）把线画在中间，否则 `(v-min)/(max-min)` 是 0/0
 *   3. 上下各留 10% 的空白，线不贴边
 */
export function polyline(
  points: { value: number }[],
  width: number,
  height: number,
): string {
  if (points.length < 2) return ''
  const values = points.map((p) => p.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const flat = max === min
  const pad = height * 0.1
  const usable = height - pad * 2

  return points
    .map((p, i) => {
      const x = (i / (points.length - 1)) * width
      const y = flat ? height / 2 : pad + (1 - (p.value - min) / (max - min)) * usable
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

/** 变化的那句话（▲ / ▼ / —），颜色由调用方按语义决定。 */
export function deltaText(s: MetricSeries): string {
  if (s.latest === null || s.points.length < 2) return '只有一个点'
  if (s.delta === 0) return '— 持平'
  const arrow = s.delta > 0 ? '▲' : '▼'
  const pct = s.deltaPercent === null ? '' : ` · ${Math.abs(s.deltaPercent)}%`
  return `${arrow} ${Math.abs(s.delta)}${s.unit}${pct}`
}
