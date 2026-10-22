<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  MEAL_SLOTS,
  SLOT_LABELS,
  dailyNutrition,
  mealsOn,
  recordMeal,
  searchFoods,
  type DailyNutrition,
  type FoodSummary,
  type MealDetail,
  type MealSlot,
} from '../api/nutrition'
import CustomFoodForm from '../components/CustomFoodForm.vue'
import { todayIso } from './training-form'
import {
  draftGrams,
  draftItem,
  draftNutrition,
  draftTotal,
  toMealInput,
  validationError,
  type DraftItem,
} from './nutrition-form'

/**
 * 饮食记录页：记一餐（设计文档流程②里**完全本地**的那一半）。
 *
 *   搜食物 → 选份量（碗/个/…或直接按克）→ 加条目 → 保存 → 写入 Meal + MealItem
 *
 * 拍照 / 外卖截图 / 说话出草稿那三条入口是**计划 4b**，要模型与 API key。
 * 这一页不依赖网络（设计文档的核心原则：模型不可用时本地记录照常可用）。
 *
 * 界面上有两句话必须写出来，否则这两条口径没人知道：
 * ① 每 100 克是**食物属性**，你填的是**这一次的份量**；
 * ② 表里那个热量是**预览**，保存后显示的是**后端算**的。
 */

/** 输入搜索的防抖，与训练记录页同一个数。 */
const SEARCH_DEBOUNCE_MS = 300

function reason(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

const mealDate = ref(todayIso())
const slot = ref<MealSlot>('lunch')
const note = ref('')
const query = ref('')
const foods = ref<FoodSummary[]>([])
const foodsError = ref<string | null>(null)
const draft = ref<DraftItem[]>([])

/** 是否展开「新建食物」。默认收起：搜索是主路径，建食物是兜底。 */
const showNewFood = ref(false)
const saving = ref(false)
const saveError = ref<string | null>(null)
const savedSummary = ref<string | null>(null)

const daily = ref<DailyNutrition | null>(null)
const dailyError = ref<string | null>(null)
const meals = ref<MealDetail[]>([])

const preview = computed(() => draftTotal(draft.value))

/**
 * 把这一天已记录的餐按**餐次**分组，并按固定顺序排列。
 *
 * 为什么按餐次而不是按时间：一天里最重要的判断是「哪一餐还没记」，
 * 而时段顺序（早→午→晚→加餐）就是用户脑子里的顺序。缺的餐次也要占一行，
 * 否则「晚餐没记」这件事在界面上不存在。
 */
const mealsBySlot = computed(() => {
  return MEAL_SLOTS.map((s) => {
    const rows = meals.value.filter((m) => m.slot === s)
    return {
      slot: s,
      label: slotLabel(s),
      meals: rows,
      kcal: rows.reduce((n, m) => n + m.nutrition.kcal, 0),
      protein: round1(rows.reduce((n, m) => n + m.nutrition.protein, 0)),
      fat: round1(rows.reduce((n, m) => n + m.nutrition.fat, 0)),
      carb: round1(rows.reduce((n, m) => n + m.nutrition.carb, 0)),
      count: rows.reduce((n, m) => n + m.items.length, 0),
    }
  })
})

/** 一位小数。宏量营养的显示精度，与参考图那种 `141.5` 一致。 */
function round1(v: number): number {
  return Math.round(v * 10) / 10
}

function slotLabel(value: string): string {
  // 不做模板里的 `as` 断言：模板对 TS 语法的支持比脚本窄，一个字符串到远点更稳。
  return (SLOT_LABELS as Record<string, string>)[value] ?? value
}

async function loadDay() {
  try {
    daily.value = await dailyNutrition(mealDate.value)
    dailyError.value = null
  } catch (e) {
    daily.value = null
    dailyError.value = reason(e)
  }
  try {
    meals.value = await mealsOn(mealDate.value)
  } catch {
    // 列表取不到不该把合计也抹掉——合计有它自己的错误位
    meals.value = []
  }
}

let searchTimer: number | undefined
function clearSearchTimer() {
  if (searchTimer !== undefined) {
    window.clearTimeout(searchTimer)
    searchTimer = undefined
  }
}

async function runSearch() {
  const q = query.value.trim()
  try {
    foods.value = q === '' ? [] : await searchFoods({ q })
    foodsError.value = null
  } catch (e) {
    foods.value = []
    foodsError.value = reason(e)
  }
}

watch(query, () => {
  clearSearchTimer()
  searchTimer = window.setTimeout(() => void runSearch(), SEARCH_DEBOUNCE_MS)
})

// 换日期就把那一天的合计与记录列表重新取一遍。**监听的是 `mealDate` 而不是「今天」**：
// 这一页展示的是「你正在记的那一天」，翻到昨天补记时数字得跟着走。
watch(mealDate, () => {
  savedSummary.value = null
  void loadDay()
})

onUnmounted(clearSearchTimer)

function pick(food: FoodSummary) {
  draft.value.push(draftItem(food))
  query.value = ''
  foods.value = []
  saveError.value = null
  savedSummary.value = null
}

function removeItem(index: number) {
  draft.value.splice(index, 1)
}

/**
 * 新建食物成功后：把它塞进搜索结果首行，并把搜索框填成它的名字。
 *
 * 用户刚建完，下一步一定是「用它」——让他再搜一次是纯浪费。
 */
function onFoodCreated(food: FoodSummary) {
  showNewFood.value = false
  query.value = food.nameZh
  foods.value = [food, ...foods.value.filter((f) => f.id !== food.id)]
  foodsError.value = null
}

async function save() {
  saveError.value = null
  savedSummary.value = null

  const problem = validationError(mealDate.value, draft.value)
  if (problem !== null) {
    saveError.value = problem
    return
  }

  saving.value = true
  try {
    const result = await recordMeal(
      toMealInput({
        mealDate: mealDate.value,
        slot: slot.value,
        eatenAt: null,
        note: note.value,
        items: draft.value,
      }),
    )
    // 显示的合计来自**后端**，不是上面那个预览
    savedSummary.value = `已记录：${result.items} 个条目 · ${result.nutrition.kcal} 千卡`
    draft.value = []
    note.value = ''
    await loadDay()
  } catch (e) {
    // **表单不清空**：服务端拒绝时把用户填的东西丢掉是最气人的事
    saveError.value = reason(e)
  } finally {
    saving.value = false
  }
}

function gramsText(item: DraftItem): string {
  const grams = draftGrams(item)
  return grams === null ? '—' : `${grams} 克`
}

function previewText(item: DraftItem): string {
  const n = draftNutrition(item)
  return n === null ? '—' : `${n.kcal} 千卡`
}

onMounted(() => {
  void loadDay()
})
</script>

<template>
  <section class="page">
    <p class="eyebrow">记录</p>
    <h2>饮食记录</h2>

    <!--
      顶部信息带：一天的「总量」放最显眼。参考图里那一大块就是这个作用——
      用户打开饮食页只想知道两件事：今天吃了多少、还差多少。
      （我们没有「目标热量」这个概念，所以只给已吃与构成，不编一个目标出来。）
    -->
    <p v-if="dailyError" class="err">{{ dailyError }}</p>
    <div v-if="!dailyError" class="stat-band">
      <div class="stat-main">
        <div class="kcal">
          {{ round1(daily?.nutrition.kcal ?? 0) }} <small>千卡</small>
        </div>
        <div class="sub">
          {{ daily?.meals ?? 0 }} 餐 · {{ daily?.items ?? 0 }} 条目
        </div>
      </div>
      <div class="stat-macros">
        <div class="macro"><b>{{ round1(daily?.nutrition.protein ?? 0) }}</b><span>蛋白 g</span></div>
        <div class="macro"><b>{{ round1(daily?.nutrition.fat ?? 0) }}</b><span>脂肪 g</span></div>
        <div class="macro"><b>{{ round1(daily?.nutrition.carb ?? 0) }}</b><span>碳水 g</span></div>
      </div>
    </div>

    <div class="fields">
      <label class="field">
        <span class="label">日期</span>
        <input v-model="mealDate" class="meal-date" type="date" />
      </label>
      <label class="field">
        <span class="label">餐次</span>
        <select v-model="slot" class="slot">
          <option v-for="s in MEAL_SLOTS" :key="s" :value="s">{{ SLOT_LABELS[s] }}</option>
        </select>
      </label>
      <label class="field grow">
        <span class="label">搜食物</span>
        <input v-model="query" class="query" type="search" placeholder="中文名或英文名" />
      </label>
    </div>

    <p v-if="foodsError" class="err">{{ foodsError }}</p>
    <ul v-else-if="foods.length > 0" class="foods">
      <li v-for="f in foods" :key="f.id">
        <button type="button" class="food" @click="pick(f)">
          <span class="food-name">{{ f.nameZh }}</span>
          <span class="food-meta">每 100 克 {{ f.per100.kcal }} 千卡</span>
        </button>
      </li>
    </ul>

    <!--
      **搜不到时必须说话。** 食物库只有 35 条，用户搜「包子」这类不在库里的词会拿到
      空数组——之前这里什么都不显示，于是他看到的是「输入了没反应」，
      接着以为「保存不了」。空结果是最容易让人放弃的失败形态。
    -->
    <p v-else-if="query.trim() !== ''" class="no-hit">
      没找到「{{ query }}」。食物库还很小，换个名字再试；或者去左边的
      <b>智能体对话</b>（07）说一句，让它按库里的食物折算。
    </p>

    <p v-if="draft.length === 0 && query.trim() === '' && foods.length === 0" class="how">
      搜到食物之后，<b>点一下那一行</b>就加进来了。
    </p>

    <!-- 草稿 -->
    <template v-if="draft.length > 0">
      <table class="draft">
        <thead>
          <tr>
            <th>食物</th><th>份量</th><th>单位</th><th>折合</th><th>预览</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(item, i) in draft" :key="item.id" class="draft-row">
            <td class="name">{{ item.food.nameZh }}</td>
            <td><input v-model="item.amount" class="amount" inputmode="decimal" /></td>
            <td>
              <select v-model="item.unitName" class="unit">
                <option :value="null">克</option>
                <option v-for="p in item.food.portions" :key="p.unitName" :value="p.unitName">
                  {{ p.unitName }}
                </option>
              </select>
            </td>
            <td class="grams">{{ gramsText(item) }}</td>
            <td class="preview">{{ previewText(item) }}</td>
            <td><button type="button" class="remove" @click="removeItem(i)">删除</button></td>
          </tr>
        </tbody>
      </table>

      <p class="hint">每 100 克是食物属性，你填的是这一次的份量。</p>
      <p class="total">
        预览合计 {{ preview.kcal }} 千卡 —— 保存后显示的是后端算的数字（可能差最后一两位小数）。
      </p>

      <label class="field">
        <span class="label">备注</span>
        <input v-model="note" class="note" />
      </label>

      <div class="save-row">
        <button type="button" class="save" :disabled="saving" @click="save">保存这一餐</button>
      </div>
    </template>

    <p v-if="saveError" class="err">{{ saveError }}</p>
    <p v-if="savedSummary" class="ok">{{ savedSummary }}</p>

    <!--
      搜不到就自己建。位置刻意放在「搜食物」之后、记录之前：
      用户是**在搜不到的那一刻**才需要它，而不是在打开页面时。
    -->
    <div class="new-food-entry">
      <button type="button" class="new-food-toggle" @click="showNewFood = !showNewFood">
        {{ showNewFood ? '收起' : '搜不到？自己建一个' }}
      </button>
    </div>
    <CustomFoodForm v-if="showNewFood" @created="onFoodCreated" />

    <h3>这一天的记录</h3>
    <p v-if="meals.length === 0" class="dim">这一天还没有记录</p>

    <!--
      四个餐次**全部渲染**，没记的那一餐也占一行并注明「还没记」。
      只列出有数据的餐次，等于把「晚餐忘了记」这件事从界面上抹掉 ——
      而那正是记饮食最需要被提醒的一类事实。
    -->
    <div v-else class="slots">
      <section
        v-for="g in mealsBySlot"
        :key="g.slot"
        class="slot-card"
        :class="{ empty: g.count === 0 }"
      >
        <header class="slot-head">
          <span class="slot-name">{{ g.label }}</span>
          <span v-if="g.count > 0" class="chips">
            <span class="chip-m">P {{ g.protein }}</span>
            <span class="chip-m">F {{ g.fat }}</span>
            <span class="chip-m">C {{ g.carb }}</span>
            <span class="chip-kcal">{{ round1(g.kcal) }} kcal</span>
          </span>
          <span v-else class="not-yet">还没记</span>
        </header>

        <ul v-if="g.count > 0" class="items">
          <template v-for="meal in g.meals" :key="meal.id">
            <li v-for="it in meal.items" :key="it.mealItemId" class="item">
              <span class="i-name">{{ it.nameZh }}</span>
              <span class="i-amount">{{ it.grams }} 克</span>
              <span class="i-macros">
                P {{ round1(it.nutrition.protein) }} · F {{ round1(it.nutrition.fat) }} ·
                C {{ round1(it.nutrition.carb) }}
              </span>
              <span class="i-kcal">{{ round1(it.nutrition.kcal) }}</span>
            </li>
          </template>
        </ul>
      </section>
    </div>
  </section>
</template>

<style scoped>
.page { padding: var(--gap-xl); max-width: 900px; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }
h3 { font-size: 16px; margin: var(--gap-lg) 0 var(--gap-sm); }

.daily { display: flex; align-items: baseline; gap: var(--gap-md); margin-bottom: var(--gap-lg); }
.kcal { font-size: 26px; font-weight: var(--title-weight); letter-spacing: var(--title-tracking); }
.macros { color: var(--text-dim); font-size: 12px; }
.counts { margin-left: auto; color: var(--text-dim); font-size: 12px; }

.fields { display: flex; gap: var(--gap-lg); flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: var(--gap-xs); }
.field.grow { flex: 1 1 220px; }
.label {
  font-size: 11px; color: var(--eyebrow);
  text-transform: uppercase; letter-spacing: var(--eyebrow-tracking);
}

select, input {
  font: inherit; color: inherit; background: var(--panel);
  border: var(--line-width) solid var(--line); border-radius: var(--radius); padding: 5px 8px;
}

.foods { list-style: none; margin: var(--gap-md) 0 0; padding: 0; border-top: var(--line-width) solid var(--line); }
.food {
  display: flex; justify-content: space-between; gap: var(--gap-md); width: 100%;
  text-align: left; background: none; border: none; border-radius: 0;
  border-bottom: var(--line-width) solid var(--line); padding: 7px 0;
}
.food:hover .food-name { color: var(--accent); }
.food-name { font-weight: 600; }
.food-meta { color: var(--text-dim); font-size: 12px; }

.draft { border-collapse: collapse; margin-top: var(--gap-lg); }
.draft th {
  font-size: 11px; color: var(--eyebrow); text-align: left;
  text-transform: uppercase; letter-spacing: var(--eyebrow-tracking); padding-right: var(--gap-md);
}
.draft-row td { padding: 3px var(--gap-md) 3px 0; }
.amount { width: 70px; }
.grams, .preview { font-variant-numeric: tabular-nums; }
.preview { color: var(--text-dim); }
.remove { padding: 2px 8px; font-size: 12px; }

.hint, .total { color: var(--text-dim); font-size: 12px; margin: var(--gap-sm) 0 0; }
.save-row { margin-top: var(--gap-lg); }
.save { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.save:disabled { opacity: 0.5; cursor: default; }

.meals { list-style: none; margin: 0; padding: 0; border-top: var(--line-width) solid var(--line); }
.meal {
  display: grid; grid-template-columns: 60px 90px 1fr; gap: var(--gap-md);
  padding: 7px 0; border-bottom: var(--line-width) solid var(--line);
}
.slot-label { color: var(--text-dim); }
.meal-kcal { font-variant-numeric: tabular-nums; font-weight: 600; }
.meal-items { color: var(--text-dim); font-size: 12px; }

.stat-band {
  display: flex;
  align-items: flex-end;
  gap: var(--gap-lg);
  margin: var(--gap-md) 0 var(--gap-lg);
  padding-bottom: var(--gap-md);
  border-bottom: var(--line-width) solid var(--line);
}
.kcal {
  font-size: 44px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
.kcal small { font-size: 13px; font-weight: 400; color: var(--eyebrow); margin-left: 6px; }
.sub { margin-top: 6px; font-size: 11px; color: var(--text-dim); }
.stat-macros { display: flex; gap: var(--gap-lg); margin-left: auto; }
.macro { text-align: right; }
.macro b {
  display: block;
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.macro span { font-size: 10px; color: var(--eyebrow); }

.slots { display: flex; flex-direction: column; gap: var(--gap-sm); }
.slot-card {
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
}
.slot-card.empty { border-style: dashed; }
.slot-head { display: flex; align-items: baseline; gap: var(--gap-sm); }
.slot-name { font-weight: 600; font-size: 13px; }
.chips { display: flex; gap: 6px; margin-left: auto; align-items: baseline; }
.chip-m {
  font-size: 10px;
  color: var(--text-dim);
  background: var(--bg);
  border-radius: 4px;
  padding: 1px 6px;
  font-variant-numeric: tabular-nums;
}
.chip-kcal {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.not-yet { margin-left: auto; font-size: 11px; color: var(--eyebrow); }

.items { list-style: none; margin: 8px 0 0; padding: 0; }
.item {
  display: grid;
  grid-template-columns: 1fr auto auto auto;
  gap: var(--gap-md);
  align-items: baseline;
  padding: 4px 0;
  font-size: 12px;
  border-top: var(--line-width) solid var(--line-soft);
}
.i-name { font-weight: 500; }
.i-amount { color: var(--text-dim); font-variant-numeric: tabular-nums; }
.i-macros { color: var(--eyebrow); font-size: 10px; font-variant-numeric: tabular-nums; }
.i-kcal { color: var(--text); font-variant-numeric: tabular-nums; }

.new-food-entry { margin: var(--gap-md) 0 0; }
.new-food-toggle {
  font-size: 11px;
  color: var(--accent);
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  text-decoration: underline dotted;
}

.err { color: var(--danger); }
.no-hit {
  margin: var(--gap-md) 0;
  padding: var(--gap-md);
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-dim);
  border: var(--line-width) dashed var(--line);
  border-radius: var(--radius-sm);
}
.how { margin: var(--gap-md) 0; font-size: 12px; color: var(--eyebrow); }
.ok { color: var(--ok); }
.dim { color: var(--text-dim); }
</style>
