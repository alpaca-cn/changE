import { invoke } from '@tauri-apps/api/core'

/** 与 Rust 侧 `BackendState` 对应。 */
export interface BackendState {
  status: 'starting' | 'ready' | 'failed'
  port: number | null
  message: string
}

/** `/api/health` 的响应，与 Rust 侧 `http::Health` 对应。
 *
 * **两处手写定义，靠契约测试钉住**：Rust 侧的
 * `the_health_struct_serializes_with_the_field_names_the_frontend_reads`
 * 与这里的 `Rust 真的序列化出来的 Health JSON 字段名，前端能读出来`
 * 用的是同一段 JSON 字符串。改字段名要同时改两边，否则一侧会红。
 */
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

/**
 * 查后端健康端点。
 *
 * **不发 HTTP，也没有端口参数。**
 *
 * 旧版是 `checkHealth(port)` + `fetch('http://127.0.0.1:' + port + '/api/health')`，
 * 真机验收时它稳定失败：页面的源（dev 是 `http://127.0.0.1:1420`，打包后是
 * `tauri://localhost`）与后端的**随机端口**不同源，响应里没有
 * `Access-Control-Allow-Origin`，浏览器把响应拦掉，前端只拿到
 * `TypeError: Failed to fetch`。
 *
 * 两个概念要分清：**CSP 与 CORS 是两道独立且都要过的门**。CSP 管「页面能不能发
 * 这个请求」，CORS 管「浏览器肯不肯把响应交给脚本」。`tauri.conf.json` 里给
 * `connect-src` 放行了 `http://127.0.0.1:*`，只过了第一道。
 *
 * 现在这条请求由外壳（Rust 的 `get_health` 命令）去发——它不经浏览器，
 * 天生不在同源策略的管辖范围内。所以：
 * - 端口**留在 Rust 侧**，前端不再经手（签名因此一个参数都不收）；
 * - 前端不再需要 `connect-src` 的通配，CSP 收紧了；
 * - 失败走 invoke 的 reject：Rust 侧把「连不上 / 超时 / 非 2xx / JSON 畸形」
 *   分成四句不同的话，调用方原样显示即可。
 */
export function checkHealth(): Promise<Health> {
  return invoke<Health>('get_health')
}
