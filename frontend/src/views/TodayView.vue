<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SessionList from '../components/SessionList.vue'
import VolumePanel from '../components/VolumePanel.vue'
import { dailyNutrition, type DailyNutrition } from '../api/nutrition'
import { recentSessions, weeklyVolume, type SessionSummary, type WeeklyVolume } from '../api/training'
// 用户提供的素材。走 Vite 的静态资源管线（不是 public/），这样文件名会带 hash，
// 也不会被 release 资源检查漏掉。
import heroImg from '../assets/hero/pose-night.jpg'

const router = useRouter()

const volume = ref<WeeklyVolume | null>(null)
const volumeError = ref<string | null>(null)
const sessions = ref<SessionSummary[]>([])
const sessionsError = ref<string | null>(null)

/** 今天吃了多少。**不传日期**——「今天」由后端决定（前端自己算会在跨零点/跨时区时和后端不一致）。 */
const intake = ref<DailyNutrition | null>(null)
const intakeError = ref<string | null>(null)

/**
 * hero 的副标题：本周训练次数。
 *
 * 只在拿到周区间之后才算——`sessionDate` 与 `weekStart` 都是 `YYYY-MM-DD`，
 * 字符串比较就是日期比较（不需要 new Date，也就不会踩时区）。
 * 拿不到区间时返回 null，让界面说「正在读取」而不是说「0 次」——
 * 「还没读到」与「真的是 0 次」是两件事。
 */
const sessionsThisWeek = computed(() => {
  const v = volume.value
  if (v === null) return null
  return sessions.value.filter(
    (s) => s.sessionDate >= v.weekStart && s.sessionDate <= v.weekEnd,
  ).length
})

const heroSub = computed(() => {
  const n = sessionsThisWeek.value
  if (n === null) return '正在读取本周数据…'
  return n === 0 ? '本周还没有训练 · 练一次也算' : `本周 ${n} 次训练`
})

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
    <!--
      hero：整页唯一的一块深色（--hero）。照片是用户提供的素材，
      左侧压一层渐变把文字衬出来。这块**不依赖任何请求**，所以数据全挂时
      页面仍然有身份与入口——「失败不能白屏」靠的是它。
    -->
    <div class="hero" :style="{ backgroundImage: `url(${heroImg})` }">
      <div class="scrim" />
      <div class="hero-ct">
        <p class="eyebrow">今日</p>
        <h2>今天你健身了吗</h2>
        <p class="hero-sub">{{ heroSub }}</p>
      </div>
    </div>

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

.hero {
  position: relative;
  min-height: 200px;
  border-radius: var(--radius);
  overflow: hidden;
  background-color: var(--hero);
  background-size: cover;
  background-position: 68% 32%;
  display: flex;
  align-items: flex-end;
}

/* 左深右透：文字压在左边，照片的主体留在右边 */
.scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    96deg,
    rgba(12, 11, 9, 0.94) 0%,
    rgba(12, 11, 9, 0.8) 34%,
    rgba(12, 11, 9, 0.28) 62%,
    rgba(12, 11, 9, 0.05) 100%
  );
}

.hero-ct {
  position: relative;
  padding: var(--gap-lg) 30px;
}

/* hero 里的小标签与副标题要跟着翻成浅色，否则在深底上读不出来 */
.hero .eyebrow { color: rgba(244, 242, 237, 0.52); }

.hero h2 {
  font-size: 30px;
  margin: 6px 0 0;
  color: #f7f5f0;
  letter-spacing: -0.01em;
}

.hero-sub {
  margin: 10px 0 0;
  font-size: 12px;
  color: rgba(244, 242, 237, 0.62);
}

.actions { margin: var(--gap-md) 0 var(--gap-sm); }

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
