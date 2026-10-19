import type { MuscleVolume, VolumeStatus } from '../api/training'

/**
 * 容量状态 → CSS class 与中文短语。
 *
 * 单独成模块（而不是塞在 `VolumePanel.vue` 的 `<script setup>` 里）有两个理由：
 * 1. `<script setup>` **不允许具名导出**，放进去这些函数就没法被单测直接调用；
 * 2. 「状态 → 表现」是一条纯映射，被模板与测试共用，放在一个地方才不会被写歪。
 *
 * **颜色不是唯一的信号**：{@link statusLabel} 给出文字，色盲用户也能读出状态
 * （设计文档要求的是「一眼看出哪里练多、哪里漏练」，只有颜色做不到这一点）。
 */

/** 状态 → class。未知状态落到 `not-configured`：宁可显得「没配目标」，也不静默变空。 */
export function statusClass(s: VolumeStatus): string {
  switch (s) {
    case 'IN_RANGE':
      return 'in-range'
    case 'BELOW':
      return 'below'
    case 'ABOVE':
      return 'above'
    case 'UNTRAINED':
      return 'untrained'
    case 'NOT_CONFIGURED':
      return 'not-configured'
    default:
      // 到不了这里，除非后端新增了一个状态而忘了同步这个文件。
      // 那时旧写法会返回 undefined，class 绑定为空 → 那块肌群没有任何颜色提示，
      // 是典型的静默失效。宁可把它显示成「未设目标」也要让人看见。
      return 'not-configured'
  }
}

/** 状态 → 中文短语。`UNTRAINED` 特意把「0 组」写出来——那是它的全部含义。 */
export function statusLabel(s: VolumeStatus): string {
  switch (s) {
    case 'IN_RANGE':
      return '达标'
    case 'BELOW':
      return '偏少'
    case 'ABOVE':
      return '偏多'
    case 'UNTRAINED':
      return '本周 0 组'
    case 'NOT_CONFIGURED':
      return '未设目标'
    default:
      return '未设目标'
  }
}

/**
 * 目标区间的文案。
 *
 * `null` 是**真事实**（`neck` 没配目标），不是「0」。写成 `null–null` 或 `0–0`
 * 都会把「我们没给这块肌肉定建议量」说成「建议练 0 组」。
 */
export function targetText(m: Pick<MuscleVolume, 'min' | 'max'>): string {
  if (m.min === null || m.max === null) return '未设目标'
  return `${m.min}–${m.max}`
}

/** 组数文案：整数不显示小数点，半组（0.5 的倍数）保留一位。 */
export function setsText(sets: number): string {
  return Number.isInteger(sets) ? String(sets) : sets.toFixed(1)
}
