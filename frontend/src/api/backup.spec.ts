import { describe, expect, it, vi } from 'vitest'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

import { humanSize, createBackup, backupInfo } from './backup'

describe('备份接口', () => {
  it('大小显示成人能读的单位', () => {
    expect(humanSize(512)).toBe('512 B')
    expect(humanSize(2048)).toBe('2 KB')
    expect(humanSize(3 * 1024 * 1024)).toBe('3.0 MB')
  })

  it('0 字节也要显示出来，不能变成空字符串', () => {
    // 0 字节的备份是最坏的失败形态：看起来成功了，实际什么都没导出来
    expect(humanSize(0)).toBe('0 B')
  })

  it('导出用 POST，信息用 GET', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => ({
      status: 200,
      body: JSON.stringify({ path: 'C:/x.db', bytes: 1, at: '', inDocuments: true }),
    }))
    await createBackup()
    await backupInfo()
    const methods = invokeMock.mock.calls.map((c) => (c[1] as { method: string }).method)
    expect(methods).toEqual(['POST', 'GET'])
  })
})
