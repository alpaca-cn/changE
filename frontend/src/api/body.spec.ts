import { beforeEach, describe, expect, it, vi } from 'vitest'

// 与其它前端 spec 同一套：前端不发 HTTP，mock `invoke`（走 api_request 命令）。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { ApiError } from './training'
import {
  METRIC_DEFAULT_UNITS,
  METRIC_LABELS,
  METRIC_TYPES,
  addMetric,
  listMetrics,
  metricLabel,
  types,
} from './body'

const invokeMock = vi.mocked(invoke)

function reply(status: number, body: unknown) {
  invokeMock.mockResolvedValue({
    status,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

function lastCall() {
  const c = invokeMock.mock.calls.at(-1)!
  return { cmd: c[0] as string, args: c[1] as { method: string; path: string; body?: string } }
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('请求形状', () => {
  it('listMetrics 的 type 进查询串，路径逐字精确', async () => {
    reply(200, '[]')
    await listMetrics('weight')

    const { cmd, args } = lastCall()
    expect(cmd).toBe('api_request')
    expect(args.method).toBe('GET')
    expect(args.path).toBe('/api/body-metrics?type=weight')
  })

  it('listMetrics 不给类型就是裸路径（后端返回全部类型）', async () => {
    reply(200, '[]')
    await listMetrics()
    expect(lastCall().args.path).toBe('/api/body-metrics')
  })

  it('addMetric 走 POST，body 原样就是那个对象', async () => {
    const input = {
      measuredOn: '2026-10-19',
      metricType: 'weight',
      value: 72.5,
      unit: null,
      note: '早上空腹',
    }
    reply(200, { id: 12 })

    const created = await addMetric(input)

    const { args } = lastCall()
    expect(args.method).toBe('POST')
    expect(args.path).toBe('/api/body-metrics')
    expect(JSON.parse(args.body!)).toEqual(input)
    // 响应只有 id：这个端点没有可实时算的派生值
    expect(created).toEqual({ id: 12 })
  })

  it('types 走固定路径 /api/body-metrics/types', async () => {
    reply(200, '[]')
    await types()
    expect(lastCall().args.method).toBe('GET')
    expect(lastCall().args.path).toBe('/api/body-metrics/types')
  })
})

describe('错误与空结果', () => {
  it('400 带着后端 {error} 那句文案，不是「请求失败」', async () => {
    const message =
      'metricType 不合法：gremlin（合法值 [weight, body_fat, waist, chest, hip, arm, thigh, calf, shoulder]）'
    reply(400, { error: message })

    const err = await addMetric({
      measuredOn: '2026-10-19',
      metricType: 'gremlin',
      value: 72,
      unit: null,
      note: null,
    }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(400)
    expect((err as Error).message).toBe(message)
    expect((err as Error).message).not.toContain('请求失败')
  })

  it('日期形状错误也原样带着后端那句中文', async () => {
    const message = 'measuredOn 必须是 YYYY-MM-DD（例 2026-10-19），实际为 2026/10/19'
    reply(400, { error: message })

    const err = await addMetric({
      measuredOn: '2026/10/19',
      metricType: 'weight',
      value: 72,
      unit: null,
      note: null,
    }).catch((e: unknown) => e)

    expect((err as Error).message).toBe(message)
  })

  it('空结果是 []，不是错误（新库里一条都没记过）', async () => {
    reply(200, '[]')
    await expect(listMetrics('weight')).resolves.toEqual([])

    reply(200, '[]')
    await expect(types()).resolves.toEqual([])
  })
})

describe('类型词汇', () => {
  it('九个类型，顺序与后端 MetricType 枚举一致', () => {
    expect([...METRIC_TYPES]).toEqual([
      'weight',
      'body_fat',
      'waist',
      'chest',
      'hip',
      'arm',
      'thigh',
      'calf',
      'shoulder',
    ])
  })

  it('每个类型都有中文名与默认单位（前端只用它显示，不发给后端）', () => {
    expect(METRIC_LABELS.weight).toBe('体重')
    expect(METRIC_LABELS.body_fat).toBe('体脂率')
    expect(METRIC_DEFAULT_UNITS.weight).toBe('kg')
    expect(METRIC_DEFAULT_UNITS.body_fat).toBe('%')
    expect(METRIC_DEFAULT_UNITS.waist).toBe('cm')
    for (const t of METRIC_TYPES) {
      expect(METRIC_LABELS[t]).toBeTruthy()
      expect(METRIC_DEFAULT_UNITS[t]).toBeTruthy()
    }
  })

  it('认不出的历史类型原样显示，不消失也不报错', () => {
    // 读路径是开放词汇：库里可能有一条绕过 Java 写进去的 bicep
    expect(metricLabel('bicep')).toBe('bicep')
  })
})
