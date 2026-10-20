import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import BodyMapView from './BodyMapView.vue'
import BodyMap from '../components/BodyMap.vue'
import BodyMapSidePanel from '../components/BodyMapSidePanel.vue'
// 用 `?raw` 读源码而不是 node:fs：jsdom 下 Vite 会把 node:fs 换成空壳
// （见 styles/theme.spec.ts 顶部那段注释），而 `?raw` 在构建/测试两个环境都只是字符串。
import viewSource from './BodyMapView.vue?raw'

// 前端不发 HTTP：`/api/exercises` 走 invoke → api_request。人体图数据这一层
// 另外整块 mock 掉 `../api/body-map`，这样能直接断言**调用参数**（本计划 A 的硬要求）。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

vi.mock('../api/body-map', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/body-map')>()
  return { ...actual, bodyMapVolume: vi.fn() }
})

import { invoke } from '@tauri-apps/api/core'
import { bodyMapVolume, type BodyMapVolume } from '../api/body-map'

const invokeMock = vi.mocked(invoke)
const bodyMapVolumeMock = vi.mocked(bodyMapVolume)

/**
 * **这段 JSON 来自 Task 3 实现完成后对 `GET /api/body-map/volume` 的实测响应**
 * （与 `api/body-map.spec.ts` 的夹具同源，只摘出 5 块肌肉：真实响应是 17 块，
 * 其余 12 块字段形状相同）。
 *
 * 这里原样用后端 JSON 而不是手写对象：`regionIds` 少一个字母，图上就有一块
 * 永远灰着，而 TypeScript 不会报错（`as` 是不做校验的断言）。
 */
const VOLUME_JSON =
  '{"weekStart":"2026-10-19","weekEnd":"2026-10-25","today":"2026-10-20","muscles":[' +
  '{"muscleGroupId":1,"nameZh":"胸部","nameEn":"chest","sets":3.0,"min":10,"max":20,"status":"BELOW","lastTrainedOn":"2026-10-20","daysSince":0,"regionIds":["chest-upper-left","chest-lower-left","chest-upper-right","chest-lower-right"]},' +
  '{"muscleGroupId":2,"nameZh":"腹直肌","nameEn":"abdominals","sets":0.0,"min":8,"max":16,"status":"UNTRAINED","lastTrainedOn":null,"daysSince":null,"regionIds":["abs-upper-left","abs-lower-left","abs-upper-right","abs-lower-right","obliques-left","obliques-right"]},' +
  '{"muscleGroupId":7,"nameZh":"肩部","nameEn":"shoulders","sets":1.5,"min":8,"max":18,"status":"BELOW","lastTrainedOn":"2026-10-20","daysSince":0,"regionIds":["shoulder-front-left","shoulder-side-left","deltoid-rear-left","shoulder-front-right","shoulder-side-right","deltoid-rear-right"]},' +
  '{"muscleGroupId":9,"nameZh":"肱三头肌","nameEn":"triceps","sets":1.5,"min":6,"max":14,"status":"BELOW","lastTrainedOn":"2026-10-20","daysSince":0,"regionIds":["triceps-long-left","triceps-lateral-left","triceps-long-right","triceps-lateral-right"]},' +
  '{"muscleGroupId":17,"nameZh":"颈部","nameEn":"neck","sets":0.0,"min":null,"max":null,"status":"NOT_CONFIGURED","lastTrainedOn":null,"daysSince":null,"regionIds":["neck-left","neck-right","nape"]}' +
  ']}'

const VOLUME = JSON.parse(VOLUME_JSON) as BodyMapVolume

/** `GET /api/exercises?muscleGroupId=1` 的一行。nameZh 为 null 时面板要退到 nameEn。 */
const EXERCISES =
  '[{"id":101,"nameEn":"ZZ Body Map Bench Press","nameZh":"杠铃卧推","equipment":"barbell","isCompound":true,"category":"strength"},' +
  '{"id":102,"nameEn":"ZZ Body Map Dumbbell Press","nameZh":null,"equipment":"dumbbell","isCompound":false,"category":"strength"}]'

interface Req {
  method: string
  path: string
  body?: string
}

function reqs(): Req[] {
  // `InvokeArgs` 与这里的 `Req` 没有重叠，直接断言会被 TS 拒绝（TS2352）：
  // 先过一道 `unknown`，表明「我知道这条 mock 调用是我们自己造的」。
  return invokeMock.mock.calls.map((c) => c[1] as unknown as Req)
}

async function mountView() {
  const wrapper = mount(BodyMapView)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (_cmd: unknown, args: unknown) => {
    const req = args as unknown as Req
    if (req.method === 'GET' && req.path.startsWith('/api/exercises')) {
      return { status: 200, body: EXERCISES }
    }
    throw new Error(`测试没有为 ${req.method} ${req.path} 准备应答`)
  })
  bodyMapVolumeMock.mockReset()
  bodyMapVolumeMock.mockResolvedValue(VOLUME)
})

describe('人体图页 · 加载与画布', () => {
  it('mounts_with_the_volume_mode_by_default_and_paints_from_regionIds', async () => {
    const wrapper = await mountView()

    // 只发一次请求，且拿到的就是本周人体图数据
    expect(bodyMapVolumeMock).toHaveBeenCalledTimes(1)

    const map = wrapper.findComponent(BodyMap)
    expect(map.exists()).toBe(true)
    // 默认口径是「本周组数」，不是另外两种
    expect(map.props('mode')).toBe('volume')
    expect(wrapper.find('.body-map').attributes('data-mode')).toBe('volume')

    // 本周区间来自响应，不是前端算的
    expect(wrapper.find('.span').text()).toBe('2026-10-19 → 2026-10-25')

    // **本任务最危险的失效点**：把 `muscles` 转成 `volumeByMuscle` 时若丢了
    // `regionIds`，整张图会一块都不着色，而且不报错。这条断言逐层验证：
    // 契约里的 regionIds → 组件 prop → 真的有一条 path 离开了基线色。
    const byMuscle = map.props('volumeByMuscle') as Record<number, { regionIds: string[] }>
    expect(byMuscle[1].regionIds).toContain('chest-upper-left')
    const chest = wrapper.find('[data-region-id="chest-upper-left"]')
    expect(chest.attributes('data-interactive')).toBe('true')
    expect(chest.attributes('data-intensity')).not.toBe('0')
  })

  it('three_mode_buttons_switch_the_mode_passed_to_the_body_map', async () => {
    const wrapper = await mountView()

    const buttons = wrapper.findAll('.mode')
    expect(buttons).toHaveLength(3)
    expect(buttons.map((b) => b.attributes('data-mode'))).toEqual([
      'volume',
      'recovering',
      'neglected',
    ])
    expect(buttons.map((b) => b.text())).toEqual(['本周组数', '恢复中', '久未训练'])

    // 点哪个按钮，传给 <BodyMap /> 的 mode 就变成哪个（不是只管按钮自己的高亮）
    for (const mode of ['recovering', 'neglected', 'volume'] as const) {
      await wrapper.find(`.mode[data-mode="${mode}"]`).trigger('click')
      expect(wrapper.findComponent(BodyMap).props('mode'), mode).toBe(mode)
      expect(wrapper.find('.body-map').attributes('data-mode'), mode).toBe(mode)
    }
  })
})

describe('人体图页 · 选中一块肌肉', () => {
  it('clicking_a_muscle_opens_the_side_panel_with_its_weekly_numbers', async () => {
    const wrapper = await mountView()

    // 没点之前面板是引导态，不是空白
    expect(wrapper.findComponent(BodyMapSidePanel).find('.guide').exists()).toBe(true)

    await wrapper.find('[data-region-id="chest-upper-left"]').trigger('click')
    await flushPromises()

    const panel = wrapper.findComponent(BodyMapSidePanel)
    // 面板「打开」= 引导态换成这块肌肉的详情
    expect(panel.find('.guide').exists()).toBe(false)
    expect(panel.find('.panel-title').text()).toBe('胸部')
    expect(panel.find('[data-fact="sets"] dd').text()).toBe('3 组')
    // 建议区间用 en dash，与 volume-status.ts 的 targetText 一致
    expect(panel.find('[data-fact="target"] dd').text()).toBe('10–20')
    expect(panel.find('[data-fact="status"] dd').text()).toBe('低于参考区间')
  })

  it('the_panel_lists_the_exercises_that_target_the_clicked_muscle_group', async () => {
    const wrapper = await mountView()

    await wrapper.find('[data-region-id="chest-upper-left"]').trigger('click')
    await flushPromises()

    // 走已有的 `/api/exercises`（训练记录页那套），不新造端点
    const searches = reqs().filter((r) => r.path.startsWith('/api/exercises'))
    expect(searches).toHaveLength(1)
    expect(searches[0].method).toBe('GET')
    expect(searches[0].path).toBe('/api/exercises?muscleGroupId=1')

    const panel = wrapper.findComponent(BodyMapSidePanel)
    const names = panel.findAll('.exercise').map((li) => li.text())
    expect(names).toEqual(['杠铃卧推', 'ZZ Body Map Dumbbell Press'])
    // 空列表态不该同时出现
    expect(panel.find('.empty').exists()).toBe(false)
  })

  it('an_unselected_panel_is_guidance_not_a_blank_column', async () => {
    const wrapper = await mountView()

    const panel = wrapper.findComponent(BodyMapSidePanel)
    expect(panel.text()).not.toBe('')
    expect(panel.find('.guide').text()).toContain('点人体图')
    // 没选肌肉就不该请求动作列表
    expect(reqs().some((r) => r.path.startsWith('/api/exercises'))).toBe(false)
  })

  it('a_second_click_switches_the_panel_to_the_new_muscle', async () => {
    const wrapper = await mountView()

    await wrapper.find('[data-region-id="chest-upper-left"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-region-id="triceps-long-left"]').trigger('click')
    await flushPromises()

    const panel = wrapper.findComponent(BodyMapSidePanel)
    expect(panel.find('.panel-title').text()).toBe('肱三头肌')
    // 每次换肌群都重新问一次动作列表，而不是沿用上一块的结果
    const searches = reqs().filter((r) => r.path.startsWith('/api/exercises'))
    expect(searches.map((r) => r.path)).toEqual([
      '/api/exercises?muscleGroupId=1',
      '/api/exercises?muscleGroupId=9',
    ])
  })
})

describe('人体图页 · 加载中与失败', () => {
  it('shows_a_loading_line_when_the_volume_is_not_there_yet', () => {
    const wrapper = mount(BodyMapView)

    // 挂载瞬间还没有数据：给人一句话，不是一片空白
    expect(wrapper.find('.dim').text()).toContain('正在读取本周容量')
    expect(wrapper.findComponent(BodyMap).exists()).toBe(false)
    expect(wrapper.find('.err').exists()).toBe(false)
  })

  it('a_failed_load_shows_the_reason_and_keeps_the_page_title', async () => {
    bodyMapVolumeMock.mockRejectedValueOnce(new Error('连不上后端 127.0.0.1:1：拒绝连接'))

    const wrapper = await mountView()

    expect(wrapper.find('.err').text()).toContain('连不上后端 127.0.0.1:1')
    expect(wrapper.find('h2').text()).toBe('人体图')
    expect(wrapper.findComponent(BodyMap).exists()).toBe(false)
    // 数据没到就画不出一张「全灰」的图——那会被读成「本周一块都没练」
    expect(wrapper.findAll('[data-region-id]')).toHaveLength(0)
  })

  it('an_exercise_search_failure_is_shown_in_the_panel_not_swallowed', async () => {
    const wrapper = await mountView()
    invokeMock.mockImplementationOnce(async () => {
      throw new Error('读动作列表失败')
    })

    await wrapper.find('[data-region-id="chest-upper-left"]').trigger('click')
    await flushPromises()

    const panel = wrapper.findComponent(BodyMapSidePanel)
    expect(panel.find('.err').text()).toContain('读动作列表失败')
    // 肌群本身的数字还在：动作列表失败不该把上半截也抹掉
    expect(panel.find('.panel-title').text()).toBe('胸部')
  })
})

describe('人体图页 · 只看「现在」', () => {
  it('bodyMapVolume_is_called_without_a_date_because_this_page_only_shows_now', async () => {
    await mountView()

    expect(bodyMapVolumeMock).toHaveBeenCalledTimes(1)
    // A 的硬要求：第一个参数是 undefined（压根没传，或显式传 undefined 都行）。
    // 传 '2026-10-19' 这类日期会让 daysSince 变成负数——组件虽有兜底，
    // 但「这一页只看现在」这个决定就没人知道了。
    const [firstArg] = bodyMapVolumeMock.mock.calls.at(-1) ?? []
    expect(firstArg).toBeUndefined()

    // 万一日后真的拼了 ?date=…，这条会先红
    for (const req of reqs()) {
      expect(req.path).not.toContain('date=')
    }
  })
})

describe('人体图页 · 视觉 token', () => {
  it('colors_come_from_theme_tokens_only', () => {
    // 设计文档「九」：theme.css 是唯一颜色来源。与 BodyDataView / ProgressView 同一条 ?raw 断言。
    expect(viewSource).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(viewSource).not.toMatch(/\brgba?\s*\(/i)
    expect(viewSource).not.toMatch(/\bhsla?\s*\(/i)

    const tokenUses = viewSource.match(/var\(--[a-z0-9-]+\)/g) ?? []
    expect(tokenUses.length).toBeGreaterThanOrEqual(15)
  })
})
