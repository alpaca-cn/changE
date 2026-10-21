<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { BackendState } from '../api/backend'

/**
 * 后端没就绪时挡在工作区前面的启动态。
 *
 * 它**不自己轮询**：状态由父组件（`App.vue` 的 `useBackendStatus`）给进来，
 * 这里只负责「把等待画出来」和「失败时给一条出路」。这样启动门与页脚对
 * 「什么算就绪」的判断永远一致，不会出现门开了页脚还写着正在启动。
 */
const props = defineProps<{ state: BackendState }>()
const emit = defineEmits<{ retry: [] }>()

/**
 * 超过这个时长补一句解释。
 *
 * **原来的假设是错的。** 注释里写着「正常的第二次启动 4 秒内就进去了」，
 * 于是阈值取了 5 秒。实测（装出来的包，连续三次）是：
 * 首次 8.0s / 再次 8.5s / 第三次 8.1s —— **启动本来就一直是 8 秒**，
 * 所以那句提示每次都会出现，而它写的是「首次启动」。用户第二次打开看到
 * 「首次启动」，当然会问为什么。
 *
 * 现在取 12 秒：8 秒是常态，给约 50% 余量，只有真的卡住才提示。
 * 另外注意 `/api/health` 在 8 秒就 200 了，动作库导入是在那之后后台继续跑的——
 * 所以「启动门在等导入」这个说法本来也不成立。
 */
const SLOW_HINT_AFTER_MS = 12000

const showSlowHint = ref(false)
let hintTimer: number | undefined

function clearHintTimer() {
  if (hintTimer !== undefined) {
    window.clearTimeout(hintTimer)
    hintTimer = undefined
  }
}

/** 每次回到 `starting` 都从零计时：重试之后不应该立刻又挂着上一轮的提示。 */
function startHintTimer() {
  clearHintTimer()
  showSlowHint.value = false
  hintTimer = window.setTimeout(() => {
    showSlowHint.value = true
  }, SLOW_HINT_AFTER_MS)
}

watch(
  () => props.state.status,
  (status) => {
    if (status === 'starting') startHintTimer()
    else clearHintTimer()
  },
  { immediate: true },
)

onUnmounted(clearHintTimer)

const failed = computed(() => props.state.status === 'failed')
</script>

<template>
  <section class="startup" :class="failed ? 'failed' : 'starting'" role="status" aria-live="polite">
    <template v-if="failed">
      <!-- 失败态**不转圈**：转圈表示「还在等」，而后端的 failed 是终态 -->
      <div class="mark" aria-hidden="true">!</div>
      <h1 class="title">后端没有起来</h1>
      <p class="message">{{ state.message || '后端启动失败' }}</p>
      <button class="retry" type="button" @click="emit('retry')">重试</button>
      <p class="hint">如果反复失败，可以退出后重新打开应用。</p>
    </template>
    <template v-else>
      <div class="spinner" aria-hidden="true" />
      <h1 class="title">正在启动服务…</h1>
      <p class="lead">数据服务准备好之后会自动进入，不需要手动操作。</p>
      <p v-if="showSlowHint" class="hint slow">启动比平时慢（通常 8 秒左右）。如果一直停在这里，可以退出后重新打开应用。</p>
    </template>
  </section>
</template>

<style scoped>
.startup {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--gap-md);
  padding: var(--gap-xl);
  background: var(--bg);
  color: var(--text);
  text-align: center;
}

/* 只有一枚细描边的转圈，不铺大色块——设计文档要求用对比而不是装饰分层 */
.spinner {
  width: 26px;
  height: 26px;
  border: 2px solid var(--line);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg) } }

.title {
  margin: 0;
  font-size: 20px;
  font-weight: var(--title-weight);
  letter-spacing: var(--title-tracking);
}

.lead,
.message {
  margin: 0;
  max-width: 46ch;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-dim);
}

.hint {
  margin: 0;
  max-width: 46ch;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-dim);
}

.hint.slow { color: var(--text); }

/* 失败态：注意力给那枚「!」与标题，错误详情用正文的灰 */
.failed .title { color: var(--accent); }

.mark {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border: var(--line-width) solid var(--accent);
  border-radius: 50%;
  color: var(--accent);
  font-size: 22px;
  font-weight: var(--title-weight);
}

.retry {
  margin-top: var(--gap-sm);
  padding: 6px var(--gap-lg);
  font: inherit;
  font-size: 13px;
  color: var(--text);
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  cursor: pointer;
}

.retry:hover { border-color: var(--accent); }
</style>
