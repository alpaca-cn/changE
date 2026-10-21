<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { searchExercises, type ExerciseSummary } from '../api/training'
import { progress, type ExerciseProgress, type ProgressPoint } from '../api/progress'
import MetricSpark from '../components/MetricSpark.vue'
import { groupByType, type MetricSeries } from './metric-series'
import { listMetrics } from '../api/body'

/**
 * 进步曲线页：选一个动作 → 三条序列各一张小图。
 *
 * <h2>为什么是「三张图」而不是一张图三条线</h2>
 *
 * e1rm 的量级是几十、totalVolume 的量级是几百上千。共享一根 y 轴的话，
 * totalVolume 会把 e1rm 压成贴着底边的一条平线（而且看起来像「没进步」）。
 * 各自的 y 轴范围让每条曲线都占满自己的图，代价是不能一眼比较两条线的高低——
 * 本来也不该比较：它们量的不是同一件事。
 *
 * <h2>为什么手绘 SVG，不引图表库</h2>
 *
 * 一条折线 + 一根网格线，用 `polyline` 就够。引一个图表库会带来 100KB+ 的包体积、
 * 一套要跟 `theme.css` 对齐的配置，以及一个会自己决定「null 怎么画」的默认行为——
 * 而 null 怎么画恰好是本页最需要说清楚的地方（见下）。
 *
 * <h2>e1rm 为 null 的那天：断线，不补 0、不跨过去</h2>
 *
 * 后端会为「有训练但估不出 1RM」的日子保留一个点，`e1rm: null`。
 * - 当 0 画 = 图上多一个假的下跌，看起来像退步；
 * - 跨过去连到下一个点 = 画出一条不存在的趋势。
 *
 * 所以折线按「连续的非 null 点」切段：`segments` 可能不止一条 `polyline`。
 * 每段只有 1 个点时 `polyline` 画不出线，所以每个真实点另外画一个圆点——
 * 孤立的点必须看得见，否则「那天有数据但估不出」在图上等于消失。
 *
 * <h2>坐标换算的两个坑</h2>
 *
 * ① SVG 的 y 向下增大：数值越大 y 越小（`HEIGHT - (v-min)/(max-min) * HEIGHT`）。
 *    反过来会画出倒着的曲线，而且不会报错。
 * ② `max === min`（三天同一个重量）时 `(max-min) === 0`：不特判会得到 `NaN`，
 *    `polyline points="NaN,…"` 在浏览器里**静默不画**——没有报错，只有一张空图。
 *    这里把整条线画在中线。
 *
 * <h2>空态与错误态是两件事</h2>
 *
 * 没选动作 → 引导文案；选了但从没练过 → 「还没练过这个动作」（不是错误、不是红色）；
 * 动作不存在（404）/ 传输失败 → 错误提示 + 后端或外壳的那句话。
 */

const WIDTH = 320
const HEIGHT = 100
const PAD = 8

function reason(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function displayName(ex: { nameEn: string; nameZh: string | null }): string {
  // nameZh 可以为 null（手工录入的动作）：读路径回退到 nameEn，不显示空白标题
  return ex.nameZh || ex.nameEn
}

function round2(n: number): number {
  return Math.round(n * 100) / 100
}

/** 刻度文字：整数不带小数点，其余保留两位。 */
function tick(n: number): string {
  return String(round2(n))
}

interface SeriesSpec {
  key: 'e1rm' | 'maxWeight' | 'totalVolume'
  name: string
  unit: string
}

/**
 * 三条序列的固定顺序与显示名。
 *
 * 「总容量」在这里是**吨位**（Σ 重量×次数），与周容量那套「组数」是两件事——
 * 页面上那句 hint 必须说清楚，否则同一个中文词会被读成另一个数。
 */
const SERIES: SeriesSpec[] = [
  { key: 'e1rm', name: '估算 1RM', unit: 'kg' },
  { key: 'maxWeight', name: '最大重量', unit: 'kg' },
  { key: 'totalVolume', name: '总容量（吨位）', unit: 'kg' },
]

interface ChartDot {
  date: string
  cx: number
  cy: number
}

interface Chart {
  key: string
  name: string
  unit: string
  /** 有任何一个可画的数值。全 null 的序列要画基线，不是一张空图。 */
  hasData: boolean
  /** 按连续非 null 点切好的折线段；e1rm 中间为 null 时会是两段。 */
  segments: string[]
  dots: ChartDot[]
  maxLabel: string
  minLabel: string
  firstDate: string
  lastDate: string
}

// ---- 动作选择 ----

const query = ref('')
const exercises = ref<ExerciseSummary[]>([])
const exercisesError = ref<string | null>(null)

// ---- 当前曲线 ----

const selected = ref<ExerciseSummary | null>(null)
const data = ref<ExerciseProgress | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

// 连点两个动作时，先发的响应可能后到：用序号丢掉过期的那一份。
let requestSeq = 0
let searchSeq = 0

/**
 * 搜动作。**不防抖**：本机单用户、列表很短，少一层计时器就少一种竞态；
 * 真正的取消逻辑是下面的序号。
 */
async function runSearch() {
  const q = query.value.trim()
  const seq = ++searchSeq
  if (q === '') {
    exercises.value = []
    exercisesError.value = null
    return
  }
  try {
    const found = await searchExercises({ q })
    if (seq !== searchSeq) return
    exercises.value = found
    exercisesError.value = null
  } catch (e) {
    if (seq !== searchSeq) return
    exercises.value = []
    exercisesError.value = reason(e)
  }
}

watch(query, () => {
  void runSearch()
})

async function pick(ex: ExerciseSummary) {
  selected.value = ex
  data.value = null
  error.value = null
  loading.value = true
  const seq = ++requestSeq
  try {
    const result = await progress(ex.id)
    if (seq !== requestSeq) return
    data.value = result
  } catch (e) {
    if (seq !== requestSeq) return
    data.value = null
    error.value = reason(e)
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

const points = computed<ProgressPoint[]>(() => data.value?.points ?? [])
const selectedName = computed(() => (selected.value === null ? '' : displayName(selected.value)))
const hasPoints = computed(() => points.value.length > 0)

/**
 * 把一条序列换算成 SVG 坐标。
 *
 * x 用**点的序号**而不是真实日期间隔：训练日之间的间隔不固定，按日期铺开会让
 * 一段停练期在图上占掉半个宽度；这里横轴是「第几次训练」，首尾日期标出来说明它代表哪天。
 */
function buildChart(spec: SeriesSpec): Chart {
  const pts = points.value
  const base: Chart = {
    key: spec.key,
    name: spec.name,
    unit: spec.unit,
    hasData: false,
    segments: [],
    dots: [],
    maxLabel: '',
    minLabel: '',
    firstDate: pts.length > 0 ? pts[0].date : '',
    lastDate: pts.length > 0 ? pts[pts.length - 1].date : '',
  }
  if (pts.length === 0) return base

  const values = pts.map((point) => point[spec.key])
  // 运行时不信任类型断言：字段改名/缺失时这里拿到的是 undefined，不是 null。
  // 若把 undefined 当数值，min/max 会变 NaN，整张图静默不画——降级成基线比空图诚实。
  const numeric = values.filter(
    (value): value is number => typeof value === 'number' && Number.isFinite(value),
  )
  if (numeric.length === 0) return base

  const min = Math.min(...numeric)
  const max = Math.max(...numeric)

  const innerWidth = WIDTH - PAD * 2
  const innerHeight = HEIGHT - PAD * 2
  const xOf = (index: number): number =>
    pts.length === 1 ? WIDTH / 2 : PAD + (index / (pts.length - 1)) * innerWidth
  // 坑①：SVG 的 y 向下增大，所以数值越大 y 越小
  const yOf = (value: number): number =>
    // 坑②：max === min 时除以 0 → NaN → SVG 静默不画。全部放中线
    max === min ? HEIGHT / 2 : HEIGHT - PAD - ((value - min) / (max - min)) * innerHeight

  const segments: string[] = []
  const dots: ChartDot[] = []
  let current: string[] = []

  pts.forEach((point, index) => {
    const value = values[index]
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      // 断线：结束当前段，下一个非 null 点开新段。**不补 0、不跨过去。**
      if (current.length > 0) {
        segments.push(current.join(' '))
        current = []
      }
      return
    }
    const cx = round2(xOf(index))
    const cy = round2(yOf(value))
    current.push(`${cx},${cy}`)
    dots.push({ date: point.date, cx, cy })
  })
  if (current.length > 0) segments.push(current.join(' '))

  return {
    ...base,
    hasData: true,
    segments,
    dots,
    maxLabel: tick(max),
    minLabel: tick(min),
  }
}

const charts = computed<Chart[]>(() => SERIES.map((spec) => buildChart(spec)))

/**
 * 身体数据。与上面的动作曲线**并列，不是同一件事**：
 * 动作曲线回答「这个动作我变强了吗」，这里回答「我的体重/腰围在往哪走」。
 *
 * 两者共用一页但不共用一个选择器——如果只给一个「选择看什么」的下拉，
 * 用户得先决定自己想看哪类才能开始，而这两类问题的触发时机完全不同
 * （想看动作是练完，想看体重是称完）。
 */
const metricSeries = ref<MetricSeries[]>([])
const metricsError = ref<string | null>(null)

async function loadMetrics() {
  try {
    metricSeries.value = groupByType(await listMetrics())
    metricsError.value = null
  } catch (e) {
    // 身体数据挂了不该把动作曲线也拖成空白
    metricSeries.value = []
    metricsError.value = e instanceof Error ? e.message : String(e)
  }
}

onMounted(() => {
  void loadMetrics()
})
</script>

<template>
  <section class="page">
    <p class="eyebrow">分析</p>
    <h2>进步曲线</h2>

    <div class="picker">
      <label class="field grow">
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
          :class="{ picked: selected?.id === ex.id }"
          @click="pick(ex)"
        >
          <span class="ex-name">{{ displayName(ex) }}</span>
        </button>
      </li>
    </ul>

    <p v-if="selected === null" class="guide">
      选一个动作看走势——在上面的搜索框里输入中文名或英文名，点一下结果。
    </p>
    <p v-else-if="loading" class="dim">正在取曲线…</p>
    <p v-else-if="error" class="err">{{ error }}</p>
    <p v-else-if="!hasPoints" class="empty">
      还没练过这个动作。先记一次训练，这里就会出现曲线。
    </p>

    <template v-else>
      <h3 class="subject">{{ selectedName }}</h3>
      <p class="hint">
        每个点是一个训练日，横轴按训练先后排列。估算 1RM 取每天最好的一组；总容量是吨位
        （重量×次数之和），不是组数。某天估不出 1RM 时那条线会断开，不补 0。
      </p>

      <div class="charts">
        <figure v-for="chart in charts" :key="chart.key" class="chart" :data-series="chart.key">
          <figcaption class="chart-name">
            {{ chart.name }}
            <span class="chart-unit">{{ chart.unit }}</span>
          </figcaption>

          <template v-if="chart.hasData">
            <div class="plot">
              <div class="y-axis">
                <span class="y-max">{{ chart.maxLabel }}</span>
                <span class="y-min">{{ chart.minLabel }}</span>
              </div>
              <svg class="chart-svg" viewBox="0 0 320 100" role="img" :aria-label="chart.name">
                <line class="grid" x1="0" y1="8" x2="320" y2="8" />
                <line class="grid" x1="0" y1="50" x2="320" y2="50" />
                <line class="grid" x1="0" y1="92" x2="320" y2="92" />
                <polyline
                  v-for="(segment, i) in chart.segments"
                  :key="`${chart.key}-seg-${i}`"
                  class="series-line"
                  :points="segment"
                  fill="none"
                  stroke="var(--accent)"
                  stroke-width="2"
                  stroke-linejoin="round"
                  stroke-linecap="round"
                />
                <circle
                  v-for="dot in chart.dots"
                  :key="`${chart.key}-${dot.date}`"
                  class="dot"
                  :cx="dot.cx"
                  :cy="dot.cy"
                  r="2.5"
                  fill="var(--accent)"
                />
              </svg>
            </div>
            <div class="x-axis">
              <span class="x-first">{{ chart.firstDate }}</span>
              <span v-if="chart.lastDate !== chart.firstDate" class="x-last">{{ chart.lastDate }}</span>
            </div>
          </template>

          <!-- 没有可画的点：一条基线 + 文案，不是一张空图 -->
          <div v-else class="chart-empty">
            <div class="baseline"></div>
            <p class="dim">这条序列还没有数据。</p>
          </div>
        </figure>
      </div>
    </template>

    <!-- 身体数据：独立的一块，不随上面选的动作变化 -->
    <section class="body-metrics">
      <h3>身体数据</h3>
      <p v-if="metricsError" class="err">{{ metricsError }}</p>
      <p v-else-if="metricSeries.length === 0" class="dim">
        还没有身体数据。去「身体数据」页记一次体重，这里就会出现走势。
      </p>
      <div v-else class="metric-grid">
        <MetricSpark v-for="s in metricSeries" :key="s.metricType" :series="s" />
      </div>
    </section>
  </section>
</template>

<style scoped>
.page { padding: var(--gap-xl); max-width: 900px; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }
h3 { font-size: 16px; margin: var(--gap-lg) 0 var(--gap-sm); }

.picker { display: flex; gap: var(--gap-lg); flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: var(--gap-xs); }
.field.grow { flex: 1 1 280px; }
.label {
  font-size: 11px;
  color: var(--eyebrow);
  text-transform: uppercase;
  letter-spacing: var(--eyebrow-tracking);
}

input {
  font: inherit;
  color: inherit;
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  padding: 5px 8px;
}

.exercises {
  list-style: none;
  margin: var(--gap-md) 0 0;
  padding: 0;
  border-top: var(--line-width) solid var(--line);
}
.exercise {
  display: flex;
  width: 100%;
  text-align: left;
  background: none;
  border: none;
  border-bottom: var(--line-width) solid var(--line);
  border-radius: 0;
  padding: 7px 0;
}
.exercise.picked .ex-name { color: var(--accent); font-weight: 700; }
.ex-name { font-weight: 600; }

.guide { color: var(--text-dim); margin: var(--gap-lg) 0 var(--gap-md); }
.subject { margin-bottom: var(--gap-xs); }
.hint { color: var(--text-dim); font-size: 12px; margin: 0 0 var(--gap-md); }
.dim { color: var(--text-dim); }
.empty { color: var(--text-dim); margin: var(--gap-lg) 0 var(--gap-md); }

/* 靠描边与间距分层，不用阴影 */
.charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--gap-md);
  margin-top: var(--gap-md);
}
.chart {
  margin: 0;
  padding: var(--gap-md);
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
}
.chart-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
  margin-bottom: var(--gap-sm);
}
.chart-unit { color: var(--text-dim); font-size: 10px; font-weight: 400; }

.plot { display: flex; gap: var(--gap-xs); align-items: stretch; }
.y-axis {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 3ch;
  text-align: right;
  color: var(--text-dim);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}
.body-metrics { margin-top: var(--gap-xl); }
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--gap-lg);
  margin-top: var(--gap-md);
}
.err { color: var(--danger); }

.chart-svg { display: block; width: 100%; height: auto; aspect-ratio: 320 / 100; }
.grid { stroke: var(--line); stroke-width: 1; }
.dot { stroke: none; }

.x-axis {
  display: flex;
  justify-content: space-between;
  color: var(--text-dim);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  padding-left: calc(3ch + var(--gap-xs));
  margin-top: var(--gap-xs);
}

.chart-empty { display: flex; flex-direction: column; gap: var(--gap-sm); justify-content: center; min-height: 90px; }
.baseline { height: var(--line-width); background: var(--line); }
.chart-empty .dim { font-size: 12px; margin: 0; }

.err { color: var(--danger); }
</style>
