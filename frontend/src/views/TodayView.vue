<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SessionList from '../components/SessionList.vue'
import VolumePanel from '../components/VolumePanel.vue'
import { recentSessions, weeklyVolume, type SessionSummary, type WeeklyVolume } from '../api/training'

const router = useRouter()

const volume = ref<WeeklyVolume | null>(null)
const volumeError = ref<string | null>(null)
const sessions = ref<SessionSummary[]>([])
const sessionsError = ref<string | null>(null)

/** 错误一律显示 `message`：ApiError 是后端的中文校验文案，ApiTransportError 是外壳给的。 */
function reason(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

async function load() {
  // 两个请求各自 try/catch：周容量挂了不该把「最近训练」也拖成一片空白。
  try {
    volume.value = await weeklyVolume()
    volumeError.value = null
  } catch (e) {
    volumeError.value = reason(e)
  }
  try {
    sessions.value = await recentSessions()
    sessionsError.value = null
  } catch (e) {
    sessionsError.value = reason(e)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="page">
    <p class="eyebrow">记录</p>
    <h2>今日</h2>

    <div class="actions">
      <button class="primary" @click="router.push('/training')">开始训练</button>
    </div>

    <!--
      设计文档「两处刻意的界面设计」第 1 条：**「本周容量」放首页最显眼位置**。
      「腹/核心本周 0 组」这类提醒，只有让人躲不掉才有用。所以它排在最近训练之前。
    -->
    <VolumePanel :volume="volume" :error="volumeError" />
    <SessionList :sessions="sessions" :error="sessionsError" />
  </section>
</template>

<style scoped>
.page { padding: var(--gap-xl); max-width: 900px; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }

.actions { margin-bottom: var(--gap-sm); }
.primary {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
</style>
