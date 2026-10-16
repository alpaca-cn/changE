import { invoke } from '@tauri-apps/api/core'

/** 与 Rust 侧 `BackendState` 对应。 */
export interface BackendState {
  status: 'starting' | 'ready' | 'failed'
  port: number | null
  message: string
}

export interface Health {
  status: string
  /** 形如「缓存：已连接 127.0.0.1:6399」或「缓存：未启用（配置为关闭）」。 */
  cache: string
}

export type Tone = 'loading' | 'ok' | 'error'

/**
 * 向后端要状态。
 *
 * **端口只能来自这里**——它是随机分配的，前端不可能知道，
 * 任何硬编码端口都会在外壳换成另一个端口时失效。
 */
export function getBackendState(): Promise<BackendState> {
  return invoke<BackendState>('get_backend_status')
}

/** 把状态翻译成给人看的一句话 + 一个色调。 */
export function describeState(s: BackendState): { text: string; tone: Tone } {
  switch (s.status) {
    case 'starting':
      return { text: '正在启动后端…', tone: 'loading' }
    case 'ready':
      return { text: `后端已连接 · 端口 ${s.port}`, tone: 'ok' }
    case 'failed':
      return { text: s.message || '后端启动失败', tone: 'error' }
    default:
      // 到不了这里，除非 Rust 侧新增了一个 BackendStatus 变体而忘了同步这里。
      // 那时旧的写法会返回 undefined → 状态栏渲染成空白、且没有任何提示，
      // 是典型的静默失效。这里宁可显示一句「看不懂」也要让人一眼看见。
      return {
        text: `未知的后端状态：${String((s as { status: unknown }).status)}`,
        tone: 'error',
      }
  }
}

/** 查后端健康端点。 */
export async function checkHealth(port: number): Promise<Health> {
  const res = await fetch(`http://127.0.0.1:${port}/api/health`)
  if (!res.ok) {
    throw new Error(`后端返回 ${res.status}`)
  }
  return (await res.json()) as Health
}
