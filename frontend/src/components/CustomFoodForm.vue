<script setup lang="ts">
import { computed, ref } from 'vue'
import { createFood, type FoodSummary } from '../api/nutrition'

/**
 * 新建食物。
 *
 * ## 为什么让用户填「这一份」而不是「每 100 克」
 *
 * 包装上印的是「每份 250ml」，外卖单上写的是「一份约 400 克」——
 * **没有任何场景会告诉他每 100 克是多少**。要求他心算除以几，
 * 是把这个应用最没耐心的一步抬高。
 *
 * 所以表单问的是「你这一份多少克、含多少克蛋白/脂肪/碳水」，
 * 换算成每 100 克由后端做（与录入时算克数是同一份代码）。
 *
 * ## 为什么把换算结果实时显示出来
 *
 * 用户填的是每份值，而库里存的是每 100 克值。这两个数字差别很大，
 * 如果不给他看「换算后是多少」，他会怀疑我们存错了——或者更糟，
 * 他会为了「看起来对」而把份量填成 100。
 * 实时预览让这一步变成可见的算术，而不是黑箱。
 */
const emit = defineEmits<{ (e: 'created', food: FoodSummary): void }>()

const name = ref('')
const grams = ref<number | null>(null)
const protein = ref<number | null>(null)
const fat = ref<number | null>(null)
const carb = ref<number | null>(null)
const kcal = ref<number | null>(null)

const saving = ref(false)
const error = ref<string | null>(null)

function n(v: number | null): number {
  return v === null || Number.isNaN(v) ? 0 : v
}

/** 这一份的热量：给了就用，没给按 4/4/9 算。与后端同一套规则。 */
const kcalPerServing = computed(() => {
  if (kcal.value !== null && kcal.value > 0) return kcal.value
  return 4 * n(protein.value) + 4 * n(carb.value) + 9 * n(fat.value)
})

/** 换算后的每 100 克值。份量无效时不显示。 */
const per100 = computed(() => {
  const g = n(grams.value)
  if (!(g > 0)) return null
  const k = 100 / g
  return {
    kcal: Math.round(kcalPerServing.value * k * 10) / 10,
    protein: Math.round(n(protein.value) * k * 10) / 10,
    fat: Math.round(n(fat.value) * k * 10) / 10,
    carb: Math.round(n(carb.value) * k * 10) / 10,
  }
})

const canSave = computed(
  () =>
    name.value.trim() !== '' &&
    n(grams.value) > 0 &&
    (kcalPerServing.value > 0 || n(protein.value) > 0 || n(fat.value) > 0 || n(carb.value) > 0),
)

async function save() {
  if (!canSave.value || saving.value) return
  saving.value = true
  error.value = null
  try {
    const food = await createFood({
      nameZh: name.value.trim(),
      grams: n(grams.value),
      protein: n(protein.value),
      fat: n(fat.value),
      carb: n(carb.value),
      kcal: kcal.value !== null && kcal.value > 0 ? kcal.value : undefined,
    })
    emit('created', food)
    name.value = ''
    grams.value = null
    protein.value = null
    fat.value = null
    carb.value = null
    kcal.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="new-food">
    <p class="lead">
      搜不到就自己建一个。填**你这一份**有多少、含多少，换算成每 100 克由我们做。
    </p>

    <div class="grid">
      <label class="f wide">
        <span>名字</span>
        <input v-model="name" type="text" placeholder="例如 黄焖鸡米饭（大份）" />
      </label>

      <label class="f">
        <span>这一份多少克</span>
        <input v-model.number="grams" type="number" min="1" placeholder="400" />
      </label>

      <label class="f">
        <span>蛋白 g</span>
        <input v-model.number="protein" type="number" min="0" step="0.1" placeholder="28.8" />
      </label>

      <label class="f">
        <span>脂肪 g</span>
        <input v-model.number="fat" type="number" min="0" step="0.1" placeholder="12" />
      </label>

      <label class="f">
        <span>碳水 g</span>
        <input v-model.number="carb" type="number" min="0" step="0.1" placeholder="40" />
      </label>

      <label class="f">
        <span>热量 kcal（可不填）</span>
        <input v-model.number="kcal" type="number" min="0" placeholder="按 4/4/9 自动算" />
      </label>
    </div>

    <!-- 换算结果实时可见：不给看，用户会怀疑存错了 -->
    <p v-if="per100 !== null" class="preview">
      这一份 {{ Math.round(kcalPerServing) }} 千卡 · 换算后每 100 克：
      <b>{{ per100.kcal }}</b> 千卡 / 蛋白 {{ per100.protein }} · 脂肪 {{ per100.fat }} ·
      碳水 {{ per100.carb }} 克
    </p>
    <p v-else class="preview dim">填了「这一份多少克」之后，这里会显示换算结果</p>

    <p v-if="error !== null" class="err">{{ error }}</p>

    <div class="actions">
      <button class="save" :disabled="!canSave || saving" @click="save">
        {{ saving ? '保存中…' : '存进食物库' }}
      </button>
      <span v-if="!canSave" class="hint">名字、份量、至少一项营养值是必填的</span>
    </div>
  </div>
</template>

<style scoped>
.new-food {
  border: var(--line-width) dashed var(--line);
  border-radius: var(--radius-sm);
  padding: var(--gap-md);
  margin-top: var(--gap-sm);
}
.lead { margin: 0 0 var(--gap-sm); font-size: 11px; color: var(--text-dim); }
.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--gap-sm); }
.f { display: flex; flex-direction: column; gap: 3px; }
.f.wide { grid-column: span 4; }
.f span { font-size: 10px; color: var(--eyebrow); }
.f input {
  border: var(--line-width) solid var(--line);
  border-radius: 6px;
  padding: 5px 8px;
  background: var(--panel);
  color: var(--text);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.preview { margin: var(--gap-sm) 0 0; font-size: 11px; color: var(--text-dim); }
.preview b { color: var(--accent); font-variant-numeric: tabular-nums; }
.preview.dim { color: var(--eyebrow); }
.err { color: var(--danger); font-size: 11px; margin: var(--gap-sm) 0 0; }
.actions { display: flex; align-items: center; gap: var(--gap-sm); margin-top: var(--gap-sm); }
.save {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
  padding: 5px 16px;
}
.save:disabled { border-color: var(--line); color: var(--text-dim); }
.hint { font-size: 10px; color: var(--eyebrow); }
</style>
