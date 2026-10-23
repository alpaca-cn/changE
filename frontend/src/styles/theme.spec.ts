// @vitest-environment node
// 这条用例只读文件、不碰 DOM，必须跑在 node 环境：jsdom 模式下 Vite 会把
// node:fs / node:url / node:path 当作浏览器模块 externalize 成空壳，
// 于是 fileURLToPath 不是函数（2026-10-18 实测）。
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

// 这条测试的意义：视觉方向是一份冻结的决定，而 CSS 是最容易悄悄漂移的地方
// （改一个色值不会有任何东西报警）。这里把「决定里写的那几个值」变成断言，
// 让漂移变成红的。这与后端 SchemaTest 用 DDL 快照钉住 schema 是同一个思路。
//
// **2026-10-21 整体改写过一次**：第一版是深色底 + 暖红，用户装机后反馈
// 「纯黑看着太压抑」，并要求参考 LZheng0411/Lzheng-fitness。方向翻了，
// 所以这里的断言也翻了——不是放宽，是换成新方向的值。
const here = dirname(fileURLToPath(import.meta.url))
const raw = readFileSync(resolve(here, 'theme.css'), 'utf-8')

// **必须先剥掉注释再断言**。theme.css 的注释里会引用 token 名与色值
// （比如「参考项目的 --hero:#161616」「强调色变成黄铜 #b5893a」），
// 不剥的话 `--hero\s*:\s*([^;]+);` 会从注释里的那半句开始匹配，
// 一路抓到下一个分号，取回来一长串散文。2026-10-21 实测踩过。
const css = raw.replace(/\/\*[\s\S]*?\*\//g, '')

/** 取 `--name: value;` 里的 value（去空白）。 */
function token(name: string): string | undefined {
  const m = css.match(new RegExp(`--${name}\\s*:\\s*([^;]+);`))
  return m?.[1].trim()
}

describe('主题 token', () => {
  it('暖米白底、白卡片、暖灰发丝线', () => {
    // 底色是 #f4f2ed 而不是纯白：纯白在屏幕上发冷，带一点暖才「稳」。
    expect(token('bg')).toBe('#f4f2ed')
    // 2026-10-23 玻璃质感：面板与描边改成半透明，背后的渐变底色才透得上来。
    // 不透明的 #ffffff 在渐变底色上像一块贴纸，而不是一层玻璃。
    expect(token('panel')).toBe('rgba(255, 255, 255, 0.66)')
    expect(token('line')).toBe('rgba(227, 223, 213, 0.72)')
  })

  it('底色不是纯黑也不是纯白（两端都极端，一个压抑一个发冷）', () => {
    const bg = token('bg')?.toLowerCase()
    expect(bg).not.toBe('#000000')
    expect(bg).not.toBe('#ffffff')
    // 暖色意味着 R > B。冷灰或纯灰不算暖。
    const r = parseInt(bg!.slice(1, 3), 16)
    const b = parseInt(bg!.slice(5, 7), 16)
    expect(r, '底色必须偏暖（R > B）').toBeGreaterThan(b)
  })

  it('强调色只有一个黄铜', () => {
    expect(token('accent')).toBe('#b5893a')
  })

  it('强调色不再等于告警色（练过量必须与「这是你的数」分得开）', () => {
    // 第一版两者同值是刻意的（红既是强调又是告警）。现在强调色是中性的黄铜，
    // 若 --danger 还是它，「练过量」就看不出是告警了。
    expect(token('danger')).not.toBe(token('accent'))
  })

  it('深色只留一块 hero，不做成大面积底色', () => {
    expect(token('hero')).toBe('#161412')
    // hero 必须比任何表面色都暗，否则那块压不住画面
    // 只算 RGB 三个分量：`--panel` 现在是 rgba(…)，按 #rrggbb 解析会得到 NaN，
    // 而 NaN 参与比较永远为 false，那条断言会**静默地永远通过**。
    const lum = (color: string) => {
      // 两种格式都要支持：十六进制（没有分隔符，逐个 \d+ 会把整串当一个数）
      // 与 rgba(r, g, b, a) 这种带逗号的形式。
      if (color.startsWith('#')) {
        return (parseInt(color.slice(1, 3), 16)
          + parseInt(color.slice(3, 5), 16)
          + parseInt(color.slice(5, 7), 16)) / 3
      }
      const nums = color.match(/[\d.]+/g)
      if (nums === null || nums.length < 3) {
        throw new Error('无法解析颜色: ' + color)
      }
      return (Number(nums[0]) + Number(nums[1]) + Number(nums[2])) / 3
    }
    for (const name of ['bg', 'panel', 'line']) {
      expect(lum(token('hero')!), `--hero 应该比 --${name} 暗`).toBeLessThan(lum(token(name)!))
    }
  })

  it('小标签的灰与字距与决定一致', () => {
    expect(token('eyebrow')).toBe('#9c968a')
    expect(token('eyebrow-tracking')).toBe('0.16em')
  })

  it('标题字距 -0.015em，字重 600（不是 800：粗体让界面显得吵）', () => {
    expect(token('title-tracking')).toBe('-0.015em')
    expect(token('title-weight')).toBe('600')
  })

  it('主圆角 16px、小圆角 10px（大圆角 + 发丝线分层，不用阴影）', () => {
    expect(Number(token('radius')?.replace('px', ''))).toBe(16)
    expect(Number(token('radius-sm')?.replace('px', ''))).toBe(10)
  })

  it('不定义阴影（靠描边与间距分层，不用阴影）', () => {
    expect(css).not.toMatch(/--shadow\s*:/)
  })

  it('不做深色覆盖：主题就是浅色这一套', () => {
    // 第一版钉的是「不做浅色」（当时是深色主题）。方向翻了之后，
    // 同一条约束镜像成「不做深色」——否则两套主题并存会让每个色值的
    // 验证成本翻倍，而收益只是「喜欢暗色的人」。
    expect(css).not.toMatch(/prefers-color-scheme\s*:\s*dark/)
    expect(css).not.toMatch(/\[data-theme\s*=\s*["']?dark/)
  })

  it('黄铜 #b5893a 只出现在 --accent 一处，组件不许各写各的铜', () => {
    // 与第一版对暖红的要求同构：唯一强调色只该有一个来源。
    const hits = css.match(/#b5893a/gi) ?? []
    expect(hits).toHaveLength(1)
    expect(token('accent')).toBe('#b5893a')
  })

  it('语义色齐了四个（周容量的五种状态各有归属）', () => {
    // IN_RANGE→--ok、BELOW→--warn、ABOVE→--danger、UNTRAINED→--muted-strong，
    // NOT_CONFIGURED→--text-dim（已有）。少一个就会有状态渲染成「没有颜色」。
    for (const name of ['ok', 'warn', 'danger', 'muted-strong']) {
      expect(token(name), `--${name} 未定义`).toBeTruthy()
    }
  })

  it('四个语义色互不相同，也不与层级色撞车', () => {
    // 「练少了」与「练过量」用同一个颜色的话，容量面板比不显示更难看出问题。
    const vals = ['ok', 'warn', 'danger', 'muted-strong'].map((n) => token(n))
    expect(new Set(vals).size).toBe(4)
    for (const reserved of ['text', 'bg', 'panel', 'line', 'accent']) {
      expect(vals, `语义色不该等于 --${reserved}`).not.toContain(token(reserved))
    }
  })

  it('语义色在米白底上要有足够对比（不是只在深底上好看）', () => {
    // 粗略的相对亮度差：太接近 --bg 的颜色在那个底色上等于看不见。
    const lum = (hex: string) => {
      const n = parseInt(hex.slice(1, 3), 16) * 0.299
        + parseInt(hex.slice(3, 5), 16) * 0.587
        + parseInt(hex.slice(5, 7), 16) * 0.114
      return n / 255
    }
    const bgLum = lum(token('bg')!)
    for (const name of ['ok', 'warn', 'danger', 'muted-strong']) {
      expect(Math.abs(lum(token(name)!) - bgLum), `--${name} 在米白底上太淡`).toBeGreaterThan(0.25)
    }
  })
})
