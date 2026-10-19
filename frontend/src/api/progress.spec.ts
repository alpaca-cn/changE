import { beforeEach, describe, expect, it, vi } from 'vitest'

// 与其它前端 spec 同一套：前端不发 HTTP，mock `invoke`（走 api_request 命令）。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { ApiError } from './training'
import { progress } from './progress'
import { PROGRESS_THREE_DAYS } from './__fixtures__/progress-responses'

const invokeMock = vi.mocked(invoke)

function reply(status: number, body: unknown) {
  invokeMock.mockResolvedValue({
    status,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

function lastCall() {
  const c = invokeMock.mock.calls.at(-1)!
  // `InvokeArgs` 与这里的形状没有重叠，直接断言会被 TS 拒绝（TS2352）；先过 `unknown`。
  return {
    cmd: c[0] as string,
    args: c[1] as unknown as { method: string; path: string; body?: string },
  }
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('请求形状', () => {
  it('progress 的路径精确到 exerciseId', async () => {
    reply(200, PROGRESS_THREE_DAYS)
    await progress(10521)

    const { cmd, args } = lastCall()
    expect(cmd).toBe('api_request')
    expect(args.method).toBe('GET')
    // path 少了 exerciseId 后端会 404；这条是那个变异的杀手
    expect(args.path).toBe('/api/progress/10521')
    // 读端点没有请求体：传 undefined 而不是 'undefined'
    expect(args.body).toBeUndefined()
  })
})

describe('错误与空结果', () => {
  it('404 抛出带后端文案的 ApiError，不是写死的「加载失败」', async () => {
    const message = '找不到这个动作: 999999'
    reply(404, { error: message })

    const err = await progress(999999).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(404)
    expect((err as Error).message).toBe(message)
    expect((err as Error).message).not.toContain('加载失败')
    // 「没练过」是 200 + []，不是 404——两者在这一层必须分得开
    expect((err as Error).message).not.toContain('还没练过')
  })

  it('points: [] 原样返回，不抛错', async () => {
    reply(200, '{"exerciseId":7,"nameEn":"Deadlift","nameZh":null,"points":[]}')

    const p = await progress(7)

    expect(p.exerciseId).toBe(7)
    expect(p.nameEn).toBe('Deadlift')
    expect(p.nameZh).toBeNull()
    expect(p.points).toEqual([])
  })

  it('解析后端真实响应：四个字段名、date 是字符串、e1rm 可为 null', async () => {
    // **这段 JSON 来自 ProgressControllerTest 的实测响应**，不是手写对象。
    // 详见 `./__fixtures__/progress-responses.ts` 顶部的出处。
    reply(200, PROGRESS_THREE_DAYS)

    const p = await progress(10521)

    expect(p.exerciseId).toBe(10521)
    expect(p.nameEn).toBe('ZZ Progress Http Bench')
    expect(p.nameZh).toBeNull()
    expect(p.points).toHaveLength(3)

    // 字段名逐字：后端改成 estimated1rm 时，这条会先于页面崩掉
    expect(p.points[0]).toEqual({
      date: '2026-10-14',
      e1rm: 76,
      maxWeight: 60,
      totalVolume: 480,
    })
    // date 是 "YYYY-MM-DD" 字符串，不是 [2026,10,14] 数组、也不是 epoch 毫秒
    expect(typeof p.points[0].date).toBe('string')
    expect(Array.isArray(p.points[0].date)).toBe(false)
    expect(p.points[0].date).toMatch(/^\d{4}-\d{2}-\d{2}$/)

    // 「有数据但估不出 e1rm」是保留点：e1rm 为 null，另外两条有值
    expect(p.points[1].e1rm).toBeNull()
    expect(p.points[1].maxWeight).toBe(20)
    expect(p.points[1].totalVolume).toBe(300)

    // 后端按训练日升序返回（ProgressServiceTest 钉住的契约）
    expect(p.points.map((point) => point.date)).toEqual([
      '2026-10-14',
      '2026-10-15',
      '2026-10-17',
    ])
    expect(p.points[2].e1rm).toBe(75.83)
  })
})
