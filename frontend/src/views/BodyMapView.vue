<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { searchExercises, type ExerciseSummary } from '../api/training'
import {
  bodyMapVolume,
  type BodyMapMode,
  type BodyMapVolume,
  type MuscleBodyMap,
} from '../api/body-map'
import BodyMap from '../components/BodyMap.vue'
import BodyMapSidePanel from '../components/BodyMapSidePanel.vue'
import ExerciseDetailPanel from '../components/ExerciseDetailPanel.vue'
import { exerciseDetail, type ExerciseDetail } from '../api/exercise-detail'

/**
 * 人体图页：左边是 2D 人体（正反两面），右边是被选中那块肌群的每周详情。
 *
 * <h2>这一页只看「现在」，所以请求不带 date</h2>
 *
 * 后端 `daysSince = 参照日 - lastTrainedOn`，而 `lastTrainedOn` 的 SQL 不带日期范围。
 * 只要传了比最近一次训练更早的 `?date=`，`daysSince` 就会是负数（`<BodyMap />`
 * 已把负数并入 0 兜底，但那是给「翻看往期」预留的容错）。人体图页展示的是本周，
 * 没有理由去碰这个边界：省略 `date` = 后端决定今天。真有「翻看往期」需求时，
 * 这里必须先决定负数怎么解释，而不是顺手把 date 接上。
 *
 * <h2>三种口径只是换一个着色函数</h2>
 *
 * 数据一份，`mode` 决定「强度」怎么算：本周组数 / 离上次训练多近 / 离上次训练多久。
 * 它不是筛选器——三种口径下 17 块肌肉都画出来，只是深淡不同。
 *
 * <h2>动作列表为什么在页面里拉</h2>
 *
 * 面板是纯展示组件（同 `VolumePanel`），页面负责「点了哪块、什么时候请求、
 * 连点时怎么防串台」。面板因此不需要知道 `searchExercises` 的存在。
 */

interface ModeSpec {
  key: BodyMapMode
  label: string
  hint: string
}

/**
 * 三个模式按钮的固定顺序。
 *
 * 文案只描述「颜色在说什么」，不给训练建议：分档阈值是计划 6b 自定的，没有医学依据
 * （见 `BodyMap.vue` 的 `LEVEL_PERCENT` 注释）。
 */
const MODES: ModeSpec[] = [
  { key: 'volume', label: '本周组数', hint: '颜色越深，本周练得越多，10 组封顶。' },
  { key: 'recovering', label: '恢复中', hint: '颜色越深，离上次训练越近，2 天内最深。' },
  { key: 'neglected', label: '久未训练', hint: '颜色越深，离上次训练越久，30 天以上最重。' },
]

function reason(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

// ---- 本周人体图数据 ----

const volume = ref<BodyMapVolume | null>(null)
/**
 * 挂载即「加载中」：请求还没回来时页面不能只有标题。
 * 与「请求失败」分开——后者是 `.err`，并且不画图（一张全灰的图会被读成「本周都没练」）。
 */
const loading = ref(true)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    // **不传 date**：见文件头注释。
    volume.value = await bodyMapVolume()
  } catch (e) {
    volume.value = null
    error.value = reason(e)
  } finally {
    loading.value = false
  }
}

/**
 * 动作详情的抽屉。与训练记录页用的是同一个组件、同一份数据来源——
 * 「点一个动作看做法」不该因为你在哪个页面点而长得不一样。
 */
const detailOpen = ref(false)
const detail = ref<ExerciseDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)

async function openExercise(id: number) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = null
  try {
    detail.value = await exerciseDetail(id)
  } catch (e) {
    detail.value = null
    detailError.value = reason(e)
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  void load()
})

/** `<BodyMap />` 要的是 `Record<muscleGroupId, MuscleBodyMap>`，后端给的是数组。 */
const volumeByMuscle = computed<Record<number, MuscleBodyMap>>(() => {
  const out: Record<number, MuscleBodyMap> = {}
  for (const m of volume.value?.muscles ?? []) out[m.muscleGroupId] = m
  return out
})

// ---- 选中与动作列表 ----

const selectedId = ref<number | null>(null)
const exercises = ref<ExerciseSummary[]>([])
const exercisesLoading = ref(false)
const exercisesError = ref<string | null>(null)

/**
 * 选中的那块肌肉。**按 id 从当前数据里找**，不存整块对象：
 * 数据刷新后（将来的「重新读取」）面板跟着新数字走，不会留着一份旧的副本。
 */
const selectedMuscle = computed<MuscleBodyMap | null>(
  () => volume.value?.muscles.find((m) => m.muscleGroupId === selectedId.value) ?? null,
)

const mode = ref<BodyMapMode>('volume')
const modeHint = computed(() => MODES.find((m) => m.key === mode.value)?.hint ?? '')

/** 连点两块肌肉时，先发的响应可能后到：用序号丢掉过期的那一份。 */
let exerciseSeq = 0

async function selectMuscle(muscleGroupId: number) {
  selectedId.value = muscleGroupId
  exercises.value = []
  exercisesError.value = null
  exercisesLoading.value = true
  const seq = ++exerciseSeq
  try {
    const found = await searchExercises({ muscleGroupId })
    if (seq !== exerciseSeq) return
    exercises.value = found
  } catch (e) {
    if (seq !== exerciseSeq) return
    exercises.value = []
    exercisesError.value = reason(e)
  } finally {
    if (seq === exerciseSeq) exercisesLoading.value = false
  }
}

function closePanel() {
  selectedId.value = null
  // 让还在飞的那次请求作废：关掉面板后它回来也不该把列表写进下一次选择
  exerciseSeq++
  exercises.value = []
  exercisesError.value = null
  exercisesLoading.value = false
}
</script>

<template>
  <section class="page">
    <p class="eyebrow">分析</p>
    <h2>人体图</h2>

    <div class="layout">
      <div class="canvas">
        <div class="modes" role="group" aria-label="着色口径">
          <button
            v-for="m in MODES"
            :key="m.key"
            type="button"
            class="mode"
            :class="{ picked: mode === m.key }"
            :data-mode="m.key"
            :aria-pressed="mode === m.key"
            @click="mode = m.key"
          >
            {{ m.label }}
          </button>
        </div>
        <p class="hint">{{ modeHint }}</p>
        <p class="note">关节与头面等 24 个区域不可点击 —— 它们不对应具体肌群。</p>

        <p v-if="loading" class="dim">正在读取本周容量…</p>
        <p v-else-if="error !== null" class="err">{{ error }}</p>
        <template v-else-if="volume !== null">
          <p class="span">{{ volume.weekStart }} → {{ volume.weekEnd }}</p>
          <BodyMap
            :volume-by-muscle="volumeByMuscle"
            :mode="mode"
            @muscle-click="selectMuscle"
          />
        </template>
      </div>

      <BodyMapSidePanel
        :muscle="selectedMuscle"
        :exercises="exercises"
        :loading="exercisesLoading"
        :error="exercisesError"
        @close="closePanel"
        @exercise-click="openExercise"
      />
      <ExerciseDetailPanel
        v-if="detailOpen"
        :detail="detail"
        :loading="detailLoading"
        :error="detailError"
        @close="detailOpen = false"
      />
    </div>
  </section>
</template>

<style scoped>
.page {
  padding: var(--gap-xl);
  max-width: 1180px;
}
h2 {
  font-size: 26px;
  margin: var(--gap-sm) 0 var(--gap-md);
}

/* 画布占大头，右侧面板固定宽（宽度写在面板自己身上）。 */
.layout {
  display: flex;
  align-items: flex-start;
  gap: var(--gap-lg);
}

.canvas {
  flex: 1 1 auto;
  min-width: 0;
}

.modes {
  display: flex;
  gap: var(--gap-sm);
  flex-wrap: wrap;
}

.mode {
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  color: var(--text-dim);
  padding: 5px 12px;
}

.mode.picked {
  color: var(--text);
  border-color: var(--accent);
}

.note { color: var(--eyebrow); font-size: 11px; margin: calc(-1 * var(--gap-sm)) 0 var(--gap-md); }

.hint {
  color: var(--text-dim);
  font-size: 12px;
  margin: var(--gap-sm) 0 var(--gap-md);
}

.span {
  color: var(--text-dim);
  font-size: 12px;
  margin: 0 0 var(--gap-md);
  font-variant-numeric: tabular-nums;
}

.dim {
  color: var(--text-dim);
}

.err {
  color: var(--danger);
}

/* 窄屏：面板落到画布下面，否则 320px 会把人体挤扁 */
@media (max-width: 900px) {
  .layout {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
