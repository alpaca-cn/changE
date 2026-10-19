<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SessionList from '../components/SessionList.vue'
import VolumePanel from '../components/VolumePanel.vue'
import { dailyNutrition, type DailyNutrition } from '../api/nutrition'
import { recentSessions, weeklyVolume, type SessionSummary, type WeeklyVolume } from '../api/training'

const router = useRouter()

const volume = ref<WeeklyVolume | null>(null)
const volumeError = ref<string | null>(null)
const sessions = ref<SessionSummary[]>([])
const sessionsError = ref<string | null>(null)

/** 今天吃了多少。**不传日期**——「今天」由后端决定（前端自己算会在跨零点/跨时区时和后端不一致）。 */
const intake = ref<DailyNutrition | null>(null)
const intakeError = ref<string | null>(null)

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
  // 第三个面板单独 try/catch：饮食挂了不该把训练那两块也拖成空白
  try {
    intake.value = await dailyNutrition()
    intakeError.value = null
  } catch (e) {
    intake.value = null
    intakeError.value = reason(e)
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

    <!-- 今天吃了多少。空的一天显示 0，不是空白——「还没吃」也是一个事实。 -->
    <section class="intake">
      <h3>今天吃了多少</h3>
      <p v-if="intakeError" class="err">{{ intakeError }}</p>
      <div v-else class="intake-row">
        <span class="kcal">{{ intake?.nutrition.kcal ?? 0 }} 千卡</span>
        <span class="macros">
          蛋白 {{ intake?.nutrition.protein ?? 0 }} · 碳水 {{ intake?.nutrition.carb ?? 0 }} ·
          脂肪 {{ intake?.nutrition.fat ?? 0 }} 克
        </span>
        <span class="counts">{{ intake?.meals ?? 0 }} 餐 · {{ intake?.items ?? 0 }} 条目</span>
      </div>
    </section>
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

.intake { margin-top: var(--gap-lg); }
.intake h3 { font-size: 15px; }
.intake-row {
  display: flex;
  align-items: baseline;
  gap: var(--gap-md);
  margin-top: var(--gap-md);
  padding-top: var(--gap-md);
  border-top: var(--line-width) solid var(--line);
}
.intake-row .kcal { font-size: 20px; font-weight: var(--title-weight); letter-spacing: var(--title-tracking); }
.intake-row .macros { color: var(--text-dim); font-size: 12px; }
.intake-row .counts { margin-left: auto; color: var(--text-dim); font-size: 12px; }
.err { color: var(--danger); }
</style>
