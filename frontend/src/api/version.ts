import { callApi } from './training'

/**
 * 版本与更新检查。
 *
 * 三个值都在**构建期**由 `vite.config.ts` 的 `define` 钉死：
 * `__APP_VERSION__`（来自 package.json）、`__BUILD_TIME__`、`__UPDATE_MANIFEST__`。
 * 运行期去问 Tauri 要版本需要额外插件，而这两个值本来就只对「这份产物」有意义。
 */

export const APP_VERSION: string = __APP_VERSION__
export const BUILD_TIME: string = __BUILD_TIME__
/** 空串 = 这个发行版没有配置更新源。 */
export const UPDATE_MANIFEST: string = __UPDATE_MANIFEST__

/** 更新清单。字段都可选，只有 version 是必需的。 */
export interface UpdateManifest {
  version: string
  notes?: string
  url?: string
}

export type UpdateCheck =
  | { kind: 'no-source' }
  | { kind: 'up-to-date'; current: string }
  | { kind: 'available'; current: string; latest: string; notes: string; url: string }
  | { kind: 'failed'; message: string }

/**
 * 版本比较：`candidate` 是否比 `current` 新。
 *
 * 只按数字段比（`1.10.0 > 1.9.0`，这是字符串比较会搞错的地方）。
 * **不处理预发布后缀**（`1.0.0-beta.1`）——这个项目不发预发布版，为一行没人走的分支
 * 写解析只会多一个测不到的模块。真要用时再补，别假装支持。
 *
 * 任意一边格式坏掉时返回 false：不知道谁新的时候，**不提示更新** —
 * 提示了才去下，下错了更麻烦。
 */
export function isNewer(candidate: string, current: string): boolean {
  const a = candidate.trim().split('.').map(Number)
  const b = current.trim().split('.').map(Number)
  if (a.some((n) => !Number.isFinite(n)) || b.some((n) => !Number.isFinite(n))) return false
  const len = Math.max(a.length, b.length)
  for (let i = 0; i < len; i++) {
    const x = a[i] ?? 0
    const y = b[i] ?? 0
    if (x !== y) return x > y
  }
  return false
}

/** 构建时间显示成 `2026-10-21 18:54`（本地时区）。坏值原样返回，不抛。 */
export function buildTimeText(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 检查更新。
 *
 * 网络请求**由后端发**（`GET /api/version/manifest`），不是这里 fetch——
 * 外壳的 CSP 是 `connect-src 'self'`，WebView 里 fetch 任何外部地址都会被直接拒绝。
 * 为一个更新检查去放宽整台机器的出网权限不划算，而后端本来就是应用的网络客户端。
 */
export async function checkForUpdate(manifestUrl: string = UPDATE_MANIFEST): Promise<UpdateCheck> {
  if (manifestUrl.trim() === '') return { kind: 'no-source' }

  let manifest: UpdateManifest
  try {
    manifest = (await callApi(
      'GET',
      `/api/version/manifest?url=${encodeURIComponent(manifestUrl)}`,
    )) as UpdateManifest
  } catch (e) {
    // 连不上更新源不是「没有新版」：两者必须分开，否则用户会以为已经是最新的
    return { kind: 'failed', message: e instanceof Error ? e.message : String(e) }
  }

  if (manifest.version === undefined || manifest.version === '') {
    return { kind: 'failed', message: '更新清单里没有 version' }
  }
  if (!isNewer(manifest.version, APP_VERSION)) {
    return { kind: 'up-to-date', current: APP_VERSION }
  }
  return {
    kind: 'available',
    current: APP_VERSION,
    latest: manifest.version,
    notes: manifest.notes ?? '',
    url: manifest.url ?? '',
  }
}
