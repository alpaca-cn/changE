# 调研：竞品槽点、开源参考、食物数据源与数据模型

- 日期：2026-10-13
- 项目：change（个人自用健身记录客户端）
- 用途：为「需求分析」「数据模型设计」提供依据
- 来源：由调研子代理检索产出，star 数据取自 2026-10-13，经 GitHub API / 官方页面核验；**许可证结论落地前建议再核一次**

---

## 0. 一句话结论

商业 App 的槽点几乎都源于多租户订阅制，**单用户自建可全部规避**。数据模型不必自创：照抄 wger 的「计划模板 / 实际日志」两层结构 + 每食材克重换算；食物数据用 USDA SR Legacy 离线打底，中国食物成分表转录补中式。

## 1. 商业 App 的槽点（= change 的机会）

| App | 好评点 | 抱怨点 |
|---|---|---|
| Strong / Hevy / Jefit | 记组快、例程分享、动作库全 | 免费版限计划数（Strong 免费仅 3 个 routine）、广告、UI 陈旧（Jefit Trustpilot 3.1）、分析与导出收费 |
| MyFitnessPal | 食物库最大 | 2022 起条码扫描等进订阅（$19.99/月）、用户贡献数据腐烂（同食物多条目、热量错）、广告多、导出仅 Premium |
| Keep / 训记 | 中文动作名 / 组次 / RPE 最贴国人；训记有开放 API | 会员墙、导出难、缺 Strava 打通 |

**共性缺口**：功能被订阅墙切碎、有广告、导出受限、食物库偏西式包装食品（中式家常菜与份量换算差）、**计划是静态模板而非按个人历史递进**。

## 2. 开源参考项目

| 项目 | Star | 技术栈 | 许可证 | 可借鉴点 |
|---|---|---|---|---|
| [wger](https://github.com/wger-project/wger) | 7.1k | Django + DRF | **AGPL-3.0** | 唯一训练+饮食+体重+进度照全覆盖，**数据模型最值得抄** |
| [openGym](https://github.com/DuarteSantos8/openGym) | 10.9k | React/PWA + Node | **AGPL-3.0** | 从 FitNotes/Strong/Hevy 导入、superset/热身建模、肌肉疲劳与去训练状态、passkey、MCP |
| [SparkyFitness](https://github.com/CodeWithCJ/SparkyFitness) | 6.3k | TypeScript | **自定义「仅非商业」许可（非 OSI 开源）** | 家庭多用户、AI 食物识别、饮水 |
| [FitTrackee](https://github.com/SamR1/FitTrackee) | 1.2k | Flask | AGPL-3.0 | GPX 统计 |
| [openScale](https://github.com/oliexdev/openScale) | 2.6k | Kotlin | GPL-3.0 | 体重体脂趋势、蓝牙秤接入 |
| [endurain](https://github.com/endurain-project/endurain) | 2.3k | — | AGPL-3.0 | — |
| [lyftr](https://github.com/Cawlumm/lyftr) | 358 | Go + SQLite 单容器 | MIT | 单容器部署形态 |
| [lifttrace](https://github.com/TraceApps/lifttrace) | 110 | Svelte | AGPL-3.0 | — |

**Java / Spring Boot 生态很薄（重要排期风险）**：

- [fitnessjiffy-spring](https://github.com/steve-perkins/fitnessjiffy-spring) 121★，**未声明标准许可证**（默认保留版权，只能读不能抄）
- [fitbuddy](https://github.com/mepox/fitbuddy) 14★ MIT
- 其余 0–20★ 学生项目
- 唯一可用的 Spring 组件：[openfoodfacts-springboot-starter](https://github.com/openfoodfacts/openfoodfacts-springboot-starter)
- 同名 FitHub 仓库仅 4★ / 0★，无参考价值

> **风险结论：Java 侧没有成熟可抄的健身项目。** 意味着数据模型和业务逻辑要自己设计，但可以「读 wger 的模型思路，用 Java 重写」——这本身也是很好的学习路径。

## 3. 热量数据源

| 数据源 | 许可证 | 中文覆盖 | 结论 |
|---|---|---|---|
| **USDA FoodData Central** | **公共领域**（美国政府作品） | ≈0 | 免费 key，1000 次/时/IP（DEMO_KEY 30/时）。SR Legacy 压缩 12.7MB / 解压 54MB，**最宜离线打包**；但 2018 停更。Branded 195MB 起、质量参差 |
| **Open Food Facts** | 数据库 ODbL / 内容 DbCL / 图片 CC-BY-SA | 中式包装食品可查，家常菜与营养缺项多 | API 免费但「1 次调用 = 1 次真实扫描」**禁爬**；离线走 nightly dump（CSV gz ≈ 0.9GB、Parquet）。**条码走在线查，不打包** |
| **中国食物成分表第 6 版** | **有版权、无开放许可，不可再分发** | 高 | 转录版 [Sanotsu/china-food-composition-data](https://github.com/Sanotsu/china-food-composition-data) 362★，OCR 出 1677 条为 JSON/CSV/XLSX（仓库未声明许可）。**自用可，分发有风险** |
| Nutritionix / Edamam / FatSecret | 免费档限次 | — | **不许离线打包**，排除 |

**建议组合**：本地库 = SR Legacy + 中国成分表转录 + 手工补 20–50 条常吃项；条码走 OFF 在线查。

## 4. 数据模型要点（直接来自 wger 的设计）

### 4.1 训练：模板 / 实际 两层

- 模板层：`Routine` → `Day` → `Slot`（动作位，可 superset）→ `SlotEntry(exercise, repetition_unit, weight_unit, order)`
- 实际层：`WorkoutSession` + `WorkoutLog(date, exercise, slot_entry, repetitions/weight 与 *_target, rir, rest, iteration)`
- **关键**：目标值与实际值**同表并存**，用 `slot_entry` 反指计划，避免每次训练都复制一遍动作结构。

### 4.2 「一次训练多动作多组」怎么表达

- 一个 `SlotEntry` 下挂多条 log 行 = 多组。
- 组类型用**枚举**（normal / warmup / dropset / myo / partial / forced / tut / iso / jump），比「第几组」表达力强得多。

### 4.3 「一餐多食物条目」怎么表达

- `Plan` → `Meal` → `MealItem(meal, ingredient, weight_unit, amount, order)`
- 单位换算靠 `IngredientWeightUnit(ingredient, name, gram)`，把「1 碗 / 1 勺」折成克。
- **关键约束：营养一律存 per-100g**，热量 = `amount × gram / 100 × 每百克值`。
  **条目里绝不留绝对热量**，否则换算单位后立刻算错。

### 4.4 照片

- `Image(date, user, image, height, width, description)`，按 `date` 排序即时间线。

## 5. 照片进步追踪

- 文件落本地/挂载卷，**数据库只存相对路径 + 日期 + 备注**。
- EXIF 或手选日期归入时间线；按月或按体重取锚点并排对比。
- 进阶参考：[jStrider/body-progress-tracker](https://github.com/jStrider/body-progress-tracker)（Python，MediaPipe + rembg 自动对齐抠背景，不同时期体态可直接叠加）。
- 隐私：**仅本地不出网**；多端访问走鉴权私有实例 + 缩略图，不放公开对象存储裸链。

---

## 落地建议（写进设计文档）

1. 数据模型学 wger（模板/实际两层 + per-100g 营养 + 克重换算表），用 Java 重写。
2. 食物库分三块：离线打底（SR Legacy）+ 中式补充（成分表转录，仅供自用不分发）+ 在线查条码（OFF）。
3. 照片全本地存储，AI 识别走云端 API 时必须让用户可一键切本地/关掉（身材照隐私）。
