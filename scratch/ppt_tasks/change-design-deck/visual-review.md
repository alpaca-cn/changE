# Visual review

## Render record

- PPTX: `ppt_tasks/change-design-deck/change-design-deck.pptx`（18 页）
- PDF: `ppt_tasks/change-design-deck/render/change-design-deck.pdf`
- PNG directory: `ppt_tasks/change-design-deck/render/`（slide01–slide18.png，1280×720）
- 渲染链路：LibreOffice + Poppler（`skill/scripts/render_pptx.ps1`）
- 审阅方式：**逐页直接查看 PNG**；联系表 `contact-sheet.png` 仅用于总览一致性核对

## Gate 1 — visual effect

- 第一眼判断：深色近黑底 + 唯一暖红，18 页读起来是同一套系统，具备交付级别的完成度；没有任何一页呈现「模板感」。
- 视觉锚点与构图：封面由超大标题与右侧三条实测数字构成焦点；焦点页（07 铁律对照、09 拾取基准、16 三组实测）各有明确的视觉重心，其余页面主动降低视觉权重。
- 层级、密度与留白：字重对比（标题加粗 vs 正文常规）承担层级；留白用于分隔区块而不是靠卡片边框；首轮 02/06/10/16 存在填充不足，已通过补入**有依据的内容**而非装饰填满。
- 方向一致性：底 `#0D0F12`、面板 `#14171B`、细线 `#262B31`、强调 `#E0453A`、圆角 4–6px、**全程无阴影**——18 页零偏离。

Result: **PASS**

## Gate 2 — requirements and defects

| Requirement / slide | Status | Evidence | Cause | Action |
|---|---|---|---|---|
| MUST · 中文与数字可读 | PASS | slide01–18 全部中文正常 | 微软雅黑已装入主题 typography | 无需处理 |
| MUST · 无溢出 / 重叠 | PASS | 修复后 18 页逐页复核 | 见 D1–D3 | 已修复并重渲染 |
| MUST · 数字真实、不编造 | PASS | 12 MB / 1.9 分钟 / 14.96→0.06 ms / 876 条 / 1,324 条 / 321 MB | 全部取自实测与调研记录 | 无需处理 |
| MUST · 原生可编辑 | PASS | 全部为文字框与原生形状 | Build Mode 显式坐标绘制 | 无需处理 |
| SHOULD · 骨架有变化 | PASS | 9 种页面骨架 | 按 page-plan 逐页指定 | 无需处理 |
| D1 · slide 09 右栏 | **FAIL → PASS** | 数值标签伸入右栏并与标题重叠 | 标签 x=lx+6.47、宽 1.5，右缘越过分隔线 | 名称列收至 2.3、条长 2.55、标签移至 lx+5.05 |
| D2 · slide 11 底部 | **FAIL → PASS** | 「明确排除」压到页码 | 表格行高 0.50×8 挤占底部余量 | 行高降至 0.44，文字降至 10.5pt |
| D3 · slide 12 底层 | **FAIL → PASS** | 第四格右缘压过竖分隔线 | 左栏 8.1 宽超出分隔线位置 | 左栏收至 7.85，四格重算宽距，右栏移至 9.15 |
| D4 · slide 16 卡片 | **FAIL → PASS** | 卡片高 3.65 而内容仅约 2.0 | 卡高与内容未对齐 | 卡高降至 3.05，下方补一条结论 |
| D5 · slide 07 对照框 | NEEDS_REVISION → PASS | 反例与正例描边过于接近 | 反例误用 `accent_dim` | 反例改中性描边 + 灰 ✗ |
| D6 · slide 02 归因段 | NEEDS_REVISION → PASS | 句末「。」单独成行 | 自动换行不受控 | 改显式换行 + 左下补结论 |
| D7 · slide 06 / 10 留白 | NEEDS_REVISION → PASS | 下半页填充不足 | 内容未填满版心 | 各补一条有依据的否定说明 |

## Revision history

| Revision | Change | Failure level | Result |
|---|---|---|---|
| v1 | 首轮生成并渲染 18 页 | — | 4 项 MUST 级缺陷（D1–D4）+ 3 项 NEEDS_REVISION |
| v2 | 修复 D1–D4，优化 D5–D7 | 局部缺陷（构图与坐标），非方向性失败 | 重渲染后逐页复核，18/18 PASS |
| v3 | 按「单用户可分发」决定修订：封面三条数字全部改为实测值（12 MB 外壳 / 47 MB 精简 JRE / 0.06 ms）；p16 补「完整包预计 100–130 MB」并去掉易误读的 Electron 对比行；p17 风险增至 7 条（新增「朋友的电脑没有 JDK」），第一阶段末步改为「打包分发（自带 JRE）」 | 事实性更正（原 12 MB 仅为外壳体积，易被误读为整个安装包） | 重渲染并复核 p01 / p16 / p17，PASS；其余 15 页未改动 |

### v3 复核记录（2026-10-14）

| 页 | 核对点 | 结果 |
|---|---|---|
| 01 | 三条数字是否均为实测且标注清楚 | PASS（均标「实测」） |
| 16 | 是否误读为整个安装包只有 12 MB | PASS（已拆成「Tauri 外壳 12 MB」与「精简 JRE 47 MB」两行，并在注释中给出完整包区间） |
| 17 | 风险行数与副标题是否一致；新增行是否超出版心 | PASS（7 条，末行未越过页脚线） |

## 未解决项

无。所有 MUST 条件满足，SHOULD 条件全部达成。
