/**
 * 大模型服务商预设。
 *
 * **为什么要这个**：换模型时用户得自己记住「接口地址 + 型号名」，而这两样
 * 各家都不一样（百炼是 `/compatible-mode/v1`，智谱是 `/paas/v4`，Kimi 是 `/v1`）。
 * 记错一个字母就是一条 404，而界面只会说「请求失败」。
 *
 * **`vision` 这一栏是真的会影响功能的**：拍照记饮食要把图片放进请求里，
 * 不支持图片的型号会在这一条上一直失败。所以不支持的要**明说**，不能只是
 * 默认值不同。
 *
 * 型号名会变（服务商会下线旧型号）。这里给的是「写这份代码时各家能用的默认值」，
 * 填错了改成自己那家当前在售的型号即可 —— 这个下拉只是省掉记地址的麻烦。
 */
export interface LlmProvider {
  id: string
  label: string
  baseUrl: string
  model: string
  /** 这个预设的默认型号是否接受图片输入。 */
  vision: boolean
  note: string
}

export const LLM_PROVIDERS: LlmProvider[] = [
  {
    id: 'qwen',
    label: '通义千问（阿里云百炼）',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    // 2026-10-25 实测：qwen-vl-max 已下线（返回 Model not exist）。
    // 这里的默认值只是**起点** —— 百炼的型号上下线很频繁，
    // 真正的权威是控制台「模型广场」里的 Model ID 原文。
    model: 'qwen3-vl-plus',
    vision: true,
    note: '视觉与工具调用都支持。型号名以百炼控制台的 Model ID 为准'
      + '（qwen-vl-max 这类旧型号已下线）。',
  },
  {
    id: 'zhipu',
    label: '智谱 GLM',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    model: 'glm-4v-plus',
    vision: true,
    note: '国内直连，视觉强。拍照记饮食可以用。',
  },
  {
    id: 'kimi',
    label: 'Kimi（月之暗面）',
    baseUrl: 'https://api.moonshot.cn/v1',
    model: 'moonshot-v1-8k-vision-preview',
    vision: true,
    note: '文字强。**拍照要选带 vision 的型号**，纯文字型号不吃图片。',
  },
  {
    id: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    model: 'deepseek-chat',
    vision: false,
    note: '便宜、文字与工具调用好，**但 API 不接受图片** —— 拍照记饮食会一直失败。',
  },
  {
    id: 'openai',
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    model: 'gpt-4o-mini',
    vision: true,
    note: '国内需要能连外网。',
  },
  {
    id: 'custom',
    label: '自定义',
    baseUrl: '',
    model: '',
    vision: true,
    note: '自己填接口地址与型号。兼容 OpenAI 协议的服务都能用。',
  },
]

/** 按 id 找预设；找不到返回 undefined（用户在「自定义」里改过地址就会这样）。 */
export function providerById(id: string): LlmProvider | undefined {
  return LLM_PROVIDERS.find((p) => p.id === id)
}
