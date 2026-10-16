<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { getBackendState, describeState, checkHealth, type BackendState } from '../api/backend'

/**
 * 启动期的轮询间隔。
 *
 * 后端起得慢（实测 3.8–6.8 秒），只问一次的话用户要盯着「正在启动后端…」
 * 直到超时。500ms 是「看起来像立刻亮起来」与「别把 IPC 打满」的折中：
 * 启动期总共也就十几次调用，代价可以忽略。
 */
const SHELL_POLL_MS = 500

/**
 * 稳态下刷新缓存文案的间隔。
 *
 * **不能沿用 500ms**：外壳状态就绪之后永远不会回到 `starting`（Rust 侧
 * `with_port` / `child_exited` / `timed_out` 都以 `status == Ready` 提前返回，
 * 由 `a_ready_state_is_never_overwritten_by_a_later_failure` 守着），
 * 所以再问外壳状态纯属白烧；而 health 每次都是一个真实的 HTTP 请求，
 * 500ms 一条就是每秒 2 个请求永远不停，打在一个本地服务上毫无意义。
 *
 * 但也不只查一次：缓存描述是**会变的**——Redis 掉线后 `describe()` 会从
 * 「已连接」变成「已熔断」。这个变化很慢，5 秒足够及时，而稳态开销
 * 从 2 次/秒降到 0.2 次/秒。
 */
const HEALTH_POLL_MS = 5000

const state = ref<BackendState>({ status: 'starting', port: null, message: '' })
const cacheText = ref<string>('')
const cacheError = ref<string>('')

let shellTimer: number | undefined
let healthTimer: number | undefined

function stopShellPolling() {
  if (shellTimer !== undefined) {
    window.clearInterval(shellTimer)
    shellTimer = undefined
  }
}

function stopHealthPolling() {
  if (healthTimer !== undefined) {
    window.clearInterval(healthTimer)
    healthTimer = undefined
  }
}

async function pollHealth() {
  if (state.value.port === null) return
  try {
    const h = await checkHealth(state.value.port)
    cacheText.value = h.cache
    cacheError.value = ''
  } catch (e) {
    // 一次失败不清空上一次成功的文案：Redis 抖一下就把「缓存：已连接」
    // 换成错误提示，比继续显示旧值更让人困惑。
    cacheError.value = String(e)
  }
}

/**
 * 就绪 → 停掉外壳轮询、立刻查一次 health、按稳态节奏继续刷新。
 *
 * `void` 而不是 `await`：health 是旁路，不该拖慢状态栏本身——
 * 端口一到就先把「后端已连接」显示出来。
 */
function onReady() {
  stopShellPolling()
  void pollHealth()
  if (healthTimer === undefined) {
    healthTimer = window.setInterval(() => void pollHealth(), HEALTH_POLL_MS)
  }
}

async function tick() {
  try {
    state.value = await getBackendState()
  } catch (e) {
    state.value = { status: 'failed', port: null, message: `与外壳通信失败：${String(e)}` }
  }

  if (state.value.status === 'starting') return // 还没起来，继续按 SHELL_POLL_MS 问
  stopShellPolling() // 就绪与失败都是单向的，再问也只会得到同一个答案
  if (state.value.status === 'ready') onReady()
}

onMounted(() => {
  void tick()
  shellTimer = window.setInterval(() => void tick(), SHELL_POLL_MS)
})

onUnmounted(() => {
  stopShellPolling()
  stopHealthPolling()
})

const desc = computed(() => describeState(state.value))
</script>

<template>
  <footer class="status" :class="desc.tone">
    <span class="dot" />
    <span class="text">{{ desc.text }}</span>
    <span v-if="cacheText" class="cache">{{ cacheText }}</span>
    <span v-else-if="cacheError" class="cache err">缓存状态取不到：{{ cacheError }}</span>
    <span v-else-if="desc.tone === 'loading'" class="cache dim">后端启动需要几秒，请稍候</span>
  </footer>
</template>

<style scoped>
.status {
  display: flex;
  align-items: center;
  gap: var(--gap-sm);
  padding: 6px var(--gap-md);
  border-top: var(--line-width) solid var(--line);
  background: var(--panel);
  font-size: 12px;
  color: var(--text-dim);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-dim);
  flex: 0 0 auto;
}

/* 色调只影响小圆点，整条状态栏保持低调——设计文档要求用对比而非大色块 */
.status.ok .dot      { background: var(--accent); }
.status.error .dot   { background: var(--accent); }
.status.loading .dot { animation: pulse 1.1s ease-in-out infinite; }

@keyframes pulse { 0%, 100% { opacity: 0.3 } 50% { opacity: 1 } }

.text { color: var(--text); }
.cache { margin-left: auto; }
.cache.dim { opacity: 0.7; }
.cache.err { color: var(--accent); }
</style>
