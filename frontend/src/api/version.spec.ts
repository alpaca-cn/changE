import { describe, expect, it, vi } from 'vitest'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

import { buildTimeText, checkForUpdate, isNewer } from './version'

describe('版本比较', () => {
  it('按数字段比，而不是字符串比', () => {
    // 字符串比较会认为 1.9.0 > 1.10.0，这是最经典的坑
    expect(isNewer('1.10.0', '1.9.0')).toBe(true)
    expect(isNewer('1.9.0', '1.10.0')).toBe(false)
  })

  it('位数不齐时按 0 补', () => {
    expect(isNewer('1.2', '1.2.0')).toBe(false)
    expect(isNewer('1.2.1', '1.2')).toBe(true)
  })

  it('相同版本不算新', () => {
    expect(isNewer('0.1.0', '0.1.0')).toBe(false)
  })

  it('格式坏掉时返回 false（不知道谁新时不提示更新）', () => {
    // 提示了才去下，下错了更麻烦。宁可不提示。
    expect(isNewer('latest', '0.1.0')).toBe(false)
    expect(isNewer('1.0.0', 'abc')).toBe(false)
  })
})

describe('构建时间的显示', () => {
  it('ISO 串变成人能读的本地时间', () => {
    expect(buildTimeText('2026-10-21T18:54:00')).toMatch(/^2026-10-21 \d{2}:\d{2}$/)
  })

  it('坏值原样返回，不抛也不显示 Invalid Date', () => {
    expect(buildTimeText('不是时间')).toBe('不是时间')
  })
})

describe('检查更新', () => {
  it('没配更新源时明说「没配」，不是「已是最新」', async () => {
    // 这两件事混起来会让人以为自己永远是最新的
    const r = await checkForUpdate('')
    expect(r.kind).toBe('no-source')
  })

  it('清单版本更高时给出最新版本与说明', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => ({
      status: 200,
      body: JSON.stringify({ version: '99.0.0', notes: '修了很多', url: 'https://x/y.exe' }),
    }))
    const r = await checkForUpdate('https://example.com/latest.json')
    expect(r.kind).toBe('available')
    if (r.kind === 'available') {
      expect(r.latest).toBe('99.0.0')
      expect(r.notes).toBe('修了很多')
    }
  })

  it('清单版本相同或更低时是「已是最新」', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => ({
      status: 200,
      body: JSON.stringify({ version: '0.0.1' }),
    }))
    const r = await checkForUpdate('https://example.com/latest.json')
    expect(r.kind).toBe('up-to-date')
  })

  it('连不上更新源时是 failed，不是 up-to-date', async () => {
    // 「查不到」与「没有新版」必须分开
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => {
      throw new Error('连不上后端')
    })
    const r = await checkForUpdate('https://example.com/latest.json')
    expect(r.kind).toBe('failed')
  })

  it('清单缺 version 时是 failed', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => ({ status: 200, body: '{}' }))
    const r = await checkForUpdate('https://example.com/latest.json')
    expect(r.kind).toBe('failed')
  })

  it('请求走后端（GET），不在前端直接 fetch', async () => {
    // 外壳的 CSP 是 connect-src 'self'，前端 fetch 外部地址会被直接拒绝
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => ({ status: 200, body: '{version:1.0.0}' }))
    await checkForUpdate('https://example.com/latest.json')
    const args = invokeMock.mock.calls[0][1] as { method: string; path: string }
    expect(args.method).toBe('GET')
    expect(args.path).toContain('/api/version/manifest')
    expect(args.path).toContain(encodeURIComponent('https://example.com/latest.json'))
  })
})
