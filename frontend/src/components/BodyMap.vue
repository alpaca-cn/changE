<script setup lang="ts">
import { computed } from 'vue'
import type { BodyMapMode, MuscleBodyMap } from '../api/body-map'
import { FRONT_MUSCLES } from '../assets/body-map/data/muscles.front'
import { BACK_MUSCLES } from '../assets/body-map/data/muscles.back'

/**
 * 人体图上一块肌群的类型与三种口径**只有一份定义**，在 `../api/body-map`
 * （`MuscleBodyMap` / `BodyMapMode`）。这里从那里导入，不再自己声明：
 * 同一个名字在 `api/training.ts` 里是 `/api/volume/weekly` 的 7 字段形状，
 * 两处同名不同形会让「导入错的那个」不报错、只是整张图拿不到 `regionIds`。
 *
 * 契约名 `volumeByMuscle` 与事件名 `muscle-click` 是设计文档决定 5 **冻结**的：
 * 第二阶段换 Three.js 时只换渲染层，这两个名字不动。
 */
const props = defineProps<{
  volumeByMuscle: Record<number, MuscleBodyMap>
  mode: BodyMapMode
}>()

const emit = defineEmits<{
  (e: 'muscle-click', muscleGroupId: number): void
}>()

/**
 * 两面各自的 viewBox。
 *
 * 素材原本是**同一张** SVG 里的两个人体：正面在左、背面在右，坐标系里正面占
 * x 0–32、背面占 x 36–69（按 89 条 path 的坐标算出的包围盒，y 都是 0–93）。
 * 两面都切成 33 单位宽、93 单位高：比例一致，并排渲染时人形一样高，不会一大一小。
 */
const sides = [
  { name: 'front', label: '正面', viewBox: '0 0 33 93', muscles: FRONT_MUSCLES },
  { name: 'back', label: '背面', viewBox: '36 0 33 93', muscles: BACK_MUSCLES },
] as const

/** 未映射区域、以及 0 组的基线色（就是人体轮廓的灰）。 */
const BASELINE_FILL = 'var(--muted-strong)'

/**
 * 强度档位 0–5 → 与 `--accent` 混合的百分比。
 *
 * **这些阈值是计划 6b 自定的着色分档，可调，不是训练建议、没有医学依据。**
 * 颜色本身只有一个来源（`--accent`），强度靠混合比例表达——不新增任何色值。
 */
const LEVEL_PERCENT = [0, 20, 36, 52, 70, 88] as const

/** 档位收到 0–5。非有限数（NaN/Infinity）当 0：宁可灰着，也不要一个坏 fill。 */
function clampLevel(level: number): number {
  if (!Number.isFinite(level)) return 0
  return Math.min(5, Math.max(0, Math.round(level)))
}

/**
 * volume：按**本周组数**，越多越深。
 *
 * 10 组封顶（2 组一档）：练得多的人再往上不再加深，否则整张图会糊成一块，
 * 反而看不出「哪里练少了」。
 */
function volumeLevel(mv: MuscleBodyMap): number {
  return clampLevel((mv.sets / 10) * 5)
}

/**
 * recovering：按 `daysSince`，**越近越深**。0–2 天最深、3–5 天浅、>5 天不着色。
 *
 * 负数**显式**并入 0：后端 `daysSince = 参照日 - lastTrainedOn`，而 lastTrainedOn
 * 的 SQL 不带日期范围，所以用 `?date=` 翻到比「最近一次训练」更早的周时它会是
 * 负数，语义是「你在这天之后练过这块肌肉」——那是**最近练过**，不是很久没练。
 *
 * 不能写成 `d >= 0 && d <= 2`：那样负数会落到 else（不着色），图上就是一块
 * 几天前刚练过的肌肉显示成灰的，而界面上看不出来那是错的。
 */
function recoveringLevel(mv: MuscleBodyMap): number {
  const d = mv.daysSince
  if (d === null) return 0 // 从没练过：谈不上「恢复中」
  const days = Math.max(0, d)
  if (days <= 2) return 5
  if (days <= 5) return 3
  return 0
}

/**
 * neglected：按 `daysSince`，**越久越深**。>14 天提示、>30 天最重。
 *
 * `null`（从没练过）给最重的一档，并且**与「练过但很久」不是同一档**：
 * 「从来没练过」和「两个月没练」都该提醒，但不是同一件事，混成一个颜色就把
 * 这个区别丢掉了。
 *
 * 负数按「刚练过」处理（同 recovering 的注释）→ 不着色。
 */
function neglectedLevel(mv: MuscleBodyMap): number {
  const d = mv.daysSince
  if (d === null) return 5
  const days = Math.max(0, d)
  if (days > 30) return 4
  if (days > 14) return 3
  if (days > 7) return 2
  return 0
}

/** 当前 mode 下这块肌群的档位。三种口径就是三个函数，不共用一条判断链。 */
function levelFor(mv: MuscleBodyMap): number {
  switch (props.mode) {
    case 'recovering':
      return recoveringLevel(mv)
    case 'neglected':
      return neglectedLevel(mv)
    default:
      // 'volume'；类型外的东西也退到这里，宁可显示容量也不要一片空白。
      return volumeLevel(mv)
  }
}

interface RegionHit {
  muscleGroupId: number
  level: number
}

/**
 * 区域 id → 所属肌群与着色档位。
 *
 * 从 `volumeByMuscle` 里的 `regionIds` 反推（契约里没有别的来源，前端也不该
 * 自己维护一份「肌群 → 区域」的映射）。没出现在任何 `regionIds` 里的区域查不到
 * → 基线色、不可点：素材的 89 个区域里有 24 个是 head / face / 关节 / 四肢末端 /
 * spine / serratus-anterior-* / tibialis-anterior-* / hip-flexor-*，它们不是训练
 * 目标，但**仍然要画出来**——缺了看起来像残肢。
 */
const regionIndex = computed(() => {
  const map = new Map<string, RegionHit>()
  for (const mv of Object.values(props.volumeByMuscle)) {
    const level = levelFor(mv)
    for (const regionId of mv.regionIds) {
      map.set(regionId, { muscleGroupId: mv.muscleGroupId, level })
    }
  }
  return map
})

function levelOf(regionId: string): number {
  return regionIndex.value.get(regionId)?.level ?? 0
}

function isMapped(regionId: string): boolean {
  return regionIndex.value.has(regionId)
}

/** 档位 → fill。0 档（含未映射区域）是基线灰，其余是 --accent 与基线的混合。 */
function fillOf(regionId: string): string {
  const percent = LEVEL_PERCENT[levelOf(regionId)] ?? 0
  if (percent === 0) return BASELINE_FILL
  return `color-mix(in srgb, var(--accent) ${percent}%, var(--muted-strong))`
}

/**
 * 点一块区域 → 把**肌群 id** 发出去（不是区域 id）。
 * 未映射区域点不动：直接返回，什么也不 emit。
 */
function onRegionClick(regionId: string): void {
  const hit = regionIndex.value.get(regionId)
  if (!hit) return
  emit('muscle-click', hit.muscleGroupId)
}
</script>

<template>
  <div class="body-map" :data-mode="mode">
    <!-- 两个并排的 svg：正面 / 背面。各自独立的 viewBox，不拼成一张大图。 -->
    <figure v-for="side in sides" :key="side.name" class="side">
      <figcaption class="eyebrow">{{ side.label }}</figcaption>
      <svg class="figure" :viewBox="side.viewBox">
        <path
          v-for="m in side.muscles"
          :key="m.id"
          :d="m.path"
          :fill="fillOf(m.id)"
          :data-region-id="m.id"
          :data-intensity="levelOf(m.id)"
          :data-interactive="isMapped(m.id) ? 'true' : 'false'"
          :class="{ 'is-clickable': isMapped(m.id) }"
          @click="onRegionClick(m.id)"
        />
      </svg>
    </figure>
  </div>
</template>

<style scoped>
.body-map {
  display: flex;
  gap: var(--gap-lg);
  align-items: flex-start;
}

.side {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--gap-sm);
}

/* 两面同高，宽度由 viewBox 的比例（33:93）决定，所以人形一样高。 */
.figure {
  display: block;
  height: 440px;
  width: auto;
}

/* 描边用底色：相邻肌群之间留一道缝，否则一整片同档颜色会糊成一块。 */
path {
  stroke: var(--bg);
  stroke-width: 0.15;
  stroke-linejoin: round;
}

.is-clickable {
  cursor: pointer;
}

.is-clickable:hover {
  stroke: var(--accent);
  stroke-width: 0.4;
}
</style>
