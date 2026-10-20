<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import SessionList from '../components/SessionList.vue'
import ExerciseDetailPanel from '../components/ExerciseDetailPanel.vue'
import { exerciseDetail, type ExerciseDetail } from '../api/exercise-detail'
import {
  lastPerformance,
  recordSession,
  recentSessions,
  searchExercises,
  sessionDetail,
  weeklyVolume,
  type ExerciseSummary,
  type MuscleVolume,
  type SessionDetail,
  type SessionSummary,
} from '../api/training'
import {
  emptyRow,
  lastPerformanceNote,
  rowsFromLastPerformance,
  toSessionInput,
  todayIso,
  validationError,
  type SetRow,
} from './training-form'

/**
 * 训练记录页：走设计文档流程①。
 *
 *   选肌群 → 出动作列表 → 选动作 → 填 60 × 8 · 打勾 → 自动带出上次重量
 *   → 继续下一组… → 结束训练 → 写入 Session / Exercise / Set
 *
 * 状态全部是**组件内的 ref**，没引入 store：这一页用完即弃，跨页共享的只有
 * 「最近训练」，而那一份每次进页面重取一次就够——引入 Pinia 只会多一层
 * 需要在页面间同步的缓存。
 */

/** 输入搜索的防抖。太短会在每个按键上都打一次后端，太长会显得迟钝。 */
const SEARCH_DEBOUNCE_MS = 300

function reason(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

// ---- 肌群（复用周容量那 17 块，不新增后端端点）----
const muscles = ref<MuscleVolume[]>([])
const musclesError = ref<string | null>(null)
const selectedMuscleId = ref<number | ''>('')

// ---- 动作选择 ----
const query = ref('')
const exercises = ref<ExerciseSummary[]>([])
const exercisesError = ref<string | null>(null)
const selectedExercise = ref<ExerciseSummary | null>(null)

// ---- 组 ----
const rows = ref<SetRow[]>([emptyRow()])
const prefillNote = ref('')

// ---- 训练本身 ----
const title = ref('')
const perceivedState = ref('')
const note = ref('')

// ---- 提交与历史 ----
const saving = ref(false)
const saveError = ref<string | null>(null)
const savedSummary = ref('')
const recent = ref<SessionSummary[]>([])
const recentError = ref<string | null>(null)
const detail = ref<SessionDetail | null>(null)
const detailError = ref<string | null>(null)

// ---- 动作详情抽屉（看做法） ----
// 与「这次训练的历史详情」是两件事：那个叫 detail，这个叫 panelDetail。
const panelOpen = ref(false)
const panelDetail = ref<ExerciseDetail | null>(null)
const panelLoading = ref(false)
const panelError = ref<string | null>(null)
let panelSeq = 0

async function loadMuscles() {
  try {
    const v = await weeklyVolume()
    muscles.value = v.muscles
    musclesError.value = null
  } catch (e) {
    // **不显示空列表**：空列表会让人以为「库里没有肌群」，而真因是取数失败。
    muscles.value = []
    musclesError.value = reason(e)
  }
}

async function loadRecent() {
  try {
    recent.value = await recentSessions()
    recentError.value = null
  } catch (e) {
    recentError.value = reason(e)
  }
}

async function runSearch() {
  const q = query.value.trim()
  const muscleGroupId = selectedMuscleId.value === '' ? undefined : selectedMuscleId.value
  if (q === '' && muscleGroupId === undefined) {
    exercises.value = []
    return
  }
  try {
    exercises.value = await searchExercises({
      muscleGroupId,
      q: q === '' ? undefined : q,
    })
    exercisesError.value = null
  } catch (e) {
    exercises.value = []
    exercisesError.value = reason(e)
  }
}

let searchTimer: number | undefined
function clearSearchTimer() {
  if (searchTimer !== undefined) {
    window.clearTimeout(searchTimer)
    searchTimer = undefined
  }
}

// 输入框防抖；选肌群走 `onMuscleChange` 立刻搜（点一下就该有结果）
watch(query, () => {
  clearSearchTimer()
  searchTimer = window.setTimeout(() => void runSearch(), SEARCH_DEBOUNCE_MS)
})

onUnmounted(clearSearchTimer)

function onMuscleChange() {
  clearSearchTimer()
  void runSearch()
}

/** 选中一个动作：把「上次」带出来。 */
async function pickExercise(ex: ExerciseSummary) {
  selectedExercise.value = ex
  saveError.value = null
  savedSummary.value = ''
  try {
    const lp = await lastPerformance(ex.id)
    rows.value = rowsFromLastPerformance(lp)
    prefillNote.value = lastPerformanceNote(lp)
  } catch (e) {
    // 带不出上次不该拦住记录：给一行空组，把原因说出来
    rows.value = [emptyRow()]
    prefillNote.value = ''
    saveError.value = reason(e)
  }
}

function addRow() {
  rows.value.push(emptyRow())
}

function removeRow(index: number) {
  rows.value.splice(index, 1)
}

async function save() {
  saveError.value = ''
  savedSummary.value = ''

  const problem = validationError(selectedExercise.value?.id ?? null, rows.value)
  if (problem !== null) {
    saveError.value = problem
    return
  }

  saving.value = true
  try {
    const result = await recordSession(
      toSessionInput({
        sessionDate: todayIso(),
        title: title.value,
        perceivedState: perceivedState.value,
        note: note.value,
        exerciseId: selectedExercise.value!.id,
        rows: rows.value,
      }),
    )
    savedSummary.value = `已记录：${result.exercises} 个动作 / ${result.sets} 组`
    // 清空这一轮的表单，但**保留肌群与搜索词**——连着记第二个动作时不用重选
    selectedExercise.value = null
    rows.value = [emptyRow()]
    prefillNote.value = ''
    title.value = ''
    perceivedState.value = ''
    note.value = ''
    await loadRecent()
  } catch (e) {
    // **表单不清空**：服务端拒绝时把用户填的东西丢掉是最气人的事
    saveError.value = reason(e)
  } finally {
    saving.value = false
  }
}

async function openDetail(id: number) {
  detailError.value = null
  try {
    detail.value = await sessionDetail(id)
  } catch (e) {
    detail.value = null
    detailError.value = reason(e)
  }
}

/**
 * 打开「看做法」抽屉并取详情。
 *
 * 不跳页：跳页会把已经填好的组丢掉。面板盖在当前页上，关掉后组还在。
 * 连点（或关掉又开）时用序号丢掉过期响应——先发的可能后到，把已关闭的
 * 面板改成有内容只会让人以为点错了。
 */
async function openExerciseDetail() {
  const ex = selectedExercise.value
  if (ex === null) return

  const seq = ++panelSeq
  panelOpen.value = true
  panelDetail.value = null
  panelError.value = null
  panelLoading.value = true
  try {
    const d = await exerciseDetail(ex.id)
    if (seq !== panelSeq) return
    panelDetail.value = d
  } catch (e) {
    if (seq !== panelSeq) return
    panelError.value = reason(e)
  } finally {
    if (seq === panelSeq) panelLoading.value = false
  }
}

function closeExerciseDetail() {
  panelOpen.value = false
  // 推走序号：还在路上的响应不该写进一个已经关掉的面板
  panelSeq += 1
}

onMounted(() => {
  void loadMuscles()
  void loadRecent()
})
</script>

<template>
  <section class="page">
    <p class="eyebrow">记录</p>
    <h2>训练记录</h2>

    <!-- 1. 选肌群 → 出动作列表 -->
    <div class="picker">
      <label class="field">
        <span class="label">肌群</span>
        <!-- 取不到肌群时**不显示空下拉**：空列表会被读成「库里没有肌群」 -->
        <select v-if="!musclesError" v-model="selectedMuscleId" class="muscle-picker" @change="onMuscleChange">
          <option value="">选择肌群</option>
          <option v-for="m in muscles" :key="m.muscleGroupId" :value="m.muscleGroupId">
            {{ m.nameZh }}
          </option>
        </select>
        <span v-else class="err">{{ musclesError }}</span>
      </label>

      <label class="field">
        <span class="label">搜索动作</span>
        <input v-model="query" class="query" type="search" placeholder="中文名或英文名" />
      </label>
    </div>

    <p v-if="exercisesError" class="err">{{ exercisesError }}</p>
    <ul v-else-if="exercises.length > 0" class="exercises">
      <li v-for="ex in exercises" :key="ex.id">
        <button
          type="button"
          class="exercise"
          :class="{ picked: selectedExercise?.id === ex.id }"
          @click="pickExercise(ex)"
        >
          <span class="ex-name">{{ ex.nameZh || ex.nameEn }}</span>
          <span class="ex-meta">{{ ex.nameZh ? ex.nameEn : '' }}{{ ex.equipment ? ` · ${ex.equipment}` : '' }}</span>
        </button>
      </li>
    </ul>

    <!-- 2. 选动作 → 带出上次重量 → 填组 -->
    <template v-if="selectedExercise">
      <div class="exercise-head">
        <h3>{{ selectedExercise.nameZh || selectedExercise.nameEn }}</h3>
        <!-- 看做法是抽屉，不跳页：跳走会把下面已经填好的组丢掉 -->
        <button type="button" class="look-up" @click="openExerciseDetail">看做法</button>
      </div>
      <p v-if="prefillNote" class="prefill">{{ prefillNote }}</p>

      <table class="sets">
        <thead>
          <tr><th>#</th><th>重量</th><th>次数</th><th>完成</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in rows" :key="row.id" class="set-row">
            <td class="idx">{{ i + 1 }}</td>
            <td><input v-model="row.weight" class="weight" inputmode="decimal" /></td>
            <td><input v-model="row.repetitions" class="reps" inputmode="numeric" /></td>
            <td><input v-model="row.completed" class="completed" type="checkbox" /></td>
            <td><button type="button" class="remove" @click="removeRow(i)">删除</button></td>
          </tr>
        </tbody>
      </table>

      <!-- 这条口径不写出来就没人知道：没打勾的组**不计入**本周容量 -->
      <p class="hint">打勾的组才算训练量；没打勾的是「还没做 / 还没记」。</p>

      <div class="row-actions">
        <button type="button" class="add-row" @click="addRow">加一组</button>
      </div>

      <div class="session-fields">
        <label class="field">
          <span class="label">标题</span>
          <input v-model="title" class="title" placeholder="胸 + 三头" />
        </label>
        <label class="field">
          <span class="label">今天状态</span>
          <input v-model="perceivedState" class="state" placeholder="睡得好" />
        </label>
        <label class="field">
          <span class="label">备注</span>
          <input v-model="note" class="note" />
        </label>
      </div>

      <div class="save-row">
        <button type="button" class="primary" :disabled="saving" @click="save">结束训练</button>
      </div>
    </template>

    <p v-if="saveError" class="err save-error">{{ saveError }}</p>
    <p v-if="savedSummary" class="ok">{{ savedSummary }}</p>

    <!-- 3. 历史 -->
    <SessionList :sessions="recent" :error="recentError" @select="openDetail" />

    <p v-if="detailError" class="err">{{ detailError }}</p>
    <div v-if="detail" class="detail">
      <h3>{{ detail.sessionDate }} · {{ detail.title || '（未命名）' }}</h3>
      <ul class="detail-exercises">
        <li v-for="ex in detail.exercises" :key="ex.sessionExerciseId">
          <span class="ex-name">{{ ex.nameZh || ex.nameEn }}</span>
          <span class="ex-meta">{{ ex.sets.length }} 组</span>
        </li>
      </ul>
    </div>

    <!-- 动作详情抽屉：数据由本页拉，面板只负责渲染三种状态 -->
    <ExerciseDetailPanel
      v-if="panelOpen"
      :detail="panelDetail"
      :loading="panelLoading"
      :error="panelError"
      @close="closeExerciseDetail"
    />
  </section>
</template>

<style scoped>
.page { padding: var(--gap-xl); max-width: 900px; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }
h3 { font-size: 16px; margin: var(--gap-lg) 0 var(--gap-sm); }

.picker { display: flex; gap: var(--gap-lg); flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: var(--gap-xs); }
.label { font-size: 11px; color: var(--eyebrow); text-transform: uppercase; letter-spacing: var(--eyebrow-tracking); }

select, input {
  font: inherit;
  color: inherit;
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  padding: 5px 8px;
}

.exercises { list-style: none; margin: var(--gap-md) 0 0; padding: 0; border-top: var(--line-width) solid var(--line); }
.exercise {
  display: flex;
  justify-content: space-between;
  gap: var(--gap-md);
  width: 100%;
  text-align: left;
  background: none;
  border: none;
  border-bottom: var(--line-width) solid var(--line);
  border-radius: 0;
  padding: 7px 0;
}
.exercise:hover .ex-name { color: var(--accent); }
.exercise.picked .ex-name { color: var(--accent); font-weight: 700; }
.ex-name { font-weight: 600; }
.ex-meta { color: var(--text-dim); font-size: 12px; }

.exercise-head { display: flex; align-items: center; justify-content: space-between; gap: var(--gap-md); }
.exercise-head h3 { margin-bottom: 0; }
.look-up { border-color: var(--accent); color: var(--accent); font-weight: 600; }

.prefill { color: var(--text-dim); font-size: 12px; }

.sets { border-collapse: collapse; margin-top: var(--gap-sm); }
.sets th { font-size: 11px; color: var(--eyebrow); text-transform: uppercase; letter-spacing: var(--eyebrow-tracking); text-align: left; padding-right: var(--gap-md); }
.set-row td { padding: 3px var(--gap-md) 3px 0; }
.idx { color: var(--text-dim); font-variant-numeric: tabular-nums; }
.weight, .reps { width: 80px; }
.completed { width: auto; }
.remove { padding: 2px 8px; font-size: 12px; }

.hint { color: var(--text-dim); font-size: 12px; margin: var(--gap-sm) 0 0; }
.row-actions { margin-top: var(--gap-sm); }

.session-fields { display: flex; gap: var(--gap-lg); flex-wrap: wrap; margin-top: var(--gap-lg); }
.save-row { margin-top: var(--gap-lg); }
.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.primary:disabled { opacity: 0.5; cursor: default; }

.detail { margin-top: var(--gap-md); }
.detail-exercises { list-style: none; margin: 0; padding: 0; }
.detail-exercises li { display: flex; justify-content: space-between; gap: var(--gap-md); padding: 5px 0; border-bottom: var(--line-width) solid var(--line); }

.err { color: var(--danger); }
.ok { color: var(--ok); }
</style>
