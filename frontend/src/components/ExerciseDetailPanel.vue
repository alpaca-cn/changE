<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ExerciseDetail } from '../api/exercise-detail'

/**
 * 动作详情面板：点「看做法」后从右侧滑出的抽屉。
 *
 * <h2>为什么是纯展示组件</h2>
 *
 * 数据由 `TrainingView` 拉（打开时 `void exerciseDetail(id)`），这个组件只接收
 * `detail` / `loading` / `error` 三个 prop 并渲染。这样：
 * - 组件测试不需要 mock `invoke`，拿一个对象就能测三种数据形态；
 * - 「用哪个 id、什么时候拉、连点时怎么防串台」留在页面里，不散进组件。
 *
 * <h2>为什么不用 `<a href>`</h2>
 *
 * Tauri 的 WebView 里点一个 `<a href>` 会把**整个应用**导航走（页面变空白），
 * 除非另装 opener。本计划明确不增加外壳能力，所以搜索地址只显示成
 * `<code>` 文本 + 复制按钮，不渲染任何 `<a>`。
 *
 * <h2>复制失败不静默</h2>
 *
 * `navigator.clipboard` 在 Tauri WebView 里可能不可用（取决于安全上下文），
 * 写入也可能被拒。两条路径都落到 `copy-fallback`：把完整 URL 放进一个
 * 只读输入框让人手选。静默失败是最差的结果——用户点了复制、什么都没发生、
 * 他不知道该怪谁。
 *
 * <h2>中文做法按 `\n` 切段</h2>
 *
 * 后端给的是拼好的字符串（`段1\n段2\n…`），一个换行就是一步。按 `\n` 切是
 * 唯一不需要猜的规则：如果某条译文的一段内部带了换行（真实数据里有），
 * 它会显示成两步而不是一步。这与「把两步合并成一步」相比是更小的错——
 * 合并会让步骤号与内容错位，而错位在界面上看不出来。
 */

const props = withDefaults(
  defineProps<{
    detail: ExerciseDetail | null
    loading?: boolean
    error?: string | null
  }>(),
  { loading: false, error: null },
)

const emit = defineEmits<{ (e: 'close'): void }>()

/** 刚复制成功的那个 URL（按 URL 区分，不按按钮）。 */
const copiedUrl = ref<string | null>(null)
/** 复制失败的那个 URL：界面要把它显式显示出来让人手选。 */
const failedUrl = ref<string | null>(null)

function displayName(d: ExerciseDetail): string {
  // nameZh 可以为 null（手工录入的动作）：标题退回 nameEn，不留空白。
  return d.nameZh !== null && d.nameZh.trim() !== '' ? d.nameZh : d.nameEn
}

const heading = computed(() => (props.detail === null ? '动作做法' : displayName(props.detail)))

/**
 * 把一段做法切成步骤。空白行丢掉——空行不是一步，渲染成空 `<li>` 只会多一个空序号。
 * 修掉 `\r`：Windows 换行不该在步骤文字末尾留一个看不见的字符。
 */
function paragraphs(text: string | null): string[] {
  if (text === null) return []
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

const zhSteps = computed(() =>
  props.detail === null ? [] : paragraphs(props.detail.instructionsZh),
)
const enSteps = computed(() => (props.detail === null ? [] : paragraphs(props.detail.instructions)))

const primaryMuscles = computed(
  () => props.detail?.muscles.filter((m) => m.role === 'primary') ?? [],
)
const secondaryMuscles = computed(
  () => props.detail?.muscles.filter((m) => m.role === 'secondary') ?? [],
)
const hasMuscles = computed(
  () => primaryMuscles.value.length > 0 || secondaryMuscles.value.length > 0,
)

interface SearchLink {
  key: string
  label: string
  url: string
}

/**
 * 两条搜索链接。URL 模板是计划里定死的：
 * - B 站搜中文名（没有中文名才用英文名）；
 * - YouTube 搜英文名 + " exercise form"。
 */
const searchLinks = computed<SearchLink[]>(() => {
  const d = props.detail
  if (d === null) return []
  const zh = d.nameZh !== null && d.nameZh.trim() !== '' ? d.nameZh : null
  const bilibiliTerm = zh ?? d.nameEn
  return [
    {
      key: 'bilibili',
      label: `B 站搜索：${bilibiliTerm}`,
      url: `https://search.bilibili.com/all?keyword=${encodeURIComponent(bilibiliTerm)}`,
    },
    {
      key: 'youtube',
      label: `YouTube 搜索：${d.nameEn}`,
      url: `https://www.youtube.com/results?search_query=${encodeURIComponent(`${d.nameEn} exercise form`)}`,
    },
  ]
})

/**
 * 复制到剪贴板。成功 → 一行「已复制」；失败（API 不存在 / 写入被拒）→
 * 把 URL 放进只读输入框让人手选。**不允许静默失败。**
 */
async function copy(url: string) {
  copiedUrl.value = null
  failedUrl.value = null
  try {
    // 运行时 `navigator.clipboard` 可能是 undefined（jsdom 与部分 WebView），
    // 而 TS 的 DOM 类型把它标成必有——所以过一道 `unknown` 再判。
    const clipboard = (navigator as unknown as { clipboard?: Clipboard }).clipboard
    if (clipboard === undefined || typeof clipboard.writeText !== 'function') {
      throw new Error('剪贴板不可用')
    }
    await clipboard.writeText(url)
    copiedUrl.value = url
  } catch {
    failedUrl.value = url
  }
}
</script>

<template>
  <div class="layer">
    <!-- 点背板也关，和「关闭」按钮同一条出口 -->
    <div class="backdrop" @click="emit('close')"></div>

    <aside class="panel" role="dialog" aria-modal="true" aria-label="动作做法">
      <header class="panel-head">
        <div class="panel-heading">
          <p class="eyebrow">动作做法</p>
          <h2 class="panel-title">{{ heading }}</h2>
        </div>
        <button type="button" class="close" @click="emit('close')">关闭</button>
      </header>

      <!-- 三态：加载中 / 失败 / 成功。失败时显示后端那句，不写死「加载失败」。 -->
      <p v-if="loading" class="dim loading">正在取做法…</p>
      <p v-else-if="error !== null" class="err detail-error">{{ error }}</p>
      <p v-else-if="detail === null" class="dim empty-detail">没取到动作详情。</p>

      <template v-else>
        <dl class="facts">
          <div class="fact" data-fact="nameZh">
            <dt>中文名</dt>
            <dd>{{ detail.nameZh ?? '未录入' }}</dd>
          </div>
          <div class="fact" data-fact="nameEn">
            <dt>英文名</dt>
            <dd>{{ detail.nameEn }}</dd>
          </div>
          <div class="fact" data-fact="equipment">
            <dt>器械</dt>
            <dd>{{ detail.equipment ?? '未标注' }}</dd>
          </div>
          <div class="fact" data-fact="category">
            <dt>分类</dt>
            <dd>{{ detail.category ?? '未标注' }}</dd>
          </div>
          <div class="fact" data-fact="type">
            <dt>类型</dt>
            <dd>{{ detail.isCompound ? '复合动作' : '孤立动作' }}</dd>
          </div>
        </dl>

        <!-- 肌群：只渲染有内容的那个分区。空数组（手工动作没标）给一句话。 -->
        <section class="section muscles">
          <h3>目标肌群</h3>
          <p v-if="!hasMuscles" class="dim no-muscles">未标注肌群</p>
          <template v-else>
            <div
              v-if="primaryMuscles.length > 0"
              class="muscle-group muscle-group-primary"
              data-role="primary"
            >
              <h4>主要</h4>
              <ul class="muscle-list">
                <li v-for="m in primaryMuscles" :key="m.muscleGroupId" class="muscle">
                  {{ m.nameZh }}
                  <span class="muscle-en">{{ m.nameEn }}</span>
                </li>
              </ul>
            </div>
            <div
              v-if="secondaryMuscles.length > 0"
              class="muscle-group muscle-group-secondary"
              data-role="secondary"
            >
              <h4>次要</h4>
              <ul class="muscle-list">
                <li v-for="m in secondaryMuscles" :key="m.muscleGroupId" class="muscle">
                  {{ m.nameZh }}
                  <span class="muscle-en">{{ m.nameEn }}</span>
                </li>
              </ul>
            </div>
          </template>
        </section>

        <!-- 中文优先；没有中文才退到英文（并说明为什么是英文）；两个都没有给一句话 -->
        <section class="section instructions">
          <h3>做法</h3>
          <template v-if="zhSteps.length > 0">
            <ol class="steps" data-lang="zh">
              <li v-for="(step, i) in zhSteps" :key="`zh-${i}`" class="step">{{ step }}</li>
            </ol>
          </template>
          <template v-else-if="enSteps.length > 0">
            <p class="notice no-zh">这条还没有中文说明，下面是英文原文。</p>
            <ol class="steps" data-lang="en">
              <li v-for="(step, i) in enSteps" :key="`en-${i}`" class="step">{{ step }}</li>
            </ol>
          </template>
          <p v-else class="dim no-instructions">这条没有做法说明</p>
        </section>

        <!-- 只给文本 + 复制，不渲染 <a>：Tauri WebView 里点链接会把应用导航走 -->
        <section class="section links">
          <h3>去视频网站搜</h3>
          <p class="hint">这里只给地址，不替你打开浏览器——复制后到浏览器里粘贴。</p>
          <ul class="search-links">
            <li
              v-for="link in searchLinks"
              :key="link.key"
              class="search-link"
              :data-link="link.key"
            >
              <span class="link-label">{{ link.label }}</span>
              <div class="link-row">
                <code class="url">{{ link.url }}</code>
                <button
                  type="button"
                  class="copy"
                  :data-copy="link.key"
                  @click="copy(link.url)"
                >
                  复制
                </button>
              </div>
              <div v-if="failedUrl === link.url" class="copy-fallback">
                <p class="fallback-note">复制没成功——请手动选中下面的地址，再复制一次。</p>
                <input class="fallback-url" :value="link.url" readonly />
              </div>
              <p v-else-if="copiedUrl === link.url" class="copied">已复制到剪贴板。</p>
            </li>
          </ul>
        </section>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.layer {
  position: fixed;
  inset: 0;
  z-index: 20;
}

.backdrop {
  position: absolute;
  inset: 0;
  background: var(--bg);
  opacity: 0.72;
}

/* 右侧抽屉：不跳页，已经填好的组还在下面 */
.panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(480px, 94vw);
  overflow-y: auto;
  background: var(--panel);
  border-left: var(--line-width) solid var(--line);
  padding: var(--gap-xl);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--gap-md);
}
.panel-heading {
  display: flex;
  flex-direction: column;
  gap: var(--gap-xs);
}
.panel-title {
  font-size: 22px;
  margin: 0;
}
.close {
  flex: none;
}

.facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--gap-md);
  margin: var(--gap-lg) 0 0;
}
.fact {
  display: flex;
  flex-direction: column;
  gap: var(--gap-xs);
}
.fact dt {
  font-size: 11px;
  color: var(--eyebrow);
  text-transform: uppercase;
  letter-spacing: var(--eyebrow-tracking);
}
.fact dd {
  margin: 0;
}

.section {
  margin-top: var(--gap-lg);
}
.section h3 {
  font-size: 16px;
  margin: 0 0 var(--gap-sm);
}
.section h4 {
  font-size: 12px;
  margin: var(--gap-sm) 0 var(--gap-xs);
  color: var(--text-dim);
}

.muscle-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--gap-sm);
}
.muscle {
  padding: 2px 8px;
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
}
.muscle-en {
  color: var(--text-dim);
  font-size: 11px;
}

.steps {
  margin: 0;
  padding-left: var(--gap-lg);
  display: flex;
  flex-direction: column;
  gap: var(--gap-sm);
}
.step {
  line-height: 1.6;
}

.notice {
  color: var(--warn);
  font-size: 12px;
  margin: 0 0 var(--gap-sm);
}
.hint {
  color: var(--text-dim);
  font-size: 12px;
  margin: 0 0 var(--gap-sm);
}
.no-muscles,
.no-instructions,
.empty-detail {
  color: var(--text-dim);
  margin: 0;
}

.search-links {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--gap-md);
}
.search-link {
  display: flex;
  flex-direction: column;
  gap: var(--gap-xs);
}
.link-label {
  font-size: 12px;
  font-weight: 600;
}
.link-row {
  display: flex;
  align-items: flex-start;
  gap: var(--gap-sm);
}
.url {
  flex: 1 1 auto;
  min-width: 0;
  margin: 0;
  padding: 4px 6px;
  font-size: 11px;
  color: var(--text-dim);
  background: var(--bg);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  word-break: break-all;
}
.copy {
  flex: none;
  font-size: 12px;
  padding: 3px 10px;
}

.copy-fallback {
  display: flex;
  flex-direction: column;
  gap: var(--gap-xs);
  padding: var(--gap-sm);
  border: var(--line-width) solid var(--warn);
  border-radius: var(--radius);
}
.fallback-note {
  margin: 0;
  color: var(--warn);
  font-size: 12px;
}
.fallback-url {
  font: inherit;
  font-size: 12px;
  color: var(--text);
  background: var(--bg);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  padding: 4px 6px;
  width: 100%;
}
.copied {
  margin: 0;
  color: var(--ok);
  font-size: 12px;
}

.dim {
  color: var(--text-dim);
}
.err {
  color: var(--danger);
}
</style>
