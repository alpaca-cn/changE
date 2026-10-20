<script setup lang="ts">
import AppSidebar from './components/AppSidebar.vue'
import AppToolbar from './components/AppToolbar.vue'
import BackendStatusBar from './components/BackendStatusBar.vue'
import StartupGate from './components/StartupGate.vue'
import { useBackendStatus } from './composables/useBackendStatus'

/**
 * **启动门**：后端没就绪时，整块工作区（侧边栏 + 工具条 + RouterView + 页脚）
 * 一个都不渲染。
 *
 * 原因是启动链路：双击 → Tauri 窗口立刻显示 → Vue 挂载 → `RouterView` 渲染
 * `TodayView` → 它立刻发 API 请求 → 后端还在启动（首次 12 秒）→ 请求失败 →
 * 用户看到一个满是红字的「今日」页，那看起来像坏了，其实只是还没起来。
 * 六个业务页显示失败文案**本身是对的**（后端真的挂了就该说），错的是启动期
 * 就把它们渲染出来。所以门开在 `RouterView` 之上，业务页一行都不改。
 *
 * `healthPolling: false`：页脚在位时自己会查缓存文案，这里只要「能渲染了吗」，
 * 少一份 5 秒一次的 HTTP 轮询。页脚的行为不经过这里，一字未变。
 */
const { state, retry } = useBackendStatus({ healthPolling: false })
</script>

<template>
  <!-- 失败态也走启动门：它同样不渲染工作区，但会显示错误与「重试」，不会转圈到底 -->
  <StartupGate v-if="state.status !== 'ready'" :state="state" @retry="retry" />
  <div v-else class="shell">
    <AppSidebar />
    <div class="main">
      <AppToolbar />
      <main class="workspace">
        <RouterView />
      </main>
      <BackendStatusBar />
    </div>
  </div>
</template>

<style scoped>
.shell { display: flex; height: 100%; }
.main { flex: 1 1 auto; display: flex; flex-direction: column; min-width: 0; }
.workspace { flex: 1 1 auto; overflow-y: auto; }
</style>
