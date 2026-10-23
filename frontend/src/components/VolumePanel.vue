<script setup lang="ts">
import { computed } from 'vue'
import type { WeeklyVolume } from '../api/training'
import { setsText, statusClass, statusLabel, targetText } from './volume-status'
import MuscleStrip from './MuscleStrip.vue'
import WeekRing from './WeekRing.vue'

// 只读展示：数据由调用方（今日页）取好传进来，组件自己不发起请求。
// 这样它的测试不需要 mock invoke，专注在「渲染成什么样」上。
const props = defineProps<{ volume: WeeklyVolume | null; error: string | null }>()

/**
 * **顶层**肌群（没有父组的那 17 块）。
 *
 * 环形图与条带图只画顶层：子部位的组数已经计入父组，
 * 两者都画会让同一组数在图上出现两次，看起来像练了两倍。
 */
const topLevel = computed(() => (props.volume?.muscles ?? []).filter((m) => m.parentEn === null))

/** 有几块顶层。标题里那个数字不该再写死 17。 */
const topLevelCount = computed(() => topLevel.value.length)

</script>

<template>
  <section class="volume">
    <h3>本周容量</h3>

    <p v-if="error" class="err">{{ error }}</p>
    <p v-else-if="!volume" class="dim">正在读取本周容量…</p>

    <template v-else>
      <p class="span">{{ volume.weekStart }} → {{ volume.weekEnd }}</p>

      <!-- 图在表前：先「一眼看出」，再细看每一块。两者用同一份数据，不会打架。 -->
      <div class="charts">
        <WeekRing :muscles="topLevel" />
        <div class="strip-wrap">
          <p class="eyebrow">MUSCLE STRIP · {{ topLevelCount }}</p>
          <MuscleStrip :muscles="topLevel" />
          <p class="legend">颜色越深 = 本周折算组数越多 · 10 组封顶</p>
        </div>
      </div>

      <!--
        **17 块全部渲染**，包括 0 组的：设计文档要求「腹/核心本周 0 组」这类提醒
        躲不掉才有用，把 0 组过滤掉就等于把它藏起来了。顺序**保持后端给的顺序**
        （肌群 id 序）——固定顺序用户才能形成肌肉记忆。
      -->
      <ul class="muscles">
        <li
          v-for="m in volume.muscles"
          :key="m.muscleGroupId"
          class="muscle"
          :class="[statusClass(m.status), { child: m.parentEn !== null }]"
        >
          <span class="name">{{ m.nameZh }}</span>
          <span class="sets">{{ setsText(m.sets) }} 组</span>
          <span class="target">目标 {{ targetText(m) }}</span>
          <span class="state">{{ statusLabel(m.status) }}</span>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.volume { margin-top: var(--gap-lg); }
h3 { font-size: 15px; }
.span { color: var(--text-dim); font-size: 12px; margin: var(--gap-xs) 0 var(--gap-md); }

.charts {
  display: flex;
  align-items: center;
  gap: var(--gap-lg);
  padding-bottom: var(--gap-md);
  margin-bottom: var(--gap-md);
  border-bottom: var(--line-width) solid var(--line);
}

.strip-wrap { flex: 1 1 auto; min-width: 0; }

.legend { margin: var(--gap-sm) 0 0; color: var(--eyebrow); font-size: 10px; }

.muscles {
  list-style: none;
  margin: 0;
  padding: 0;
  /* 用空白与细线分层，不用卡片边框（设计文档） */
  border-top: var(--line-width) solid var(--line);
}

/* 子部位缩进并淡一档：它们属于上面那一块的细分，不是同级。
   缩进用 padding 而不是 margin，行高与整行点击区域才不会跟着缩。 */
.muscle.child { padding-left: var(--gap-lg); }
.muscle.child .name { font-weight: 400; color: var(--text-dim); }
.muscle.child .name::before { content: '└ '; color: var(--eyebrow); }

.muscle {
  display: grid;
  grid-template-columns: 1fr auto auto auto;
  gap: var(--gap-md);
  align-items: baseline;
  padding: 7px 0;
  border-bottom: var(--line-width) solid var(--line);
}

.name { font-weight: 600; }
.sets { font-variant-numeric: tabular-nums; }
.target { color: var(--text-dim); font-size: 12px; min-width: 96px; text-align: right; }
.state { font-size: 12px; min-width: 64px; text-align: right; }

/* 状态色只落在**文字**上，不做大面积色块（设计文档：厚重感来自对比） */
.in-range .state      { color: var(--ok); }
.below .state         { color: var(--warn); }
.above .state         { color: var(--danger); }
.untrained .state     { color: var(--muted-strong); }
.not-configured .state { color: var(--text-dim); }

/* 0 组的整行压暗，但**不隐藏** */
.untrained .name, .untrained .sets { color: var(--text-dim); }

.err { color: var(--danger); }
.dim { color: var(--text-dim); }
</style>
