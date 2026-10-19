import { describe, it, expect, vi, beforeEach } from 'vitest'

// 前端**不发 HTTP**：所有后端访问都经外壳的 `api_request` 命令。所以这里 mock 的
// 是 `invoke`，不是 `fetch`（与 `backend.spec.ts` / `BackendStatusBar.spec.ts` 同一套）。
//
// 这一点很关键：真机上跨源被拦掉（CORS）时，**这个文件会全绿**——因为网络根本不在
// 它的观察范围内。跨源只有在计划 4 Task 6 的真机验收里才能被证明。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import {
  ApiError,
  ApiTransportError,
  lastPerformance,
  recentSessions,
  recordSession,
  searchExercises,
  sessionDetail,
  weeklyVolume,
} from './training'

const invokeMock = vi.mocked(invoke)

/** 外壳的成功返回：`{status, body}`，`body` 是**原始文本**（与 Rust `ApiResponse` 一致）。 */
function reply(status: number, body: unknown) {
  invokeMock.mockResolvedValue({
    status,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

/** 最近一次 invoke 的命令名与参数。 */
function lastCall() {
  const c = invokeMock.mock.calls.at(-1)!
  return { cmd: c[0] as string, args: c[1] as { method: string; path: string; body?: string } }
}

/**
 * **真机 JSON**（2026-10-19 从 `GET /api/volume/weekly?date=2026-10-19` 抓下来，原样复制，
 * 一个字符都没改）——库里当时有 1 次训练：交替地板卧推 2 组 60×8 完成。
 *
 * 用它而不是手写 17 块：手写的会漏掉真实形态（比如 `sets` 是 `2.0` 而不是 `2`、
 * `neck` 的 `min/max` 是 `null`）。事实核对：`chest` 2.0（主 +1×2）、
 * `abdominals` **1.0**（地板卧推的次要肌群，+0.5×2）、`triceps` 1.0（次要）、
 * `shoulders` 1.0（次要）、`lats` 0.0 → `UNTRAINED`、`neck` → `NOT_CONFIGURED`。
 */
const REAL_WEEKLY =
  '{"weekStart":"2026-10-19","weekEnd":"2026-10-25","muscles":[{"muscleGroupId":1,"nameZh":"胸部","nameEn":"chest","sets":2.0,"min":10,"max":20,"status":"BELOW"},{"muscleGroupId":2,"nameZh":"腹直肌","nameEn":"abdominals","sets":1.0,"min":8,"max":16,"status":"BELOW"},{"muscleGroupId":3,"nameZh":"背阔肌","nameEn":"lats","sets":0.0,"min":10,"max":20,"status":"UNTRAINED"},{"muscleGroupId":4,"nameZh":"中背部","nameEn":"middle back","sets":0.0,"min":8,"max":16,"status":"UNTRAINED"},{"muscleGroupId":5,"nameZh":"下背部","nameEn":"lower back","sets":0.0,"min":4,"max":10,"status":"UNTRAINED"},{"muscleGroupId":6,"nameZh":"斜方肌","nameEn":"traps","sets":0.0,"min":6,"max":14,"status":"UNTRAINED"},{"muscleGroupId":7,"nameZh":"肩部","nameEn":"shoulders","sets":1.0,"min":8,"max":18,"status":"BELOW"},{"muscleGroupId":8,"nameZh":"肱二头肌","nameEn":"biceps","sets":0.0,"min":6,"max":14,"status":"UNTRAINED"},{"muscleGroupId":9,"nameZh":"肱三头肌","nameEn":"triceps","sets":1.0,"min":6,"max":14,"status":"BELOW"},{"muscleGroupId":10,"nameZh":"前臂","nameEn":"forearms","sets":0.0,"min":4,"max":10,"status":"UNTRAINED"},{"muscleGroupId":11,"nameZh":"股四头肌","nameEn":"quadriceps","sets":0.0,"min":10,"max":20,"status":"UNTRAINED"},{"muscleGroupId":12,"nameZh":"腘绳肌","nameEn":"hamstrings","sets":0.0,"min":8,"max":16,"status":"UNTRAINED"},{"muscleGroupId":13,"nameZh":"臀肌","nameEn":"glutes","sets":0.0,"min":8,"max":16,"status":"UNTRAINED"},{"muscleGroupId":14,"nameZh":"小腿","nameEn":"calves","sets":0.0,"min":6,"max":14,"status":"UNTRAINED"},{"muscleGroupId":15,"nameZh":"内收肌","nameEn":"adductors","sets":0.0,"min":4,"max":10,"status":"UNTRAINED"},{"muscleGroupId":16,"nameZh":"外展肌","nameEn":"abductors","sets":0.0,"min":4,"max":10,"status":"UNTRAINED"},{"muscleGroupId":17,"nameZh":"颈部","nameEn":"neck","sets":0.0,"min":null,"max":null,"status":"NOT_CONFIGURED"}]}'

beforeEach(() => {
  invokeMock.mockReset()
})

describe('命令名与请求形状', () => {
  it('命令名恒为 api_request（跨语言契约）', async () => {
    // Rust 侧 `lib.rs` 的 `generate_handler![]` 里少注册 `api_request`，
    // 前端这里就会 reject（Tauri 报 command not found）。这个文件 mock 掉了 invoke，
    // 所以真正守住注册表的是 Rust 侧那条 `every_command_is_registered_in_the_invoke_handler`；
    // 这里只钉住「前端调的命令名是哪一个」。
    reply(200, { weekStart: 'x', weekEnd: 'y', muscles: [] })
    await weeklyVolume()
    expect(invokeMock).toHaveBeenCalledTimes(1)
    expect(lastCall().cmd).toBe('api_request')
  })

  it('GET 不带 body（body 是 undefined，不是 "undefined" 这个字符串）', async () => {
    reply(200, [])
    await recentSessions()
    const { args } = lastCall()
    expect(args.method).toBe('GET')
    expect(args.body).toBeUndefined()
  })

  it('weekly_volume_passes_the_date_as_a_query_parameter', async () => {
    reply(200, { weekStart: 'x', weekEnd: 'y', muscles: [] })
    await weeklyVolume('2026-10-19')
    expect(lastCall().args.path).toBe('/api/volume/weekly?date=2026-10-19')
  })

  it('weekly_volume_without_a_date_sends_no_query', async () => {
    // 「省略 = 今天」是**后端**的决定。前端自己算日期会在跨零点/跨时区时和后端不一致。
    reply(200, { weekStart: 'x', weekEnd: 'y', muscles: [] })
    await weeklyVolume()
    expect(lastCall().args.path).toBe('/api/volume/weekly')
  })
})

describe('解析响应', () => {
  it('parses_the_weekly_volume_shape_the_backend_sends', async () => {
    reply(200, REAL_WEEKLY)

    const v = await weeklyVolume('2026-10-19')

    expect(v.weekStart).toBe('2026-10-19')
    expect(v.weekEnd).toBe('2026-10-25')
    expect(v.muscles).toHaveLength(17)

    const chest = v.muscles.find((m) => m.nameEn === 'chest')!
    expect(chest.nameZh).toBe('胸部')
    expect(chest.sets).toBe(2.0)
    expect(chest.min).toBe(10)
    expect(chest.max).toBe(20)
    expect(chest.status).toBe('BELOW')

    // 次要肌群 +0.5/组 真的到了前端：地板卧推的次要肌群是腹直肌与肱三头肌
    expect(v.muscles.find((m) => m.nameEn === 'abdominals')!.sets).toBe(1.0)
    expect(v.muscles.find((m) => m.nameEn === 'triceps')!.sets).toBe(1.0)

    // 0 组与「没配目标」是两个不同的事实，前端都要原样拿到
    expect(v.muscles.find((m) => m.nameEn === 'lats')!.status).toBe('UNTRAINED')
    const neck = v.muscles.find((m) => m.nameEn === 'neck')!
    expect(neck.status).toBe('NOT_CONFIGURED')
    expect(neck.min).toBeNull()
    expect(neck.max).toBeNull()
  })

  it('recent_sessions_parses_an_array', async () => {
    reply(200, '[{"id":1,"sessionDate":"2026-10-19","title":"胸 + 三头","perceivedState":"睡得好"}]')
    const list = await recentSessions()
    expect(list).toHaveLength(1)
    expect(list[0].sessionDate).toBe('2026-10-19')
    expect(list[0].perceivedState).toBe('睡得好')
  })

  it('session_detail_parses_the_nested_exercises_and_sets', async () => {
    reply(
      200,
      '{"id":1,"sessionDate":"2026-10-19","title":"胸 + 三头","perceivedState":"睡得好",' +
        '"note":null,"startedAt":"2026-10-19T19:30:00","endedAt":null,"exercises":[' +
        '{"sessionExerciseId":1,"exerciseId":16,"nameZh":"交替地板卧推","nameEn":"Alternating Floor Press",' +
        '"orderIndex":0,"note":"主项","supersetGroup":null,"sets":[' +
        '{"id":1,"setIndex":0,"setType":"normal","weight":60.0,"repetitions":8,"durationSeconds":null,' +
        '"perSide":false,"rir":null,"restSeconds":null,"completed":true,"targetWeight":null,"targetReps":null}]}]}',
    )

    const d = await sessionDetail(1)

    expect(d.exercises).toHaveLength(1)
    expect(d.exercises[0].nameZh).toBe('交替地板卧推')
    expect(d.exercises[0].sets[0].weight).toBe(60)
    expect(d.exercises[0].sets[0].completed).toBe(true)
  })
})

describe('上次表现：204 不是错误', () => {
  it('last_performance_returns_null_for_204', async () => {
    // 「这个动作从没做过」是**正常状态**，后端用 204（不是 404）。
    // 把它变成异常会让界面上多出一句吓人的话。
    reply(204, '')
    await expect(lastPerformance(16)).resolves.toBeNull()
  })

  it('last_performance_parses_200', async () => {
    reply(
      200,
      '{"sessionDate":"2026-10-19","sessionExerciseId":1,"sets":[' +
        '{"setType":"normal","weight":60.0,"repetitions":8,"durationSeconds":null,"perSide":false},' +
        '{"setType":"normal","weight":60.0,"repetitions":8,"durationSeconds":null,"perSide":false}]}',
    )
    const lp = await lastPerformance(16)
    expect(lp!.sessionDate).toBe('2026-10-19')
    expect(lp!.sessionExerciseId).toBe(1)
    expect(lp!.sets.map((s) => s.weight)).toEqual([60, 60])
    expect(lp!.sets.map((s) => s.repetitions)).toEqual([8, 8])
  })
})

describe('动作选择器', () => {
  it('search_exercises_builds_the_query_from_defined_params_only', async () => {
    reply(200, '[]')
    await searchExercises({ muscleGroupId: 1, q: '卧推', category: undefined, limit: undefined })
    // 中文必须 URL 编码；未给的参数**不出现**在 URL 里（后端对「省略」自有解释）
    expect(lastCall().args.path).toBe('/api/exercises?muscleGroupId=1&q=%E5%8D%A7%E6%8E%A8')
  })

  it('search_exercises_with_only_a_query_still_works', async () => {
    reply(200, '[]')
    await searchExercises({ q: 'bench' })
    expect(lastCall().args.path).toBe('/api/exercises?q=bench')
  })

  it('search_exercises_with_no_params_asks_for_the_default_page', async () => {
    reply(200, '[]')
    await searchExercises({})
    expect(lastCall().args.path).toBe('/api/exercises')
  })
})

describe('写一次训练', () => {
  it('record_session_serializes_the_input_as_the_body', async () => {
    const input = {
      sessionDate: '2026-10-19',
      title: '胸 + 三头',
      perceivedState: null,
      note: null,
      startedAt: null,
      endedAt: null,
      exercises: [
        {
          exerciseId: 16,
          note: null,
          supersetGroup: null,
          sets: [
            {
              setType: 'normal',
              weight: 60,
              repetitions: 8,
              durationSeconds: null,
              perSide: false,
              rir: null,
              restSeconds: null,
              completed: true,
              targetWeight: null,
              targetReps: null,
            },
          ],
        },
      ],
    }
    reply(200, { sessionId: 7, exercises: 1, sets: 1 })

    const r = await recordSession(input)

    expect(r).toEqual({ sessionId: 7, exercises: 1, sets: 1 })
    const { args } = lastCall()
    expect(args.method).toBe('POST')
    expect(args.path).toBe('/api/sessions')
    // body 是 JSON 文本（外壳只转交文本，不解析）
    expect(typeof args.body).toBe('string')
    expect(JSON.parse(args.body!)).toEqual(input)
  })
})

describe('错误分流', () => {
  it('a_400_becomes_an_ApiError_carrying_the_server_message', async () => {
    // 后端 400 的 `{error}` 就是给用户看的中文校验文案，**必须**被带出来。
    // 这是我按下「结束训练」却填错时唯一能得到的解释。
    reply(400, '{"error":"一次训练至少要有 1 个动作"}')

    const err = await recordSession({
      sessionDate: '2026-10-19',
      title: null,
      perceivedState: null,
      note: null,
      startedAt: null,
      endedAt: null,
      exercises: [],
    }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(400)
    expect((err as ApiError).message).toBe('一次训练至少要有 1 个动作')
  })

  it('a_404_becomes_an_ApiError', async () => {
    reply(404, '{"error":"找不到这次训练: 999999"}')
    const err = await sessionDetail(999999).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(404)
    expect((err as ApiError).message).toContain('999999')
  })

  it('a_status_without_an_error_field_still_says_the_code', async () => {
    // 后端没给 `{error}`（比如容器自己回的 502）：兜底也要让人知道是几百，
    // 而不是一句无从下手的「失败」。
    reply(502, '')
    const err = await recentSessions().catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).message).toContain('502')
  })

  it('a_transport_rejection_propagates_as_an_error', async () => {
    // 外壳 reject：连不上 / 超时 / 后端没起来。**不是**状态码能表达的东西。
    invokeMock.mockRejectedValue(new Error('连不上后端 127.0.0.1:1：拒绝连接'))
    const err = await recentSessions().catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiTransportError)
    expect((err as Error).message).toContain('连不上')
  })

  it('a_malformed_body_is_an_error_not_a_silent_null', async () => {
    // 反面：读不懂的正文**不能**变成 undefined 流到界面（会静默渲染成空白，
    // 而调用方以为拿到了数据）。
    reply(200, 'not json at all')
    const err = await weeklyVolume().catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as Error).message).toContain('读不懂')
  })

  it('an_error_reply_is_not_decoded_as_a_success_even_if_it_is_a_204_like_empty_body', async () => {
    // 3xx 也不该被当成成功（外壳按 Connection: close 读完整条响应，不会自动跟随跳转）
    reply(302, '')
    const err = await recentSessions().catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(302)
  })
})
