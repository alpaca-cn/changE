<script setup lang="ts">
import { useBackendStatus } from '../composables/useBackendStatus'

// 轮询、就绪守卫、失败终态、缓存文案全在 composable 里（Task 3 从本组件抽出去的，
// 逻辑与注释一字未改），这里只剩「把状态画成一条页脚」。
const { desc, cacheText, cacheError } = useBackendStatus()
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
