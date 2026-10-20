import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ExerciseDetailPanel from './ExerciseDetailPanel.vue'
// 用 `?raw` 读源码而不是 node:fs：jsdom 下 Vite 会把 node:fs 换成空壳
// （见 styles/theme.spec.ts 顶部那段注释），而 `?raw` 在构建/测试两个环境都只是字符串。
import panelSource from './ExerciseDetailPanel.vue?raw'
import type { ExerciseDetail } from '../api/exercise-detail'

const NAME_ZH = '中握距杠铃卧推'
const NAME_EN = 'Barbell Bench Press - Medium Grip'

/**
 * 五段中文做法。**段数就是步骤号**：一个 `\n` 一步。
 *
 * 刻意用「第一段…第五段」这种可区分的文案，而不是五句真做法——
 * 这样「第 4 步的序号下写着第 5 步」这种没人会报告的错位能被逐项断言抓住。
 */
const FIVE_STEPS = ['第一段', '第二段', '第三段', '第四段', '第五段']

function detail(overrides: Partial<ExerciseDetail> = {}): ExerciseDetail {
  return {
    id: 44,
    nameEn: NAME_EN,
    nameZh: NAME_ZH,
    equipment: 'barbell',
    isCompound: true,
    category: 'strength',
    instructions: 'Lie back on a flat bench.\nPress the bar back up.',
    instructionsZh: FIVE_STEPS.join('\n'),
    muscles: [
      { muscleGroupId: 1, nameZh: '胸部', nameEn: 'chest', role: 'primary' },
      { muscleGroupId: 7, nameZh: '肩部', nameEn: 'shoulders', role: 'secondary' },
      { muscleGroupId: 9, nameZh: '肱三头肌', nameEn: 'triceps', role: 'secondary' },
    ],
    ...overrides,
  }
}

interface PanelProps {
  detail: ExerciseDetail | null
  loading?: boolean
  error?: string | null
}

function mountPanel(overrides: Partial<PanelProps> = {}) {
  return mount(ExerciseDetailPanel, {
    props: { detail: detail(), ...overrides },
  })
}

const BILIBILI_URL = 'https://search.bilibili.com/all?keyword=' + encodeURIComponent(NAME_ZH)
const YOUTUBE_URL =
  'https://www.youtube.com/results?search_query=' + encodeURIComponent(NAME_EN + ' exercise form')

/**
 * 覆盖 `navigator.clipboard`。
 *
 * jsdom 没有实现 Clipboard API（默认就是 undefined），所以这里既能模拟可用，
 * 也能显式把它设回 undefined——降级路径必须真的走到，不能只写在注释里。
 */
function setClipboard(value: unknown) {
  Object.defineProperty(window.navigator, 'clipboard', {
    value,
    configurable: true,
    writable: true,
  })
}

beforeEach(() => {
  setClipboard(undefined)
})

describe('动作详情面板 · 基本信息', () => {
  it('shows_the_chinese_and_english_names_equipment_category_and_compound_flag', () => {
    const wrapper = mountPanel()

    expect(wrapper.find('.panel-title').text()).toBe(NAME_ZH)
    expect(wrapper.find('[data-fact="nameZh"] dd').text()).toBe(NAME_ZH)
    expect(wrapper.find('[data-fact="nameEn"] dd').text()).toBe(NAME_EN)
    expect(wrapper.find('[data-fact="equipment"] dd').text()).toBe('barbell')
    expect(wrapper.find('[data-fact="category"] dd').text()).toBe('strength')
    expect(wrapper.find('[data-fact="type"] dd').text()).toBe('复合动作')

    // 孤立动作是另一句话，不是把「复合」两个字删掉留个空
    const isolated = mountPanel({ detail: detail({ isCompound: false }) })
    expect(isolated.find('[data-fact="type"] dd').text()).toBe('孤立动作')
  })

  it('a_missing_chinese_name_falls_back_to_the_english_name', () => {
    // 手工录入的动作可能没有中文名（nameZh 为 null），标题不能是空白。
    const wrapper = mountPanel({ detail: detail({ nameZh: null }) })
    expect(wrapper.find('.panel-title').text()).toBe(NAME_EN)
    expect(wrapper.find('[data-fact="nameZh"] dd').text()).toBe('未录入')
  })
})

describe('动作详情面板 · 目标肌群', () => {
  it('splits_muscles_into_primary_and_secondary_groups', () => {
    const wrapper = mountPanel()

    const primary = wrapper.findAll('.muscle-group-primary .muscle')
    expect(primary).toHaveLength(1)
    expect(primary[0].text()).toContain('胸部')

    const secondary = wrapper.findAll('.muscle-group-secondary .muscle')
    expect(secondary).toHaveLength(2)
    expect(secondary[0].text()).toContain('肩部')
    expect(secondary[1].text()).toContain('肱三头肌')

    // 分区是真的分区：主要肌群不该出现在次要那一块里
    expect(wrapper.find('.muscle-group-secondary').text()).not.toContain('胸部')
  })

  it('does_not_render_an_empty_primary_section_when_no_muscle_is_tagged', () => {
    // 手工动作可能一条肌群标注都没有（muscles: []）。空的「主要肌群」小节
    // 会让人以为数据坏了，要么整块隐藏，要么给一句话。
    const wrapper = mountPanel({ detail: detail({ muscles: [] }) })

    expect(wrapper.find('.muscle-group-primary').exists()).toBe(false)
    expect(wrapper.find('.muscle-group-secondary').exists()).toBe(false)
    expect(wrapper.find('.no-muscles').text()).toBe('未标注肌群')
  })
})

describe('动作详情面板 · 分步做法', () => {
  it('renders_one_ordered_list_item_per_paragraph_so_step_numbers_match_the_text', () => {
    // **本任务最要紧的一条**：一个 `\n` 一步。5 段 → 5 个 <li>，
    // 且第 3 个 <li> 的文字就是第 3 段。
    //
    // 为什么逐项钉死：如果段落被错误合并或拆分，界面上仍然是「1、2、3、4」，
    // 只是第 4 步的序号下写着第 5 步的内容——没有任何人会报告这个 bug。
    const wrapper = mountPanel()

    const list = wrapper.find('ol.steps[data-lang="zh"]')
    expect(list.exists()).toBe(true)

    const items = list.findAll('li')
    expect(items).toHaveLength(FIVE_STEPS.length)
    expect(items[2].text()).toBe(FIVE_STEPS[2])
    expect(items.map((item) => item.text())).toEqual(FIVE_STEPS)

    // 有中文就不该再渲染英文
    expect(wrapper.find('ol.steps[data-lang="en"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Lie back on a flat bench.')
  })

  it('falls_back_to_english_with_a_note_when_chinese_is_missing', () => {
    const wrapper = mountPanel({ detail: detail({ instructionsZh: null }) })

    expect(wrapper.find('ol.steps[data-lang="zh"]').exists()).toBe(false)

    const english = wrapper.find('ol.steps[data-lang="en"]')
    expect(english.exists()).toBe(true)
    expect(english.findAll('li')).toHaveLength(2)
    expect(english.findAll('li')[0].text()).toBe('Lie back on a flat bench.')
    expect(wrapper.find('.no-zh').text()).toContain('这条还没有中文说明')
  })

  it('renders_chinese_even_when_the_english_source_is_null', () => {
    // 真实数据里中文是从英文翻的，但这个组合不假设「有中文就一定有英文」。
    const wrapper = mountPanel({ detail: detail({ instructions: null }) })

    expect(wrapper.findAll('ol.steps[data-lang="zh"] li')).toHaveLength(FIVE_STEPS.length)
    expect(wrapper.find('.no-zh').exists()).toBe(false)
    expect(wrapper.find('.no-instructions').exists()).toBe(false)
  })

  it('says_there_is_no_instruction_instead_of_rendering_blank', () => {
    // 那 5 条原文为空的动作、以及没写做法的手工动作：两个都是 null。
    const wrapper = mountPanel({ detail: detail({ instructions: null, instructionsZh: null }) })

    expect(wrapper.find('ol').exists()).toBe(false)
    expect(wrapper.find('.no-instructions').text()).toBe('这条没有做法说明')
  })

  it('does_not_treat_a_blank_string_as_a_real_instruction', () => {
    // 空串与 null 在界面上是同一个事实：没有做法。不能渲染一个空的列表。
    const wrapper = mountPanel({ detail: detail({ instructions: '   ', instructionsZh: '\n  \n' }) })

    expect(wrapper.find('ol').exists()).toBe(false)
    expect(wrapper.find('.no-instructions').exists()).toBe(true)
  })
})

describe('动作详情面板 · 搜索链接', () => {
  it('offers_https_urls_with_both_names_and_never_an_anchor_tag', () => {
    const wrapper = mountPanel()

    // 硬约束：Tauri 的 WebView 里点 <a href> 会把整个应用导航走（页面变空白），
    // 所以这些地址只能是可以选中复制的文本，不能是链接。
    expect(wrapper.findAll('a')).toHaveLength(0)

    const links = wrapper.findAll('.search-link')
    expect(links).toHaveLength(2)

    const bilibili = links[0]
    expect(bilibili.find('.link-label').text()).toContain(NAME_ZH)
    expect(bilibili.find('code.url').text()).toBe(BILIBILI_URL)
    expect(bilibili.find('code.url').text().startsWith('https://')).toBe(true)

    const youtube = links[1]
    expect(youtube.find('.link-label').text()).toContain(NAME_EN)
    expect(youtube.find('code.url').text()).toBe(YOUTUBE_URL)
    expect(youtube.find('code.url').text().startsWith('https://')).toBe(true)
  })

  it('falls_back_to_the_english_name_for_the_bilibili_search_when_nameZh_is_null', () => {
    const wrapper = mountPanel({ detail: detail({ nameZh: null }) })
    const label = wrapper.find('.search-link').find('.link-label').text()
    expect(label).toContain(NAME_EN)
  })
})

describe('动作详情面板 · 复制', () => {
  it('copies_the_url_with_navigator_clipboard_and_confirms', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })

    const wrapper = mountPanel()
    await wrapper.find('[data-copy="bilibili"]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText).toHaveBeenCalledWith(BILIBILI_URL)
    expect(wrapper.find('.copied').text()).toContain('已复制')
    expect(wrapper.find('.copy-fallback').exists()).toBe(false)
  })

  it('shows_a_selectable_url_when_clipboard_is_unavailable_instead_of_failing_silently', async () => {
    // 静默失败是最差的结果：用户点了复制、什么都没发生、他不知道该怪谁。
    setClipboard(undefined)

    const wrapper = mountPanel()
    await wrapper.find('[data-copy="bilibili"]').trigger('click')
    await flushPromises()

    const fallback = wrapper.find('.copy-fallback')
    expect(fallback.exists()).toBe(true)
    expect(fallback.text()).toContain('手动')
    expect((fallback.find('input.fallback-url').element as HTMLInputElement).value).toBe(
      BILIBILI_URL,
    )
    expect(wrapper.find('.copied').exists()).toBe(false)
  })

  it('falls_back_when_write_text_rejects', async () => {
    // 有的安全上下文里 clipboard 存在但写入被拒（没有用户手势、权限被拒）。
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })

    const wrapper = mountPanel()
    await wrapper.find('[data-copy="youtube"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('.copy-fallback').exists()).toBe(true)
    expect((wrapper.find('input.fallback-url').element as HTMLInputElement).value).toBe(
      YOUTUBE_URL,
    )
  })
})

describe('动作详情面板 · 加载中 / 失败 / 关闭', () => {
  it('shows_a_loading_line_while_the_detail_is_still_being_fetched', () => {
    const wrapper = mountPanel({ detail: null, loading: true })

    expect(wrapper.find('.loading').text()).toContain('正在取做法')
    expect(wrapper.find('ol').exists()).toBe(false)
    expect(wrapper.find('.detail-error').exists()).toBe(false)
  })

  it('shows_the_backend_message_when_the_detail_request_failed', () => {
    const wrapper = mountPanel({ detail: null, error: '找不到这个动作: 99999999' })

    expect(wrapper.find('.detail-error').text()).toBe('找不到这个动作: 99999999')
    expect(wrapper.find('.detail-error').text()).not.toContain('加载失败')
    expect(wrapper.find('ol').exists()).toBe(false)
  })

  it('emits_close_from_the_close_button_and_the_backdrop', async () => {
    const wrapper = mountPanel()

    await wrapper.find('.close').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)

    await wrapper.find('.backdrop').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(2)
  })
})

describe('动作详情面板 · 视觉 token', () => {
  it('colors_come_from_theme_tokens_only', () => {
    // 设计文档「九」：theme.css 是唯一颜色来源。与 BodyDataView / ProgressView
    // 同一条 ?raw 断言——谁写死一个 #ffffff 或 rgb(...)，这条就红。
    expect(panelSource).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(panelSource).not.toMatch(/\brgba?\s*\(/i)
    expect(panelSource).not.toMatch(/\bhsla?\s*\(/i)

    const tokenUses = panelSource.match(/var\(--[a-z0-9-]+\)/g) ?? []
    expect(tokenUses.length).toBeGreaterThan(0)
  })
})
