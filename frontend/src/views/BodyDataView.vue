<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  METRIC_DEFAULT_UNITS,
  METRIC_LABELS,
  METRIC_TYPES,
  addMetric,
  listMetrics,
  metricLabel,
  types,
  type Metric,
  type MetricType,
  type MetricTypeSummary,
} from '../api/body'
import { todayIso } from './training-form'

/**
 * 身体数据页：体重 / 体脂 / 各围度。
 *
 * 三块：顶部「当前值」卡片（值来自 `types()`）、下面的时间线、最下面的录入区。
 *
 * <h2>四条表单判断（都不是随手决定的）</h2>
 *
 * ① **没有「单位」输入框。** 后端按 `metricType` 填默认单位（weight → kg、
 *    围度 → cm、体脂 → %），前端只在输入框旁边**显示**它。让用户填单位等于给他
 *    一个弄错的机会，而算错的数值**不会报错**——只会静悄悄躺在历史里。
 * ② **日期默认今天，但可以改。** 补录昨天的体重是常见需求；后端只认
 *    `YYYY-MM-DD`，格式不对会回 400，界面照常显示它那句中文。
 * ③ **备注可选，空就传 `null`。** 后端 `note` 可空，null 与「没写」是同一个意思。
 *    显式传 null 而不是省略字段，是为了让请求体的形状固定。
 * ④ **数值只做最轻的拦（空 / 非数字 / 负数），真正的范围校验交给后端。**
 *    只有后端知道那个类型的知识（体脂 100% 以上算不算错，是后端说的）；
 *    前端拦多了会把一条真实数据当成笔误挡掉，而「0 公斤」是允许的。
 *
 * <h2>空态与错误态是两件事</h2>
 *
 * 一个类型都没记过 → 引导文案（**不是**报错）；某个类型没记过 → 卡片显示「—」
 * （**不是** 0）；网络/后端真失败 → 错误提示 + 后端文案。
 */

function reason(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 「当前值」卡片：九个类型固定显示，没记过的那个显示「—」。 */
interface Card {
  metricType: string
  label: string
  valueText: string
  unit: string
  meta: string
}

const summaries = ref<MetricTypeSummary[]>([])
const typesError = ref<string | null>(null)

const metrics = ref<Metric[]>([])
const metricsError = ref<string | null>(null)

const formType = ref<MetricType>('weight')
const measuredOn = ref(todayIso())
const valueText = ref('')
const note = ref('')
const saving = ref(false)
const saveError = ref<string | null>(null)
const savedSummary = ref('')

/**
 * 九个已知类型按枚举序 + 枚举外的历史类型（排在后面，不消失）。
 *
 * 用固定词汇而不是 `types()` 的键来铺卡片：`types()` 只含记过的类型，若按它铺，
 * 没记过的围度会**整张卡片消失**——而「这个类型还没记过」本身是要显示的信息。
 */
const cards = computed<Card[]>(() => {
  const recorded = new Map(summaries.value.map((s) => [s.metricType, s]))
  const known: Card[] = METRIC_TYPES.map((t) => {
    const summary = recorded.get(t) ?? null
    return {
      metricType: t,
      label: METRIC_LABELS[t],
      valueText: summary === null ? '—' : String(summary.latestValue),
      unit: summary === null ? '' : summary.unit,
      meta: summary === null ? '还没记过' : `${summary.latestOn} · ${summary.count} 条`,
    }
  })
  const extra: Card[] = summaries.value
    .filter((s) => !(METRIC_TYPES as readonly string[]).includes(s.metricType))
    .map((s) => ({
      metricType: s.metricType,
      label: s.metricType,
      valueText: String(s.latestValue),
      unit: s.unit,
      meta: `${s.latestOn} · ${s.count} 条`,
    }))
  return [...known, ...extra]
})

/** 后端按日期升序，界面「最近的在最上面」——倒过来是页面的责任。 */
const timeline = computed(() => [...metrics.value].reverse())

/** 输入框旁边显示的单位：该类型的默认单位，也是后端会填的那个。 */
const unitHint = computed(() => METRIC_DEFAULT_UNITS[formType.value])

const isEmpty = computed(() => summaries.value.length === 0 && metrics.value.length === 0)

async function loadTypes() {
  try {
    summaries.value = await types()
    typesError.value = null
  } catch (e) {
    // **不显示成空列表**：空列表会让人以为「一条都没记过」，而真因是取数失败。
    summaries.value = []
    typesError.value = reason(e)
  }
}

async function loadTimeline() {
  try {
    metrics.value = await listMetrics()
    metricsError.value = null
  } catch (e) {
    metrics.value = []
    metricsError.value = reason(e)
  }
}

async function loadAll() {
  await Promise.all([loadTypes(), loadTimeline()])
}

/** 最轻的一层拦截。返回 null 表示可以发给后端（它仍然可能拒绝）。 */
function valueProblem(text: string): string | null {
  const t = text.trim()
  if (t === '') return '填一个数值'
  const n = Number(t)
  if (!Number.isFinite(n)) return '数值必须是数字'
  if (n < 0) return '数值不能为负'
  return null
}

/** 可选文本：空白 → null（后端接受 null；空串和 null 未必是同一个意思，别替它决定）。 */
function noteOrNull(text: string): string | null {
  const t = text.trim()
  return t === '' ? null : t
}

async function save() {
  saveError.value = null
  savedSummary.value = ''

  const problem = valueProblem(valueText.value)
  if (problem !== null) {
    saveError.value = problem
    return
  }

  const value = Number(valueText.value.trim())
  saving.value = true
  try {
    await addMetric({
      measuredOn: measuredOn.value,
      metricType: formType.value,
      value,
      // 单位交给后端按类型补。前端显示的 unitHint 只用于提示。
      unit: null,
      note: noteOrNull(note.value),
    })
    savedSummary.value = `已记录：${METRIC_LABELS[formType.value]} ${value} ${unitHint.value}`
    valueText.value = ''
    note.value = ''
    // 成功后把卡片与时间线都重新取一遍，而不是往本地数组里塞一条：
    // 最新值/条数/排序全由后端算，本地拼一份就是第二套口径。
    await loadAll()
  } catch (e) {
    // **表单不清空**：服务端拒绝时把用户填的东西丢掉是最气人的事
    saveError.value = reason(e)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void loadAll()
})
</script>

<template>
  <section class="page">
    <p class="eyebrow">分析</p>
    <h2>身体数据</h2>

    <!-- 当前值：九个固定卡片；没记过的显示「—」，不是 0 -->
    <p v-if="typesError" class="err">{{ typesError }}</p>
    <p v-else-if="isEmpty && metricsError === null" class="empty">
      还没有记录，从下面开始——记一次体重或围度，卡片与时间线就会出现在这里。
    </p>

    <ul class="cards">
      <li
        v-for="card in cards"
        :key="card.metricType"
        class="metric-card"
        :data-metric="card.metricType"
      >
        <span class="label">{{ card.label }}</span>
        <span class="metric-value">
          {{ card.valueText }}
          <span v-if="card.unit !== ''" class="metric-unit">{{ card.unit }}</span>
        </span>
        <span class="metric-meta">{{ card.meta }}</span>
      </li>
    </ul>

    <h3>时间线</h3>
    <p v-if="metricsError" class="err">{{ metricsError }}</p>
    <table v-else-if="timeline.length > 0" class="timeline">
      <thead>
        <tr>
          <th>日期</th>
          <th>指标</th>
          <th>数值</th>
          <th>备注</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="m in timeline" :key="m.id" class="timeline-row">
          <td class="date">{{ m.measuredOn }}</td>
          <td class="kind">{{ metricLabel(m.metricType) }}</td>
          <td class="value">{{ m.value }} <span class="unit">{{ m.unit }}</span></td>
          <td class="note">{{ m.note ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
    <p v-else class="dim">记录过的测量会出现在这里。</p>

    <h3>记一条</h3>
    <div class="fields">
      <label class="field">
        <span class="label">指标</span>
        <select v-model="formType" class="metric-type">
          <option v-for="t in METRIC_TYPES" :key="t" :value="t">{{ METRIC_LABELS[t] }}</option>
        </select>
      </label>
      <label class="field">
        <span class="label">日期</span>
        <input v-model="measuredOn" class="measured-on" type="date" />
      </label>
      <label class="field">
        <span class="label">数值</span>
        <span class="value-row">
          <input v-model="valueText" class="value-input" inputmode="decimal" />
          <span class="unit-hint">{{ unitHint }}</span>
        </span>
      </label>
      <label class="field grow">
        <span class="label">备注</span>
        <input v-model="note" class="note-input" placeholder="早上空腹" />
      </label>
    </div>

    <p class="hint">
      单位由后端按指标补：体重 kg、体脂 %、围度 cm，不用手填。日期默认今天，可以改成补录的那天。
    </p>
    <div class="save-row">
      <button type="button" class="save" :disabled="saving" @click="save">记下来</button>
    </div>

    <p v-if="saveError" class="err save-error">{{ saveError }}</p>
    <p v-if="savedSummary" class="ok">{{ savedSummary }}</p>
  </section>
</template>

<style scoped>
.page { padding: var(--gap-xl); max-width: 900px; }

h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }
h3 { font-size: 16px; margin: var(--gap-lg) 0 var(--gap-sm); }

.empty { color: var(--text-dim); margin: 0 0 var(--gap-lg); }
.dim { color: var(--text-dim); }

/* 当前值：靠描边与间距分层，不用阴影 */
.cards {
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: var(--gap-md);
  margin: var(--gap-md) 0 var(--gap-lg);
  padding: 0;
}
.metric-card {
  display: flex;
  flex-direction: column;
  gap: var(--gap-xs);
  min-width: 118px;
  padding: var(--gap-md);
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
}
.metric-value {
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.metric-unit { color: var(--text-dim); font-size: 12px; font-weight: 400; }
.metric-meta { color: var(--text-dim); font-size: 11px; }

.timeline { width: 100%; border-collapse: collapse; margin-top: var(--gap-sm); }
.timeline th {
  font-size: 11px;
  color: var(--eyebrow);
  text-align: left;
  text-transform: uppercase;
  letter-spacing: var(--eyebrow-tracking);
  padding: 0 var(--gap-md) var(--gap-xs) 0;
  border-bottom: var(--line-width) solid var(--line);
}
.timeline-row td {
  padding: 7px var(--gap-md) 7px 0;
  border-bottom: var(--line-width) solid var(--line);
}
.date, .value { font-variant-numeric: tabular-nums; }
.kind, .note, .unit { color: var(--text-dim); }

.fields { display: flex; gap: var(--gap-lg); flex-wrap: wrap; align-items: flex-end; }
.field { display: flex; flex-direction: column; gap: var(--gap-xs); }
.field.grow { flex: 1 1 200px; }

.label {
  font-size: 11px;
  color: var(--eyebrow);
  text-transform: uppercase;
  letter-spacing: var(--eyebrow-tracking);
}

select, input {
  font: inherit;
  color: inherit;
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  padding: 5px 8px;
}

.value-row { display: flex; align-items: baseline; gap: var(--gap-xs); }
.value-input { width: 96px; font-variant-numeric: tabular-nums; }
.unit-hint { color: var(--text-dim); font-size: 12px; }

.hint { color: var(--text-dim); font-size: 12px; margin: var(--gap-sm) 0 0; }
.save-row { margin-top: var(--gap-lg); }
.save { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.save:disabled { opacity: 0.5; cursor: default; }

.err { color: var(--danger); }
.ok { color: var(--ok); }
</style>
