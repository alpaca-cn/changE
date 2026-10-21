import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import WeekRing from './WeekRing.vue'
import type { MuscleVolume, VolumeStatus } from '../api/training'

function muscle(status: VolumeStatus, over: Partial<MuscleVolume> = {}): MuscleVolume {
  const hasTarget = status !== 'NOT_CONFIGURED'
  return {
    muscleGroupId: 1,
    nameZh: '胸部',
    nameEn: 'chest',
    sets: 0,
    min: hasTarget ? 10 : null,
    max: hasTarget ? 20 : null,
    status,
    ...over,
  }
}

/** 周长 2π×34 ≈ 213.63。dasharray 的第一段就是已填充的弧长。 */
const C = 2 * Math.PI * 34

function arcOf(muscles: MuscleVolume[]): number {
  const w = mount(WeekRing, { props: { muscles } })
  const circles = w.findAll('circle')
  const dash = circles[1].attributes('stroke-dasharray') ?? ''
  return Number(dash.split(' ')[0])
}

describe('本周达标环', () => {
  it('达标率 = IN_RANGE 数 / 有目标的数', () => {
    // 4 块有目标，2 块达标 → 50%
    const w = mount(WeekRing, {
      props: {
        muscles: [
          muscle('IN_RANGE'), muscle('IN_RANGE'),
          muscle('BELOW'), muscle('ABOVE'),
        ],
      },
    })
    expect(w.find('.pct').text()).toBe('50%')
    expect(arcOf([muscle('IN_RANGE'), muscle('IN_RANGE'), muscle('BELOW'), muscle('ABOVE')]))
      .toBeCloseTo(C / 2, 1)
  })

  it('ABOVE（练过量）不算达标', () => {
    // 练过头不是成绩。这是刻意的：只有 IN_RANGE 进分子。
    const w = mount(WeekRing, {
      props: { muscles: [muscle('ABOVE'), muscle('IN_RANGE'), muscle('BELOW'), muscle('BELOW')] },
    })
    expect(w.find('.pct').text()).toBe('25%')
  })

  it('没配目标的肌群不进分母（neck 永远达标不了不是用户的错）', () => {
    // 2 块有目标且都达标 → 100%；若把 5 块 NOT_CONFIGURED 也算进分母就会变成 28%
    const muscles = [
      muscle('IN_RANGE'), muscle('IN_RANGE'),
      ...Array.from({ length: 5 }, () => muscle('NOT_CONFIGURED', { nameZh: '颈部' })),
    ]
    const w = mount(WeekRing, { props: { muscles } })
    expect(w.find('.pct').text()).toBe('100%')
  })

  it('一块有目标的都没有时显示「—」而不是 0%', () => {
    // 没有目标时「达成率」本身不成立。显示 0% 会让用户以为自己全漏了。
    const w = mount(WeekRing, {
      props: { muscles: [muscle('NOT_CONFIGURED'), muscle('NOT_CONFIGURED')] },
    })
    expect(w.find('.pct').text()).toBe('—')
    expect(arcOf([muscle('NOT_CONFIGURED')])).toBe(0)
  })

  it('全都未练时是 0%，环上没有弧', () => {
    const muscles = [muscle('UNTRAINED'), muscle('UNTRAINED')]
    const w = mount(WeekRing, { props: { muscles } })
    expect(w.find('.pct').text()).toBe('0%')
    expect(arcOf(muscles)).toBe(0)
  })

  it('弧长永不超出周长（100% 时不溢出）', () => {
    for (const n of [1, 3, 7, 17]) {
      const muscles = Array.from({ length: n }, () => muscle('IN_RANGE'))
      expect(arcOf(muscles)).toBeLessThanOrEqual(C + 0.01)
    }
  })

  it('用 stroke-dasharray 画弧，而不是写死路径（换半径不用重算 d 属性）', () => {
    const w = mount(WeekRing, { props: { muscles: [muscle('IN_RANGE')] } })
    const circles = w.findAll('circle')
    expect(circles).toHaveLength(2)
    // 第一圈是轨道，第二圈是进度；进度圈的描边色只能来自 token
    expect(circles[1].attributes('stroke')).toBe('var(--accent)')
    expect(circles[0].attributes('stroke')).toBe('var(--line)')
  })
})
