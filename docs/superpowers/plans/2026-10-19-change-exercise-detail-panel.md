# 动作详情面板与中文做法 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在训练记录页点一个动作，就能看到它怎么做——分步做法（中文）、器械、分类、目标肌群，以及一个可直接复制去搜索的视频链接。

**Architecture:** 做法文字在计划 2 已经导入（`exercise.instructions`，871 条英文），本计划补一列中文（`instructions_zh`）并**把它暴露出来**——目前没有任何端点返回它。中文按「数据集 → 分批翻译产物 → 合并 → classpath seed → 启动时写库」这条链落地，与中文名那套（`ExerciseNameZhService` + `name-zh.json`）**完全同构**。前端只加一个详情面板，**不新增任何外壳能力**。

**Tech Stack:** Java 25 · Spring Boot 3.5.16 · Flyway · SQLite · Vue 3 + TS

---

## 这一份要解决什么

你说的是「训练记录里动作这么多，点一个能不能教我这个动作」。核对下来：

| 东西 | 现状 |
|---|---|
| **视频** | **没有合法来源**。free-exercise-db 只有静态图；hasaneyldrm 的 `videos/` 版权属 Gym visual，设计文档已排除 |
| **分步做法** | **已经在库里**（`exercise.instructions`，871 条），但 `GET /api/exercises` 返回的 `ExerciseSummary` 只有 `id/nameEn/nameZh/equipment/isCompound/category`——**这个字段从来没被暴露过** |
| **中文做法** | **没有**。设计文档只要求自产中文**名**，计划 2 的非目标里明确写了「不翻译 instructions」 |

所以本计划做三件事：**把已存在的字段暴露出来**、**把它翻成中文**、**加一个能复制去搜索的链接**。

**视频链接的形态（用户已定）**：**不**在应用里播放、**不**由应用拉起浏览器——只在面板里给出**可选中复制的搜索链接**（B 站搜中文名、YouTube 搜英文名）。

> 这条刻意的选择消掉了一个架构偏离：设计文档写着「模型接口是唯一出网点」。如果是「点一下就打开浏览器」，应用就多出一个「拉起外部程序」的能力，需要自写命令 + host 白名单 + 在设计的偏离表里登记。**只是给文本，就什么都不用加。**

---

## 前置事实（已实测，不需要重新验证）

| 项 | 值 |
|---|---|
| `exercise.instructions` | 存在（`V8`）。**871 条非空、5 条为空**；总段落 **3,738**、平均 **4.27 段/条**、范围 0–24；总字符 **574,573**、非空平均 **659**、最长 **3,214** |
| 中文名那套的形态 | `seed/name-zh.json` = `{"<nameEn>": "<中文名>"}` 的 **dict**，876 条、44,684 字节；`ExerciseNameZhService.apply(Map)` 返回计数的 `Result`；`ExerciseNameZhRunner` 是 `@Component` 的 `ApplicationArguments` runner |
| `GET /api/exercises` 的返回 | `ExerciseSummary(long id, String nameEn, String nameZh, String equipment, boolean isCompound, String category)`——**不含 instructions** |
| 训练记录页 | `frontend/src/views/TrainingView.vue`（12,278 B，可用）。它用 `searchExercises` 按肌群拉动作 |
| 前端 HTTP 出口 | 只有 `callApi`（`api/training.ts`），其它模块 `import { callApi } from './training'` |
| 主题 | `theme.css` 是唯一颜色来源；两个成品页 `var(--` 47/51 次、`#hex` 0 次 |
| 基线 | 后端 **315**、前端 **216**、外壳 **93+4**；迁移到 **V8** |

### ⚠️ 翻译的实际规模（先认清，再决定怎么切）

| 指标 | 值（**全量实测**，不是抽样） |
|---|---|
| 要翻的条数 | **876**（其中 5 条原文为空 → 实际有内容可翻的是 **871**） |
| 总段落数 | **3,738**（平均 4.27 段/条，范围 0–24） |
| 英文总字符 | **574,573**（非空平均 659，最长 3,214） |
| 中文产物估算 | ≈ **336 KB** JSON（约英文的 0.6×；比 `name-zh.json` 的 44 KB 大 7 倍多，但仍是个小文件） |

**这不是一个任务能做完的量。** 本计划把它切成**每批 40 条、共 22 批**（每批平均 171 段、约 26,000 英文字符），每批独立落盘到自己的文件——这样**并行安全**（不会同时写一个文件）、**可续跑**（崩了只丢一批）、**可校验**（每批单独看质量）。

---

## 一处已解决的规格冲突（Task 2 实施者报出来的）

这份计划初稿里有两条互相矛盾的断言：

- Task 2 Step 3 说「合并产物 **876 条**、键集合与 `name-zh.json` 完全相同」
- Task 2 Step 2 说「英文原文为空的 5 条**不该出现在产物里**」

两者不能同时成立。**已定为 871 条**（876 − 5），理由：

1. 那 5 条的英文原文是**空数组**，没有任何可翻的内容；在产物里写 `[]` 等于宣称「这条已经被翻译成零段」，语义是错的；
2. Task 5 的写入服务本来就有 `skippedEmptySource` 这一档计数——它假定的就是「这 5 条不进产物」；
3. 「完成标准」写的是「876 条里 **871 条**有中文做法」，也指向 871。

所以产物 871 条、**覆盖率 871/871**，并且测试要**显式断言那 5 条不在产物里**（否则「少了 5 条」和「漏翻了 5 条」分不开）。

> 数据集顺序里那 5 条是下标 358 / 501 / 568 / 665 / 667（`Iron Cross`、`One-Arm Kettlebell Swings`、`Push Press`、`Side Bridge`、`Side Jackknife`），落在第 9/13/15/17 批。

---

## 全局约定

| 项 | 约定 |
|---|---|
| 迁移 | 只增不改。本计划新增 **V9**（`exercise.instructions_zh`） |
| 翻译产物 | 放在 `backend/src/main/resources/seed/instructions-zh/`，每批一个 `part-NN.json`，**全部入库**（它们是可审计的中间产物，也是续跑的依据） |
| 合并产物 | `seed/instructions-zh.json`（**871 条**——876 减去 5 条原文为空的）——**由脚本从 part 文件合并，不手编** |
| 翻译风格 | 见 Task 3 的「翻译约定」一节 |
| 幂等 | 启动时写库的那一步必须可重跑：已有 `instructions_zh` 的行跳过 |
| 提交信息 | 中文，说清「做了什么 + 为什么」 |
| 计划先于实现 | 偏离随时记进「实施修正」 |

---

## 文件结构

```
backend/src/main/
├── resources/
│   ├── db/migration/
│   │   └── V9__exercise_instructions_zh.sql          ← 新
│   └── seed/
│       ├── name-zh.json                              ← 已有（不动）
│       ├── instructions-zh/                          ← 新：分批产物
│       │   ├── part-01.json … part-22.json
│       │   └── MANIFEST.md                           ← 批次清单与进度
│       └── instructions-zh.json                      ← 新：合并产物（生成物）
└── java/com/change/
    ├── domain/Exercise.java                          ← 改（加 instructionsZh）
    ├── service/
    │   ├── ExerciseInstructionsZhService.java        ← 新（仿 ExerciseNameZhService）
    │   └── ExerciseDetailService.java                ← 新（详情聚合）
    ├── config/ExerciseInstructionsZhRunner.java      ← 新（仿 ExerciseNameZhRunner）
    └── web/ExerciseController.java                   ← 改（加 GET /{id}）

frontend/src/
├── api/exercise-detail.ts                            ← 新（或并入 training.ts，见 Task 6）
└── views/TrainingView.vue                            ← 改（加「看做法」入口 + 详情面板）
```

---

## Task 1: 迁移 V9 + 实体字段

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__exercise_instructions_zh.sql`
- Modify: `backend/src/main/java/com/change/domain/Exercise.java`
- Modify: `backend/src/test/java/com/change/MigrationTest.java`（冻结 V9 的 checksum）
- Modify: `backend/src/test/java/com/change/SchemaTest.java`（如果它有 `exercise` 的列快照）

- [ ] **Step 1: 先读真实的既有形态，别照抄本计划**

```bash
grep -n "ADD COLUMN" backend/src/main/resources/db/migration/V8__exercise_category_and_instructions.sql
grep -n "instructions\|category" backend/src/main/java/com/change/domain/Exercise.java
```

- [ ] **Step 2: 写迁移**

```sql
-- 动作的分步做法（中文）。
--
-- exercise.instructions 是英文原文（计划 2 · Task 3 导入的，871 条非空）。
-- 这一列放中文，由 seed/instructions-zh.json 在启动时写入。
--
-- 为什么单独一列而不是覆盖原列：
--   1. 英文原文是**上游数据**，覆盖了就再也拿不回来（重新导入要网络）；
--   2. 中文是**派生**的，源头改了可以重生成；
--   3. 出问题（译文错、漏译）时，原文还能兜底显示。
ALTER TABLE exercise ADD COLUMN instructions_zh TEXT;
```

- [ ] **Step 3: 实体加字段**

照 `Exercise.instructions` 的写法加一个 `public String instructionsZh;`（`@Column(name = "instructions_zh")`）。

- [ ] **Step 4: 跑全量测试，按需更新两个快照测试**

Run: `cd backend && mvn -B clean test`

Expected: **`SchemaTest` 与 `MigrationTest` 会红**——它们对 `exercise` 的列与已发布迁移的 checksum 有精确快照。**这是设计如此**（新列/新迁移必须被显式确认一次）。

**按报错改那两个测试，但只改「把新列/新 checksum 加进清单」**——如果它们要求的改动超出这个范围，**停下来报告**。

**V9 的 checksum 必须从实跑后的 `flyway_schema_history` 读出，不能猜。**

- [ ] **Step 5: 提交**

---

## Task 2: 翻译流水线（目录、批模板、合并脚本、校验）

**这一任务只搭骨架，不翻任何一条真实的动作**——它让后面 22 批有共同形状。

**Files:**
- Create: `backend/src/main/resources/seed/instructions-zh/MANIFEST.md`
- Create: `scripts/merge-instructions-zh.py`（或放 `tools/`，按项目现有惯例）
- Test: `backend/src/test/java/com/change/seed/InstructionsZhFixturesTest.java`（校验合并产物）

- [ ] **Step 1: 定分批产物的形状**

每个 `part-NN.json`：

```json
{
  "batch": 1,
  "range": "aliases 1-40 of the dataset order",
  "items": {
    "3/4 Sit-Up": ["躺在地板上，脚勾住固定物。双腿屈膝。", "双手置于脑后或头两侧。……"],
    "90/90 Hamstring": ["……"]
  }
}
```

- **键是 `nameEn`**，与 `name-zh.json` 同一套键（这样两边可以按名字对齐）。
- **值是一段段的中文**（数组，不是一整段用 `\n` 拼）——**保留段落结构**，否则前端没法做有序列表。
- **每条的段数必须与英文原文一致**。少一段 = 丢了一步做法，而这在页面上看不出来。

- [ ] **Step 2: 写合并脚本**

`merge-instructions-zh.py` 要做：

1. 读 `seed/instructions-zh/part-*.json`，按文件名排序
2. 检测**重复键**（同一动作出现在两批里）→ 报错退出
3. 检测**段数与英文原文不一致** → 报错并列出是哪几条
4. 输出 `seed/instructions-zh.json`（`{"<nameEn>": ["段1","段2",...]}`，键按数据集顺序）
5. 打印：总条数、总段落数、**覆盖率（应为 871/871）**、跳过条数。英文原文为空的 5 条没有可翻的内容、**不该出现在产物里**——所以产物是 **871 条**而不是 876。

**英文原文从哪拿**：**仓库里已经 vendor 好了**——`backend/src/main/resources/seed/free-exercise-db/exercises.json`（计划 2 · Task 3 放的，1,005,327 字节、876 条）。**不要**去读 `E:\devtools\cache\fedb\` 那个临时缓存（它随时可能被清掉，而且不该出现在任何脚本里）。

同理，`schema.json` 与 `LICENSE.md` 也在那个目录下。

- [ ] **Step 3: 写校验测试**

`InstructionsZhFixturesTest` 至少断言：

1. 合并产物存在、**871 条**、键集合 == `name-zh.json` 的键**去掉那 5 条原文为空的**（并显式断言这 5 条**不在**产物里）
2. 每条的段数 == 英文原文的段数
3. 没有任何一条是空串数组
4. 全是中文（`chars().anyMatch(汉字)`），不是把英文抄回来

> 这条测试跑在**仓库里的产物**上，不需要网络——它守的是「合并之后没丢段、没漏条」。

- [ ] **Step 4: 提交**

---

## Task 3: 试点 40 条 + 定死翻译约定

**先翻 40 条，人工看质量，再决定后面 21 批怎么写。** 不先试点就直接翻 836 条，风格错了要全部返工。

**Files:**
- Create: `backend/src/main/resources/seed/instructions-zh/part-01.json`（40 条）

- [ ] **Step 1: 翻译约定（写进 MANIFEST.md，后面每批都按它）**

| 规则 | 为什么 |
|---|---|
| **器械名与动作名保留通用译法**（barbell → 杠铃、dumbbell → 哑铃、cable → 绳索、machine → 器械、body only → 自重） | 与 876 条中文名里已用的译法保持一致 |
| **段数必须一一对应** | 段数是「步骤序号」；合并与前端列表都依赖它 |
| **不翻译数字与单位**（8-12 reps、45 秒、30 度） | 数字翻错比不翻更糟 |
| **专业术语用健身圈的通行说法**：`reps` → 次数、`set` → 组、`eccentric` → 离心、`concentric` → 向心、`peak contraction` → 顶峰收缩、`range of motion` → 活动范围、`spotter` → 保护者 | 用直译会读不懂 |
| **第二人称祈使句**（「躺下…」「保持…」），不用「你应该…」 | 操作指引的通行写法 |
| **不增不减内容**：不补充安全提示、不省略步骤 | 译文不是再创作；安全提示应由我们单独写，不由翻译夹带 |

- [ ] **Step 2: 翻第一批 40 条**

按数据集顺序的前 40 条（`free-exercise-db` 的 `dist/exercises.json` 顺序），落盘 `part-01.json`。

**质量自查**（写进报告）：

- 抽查 3 条，把中文与英文原文并列给出
- 确认段数一一对应
- 确认没有把数字写错

- [ ] **Step 3: 让用户看一眼这 40 条**

**这是本计划唯一一次停下来等确认。** 把 3 条并列贴出来，问「这个风格行不行」。**行 → 继续；不行 → 改约定，重翻这 40 条（很便宜）。**

- [ ] **Step 4: 提交**

---

## Task 4: 翻完剩余 836 条（21 批）

**Files:**
- Create: `part-02.json` … `part-22.json`

- [ ] **Step 1: 按 40 条一批切**

剩 836 条 → 21 批（每批 40，最后一批 36）。批次划分**按数据集顺序**（与 `part-01` 连续），这样「哪条在哪批」是确定的、可复现的。

**批边界必须写进 MANIFEST.md**（第几批 = 数据集的第几条到第几条），否则将来没法验证「有没有漏」。

- [ ] **Step 2: 逐批翻，每批落自己的文件**

**每批是独立任务**。批与批之间**没有依赖**，所以可以并行（各自写自己的文件）；但**合并必须在全部完成后做**（Task 5）。

**每批做完都要**：

1. 段数与英文原文对照（脚本或人工）
2. 落盘 `part-NN.json`
3. 提交（一批一个提交，或几批合并一个提交——都行，但**每批的文件必须是一次成型的**，不要留下半成品）

- [ ] **Step 3: 全部完成后跑合并脚本**

Run: `python scripts/merge-instructions-zh.py`

Expected: **`871/871` 覆盖**、`段数全部一致`、无重复键、跳过 5 条。

**任何一条不满足就停下来修那一批**，不要用「跳过」的方式让脚本通过。

- [ ] **Step 4: 跑校验测试**

Run: `cd backend && mvn -B -q -Dtest=InstructionsZhFixturesTest test`

- [ ] **Step 5: 提交**

---

## Task 5: 写入服务 + 启动 Runner

**完全照 `ExerciseNameZhService` / `ExerciseNameZhRunner` 的形状做**——先读那两个文件，再写。

**Files:**
- Create: `backend/src/main/java/com/change/service/ExerciseInstructionsZhService.java`
- Create: `backend/src/main/java/com/change/config/ExerciseInstructionsZhRunner.java`
- Test: `backend/src/test/java/com/change/service/ExerciseInstructionsZhServiceTest.java`

- [ ] **Step 1: 写失败的测试**

至少覆盖：

1. 给定一份 `{nameEn: [段]}`, 写入后库里 `instructions_zh` 是 `段1\n段2\n…`
2. **已经有的行跳过**（幂等，可重跑）
3. **英文原文为空的 5 条**：中文也不写（没有原文就没有可翻的东西），计数里单独一档
4. **映射里出现库里没有的动作名** → 计入「找不到」而不是抛异常（与 `ExerciseNameZhService` 的 `skippedNotImported` 同一个处理）
5. 返回的 `Result` 计数正确

- [ ] **Step 2: 实现**

要点：

- `Result` 用 record，字段仿 `ExerciseNameZhService.Result`（`updated` / `skippedAlready` / `skippedEmptySource` / `skippedNotImported`）
- `applyMappingFromClasspath()` 读 `seed/instructions-zh.json`
- 写库用 `JdbcTemplate`（与中文名那套一致，不引入 JPA 的批量写）

- [ ] **Step 3: 启动 Runner**

`@Component` + `ApplicationArguments` runner，**排在中文名 Runner 之后**（用 `@Order` 或按名字判断）。

日志格式照中文名那条：`动作中文做法：写入 871 / 已有跳过 0 / 原文为空 5 / 找不到导入动作 0`。

- [ ] **Step 4: 跑全量测试**

Run: `cd backend && mvn -B clean test`

- [ ] **Step 5: 提交**

---

## Task 6: `GET /api/exercises/{id}` 详情端点

**这是本计划的关键一步**——把已经存在但没人用的 `instructions` 暴露出来。

**Files:**
- Create: `backend/src/main/java/com/change/service/ExerciseDetailService.java`
- Modify: `backend/src/main/java/com/change/web/ExerciseController.java`
- Test: `backend/src/test/java/com/change/web/ExerciseControllerTest.java`（该文件已存在，**加测试不要重写**）

- [ ] **Step 1: 写失败的测试**

至少覆盖：

1. `GET /api/exercises/{id}` → 200，返回 `{id, nameEn, nameZh, equipment, isCompound, category, instructions, instructionsZh, muscles: [{muscleGroupId, nameZh, nameEn, role}]}`
2. **`muscles` 里主要肌群在前、次要肌群在后**，各带中文名
3. **没有 `instructionsZh` 的动作**：`instructionsZh` 为 `null`（不是空串），`instructions` 照常有值
4. **`instructions` 也为空的那 5 条**：两个都是 `null`，**不 404**
5. 不存在的 id → **404** + `{error}`
6. **响应形状断言**（键集合）——照计划 6a `ProgressControllerTest` 的做法，因为前端会手写一份 TS interface，两处定义可以漂

- [ ] **Step 2: 实现**

要点：

- **一次查三张表**（`exercise` / `exercise_muscle` / `muscle_group`），不要 N+1
- 肌群按 `role` 排序：`primary` 在 `secondary` 之前；同 role 内按 `muscle_group.id`
- 404 用 `ResponseStatusException(NOT_FOUND, "找不到这个动作: " + id)` → `ApiErrors` 翻成 `{error}`
- **不要**在详情里返回 `source_id` / `note` / `archived`（前端不需要，返回了就是又一处要维护的契约）

- [ ] **Step 3: 跑测试**

Run: `cd backend && mvn -B clean test`

- [ ] **Step 4: 用真实 HTTP 打一遍**

起后端（干净库或现成库都行），贴出**一条真实响应**（含 `instructionsZh` 与`muscles`），以及一条 404 的响应。

> 照计划 6a Task 2 的做法。**必须贴原始 JSON**——它是 Task 7 前端 fixture 的出处。

- [ ] **Step 5: 提交**

---

## Task 7: 前端动作详情面板

**Files:**
- Create: `frontend/src/api/exercise-detail.ts`
- Modify: `frontend/src/views/TrainingView.vue`
- Test: `frontend/src/api/exercise-detail.spec.ts`、`frontend/src/views/ExerciseDetailPanel.spec.ts`
- Create（建议）：`frontend/src/components/ExerciseDetailPanel.vue`

- [ ] **Step 1: 先读现约定**

```
frontend/src/api/training.ts          （callApi / ApiError）
frontend/src/views/TrainingView.vue   （现有交互：选肌群 → 选动作 → 填组）
frontend/src/views/BodyDataView.vue   （最新的成品页：token 用法、空态）
frontend/src/api/body.ts              （跟 './training' 的关系）
```

- [ ] **Step 2: 写失败的测试**

客户端：

1. `exerciseDetail(id)` 的 path 精确 `'/api/exercises/' + id`
2. 404 抛 `ApiError`，`message` 是后端文案
3. `instructionsZh` 为 `null` 时不抛错

组件（`ExerciseDetailPanel`）：

1. 显示中英名、器械、分类、复合/孤立
2. **目标肌群分区显示**：主要 / 次要
3. **有中文做法 → 按段落渲染成有序列表**
4. **没有中文做法但英文有 → 显示英文 + 一行「这条还没有中文说明」**
5. **两个都没有 → 显示「这条没有做法说明」，不是空白**
6. **搜索链接区**：两条链接文案里分别含**中文名**与**英文名**，且是 `https://` 开头的完整 URL
7. **复制按钮**：点一下调用 `navigator.clipboard.writeText`（测试里 mock），**且存在失败降级**（clipboard 不可用时把 URL 显示出来让人手选）
8. 颜色全部来自 token（照 Task 5/6 的 `?raw` 式断言）

- [ ] **Step 3: 实现**

**面板形态**：训练记录页里选中的动作旁出现「看做法」按钮 → 点击打开一个**右侧抽屉或弹层**（不跳页——那会丢掉已经填的组）。

要点：

- 面板打开时 `void exerciseDetail(id)` 拉数据；**加载中/失败/成功三态都要有**
- **搜索链接不长按就自动打开**——按你的决定，只给文本 + 复制按钮。URL 用模板拼：
  - B 站：`https://search.bilibili.com/all?keyword=` + `encodeURIComponent(nameZh || nameEn)`
  - YouTube：`https://www.youtube.com/results?search_query=` + `encodeURIComponent(nameEn + ' exercise form')`
- **`<a href>` 不要用**：Tauri 的 WebView 里点它会**把应用导航走**（页面变空白），除非另装 opener。只显示成 `<code>` 文本 + 复制按钮
- 中文做法用 `<ol>` 渲染（段数就是步骤数）

- [ ] **Step 4: 跑测试 + 构建**

Run:
```powershell
cd frontend
pnpm test
pnpm build
```

Expected: 全绿（**总条数 = 216 + 你新增的**）；`pnpm build` **退 0**。

- [ ] **Step 5: 提交**

---

## Task 8: 端到端验收

- [ ] **Step 1: 干净库全链路**

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:CHANGE_DATA_DIR = "$env:TEMP\change-detail-e2e"
Remove-Item -Recurse -Force $env:CHANGE_DATA_DIR -ErrorAction SilentlyContinue
mvn -B -q -DskipTests package
java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

**逐条核对**（记录实际值）：

| # | 做什么 | 期望 |
|---|---|---|
| 1 | 启动日志 | 动作库导入 876 → 动作中文名 876 → **动作中文做法 871** → 食物 35 → `CHANGE_PORT` |
| 2 | `GET /api/exercises/{id}` 一个常见动作 | `instructions` 与 `instructionsZh` **都有**，`muscles` 有主要与次要 |
| 3 | 那 5 条 `instructions` 为空的 | 两个都是 `null`，**不是 404** |
| 4 | 不存在的 id | `404 {error}` |
| 5 | `instructionsZh` 的段落数 | 与英文原文段数**一致**（抽 3 条核） |
| 6 | 关掉后端 | 无残留 java 进程 |

- [ ] **Step 2: 记进总览**

在 `docs/superpowers/plans/2026-10-14-change-phase1-overview.md` 的「计划 6a」之后插入一节，写清：翻译条数、批次数、Coverage、以及「视频只给链接、不拉起浏览器」这个决定及其理由。

- [ ] **Step 3: 提交**

---

## 完成标准

- [ ] `cd backend && mvn -B clean test` 全绿，**报告实际条数**（基线 315）
- [ ] `cd frontend && pnpm test` 全绿（基线 216）；`pnpm build` 退 0
- [ ] **876 条里 871 条有中文做法**（5 条原文为空）；`instructions-zh.json` 覆盖 **871/871**（不是 876——那 5 条不该在产物里）
- [ ] **每条的段数与英文原文一致**（由合并脚本与 `InstructionsZhFixturesTest` 双重守）
- [ ] `GET /api/exercises/{id}` 返回 `instructions` / `instructionsZh` / `muscles`，有 404
- [ ] 前端面板三种状态都覆盖：有中文 / 只有英文 / 都没有
- [ ] 搜索链接**可复制**，且 clipboard 不可用时有降级
- [ ] **没有新增任何外壳能力**（`capabilities` 与 `generate_handler!` 不变）
- [ ] `git status --short` 无输出

## 实施修正（落地时发现的问题）

### 1. 计划自相矛盾：产物该 876 条还是 871 条（已解决）

见上面那一节「一处已解决的规格冲突」。结论是 **871**。

### 2. `POST /api/exercises/unimport` 在有训练记录时会**挂死**（**已知缺陷，本计划不修**）

**现象**（2026-10-19 实测，`java -jar` + 干净库 + 真实 HTTP）：

| 情况 | 结果 |
|---|---|
| 干净库（没有训练记录） | `HTTP 200`，**0.0 秒**，`{"removed":876}` |
| 录一次训练（引用其中一个导入动作）后再调 | **挂死 >60 秒**，不返回、不报错 |

**数据完好无损**：动作 876、训练记录 1、`session_exercise` 1、孤儿行 **0**。

**机制**：`session_exercise.exercise_id` 的外键是 `REFERENCES exercise(id)`，**没有 `ON DELETE CASCADE`**（只有 `exercise_muscle` 有）。所以 `DELETE FROM exercise WHERE source_id LIKE …` 删到被训练记录引用的那一行时，外键检查会撞上它。
另一个诱因：`session_exercise.exercise_id` **没有单列索引**（只有 `session_id` 上的），而 SQLite 的外键检查要按这一列反查。一个逻辑问题叠一个物理问题。

**是谁引入的**：计划 2（导入/移除动作库），**不是本计划**。
**用户会碰到吗**：**不会**——前端没有任何地方调用 `unimport`，只有端点存在，要碰得直接用 HTTP。

**为什么本计划不修**：

1. **它不可达。** 修一个用户碰不到的东西，优先级低于把用户要的功能做完。
2. **修它要先定一个产品语义。**「移除动作库」遇上「有训练记录引用了这些动作」时，正确行为是什么？三个选项都不显然：

   | 选项 | 后果 |
   |---|---|
   | 跳过被引用的，报告「移除 870 个 / 保留 6 个」 | 最诚实，但动作库不再完整 |
   | 一并删掉训练记录 | **绝对不行**——那是用户的历史 |
   | 拒绝并说明「你有训练记录，不能移除」 | 比挂死好，但等于这个功能对老库永久失效 |

   这是**产品决定**，不该在一个前端任务里顺手定了。

**将来怎么修**：删除时**跳过被引用的动作并报告数量**（而不是静默失败），同时给 `session_exercise.exercise_id` 加索引。

**为什么这个缺陷的严重度可控**：它现在表现为**挂死**，不是**静默毁数据**。挂死可发现，毁数据不可发现。

### 3. 提示词里写错过一个事实

Task 20 的提示词里我写「第 797 条英文 5 段」，**实际是 6 段**（我凭早先探测的片段猜的）。实施者没有盲从，去数了真实数据并写进报告。

这类错比代码错更隐蔽：它不报错，只会让执行者按错的预期裁剪内容。

---

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| **翻译量是 22 批** | 876 条（实际可翻 **871**）× 平均 **4.27** 段。一批写错要重做 | 先做 Task 3 的 40 条试点，**风格确认后**再批量；分批落盘可续跑 |
| **段数对不上** | 少一段 = 丢一步做法，页面上看不出来 | 合并脚本按英文原文核段数；`InstructionsZhFixturesTest` 再核一遍 |
| 译文夹带 | 模型容易「顺手补充安全提示」 | 翻译约定里明写「不增不减」；抽查时专门看这一条 |
| 336 KB 的 seed 进 jar | jar 会变大 | 可忽略（当前 83 MB，涨幅 0.4%）；真要压可以合并后 gzip，但先不做 |
| 前端 `<a href>` 把应用导航走 | Tauri WebView 里点链接会离开应用 | **只用文本 + 复制**，不用 `<a>`。这是本计划的硬约束 |
| 用户想「点一下就看」 | 现在是复制再切浏览器，多两步 | 已确认按你的决定做；将来想一键开，加一个 `open_external` 命令即可（那份工作需要 host 白名单 + 偏离登记） |

## 非目标（本计划不做）

- **不在应用内播放视频**（没有合法来源）
- **不拉起浏览器**（本计划的明确决定）
- **不 vendor 那 1,746 张起止姿势图**（体积；将来要另做决定）
- **不翻译 `instructions` 以外的东西**（`name_zh` 已有）
- **不做动作的「收藏 / 自定义」**
- **不改任何外壳命令与 capabilities**

## 下一步

本计划完成后：**6b（人体图 2D）**，以及等你定下三个决策后的 **4b（拍照/外卖/说话 → AI 草稿）**。
