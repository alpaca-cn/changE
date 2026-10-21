<script setup lang="ts">
import { computed } from 'vue'
import type { MuscleVolume } from '../api/training'

const props = defineProps<{ muscles: MuscleVolume[] }>()

/**
 * 本周达标率 = **IN_RANGE 的肌群数 / 有目标的肌群数**。
 *
 * 三个刻意的决定：
 *   1. 分母只算**配了目标**的（`min`/`max` 非 null）。`neck` 没目标，
 *      算进分母会让达成率永远到不了 100%——那不是用户的错。
 *   2. 分子**只算 IN_RANGE**。ABOVE（练过量）不算达标：练过头不是成绩。
 *   3. 分母为 0 时不做除零，显示「—」而不是 0%——没有目标时「达成率」
 *      本身就不成立，显示 0% 会误导。
 */
const total = computed(() => props.muscles.filter((m) => m.max !== null).length)
const hit = computed(() => props.muscles.filter((m) => m.status === 'IN_RANGE').length)

const pct = computed(() =>
  total.value === 0 ? null : Math.round((hit.value / total.value) * 100),
)

/** SVG 圆环：r=34 → 周长 2πr ≈ 213.6。 */
const R = 34
const C = 2 * Math.PI * R
const dash = computed(() => {
  const p = pct.value ?? 0
  return { array: `${(C * p) / 100} ${C}`, offset: 0 }
})
</script>

<template>
  <div class="ring">
    <svg width="82" height="82" viewBox="0 0 82 82" aria-hidden="true">
      <circle cx="41" cy="41" :r="R" fill="none" stroke="var(--line)" stroke-width="3" />
      <circle
        cx="41"
        cy="41"
        :r="R"
        fill="none"
        stroke="var(--accent)"
        stroke-width="3"
        stroke-linecap="round"
        :stroke-dasharray="dash.array"
        :stroke-dashoffset="dash.offset"
        transform="rotate(-90 41 41)"
      />
    </svg>
    <div class="center">
      <div class="pct">{{ pct === null ? '—' : `${pct}%` }}</div>
      <div class="cap">WEEK</div>
    </div>
  </div>
</template>

<style scoped>
.ring {
  position: relative;
  width: 82px;
  height: 82px;
  flex: none;
}

.center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.pct {
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.cap {
  margin-top: 3px;
  font-size: 8px;
  letter-spacing: var(--eyebrow-tracking);
  color: var(--eyebrow);
}
</style>
