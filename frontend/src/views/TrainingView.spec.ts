import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { DOMWrapper } from '@vue/test-utils'
import TrainingView from './TrainingView.vue'
import {
  EXERCISES_CHEST,
  LAST_PERF,
  RECENT,
  SESSION_1,
  WEEKLY_17,
} from '../api/__fixtures__/training-responses'

// 与其它前端 spec 同一套：mock `invoke`（前端不发 HTTP），按 **path** 分派。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

type Reply = { status: number; body: unknown } | 'reject'
type Matcher = string | ((p: string) => boolean)
type Rule = [Matcher, Reply]

/**
 * 分派规则，**按顺序**取第一条命中的。
 *
 * 字符串匹配是「精确相等，或相等后跟查询串」——不是前缀匹配。这一点是刻意的：
 * `/api/exercises` 前缀匹配会连 `/api/exercises/364/last-performance` 一起吞掉，
 * 于是「带出上次重量」那条测试会拿到动作列表，变成一条看不出问题的假绿。
 */
function dispatch(rules: Rule[]) {
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (_cmd: unknown, args: unknown) => {
    const path = (args as { path: string }).path
    for (const [m, reply] of rules) {
      const hit = typeof m === 'function' ? m(path) : path === m || path.startsWith(`${m}?`)
      if (!hit) continue
      if (reply === 'reject') throw new Error('连不上后端 127.0.0.1:1：拒绝连接')
      return {
        status: reply.status,
        body: typeof reply.body === 'string' ? reply.body : JSON.stringify(reply.body),
      }
    }
    throw new Error(`测试没有为 ${path} 准备应答`)
  })
}

const isExercises = (p: string) => p === '/api/exercises' || p.startsWith('/api/exercises?')
const isLastPerf = (p: string) => p.endsWith('/last-performance')

/** 正常路径的全部端点。`overrides` 排在前面，所以能盖掉同名规则。 */
function baseRules(overrides: Rule[] = []): Rule[] {
  return [
    ...overrides,
    [isLastPerf, { status: 200, body: LAST_PERF }],
    [isExercises, { status: 200, body: EXERCISES_CHEST }],
    ['/api/volume/weekly', { status: 200, body: WEEKLY_17 }],
    ['/api/sessions/recent', { status: 200, body: RECENT }],
    ['/api/sessions/1', { status: 200, body: SESSION_1 }],
    ['/api/sessions', { status: 200, body: { sessionId: 9, exercises: 1, sets: 2 } }],
  ]
}

async function mountTraining(rules: Rule[] = baseRules()) {
  dispatch(rules)
  const wrapper = mount(TrainingView)
  await flushPromises()
  return wrapper
}

function paths(): string[] {
  return invokeMock.mock.calls.map((c) => (c[1] as { path: string }).path)
}

/** 原生 input 的当前值。 */
function value(input: DOMWrapper<Element>): string {
  return (input.element as HTMLInputElement).value
}

function checked(box: DOMWrapper<Element>): boolean {
  return (box.element as HTMLInputElement).checked
}

function weights(wrapper: Awaited<ReturnType<typeof mountTraining>>): string[] {
  return wrapper.findAll('.weight').map(value)
}

/** 选中第一个动作（id 364 · JM 卧推）。 */
async function pickFirstExercise(wrapper: Awaited<ReturnType<typeof mountTraining>>) {
  await wrapper.find('.muscle-picker').setValue('1')
  await flushPromises()
  await wrapper.findAll('.exercise')[0].trigger('click')
  await flushPromises()
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('训练记录页 · 选肌群', () => {
  it('muscle_picker_comes_from_the_weekly_volume_muscles_not_a_new_endpoint', async () => {
    // 肌群列表复用 `GET /api/volume/weekly` 的那 17 块，**不新增后端端点**。
    // 计划 3 的后端已冻结；再加一个 `/api/muscles` 就得同时改它。
    const wrapper = await mountTraining()

    const options = wrapper.find('.muscle-picker').findAll('option')
    expect(options).toHaveLength(18) // 17 块 + 1 个「选择肌群」占位
    expect(options[1].text()).toBe('胸部')
    expect(options[17].text()).toBe('颈部')

    expect(paths().some((p) => p.startsWith('/api/muscles'))).toBe(false)
  })

  it('a_failed_muscle_load_shows_an_error_not_an_empty_picker', async () => {
    // 空下拉会被读成「库里一块肌群都没有」，而真因是取数失败。
    const wrapper = await mountTraining(baseRules([['/api/volume/weekly', 'reject']]))

    expect(wrapper.find('.muscle-picker').exists()).toBe(false)
    expect(wrapper.text()).toContain('连不上')
    // 页面骨架还在
    expect(wrapper.find('h2').text()).toBe('训练记录')
  })

  it('picking_a_muscle_searches_exercises_by_muscle_group_id', async () => {
    const wrapper = await mountTraining()

    await wrapper.find('.muscle-picker').setValue('1')
    await flushPromises()

    expect(paths()).toContain('/api/exercises?muscleGroupId=1')
    expect(wrapper.findAll('.exercise')).toHaveLength(3)
    expect(wrapper.text()).toContain('JM 卧推')
  })
})

describe('训练记录页 · 搜索（防抖）', () => {
  it('typing_a_query_debounces_and_searches_by_q', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountTraining()
      const q = wrapper.find('.query')

      await q.setValue('b')
      await q.setValue('be')
      await q.setValue('ben')

      // 打字过程中一次都不该打后端
      expect(paths().filter((p) => p.includes('q='))).toHaveLength(0)

      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()

      const searched = paths().filter((p) => p.includes('q='))
      expect(searched).toHaveLength(1)
      expect(searched[0]).toBe('/api/exercises?q=ben')
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('训练记录页 · 带出上次重量', () => {
  it('picking_an_exercise_prefills_the_last_performance_weights', async () => {
    const wrapper = await mountTraining()
    await pickFirstExercise(wrapper)

    // 真机夹具：上次是 60×8、60×8、以及一组没记数
    expect(weights(wrapper)).toEqual(['60', '60', ''])
    expect(wrapper.findAll('.reps').map(value)).toEqual(['8', '8', ''])
    expect(wrapper.find('.prefill').text()).toContain('2026-10-19')
  })

  it('prefilled_sets_are_not_marked_completed_by_default', async () => {
    // 「上次做了」≠「这次做了」。全勾上会让还没做的组静默计入周容量。
    const wrapper = await mountTraining()
    await pickFirstExercise(wrapper)

    const boxes = wrapper.findAll('.completed')
    expect(boxes.length).toBeGreaterThan(0)
    expect(boxes.every((b) => checked(b) === false)).toBe(true)
  })

  it('a_fresh_exercise_starts_with_one_empty_set_and_says_so', async () => {
    const wrapper = await mountTraining(
      baseRules([[isLastPerf, { status: 204, body: '' }]]),
    )
    await pickFirstExercise(wrapper)

    expect(wrapper.findAll('.set-row')).toHaveLength(1)
    expect(weights(wrapper)).toEqual([''])
    expect(wrapper.find('.prefill').text()).toContain('没记录过')
  })

  it('a_failed_last_performance_does_not_block_recording', async () => {
    // 带不出上次不该拦住记录：给一行空组，把原因说出来。
    const wrapper = await mountTraining(baseRules([[isLastPerf, 'reject']]))
    await pickFirstExercise(wrapper)

    expect(wrapper.findAll('.set-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('连不上')
  })
})

describe('训练记录页 · 组行', () => {
  it('the_completed_checkbox_is_explained_in_the_ui', async () => {
    // 「没打勾的组不算训练量」不写出来就没人知道，而它直接影响周容量的数字。
    const wrapper = await mountTraining()
    await pickFirstExercise(wrapper)
    expect(wrapper.text()).toContain('打勾的组才算训练量')
  })

  it('the_set_rows_delete_button_removes_just_that_row', async () => {
    const wrapper = await mountTraining()
    await pickFirstExercise(wrapper)

    // 三行：['60','60',''] → 把最后一行的重量改成 50，再删掉中间那行
    await wrapper.findAll('.weight')[2].setValue('50')
    expect(weights(wrapper)).toEqual(['60', '60', '50'])

    await wrapper.findAll('.remove')[1].trigger('click')
    await flushPromises()

    // 删掉的是**中间那行**：首尾两行的值必须还在
    expect(weights(wrapper)).toEqual(['60', '50'])
  })

  it('adding_a_row_appends_an_empty_one', async () => {
    const wrapper = await mountTraining()
    await pickFirstExercise(wrapper)
    const before = wrapper.findAll('.set-row').length

    await wrapper.find('.add-row').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.set-row')).toHaveLength(before + 1)
    expect(weights(wrapper).at(-1)).toBe('')
  })
})

describe('训练记录页 · 写入', () => {
  it('submitting_posts_the_session_input_and_shows_the_counts', async () => {
    const wrapper = await mountTraining()
    await pickFirstExercise(wrapper)

    // 勾前两组（第三组是上次没做的，留着不勾）
    await wrapper.findAll('.completed')[0].setValue(true)
    await wrapper.findAll('.completed')[1].setValue(true)

    await wrapper.find('.primary').trigger('click')
    await flushPromises()

    const post = invokeMock.mock.calls.find(
      (c) => (c[1] as { method: string }).method === 'POST',
    )!
    const body = JSON.parse((post[1] as { body: string }).body)

    // 独立算法算「今天」：`toLocaleDateString('sv-SE')` 就是本地的 YYYY-MM-DD
    const today = new Date().toLocaleDateString('sv-SE')
    expect(body.sessionDate).toBe(today)
    expect(body.exercises).toHaveLength(1)
    expect(body.exercises[0].exerciseId).toBe(364)
    expect(body.exercises[0].sets).toHaveLength(3)
    expect(body.exercises[0].sets.map((s: { completed: boolean }) => s.completed)).toEqual([
      true,
      true,
      false,
    ])

    expect(wrapper.text()).toContain('已记录：1 个动作 / 2 组')
  })

  it('a_successful_save_clears_the_form_and_refreshes_the_history', async () => {
    const wrapper = await mountTraining()
    await pickFirstExercise(wrapper)
    expect(wrapper.findAll('.set-row')).toHaveLength(3)

    await wrapper.find('.primary').trigger('click')
    await flushPromises()

    // 表单清空（选中动作被取消 → 整个记录区消失）
    expect(wrapper.findAll('.set-row')).toHaveLength(0)
    expect(wrapper.find('.primary').exists()).toBe(false)
    // 最近训练被重新取了一次（挂载时 1 次 + 保存后 1 次）
    expect(paths().filter((p) => p === '/api/sessions/recent')).toHaveLength(2)
    expect(wrapper.text()).toContain('已记录')
  })

  it('a_400_shows_the_server_validation_message_and_keeps_the_form', async () => {
    const wrapper = await mountTraining(
      baseRules([['/api/sessions', { status: 400, body: { error: '一次训练至少要有 1 个动作' } }]]),
    )
    await pickFirstExercise(wrapper)
    await wrapper.findAll('.weight')[0].setValue('62.5')

    await wrapper.find('.primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('一次训练至少要有 1 个动作')
    // **表单不清空**：服务端拒绝时把用户填的东西丢掉是最气人的事
    expect(weights(wrapper)[0]).toBe('62.5')
  })

  it('a_transport_failure_on_save_shows_the_reason', async () => {
    const wrapper = await mountTraining(baseRules([['/api/sessions', 'reject']]))
    await pickFirstExercise(wrapper)

    await wrapper.find('.primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('连不上')
    expect(wrapper.findAll('.set-row')).toHaveLength(3) // 表单还在
  })

  it('refuses_to_submit_without_picking_an_exercise', async () => {
    // 没选动作时连「结束训练」按钮都不存在（记录区没渲染），所以这条走的是
    // 「选了动作但一行组都没有」那条路。
    const wrapper = await mountTraining()
    await pickFirstExercise(wrapper)
    await wrapper.findAll('.remove')[0].trigger('click')
    await wrapper.findAll('.remove')[0].trigger('click')
    await wrapper.findAll('.remove')[0].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.set-row')).toHaveLength(0)

    await wrapper.find('.primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('至少要有一组')
    // 没发 POST
    expect(invokeMock.mock.calls.some((c) => (c[1] as { method: string }).method === 'POST')).toBe(false)
  })
})

describe('训练记录页 · 历史', () => {
  it('history_detail_opens_on_click', async () => {
    const wrapper = await mountTraining()

    await wrapper.find('.sessions .session').trigger('click')
    await flushPromises()

    expect(paths()).toContain('/api/sessions/1')
    const detail = wrapper.find('.detail')
    expect(detail.text()).toContain('交替地板卧推')
    expect(detail.text()).toContain('3 组')
  })

  it('a_failed_detail_load_shows_the_reason', async () => {
    const wrapper = await mountTraining(
      baseRules([['/api/sessions/1', { status: 404, body: { error: '找不到这次训练: 1' } }]]),
    )

    await wrapper.find('.sessions .session').trigger('click')
    await flushPromises()

    expect(wrapper.find('.detail').exists()).toBe(false)
    expect(wrapper.text()).toContain('找不到这次训练')
  })
})
