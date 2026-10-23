import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MuscleStrip from './MuscleStrip.vue'
import { short, tone, tonePercent } from './muscle-strip'
import type { MuscleVolume } from '../api/training'

function muscle(over: Partial<MuscleVolume> = {}): MuscleVolume {
  return {
    muscleGroupId: 1,
    nameZh: '胸部',
    nameEn: 'chest',
    sets: 0,
    min: 10,
    max: 20,
    status: 'BELOW',
    parentEn: null,
    ...over,
  }
}

/** 17 块，前 n 块有组数，其余 0。 */
function seventeen(n = 0): MuscleVolume[] {
  return Array.from({ length: 17 }, (_, i) =>
    muscle({ muscleGroupId: i + 1, nameZh: '肌群' + (i + 1), sets: i < n ? 5 : 0 }),
  )
}

describe('17 格热力条 · 颜色算式', () => {
  // 这些用例测的是纯函数，不是 DOM：jsdom 的 cssstyle 不认 var() / color-mix()，
  // 会静默丢掉行内样式，所以颜色必须能脱离 DOM 断言。

  it('0 组用未练色（不是「没有颜色」）', () => {
    expect(tone(0)).toBe('var(--muted-strong)')
    expect(tonePercent(0)).toBeNull()
  })

  it('组数越多强调色占比越高', () => {
    const pcts = [1, 2, 5, 8, 10].map((n) => tonePercent(n)!)
    for (let i = 1; i < pcts.length; i++) {
      expect(pcts[i], `${i} 档没比上一档深`).toBeGreaterThan(pcts[i - 1])
    }
  })

  it('10 组封顶 100%，40 组也不会算出大于 100', () => {
    expect(tonePercent(10)).toBe(100)
    expect(tonePercent(40)).toBe(100)
    expect(tonePercent(1e9)).toBe(100)
  })

  it('下限不是 0%：1 组也要看得出来练过', () => {
    // 若从 0% 起混，1 组和未练在视觉上一模一样——那这一格就白画了。
    expect(tonePercent(1)!).toBeGreaterThan(30)
  })

  it('颜色只来自 token，没有写死的色值', () => {
    for (const sets of [0, 1, 3, 7, 10, 20]) {
      const t = tone(sets)
      expect(t, `写死的颜色: ${t}`).not.toMatch(/#[0-9a-f]{3,8}/i)
      expect(t).toMatch(/var\(--/)
    }
  })

  it('半组（0.5）也按有组数处理，不会被当成 0', () => {
    // 只算次要肌群时可能出现 0.5 组，那是练过。
    expect(tone(0.5)).not.toBe('var(--muted-strong)')
  })
})

describe('17 格热力条 · 短名', () => {
  it('取中文名前两个字', () => {
    expect(short('胸部')).toBe('胸部')
    expect(short('肱二头肌')).toBe('肱二')
    expect(short('腘绳肌')).toBe('腘绳')
    expect(short('小腿')).toBe('小腿')
  })

  it('不足两个字也不炸', () => {
    expect(short('颈')).toBe('颈')
    expect(short('')).toBe('')
  })
})

describe('17 格热力条 · 渲染', () => {
  it('17 块全部渲染，0 组也要在（不能过滤掉）', () => {
    const w = mount(MuscleStrip, { props: { muscles: seventeen(0) } })
    expect(w.findAll('.block')).toHaveLength(17)
    expect(w.findAll('.cap')).toHaveLength(17)
  })

  it('渲染顺序与传入顺序一致（用户要能形成肌肉记忆）', () => {
    const w = mount(MuscleStrip, {
      props: {
        muscles: [muscle({ nameZh: '胸部' }), muscle({ nameZh: '腹部' }), muscle({ nameZh: '背部' })],
      },
    })
    expect(w.findAll('.cap').map((c) => c.text())).toEqual(['胸部', '腹部', '背部'])
  })

  it('每块带 title，鼠标停上去能读到肌群名与组数', () => {
    const w = mount(MuscleStrip, {
      props: { muscles: [muscle({ nameZh: '胸部', sets: 3 })] },
    })
    expect(w.find('.block').attributes('title')).toBe('胸部 · 3 组')
  })

  it('带 data-sets，便于排查「这格为什么这么深」', () => {
    const w = mount(MuscleStrip, { props: { muscles: [muscle({ sets: 4.5 })] } })
    expect(w.find('.block').attributes('data-sets')).toBe('4.5')
  })
})
