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
 * 本周训练次数。
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

/** 本周总组数（原始组数，不是折算后的）。拿不到容量时说 null。 */
const setsThisWeek = computed(() => {
  const v = volume.value
  if (v === null) return null
  return sessions.value
    .filter((s) => s.sessionDate >= v.weekStart && s.sessionDate <= v.weekEnd)
    .reduce((n, s) => n + (s.sets ?? 0), 0)
})

/** 漏练的肌群名（0 组的那些），给概览卡最后一行。 */
const untrained = computed(() => {
  const v = volume.value
  if (v === null) return []
  return v.muscles.filter((m) => m.sets <= 0).map((m) => m.nameZh)
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
      <button class="hero-cta" @click="router.push('/training')">开始训练</button>
    </div>

    <!--
      概览带：两张卡**压在 hero 下沿上**（负外边距），像贴在照片上。
      这是参考图里那种「卡片浮在画面上」的做法——单纯往下排会变成一条条列表。
    -->
    <div class="band">
      <div class="card overview glass">
        <div class="card-head">
          <span class="caps">本周进度</span>
          <span class="week mono">{{ volume?.weekStart ?? '—' }} → {{ volume?.weekEnd ?? '—' }}</span>
        </div>

        <div class="overview-body">
          <div class="ring-wrap">
            <div class="ring">
              <svg width="96" height="96" viewBox="0 0 96 96" aria-hidden="true">
                <circle cx="48" cy="48" r="41" fill="none" stroke="var(--line)" stroke-width="4" />
                <circle
                  cx="48"
                  cy="48"
                  r="41"
                  fill="none"
                  stroke="var(--accent)"
                  stroke-width="4"
                  stroke-linecap="round"
                  :stroke-dasharray="`${Math.min(1, (sessionsThisWeek ?? 0) / 4) * 257.6} 257.6`"
                  transform="rotate(-90 48 48)"
                />
              </svg>
              <div class="ring-num">
                <b>{{ sessionsThisWeek ?? '—' }}</b>
                <span>次</span>
              </div>
            </div>
            <p class="ring-cap">本周训练</p>
          </div>

          <div class="facts">
            <div class="fact">
              <span class="k">总组数</span>
              <span class="v mono">{{ setsThisWeek ?? '—' }}</span>
            </div>
            <div class="fact">
              <span class="k">漏练肌群</span>
              <span class="v">{{ untrained.length === 0 ? '无' : `${untrained.length} 块` }}</span>
            </div>
            <div class="fact">
              <span class="k">最需要补</span>
              <span class="v small">{{ untrained.slice(0, 3).join('、') || '—' }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="card intake-card glass">
        <div class="card-head">
          <span class="caps">今天吃了多少</span>
          <span class="week mono">{{ intake?.meals ?? 0 }} 餐 · {{ intake?.items ?? 0 }} 条目</span>
        </div>
        <p v-if="intakeError" class="err">{{ intakeError }}</p>
        <template v-else>
          <p class="big">
            {{ intake?.nutrition.kcal ?? 0 }} <small>千卡</small>
          </p>
          <div class="macros">
            <span>蛋白 <b>{{ intake?.nutrition.protein ?? 0 }}</b> g</span>
            <span>碳水 <b>{{ intake?.nutrition.carb ?? 0 }}</b> g</span>
            <span>脂肪 <b>{{ intake?.nutrition.fat ?? 0 }}</b> g</span>
          </div>
        </template>
      </div>
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
.page { padding: var(--gap-xl); max-width: 1000px; }

/* ── hero ─────────────────────────────────────────────── */
.hero {
  position: relative;
  min-height: 300px;
  border-radius: var(--radius);
  overflow: hidden;
  background-color: var(--hero);
  background-size: cover;
  background-position: 68% 32%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
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
  padding: var(--gap-lg) 30px 96px;
}

/* hero 里的小标签与副标题要跟着翻成浅色，否则在深底上读不出来 */
.hero .eyebrow { color: rgba(244, 242, 237, 0.52); }

.hero h2 {
  font-size: 34px;
  margin: 6px 0 0;
  color: #f7f5f0;
  letter-spacing: -0.01em;
}

.hero-sub { margin: 10px 0 0; font-size: 12px; color: rgba(244, 242, 237, 0.62); }

/* 主按钮压在 hero 右下角：它是这一页唯一的动作 */
.hero-cta {
  position: absolute;
  right: 24px;
  bottom: 24px;
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
  font-weight: 600;
  padding: 9px 18px;
}

/* ── 概览带：压在 hero 下沿 ─────────────────────────────── */
.band {
  position: relative;
  display: grid;
  grid-template-columns: 1.35fr 1fr;
  gap: var(--gap-md);
  margin: -72px var(--gap-md) 0;
}

.card {
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  padding: var(--gap-md);
  box-shadow: 0 8px 24px rgba(26, 26, 26, 0.06);
}

.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--gap-sm);
  margin-bottom: var(--gap-sm);
}

.caps {
  font-size: 10px;
  letter-spacing: var(--eyebrow-tracking);
  color: var(--eyebrow);
  font-weight: 600;
}

.week { font-size: 10px; color: var(--eyebrow); }
.mono { font-family: var(--mono); font-variant-numeric: tabular-nums; }

.overview-body { display: flex; align-items: center; gap: var(--gap-md); }

.ring-wrap { flex: none; text-align: center; }
.ring { position: relative; width: 96px; height: 96px; }

.ring-num {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 2px;
  padding-top: 34px;
}
.ring-num b { font-size: 24px; font-weight: 600; font-variant-numeric: tabular-nums; }
.ring-num span { font-size: 10px; color: var(--eyebrow); }

.ring-cap { margin: 4px 0 0; font-size: 10px; color: var(--eyebrow); }

.facts { flex: 1 1 auto; min-width: 0; }
.fact {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--gap-sm);
  padding: 6px 0;
  border-bottom: var(--line-width) solid var(--line-soft);
}
.fact:last-child { border-bottom: none; }
.fact .k { font-size: 11px; color: var(--text-dim); }
.fact .v { font-size: 17px; font-weight: 600; font-variant-numeric: tabular-nums; }
.fact .v.small { font-size: 12px; font-weight: 500; }

.intake-card .big {
  margin: 4px 0 0;
  font-size: 34px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
.intake-card .big small { font-size: 12px; font-weight: 400; color: var(--eyebrow); }

.macros {
  display: flex;
  gap: var(--gap-md);
  margin-top: var(--gap-md);
  font-size: 11px;
  color: var(--text-dim);
}
.macros b { color: var(--text); font-weight: 600; font-variant-numeric: tabular-nums; }

.err { color: var(--danger); }

/* 窄屏：概览带改成单列，负外边距也要收一收，否则卡片会盖住 hero 的字 */
@media (max-width: 760px) {
  .band { grid-template-columns: 1fr; margin: -40px var(--gap-sm) 0; }
  .hero-ct { padding-bottom: 72px; }
}
</style>
