<script setup lang="ts">
import type { MuscleVolume } from '../api/training'
import { short, tone } from './muscle-strip'

// 只读展示：数据由 VolumePanel 传进来，组件自己不发起请求。
// 颜色与短名的算式在 muscle-strip.ts 里（纯函数，可单独测）。
defineProps<{ muscles: MuscleVolume[] }>()
</script>

<template>
  <div class="strip" role="img" aria-label="17 块肌群的本周折算组数">
    <div v-for="m in muscles" :key="m.muscleGroupId" class="cell">
      <div
        class="block"
        :style="{ background: tone(m.sets) }"
        :title="`${m.nameZh} · ${m.sets} 组`"
        :data-sets="m.sets"
      />
      <div class="cap">{{ short(m.nameZh) }}</div>
    </div>
  </div>
</template>

<style scoped>
.strip {
  display: flex;
  gap: 5px;
}

.cell { flex: 1 1 0; min-width: 0; text-align: center; }

.block {
  height: 22px;
  border-radius: 3px;
  border: var(--line-width) solid var(--line);
}

.cap {
  margin-top: 6px;
  font-size: 8px;
  color: var(--eyebrow);
  letter-spacing: 0.02em;
  white-space: nowrap;
  overflow: hidden;
}
</style>
