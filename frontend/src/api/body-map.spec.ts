import { beforeEach, describe, expect, it, vi } from 'vitest'

// 与其它前端 spec 同一套：前端不发 HTTP，mock `invoke`（走 api_request 命令）。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { ApiError } from './training'
import { bodyMapVolume } from './body-map'

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

/**
 * **这段 JSON 来自 Task 3 实现完成后对 `GET /api/body-map/volume` 的实测响应**，
 * 不是照着类型手写的对象：
 *
 * ```
 * {"weekStart":"2026-10-19","weekEnd":"2026-10-25","today":"2026-10-20",
 *  "muscles":[ … 17 块 … ]}
 * ```
 *
 * 真实响应的 `muscles` 有全部 17 块；这里只摘出 5 块覆盖本客户端需要区分的形状：
 * 有目标且有组数（chest）、0 组（abdominals）、半组（shoulders / triceps）、
 * 以及**没配目标**（neck，min/max 为 null）。其余 12 块字段形状相同。
 *
 * 用它而不是手写对象，是为了让「后端改字段名」这件事先在这里变红：
 * 手写的对象永远与类型自洽，抓不到契约漂移。
 */
const VOLUME_JSON =
  '{"weekStart":"2026-10-19","weekEnd":"2026-10-25","today":"2026-10-20","muscles":[' +
  '{"muscleGroupId":1,"nameZh":"胸部","nameEn":"chest","sets":3.0,"min":10,"max":20,"status":"BELOW","lastTrainedOn":"2026-10-20","daysSince":0,"regionIds":["chest-upper-left","chest-lower-left","chest-upper-right","chest-lower-right"]},' +
  '{"muscleGroupId":2,"nameZh":"腹直肌","nameEn":"abdominals","sets":0.0,"min":8,"max":16,"status":"UNTRAINED","lastTrainedOn":null,"daysSince":null,"regionIds":["abs-upper-left","abs-lower-left","abs-upper-right","abs-lower-right","obliques-left","obliques-right"]},' +
  '{"muscleGroupId":7,"nameZh":"肩部","nameEn":"shoulders","sets":1.5,"min":8,"max":18,"status":"BELOW","lastTrainedOn":"2026-10-20","daysSince":0,"regionIds":["shoulder-front-left","shoulder-side-left","deltoid-rear-left","shoulder-front-right","shoulder-side-right","deltoid-rear-right"]},' +
  '{"muscleGroupId":9,"nameZh":"肱三头肌","nameEn":"triceps","sets":1.5,"min":6,"max":14,"status":"BELOW","lastTrainedOn":"2026-10-20","daysSince":0,"regionIds":["triceps-long-left","triceps-lateral-left","triceps-long-right","triceps-lateral-right"]},' +
  '{"muscleGroupId":17,"nameZh":"颈部","nameEn":"neck","sets":0.0,"min":null,"max":null,"status":"NOT_CONFIGURED","lastTrainedOn":null,"daysSince":null,"regionIds":["neck-left","neck-right","nape"]}' +
  ']}'

beforeEach(() => {
  invokeMock.mockReset()
})

describe('人体图客户端 · 请求形状', () => {
  it('bodyMapVolume 的路径精确到 /api/body-map/volume，不带 ?date=', async () => {
    reply(200, VOLUME_JSON)
    await bodyMapVolume()

    const { cmd, args } = lastCall()
    expect(cmd).toBe('api_request')
    expect(args.method).toBe('GET')
    // 这一个字符之差就是本计划 A 的硬要求：这一页只看「现在」，不传日期。
    expect(args.path).toBe('/api/body-map/volume')
    expect(args.path).not.toContain('date=')
    // 读端点没有请求体：传 undefined 而不是 'undefined'
    expect(args.body).toBeUndefined()
  })

  it('给了 date 才带 ?date=，且日期按原样出现在查询串里', async () => {
    reply(200, VOLUME_JSON)
    await bodyMapVolume('2026-10-19')

    expect(lastCall().args.path).toBe('/api/body-map/volume?date=2026-10-19')
  })
})

describe('人体图客户端 · 解析后端真实响应', () => {
  it('解析四个顶层字段、10 个肌肉字段，regionIds 是数组、daysSince 可 null', async () => {
    reply(200, VOLUME_JSON)

    const volume = await bodyMapVolume()

    // ---- 顶层四个字段 ----
    expect(volume.weekStart).toBe('2026-10-19')
    expect(volume.weekEnd).toBe('2026-10-25')
    expect(volume.today).toBe('2026-10-20')
    expect(volume.muscles.length).toBe(5)

    // 日期是 "YYYY-MM-DD" 字符串（不是 epoch 毫秒、不是 [2026,10,20]）
    for (const date of [volume.weekStart, volume.weekEnd, volume.today]) {
      expect(typeof date).toBe('string')
      expect(date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    }

    // ---- 一块有目标、有组数的肌肉：字段名逐字 ----
    const chest = volume.muscles[0]
    expect(chest).toEqual({
      muscleGroupId: 1,
      nameZh: '胸部',
      nameEn: 'chest',
      sets: 3,
      min: 10,
      max: 20,
      status: 'BELOW',
      lastTrainedOn: '2026-10-20',
      daysSince: 0,
      regionIds: ['chest-upper-left', 'chest-lower-left', 'chest-upper-right', 'chest-lower-right'],
    })
    // 后端 `sets` 是小数（折算组数可能是 .5），JSON 里的 3.0 解析成 3
    expect(chest.sets).toBe(3)

    // ---- 0 组、从没练过：daysSince 与 lastTrainedOn 都是 null，不是 0 ----
    const abs = volume.muscles[1]
    expect(abs.sets).toBe(0)
    expect(abs.status).toBe('UNTRAINED')
    expect(abs.lastTrainedOn).toBeNull()
    expect(abs.daysSince).toBeNull()
    // 「没练过」与「练过但 daysSince=0」在类型上必须分得开
    expect(abs.daysSince).not.toBe(0)

    // ---- 半组 ----
    expect(volume.muscles[2].sets).toBe(1.5)
    expect(volume.muscles[3].sets).toBe(1.5)

    // ---- 没配目标的肌肉：min/max 是 null，不是 0 ----
    const neck = volume.muscles[4]
    expect(neck.min).toBeNull()
    expect(neck.max).toBeNull()
    expect(neck.status).toBe('NOT_CONFIGURED')

    // ---- regionIds 是字符串数组，不是字符串、不是 undefined ----
    // 这条守的是本任务最危险的失效：导入错形状时 regionIds 是 undefined，
    // 整张人体图静默不着色（没有报错，只有一张灰图）。
    for (const muscle of volume.muscles) {
      expect(Array.isArray(muscle.regionIds), `${muscle.nameEn} 的 regionIds 应该是数组`).toBe(true)
      expect(muscle.regionIds.length).toBeGreaterThan(0)
      for (const regionId of muscle.regionIds) {
        expect(typeof regionId).toBe('string')
      }
    }

    // 顺序保持后端给的顺序（肌群 id 序），前端不重排
    expect(volume.muscles.map((m) => m.muscleGroupId)).toEqual([1, 2, 7, 9, 17])
    expect(volume.muscles.map((m) => m.nameZh)).toEqual(['胸部', '腹直肌', '肩部', '肱三头肌', '颈部'])
  })
})

describe('人体图客户端 · 错误', () => {
  it('非 2xx 抛 ApiError，message 是后端文案而不是写死的「加载失败」', async () => {
    const message = '找不到参考日对应的周: 2026-13-01'
    reply(400, { error: message })

    const err = await bodyMapVolume('2026-13-01').catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(400)
    expect((err as Error).message).toBe(message)
    // 页面会原样显示这句话：不能是前端编的
    expect((err as Error).message).not.toContain('加载失败')
  })

  it('500 也走同一条分流', async () => {
    reply(500, { error: '内部错误' })

    const err = await bodyMapVolume().catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(500)
    expect((err as Error).message).toBe('内部错误')
  })
})
