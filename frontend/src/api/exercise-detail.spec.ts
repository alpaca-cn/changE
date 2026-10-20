import { beforeEach, describe, expect, it, vi } from 'vitest'

// 与其它前端 spec 同一套：前端不发 HTTP，mock `invoke`（走外壳的 api_request 命令）。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { ApiError } from './training'
import { exerciseDetail, type ExerciseDetail } from './exercise-detail'

const invokeMock = vi.mocked(invoke)

function reply(status: number, body: unknown) {
  invokeMock.mockResolvedValue({
    status,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

function lastCall() {
  const c = invokeMock.mock.calls.at(-1)!
  // `InvokeArgs` 与这里的形状没有重叠，直接断言会被 TS 拒绝（TS2352）：
  // 先过一道 `unknown`，表明「我知道这条 mock 调用是我们自己造的」。
  return {
    cmd: c[0] as string,
    args: c[1] as unknown as { method: string; path: string; body?: string },
  }
}

/**
 * **Task 6 的实测响应**（2026-10-19，`GET /api/exercises/44`，`Barbell Bench Press - Medium Grip`）。
 *
 * 不是手写的：手写会漏掉真实形态，也会随文案漂移。这份原文里 `instructions` /
 * `instructionsZh` 各有 **5 段**（`\n` 分隔），`muscles` 是 primary 在前、
 * secondary 在后——这三件事都是界面直接依赖的，所以 fixture 必须是真的。
 *
 * 用 `String.raw`：里面那些 `\n` 是 JSON 的转义序列（反斜杠 + n），
 * 普通模板字符串会把它们变成真的换行符，JSON 就坏了。
 */
const REAL_DETAIL = String.raw`{"id":44,"nameEn":"Barbell Bench Press - Medium Grip","nameZh":"中握距杠铃卧推","equipment":"barbell","isCompound":true,"category":"strength","instructions":"Lie back on a flat bench. Using a medium width grip (a grip that creates a 90-degree angle in the middle of the movement between the forearms and the upper arms), lift the bar from the rack and hold it straight over you with your arms locked. This will be your starting position.\nFrom the starting position, breathe in and begin coming down slowly until the bar touches your middle chest.\nAfter a brief pause, push the bar back to the starting position as you breathe out. Focus on pushing the bar using your chest muscles. Lock your arms and squeeze your chest in the contracted position at the top of the motion, hold for a second and then start coming down slowly again. Tip: Ideally, lowering the weight should take about twice as long as raising it.\nRepeat the movement for the prescribed amount of repetitions.\nWhen you are done, place the bar back in the rack.","instructionsZh":"仰卧在平凳上。采用中等宽度的握距（即动作中段前臂与上臂之间形成 90 度角的握距），把横杠从架上取下，双臂锁定，将横杠笔直举在身体上方。这将是你的起始姿势。\n从起始姿势开始，吸气并缓慢下放，直到横杠触到胸部中间。\n稍作停顿后，呼气的同时把横杠推回起始姿势。注意用胸部肌肉发力推横杠。在动作顶端的收缩位置锁定手臂并挤压胸部，保持 1 秒，然后再开始缓慢下放。提示：理想情况下，下放重量所用的时间应约为上推的 2 倍。\n按规定的次数重复该动作。\n完成后，把横杠放回架上。","muscles":[{"muscleGroupId":1,"nameZh":"胸部","nameEn":"chest","role":"primary"},{"muscleGroupId":7,"nameZh":"肩部","nameEn":"shoulders","role":"secondary"},{"muscleGroupId":9,"nameZh":"肱三头肌","nameEn":"triceps","role":"secondary"}]}`

beforeEach(() => {
  invokeMock.mockReset()
})

describe('动作详情客户端 · 请求形状', () => {
  it('exercise_detail_requests_the_exact_path_with_the_id_in_it', async () => {
    reply(200, REAL_DETAIL)

    await exerciseDetail(44)

    const { cmd, args } = lastCall()
    expect(cmd).toBe('api_request')
    expect(args.method).toBe('GET')
    // 精确相等，不是「以它开头」：少了 id 的 `/api/exercises` 是另一个端点（列表）
    expect(args.path).toBe('/api/exercises/44')
    expect(args.body).toBeUndefined()

    // 另一个 id 也照原样进路径（防止把 44 写死）
    reply(200, REAL_DETAIL)
    await exerciseDetail(7)
    expect(lastCall().args.path).toBe('/api/exercises/7')
  })
})

describe('动作详情客户端 · 错误', () => {
  it('a_404_becomes_an_ApiError_with_the_backend_message', async () => {
    const message = '找不到这个动作: 99999999'
    reply(404, { error: message })

    const err = await exerciseDetail(99999999).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(404)
    expect((err as Error).message).toBe(message)
    expect((err as Error).message).not.toContain('请求失败')
  })
})

describe('动作详情客户端 · 可空字段不是错误', () => {
  it('a_null_instructions_zh_resolves_instead_of_throwing', async () => {
    // 871 条有中文、5 条原文为空（两个都是 null）——那是数据事实，不是错误。
    reply(200, {
      id: 44,
      nameEn: 'Barbell Bench Press - Medium Grip',
      nameZh: null,
      equipment: null,
      isCompound: false,
      category: null,
      instructions: 'Step one.\nStep two.',
      instructionsZh: null,
      muscles: [],
    })

    const detail = await exerciseDetail(44)

    expect(detail.instructionsZh).toBeNull()
    expect(detail.instructions).toContain('Step one.')
    expect(detail.nameZh).toBeNull()
  })

  it('both_instructions_null_still_resolves', async () => {
    // 数据集里那 5 条（Iron Cross、Push Press…）两个都是 null，后端不 404。
    reply(200, {
      id: 1,
      nameEn: 'Iron Cross',
      nameZh: null,
      equipment: 'dumbbell',
      isCompound: false,
      category: 'strength',
      instructions: null,
      instructionsZh: null,
      muscles: [],
    })

    await expect(exerciseDetail(1)).resolves.toMatchObject({
      instructions: null,
      instructionsZh: null,
    })
  })
})

describe('动作详情客户端 · 真实响应解析', () => {
  it('parses_the_task6_response_field_by_field', async () => {
    reply(200, REAL_DETAIL)

    const detail: ExerciseDetail = await exerciseDetail(44)

    // 键集合：后端 DTO 的九个键，多一个/少一个都是契约漂移（漂了只会静默拿到 undefined）
    expect(Object.keys(detail).sort()).toEqual([
      'category',
      'equipment',
      'id',
      'instructions',
      'instructionsZh',
      'isCompound',
      'muscles',
      'nameEn',
      'nameZh',
    ])

    expect(detail.id).toBe(44)
    expect(detail.nameEn).toBe('Barbell Bench Press - Medium Grip')
    expect(detail.nameZh).toBe('中握距杠铃卧推')
    expect(detail.equipment).toBe('barbell')
    expect(detail.isCompound).toBe(true)
    expect(detail.category).toBe('strength')

    // 中英文做法都是 5 段；`\n` 就是段落分隔（界面按它做有序列表）
    expect(detail.instructions!.split('\n')).toHaveLength(5)
    expect(detail.instructionsZh!.split('\n')).toHaveLength(5)
    expect(detail.instructionsZh!.startsWith('仰卧在平凳上。')).toBe(true)

    // 肌群：primary 在前、secondary 在后，各带中文名
    expect(detail.muscles).toHaveLength(3)
    expect(detail.muscles.map((m) => m.role)).toEqual(['primary', 'secondary', 'secondary'])
    expect(detail.muscles.map((m) => m.nameZh)).toEqual(['胸部', '肩部', '肱三头肌'])
    expect(detail.muscles[0]).toEqual({
      muscleGroupId: 1,
      nameZh: '胸部',
      nameEn: 'chest',
      role: 'primary',
    })
  })
})
