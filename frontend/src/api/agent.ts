import { callApi } from './training'

/**
 * 智能体域的 API 客户端。
 *
 * **注意这个文件里没有 apiKey。** 后端的 `GET/PUT /api/agent/config` 只认
 * `configured / baseUrl / model` 三个字段回传，key 只在**发出去**的方向存在
 * （`AgentConfigInput`），从不读回来。所以前端也没有承载它的状态——
 * 用户填一次，写进后端的 `agent.json`，之后就只在请求头里出现。
 */
export interface AgentConfig {
  configured: boolean
  baseUrl: string
  model: string
}

/** 写配置时传的字段。三个都可省略：省略 = 这项不动（apiKey 传空串 = 清除）。 */
export interface AgentConfigInput {
  baseUrl?: string
  model?: string
  apiKey?: string
}

/** 一轮对话的结果。`tools` 是本轮真正执行过的工具名，顺序即执行顺序。 */
export interface AgentChatResult {
  reply: string
  tools: string[]
  sessionId: number
}

/** 一条历史消息。`tool_calls` 是 JSON 数组字符串（后端存的就是字符串）。 */
export interface AgentMessage {
  role: 'user' | 'assistant' | 'tool'
  content: string | null
  tool_calls: string | null
  created_at: string
}

export function getAgentConfig(): Promise<AgentConfig> {
  return callApi('GET', '/api/agent/config') as Promise<AgentConfig>
}

export function putAgentConfig(input: AgentConfigInput): Promise<AgentConfig> {
  return callApi('POST', '/api/agent/config', input) as Promise<AgentConfig>
}

/**
 * 一轮对话。`imageDataUrl` 可选，必须是 `data:image/...;base64,...`
 * （由 `lib/image.ts` 压好再传）。
 */
/**
 * 一次对话的等待上限。
 *
 * 智能体内部要跑「模型 → 工具 → 模型」，最多 6 轮工具调用。实测中等任务
 * 10-40 秒，给到 180 秒（外壳的上限也是 180 秒，超出的会被夹掉）。
 */
/**
 * 服务商当前可用的模型列表。
 *
 * **为什么不让人手打型号**：型号会下线。2026-10-25 用户先后填了 qwen-vl-max
 * （已下线）和 Qwen3.8-Flash（不存在），两次都只得到一句 model_not_found。
 * 让界面把服务商**真实存在的 id** 拉回来选，就不会再撞这个墙。
 */
export function listModels(): Promise<string[]> {
  return callApi('GET', '/api/agent/models', undefined, 30_000) as Promise<string[]>
}

const CHAT_TIMEOUT_MS = 180_000

export function chat(message: string, imageDataUrl?: string): Promise<AgentChatResult> {
  return callApi(
    'POST',
    '/api/agent/chat',
    { message, imageDataUrl },
    CHAT_TIMEOUT_MS,
  ) as Promise<AgentChatResult>
}

export function history(): Promise<AgentMessage[]> {
  return callApi('GET', '/api/agent/history') as Promise<AgentMessage[]>
}

/** 回退智能体最近一次改动。没有可退的改动时后端回 400 + {error}。 */
export function undo(): Promise<{ message: string }> {
  return callApi('POST', '/api/agent/undo') as Promise<{ message: string }>
}
