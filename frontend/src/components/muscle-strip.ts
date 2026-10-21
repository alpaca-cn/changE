/**
 * 17 格热力条的纯函数。
 *
 * 为什么拆出来：`:style="{ background: tone(sets) }"` 在 jsdom 里会**被静默丢弃**
 * （jsdom 的 cssstyle 不认 `var(...)` 与 `color-mix(...)`，赋值失败但不抛错），
 * 于是测试读到的是空 style 属性。颜色算式放在模板里就等于测不到。
 * 拆成纯函数之后，算式可以被直接断言，模板只负责把结果贴上去。
 */

/**
 * 一格的填充色。
 *
 * 0 组 → `--muted-strong`（未练），**不隐藏**：
 * 「本周 0 组」这类提醒躲不掉才有用。
 * 其余从 `--accent` 的 34% 混到 100%，10 组封顶。
 *
 * 用 `color-mix` 而不是在 JS 里算十六进制：这样颜色仍然只来自两个 token，
 * 组件里没有一个写死的色值（theme.spec.ts 的前提）。
 */
export function tone(sets: number): string {
  if (sets <= 0) return 'var(--muted-strong)'
  const pct = Math.round((0.34 + 0.66 * Math.min(1, sets / 10)) * 100)
  return `color-mix(in srgb, var(--accent) ${pct}%, var(--muted-strong))`
}

/** 从 tone() 的结果里取出强调色的百分比。取不到时返回 null（而不是 NaN）。 */
export function tonePercent(sets: number): number | null {
  const m = tone(sets).match(/var\(--accent\)\s*(\d+)%/)
  return m ? Number(m[1]) : null
}

/**
 * 两字短名。取前两个字而不是维护一张手写缩写表——
 * 17 个肌群的中文名前两位刚好可读（胸部 / 肱二 / 腘绳 / 小腿）。
 */
export function short(nameZh: string): string {
  return nameZh.slice(0, 2)
}
