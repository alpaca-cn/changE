import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import BodyMap, { type BodyMapMode, type MuscleVolume } from './BodyMap.vue'
import { ALL_REGION_IDS, BACK_REGION_IDS, FRONT_REGION_IDS } from '../assets/body-map/region-ids'
import type { VolumeStatus } from '../api/training'
import source from './BodyMap.vue?raw'

/**
 * 17 块肌群 × regionIds 的映射。
 *
 * **regionIds 抄自数据库**（`V10__muscle_region_ids.sql`，17 条 UPDATE），不是
 * 照素材猜的：前端拿后端给的 `regionIds` 去 SVG 里找同 id 的 path，两边对不上
 * 不会报错，只会有一块肌肉永远灰着。这 65 个 id 同时是「可点区域」的定义，
 * 素材的另外 24 个区域（head / face / 关节 / 四肢末端 / spine / …）刻意不映射。
 *
 * sets / min / max / daysSince 逐块手写，覆盖三种 mode 需要区分的所有情形：
 * 0 组、从没练过（null）、刚练过、很久没练、以及**负数 daysSince**
 * （`?date=` 翻到比最近一次训练更早的周时会出现，见组件里的注释）。
 */
interface Row {
  readonly id: number
  readonly zh: string
  readonly en: string
  readonly sets: number
  readonly days: number | null
  readonly min: number | null
  readonly max: number | null
  readonly regions: readonly string[]
}

const ROWS: readonly Row[] = [
  { id: 1, zh: '胸部', en: 'chest', sets: 3, days: 0, min: 10, max: 20, regions: ['chest-upper-left', 'chest-lower-left', 'chest-upper-right', 'chest-lower-right'] },
  { id: 2, zh: '腹直肌', en: 'abdominals', sets: 0, days: null, min: 8, max: 16, regions: ['abs-upper-left', 'abs-lower-left', 'abs-upper-right', 'abs-lower-right', 'obliques-left', 'obliques-right'] },
  { id: 3, zh: '背阔肌', en: 'lats', sets: 6.5, days: 3, min: 10, max: 20, regions: ['lats-upper-left', 'lats-mid-left', 'lats-lower-left', 'lats-upper-right', 'lats-mid-right', 'lats-lower-right'] },
  { id: 4, zh: '中背部', en: 'middle back', sets: 2, days: 9, min: 8, max: 16, regions: ['traps-mid-left', 'traps-mid-right'] },
  { id: 5, zh: '下背部', en: 'lower back', sets: 1, days: 20, min: 4, max: 10, regions: ['lower-back-erectors-left', 'lower-back-ql-left', 'lower-back-erectors-right', 'lower-back-ql-right'] },
  { id: 6, zh: '斜方肌', en: 'traps', sets: 4, days: 1, min: 6, max: 14, regions: ['traps-upper-left', 'traps-lower-left', 'traps-upper-right', 'traps-lower-right'] },
  { id: 7, zh: '肩部', en: 'shoulders', sets: 5, days: 40, min: 8, max: 18, regions: ['shoulder-front-left', 'shoulder-side-left', 'deltoid-rear-left', 'shoulder-front-right', 'shoulder-side-right', 'deltoid-rear-right'] },
  { id: 8, zh: '肱二头肌', en: 'biceps', sets: 2.5, days: -3, min: 6, max: 14, regions: ['biceps-left', 'biceps-right'] },
  { id: 9, zh: '肱三头肌', en: 'triceps', sets: 1.5, days: 2, min: 6, max: 14, regions: ['triceps-long-left', 'triceps-lateral-left', 'triceps-long-right', 'triceps-lateral-right'] },
  { id: 10, zh: '前臂', en: 'forearms', sets: 0, days: 6, min: 4, max: 10, regions: ['forearm-left', 'forearm-flexors-left', 'forearm-extensors-left', 'forearm-right', 'forearm-flexors-right', 'forearm-extensors-right'] },
  { id: 11, zh: '股四头肌', en: 'quadriceps', sets: 7, days: 4, min: 10, max: 20, regions: ['quads-left', 'quads-right'] },
  { id: 12, zh: '腘绳肌', en: 'hamstrings', sets: 3, days: 15, min: 8, max: 16, regions: ['hamstrings-medial-left', 'hamstrings-lateral-left', 'hamstrings-medial-right', 'hamstrings-lateral-right'] },
  { id: 13, zh: '臀肌', en: 'glutes', sets: 0, days: null, min: 8, max: 16, regions: ['gluteus-maximus-left', 'gluteus-maximus-right'] },
  { id: 14, zh: '小腿', en: 'calves', sets: 2, days: 31, min: 6, max: 14, regions: ['calves-gastroc-medial-left', 'calves-gastroc-lateral-left', 'calves-soleus-left', 'calves-gastroc-medial-right', 'calves-gastroc-lateral-right', 'calves-soleus-right'] },
  { id: 15, zh: '内收肌', en: 'adductors', sets: 1, days: 0, min: 4, max: 10, regions: ['adductors-left', 'adductors-right'] },
  { id: 16, zh: '外展肌', en: 'abductors', sets: 0.5, days: 8, min: 4, max: 10, regions: ['gluteus-medius-left', 'gluteus-medius-right'] },
  { id: 17, zh: '颈部', en: 'neck', sets: 0, days: null, min: null, max: null, regions: ['neck-left', 'neck-right', 'nape'] },
]

/** 2026-10-20 往回推 days 天（负数就是往后）。与后端 `today` 的语义一致。 */
function isoDaysFrom(days: number): string {
  const d = new Date(Date.UTC(2026, 9, 20))
  d.setUTCDate(d.getUTCDate() - days)
  return d.toISOString().slice(0, 10)
}

function statusOf(r: Row): VolumeStatus {
  if (r.min === null || r.max === null) return 'NOT_CONFIGURED'
  if (r.sets === 0) return 'UNTRAINED'
  if (r.sets < r.min) return 'BELOW'
  if (r.sets > r.max) return 'ABOVE'
  return 'IN_RANGE'
}

/** 把行拼成组件契约要的 `Record<number, MuscleVolume>`。 */
function volumeOf(rows: readonly Row[]): Record<number, MuscleVolume> {
  const out: Record<number, MuscleVolume> = {}
  for (const r of rows) {
    out[r.id] = {
      muscleGroupId: r.id,
      nameZh: r.zh,
      nameEn: r.en,
      sets: r.sets,
      min: r.min,
      max: r.max,
      status: statusOf(r),
      lastTrainedOn: r.days === null ? null : isoDaysFrom(r.days),
      daysSince: r.days,
      regionIds: [...r.regions],
    }
  }
  return out
}

function mountMap(mode: BodyMapMode, data: Record<number, MuscleVolume> = volumeOf(ROWS)) {
  return mount(BodyMap, { props: { volumeByMuscle: data, mode } })
}

/** 造一份只把 `en` 那块的 daysSince 换掉的 fixture（其余不动）。 */
function withDays(en: string, days: number | null): Record<number, MuscleVolume> {
  return volumeOf(ROWS.map((r) => (r.en === en ? { ...r, days } : r)))
}

const MAPPED_REGIONS = new Set(ROWS.flatMap((r) => r.regions))

describe('人体图', () => {
  it('正反两面共 89 个 path，每个都带非空的 d', () => {
    const w = mountMap('volume')
    const svgs = w.findAll('svg')
    expect(svgs).toHaveLength(2)
    // 正面 40 / 背面 49：数字来自素材（FRONT_MUSCLES / BACK_MUSCLES 的长度）。
    expect(svgs[0].findAll('path')).toHaveLength(FRONT_REGION_IDS.length)
    expect(svgs[1].findAll('path')).toHaveLength(BACK_REGION_IDS.length)

    const paths = w.findAll('path')
    expect(paths).toHaveLength(89)
    // `d` 绑错字段（比如写成 m.d）时 path 仍然渲染，只是全空——
    // 无报错、无异常，界面上是一张白图。所以这条必须逐个数。
    for (const p of paths) {
      expect((p.attributes('d') ?? '').length).toBeGreaterThan(0)
    }
    expect(new Set(paths.map((p) => p.attributes('data-region-id'))).size).toBe(89)
    expect(paths.map((p) => p.attributes('data-region-id') ?? '').sort()).toEqual(
      [...ALL_REGION_IDS].sort(),
    )
  })

  it('有组数的区域带上着色标记，fill 离开基线色', () => {
    const w = mountMap('volume')
    const chest = w.find('[data-region-id="chest-upper-left"]')
    const baseline = w.find('[data-region-id="head"]')
    expect(chest.attributes('data-intensity')).not.toBe('0')
    // 颜色只能从 token 派生：基线是 --muted-strong，着色是它与 --accent 的混合。
    expect(baseline.attributes('fill')).toBe('var(--muted-strong)')
    expect(chest.attributes('fill')).not.toBe(baseline.attributes('fill'))
  })

  it('本周 0 组的区域照常渲染，只是不着色', () => {
    const w = mountMap('volume')
    const abs = ['abs-upper-left', 'abs-lower-left', 'abs-upper-right', 'abs-lower-right', 'obliques-left', 'obliques-right']
    for (const id of abs) {
      const p = w.find(`[data-region-id="${id}"]`)
      expect(p.exists(), `${id} 不该因为 0 组就消失`).toBe(true)
      expect(p.attributes('data-intensity'), id).toBe('0')
      expect(p.attributes('fill'), id).toBe('var(--muted-strong)')
    }
  })

  it('点一块区域 emit muscle-click，参数是 muscleGroupId 而不是区域 id', () => {
    const w = mountMap('volume')
    w.find('[data-region-id="chest-upper-left"]').trigger('click')
    // 区域 id 是字符串（chest-upper-left），肌群 id 是数字 1——两者不可能混。
    expect(w.emitted('muscle-click')).toEqual([[1]])

    const w2 = mountMap('volume')
    w2.find('[data-region-id="triceps-lateral-right"]').trigger('click')
    expect(w2.emitted('muscle-click')).toEqual([[9]])
  })

  it('同一肌群的多块/左右区域一起亮', () => {
    const w = mountMap('volume')
    const left = w.find('[data-region-id="chest-upper-left"]')
    const right = w.find('[data-region-id="chest-lower-right"]')
    expect(left.attributes('data-intensity')).toBe(right.attributes('data-intensity'))
    expect(left.attributes('fill')).toBe(right.attributes('fill'))
    expect(left.attributes('data-intensity')).not.toBe('0')
  })

  it('三种 mode 用不同的着色函数（同一份数据，标记不同）', () => {
    const snapshot = (mode: BodyMapMode) =>
      mountMap(mode)
        .findAll('path')
        .map((p) => `${p.attributes('data-region-id')}:${p.attributes('data-intensity')}`)

    const volume = snapshot('volume')
    const recovering = snapshot('recovering')
    const neglected = snapshot('neglected')
    expect(volume).not.toEqual(recovering)
    expect(volume).not.toEqual(neglected)
    expect(recovering).not.toEqual(neglected)
  })

  it('未映射的 24 个区域是基线灰且不可点', async () => {
    const w = mountMap('volume')
    const unmapped = ALL_REGION_IDS.filter((id) => !MAPPED_REGIONS.has(id))
    expect(MAPPED_REGIONS.size).toBe(65)
    expect(unmapped).toHaveLength(24)
    expect(w.findAll('[data-interactive="true"]')).toHaveLength(65)

    const baseline = w.find('[data-region-id="head"]').attributes('fill')
    for (const id of unmapped) {
      const p = w.find(`[data-region-id="${id}"]`)
      expect(p.exists(), `${id} 也要画出来（缺了像残肢）`).toBe(true)
      expect(p.attributes('data-intensity'), id).toBe('0')
      expect(p.attributes('data-interactive'), id).toBe('false')
      expect(p.attributes('fill'), id).toBe(baseline)
      await p.trigger('click')
    }
    expect(w.emitted('muscle-click')).toBeUndefined()
  })

  // 后端 `daysSince = 参照日 - lastTrainedOn`，而 lastTrainedOn 的 SQL 不带日期范围：
  // 用 `?date=` 翻到比「最近一次训练」更早的周时，daysSince 会是负数，语义是
  // 「你在这天之后练过这块肌肉」。它是**最近练过**，不是很久没练。
  it('daysSince 为负数时按「最近练过」处理，与 0 同档', () => {
    const negative = mountMap('recovering', withDays('biceps', -3))
    const zero = mountMap('recovering', withDays('biceps', 0))
    const pick = (w: ReturnType<typeof mountMap>) =>
      w.find('[data-region-id="biceps-left"]').attributes('data-intensity')

    expect(pick(negative)).toBe(pick(zero))
    expect(pick(negative)).not.toBe('0')
    // 左右两块都跟着亮（负数不该把整块肌群变成灰的）
    expect(negative.find('[data-region-id="biceps-right"]').attributes('data-intensity')).toBe(
      pick(negative),
    )

    // neglected 同理：负数意味着「刚练过」，不是「很久没练」，不该被催促。
    const negNeglected = mountMap('neglected', withDays('biceps', -3))
    const zeroNeglected = mountMap('neglected', withDays('biceps', 0))
    expect(negNeglected.find('[data-region-id="biceps-left"]').attributes('data-intensity')).toBe(
      zeroNeglected.find('[data-region-id="biceps-left"]').attributes('data-intensity'),
    )
    expect(negNeglected.find('[data-region-id="biceps-left"]').attributes('data-intensity')).toBe('0')
  })

  it('颜色全部来自主题 token，组件里没有色值字面量', () => {
    // 视觉方向是设计文档封板的：组件只许 var(--xxx) / color-mix(var(...))。
    expect((source.match(/var\(--/g) ?? []).length).toBeGreaterThan(0)
    for (const pattern of [/#[0-9a-fA-F]{3,8}\b/, /\brgba?\(/, /\bhsla?\(/]) {
      expect(source.match(pattern) ?? [], `${pattern} 不该出现在 BodyMap.vue 里`).toHaveLength(0)
    }
    // 渲染出来的 fill 也一样：每一条都得是 token 或 token 的混合。
    const fills = mountMap('volume')
      .findAll('path')
      .map((p) => p.attributes('fill') ?? '')
    for (const fill of fills) {
      expect(fill).toMatch(/^(var\(--[a-z-]+\)|color-mix\(in srgb, var\(--[a-z-]+\) \d+%, var\(--[a-z-]+\)\))$/)
    }
  })
})
