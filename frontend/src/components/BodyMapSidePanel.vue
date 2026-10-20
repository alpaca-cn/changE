<script setup lang="ts">
import { computed } from 'vue'
import type { MuscleBodyMap } from '../api/body-map'
import type { ExerciseSummary, VolumeStatus } from '../api/training'
import { setsText, targetText } from './volume-status'

/**
 * 人体图右侧面板：一块肌群的「本周组数 / 参考区间 / 区间比较 / 动作列表」。
 *
 * <h2>为什么是纯展示组件</h2>
 *
 * 数据由 `BodyMapView` 取好传进来（与 `VolumePanel`、`ExerciseDetailPanel` 同一分工）：
 * 「点哪块肌肉、什么时候拉动作、连点时怎么防串台」留在页面里，组件只管渲染。
 * 于是它的测试不需要 mock `invoke`，拿一个对象就能测三种数据形态。
 *
 * <h2>status 的中文措辞：描述比较结果，不做评价</h2>
 *
 * `status` 是后端 `VolumeCalculator` 给的一个事实：本周折算组数落在参考目标的哪一段。
 * 它不是「你练得够不够」的判断——参考区间本身只是建议，睡眠、恢复、别的训练都会
 * 改变它该是多少。所以这里用「低于参考区间 / 在参考区间内 / 高于参考区间」这种
 * **描述性的**说法，不用「不足 / 超标 / 达标」这类带评判的词：数值对不上目标已经
 * 写在组数与区间里了，界面不需要再加一层「你错了」。
 *
 * `UNTRAINED`（本周 0 组）与 `NOT_CONFIGURED`（没配目标）各给一句实话：
 * 前者是「本周还没有组数」，后者是「我们没给这块肌肉定建议量」——两者都不该被
 * 显示成「低于参考区间」，因为那会暗示存在一个它没达到的目标。
 */

const props = withDefaults(
  defineProps<{
    muscle: MuscleBodyMap | null
    exercises?: ExerciseSummary[]
    loading?: boolean
    error?: string | null
  }>(),
  { exercises: () => [], loading: false, error: null },
)

const emit = defineEmits<{ (e: 'close'): void }>()

/** 状态 → 中文短语。**界面措辞，不是医学判断**（见文件头注释）。 */
const STATUS_LABEL: Record<VolumeStatus, string> = {
  IN_RANGE: '在参考区间内',
  BELOW: '低于参考区间',
  ABOVE: '高于参考区间',
  UNTRAINED: '本周 0 组',
  NOT_CONFIGURED: '未设参考区间',
}

/**
 * 未知状态退到「未设参考区间」：宁可显得「没配目标」，也不静默留一个空单元格。
 * 这条兜底只在后端新增状态而忘了同步时才会走到。
 */
function statusLabel(status: VolumeStatus): string {
  return STATUS_LABEL[status] ?? '未设参考区间'
}

function displayName(ex: ExerciseSummary): string {
  // nameZh 可以为 null（手工录入的动作）：显示时退回 nameEn，不留空白
  return ex.nameZh || ex.nameEn
}

/**
 * 面板要显示的五项，先在这里算好。
 *
 * - `sets` 复用 `volume-status.ts` 的 `setsText`：折算组数可能是 1.5，不能被取整；
 * - `target` 复用 `targetText`：`null` 是「没设目标」，不是「0–0」；
 * - `status` 用本文件这套描述性措辞（与 `volume-status.ts` 的评价式措辞不同，
 *   原因见文件头注释），所以单独映射。
 */
interface ShownMuscle {
  nameZh: string
  nameEn: string
  sets: string
  target: string
  status: VolumeStatus
  statusText: string
}

const shown = computed<ShownMuscle | null>(() => {
  const m = props.muscle
  if (m === null) return null
  return {
    nameZh: m.nameZh,
    nameEn: m.nameEn,
    sets: `${setsText(m.sets)} 组`,
    target: targetText(m),
    status: m.status,
    statusText: statusLabel(m.status),
  }
})
</script>

<template>
  <aside class="panel">
    <header class="panel-head">
      <h3 class="panel-title">{{ shown === null ? '肌群详情' : shown.nameZh }}</h3>
      <button type="button" class="close" aria-label="关闭详情" @click="emit('close')">×</button>
    </header>

    <!-- 没选中肌肉时也要有话说：一列空白会被读成「这里坏了」 -->
    <p v-if="shown === null" class="guide">
      点人体图上的一块肌肉，这里会显示它本周的组数、参考区间和动作。
    </p>

    <template v-else>
      <p class="subtitle">{{ shown.nameEn }}</p>

      <dl class="facts">
        <div class="fact" data-fact="sets">
          <dt>本周组数</dt>
          <dd>{{ shown.sets }}</dd>
        </div>
        <div class="fact" data-fact="target">
          <dt>参考区间</dt>
          <dd>{{ shown.target }}</dd>
        </div>
        <div class="fact" data-fact="status">
          <dt>区间比较</dt>
          <dd :data-status="shown.status">{{ shown.statusText }}</dd>
        </div>
      </dl>

      <section class="exercises">
        <h4>这个肌群的动作</h4>

        <p v-if="loading" class="dim">正在读动作…</p>
        <p v-else-if="error" class="err">{{ error }}</p>
        <!-- 空列表不是空白：没有动作是一个可读的事实 -->
        <p v-else-if="exercises.length === 0" class="empty">这个肌群还没有动作。</p>
        <ul v-else class="exercise-list">
          <li v-for="ex in exercises" :key="ex.id" class="exercise">{{ displayName(ex) }}</li>
        </ul>
      </section>
    </template>
  </aside>
</template>

<style scoped>
/* 右侧固定宽：画布占大头，面板宽度不随内容跳动（否则点不同肌群图会左右晃）。 */
.panel {
  display: flex;
  flex-direction: column;
  gap: var(--gap-md);
  width: 320px;
  flex: 0 0 320px;
  padding: var(--gap-lg);
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--gap-sm);
}

.panel-title {
  font-size: 20px;
  margin: 0;
}

.close {
  padding: 0 6px;
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 18px;
  line-height: 1;
}

.subtitle {
  margin: calc(-1 * var(--gap-sm)) 0 0;
  color: var(--text-dim);
  font-size: 12px;
}

.guide {
  color: var(--text-dim);
  margin: 0;
}

/* 靠间距与细线分层，不用卡片边框（设计文档） */
.facts {
  margin: 0;
  border-top: var(--line-width) solid var(--line);
}

.fact {
  display: flex;
  justify-content: space-between;
  gap: var(--gap-md);
  padding: 7px 0;
  border-bottom: var(--line-width) solid var(--line);
}

.fact dt {
  color: var(--text-dim);
  font-size: 12px;
}

.fact dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}

/* 状态色只落在文字上，不做大面积色块（设计文档：厚重感来自对比） */
[data-status='IN_RANGE'] {
  color: var(--ok);
}
[data-status='BELOW'] {
  color: var(--warn);
}
[data-status='ABOVE'] {
  color: var(--danger);
}
[data-status='UNTRAINED'] {
  color: var(--muted-strong);
}
[data-status='NOT_CONFIGURED'] {
  color: var(--text-dim);
}

.exercises h4 {
  font-size: 12px;
  color: var(--eyebrow);
  text-transform: uppercase;
  letter-spacing: var(--eyebrow-tracking);
  margin: 0 0 var(--gap-sm);
}

.exercise-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: var(--line-width) solid var(--line);
}

.exercise {
  padding: 7px 0;
  border-bottom: var(--line-width) solid var(--line);
}

.dim {
  color: var(--text-dim);
  margin: 0;
}

.empty {
  color: var(--text-dim);
  margin: 0;
}

.err {
  color: var(--danger);
  margin: 0;
}

/* 窄屏：面板落到画布下面，宽度恢复自适应 */
@media (max-width: 900px) {
  .panel {
    width: 100%;
    flex: 1 1 auto;
  }
}
</style>
