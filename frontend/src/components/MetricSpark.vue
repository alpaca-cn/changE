<script setup lang="ts">
import { computed } from 'vue'
import { deltaText, polyline, type MetricSeries } from '../views/metric-series'
import { metricLabel } from '../api/body'

/**
 * 一个身体指标的走势小卡：指标名 + 最新值 + 变化 + 折线。
 *
 * 与「动作进步曲线」用同一种视觉（细线 + 单色），但**不共用组件**：那边是三条
 * 不同量纲的曲线并排、带 y 轴刻度；这里是一条小走势、只回答「在往哪走」。
 * 硬合成一个组件会让两边都变形。
 */
const props = defineProps<{ series: MetricSeries }>()

const path = computed(() => polyline(props.series.points, 220, 48))
const label = computed(() => metricLabel(props.series.metricType))
const up = computed(() => props.series.delta > 0)
</script>

<template>
  <div class="spark">
    <div class="head">
      <span class="name">{{ label }}</span>
      <span class="latest">
        {{ series.latest ?? '—' }}<small>{{ series.unit }}</small>
      </span>
    </div>

    <svg
      v-if="path !== ''"
      class="line"
      viewBox="0 0 220 48"
      preserveAspectRatio="none"
      role="img"
      :aria-label="`${label} 的走势`"
    >
      <polyline :points="path" fill="none" stroke="var(--accent)" stroke-width="1.6" />
    </svg>
    <p v-else class="only-one">再记一次就能看出走势</p>

    <p class="delta" :class="{ up, down: !up && series.delta !== 0 }">
      {{ deltaText(series) }}
    </p>
  </div>
</template>

<style scoped>
.spark { border-top: var(--line-width) solid var(--line); padding-top: 10px; }
.head { display: flex; align-items: baseline; justify-content: space-between; }
.name { font-size: 12px; color: var(--text-dim); }
.latest { font-size: 17px; font-weight: 500; font-variant-numeric: tabular-nums; }
.latest small { font-size: 10px; color: var(--eyebrow); margin-left: 3px; font-weight: 400; }

.line { display: block; width: 100%; height: 48px; margin-top: 6px; }

.only-one { margin: 6px 0 0; font-size: 10px; color: var(--eyebrow); }

.delta { margin: 4px 0 0; font-size: 10px; color: var(--eyebrow); font-variant-numeric: tabular-nums; }
.delta.up { color: var(--ok); }
.delta.down { color: var(--warn); }
</style>
