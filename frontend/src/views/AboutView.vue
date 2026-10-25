<script setup lang="ts">
import painting from '../assets/about/oil-painting.jpg'
import { computed, ref } from 'vue'
import { APP_VERSION, BUILD_TIME, buildTimeText } from '../api/version'

/**
 * 检查更新。
 *
 * ## 为什么用 Tauri 的 updater 插件，而不是自己 fetch 清单
 *
 * 自己 fetch 只能回答「有没有新版」；而**装**这一步要：下载 exe、
 * 用公钥验证签名、静默运行安装包、重启应用。这些都在 Rust 侧，
 * 前端碰不到（也不需要碰）。
 *
 * 所以这里调 `check()` → 有新版就 `downloadAndInstall()` → `relaunch()`。
 * 公钥与清单地址在 `tauri.conf.json` 的 `plugins.updater` 里，
 * 私钥在 `keys/`（已被 gitignore）。
 *
 * ## 为什么用动态 import
 *
 * 插件在非 Tauri 环境（单元测试的 jsdom）里 import 就会抛。
 * 放在事件处理里，组件挂载时不会碰到它，测试也就不必为它搭桩。
 */
type Phase =
  | 'idle'
  | 'checking'
  | 'uptodate'
  | 'available'
  /** 正在下载。有 total 时是确定进度，没有时只显示已下载量。 */
  | 'downloading'
  /** 下载完了、正在安装。**这一段没有进度可报**（Tauri 不吐安装进度），
   *  必须与「下载中」分开，否则进度条停在 100% 不动会被当成卡死。 */
  | 'installing'
  | 'ready'
  | 'failed'

const phase = ref<Phase>('idle')
const message = ref('')
const latest = ref('')

/** 已下载字节与总字节。total = 0 表示服务器没给 Content-Length。 */
const received = ref(0)
const total = ref(0)
/** 开始下载的时刻（ms）。用来算平均速度与剩余时间。 */
let startedAt = 0

const progress = computed(() =>
  total.value > 0 ? Math.min(100, Math.round((received.value / total.value) * 100)) : 0,
)

/** 平均速度（字节/秒）。用**平均**而不是瞬时：瞬时速度在小分块上跳得没法看。 */
const speed = computed(() => {
  const seconds = (Date.now() - startedAt) / 1000
  return seconds > 0 ? received.value / seconds : 0
})

function mb(bytes: number): string {
  return (bytes / 1024 / 1024).toFixed(1)
}

/** 下载情况那一行：有总量时给百分比+速度+剩余，没有时只给已下载量。 */
const downloadDetail = computed(() => {
  if (phase.value !== 'downloading') return ''
  const got = `${mb(received.value)} MB`
  if (total.value <= 0) return `已下载 ${got}`
  const left = total.value - received.value
  const secs = speed.value > 0 ? Math.ceil(left / speed.value) : 0
  const eta = secs > 0 ? `约 ${secs >= 60 ? `${Math.floor(secs / 60)} 分 ${secs % 60} 秒` : `${secs} 秒`}` : '即将完成'
  // 速度用 KB/s 更直观：这个量级的下载，MB/s 会一直显示 0.3
  return `已下载 ${got} / ${mb(total.value)} MB · ${Math.round(speed.value / 1024)} KB/s · ${eta}`
})

const busy = computed(
  () => phase.value === 'checking' || phase.value === 'downloading' || phase.value === 'installing',
)

/** 有没有新版。**null 表示还不知道**（没查过，或查询失败）。 */
const hasUpdate = computed(() => phase.value === 'available' || phase.value === 'downloading')

/**
 * 给一个 Promise 加超时。
 *
 * 2026-10-23 实测：用户在挂着 VPN 时点「检查更新」，界面停在「正在检查…」不动。
 * 原因是更新器请求的 github.com 清单会 302 到 release-assets.githubusercontent.com，
 * 那条跳转被卡住，请求既不成功也不失败。
 *
 * **卡住和「查不到」必须长得不一样。** 没有超时的话，用户唯一能得到的信息是转圈，
 * 既不知道是不是网络问题，也不知道该不该继续等。
 */
const CHECK_TIMEOUT_MS = 15000

function withTimeout<T>(p: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms)
    p.then(
      (v) => { clearTimeout(timer); resolve(v) },
      (e) => { clearTimeout(timer); reject(e) },
    )
  })
}

async function runCheck() {
  if (phase.value === 'checking' || phase.value === 'downloading') return
  phase.value = 'checking'
  message.value = ''
  try {
    const { check } = await import('@tauri-apps/plugin-updater')
    const update = await withTimeout(
      check(),
      CHECK_TIMEOUT_MS,
      '检查更新超时（15 秒）。可能是网络、代理或 VPN 的问题 —— 关掉 VPN 再试一次。',
    )
    if (update === null) {
      phase.value = 'uptodate'
      return
    }
    latest.value = update.version
    phase.value = 'available'
    message.value = update.body ?? ''
  } catch (e) {
    // 连不上更新源、清单还没有发布过、网络被墙 —— 都落在这里。
    // **不能说成「已是最新」**：那会让人以为不用更新。
    phase.value = 'failed'
    message.value = e instanceof Error ? e.message : String(e)
  }
}

async function installUpdate() {
  if (phase.value !== 'available') return
  phase.value = 'downloading'
  received.value = 0
  total.value = 0
  startedAt = Date.now()
  try {
    const { check } = await import('@tauri-apps/plugin-updater')
    const { relaunch } = await import('@tauri-apps/plugin-process')
    const update = await check()
    if (update === null) {
      phase.value = 'uptodate'
      return
    }
    await update.downloadAndInstall((event) => {
      if (event.event === 'Started') {
        total.value = event.data.contentLength ?? 0
      } else if (event.event === 'Progress') {
        received.value += event.data.chunkLength
      } else if (event.event === 'Finished') {
        // 下载完了，接下来是静默安装。Tauri 不吐安装进度 —— 不能让它停在 100%。
        received.value = total.value > 0 ? total.value : received.value
        phase.value = 'installing'
      }
    })
    phase.value = 'ready'
    // 装完了要重启才生效。给用户一句话，而不是替他重启。
    message.value = '更新已安装。点下面的按钮重启应用。'
    await relaunch()
  } catch (e) {
    phase.value = 'failed'
    message.value = e instanceof Error ? e.message : String(e)
  }
}

const sources = [
  { name: '动作库主体 · 876 条', from: 'yuhonas/free-exercise-db', license: 'Unlicense（公有领域）' },
  { name: '中文说明 · 1,324 条（交叉校验用）', from: 'hasaneyldrm/exercises-dataset', license: 'MIT（仅文本）' },
  { name: '人体图区域路径 · 65 个', from: 'vulovix/body-muscles', license: 'Apache-2.0' },
]
</script>

<template>
  <section class="page">
    <p class="eyebrow">我的</p>
    <h2>关于与致谢</h2>

    <div class="hero">
      <img :src="painting" alt="训练者的油画" />
      <div class="cap">
        <p class="who">本项目的两幅配图由作者本人提供</p>
        <p class="note">它们随安装包一起分发，仅用于界面装饰。</p>
      </div>
    </div>

    <section class="block">
      <h3>数据来源</h3>
      <p class="hint">
        这三份数据随安装包一起发到你的机器上，所以许可证要求署名。
      </p>
      <ul class="sources">
        <li v-for="s in sources" :key="s.name">
          <span class="nm">{{ s.name }}</span>
          <span class="from">{{ s.from }}</span>
          <span class="lic">{{ s.license }}</span>
        </li>
      </ul>
    </section>

    <section class="block">
      <h3>版本</h3>
      <p class="hint">
        当前 <b class="mono">{{ APP_VERSION }}</b> · 构建于 <span class="mono">{{ buildTimeText(BUILD_TIME) }}</span>
      </p>

      <button class="primary" :disabled="busy" @click="runCheck">
        {{ phase === 'checking' ? '正在检查…' : '检查更新' }}
      </button>

      <div v-if="phase !== 'idle'" class="check">
        <p v-if="phase === 'uptodate'" class="ok">已经是最新的（{{ APP_VERSION }}）。</p>

        <p v-else-if="hasUpdate" class="ok">
          有新版本 <b>{{ latest }}</b>（当前 {{ APP_VERSION }}）。
        </p>

        <!-- 下载：有总量时给进度条，没有时给已下载量 -->
        <template v-if="phase === 'downloading'">
          <div v-if="total > 0" class="bar"><span :style="{ width: progress + '%' }" /></div>
          <p class="hint">{{ downloadDetail }}</p>
        </template>

        <!-- 安装：**没有进度可报**。给一句「正在安装」比一个卡在 100% 的条诚实 -->
        <p v-if="phase === 'installing'" class="hint">
          正在安装…（这一步没有进度，通常十几秒）
        </p>

        <button
          v-if="phase === 'available'"
          class="install"
          @click="installUpdate"
        >
          下载并安装
        </button>

        <p v-if="phase === 'ready'" class="ok">{{ message }}</p>
        <p v-if="phase === 'failed'" class="err">检查失败：{{ message }}</p>
        <p v-else-if="message !== '' && phase === 'available'" class="hint">{{ message }}</p>
      </div>

      <p class="hint tip">
        更新包从阿里云 OSS 下载，GitHub 作为兜底。装过一次之后，以后都不用再下安装包。
      </p>
    </section>

    <section class="block">
      <h3>关于这个项目</h3>
      <p class="hint">
        changE 是一个只在本地跑的健身记录工具。数据存在你自己的
        <code>%APPDATA%\\changE</code> 里，不上传、不同步、不需要账号。
        智能体对话要用你自己的模型 key——它也只存在那台机器上。
      </p>
    </section>
  </section>
</template>

<style scoped>
.page {
  margin-inline: auto; padding: var(--gap-xl); max-width: 960px; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }

/* 竖幅油画整幅放，不裁：它本身就是一个成品，裁了就只剩构图碎片 */
.hero { display: flex; gap: var(--gap-lg); align-items: flex-start; margin-top: var(--gap-md); }
.hero img {
  width: 260px;
  height: auto;
  display: block;
  border-radius: var(--radius);
  border: var(--line-width) solid var(--line);
}
.cap { padding-top: var(--gap-sm); }
.cap .who { margin: 0; font-size: 13px; }
.cap .note { margin: 6px 0 0; font-size: 11px; color: var(--eyebrow); line-height: 1.7; }

.block { margin-top: var(--gap-xl); }
h3 { font-size: 15px; }
.hint { color: var(--text-dim); font-size: 12px; line-height: 1.8; margin: var(--gap-sm) 0 var(--gap-md); }
.hint code { font-family: var(--mono); font-size: 11px; }

.sources { list-style: none; margin: 0; padding: 0; border-top: var(--line-width) solid var(--line); }
.sources li {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px var(--gap-md);
  padding: 10px 0;
  border-bottom: var(--line-width) solid var(--line);
  font-size: 12px;
}
.nm { font-weight: 500; }
.from { color: var(--text-dim); font-family: var(--mono); font-size: 11px; }
.lic { grid-column: 2; text-align: right; color: var(--eyebrow); font-size: 11px; }

.mono { font-family: var(--mono); font-variant-numeric: tabular-nums; }
.check { margin-top: var(--gap-md); }
.check .ok { color: var(--ok); font-size: 12px; }
.check .err { color: var(--danger); font-size: 12px; }
.tip { margin-top: var(--gap-md); color: var(--eyebrow); }
.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.primary:disabled { border-color: var(--line); color: var(--text-dim); cursor: not-allowed; }
.bar {
  height: 4px;
  border-radius: 2px;
  background: var(--line);
  overflow: hidden;
  margin: 6px 0 4px;
  max-width: 320px;
}
.bar span {
  display: block;
  height: 100%;
  background: var(--accent);
  border-radius: 2px;
  /* 速度波动时让宽度平滑过去，而不是一格一格跳 */
  transition: width 0.25s linear;
}

.install { margin-top: 6px; border-color: var(--accent); color: #fff; background: var(--accent); font-weight: 600; }
</style>
