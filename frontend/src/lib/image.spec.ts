import { describe, expect, it } from 'vitest'
import { MAX_EDGE, fitWithin } from './image'

describe('图片缩放的目标尺寸', () => {
  it('横图按最长边缩到上限', () => {
    expect(fitWithin(4000, 3000)).toEqual({ width: 1024, height: 768 })
  })

  it('竖图也一样按最长边', () => {
    expect(fitWithin(1200, 3000)).toEqual({ width: 410, height: 1024 })
  })

  it('本来就小的不放大', () => {
    // 放大只会让文件变大、还更糊
    expect(fitWithin(800, 600)).toEqual({ width: 800, height: 600 })
  })

  it('正方形正好等于上限时不动', () => {
    expect(fitWithin(MAX_EDGE, MAX_EDGE)).toEqual({ width: 1024, height: 1024 })
  })

  it('极端细长的图不会缩出 0 像素', () => {
    // round 之后可能是 0，canvas 宽度 0 会直接抛
    const r = fitWithin(10000, 5)
    expect(r.width).toBe(1024)
    expect(r.height).toBeGreaterThanOrEqual(1)
  })

  it('0 尺寸不炸（坏图 / 还没解码完）', () => {
    const r = fitWithin(0, 0)
    expect(r.width).toBeGreaterThanOrEqual(1)
    expect(r.height).toBeGreaterThanOrEqual(1)
  })
})
