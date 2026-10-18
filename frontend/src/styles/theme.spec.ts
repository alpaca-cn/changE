// @vitest-environment node
// 这条用例只读文件、不碰 DOM，必须跑在 node 环境：jsdom 模式下 Vite 会把
// node:fs / node:url / node:path 当作浏览器模块 externalize 成空壳，
// 于是 fileURLToPath 不是函数（2026-10-18 实测）。
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

// 这条测试的意义：视觉方向是设计文档冻结的决定，而 CSS 是最容易悄悄漂移的地方
// （改一个色值不会有任何东西报警）。这里把「设计文档里写的那几个值」变成断言，
// 让漂移变成红的。这与后端 SchemaTest 用 DDL 快照钉住 schema 是同一个思路。
const here = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(resolve(here, 'theme.css'), 'utf-8')

/** 取 `--name: value;` 里的 value（去空白）。 */
function token(name: string): string | undefined {
  const m = css.match(new RegExp(`--${name}\\s*:\\s*([^;]+);`))
  return m?.[1].trim()
}

describe('主题 token', () => {
  it('底色、面板、分隔线与设计文档一致', () => {
    expect(token('bg')).toBe('#0d0f12')
    expect(token('panel')).toBe('#14171b')
    expect(token('line')).toBe('#262b31')
  })

  it('强调色只有一个暖红', () => {
    expect(token('accent')).toBe('#e0453a')
  })

  it('小标签的灰与字距与设计文档一致', () => {
    expect(token('eyebrow')).toBe('#6b7480')
    expect(token('eyebrow-tracking')).toBe('0.16em')
  })

  it('标题字距是 -0.015em，字重 800', () => {
    expect(token('title-tracking')).toBe('-0.015em')
    expect(token('title-weight')).toBe('800')
  })

  it('圆角在 4–6px 之间（设计文档规定不允许更大）', () => {
    const r = Number(token('radius')?.replace('px', ''))
    expect(r).toBeGreaterThanOrEqual(4)
    expect(r).toBeLessThanOrEqual(6)
  })

  it('不定义阴影（设计文档明确说不用阴影分层）', () => {
    expect(css).not.toMatch(/--shadow\s*:/)
  })

  it('只有深色主题：不定义浅色覆盖（设计文档明确说不做浅色）', () => {
    // 用 prefers-color-scheme 或 [data-theme] 开浅色分支就等于破掉了这条封板决定
    expect(css).not.toMatch(/prefers-color-scheme\s*:\s*light/)
    expect(css).not.toMatch(/\[data-theme\s*=\s*["']?light/)
  })

  it('强调色只在 --accent 定义一次，组件不许各写各的红', () => {
    // 设计文档说「唯一的暖红」。全文件里 #e0453a 只该出现一次（就是 --accent 那行）。
    const hits = css.match(/#e0453a/gi) ?? []
    expect(hits).toHaveLength(1)
  })
})
