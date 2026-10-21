import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

import AgentView from './AgentView.vue'
import SettingsView from './SettingsView.vue'

type Reply = { status: number; body: unknown } | 'reject'

/** 按 path 分派。没准备的 path 直接抛错——比返回空对象更容易发现测试写错了。 */
function dispatch(map: Record<string, Reply>) {
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (_cmd: unknown, args: unknown) => {
    const path = (args as { path: string }).path
    const key = Object.keys(map).find((k) => path.startsWith(k))
    if (key === undefined) throw new Error(`测试没有为 ${path} 准备应答`)
    const r = map[key]
    if (r === 'reject') throw new Error('连不上后端 127.0.0.1:1：拒绝连接')
    return { status: r.status, body: typeof r.body === 'string' ? r.body : JSON.stringify(r.body) }
  })
}

/** 某次 invoke 调用用的 method（写在 args 里，不是 cmd 名）。 */
function methodsCalled(): string[] {
  return invokeMock.mock.calls.map((c) => (c[1] as { method: string }).method)
}

const NOT_CONFIGURED = { configured: false, baseUrl: '', model: '' }
const CONFIGURED = { configured: true, baseUrl: 'https://a/v1', model: 'deepseek-chat' }

async function mountAt(component: unknown) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/agent', component: { template: '<div/>' } },
      { path: '/settings', component: { template: '<div/>' } },
      { path: '/x', component: component as never },
    ],
  })
  router.push('/x')
  await router.isReady()
  const w = mount(component as never, { global: { plugins: [router] } })
  await flushPromises()
  return { w, router }
}

beforeEach(() => {
  invokeMock.mockReset()
})

describe('智能体接口契约', () => {
  it('配置用 GET，保存用 POST（外壳只放行 GET/POST，PUT 发不出去）', async () => {
    dispatch({
      '/api/agent/config': { status: 200, body: CONFIGURED },
    })
    const { getAgentConfig, putAgentConfig } = await import('../api/agent')
    await getAgentConfig()
    await putAgentConfig({ model: 'm' })

    // 这条守的是一个真实的坑：src-tauri/src/http.rs 的白名单只允许 GET/POST，
    // 用 PUT 写配置的话前端根本发不出去，而且报错是「只允许 GET / POST」——
    // 看起来像外壳的 bug，实际是我们选错了动词。
    expect(methodsCalled()).toEqual(['GET', 'POST'])
  })

  it('会话与撤销都用 POST', async () => {
    dispatch({
      '/api/agent/chat': { status: 200, body: { reply: '好', tools: [], sessionId: 1 } },
      '/api/agent/undo': { status: 200, body: { message: '已回退' } },
    })
    const { chat, undo } = await import('../api/agent')
    await chat('你好')
    await undo()
    expect(methodsCalled()).toEqual(['POST', 'POST'])
  })
})

describe('智能体对话页', () => {
  it('没配模型时不给输入框，只给一个去设置的入口', async () => {
    // 让用户敲完一句话再告诉他不能用，是最烦的失败方式
    dispatch({ '/api/agent/config': { status: 200, body: NOT_CONFIGURED } })
    const { w } = await mountAt(AgentView)

    expect(w.find('.inbox').exists()).toBe(false)
    expect(w.text()).toContain('还没有配置模型')
    expect(w.text()).toContain('去设置')
  })

  it('点「去设置」跳到设置页', async () => {
    dispatch({ '/api/agent/config': { status: 200, body: NOT_CONFIGURED } })
    const { w, router } = await mountAt(AgentView)

    await w.find('.primary').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings')
  })

  it('配好之后发一句话，回答与调用过的工具都上屏', async () => {
    dispatch({
      '/api/agent/config': { status: 200, body: CONFIGURED },
      '/api/agent/history': { status: 200, body: [] },
      '/api/agent/chat': {
        status: 200,
        body: { reply: '记好了', tools: ['search_exercises', 'record_workout'], sessionId: 1 },
      },
    })
    const { w } = await mountAt(AgentView)

    await w.find('.inbox input[type="text"]').setValue('今天卧推 60 公斤 3 组')
    await w.find('.inbox .primary').trigger('click')
    await flushPromises()

    const text = w.find('.chat').text()
    expect(text).toContain('今天卧推 60 公斤 3 组')   // 用户那句
    expect(text).toContain('记好了')                  // 回答
    // 用户有权知道这一句话到底动了什么
    expect(text).toContain('search_exercises')
    expect(text).toContain('record_workout')
  })

  it('失败时把原因显示出来，且不留下半条假回答', async () => {
    dispatch({
      '/api/agent/config': { status: 200, body: CONFIGURED },
      '/api/agent/history': { status: 200, body: [] },
      '/api/agent/chat': 'reject',
    })
    const { w } = await mountAt(AgentView)

    await w.find('.inbox input[type="text"]').setValue('你好')
    await w.find('.inbox .primary').trigger('click')
    await flushPromises()

    expect(w.text()).toContain('连不上')
    // 用户那句留着（他要知道自己发过什么），但**不能**多出一个空的助手气泡
    expect(w.findAll('.msg.ai')).toHaveLength(0)
  })

  it('撤销上一步把后端那句话显示出来', async () => {
    dispatch({
      '/api/agent/config': { status: 200, body: CONFIGURED },
      '/api/agent/history': { status: 200, body: [] },
      '/api/agent/undo': { status: 200, body: { message: '已回退：撤销了对 body_metric 的一次改动。' } },
    })
    const { w } = await mountAt(AgentView)

    await w.find('.undo').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('已回退')
  })
})

describe('设置页的模型配置', () => {
  it('留空的 key 不会被当成「清除」发出去', async () => {
    dispatch({ '/api/agent/config': { status: 200, body: CONFIGURED } })
    const { w } = await mountAt(SettingsView)

    // 打开时 baseUrl 与 model 被回填，key 永远是空的（后端不回传它）
    const inputs = w.findAll('input')
    expect((inputs[0].element as HTMLInputElement).value).toBe('https://a/v1')
    expect((inputs[2].element as HTMLInputElement).value).toBe('')

    // 只改模型名
    await inputs[1].setValue('deepseek-reasoner')
    await w.find('.primary').trigger('click')
    await flushPromises()

    const call = invokeMock.mock.calls.find(
      (c) => (c[1] as { method: string }).method === 'POST',
    )
    const body = JSON.parse((call![1] as { body: string }).body)

    // 回填过的 baseUrl 一起发出去是**无害的**：它写回同一个值，是幂等的。
    expect(body.baseUrl).toBe('https://a/v1')
    expect(body.model).toBe('deepseek-reasoner')

    // **真正要守的是这一条**：key 留空时不能出现在请求体里。
    // 后端把 apiKey 的空串当「清除」、把缺省当「不动」——发一个 '' 出去
    // 会把用户已经存好的 key 悄悄抹掉，而他只是想改模型名。
    expect(body).not.toHaveProperty('apiKey')
  })

  it('什么都没填时保存按钮是禁用的', async () => {
    dispatch({ '/api/agent/config': { status: 200, body: NOT_CONFIGURED } })
    const { w } = await mountAt(SettingsView)
    expect(w.find('.primary').attributes('disabled')).toBeDefined()
  })

  it('已配置时提示不再显示 key，并说明留空表示不改', async () => {
    dispatch({ '/api/agent/config': { status: 200, body: CONFIGURED } })
    const { w } = await mountAt(SettingsView)
    expect(w.text()).toContain('已配置')
    expect(w.find('input[type="password"]').attributes('placeholder')).toContain('留空表示不改')
  })
})
