# Visual direction

## Status

- **LOCKED（待用户确认）**
- Confirmed by: —（待确认）
- Confirmed at: —

## Visual thesis

> **把演示稿做成产品的延伸，而不是「关于产品的说明」。**

观众看到的不是一份介绍某个 App 的 PPT，而是**这个 App 本身的一部分**——同一套深色厚重系统、同一个暖红强调色、同样的大字重标题与细线分隔。这样做的理由很直接：本项目已经为界面定下一套明确的视觉语言，牌组另起一套只会削弱它，并让读者怀疑「做出来的东西到底长什么样」。

视觉锚点是**对比**而非装饰：近黑底 vs 暖红，800 字重标题 vs 400 正文，大量留白 vs 密集数据块。全牌组**不用阴影**，层次一律靠 1px 细线与留白建立。

## Theme Lock

- Version: **v1**
- Status: DRAFT（待用户确认后转 ACTIVE）
- User-confirmed at: —
- Forbidden patterns:
  - 默认圆角矩形堆叠成卡片网格
  - 阴影造层次
  - 随机渐变、装饰性光晕
  - 拉伸或裁切失当的图片
  - 小号 CJK 正文（正文不低于 14pt）
  - 同一套卡片骨架反复出现在多数页面
  - 编造精确数字或引用

## Resolved theme record

- Source: `ThemeComposer.compose(...)`
- Resolved-theme file: —（生成后填写）
- Seed: —（生成后填写）
- `pptx-designer` version: **1.0.0b11**
- Imported module path: `E:\dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\Lib\site-packages\pptx_designer\__init__.py`

## Design system

### Mood and audience fit

冷静、工程化、有分量。读者是这个项目的作者本人与潜在的朋友/协作者，场景是设计定稿——不需要说服性的营销语气，需要的是**依据与数字**。因此版式偏「技术文档的克制」而非「产品发布的张扬」。

### Colors

| 角色 | 值 | 用途 |
|---|---|---|
| 底 | `#0d0f12` | 全牌组背景 |
| 面板 | `#14171b` | 需要与底区分的块 |
| 细线 | `#262b31` | 唯一的分层手段 |
| 强调 | `#e0453a` | **唯一强调色**：关键数字、结论、当前项 |
| 强调（弱） | `#8f2c1e` | 强调的次级形态（如条形图低值） |
| 主文字 | `#eceef1` | 标题与正文 |
| 次文字 | `#8b939d` | 说明、单位、次要标签 |
| 三级文字 | `#6b7480` | eyebrow、脚注 |

不用第二强调色。需要区分时用**明度**而非色相。

### Typography and fallback

- **中文与英文统一用微软雅黑**：`msyh.ttc`（Regular）/ `msyhbd.ttc`（Bold）。本机已确认存在。
- 未使用可变字体（Noto Sans SC VF）——PPT 渲染对可变字体实例支持不可靠，宁可牺牲一点字重跨度也不冒渲染风险。
- 字号阶梯：页标题 40pt Bold / 小节标题 24pt Bold / 正文 15–16pt Regular / 说明 13pt / eyebrow 11pt（全大写 + 0.16em 字距）。
- **正文不低于 14pt**，宁可删内容也不缩字。

### Grid, margins, and spacing

- 16:9（13.333in × 7.5in）。
- 左右边距统一，正文区宽度一致；栏间距大于行间距，用留白而不是边框分组。
- 圆角统一 4–6px（原生形状的 `adjust`），**不使用阴影**。

### Image treatment

不使用照片与 AI 生成图。所有视觉由**原生形状、示意图与图表**构成——这既符合技术主题，也保证每一页的关键信息都可编辑。

### Chart / diagram language

- 图表只用暖红 + 中性灰阶，不引入第二色相。
- 数据标注**直接写在图上**，不依赖图例查找。
- 架构与流程用正交连接的原生形状，不用斜线。

### Variance / motion / density

- **Variance 6**：封面、总览、两个焦点页、实测页各有不同骨架；其余页面共享栅格但不共享卡片结构。
- **Motion 2**：仅在分节处使用淡入，不做花哨转场。
- **Density 5**：以「一页一个论点」为基准，数据页允许加密，但正文区不塞满。

## Page anchors and whitespace purpose

| 页 | 视觉锚点 | 留白用途 |
|---|---|---|
| 1 封面 | 超大标题「changE」+ 一行副题 | 留白制造停顿，让名字立住 |
| 7 饮食铁律 | 左右反例/正例对照 | 两栏之间的空档即「对错之分」 |
| 9 3D 实测 | 拾取耗时对比条（14.96 → 0.06） | 数字周围的空 |
| 16 实测页 | 三组真实数字并列 | 让数字自己说话 |

## Forbidden patterns

- 卡片网格作为默认容器
- 阴影、渐变、装饰性图形
- 小号 CJK 正文
- 编造或凑整的指标
- 用整页截图承载本可编辑的技术信息
