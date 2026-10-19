import { describe, it, expect } from 'vitest'
import type { VolumeStatus } from '../api/training'
import { setsText, statusClass, statusLabel, targetText } from './volume-status'

describe('容量状态 → 表现', () => {
  it('五种状态各有自己的 class', () => {
    expect(statusClass('IN_RANGE')).toBe('in-range')
    expect(statusClass('BELOW')).toBe('below')
    expect(statusClass('ABOVE')).toBe('above')
    expect(statusClass('UNTRAINED')).toBe('untrained')
    expect(statusClass('NOT_CONFIGURED')).toBe('not-configured')
  })

  it('遇到没见过的状态时落到 not-configured，而不是 undefined', () => {
    // 后端加了第六种状态而忘了同步这个文件时，旧写法会返回 undefined：
    // class 绑定为空 → 那块肌群**没有任何颜色提示**，看起来和「没配目标」一样，
    // 但其实是未知。宁可显示成看不懂的「未设目标」，也不要静默变空。
    //
    // 与「喂假数据测正常路径」不同：这里走的是一条**真实存在的防御分支**
    // （default 那一支），不是拿假数据冒充真实输入。
    const weird = 'HIBERNATING' as unknown as VolumeStatus
    expect(statusClass(weird)).toBe('not-configured')
    expect(statusLabel(weird)).toBe('未设目标')
  })

  it('「练少了」与「练过量」不是同一句话', () => {
    // 这两个要是同色同文，容量面板比不显示更难看出问题。
    expect(statusClass('BELOW')).not.toBe(statusClass('ABOVE'))
    expect(statusLabel('BELOW')).not.toBe(statusLabel('ABOVE'))
  })

  it('UNTRAINED 的文案把「0 组」说出来（颜色之外还要有文字信号）', () => {
    expect(statusLabel('UNTRAINED')).toContain('0')
    expect(statusLabel('UNTRAINED')).toContain('组')
  })

  it('没配目标显示「未设目标」，绝不显示 null', () => {
    expect(targetText({ min: null, max: null })).toBe('未设目标')
    // 只缺一半也算「没配」：显示 `10–null` 比显示「未设目标」更容易让人误解
    expect(targetText({ min: 10, max: null })).toBe('未设目标')
    expect(targetText({ min: null, max: 20 })).toBe('未设目标')
  })

  it('配了目标就显示区间', () => {
    expect(targetText({ min: 10, max: 20 })).toBe('10–20')
  })

  it('组数：整数不带小数点，半组保留一位', () => {
    // 后端给的是 `2.0`（JSON number），JS 里就是 2 —— 直接 String() 会是 "2"，正确。
    // 但 2.5 必须显示成 "2.5"，不能被抹成 "3"。
    expect(setsText(2)).toBe('2')
    expect(setsText(1)).toBe('1')
    expect(setsText(2.5)).toBe('2.5')
    expect(setsText(0)).toBe('0')
    expect(setsText(12.5)).toBe('12.5')
  })
})
