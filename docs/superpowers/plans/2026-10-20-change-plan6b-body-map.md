# 计划 6b · 人体图（2D 静态热力图 + 三种查看模式）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 侧边导航多一个「人体图」→ `/body-map`。画布区是一个**SVG 人体**（正面 + 背面），17 块肌群按本周训练量着色；点某块肌肉 → 右侧面板显示该肌群的动作列表与容量进度。**三种着色模式**：容量 / 恢复中 / 长期未练。

**Architecture:** 设计文档的**决定 5** 把这件事封成了「第一阶段 2D 静态热力图 + 界面位与契约全部冻结，第二阶段只换渲染层」。本计划就是那个第一阶段。渲染层是纯内联 SVG（无图表库），路径数据从 `vulovix/body-muscles`（Apache-2.0）vendor 进项目；后端加一个只读端点，返回每块肌群的本周组数与「上次训练日期」。

**Tech Stack:** Java 25 · Spring Boot 3.5.16 · Flyway · SQLite · Vue 3 + TS（纯内联 SVG，**不加任何依赖**）

---

## 这一份要做成什么

设计文档原文（决定 5）：

> 用户选定的角色是 **训练均衡度仪表盘**：肌肉按「本周训练组数」着色，一眼看出哪里练多、哪里漏练；点击某块肌肉 → 该肌群动作列表 + 加入训练。

**热力图算法**（设计文档已定，且**代码里已经实现了**）：

> 每记一组，该动作的 **主要肌群 +1**、**次要肌群各 +0.5**，按自然周（周一起算）汇总。

**三种查看模式**（设计文档已定）：

| 模式 | 回答的问题 | 着色依据 |
|---|---|---|
| **容量** | 我这周练了多少 | 本周累计组数 |
| **恢复中** | 今天该练哪里 | 距上次训练的天数 + 该次训练量 |
| **长期未练** | 我是不是一直漏了某块 | 距上次被训练超过 N 天 |

**「长期未练」是用户原话里「看出哪里练得少」的最直接实现**——比单纯的周容量更能暴露「三个月没练过腿」这类问题。

---

## 前置事实（全部已实测，不需要重新验证）

| 项 | 值 |
|---|---|
| 热力图算法 | **已经实现**：`WeeklyVolumeService` 经 `VolumeCalculator.accumulate` 做「主要 +1、次要 +0.5」，只算 `completed = 1`，按周一起算，返回**全部 17 块**（含 0 组的） |
| 现有端点 | `GET /api/volume/weekly?date=` → `{weekStart, weekEnd, muscles:[{muscleGroupId, nameZh, nameEn, sets, min, max, status}]}`，`status` 由 `VolumeCalculator.classify` 给出 |
| `muscle_group` 表 | **已经有 `region_ids` 列**（逗号分隔）。当前值是 `chest_left,chest_right` 这种**下划线**风格，共 **26 个** |
| 素材许可 | `vulovix/body-muscles`，**Apache-2.0**，仓库 38 KB |
| 素材区域 | **正面 40 个 + 背面 49 个 = 89 个**区域。id 是**连字符**风格且带子分组：`chest-upper-left`、`lats-mid-right`、`triceps-long-left`、`calves-gastroc-medial-left`、`gluteus-maximus-left` |
| 素材自带分组 | `MUSCLE_GROUPS` 是 8 个**粗**分组（Head & Neck / Shoulders / Arms / Chest / Back / Abdominals / Legs / Hands & Feet），与我们 17 块**不对齐**（它把 `traps` 归到肩、`gluteus` 归到背，且**没有 `abductors`**）。**不能直接用。** |
| 路由 | 由 `frontend/src/nav.ts` 派生；`BodyMapView.vue`（442 B 占位）已存在，路由已有 |
| 冻结的契约 | `<BodyMap />`：`volumeByMuscle` 入、`@muscle-click` 出 |

### 所以「纯手工活」其实只有 17 行

设计文档说「肌群映射表（683 对象名 ↔ 40–70 块肌肉 ↔ 肌群表）是全流程唯一无法回避的纯手工活」。核对下来它比想象的小得多：**17 块肌群 → 89 个区域，一张 17 行的表**。

**但「小」不等于「错不了」**：如果某个 `region_ids` 里的 id 拼错了，那块肌肉就**永远不着色**——不报错、不崩，只是那块肌肉一直是灰的，而你不会注意到「背阔肌这里应该是红的」。

**所以本计划必须有一条测试，把 `muscle_group.region_ids` 与 vendored 素材里的真实 id 集合交叉校验。这是整份计划里最重要的一条测试。**

---

## 映射表（计划里定死，Task 2 照它写 V10）

| 肌群 | 中文 | 映射到的区域 id |
|---|---|---|
| `chest` | 胸部 | `chest-upper-left`, `chest-lower-left`, `chest-upper-right`, `chest-lower-right` |
| `abdominals` | 腹直肌 | `abs-upper-left`, `abs-lower-left`, `abs-upper-right`, `abs-lower-right`, `obliques-left`, `obliques-right` |
| `lats` | 背阔肌 | `lats-upper-left`, `lats-mid-left`, `lats-lower-left`, `lats-upper-right`, `lats-mid-right`, `lats-lower-right` |
| `middle back` | 中背部 | `traps-mid-left`, `traps-mid-right` |
| `lower back` | 下背部 | `lower-back-erectors-left`, `lower-back-ql-left`, `lower-back-erectors-right`, `lower-back-ql-right` |
| `traps` | 斜方肌 | `traps-upper-left`, `traps-lower-left`, `traps-upper-right`, `traps-lower-right` |
| `shoulders` | 肩部 | `shoulder-front-left`, `shoulder-side-left`, `deltoid-rear-left`, `shoulder-front-right`, `shoulder-side-right`, `deltoid-rear-right` |
| `biceps` | 肱二头肌 | `biceps-left`, `biceps-right` |
| `triceps` | 肱三头肌 | `triceps-long-left`, `triceps-lateral-left`, `triceps-long-right`, `triceps-lateral-right` |
| `forearms` | 前臂 | `forearm-left`, `forearm-flexors-left`, `forearm-extensors-left`, `forearm-right`, `forearm-flexors-right`, `forearm-extensors-right` |
| `quadriceps` | 股四头肌 | `quads-left`, `quads-right` |
| `hamstrings` | 腘绳肌 | `hamstrings-medial-left`, `hamstrings-lateral-left`, `hamstrings-medial-right`, `hamstrings-lateral-right` |
| `glutes` | 臀肌 | `gluteus-maximus-left`, `gluteus-maximus-right` |
| `calves` | 小腿 | `calves-gastroc-medial-left`, `calves-gastroc-lateral-left`, `calves-soleus-left`, `calves-gastroc-medial-right`, `calves-gastroc-lateral-right`, `calves-soleus-right` |
| `adductors` | 内收肌 | `adductors-left`, `adductors-right` |
| `abductors` | 外展肌 | `gluteus-medius-left`, `gluteus-medius-right` |
| `neck` | 颈部 | `neck-left`, `neck-right`, `nape` |

**共 65 个区域被映射**，剩下 24 个**刻意不映射**：`head` / `face` / `head-back`（头脸不是训练目标）、`spine`（是标志线不是肌肉）、`serratus-anterior-*`（前锯肌，我们 17 块里没有）、`tibialis-anterior-*`（胫骨前肌，同上）、`hip-flexor-*`（髂腰肌，同上）、`elbow-*` / `knee-*` / `knee-back-*` / `hand-*` / `hand-back-*` / `foot-*` / `foot-back-*`（关节与四肢末端）。

### 两个必须写进注释的解剖学判断

1. **`abductors`（外展肌）映射到 `gluteus-medius`**：解剖上髋外展肌就是臀中肌/臀小肌。素材没有单独的 `abductors` 区域。
2. **`glutes`（臀肌）只映射 `gluteus-maximus`**，把 `gluteus-medius` 留给 `abductors`——**否则两块肌群会共用同一片区域，着色时互相覆盖**。

---

## 全局约定

| 项 | 约定 |
|---|---|
| 迁移 | 只增不改。本计划新增 **V10**（`UPDATE muscle_group SET region_ids = …`） |
| 素材归属 | vendor 的文件放 `frontend/src/assets/body-map/`，**同时放 LICENSE 与 NOTICE**。Apache-2.0 要求保留版权与许可声明 |
| 依赖 | **一个都不加**（不装 `body-muscles` 包，不装图表库）。只用 vendor 进来的路径数据 |
| 颜色 | 只能来自 `theme.css` 的 token。热力色阶用 `color-mix()` 或 `opacity` 从 `--accent` 派生，**不新增十六进制** |
| 端点命名 | 设计文档冻结的是 `GET /api/body-map/volume`。**照它做**，不要改成 `/api/volume/...` |

---

## 文件结构

```
backend/src/main/
├── resources/db/migration/
│   └── V10__muscle_region_ids.sql                  ← 新：把 region_ids 改成素材的 id
└── java/com/change/
    ├── service/BodyMapService.java                 ← 新（本周组数 + 上次训练日期）
    └── web/BodyMapController.java                  ← 新（GET /api/body-map/volume）

frontend/src/
├── assets/body-map/
│   ├── muscles.front.ts                            ← vendor（原样，含出处头注释）
│   ├── muscles.back.ts                             ← vendor
│   ├── LICENSE                                     ← Apache-2.0 原文
│   └── NOTICE                                      ← 版权声明 + 来源 URL + commit
├── components/
│   ├── BodyMap.vue                                 ← 新（冻结契约：volumeByMuscle 入 / @muscle-click 出）
│   └── BodyMapSidePanel.vue                        ← 新（右panel）
├── api/body-map.ts                                 ← 新
├── views/BodyMapView.vue                           ← 改（现在是 442 B 占位）
└── nav.ts                                          ← 已有「人体图」项则不动
```

---

## Task 1: vendor 素材 + 归属声明

**Files:**
- Create: `frontend/src/assets/body-map/muscles.front.ts`
- Create: `frontend/src/assets/body-map/muscles.back.ts`
- Create: `frontend/src/assets/body-map/LICENSE`
- Create: `frontend/src/assets/body-map/NOTICE`

- [x] **Step 1: 下载并 vendor**

```powershell
$dst = 'frontend/src/assets/body-map'
New-Item -ItemType Directory -Force -Path $dst | Out-Null
# 三个文件都从 fastly.jsdelivr.net 拿（raw.githubusercontent.com 在本机被墙）
Invoke-WebRequest 'https://fastly.jsdelivr.net/gh/vulovix/body-muscles@main/src/data/muscles.front.ts' -OutFile "$dst/muscles.front.ts"
Invoke-WebRequest 'https://fastly.jsdelivr.net/gh/vulovix/body-muscles@main/src/data/muscles.back.ts'  -OutFile "$dst/muscles.back.ts"
Invoke-WebRequest 'https://fastly.jsdelivr.net/gh/vulovix/body-muscles@main/LICENSE'                -OutFile "$dst/LICENSE"
```

**记录你实际用的 commit sha**（`@main` 会漂）：查 `https://api.github.com/repos/vulovix/body-muscles/commits/main` 拿 sha，写进 NOTICE。

- [x] **Step 2: 写 NOTICE**

必须含：项目名、作者、来源 URL、**实际使用的 commit sha**、许可（Apache-2.0）、以及一句「文件原样 vendor，未修改」。

- [x] **Step 3: 给两个 .ts 文件加出处头注释**

在两个文件**开头**加一段注释（**不改文件里任何其它内容**），写明来源、sha、许可、以及「未修改」。

- [x] **Step 4: 确认它能被 import**

写一个临时脚本（或直接跑一次 `pnpm build`）确认这两个文件能被 TS 正常解析——它们是别人仓库里的 TS，可能引用它自己的类型。

**如果它们 import 了自己的 `../types`，把那些被引用的类型也一起 vendor，或者把 import 去掉并内联类型。** 说明你选了哪种、为什么。

- [x] **Step 5: 提交**

---

## Task 2: V10 迁移 + 交叉校验测试（**本计划最重要的一步**）

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__muscle_region_ids.sql`
- Create: `frontend/src/assets/body-map/region-ids.ts`（把素材里的 id 抽成一个常量数组，供测试与运行时共用）
- Test: `backend/src/test/java/com/change/MuscleRegionMappingTest.java`

- [x] **Step 1: 写迁移**

按上面那张映射表，17 条 `UPDATE`：

```sql
-- V10: 把 muscle_group.region_ids 改成 vendored 人体图素材里的真实区域 id。
--
-- V7 当初填的是 `chest_left,chest_right` 这种下划线风格——那时还没有素材，
-- 名字是占位的。本迁移把它们换成 `vulovix/body-muscles`（Apache-2.0）里的真实 id，
-- 风格是连字符 + 子分组：`chest-upper-left`、`lats-mid-right`。
--
-- 为什么必须换：前端拿 region_ids 去 SVG 里找同 id 的 path 着色。
-- **id 对不上不会报错**，那块肌肉只是永远不着色——而你不会注意到
-- 「背阔肌这里应该是红的」。所以 MigrationTest 之外还要有一条交叉校验测试。
UPDATE muscle_group SET region_ids = 'chest-upper-left,chest-lower-left,chest-upper-right,chest-lower-right' WHERE name_en = 'chest';
-- ……其余 16 条，照映射表
```

- [x] **Step 2: 抽出 region-ids.ts**

从 vendored 的两个文件里**用脚本**（不是手抄）抽出全部 `id`，生成：

```ts
export const FRONT_REGION_IDS = [...] as const
export const BACK_REGION_IDS = [...]
export const ALL_REGION_IDS = [...FRONT_REGION_IDS, ...BACK_REGION_IDS]
```

**必须是脚本抽的**，因为手抄 89 个 id 一定会错一个。

- [x] **Step 3: 写交叉校验测试（核心）**

`MuscleRegionMappingTest` 至少断言：

1. `muscle_group` 表里 17 行的 `region_ids` 都非空
2. **每一条 region id 都在素材的真实 id 集合里**（这是核心）
3. 素材里被映射的 id **没有重复**（同一个 id 不能同时属于两块肌群——否则着色会互相覆盖，这正是 `glutes` / `abductors` 那条注释防的）
4. 映射覆盖的区域数 == 65（数字变了就说明映射被改过，需要显式确认）

**id 集合从哪来**：测试读 `frontend/src/assets/body-map/muscles.{front,back}.ts` 文本，用正则抽 `id:`——**不要手抄一份到测试里**，那样两边可以一起错。

> 这条测试的本质：**把「漂」的一方（DB）与「不漂」的一方（vendored 素材）对起来**。素材是别人仓库的快照、不会变；DB 是我们的、会被改。测试守住的是「改 DB 时别写错 id」。

- [x] **Step 4: 更新 MigrationTest 的 checksum**

V10 的 checksum 从实跑后的 `flyway_schema_history` 读出，**不能猜**。

- [x] **Step 5: 跑全量测试**

Run: `cd backend && mvn -B clean test`

- [x] **Step 6: 提交**

---

## Task 3: 后端 `GET /api/body-map/volume`

**Files:**
- Create: `backend/src/main/java/com/change/service/BodyMapService.java`
- Create: `backend/src/main/java/com/change/web/BodyMapController.java`
- Test: `backend/src/test/java/com/change/service/BodyMapServiceTest.java`、`backend/src/test/java/com/change/web/BodyMapControllerTest.java`

- [x] **Step 1: 写失败的测试**

至少覆盖：

1. 返回**全部 17 块**（含本周 0 组、从没练过的）——「没练」必须以 0 出现，不能从列表里消失
2. `sets` 用「主要 +1、次要 +0.5」口径（**复用 `WeeklyVolumeService`，不要重写一份**）
3. `lastTrainedOn`：某肌群最后一次被训练（`completed=1`）的日期；从没练过 → **`null`**
4. `daysSince`：`today - lastTrainedOn`；从没练过 → **`null`**（不是 0、不是很大的数——0 会被误读成「今天练了」）
5. 响应形状断言（键集合）——照 `ProgressControllerTest` / `ExerciseControllerTest` 的做法
6. `?date=` 不给时默认今天

- [x] **Step 2: 实现**

要点：

- **`lastTrainedOn` 的 SQL**：`workout_set` → `session_exercise` → `workout_session` → `exercise_muscle`，`GROUP BY em.muscle_group_id`，取 `MAX(s.session_date)`，`ws.completed = 1`
- **本周组数直接复用** `WeeklyVolumeService.weekly()` 的结果，不要写第二份权重逻辑（两份就会漂）
- 响应：`{weekStart, weekEnd, today, muscles:[{muscleGroupId, nameZh, nameEn, sets, min, max, status, lastTrainedOn, daysSince, regionIds}]}`
  - **`regionIds` 一起返回**，让前端不需要自己维护一份映射（前端只做「id → SVG path」的匹配）

- [x] **Step 3: 跑测试**

- [x] **Step 4: 真实 HTTP 打一遍**

贴一条真实响应（含至少一块 `lastTrainedOn` 非 null 的）。

- [x] **Step 5: 提交**

---

## Task 4: `<BodyMap />` 组件（冻结契约）

**Files:**
- Create: `frontend/src/components/BodyMap.vue`
- Test: `frontend/src/components/BodyMap.spec.ts`

- [x] **Step 1: 契约先定死（设计文档冻结的）**

```ts
defineProps<{
  volumeByMuscle: Record<number, { regionIds: string[]; sets: number; ... }>
  mode: 'volume' | 'recovering' | 'neglected'
}>()
defineEmits<{
  (e: 'muscle-click', muscleGroupId: number): void
}>()
```

**这两个名字是设计文档冻结的，不要改名**（第二阶段换 Three.js 时只换渲染层，契约不动）。

- [x] **Step 2: 写失败的测试**

至少覆盖：

1. **渲染出正反两面**，且区域总数 == 素材的 89
2. **有值的区域带上了着色标记**（比如 `data-intensity` 或 `fill` 不是基线色）
3. **0 组的区域也渲染**（只是不着色）——不能因为 0 就消失
4. **点某块区域 emit `muscle-click` 且参数是该肌群的 `muscleGroupId`**（不是区域 id）
5. **同一肌群的左右两块会一起亮**（`chest-left` 与 `chest-right` 同属 `chest`）
6. 三种 `mode` 的着色函数不同（给同一份数据、不同 mode，断言着色标记不同）
7. 颜色全部来自 token（`?raw` 式断言：`var(--` > 0，`#hex` == 0）

- [x] **Step 3: 实现**

要点：

- 两面 SVG 并排（`viewBox` 各自独立），从 `assets/body-map` 读 `{id, d}`
- **着色**：`sets` → 强度（0–10 映射），用 `--accent` 的 `opacity` 或 `color-mix`，**不新增颜色字面量**
- **三种模式**（同一份数据，不同着色函数）：
  - `volume`：按本周 `sets`
  - `recovering`：按 `daysSince`（**越近越红**，0–2 天深色，3–5 天浅，>5 天不着色）
  - `neglected`：按 `daysSince`（**越远越红**，>14 天提示，>30 天最重；`null` 也算「长期未练」但要与「从没练过」区分）
- **`muscle-click` 传 `muscleGroupId`**：需要一张「区域 id → muscleGroupId」的反查表，从 `volumeByMuscle` 的 `regionIds` 反推
- 未映射的 24 个区域渲染成**基线灰**，不可点

- [x] **Step 4: 跑测试 + 构建**

- [x] **Step 5: 提交**

---

## Task 5: `BodyMapView` + 右侧面板

**Files:**
- Modify: `frontend/src/views/BodyMapView.vue`
- Create: `frontend/src/components/BodyMapSidePanel.vue`
- Create: `frontend/src/api/body-map.ts`
- Test: `frontend/src/api/body-map.spec.ts`、`frontend/src/views/BodyMapView.spec.ts`

- [x] **Step 1: 客户端**

`bodyMapVolume(date?)` → `GET /api/body-map/volume`，走 `callApi`。**用 Task 3 Step 4 的真实响应当 fixture**，注释写明出处。

- [x] **Step 2: 写失败的测试**

1. 页面加载 → 请求发出 → 画布渲染、**默认模式是「容量」**
2. **切模式按钮有三个**，点击切换着色（断言 `mode` 传给了 `<BodyMap />`）
3. **点一块肌肉 → 右侧面板打开**，显示该肌群中文名、本周组数、`min`/`max`、状态
4. **面板里的动作列表**：该肌群的动作（复用 `searchExercises({muscleGroupId})`）
5. **没选中任何肌肉时的面板态**不是空白（写一句引导）
6. **加载中 / 失败**两态都有
7. 颜色全部来自 token

- [x] **Step 3: 实现**

**布局**：左侧画布区（占大头）+ 右侧面板（固定宽）。模式切换在画布区顶部。

**右侧面板内容**（照设计文档的流程 ④）：肌群中文名、本周组数 / 建议区间、状态、该肌群的动作列表。

- [x] **Step 4: 跑测试 + 构建**

- [x] **Step 5: 提交**

---

## Task 6: 端到端验收

- [x] **Step 1: 干净库全链路**

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:CHANGE_DATA_DIR = "$env:TEMP\change-6b-e2e"
Remove-Item -Recurse -Force $env:CHANGE_DATA_DIR -ErrorAction SilentlyContinue
mvn -B -q -DskipTests package
java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

**逐条核对**：

| # | 做什么 | 期望 |
|---|---|---|
| 1 | `GET /api/body-map/volume` 空库 | **17 块全部返回**，`sets` 全 0、`lastTrainedOn` 全 null |
| 2 | 录一次训练（杠铃卧推，3 组） | — |
| 3 | 再查 | `胸部` `sets = 3`（3 组 × 主要 +1）、`肱三头肌` 与 `肩部` `sets = 1.5`（3 × 0.5） |
| 4 | `lastTrainedOn` / `daysSince` | 那两个肌群非 null，`daysSince = 0`；其余肌群仍是 null |
| 5 | `regionIds` | 胸部是 4 个 `chest-*-*` id，且**都在素材里存在** |
| 6 | 关掉后端 | 无残留进程 |

- [x] **Step 2: 记进总览**

在 overview 的「动作详情面板」之后插一节，写清实测数字、映射表规模（17 → 65 区域）、以及**两处刻意不映射的判断**。

- [x] **Step 3: 提交**

---

## 完成标准

- [x] `cd backend && mvn -B clean test` 全绿，**报告实际条数**（基线 336）
- [x] `cd frontend && pnpm test` 全绿（基线 243）；`pnpm build` 退 0
- [x] **`region_ids` 里的每一个 id 都在 vendored 素材里存在**（交叉校验测试）
- [x] **同一区域不会属于两块肌群**（`glutes` / `abductors` 那条）
- [x] 空库返回 **17 块全部**（0 组不消失）
- [x] `lastTrainedOn` / `daysSince` 对「从没练过」是 **null**，不是 0
- [x] 三种模式都能切换，着色函数确实不同
- [x] 点肌肉 emit 的是 **`muscleGroupId`** 而不是区域 id
- [x] **`package.json` 一字不改**（没加任何依赖）
- [x] 素材的 LICENSE 与 NOTICE 都在，NOTICE 里记了实际 commit sha
- [x] `git status --short` 无输出

## 实施修正（落地时发现的问题）

### 1. `daysSince` 可能是**负数**——它不是 bug，但是个陷阱

`GET /api/body-map/volume` 的 `today` 是**本次请求的参照日**：省略 `?date=` 时是真实的今天，给了 `date` 就是那一天。`daysSince = today - lastTrainedOn`，而 `lastTrainedOn` 的 SQL **不带日期范围**（「上次训练」是历史上任意一天，这是对的）。

所以：**用 `?date=` 去翻一个比「最近一次训练」更早的周时，`daysSince` 会是负数。**

**这个语义是定义清楚的**（「你在这天之后练过这块肌肉」），不是错误。但它是 6b 唯一一个**只要前端传了 `date` 就会出现**的陷阱，所以：

- **Task 4 的三种着色函数必须显式处理 `daysSince < 0`**（按「最近练过」处理，也就是与 0 同档），并且**要有一条测试**造一个负数断言它不崩、不产生怪颜色
- **Task 5 的页面不许传 `date`**（它只看「本周」），并且**要有一条测试**断言调用时没有 `date` 参数

不这么做的后果：将来有人给人体图加「翻看往期」的功能时，「恢复中」模式会把一块**几天前刚练过**的肌肉显示成「很久没练」——因为负数走了 `else` 分支。**而界面上看不出来那是错的。**

### 2. 「必须复用 `WeeklyVolumeService`」这条约定，靠数字断言杀不掉

实施者实测确认：把权重逻辑**重写一份**（自己一条 SQL + 硬编码 1.0/0.5，绕开 `VolumeCalculator`）时，**逐块比对的数值断言仍然全绿**——因为算出来的值一样。

真正杀死它的是那条**替身注入**测试：给 `WeeklyVolumeService` 一个返回 `42.5 组 / 999..1000 / ABOVE` 的假实现，断言这组荒谬的数字**逐字出现在响应里**。数值一样就杀不掉，只有「让上游返回一个不可能算出来的值」才能证明我们真的在用它。

**这条记在这里，是因为它是一种可以复用的手法**：当你想守住「A 必须调用 B」而调用结果又可能相同时，**给 B 一个假实现返回荒谬值**，比断言 A 的输出更有力。

### 3. 测试用固定日期，只有一条用 `LocalDate.now()`

`BodyMapServiceTest` / `BodyMapControllerTest` 用 `2026-10-20`（周二，那周是 10-19..10-25）当参照日，这样 `daysSince` 可以被钉死；只有「不给 `date` 默认今天」那一条用真实今天。

---

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| **区域 id 拼错** | 那块肌肉永远不着色，**不报错**，而你不会注意到 | Task 2 的交叉校验测试（**本计划最重要的一条测试**） |
| **同一区域映射到两块肌群** | 着色互相覆盖；`abductors` 与 `glutes` 最容易撞 | 交叉校验测试第 3 条 |
| **素材是别人仓库的 TS** | 可能 import 它自己的类型，直接拷进来编译不过 | Task 1 Step 4 要求先确认能 import，并说明处理方式 |
| **`@main` 会漂** | 下次有人重新下载拿到的是不同版本 | NOTICE 记 commit sha，vendor 的文件不改 |
| 24 个未映射区域 | 用户可能问「这块怎么不亮」 | 渲染成基线灰且不可点；在总览里写清为什么 |
| **三种模式的口径** | 「恢复中」的阈值（0–2/3–5/>5 天）是我定的，没有依据 | 写进计划的注释，标注「可调」；不假装它有科学依据 |

## 非目标（本计划不做）

- **不做 3D**（那是设计文档的第二阶段：Three.js + Z-Anatomy）
- **不做「+ 加入今天训练」**——设计文档的流程 ④ 里有这一步，但它要跨视图把动作塞进训练记录，属于**写路径**。本计划只做「看」，加入训练留到下一轮。**这是本计划与设计流程 ④ 的一处刻意偏离，会在总览里记下。**
- **不做恢复中模式的「该次训练量」加权**——设计文档写「距上次训练的天数 + 该次训练量」，本计划只用天数。理由：加权系数没有依据，先做天数这一半，把「该次训练量」留到有真实使用反馈后再定。
- **不改 `/api/volume/weekly`**（首页在用，语义不同）
- **不 vendor 那 1,746 张起止姿势图**
