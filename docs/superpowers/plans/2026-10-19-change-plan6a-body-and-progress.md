# 身体数据与进步曲线 · 实施计划（计划 6a）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让你能记体重/体脂/围度并看到时间线，以及按动作看力量走势（估算 1RM / 最大重量 / 总容量三条曲线）。

**Architecture:** 两个纯读的聚合端点 + 一个录入端点。进步曲线的三条序列**全部由已有数据实时算出**（不建物化表），并走 `CacheService` 缓存、写训练后按前缀失效——这是设计文档点名的那个缓存场景。前端两页都经外壳的 `api_request` 代理，不发 HTTP。

**Tech Stack:** Java 25 · Spring Boot 3.5.16 · Spring Data JPA · JdbcTemplate · SQLite · Vue 3 + TS

---

## 为什么把计划 6 拆成 6a / 6b

设计文档的「计划 6」包含三件事：**身体数据**、**进步曲线**、**人体图 2D**。它们的数据依赖完全不同：

| 部分 | 依赖 | 有没有外部素材 |
|---|---|---|
| 身体数据 | `body_metric`（计划 1 建好的表） | 无 |
| 进步曲线 | `workout_set` + `OneRepMax`（都已在） | 无 |
| 人体图 2D | `GET /api/volume/weekly`（计划 3 已做） | **要一份 SVG + 27 个 region 的映射表** |

人体图那部分有一件**纯手工活**（SVG 路径 → `MuscleRegion` id 的映射），而且设计文档决定 5 把它定性为「全流程唯一无法回避的纯手工活」。把它和两个纯数据的部分捆在一份计划里，会让「三天就能用完的两个页面」等一件手工活。

所以拆开：**6a = 身体数据 + 进步曲线**（本计划），**6b = 人体图 2D**（下一步）。

---

## 前置事实（已实测，不需要重新验证）

| 项 | 值 |
|---|---|
| `body_metric` 表 | 已存在（`V4__auxiliary.sql`）。列：`id` / `measured_on`（`YYYY-MM-DD` 形状 CHECK）/ `metric_type` / `value` / `unit` / `note` |
| `metric_type` 的约束 | **只有一句注释**（`-- weight / body_fat / waist / arm / ...`），**没有 CHECK**。Java 侧也没有白名单——这是计划 1 交接报告里「7 个枚举列没有 Java 侧白名单」之一 |
| `OneRepMax` | `epley(double weight, int reps)`、`bestOf(List<Set>) → OptionalDouble`；`Set(double weight, Integer repetitions)`。**`bestOf` 跳过 `weight <= 0`，`reps == 1` 直接返回重量** |
| `workout_set` | `session_exercise_id` / `set_index` / `set_type` / `weight`（可空）/ `repetitions`（可空）/ `per_side` / `completed` / … |
| 周容量的端点在 | **`GET /api/volume/weekly`**（计划 3 做的，`VolumeController`）。**不是**设计文档里写的 `/api/body-map/volume`——见「已知偏离」 |
| `CacheService` | `get(key, type) → Optional<T>` / `put(key, value, Duration)` / `invalidatePrefix(prefix)` / `isAvailable()` / `describe()`。**任何方法都不向上抛异常**（Redis 不可用是常态） |
| 现有控制器 | `ApiErrors` / `ExerciseController` / `HealthController` / `NutritionController` / `TrainingController` / `VolumeController` |
| 错误正文 | 统一 `{error}`（400 校验失败 / 404 找不到），由 `ApiErrors` 产生；**传输失败**才是 invoke 的 reject |
| 前端约定 | 一律 `invoke('api_request', {method, path, body})`，**不碰 `fetch`**；颜色只用 `theme.css` 的 token |
| 基线 | `mvn -B clean test` → **274 条**；`pnpm test` → **176 条**；`cargo test` → 93 + 4 |

### 三个「不是错误」的约定（沿用计划 3/4a）

- 没有身体数据 → `[]`
- 某动作没有任何一次完成组 → `{points: []}`，**不是 404**
- 只有 `completed = 1` 的组算数（没打勾的组是「还没做/没记」）

---

## 全局约定

| 项 | 约定 |
|---|---|
| 包名 | `com.change` |
| 迁移 | 只增不改。本计划**预计不需要新迁移**（`body_metric` 够用） |
| 测试 | `mvn -B clean test`（**必须 clean**，cwd 必须是 `backend/`） |
| 幂等 | 重复 POST 同一条身体数据**不去重**（同一天称两次体重是正常的，两个值都要留） |
| 提交信息 | 中文，说清「做了什么 + 为什么」 |
| 计划先于实现 | 本计划的「实施修正」一节在实现中随时补 |
| 不用 `java -cp target/classes` 探行为 | 会读到陈旧 class |

---

## 文件结构

```
backend/src/main/java/com/change/
├── domain/BodyMetric.java                    ← 新（此前无实体）
├── repo/BodyMetricRepo.java                  ← 新
├── service/
│   ├── BodyMetricService.java                ← 新（录入 + 时间线；DTO 是**嵌套 record**）
│   └── ProgressService.java                  ← 新（三条序列 + 缓存；DTO 也是嵌套 record）
└── web/
    ├── BodyMetricController.java             ← 新
    └── ProgressController.java               ← 新

frontend/src/
├── api/body.ts                               ← 新（身体数据的客户端）
├── api/progress.ts                           ← 新（进步曲线的客户端）
├── views/BodyDataView.vue                    ← 改（现在是占位，394 字符）
└── views/ProgressView.vue                    ← 改（现在是占位，380 字符）
```

**⚠️ 这个项目没有 `api` 包。** DTO 一律是**服务类里的嵌套 record**，`web` 包只有 `ApiErrors` 与各个 Controller。照现有惯例写，例如 `WorkoutService` 里就是这样：

```java
public record SetInput(String setType, Double weight, Integer repetitions,
                       Integer durationSeconds, ...) {}
public record ExerciseInput(Long exerciseId, String note, String supersetGroup,
                            List<SetInput> sets) {}
public record SessionInput(LocalDate sessionDate, String title, String perceivedState,
                           String note, List<ExerciseInput> exercises) {}
public record Result(long sessionId, int exercises, int sets) {}
```

所以本计划的两个服务应当各自持有自己的 `Input` / `View` record，**不要新建包**。

**为什么身体数据与进步曲线分成两个 service/controller**：前者的数据源是 `body_metric`（**可写**），后者是 `workout_set`（**只读聚合**）。它们的失效策略、测试夹具、出错方式都不同，混在一个类里会让「写身体数据要不要失效 1RM 缓存」这种问题变得含糊。

---

## Task 1: `BodyMetric` 实体、Repo 与录入端点

**Files:**
- Create: `backend/src/main/java/com/change/domain/BodyMetric.java`
- Create: `backend/src/main/java/com/change/domain/MetricType.java`（枚举）
- Create: `backend/src/main/java/com/change/repo/BodyMetricRepo.java`
- Create: `backend/src/main/java/com/change/service/BodyMetricService.java`（含嵌套 record）
- Create: `backend/src/main/java/com/change/web/BodyMetricController.java`
- Test: `backend/src/test/java/com/change/service/BodyMetricServiceTest.java`

- [ ] **Step 1: 先读现有的同类实现，照抄风格**

**不要凭空写。** 先读这三个文件，把 DTO 放哪、怎么校验、错误怎么抛、`@RestController` 的路径风格全部对齐：

```
backend/src/main/java/com/change/web/NutritionController.java
backend/src/main/java/com/change/service/NutritionService.java
backend/src/main/java/com/change/web/ApiErrors.java
```

`NutritionController` 的 `POST /api/meals` 是最接近的参照（一个写端点 + 事务 + 逐行审计）。

- [ ] **Step 2: 定 `metric_type` 的词汇（这是本任务唯一需要判断的地方）**

`body_metric.metric_type` **没有 CHECK 约束**，只有一句注释。现在有两条路：

| 选项 | 代价 |
|---|---|
| **A（推荐）** Java 侧 `MetricType` 枚举 + 校验，**不加 DB CHECK** | 与现状一致（那 7 个枚举列都没有 CHECK 是有意的历史决定）；但数据库层不拦脏值 |
| B 加一条迁移补 CHECK | 要重建表；且会让「只增不改」的迁移再多一条 |

**选 A**，理由：这个列是**开放词汇**（设计文档的注释是 `weight / body_fat / waist / arm / ...`，`...` 意味着以后还会加「小腿围」「肩宽」）。用 CHECK 钉死一个开放词汇，等于每次加指标都要写迁移——那正是设计文档「决定 4 / 数据模型」里说的「不给每个围度开一列，是为了以后加『小腿围』『肩宽』不用改表」要避免的。

枚举（**首批**，以后加就扩这个枚举）：

```java
package com.change.domain;

import java.util.List;

/**
 * 身体数据的指标类型。
 *
 * <p><b>为什么不加数据库 CHECK</b>：这是一个**开放词汇**——设计文档的列注释写的是
 * {@code weight / body_fat / waist / arm / ...}，那个 {@code ...} 是有意的：
 * 以后要加「小腿围」「肩宽」时不该写迁移。所以约束放在 Java 侧，DB 只保证非空。
 *
 * <p>代价：绕过本枚举直接写库会产生认不出的类型。**这是有意的取舍**，与
 * 计划 1 交接报告里「7 个枚举列没有 Java 侧白名单」是同一类问题的正解——
 * 补 Java 白名单，而不是补 DB CHECK。
 */
public enum MetricType {
    /** 体重，kg。 */
    WEIGHT("weight", "kg"),
    /** 体脂率，%。 */
    BODY_FAT("body_fat", "%"),
    // ——— 围度，一律 cm ———
    WAIST("waist", "cm"),
    CHEST("chest", "cm"),
    HIP("hip", "cm"),
    ARM("arm", "cm"),
    THIGH("thigh", "cm"),
    CALF("calf", "cm"),
    SHOULDER("shoulder", "cm");

    /** 存进 `body_metric.metric_type` 的字面量。**改它等于改数据**。 */
    public final String id;
    /** 该类型的默认单位。客户端不传 unit 时用它。 */
    public final String defaultUnit;

    MetricType(String id, String defaultUnit) {
        this.id = id;
        this.defaultUnit = defaultUnit;
    }

    public static MetricType byId(String id) {
        for (MetricType t : values()) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    /** 报错文案用：把已知类型拼进去，用户一眼知道能写什么。 */
    public static List<String> validIds() {
        return java.util.Arrays.stream(values()).map(t -> t.id).toList();
    }
}
```

- [ ] **Step 3: 写失败的测试**

`BodyMetricServiceTest` 至少覆盖：

1. 录一条体重，返回 id，库里有那一行
2. **不给 `unit` 时按类型的默认单位填**（weight → `kg`，waist → `cm`）
3. **给了 `metricType` 但不在枚举里 → 抛能读懂的错**（消息里要含合法值列表）
4. `measuredOn` 不是 `YYYY-MM-DD` → 抛错（DB 有 CHECK 兜底，但 Java 侧先拦能给出能读懂的话）
5. `value` 为负 → 抛错
6. **同一天同一类型录两次**：两个都留下，返回两个不同 id（**不去重**——一天称两次体重是正常的）
7. 时间线查询：`?type=weight` 只返回 weight 类型，按 `measured_on` 升序
8. **没有数据时返回空列表，不是 404**

- [ ] **Step 4: 跑测试，确认失败**

Run: `cd backend && mvn -B -q -Dtest=BodyMetricServiceTest test`

Expected: 编译失败（`cannot find symbol: class BodyMetricService`）。**贴原始输出。**

- [ ] **Step 5: 写实现**

要点：

1. **`POST /api/body-metrics`** 请求体：

```json
{ "measuredOn": "2026-10-19", "metricType": "weight", "value": 72.5, "unit": null, "note": "早上空腹" }
```

响应 `{ "id": 12 }`。

2. **`GET /api/body-metrics?type=weight&from=&to=&limit=`**：
   - `type` 省略 → 返回**全部类型**（按日期升序）
   - `from` / `to` 是 `YYYY-MM-DD`，闭区间，都可省略
   - `limit` 默认 200、封顶 1000（与 `ExerciseController` 的 limit 风格一致）
   - 响应：`[{ id, measuredOn, metricType, value, unit, note }]`

3. **`GET /api/body-metrics/types`** → 库里**实际出现过的**类型 + 每个的最新值：

```json
[ { "metricType": "weight", "unit": "kg", "latestValue": 72.5, "latestOn": "2026-10-19", "count": 14 } ]
```

用途：身体数据页顶部的「当前体重 / 当前体脂 / 各围度」卡片。**按枚举顺序排**，不是按字母。

5. 写路径要 **`@Transactional`**（虽然只有一条 insert，但将来可能连写多条围度）。

6. **写一条审计日志**——`body_metric` **已经在** `ChangeLogService.ALLOWED_TABLES` 里（它是 11 张表之一，有单列整型 `id`），所以：

```java
changeLog.recordInsert("body_metric", id,
        Map.of("measured_on", measuredOn.toString(),
               "metric_type", type.id,
               "value", value,
               "unit", unit),
        "user", null);
```

理由：审计网的意义是「智能体改了数据你能看见、能回退」。设计文档决定 4 说智能体**也能改数据**——将来它给你补一条体重记录时，你得能查到。现在手填的也一起记，成本是一条行。

> **`after` 的键必须与 `body_metric` 的真实列名一致**（`measured_on` / `metric_type` / `value` / `unit` / `note`）——`write` 会拿 `writableColumns` 逐个校验，写错键名会抛 `IllegalArgumentException`。

- [ ] **Step 6: 跑测试**

Run: `cd backend && mvn -B clean test`

Expected: 全绿，新增条数 = 你写的条数。

- [ ] **Step 7: 提交**

（提交信息说明：为什么 `metric_type` 用 Java 枚举而不是 DB CHECK；为什么同日重复不去重。）

---

## Task 2: 身体数据的时间线（后端部分已在 Task 1 里）

**这一步只做前端要用的那层契约确认**——把 Task 1 的三个端点用真实 HTTP 打一遍，确认形状与错误码。

- [ ] **Step 1: 起后端，用真实请求核对**

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:CHANGE_DATA_DIR = "$env:TEMP\change-6a-http"
Remove-Item -Recurse -Force $env:CHANGE_DATA_DIR -ErrorAction SilentlyContinue
mvn -B -q -DskipTests package
java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

从 stdout 拿到 `CHANGE_PORT=<n>`，然后逐个核对（**贴原始输出**）：

| # | 请求 | 期望 |
|---|---|---|
| 1 | `POST /api/body-metrics` 合法体重 | 201 或 200 + `{id}` |
| 2 | `GET /api/body-metrics?type=weight` | 数组，含刚写的那条 |
| 3 | `GET /api/body-metrics?type=weight` **在没有数据的新库上** | `[]`（**不是 404**） |
| 4 | `POST` 一个非法 `metricType` | **400** + `{error}`，消息里含合法值 |
| 5 | `GET /api/body-metrics?type=不存在的类型` | `[]`（不是 400——查询不存在的类型是合法的空结果） |
| 6 | `GET /api/body-metrics/types` | 数组，按枚举顺序 |

> **⚠️ 注意陷阱 7（计划 4a 的教训）**：新库启动时会跑动作库导入与食物 seed，所以 `change_log` 里会有 3,000+ 条 `origin='import'`、`food` 里有 35 条。**断言「整张表为空」是错的**；凡是清理与断言都要收口到 `body_metric` 这一张表。

- [ ] **Step 2: 关掉后端**

`Stop-Process` 或 Ctrl+C。**确认没有残留的 java 进程。**

---

## Task 3: 进步曲线的三条序列

**Files:**
- Create: `backend/src/main/java/com/change/service/ProgressService.java`（含嵌套 record）
- Create: `backend/src/main/java/com/change/web/ProgressController.java`
- Test: `backend/src/test/java/com/change/service/ProgressServiceTest.java`

- [ ] **Step 1: 定三条序列的口径（写进注释，因为这是会被反复问的事）**

设计文档：「按动作看力量走势（**估算 1RM / 最大重量 / 总容量**）」。

| 序列 | 口径 | 为什么 |
|---|---|---|
| `e1rm` | 每个**训练日**，该动作全部完成组里 `OneRepMax.bestOf(...)` 的最大值 | 已经是项目里统一的估算方法（13 条测试）；不在这里重算 |
| `maxWeight` | 每个训练日，完成组里 `weight` 的最大值 | 它是**原始事实**，而 e1rm 是**估算**。两个都留，因为读数时信任度不同——1RM 是算出来的，最大重量是记下来的 |
| `totalVolume` | 每个训练日，`Σ(weight × repetitions)` 只算**两者都非空且 `completed=1`** 的组 | 「总容量」在这里指**吨位**，不是组数。注意它与 `VolumeCalculator` 的「组数」是两件事，不要混用命名 |

**每条序列都只含「有数据的那天」**——中间没练的日子不补 0。补 0 会让曲线在休息日往下掉，看起来像退步。

- [ ] **Step 2: 写失败的测试**

`ProgressServiceTest` 至少覆盖：

1. 一个动作两天的训练 → `points` 两天、按日期升序、三个字段都有值
2. **只有 `completed=1` 的组算数**（加一条 `completed=0` 且重量很大的组，断言 e1rm 与 maxWeight 不受影响）
3. **`repetitions` 或 `weight` 为 null 的组被跳过**（不参与 totalVolume，也不参与 e1rm）
4. **`reps == 1` 时 e1rm 等于重量本身**（`OneRepMax` 的既有性质，这里钉住它没被包错）
5. **没练过的动作 → `points: []`，不是 404**（动作存在但没数据）
6. **动作不存在 → 404**（与「没数据」区分开）
7. `per_side = 1` 的组：**重量按记录值算，不乘 2**（左右各记一组是用户的输入方式；乘 2 会静默把吨位翻倍。**如果你认为该乘，报告出来，不要自己改**）
8. 日期过滤：给了 `from` / `to` 时只返回区间内的点

- [ ] **Step 3: 跑测试，确认失败**

Run: `cd backend && mvn -B -q -Dtest=ProgressServiceTest test`

Expected: 编译失败。**贴原始输出。**

- [ ] **Step 4: 写实现**

`GET /api/progress/{exerciseId}?from=&to=`：

```json
{
  "exerciseId": 42,
  "nameEn": "Barbell Bench Press",
  "nameZh": "杠铃卧推",
  "points": [
    { "date": "2026-10-14", "e1rm": 76.0, "maxWeight": 60.0, "totalVolume": 1080.0 },
    { "date": "2026-10-17", "e1rm": 82.5, "maxWeight": 65.0, "totalVolume": 1200.0 }
  ]
}
```

实现要点：

1. **一次 SQL 取全量**，不要在循环里查（N+1）：

```sql
SELECT ws.session_date, se.exercise_id, s.weight, s.repetitions, s.per_side
FROM workout_set s
JOIN session_exercise se ON se.id = s.session_exercise_id
JOIN workout_session ws ON ws.id = se.session_id
WHERE se.exercise_id = ?
  AND s.completed = 1
  AND s.weight IS NOT NULL
  AND s.repetitions IS NOT NULL
  AND (? IS NULL OR ws.session_date >= ?)
  AND (? IS NULL OR ws.session_date <= ?)
ORDER BY ws.session_date
```

> **列名已核对**（`V2__training.sql`）：`workout_session` 有 `id` / `session_date`；`session_exercise` 有 `id` / `session_id` / `exercise_id` / `order_index` / `note` / `superset_group`；`workout_set` 有 `id` / `session_exercise_id` / `set_index` / `set_type` / `weight` / `repetitions` / `duration_seconds` / `per_side` / `rir` / `rest_seconds` / `completed` / `target_weight` / `target_reps`。

2. 在 Java 里按 `session_date` 分组，每组算三个值。
3. `e1rm` 用 `OneRepMax.bestOf(List<Set>)`，**不要**自己写 Epley。
4. 动作不存在 → 抛 `ResponseStatusException(NOT_FOUND, "找不到这个动作: " + id)`，让 `ApiErrors` 翻成 `{error}`。
5. **不要**缓存（缓存是 Task 4）。

- [ ] **Step 5: 跑测试**

Run: `cd backend && mvn -B clean test`

- [ ] **Step 6: 提交**

---

## Task 4: 1RM 序列走缓存，写训练后失效

**这是设计文档点名的缓存场景**：「进步曲线的 1RM 序列 | 按动作聚合历史，随数据量增长变慢」，以及「**写入即失效**：任何写 `WorkoutSet` / `MealItem` / `BodyMetric` 的操作，按 key 前缀批量失效」。

**Files:**
- Modify: `backend/src/main/java/com/change/service/ProgressService.java`
- Modify: `backend/src/main/java/com/change/service/WorkoutService.java`（`recordSession` 末尾加失效）
- Test: `backend/src/test/java/com/change/service/ProgressCacheTest.java`

- [ ] **Step 1: 写失败的测试**

至少覆盖：

1. **命中**：连续两次请求同一个动作，第二次 Redis 里能查到（用 `CacheService` 的探针或 mock 计数）
2. **写训练后失效**：第一次请求 → 录一次新训练 → 第二次请求拿到的是**新数据**（不是缓存的旧值）
3. **Redis 不可用时照常工作**：把 `CacheService` 换成 `NoOpCacheService`（或关掉 Redis），端点仍返回正确结果、不抛异常
4. **不同动作是不同的 key**：查 A 再查 B，两者数据不串
5. **带 `from`/`to` 的请求不能与不带过滤的串缓存**（key 里必须包含过滤条件，否则会返回错误的子集）

> 第 5 条是最容易漏的。**key 必须能唯一确定响应**。

- [ ] **Step 2: 跑测试，确认失败**

- [ ] **Step 3: 实现**

1. **key 形状**：`progress:v1:{exerciseId}:{from|_}:{to|_}`。**`v1` 是版本位**——将来改了序列口径，把它改成 `v2` 就能让所有旧缓存自然失效，不需要写「清缓存」的逻辑。
2. **TTL**：`Duration.ofMinutes(10)`。理由：这是本机单用户，数据只在你录入时变，而录入后会被显式失效；TTL 只是兜底（比如某个写路径忘了失效）。
3. **失效**：写训练成功之后 `cache.invalidatePrefix("progress:")`。
   - **落点已核对**：`WorkoutService.recordSession(SessionInput)` 是 `@Transactional` 的那个方法，写 `workout_session` / `session_exercise` / `workout_set` 都在它里面。失效就加在它返回之前（方法内最后一步），或者在调用它的 Controller 里、事务提交之后——**两种都行，但要在注释里写清你选了哪种、以及为什么顺序是对的**。
   - 顺序的理由：事务回滚了却失效了缓存，只是白清一次（无害）；反过来（先失效、后回滚）会让缓存里留着该失效的值（有害）。所以「事务提交 → 失效」是正确顺序；同方法内最后一步也满足这一点（提交发生在方法返回时，而那时缓存已清）。
4. **`CacheService` 的任何方法都不向上抛异常**——所以这里不需要 try/catch（读接口注释确认）。

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn -B clean test`

- [ ] **Step 5: 提交**

（提交信息说明：为什么 key 带版本位、为什么 TTL 是兜底、为什么失效放在事务之后。）

---

## Task 5: 前端「身体数据」页

**Files:**
- Create: `frontend/src/api/body.ts`
- Modify: `frontend/src/views/BodyDataView.vue`（现在是 394 字符的占位）
- Test: `frontend/src/api/body.spec.ts`、`frontend/src/views/BodyDataView.spec.ts`

- [ ] **Step 1: 先读现有的前端约定**

**不要凭空写。** 读这三个，把客户端怎么调 `api_request`、错误怎么分流、页面怎么用 token、测试怎么 mock `invoke` 全部对齐：

```
frontend/src/api/training.ts          （或饮食那个，看哪个更近）
frontend/src/views/TrainingView.vue
frontend/src/views/DietView.vue
```

- [ ] **Step 2: 写失败的测试**

`body.ts` 的客户端测试：

1. `listMetrics(type)` 走 `invoke('api_request', {method:'GET', path:'/api/body-metrics?type=weight'})` —— **断言 path 精确**
2. `addMetric(input)` 走 `POST`，body 是那个对象
3. `types()` 走 `/api/body-metrics/types`
4. 400 时抛出一个**带后端 `{error}` 文案**的错误（不是「请求失败」）
5. **空结果是 `[]` 而不是抛错**

`BodyDataView` 的组件测试：

1. 初始加载显示「还没有记录」一类的空态（**不是报错**）
2. 有数据时按日期倒序显示（最近的在最上面——注意后端返回升序，页面要倒过来）
3. 录入表单提交后调用 `addMetric`，成功后列表刷新
4. 提交非法值（比如负数）时显示**后端给的**错误文案
5. 类型下拉框的选项来自 `types()`，按枚举顺序

- [ ] **Step 3: 跑测试，确认失败**

Run: `cd frontend && pnpm test src/api/body.spec.ts src/views/BodyDataView.spec.ts`

- [ ] **Step 4: 写实现**

界面要求（对齐设计文档「九、界面与视觉方向」）：

- **只做深色**，颜色**一律** `var(--xxx)`，不许写死（`theme.css` 是唯一来源）
- 圆角 4–6px、**不用阴影**，靠描边与间距分层
- 小标签用 `.eyebrow`（全大写 + 宽字距 + `--eyebrow`）
- 顶部一行「当前值」卡片：体重 / 体脂 / 各围度（来自 `types()`）
- 下面一条时间线表格（日期 / 数值 / 备注），最近的在最上
- 录入区：类型下拉 + 日期（默认今天）+ 数值 + 备注
- **留白放宽**，用空白而不是卡片边框分组

- [ ] **Step 5: 跑测试 + 构建**

Run:
```powershell
cd frontend
pnpm test
pnpm build
```

Expected: 测试全绿；`pnpm build` **退 0**（`vue-tsc` 会抓 `pnpm test` 抓不到的类型错，比如陷阱 10 的 `as unknown as`）。

- [ ] **Step 6: 提交**

---

## Task 6: 前端「进步曲线」页

**Files:**
- Create: `frontend/src/api/progress.ts`
- Modify: `frontend/src/views/ProgressView.vue`（现在是 380 字符的占位）
- Test: `frontend/src/api/progress.spec.ts`、`frontend/src/views/ProgressView.spec.ts`

- [ ] **Step 1: 写失败的测试**

客户端：

1. `progress(exerciseId)` 的 path 精确
2. 404 时抛出**带后端文案**的错误（区分于「没数据」）
3. `points: []` 原样返回，不抛错

组件：

1. 没选动作时显示引导文案（不是空白页）
2. 选动作后**三条序列都渲染**（e1rm / maxWeight / totalVolume），且各自有名字
3. **`points: []` 时显示「还没练过这个动作」**（不是「加载失败」）
4. 动作选择器复用「训练记录页」那套（`/api/exercises`），**但可以只显示名字**——不必带肌群
5. 图表的横轴是日期、按升序

- [ ] **Step 2: 跑测试，确认失败**

- [ ] **Step 3: 写实现**

图表选型：**用内联 SVG 手绘折线**，不引图表库。

理由：三条序列、点数不多（一个动作一周最多几次训练）、只有单条折线，一个 `polyline` 就够。引一个图表库会带来 100KB+ 的包体积与一套新的坐标/主题配置，而设计文档的视觉方向是「靠描边和间距分层」——手绘更容易和 token 对齐。

**如果你认为该引图表库，先报告理由**（比如你觉得以后要交互式 tooltip），不要自己加依赖。

要点：

- 三条序列各自一张小图（不是三线叠一图——量纲不同，叠在一起 e1rm 会把 totalVolume 压成一条平线）
- 每张图的 y 轴给最小/最大刻度，x 轴给首尾日期
- 用 `--accent` 画线，`--line` 画网格
- 没有任何点时画一条基线 + 文案，**不要画空图**

- [ ] **Step 4: 跑测试 + 构建**

Run:
```powershell
cd frontend
pnpm test
pnpm build
```

- [ ] **Step 5: 提交**

---

## Task 7: 端到端验收

- [ ] **Step 1: 干净库上跑一遍全链路**

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:CHANGE_DATA_DIR = "$env:TEMP\change-6a-e2e"
Remove-Item -Recurse -Force $env:CHANGE_DATA_DIR -ErrorAction SilentlyContinue
mvn -B -q -DskipTests package
java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

**逐条核对并记录实际值**：

| # | 做什么 | 期望 |
|---|---|---|
| 1 | 启动日志 | `动作库导入：新增 876` → `动作中文名：写入 876` → `食物库：写入 35 条` → `CHANGE_PORT=` |
| 2 | `GET /api/body-metrics?type=weight` | `[]` |
| 3 | `POST /api/body-metrics` 体重 72.5 | 返回 id |
| 4 | `POST` 腰围 80（**不给 unit**） | 库里 `unit='cm'` |
| 5 | `POST` 非法类型 | 400 + `{error}` 含合法值 |
| 6 | 录两次训练（同一动作、不同日期、不同重量） | 各自成功 |
| 7 | `GET /api/progress/{id}` | `points` 两天、e1rm 递增、maxWeight 递增 |
| 8 | 再录一次**更重**的训练，立刻查 progress | **拿到新的 e1rm**（证明失效生效，不是缓存旧值） |
| 9 | `GET /api/progress/{不存在的id}` | **404** + `{error}` |
| 10 | 一个没练过的动作 | `points: []`，**不是 404** |
| 11 | 关掉后端 | 无残留 java 进程 |

- [ ] **Step 2: 把结果记进总览**

在 `docs/superpowers/plans/2026-10-14-change-phase1-overview.md` 的「计划 4a」之后插入一节「## 计划 6a：身体数据与进步曲线」，写清实际数字与两条口径决定（`metric_type` 用 Java 枚举、`per_side` 不乘 2）。

- [ ] **Step 3: 提交**

---

## 完成标准

- [ ] `cd backend && mvn -B clean test` 全绿，**报告实际条数**（基线 274）
- [ ] `cd frontend && pnpm test` 全绿（基线 176）；`pnpm build` 退 0
- [ ] `POST /api/body-metrics` 不给 `unit` 时按类型填默认单位
- [ ] 非法 `metricType` → 400 且消息**含合法值列表**
- [ ] 同一天同一类型录两次 → **两条都留下**
- [ ] `GET /api/body-metrics?type=不存在` → `[]`（不是 400）
- [ ] `GET /api/progress/{id}` 返回三条序列，只含 `completed=1` 且 weight/reps 都非空的组
- [ ] **没练过的动作 → `points: []`；不存在的动作 → 404**（两者区分开）
- [ ] 写训练后立刻查 progress 能拿到新值（**失效实测**）
- [ ] Redis 关掉时两个端点照常工作
- [ ] 前端两页的颜色**全部**来自 `theme.css` 的 token（`grep -c 'var(--'` 大于 0，且没有 `#` 十六进制字面量）
- [ ] `git status --short` 无输出

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| **`metric_type` 没有 DB CHECK** | 绕过 Java 直接写库会产生认不出的类型 | 有意的取舍（开放词汇）；Java 侧枚举 + 测试挡住 |
| **「总容量」被理解成组数** | `VolumeCalculator` 的「组数」与这里的「吨位」是两件事 | 注释写明；测试钉住 `Σ(weight×reps)` |
| **`per_side` 要不要乘 2** | 乘错会静默把吨位翻倍或减半 | 定为**不乘**（用户左右各记一组）；测试钉住；有异议先报告 |
| **缓存 key 漏了过滤条件** | 带 `from/to` 与不带会串缓存，返回错误的子集 | key 含过滤条件；专门一条测试 |
| **失效放在事务前** | 会留下「该失效但没失效」的缓存 | 顺序：事务提交 → 失效；测试用「写后立刻查」验证 |
| 图表引库 | 包体积 + 一套新配置 | 内联 SVG；要引库先报告 |
| 前后端字段名漂移 | 两处手写定义，没有东西会报警 | 后端加一条序列化形状的测试；前端测试里喂**真实 JSON**（不是手写对象） |

## 非目标（本计划不做）

- **不做人体图**——那是 6b（要 SVG + 27 个 region 的映射）
- **不做数据的修改 / 删除**——第一阶段只做「一次写完」（与训练、餐一致）
- **不做导出**
- **不自动补零**——没练的日子不画点
- **不做「进步」的判定（比如「比上月强了 5%」）**——只画曲线，判断留给人
- **不动 `body_metric` 的 schema**（不加 CHECK、不加列）

## 已知偏离

| 设计文档写的 | 实际 | 处置 |
|---|---|---|
| `GET /api/body-map/volume` | 计划 3 做的是 **`GET /api/volume/weekly`** | 6b 直接用后者，不新增重复端点。**这是「计划写于落地之前」的典型**（H 型失效），记在这里以免下一个人再去找那个不存在的端点 |

## 下一步

本计划完成后做 **6b：人体图 2D**——它的数据源（`GET /api/volume/weekly`）已在，缺的是 SVG 素材与 `MuscleRegion` 的映射表；设计文档决定 5 的界面位与 `<BodyMap />` 契约都已冻结。
