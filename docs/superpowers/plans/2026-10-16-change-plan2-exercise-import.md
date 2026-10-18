# 动作库导入与肌群映射 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 free-exercise-db 的 876 个动作连同它们的肌群标注导入本机库，让「按肌群找动作」与「记一组」有东西可选，并且**导入能被干净地撤销**。

**Architecture:** 数据集**随包 vendor**（1.0 MB JSON，Unlicense），导入是一个**可重跑的幂等操作**而非一次性脚本。撤销不走逐行日志回放，而是一个**按 `source_id` 前缀删除**的专用操作。为了让 join 表也能进审计网，给 `exercise_muscle` 加一个代理主键——这是「单列整型 `id`」这个准入条件的正解，一次修掉这一类。

**Tech Stack:** Java 25 · Spring Boot 3.5.16 · Spring Data JPA · JdbcTemplate · Flyway · SQLite

---

## 为什么现在做这一份

计划 3（训练记录）要「选一个动作、记一组」。没有动作库，那个界面是空的。

而计划 1 只建了表，没往里放数据。设计文档「决定 6」早就定了数据源，这份计划把它落地。

---

## 前置事实（2026-10-16 实测，不需要重新验证）

### 数据集

| 项 | 实测值 |
|---|---|
| 来源 | `yuhonas/free-exercise-db`，分支 `main` |
| 文件 | `dist/exercises.json` |
| 大小 | **1,005,327 字节** |
| sha256 | **`5bb747e3fc658f09…`**（前 16 位；完整值在导入校验里钉住） |
| 条数 | **876** |
| 许可证 | **Unlicense（公有领域）** —— 可随包分发 |
| 字段 | `name` `force` `level` `mechanic` `equipment` `primaryMuscles` `secondaryMuscles` `instructions` `category` `images` `id` |

### ⚠️ 网络：GitHub 直连被掐，必须走镜像

`raw.githubusercontent.com` 与 `cdn.jsdelivr.net` 在本机**被重置**（`WinError 10054`）。实测可用：

| 源 | 实测耗时 |
|---|---|
| **`https://fastly.jsdelivr.net/gh/yuhonas/free-exercise-db@main/dist/exercises.json`** | **319 ms** ← 首选 |
| `https://gcore.jsdelivr.net/gh/...` | 1191 ms |
| `https://ghproxy.net/https://raw.githubusercontent.com/...` | 1710 ms |

`codeload.github.com` 能通但打的是**整个仓库 tarball**（含 1746 张图，200 MB 量级）——**不要走那条路**，为 1 MB 的 JSON 不值得。

### 数据集里的四个陷阱（都实测过）

| # | 陷阱 | 实测数据 | 不处理的后果 |
|---|---|---|---|
| 1 | **9 条动作的 `primaryMuscles` 与 `secondaryMuscles` 有重叠** | `All Fours Quad Stretch` / `Barbell Step Ups` / `Bent-Arm Barbell Pullover` / `Clean and Press` / `Hurdle Hops` / `Kneeling Hip Flexor` / `Snatch Deadlift` / `Split Snatch` / `Upper Back Stretch` | 复合主键 `(exercise_id, muscle_group_id)` **撞 UNIQUE constraint**，整批回滚 |
| 2 | **292/876 条不是 `strength`** | stretching 123、plyometrics 61、powerlifting 38、olympic weightlifting 35、strongman 21、cardio 14 | 动作选择器不过滤的话，123 条「拉伸」会混进「记一组」 |
| 3 | `mechanic` 空 87 条、`equipment` 空 77 条 | `mechanic=null` 的如 `90/90 Hamstring`（category=stretching） | `is_compound` 是 NOT NULL，必须有默认规则 |
| 4 | **`id` 与 `name` 有 844 条不同** | `Ab_Crunch_Machine` vs `Ab Crunch Machine`；`id` 无斜杠、无空格、最长 58 字符 | `source_id` 必须用 `id`；用 `name` 拼会让 844 条对不上源 |

**另外三条好消息**：`name` **无重复**（`ux_exercise_name_en` 安全）、`id` **无重复**（`source_id` 命名空间安全）、`name` **全 ASCII**（中文名必须自己产出，且不会撞唯一索引）。

---

## 迁移编号的一处对调（落地时修正）

计划最初把「17 块肌群种子」编成 **V8**、「动作分类与说明」编成 **V7**。但 Task 2 先落地，
那样会在版本号里留一个洞：

- **全新库没问题**——Flyway 按版本号应用，不看文件创建顺序
- **已经应用到 V8 的库会出问题**——事后才等到 V7 时，Flyway 报 `resolved migration not applied`，
  默认 `outOfOrder=false` 会让启动直接失败，只能删库重建

所以落地时把两者对调：**Task 2 用 V7，Task 3 用 V8**，版本号顺序与创建顺序一致。
`MigrationTest` 里那段 javadoc 记了这件事。

---

## 全局约定

承接计划 1：

| 项 | 约定 |
|---|---|
| 包名 | `com.change` |
| 迁移 | `V<n>__<desc>.sql`，**只增不改**（已应用的迁移改字节会让 Flyway 校验失败） |
| 提交信息 | 中文，说清「做了什么 + 为什么」 |
| 换行 | LF；文件无 BOM |
| 元约定 | **计划 fence 与磁盘逐字一致**，用脚本比对 |
| 测试 | `mvn -B clean test`；**绝不用 `java -cp target/classes` 探行为**（会读到陈旧 class） |
| 幂等 | 导入必须**可重跑**：跑两次的结果与跑一次相同 |

---

## 文件结构

```
backend/src/main/
├── resources/
│   ├── db/migration/
│   │   ├── V5__exercise_muscle_surrogate_id.sql       ← 新（Task 1）
│   │   ├── V6__change_log_origin_import.sql           ← 新（Task 1）
│   │   ├── V7__muscle_group_seed.sql                  ← 新（Task 2，已落地）
│   │   └── V8__exercise_category_and_instructions.sql ← 新（Task 3）
│   └── seed/free-exercise-db/
│       ├── exercises.json                             ← vendor（1,005,327 字节）
│       ├── schema.json                                ← vendor（权威字段契约，2,765 字节）
│       └── LICENSE.md                                 ← Unlicense 原文，必须随包
│                                                         （是 .md，不是 LICENSE——
│                                                          仓库根目录没有 LICENSE）
└── java/com/change/
    ├── domain/
    │   ├── Exercise.java                              ← 改（加 category / instructions）
    │   ├── ExerciseMuscle.java                        ← 新（此前无实体）
    │   └── MuscleGroup.java                           ← 新（此前无实体）
    ├── repo/
    │   ├── ExerciseMuscleRepo.java                    ← 新
    │   └── MuscleGroupRepo.java                       ← 新
    ├── service/
    │   ├── ExerciseImportService.java                 ← 新（导入 + 撤销）
    │   └── ChangeLogService.java                      ← 改（准入 collect join 行）
    └── seed/
        ├── FreeExerciseDb.java                        ← 新（解析 + 映射，纯函数）
        ├── MuscleGroupSeed.java                       ← 新（17 行常量）
        └── MuscleRegion.java                          ← 新（`region_ids` 的枚举口径）
```

**为什么解析与映射单独成 `seed/FreeExerciseDb.java` 且不依赖 Spring**：876 条里那 9 条重叠、87 条 `mechanic=null`、77 条 `equipment=null`、844 条 `id≠name`——这些都是**纯数据变换**，能在毫秒级被单元测试全覆盖。把它和数据库隔开，才能在不起 Spring 的情况下测那 876 条的每一条。

---

## Task 1: 两个迁移——`exercise_muscle` 进审计网，`origin` 增加 `import`

**这是用户拍板的方案 A。**

### 危害的准确说法

计划 1 的交接报告里写成「撤销后再重做会丢掉肌群标注」。**核对源码后要修正**：`ChangeLogService` **没有 `redo`**，`undo` 只有两个分支（`insert` → `DELETE`，其余 → 从 `before` 写回）。

真正的危害是**审计日志不完整**：

> 今天创建一个动作并挂 3 块肌肉，日志里只有 **1 行**（`exercise`）。那 3 行 join 记录从不进日志。
>
> 后果有两个，都是可验证的：
> 1. **「这次改动动了什么」答不出来** —— 查日志只知道动了 `exercise`，不知道动了三行肌群标注。
> 2. **「凭日志重建状态」做不到** —— `undo` 掉那一行会级联删掉肌群，而**日志里没有任何东西能让它们回来**。

第二点正是「一次导入出错后，靠日志无法还原」的根源。

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__exercise_muscle_surrogate_id.sql`
- Create: `backend/src/main/resources/db/migration/V6__change_log_origin_import.sql`
- Modify: `backend/src/main/java/com/change/service/ChangeLogService.java`
- Test: `backend/src/test/java/com/change/service/ChangeLogJoinTableTest.java`

- [x] **Step 1: 迁移一 —— 给 `exercise_muscle` 加代理主键**

```sql
-- exercise_muscle 加代理主键。
--
-- 起因：ALLOWED_TABLES 的准入条件是「单列整型 id」（见 ChangeLogService 的注释），
-- 而这张表是复合主键 (exercise_id, muscle_group_id)，定位不到目标行，于是它被排除
-- 在审计之外。后果是：创建一个动作并挂 3 块肌肉，日志里只有 1 行——那 3 行 join
-- 记录从不进日志，所以「凭日志重建状态」做不到（undo 会级联删掉它们，日志里没有
-- 任何东西能让它们回来）。
--
-- 加一个代理 id 之后这张表就能被现有的通用回放机制原样覆盖，不需要在
-- ChangeLogService 里为它开特例。以后任何 join 表遇到同样问题都按这一条办。
CREATE TABLE exercise_muscle_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,
    muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE,
    role            TEXT    NOT NULL CHECK (role IN ('primary', 'secondary'))
);
-- 业务唯一性从「主键」降级成「唯一索引」：同一个动作对同一块肌肉只能标一次。
CREATE UNIQUE INDEX ux_exercise_muscle_pair
    ON exercise_muscle_new(exercise_id, muscle_group_id);
CREATE INDEX ix_exercise_muscle_muscle_group
    ON exercise_muscle_new(muscle_group_id);

INSERT INTO exercise_muscle_new (exercise_id, muscle_group_id, role)
SELECT exercise_id, muscle_group_id, role FROM exercise_muscle;

DROP TABLE exercise_muscle;
ALTER TABLE exercise_muscle_new RENAME TO exercise_muscle;
```

> **为什么不直接改 `V2__training.sql`**：Flyway 校验已应用迁移的 checksum，改字节会让现有的库在下次启动时校验失败。**只增不改**是硬规矩。

- [x] **Step 2: 迁移二 —— 让 `origin` 接受 `import`**

**这一条是必须的**：`origin` 是 `String`，而 `ChangeLogService.ALLOWED_ORIGINS` 与 `change_log.origin` 的 `CHECK` 约束**两处都是 `('user','agent')`**。导入的动作不是用户手点的，也不是智能体改的，它是第三种来源。

SQLite 不能 `ALTER ... MODIFY CHECK`，要重建表：

```sql
-- change_log.origin 增加 'import'。
--
-- 两处都要改，缺一不可：
--   1. 这里的 CHECK 约束（数据库侧兜底）
--   2. ChangeLogService.ALLOWED_ORIGINS（Java 侧先拦，给出能读懂的报错）
--
-- SQLite 改不了 CHECK，只能重建表。change_log 是审计表本身，所以重建时必须
-- 一字不差地把所有列与索引带过来——漏一个索引，审计查询就退化成全表扫描。
CREATE TABLE change_log_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    changed_at      TEXT    NOT NULL,
    origin          TEXT    NOT NULL CHECK (origin IN ('user', 'agent', 'import')),
    action          TEXT    NOT NULL CHECK (action IN ('insert', 'update', 'delete')),
    target_table    TEXT    NOT NULL,
    target_id       INTEGER NOT NULL,
    before_json     TEXT,
    -- 放宽成可空：delete 没有「改动之后」的状态。
    -- 之前的 NOT NULL 迫使调用方拿 before 去充数——那写进库的是一句假话，
    -- 而审计表里最不该有的就是「看起来合法但语义是错的」行。
    after_json      TEXT,
    undone          INTEGER NOT NULL DEFAULT 0,
    chat_session_id INTEGER,
    -- 三种 action 各自的快照规则，全部用 CHECK 钉死：
    --   insert：必须有 after（不然不知道插了什么）
    CHECK (action <> 'insert' OR after_json IS NOT NULL),
    --   update：两边都要有（撤销靠 before，追溯靠 after）
    CHECK (action <> 'update' OR (before_json IS NOT NULL AND after_json IS NOT NULL)),
    --   delete：必须有 before（不然不知道删了什么），且 after 必须为空
    CHECK (action <> 'delete' OR (before_json IS NOT NULL AND after_json IS NULL))
);

INSERT INTO change_log_new (id, changed_at, origin, action, target_table, target_id,
                            before_json, after_json, undone, chat_session_id)
SELECT id, changed_at, origin, action, target_table, target_id,
       before_json, after_json, undone, chat_session_id
FROM change_log;

DROP TABLE change_log;
ALTER TABLE change_log_new RENAME TO change_log;
```

> **⚠️ 迁移前先核对真实 DDL**：上面的列清单是我照 `V4__auxiliary.sql` 重述的。**执行前必须打开 `V4__auxiliary.sql` 逐列比对**，把索引也一并重建（`grep -i "index" V4__auxiliary.sql`）。漏一个索引不会有任何报错，只会让审计查询变慢——而那是最难发现的一类退化。
>
> 有一条 `MigrationTest` 会比对 schema 快照；迁移写错它会红。**红的时候去看它报的差异，不要改测试。**

- [x] **Step 3: 写失败的测试（先核对 API，别照抄本计划的签名）**

⚠️ **`ChangeLogService` 的真实公开 API 是这三个，没有 `record` / `redo` / `Action` / `Origin` 枚举**：

```java
public long recordInsert(String table, long targetId, Map<String, Object> after,
                         String origin, Long chatSessionId);
public long recordUpdate(String table, long targetId, Map<String, Object> before,
                         Map<String, Object> after, String origin, Long chatSessionId);
public void undo(long logId);
```

`origin` 是**字符串**，`chatSessionId` 可为 `null`。`writableColumns` 是 **private**——不要从测试里调它，要测它的行为。

`backend/src/test/java/com/change/service/ChangeLogJoinTableTest.java`：

```java
package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * exercise_muscle 与 origin='import' 两件事的守卫。
 *
 * <p>守的是一个**可验证的**失效：创建一个动作并挂 3 块肌肉，日志里只有 1 行，
 * 于是「凭日志重建状态」做不到——undo 会级联删掉那 3 行，而日志里没有东西让它们回来。
 */
@SpringBootTest
class ChangeLogJoinTableTest {

    @Autowired ChangeLogService changeLog;
    @Autowired JdbcTemplate jdbc;

    private long newExercise(String name) {
        return jdbc.queryForObject(
                "INSERT INTO exercise (name_en, default_unit) VALUES (?, 'kg') RETURNING id",
                Long.class, name);
    }

    private long someMuscleId() {
        return jdbc.queryForObject("SELECT id FROM muscle_group LIMIT 1", Long.class);
    }

    @Test
    void exercise_muscle_is_admitted_to_the_audit_net() {
        // 准入条件是「单列整型 id」。加了代理 id 之后它必须在这个集合里。
        assertThat(ChangeLogService.ALLOWED_TABLES).contains("exercise_muscle");
    }

    @Test
    void a_join_row_can_be_recorded_and_undone() {
        // 这是 Task 1 的全部意义：通用回放机制能作用在 join 表上。
        long exId = newExercise("ZZ Join Undo Test");
        long muscleId = someMuscleId();
        long joinId = jdbc.queryForObject(
                "INSERT INTO exercise_muscle (exercise_id, muscle_group_id, role) VALUES (?,?,'primary') RETURNING id",
                Long.class, exId, muscleId);

        long logId = changeLog.recordInsert("exercise_muscle", joinId,
                Map.of("exercise_id", exId, "muscle_group_id", muscleId, "role", "primary"),
                "user", null);

        changeLog.undo(logId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_muscle WHERE id = ?", Integer.class, joinId))
                .as("undo 一条 join 行的 insert 应当物理删除它")
                .isZero();

        // 收尾：删掉 exercise 会级联剩下的
        jdbc.update("DELETE FROM exercise WHERE id = ?", exId);
    }

    @Test
    void undoing_every_log_row_returns_the_database_to_its_original_state() {
        // 这是「日志完整」的可验证形式：把一次创建动作+挂肌肉的全部日志按逆序 undo，
        // 库必须回到创建之前的样子。
        //
        // 如果 join 行不在日志里（Task 1 之前的状态），这条会失败在
        // 「undo 之后还有残留的 change_log 行」或「muscle 行数对不上」上。
        int exercisesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM exercise", Integer.class);
        int musclesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM exercise_muscle", Integer.class);
        int logsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM change_log", Integer.class);

        long exId = newExercise("ZZ Whole Replay Test");
        long muscleId = someMuscleId();

        // 按真实写入顺序记录：先 exercise，再它的 3 条肌肉（这里用 1 条肌肉 × 3 个角色不行，
        // 唯一索引不允许，所以用现有的 3 个不同 muscle_group）
        List<Long> muscles = jdbc.queryForList(
                "SELECT id FROM muscle_group ORDER BY id LIMIT 3", Long.class);
        long exLogId = changeLog.recordInsert("exercise", exId,
                Map.of("name_en", "ZZ Whole Replay Test", "default_unit", "kg"), "user", null);

        List<Long> joinLogIds = new java.util.ArrayList<>();
        for (Long m : muscles) {
            long joinId = jdbc.queryForObject(
                    "INSERT INTO exercise_muscle (exercise_id, muscle_group_id, role) VALUES (?,?,'primary') RETURNING id",
                    Long.class, exId, m);
            joinLogIds.add(changeLog.recordInsert("exercise_muscle", joinId,
                    Map.of("exercise_id", exId, "muscle_group_id", m, "role", "primary"),
                    "user", null));
        }

        // 逆序 undo：先 join 行，再 exercise（真实用户会这么撤）
        for (int i = joinLogIds.size() - 1; i >= 0; i--) {
            changeLog.undo(joinLogIds.get(i));
        }
        changeLog.undo(exLogId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exercise", Integer.class))
                .as("undo 完全部日志后 exercise 行数应回到原值").isEqualTo(exercisesBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exercise_muscle", Integer.class))
                .as("undo 完全部日志后 exercise_muscle 行数应回到原值").isEqualTo(musclesBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM change_log", Integer.class))
                .as("日志行本身不会被 undo 删掉（撤销不是抹除历史）").isEqualTo(logsBefore + 1 + muscles.size());
    }

    @Test
    void origin_import_is_accepted() {
        // origin 是 String，不是枚举。'import' 必须被 Java 侧与数据库侧同时接受，
        // 否则导入写日志时会同时撞 IllegalArgumentException 与 CHECK constraint。
        long exId = newExercise("ZZ Origin Import Test");
        long logId = changeLog.recordInsert("exercise", exId,
                Map.of("name_en", "ZZ Origin Import Test", "default_unit", "kg"), "import", null);
        assertThat(logId).isPositive();

        jdbc.update("DELETE FROM change_log WHERE id = ?", logId);
        jdbc.update("DELETE FROM exercise WHERE id = ?", exId);
    }

    @Test
    void an_unknown_origin_is_still_rejected() {
        // 放宽 origin 不能变成「什么都能写」。这条钉住边界还在。
        assertThatThrownBy(() -> changeLog.recordInsert("exercise", 1L,
                Map.of("name_en", "x", "default_unit", "kg"), "gremlin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("origin");
    }

    @Test
    void a_primary_key_alias_is_still_rejected_as_a_writable_column() {
        // writableColumns 是 private，所以这里测的是它的**行为**：
        // 用 rowid 当快照的键必须被拒绝，否则 undo 能把一行搬到另一个 id 上。
        assertThatThrownBy(() -> changeLog.recordInsert("exercise", 1L,
                Map.of("rowid", 5), "user", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rowid");
    }
}
```

> **这条 `undoing_every_log_row_returns_the_database_to_its_original_state` 是 Task 1 的核心验收。**
> 它在 Task 1 结束时**会失败**——因为此刻还没有任何代码在创建动作时把 join 行写进日志（那要等 Task 4 的导入路径）。**不要为了让这条绿而弱化它**；在 Task 4 Step 5 之后它自然转绿。
>
> 如果 Task 1 单独跑时它挡住了其它测试，用 `@Disabled("Task 4 才会转绿：需要导入路径把 join 行写日志")` 标住，**并在 Task 4 的提交里去掉这个注解**。

- [x] **Step 4: 跑测试，确认失败**

Run: `cd backend && mvn -B -q -Dtest=ChangeLogJoinTableTest test`

Expected:
- `exercise_muscle_is_admitted_to_the_audit_net` **失败**（集合里还没有它）
- `origin_import_is_accepted` **失败**（`ALLOWED_ORIGINS` 还没有 import）
- 其余按上面的说明（`undoing_every_log_row…` 预期失败或 `@Disabled`）

- [x] **Step 5: 改 `ChangeLogService` 的两处常量**

```java
    // 加一行，并改写上面注释里那句「exercise_muscle 不进白名单」——
    // 那是 V5 之前的说法，现在是错的。留着错的注释比没有注释更糟。
    public static final Set<String> ALLOWED_TABLES = Set.of(
            "food", "food_portion", "meal", "meal_item",
            "exercise", "exercise_muscle",
            "workout_session", "session_exercise", "workout_set",
            "body_metric", "photo");

    /**
     * 合法的变更来源。必须与 {@code V6__change_log_origin_import.sql} 里
     * {@code change_log.origin} 的 CHECK 约束一致。
     *
     * <p>{@code import} 与另外两个是**不同的语义**：{@code user} 是用户手点的，
     * {@code agent} 是智能体改的，{@code import} 是批量导入产生的（一次 876 个动作）。
     * 分开之后界面才能把「一次导入的 3400 条改动」与「你手改的 3 条」分组显示。
     */
    public static final List<String> ALLOWED_ORIGINS = List.of("user", "agent", "import");
```

**同时把 `ALLOWED_TABLES` 上面那段注释改准**——它现在写着「复合主键的表……所以不进白名单」，那是 V5 之前的真实情况，现在是错的。

- [x] **Step 6: 补一个公开的 `recordDelete`，并让 `undo` 拒绝它**

`unimport`（Task 5）需要记录删除，但现在 `write(...)` 是 private 且没有 delete 的公开入口。

**设计已定（不走权宜之计）**：Step 2 的迁移已经把 `after_json` 放宽成可空，并用三条 `CHECK` 钉住每种 action 的快照规则。所以 `recordDelete` **传 `after` 为 null**，不拿 `before` 去充数——审计表里最不该有的就是「看起来合法但语义是错的」行。

```java
    /**
     * 记录一次删除。{@code after} 恒为 {@code null}——删除没有「改动之后」的状态。
     *
     * <p>这正是 V6 迁移把 {@code after_json} 放宽成可空的原因。旧 schema 的
     * {@code NOT NULL} 会迫使调用方拿 {@code before} 去充数，那写进库的是一句假话，
     * 而假话比空值危险得多：将来任何读 {@code after_json} 的代码都会得出一份
     * 「删掉之后长这样」的错误结论。
     */
    public long recordDelete(String table, long targetId, Map<String, Object> before,
                             String origin, Long chatSessionId) {
        if (before == null || before.isEmpty()) {
            // 与 recordUpdate 那条同一个理由：把约束翻译成能读懂的话。
            // 数据库侧有 CHECK (action <> 'delete' OR before_json IS NOT NULL) 兜底。
            throw new IllegalArgumentException(
                    "delete 记录必须带 before 快照，否则无从追溯删了什么");
        }
        return write(table, targetId, "delete", before, null, origin, chatSessionId);
    }
```

`write(...)` 要相应放宽：**`after` 的非空校验只在 action 不是 `delete` 时生效**。

**并且给 `undo` 加一条守卫**（紧跟在 `requireAllowed(table)` 之后）：

```java
        if ("delete".equals(action)) {
            // 回退一个 delete 需要把整行重新 INSERT——那要重建全部列与主键，
            // 而这在通用回放里做不到（一行快照并不含主键）。
            //
            // 不加这条守卫的后果是最坏的一类失效：undo 会走到下面「从 before 写回」
            // 的分支，对已删除的行 UPDATE 0 行，然后把 undone 标成 1——
            // **日志说撤了，数据没变**。
            throw new IllegalStateException(
                    "删除不能通过通用回放撤销（日志 " + logId + "）："
                            + "回退它需要重建整行，包括主键。"
                            + "第一阶段的删除只有批量导入的撤销，那是一次专用操作而不是回放。");
        }
```

**为什么第一阶段不做「回退 delete」**：唯一会记录 delete 的地方是 `unimport`（批量撤销导入），而它本身就是删除，不需要被回退。用户手动删动作的功能还没有。等真有那个需求时，再设计「重建整行」的路径——那时 `before` 快照足够（除了主键，而那可以记在别处）。

- [x] **Step 7: 补两条测试，把上面这套语义钉住**

加进 `ChangeLogJoinTableTest`：

```java
    @Test
    void a_delete_record_carries_a_before_snapshot_and_no_after() {
        // 三种 action 的快照规则是 DB 侧的 CHECK 守的，这里钉住 Java 侧的行为。
        long exId = newExercise("ZZ Delete Record Test");
        long logId = changeLog.recordDelete("exercise", exId,
                Map.of("name_en", "ZZ Delete Record Test", "default_unit", "kg"), "import", null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT before_json, after_json FROM change_log WHERE id = ?", logId);
        assertThat(row.get("before_json")).as("delete 必须有 before").isNotNull();
        assertThat(row.get("after_json")).as("delete 的 after 必须是 NULL，不能拿 before 充数").isNull();

        jdbc.update("DELETE FROM change_log WHERE id = ?", logId);
        jdbc.update("DELETE FROM exercise WHERE id = ?", exId);
    }

    @Test
    void undoing_a_delete_is_refused_instead_of_silently_doing_nothing() {
        // 不加守卫的话 undo 会 UPDATE 0 行然后把 undone 标成 1：
        // 日志说撤了，数据没变——最坏的一类失效。
        long exId = newExercise("ZZ Undo Delete Test");
        long logId = changeLog.recordDelete("exercise", exId,
                Map.of("name_en", "ZZ Undo Delete Test", "default_unit", "kg"), "import", null);

        assertThatThrownBy(() -> changeLog.undo(logId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("删除不能通过通用回放撤销");

        // 而且它没有把 undone 标成 1
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId))
                .as("拒绝之后不能留下「已撤销」的印记").isZero();

        jdbc.update("DELETE FROM change_log WHERE id = ?", logId);
        jdbc.update("DELETE FROM exercise WHERE id = ?", exId);
    }
```

- [x] **Step 8: 跑测试**

Run: `cd backend && mvn -B clean test`

Expected: 迁移后 schema 校验通过；`ChangeLogJoinTableTest` 除那条标了 `@Disabled` 的全绿（含新增的两条）。

- [x] **Step 9: 提交**

```bash
git add backend/src/main/resources/db/migration/V5__exercise_muscle_surrogate_id.sql \
        backend/src/main/resources/db/migration/V6__change_log_origin_import.sql \
        backend/src/main/java/com/change/service/ChangeLogService.java \
        backend/src/test/java/com/change/service/ChangeLogJoinTableTest.java
git commit -m "feat(db): exercise_muscle 进审计网 + origin 增加 import

准入条件是「单列整型 id」，而 exercise_muscle 是复合主键，所以一直被排除在
审计之外。真实危害是**审计日志不完整**：创建一个动作并挂 3 块肌肉，日志里只有
1 行——「这次改动动了什么」答不出来，「凭日志重建状态」也做不到（undo 会级联
删掉那 3 行，日志里没有东西让它们回来）。

加代理 id 把复合键降级成唯一索引，现有回放机制不用改一行就能覆盖它。

同时给 origin 增加 'import'：导入既不是用户手点的也不是智能体改的，它是第三种
来源。两处都要改（CHECK 约束 + ALLOWED_ORIGINS），缺一不可。

另外给 undo 加了一条拒绝 action='delete' 的守卫——否则撤销一条 delete 日志会
静默地 UPDATE 0 行然后标记 undone=1，日志说撤了而数据没变。
"
```

---

## Task 2: `muscle_group` 的 17 行种子与 `region_ids` 口径

数据集的 `primaryMuscles` / `secondaryMuscles` 是**17 值固定枚举**（实测确认），所以种子数据就是 17 行。

`region_ids` 此前**没有任何地方定义过**，而计划 6 的 2D 人体图要靠它。**这里定义它。**

**Files:**
- Create: `backend/src/main/java/com/change/seed/MuscleRegion.java`
- Create: `backend/src/main/java/com/change/seed/MuscleGroupSeed.java`
- Create: `backend/src/main/java/com/change/domain/MuscleGroup.java`
- Create: `backend/src/main/java/com/change/repo/MuscleGroupRepo.java`
- Create: `backend/src/main/resources/db/migration/V7__muscle_group_seed.sql`
- Test: `backend/src/test/java/com/change/seed/MuscleGroupSeedTest.java`

- [x] **Step 1: 定 `region_ids` 的口径（先定词汇，再建数据）**

`region_ids` 是「人体图区域 id，逗号分隔」。**这个 id 的词汇由我们定义，不由外部文件定义。**

理由：外部 SVG 文件换了版本，不该让我们的库里 876 条动作的标注跟着漂。映射的责任放在**前端**（计划 6）——它拿 SVG path → 我们的 region id，是一张静态表。

```java
package com.change.seed;

/**
 * 人体图区域 id 的**唯一词汇表**。
 *
 * <p>为什么自己定义而不是直接用某个 SVG 文件里的 path id：那是外部实现细节，
 * 换了文件版本不该让库里 876 条动作的标注跟着漂。映射的责任放在前端
 * （计划 6 的 2D 人体图拿 SVG path → 这里的 id，是一张静态表）。
 *
 * <p>大部分肌群只对应一个区域；{@code chest} 拆左右是因为人体图上胸肌
 * 是左右两块独立路径，而「本周胸 12 组」这种统计要能分别点亮。
 */
public enum MuscleRegion {
    /** 胸（左右分开，人体图上是两块路径）。 */
    CHEST_LEFT("chest_left"),
    CHEST_RIGHT("chest_right"),
    ABDOMINALS("abdominals"),
    OBLIQUES("obliques"),
    LATS_LEFT("lats_left"),
    LATS_RIGHT("lats_right"),
    MIDDLE_BACK("middle_back"),
    LOWER_BACK("lower_back"),
    TRAPS("traps"),
    SHOULDERS_LEFT("shoulders_left"),
    SHOULDERS_RIGHT("shoulders_right"),
    BICEPS_LEFT("biceps_left"),
    BICEPS_RIGHT("biceps_right"),
    TRICEPS_LEFT("triceps_left"),
    TRICEPS_RIGHT("triceps_right"),
    FOREARMS("forearms"),
    GLUTES_LEFT("glutes_left"),
    GLUTES_RIGHT("glutes_right"),
    QUADRICEPS_LEFT("quadriceps_left"),
    QUADRICEPS_RIGHT("quadriceps_right"),
    HAMSTRINGS_LEFT("hamstrings_left"),
    HAMSTRINGS_RIGHT("hamstrings_right"),
    ADDUCTORS("adductors"),
    ABDUCTORS("abductors"),
    CALVES_LEFT("calves_left"),
    CALVES_RIGHT("calves_right"),
    NECK("neck");

    /** 存进 `muscle_group.region_ids` 的字面量。**改它等于改数据**，要写迁移。 */
    public final String id;

    MuscleRegion(String id) {
        this.id = id;
    }
}
```

- [x] **Step 2: 写失败的测试**

`backend/src/test/java/com/change/seed/MuscleGroupSeedTest.java`：

```java
package com.change.seed;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MuscleGroupSeedTest {

    @Test
    void there_are_exactly_seventeen_groups() {
        // 数据集的 primaryMuscles 是 17 值固定枚举（实测确认）。
        // 数量一变就说明数据集换了版本，或者我们漏加/多加了一块肌肉。
        assertThat(MuscleGroupSeed.ALL).hasSize(17);
    }

    @Test
    void every_group_has_a_chinese_and_an_english_name() {
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            assertThat(g.nameEn).as("nameEn").isNotBlank();
            assertThat(g.nameZh).as("nameZh").isNotBlank();
            // 中文名必须真的含中文，否则只是把英文抄了一遍
            assertThat(g.nameZh.chars().anyMatch(c -> c > 0x4E00 && c < 0x9FFF))
                    .as("nameZh 应含汉字: %s", g.nameZh).isTrue();
        }
    }

    @Test
    void english_names_are_unique() {
        // 库里有唯一索引，这里提前挡住，免得导入时才发现
        List<String> names = MuscleGroupSeed.ALL.stream().map(g -> g.nameEn).toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void every_region_id_comes_from_the_declared_vocabulary() {
        // 这是「region_ids 只有一份词汇表」的守卫：任何写进种子的 id
        // 都必须是 MuscleRegion 里声明过的，不能是随手打的字符串。
        Set<String> vocabulary = Arrays.stream(MuscleRegion.values())
                .map(r -> r.id).collect(Collectors.toSet());
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            for (String id : g.regionIds) {
                assertThat(vocabulary).as("region id %s 不在词汇表里", id).contains(id);
            }
        }
    }

    @Test
    void every_group_has_at_least_one_region() {
        // 没有区域的肌群在人体图上永远点不亮，那是静默的空白。
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            assertThat(g.regionIds).as("%s 没有 region", g.nameEn).isNotEmpty();
        }
    }

    @Test
    void weekly_targets_are_either_both_null_or_a_valid_pair() {
        // classify 对「半个区间」返回 NOT_CONFIGURED，所以 min/max 必须成对。
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            boolean minNull = g.weeklySetsMin == null;
            boolean maxNull = g.weeklySetsMax == null;
            assertThat(minNull).as("%s 的 min/max 必须成对", g.nameEn).isEqualTo(maxNull);
            if (!minNull) {
                assertThat(g.weeklySetsMin).isGreaterThanOrEqualTo(0);
                assertThat(g.weeklySetsMax).isGreaterThan(g.weeklySetsMin);
            }
        }
    }

    @Test
    void the_seventeen_english_names_match_the_dataset_vocabulary() {
        // 与数据集逐字对齐：差一个字符，876 条里所有挂这块肌肉的动作都会导不进来。
        assertThat(MuscleGroupSeed.ALL.stream().map(g -> g.nameEn).collect(Collectors.toSet()))
                .isEqualTo(Set.of(
                        "abdominals", "abductors", "adductors", "biceps", "calves",
                        "chest", "forearms", "glutes", "hamstrings", "lats",
                        "lower back", "middle back", "neck", "quadriceps",
                        "shoulders", "traps", "triceps"));
    }
}
```

- [x] **Step 3: 跑测试，确认失败**

Run: `cd backend && mvn -B -q -Dtest=MuscleGroupSeedTest test`

Expected: 编译失败 —— `cannot find symbol: class MuscleGroupSeed` / `MuscleRegion`。

- [x] **Step 4: 写种子数据**

`backend/src/main/java/com/change/seed/MuscleGroupSeed.java`：

```java
package com.change.seed;

import java.util.List;

/**
 * 17 块肌群的种子数据。
 *
 * <p>英文名与 free-exercise-db 的 primaryMuscles / secondaryMuscles **逐字对齐**——
 * 差一个字符，876 条里所有挂这块肌肉的动作都会在导入时找不到对应行。
 *
 * <p>{@code bodyPart} 是粗粒度分区，用来给动作选择器分组（设计文档九、界面）。
 * {@code weeklySetsMin/Max} 是「建议周容量」的区间，来源见 SCALE_SOURCE。
 */
public final class MuscleGroupSeed {

    private MuscleGroupSeed() {}

    /**
     * 周容量区间的依据。
     *
     * <p>取值落在公开的肌肥大容量研究给出的区间内（每块肌肉每周约 10–20 组
     * 有效容量是常见共识）。**这不是医学建议**，它是界面上「本周 0 组」这类
     * 提醒的比较基准。值刻意偏宽松——把下限压低，宁可提醒得晚一点，
     * 也不要让一个刚起步的人天天看到红字。
     */
    public static final String SCALE_SOURCE =
            "每块肌肉每周约 10–20 组有效容量（肌肥大训练的常见共识区间），此处取偏宽松的下限";

    /** 一块肌群的种子定义。 */
    public static final class G {
        public final String nameEn;
        public final String nameZh;
        public final String bodyPart;
        public final List<String> regionIds;
        public final Integer weeklySetsMin;
        public final Integer weeklySetsMax;

        G(String nameEn, String nameZh, String bodyPart,
          List<String> regionIds, Integer min, Integer max) {
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.bodyPart = bodyPart;
            this.regionIds = regionIds;
            this.weeklySetsMin = min;
            this.weeklySetsMax = max;
        }
    }

    public static final List<G> ALL = List.of(
        // ——— 躯干前侧 ———
        new G("chest",       "胸部",   "躯干前侧",
              List.of(MuscleRegion.CHEST_LEFT.id, MuscleRegion.CHEST_RIGHT.id), 10, 20),
        new G("abdominals",  "腹直肌", "躯干前侧",
              List.of(MuscleRegion.ABDOMINALS.id), 8, 16),
        // ——— 躯干后侧 ———
        new G("lats",        "背阔肌", "躯干后侧",
              List.of(MuscleRegion.LATS_LEFT.id, MuscleRegion.LATS_RIGHT.id), 10, 20),
        new G("middle back", "中背部", "躯干后侧",
              List.of(MuscleRegion.MIDDLE_BACK.id), 8, 16),
        new G("lower back",  "下背部", "躯干后侧",
              List.of(MuscleRegion.LOWER_BACK.id), 4, 10),
        new G("traps",       "斜方肌", "躯干后侧",
              List.of(MuscleRegion.TRAPS.id), 6, 14),
        // ——— 上肢 ———
        new G("shoulders",   "肩部",   "上肢",
              List.of(MuscleRegion.SHOULDERS_LEFT.id, MuscleRegion.SHOULDERS_RIGHT.id), 8, 18),
        new G("biceps",      "肱二头肌", "上肢",
              List.of(MuscleRegion.BICEPS_LEFT.id, MuscleRegion.BICEPS_RIGHT.id), 6, 14),
        new G("triceps",     "肱三头肌", "上肢",
              List.of(MuscleRegion.TRICEPS_LEFT.id, MuscleRegion.TRICEPS_RIGHT.id), 6, 14),
        new G("forearms",    "前臂",   "上肢",
              List.of(MuscleRegion.FOREARMS.id), 4, 10),
        // ——— 下肢 ———
        new G("quadriceps",  "股四头肌", "下肢",
              List.of(MuscleRegion.QUADRICEPS_LEFT.id, MuscleRegion.QUADRICEPS_RIGHT.id), 10, 20),
        new G("hamstrings",  "腘绳肌", "下肢",
              List.of(MuscleRegion.HAMSTRINGS_LEFT.id, MuscleRegion.HAMSTRINGS_RIGHT.id), 8, 16),
        new G("glutes",      "臀肌",   "下肢",
              List.of(MuscleRegion.GLUTES_LEFT.id, MuscleRegion.GLUTES_RIGHT.id), 8, 16),
        new G("calves",      "小腿",   "下肢",
              List.of(MuscleRegion.CALVES_LEFT.id, MuscleRegion.CALVES_RIGHT.id), 6, 14),
        new G("adductors",   "内收肌", "下肢",
              List.of(MuscleRegion.ADDUCTORS.id), 4, 10),
        new G("abductors",   "外展肌", "下肢",
              List.of(MuscleRegion.ABDUCTORS.id), 4, 10),
        // ——— 颈 ———
        new G("neck",        "颈部",   "颈",
              List.of(MuscleRegion.NECK.id), null, null)
    );

    /** 按英文名查，导入时用。 */
    public static G byNameEn(String nameEn) {
        for (G g : ALL) {
            if (g.nameEn.equals(nameEn)) return g;
        }
        return null;
    }
}
```

> `neck` 的周容量给 `null`：**颈部训练不该被「本周 0 组」提醒追着**。`classify` 对未配置的肌肉返回 `NOT_CONFIGURED`，正好用得上（计划 1 的 `VolumeCalculator` 就是这么设计的）。

- [x] **Step 5: 写实体、Repo 与迁移**

`MuscleGroup` 实体（公开字段、无 getter，与项目既有风格一致）：

```java
package com.change.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 一块肌群。种子数据见 {@code MuscleGroupSeed}。 */
@Entity
@Table(name = "muscle_group")
public class MuscleGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "name_zh", nullable = false)
    public String nameZh;

    @Column(name = "name_en", nullable = false, unique = true)
    public String nameEn;

    /** 逗号分隔的多值，如 "left,right"。粗粒度分区，给选择器分组用。 */
    @Column(name = "body_part", nullable = false)
    public String bodyPart;

    /** 逗号分隔的 {@link com.change.seed.MuscleRegion} id。 */
    @Column(name = "region_ids")
    public String regionIds;

    @Column(name = "weekly_sets_min")
    public Integer weeklySetsMin;

    @Column(name = "weekly_sets_max")
    public Integer weeklySetsMax;
}
```

`MuscleGroupRepo`：

```java
package com.change.repo;

import com.change.domain.MuscleGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MuscleGroupRepo extends JpaRepository<MuscleGroup, Long> {

    /** 导入时按英文名查。名字与数据集逐字对齐，所以这里不能模糊匹配。 */
    Optional<MuscleGroup> findByNameEn(String nameEn);
}
```

迁移 `V7__muscle_group_seed.sql` —— **由代码从 `MuscleGroupSeed` 生成，不手抄**，避免「代码里改了、SQL 没改」。生成方式：

```powershell
# 在 backend/ 下，用一个临时 JUnit 把 Seed 打成 SQL 打到 stdout，再人工核对后落盘。
# 这一步故意不用 Flyway 的 callback：种子必须是一次性、可审计、可 diff 的 SQL 文本。
```

**这一步的验收**：迁移跑完之后 `SELECT COUNT(*) FROM muscle_group` 等于 17，且 `name_en` 集合与数据集逐字相同（用 Task 2 Step 2 那条测试守）。

- [x] **Step 6: 跑测试**

Run: `cd backend && mvn -B clean test`

Expected: 全绿，且 `MuscleGroupSeedTest` 7 条通过。

- [x] **Step 7: 提交**

```bash
git add backend/src/main/java/com/change/seed backend/src/main/java/com/change/domain/MuscleGroup.java \
        backend/src/main/java/com/change/repo/MuscleGroupRepo.java \
        backend/src/main/resources/db/migration/V7__muscle_group_seed.sql \
        backend/src/test/java/com/change/seed/MuscleGroupSeedTest.java
git commit -m "feat(db): 17 块肌群的种子数据 + region_ids 的词汇表

数据集的 primaryMuscles 是 17 值固定枚举（实测确认），种子就是这 17 行。

region_ids 此前没有任何地方定义过，而计划 6 的 2D 人体图要靠它。
这里把它定义成我们自己的词汇（MuscleRegion），而不是直接用某个 SVG 文件里的
path id——那是外部实现细节，换版本不该让库里 876 条动作的标注跟着漂。
映射放在前端，是一张静态表。

neck 的周容量给 null：颈部训练不该被「本周 0 组」追着。
"
```

---

## Task 3: vendor 数据集 + 字段映射（纯函数，876 条全覆盖）

**Files:**
- Create: `backend/src/main/resources/seed/free-exercise-db/exercises.json`（vendor）
- Create: `backend/src/main/resources/seed/free-exercise-db/LICENSE`
- Create: `backend/src/main/java/com/change/seed/FreeExerciseDb.java`
- Create: `backend/src/main/resources/db/migration/V8__exercise_category_and_instructions.sql`
- Modify: `backend/src/main/java/com/change/domain/Exercise.java`
- Test: `backend/src/test/java/com/change/seed/FreeExerciseDbTest.java`

- [ ] **Step 1: vendor 数据集、schema 与许可证**

```powershell
# 直连被掐，走镜像（实测 fastly.jsdelivr 最快，319ms）
$base = 'https://fastly.jsdelivr.net/gh/yuhonas/free-exercise-db@main'
$dir  = 'E:\idea\program\change\backend\src\main\resources\seed\free-exercise-db'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Invoke-WebRequest -Uri "$base/dist/exercises.json" -OutFile "$dir\exercises.json"
Invoke-WebRequest -Uri "$base/schema.json"        -OutFile "$dir\schema.json"
Invoke-WebRequest -Uri "$base/LICENSE.md"         -OutFile "$dir\LICENSE.md"
```

> **⚠️ 许可证的真实文件名是 `LICENSE.md`，不是 `LICENSE`。** 仓库根目录没有 `LICENSE`
> （实测 404）。许可证类型经 GitHub API 确认：`spdx_id = Unlicense`，
> 正文首行 `This is free and unencumbered software released into the public domain.`
>
> **`schema.json` 也要 vendor**：它是数据集的**权威字段契约**（哪些字段 required、
> 各自的 enum）。字段映射对不对，最终以它为准，而不是以本计划的描述为准。

**必须校验**：

```powershell
$env:PYTHONIOENCODING='utf-8'
& 'E:\dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe' -c @"
import hashlib, pathlib
p = pathlib.Path(r'E:\idea\program\change\backend\src\main\resources\seed\free-exercise-db\exercises.json')
b = p.read_bytes()
print('bytes =', len(b))
print('sha256 =', hashlib.sha256(b).hexdigest())
assert len(b) == 1005327, f'大小不符: {len(b)}'
import json; print('条数 =', len(json.loads(b.decode('utf-8'))))
"@
```

Expected: `bytes = 1005327`，`条数 = 876`。**大小对不上就停下来**——数据集换了版本，字段映射要重新核对。

**再核 `schema.json`**（它是字段契约的权威来源，与实测的分布应当一致）：

| 字段 | schema 说 | 实测分布 |
|---|---|---|
| `id` / `name` | required | 876 条都有，无重复 |
| `mechanic` | required，值可为 null | 789 字符串 + **87 null** |
| `equipment` | required，值可为 null | 799 字符串 + **77 null** |
| `force` | **不 required**（可选） | 846 字符串 + 30 null |
| `level` | required | 876 条都有（beginner/intermediate/expert） |
| `category` | required，7 值 | 876 条都有 |
| `instructions` | required（数组） | **5 条是空数组** |

> `mechanic` / `equipment` 的 null 是 **schema 允许的**，不是数据缺失。所以
> `is_compound = "compound".equals(mechanic)`（null → false）是对的，不需要额外缺省逻辑。

**许可证**：`LICENSE.md` 已在 Step 1 一起 vendor。**Unlicense 要求随分发保留**，所以它必须在
`src/main/resources/` 里进 jar，不能只在仓库根放着。

- [ ] **Step 2: 迁移加两个列**

```sql
-- 数据集里有四处信息在现有 exercise 表上没有位置：
-- category(7 值) / level(3 值) / force(3 值) / instructions(多段文本)。
--
-- 只加 category 与 instructions，理由：
--   category 是**功能性的**——876 条里 292 条不是 strength（123 条 stretching、
--   61 plyometrics、14 cardio…）。「记一组」的选择器必须能把它们滤掉，
--   否则拉伸会混进训练记录。
--   instructions 是「这个动作怎么做」，缺了动作库就只能当名字列表用。
-- level / force 第一阶段用不上（YAGNI），而且 dataset 是 vendor 的，
-- 需要时凭 source_id 能反查回来，不会丢。
ALTER TABLE exercise ADD COLUMN category     TEXT;
ALTER TABLE exercise ADD COLUMN instructions TEXT;
```

- [ ] **Step 3: 写失败的测试**

`backend/src/test/java/com/change/seed/FreeExerciseDbTest.java`：

```java
package com.change.seed;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 876 条数据集的解析与字段映射。
 *
 * <p>这些是纯数据变换，不起 Spring 就能毫秒级覆盖全部 876 条——
 * 而 876 条里有 9 条重叠、87 条 mechanic=null、77 条 equipment=null、
 * 844 条 id≠name，靠抽查是漏不掉的。
 */
class FreeExerciseDbTest {

    private static final List<FreeExerciseDb.Row> ROWS = FreeExerciseDb.load();

    @Test
    void loads_exactly_876_rows() {
        assertThat(ROWS).hasSize(876);
    }

    @Test
    void every_row_has_a_name_and_a_source_id_with_the_namespace() {
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.nameEn).isNotBlank();
            // source_id 必须带命名空间前缀，否则接第二个数据集时静默冲突
            assertThat(r.sourceId).startsWith("free-exercise-db:");
        }
    }

    @Test
    void source_ids_are_unique() {
        // 库上那个部分唯一索引会拦，但在这里暴露能直接指出是哪两条
        List<String> ids = ROWS.stream().map(r -> r.sourceId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void english_names_are_unique() {
        // ux_exercise_name_en 会拦，但这条能指出冲突的是哪几个
        List<String> names = ROWS.stream().map(r -> r.nameEn).toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void every_row_has_at_least_one_primary_muscle() {
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.primaryMuscles).as("%s 没有主肌群", r.nameEn).isNotEmpty();
        }
    }

    @Test
    void every_muscle_name_resolves_against_the_seed() {
        // 差一个字符，这条动作就挂不上肌群——而它不会报错，只会少一条标注。
        for (FreeExerciseDb.Row r : ROWS) {
            for (String m : r.primaryMuscles) {
                assertThat(MuscleGroupSeed.byNameEn(m))
                        .as("%s 的主肌群 %s 在种子数据里找不到", r.nameEn, m).isNotNull();
            }
            for (String m : r.secondaryMuscles) {
                assertThat(MuscleGroupSeed.byNameEn(m))
                        .as("%s 的次肌群 %s 在种子数据里找不到", r.nameEn, m).isNotNull();
            }
        }
    }

    @Test
    void the_nine_overlapping_rows_resolve_primary_over_secondary() {
        // 9 条动作的 primaryMuscles 与 secondaryMuscles 有重叠。
        // 朴素地两边都写会撞 (exercise_id, muscle_group_id) 的唯一索引，整批回滚。
        // 规则：primary 赢。这条测试同时钉住「重叠确实存在」与「被正确化解」。
        long overlapping = ROWS.stream().filter(FreeExerciseDb.Row::hasOverlap).count();
        assertThat(overlapping).as("数据集里应当有 9 条重叠").isEqualTo(9);

        for (FreeExerciseDb.Row r : ROWS) {
            Set<String> keys = r.muscleAssignments().stream()
                    .map(a -> a.muscleNameEn)
                    .collect(Collectors.toSet());
            assertThat(keys).as("%s 的肌群标注有重复键", r.nameEn)
                    .hasSize(r.muscleAssignments().size());
        }
    }

    @Test
    void a_known_overlapping_row_is_marked_primary() {
        // 抽一条具体的来钉死规则，防止上面那条被「整体过滤掉重复」蒙过去
        FreeExerciseDb.Row r = ROWS.stream()
                .filter(x -> x.nameEn.equals("Barbell Step Ups")).findFirst().orElseThrow();
        Map<String, String> roles = r.muscleAssignments().stream()
                .filter(a -> a.muscleNameEn.equals("quadriceps"))
                .collect(Collectors.toMap(a -> a.muscleNameEn, a -> a.role));
        assertThat(roles).containsEntry("quadriceps", "primary");
    }

    @Test
    void total_assignments_are_2587() {
        // 实测：朴素相加 2596，去重后 2587。数字变了说明数据集换了版本。
        int total = ROWS.stream().mapToInt(r -> r.muscleAssignments().size()).sum();
        assertThat(total).isEqualTo(2587);
    }

    @Test
    void is_compound_is_false_when_mechanic_is_missing() {
        // 87 条 mechanic=null，is_compound 是 NOT NULL，默认必须是 false
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.isCompound).isEqualTo("compound".equals(r.mechanic));
        }
        assertThat(ROWS.stream().filter(r -> r.mechanic == null).count()).isEqualTo(87);
    }

    @Test
    void default_unit_is_bw_only_for_bodyweight_exercises() {
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.defaultUnit).isEqualTo("body only".equals(r.equipment) ? "bw" : "kg");
        }
        assertThat(ROWS.stream().filter(r -> "body only".equals(r.equipment)).count()).isEqualTo(111);
    }

    @Test
    void category_is_preserved_and_the_non_strength_count_is_292() {
        // 232 条非 strength 是「选择器必须过滤」这件事的数据依据
        long nonStrength = ROWS.stream().filter(r -> !"strength".equals(r.category)).count();
        assertThat(nonStrength).isEqualTo(292);
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.category).isNotBlank();
        }
    }

    @Test
    void instructions_are_joined_into_one_string() {
        // 871 条有 instructions，其余为空。多段之间用换行连，空则 null。
        assertThat(ROWS.stream().filter(r -> r.instructions != null).count()).isEqualTo(871);
        for (FreeExerciseDb.Row r : ROWS) {
            if (r.instructions != null) {
                assertThat(r.instructions).doesNotContain("\r");
            }
        }
    }

    @Test
    void name_zh_is_null_at_import_time() {
        // 中文名是后面的单独任务（Task 7）产出的。
        // 这里钉住「导入不编中文名」——空着比编一个错的诚实。
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.nameZh).isNull();
        }
    }
}
```

- [ ] **Step 4: 跑测试，确认失败**

Run: `cd backend && mvn -B -q -Dtest=FreeExerciseDbTest test`

Expected: 编译失败 —— `cannot find symbol: class FreeExerciseDb`。

- [ ] **Step 5: 写 `FreeExerciseDb`**

要求（**按这些要求实现，代码由你写**）：

1. **不依赖 Spring**：`load()` 是个静态方法，从 classpath 读 `seed/free-exercise-db/exercises.json`。
2. **先校验 sha256 与条数**，不符直接抛异常并说清期望值——**不要静默继续**。数据集换了版本必须让人知道。
3. 解析成 `Row`（公开字段）：
   - `nameEn` = `name`（**不是 `id`**——844 条两者不同）
   - `sourceId` = `"free-exercise-db:" + id`
   - `nameZh` = **恒 null**
   - `equipment` = 原值可能为 null
   - `isCompound` = `"compound".equals(mechanic)`
   - `defaultUnit` = `"body only".equals(equipment) ? "bw" : "kg"`
   - `category` = 原值（7 值之一）
   - `instructions` = 多段用 `\n` 连接；空数组则 `null`；**要做 `\r\n` → `\n` 归一化**
   - `mechanic` = 原值可能为 null（保留，测试要用）
   - `primaryMuscles` / `secondaryMuscles` = `List<String>`
4. **`muscleAssignments()`**——这是核心。返回 `List<Assignment>`，每个 `Assignment` 有 `muscleNameEn` 与 `role`（`"primary"`/`"secondary"`）：
   - 先写所有 primary
   - 再写 secondary，但**跳过已经作为 primary 出现过的**
   - **规则是「primary 赢」**，不是「后写的覆盖先写的」，也不是「随便丢一个」
5. **`hasOverlap()`**——primary 与 secondary 是否有交集。

- [ ] **Step 6: 改 `Exercise` 实体加两列**

```java
    /** 数据集的 category（7 值）。strength 之外的 292 条不该出现在「记一组」的选择器里。 */
    @Column(name = "category")
    public String category;

    /** 动作做法，多段用 \n 连接。可能为 null（876 条里 5 条没有）。 */
    @Column(name = "instructions")
    public String instructions;
```

- [ ] **Step 7: 跑测试**

Run: `cd backend && mvn -B clean test`

Expected: 全绿，`FreeExerciseDbTest` 13 条通过。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/resources/seed backend/src/main/resources/db/migration/V8__exercise_category_and_instructions.sql \
        backend/src/main/java/com/change/seed/FreeExerciseDb.java \
        backend/src/main/java/com/change/domain/Exercise.java \
        backend/src/test/java/com/change/seed/FreeExerciseDbTest.java
git commit -m "feat(seed): vendor free-exercise-db 并把 876 条映射成纯函数

数据集 1,005,327 字节、876 条、Unlicense（可随包分发），sha256 与条数在加载时校验。

四处实测到的陷阱都已处理，每条都有测试钉住：
- 9 条动作的 primary 与 secondary 有重叠，朴素两边都写会撞唯一索引整批回滚。
  规则是「primary 赢」，而不是随便丢一个。
- 876 条里 292 条不是 strength（123 条 stretching），所以加了 category 列——
  「记一组」的选择器必须能滤掉拉伸。
- 87 条 mechanic=null（is_compound 是 NOT NULL，默认 false）、
  77 条 equipment=null（default_unit 按 body only → bw 判）。
- id 与 name 有 844 条不同，所以 source_id 用 id 而不是 name。

中文名在这一步**刻意不生成**（恒 null）——空着比编一个错的诚实，Task 7 单独产出。
"
```

---

## Task 4: 导入的执行与幂等

**Files:**
- Create: `backend/src/main/java/com/change/domain/ExerciseMuscle.java`
- Create: `backend/src/main/java/com/change/repo/ExerciseMuscleRepo.java`
- Create: `backend/src/main/java/com/change/service/ExerciseImportService.java`
- Test: `backend/src/test/java/com/change/service/ExerciseImportServiceTest.java`

- [ ] **Step 1: 写失败的测试**

要求覆盖这几件事（**代码由你写**，但每条都要有）：

1. 首次导入后 `exercise` 有 **876** 行、`exercise_muscle` 有 **2587** 行
2. **重跑一次结果不变**（幂等）：行数仍是 876 / 2587，没有重复
3. 导入的结果里，`source_id` 全部以 `free-exercise-db:` 开通
4. 那 9 条重叠的动作，库里每个只有**一条** quadriceps（或对应肌肉）的标注，且 `role='primary'`
5. 导入写入的 `exercise_muscle` 行**在 `change_log` 里有对应记录**（这是 Task 4 Step 5 的重点）
6. 手工录入的动作（`source_id IS NULL`）**不受导入影响**——导入前后它的行数不变

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B -q -Dtest=ExerciseImportServiceTest test`

Expected: 编译失败 —— `cannot find symbol: class ExerciseImportService`。

- [ ] **Step 3: 写实体与 Repo**

`ExerciseMuscle`（复合业务键 + 代理主键，与 Task 1 的迁移一致）：

```java
package com.change.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 「这个动作练哪块肌肉、主要还是次要」。
 *
 * <p>有代理主键是因为审计网（ChangeLogService）的准入条件是「单列整型 id」——
 * 见 V5 迁移的注释。业务唯一性是 {@code (exercise_id, muscle_group_id)} 上的唯一索引。
 */
@Entity
@Table(name = "exercise_muscle")
public class ExerciseMuscle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "exercise_id", nullable = false)
    public Long exerciseId;

    @Column(name = "muscle_group_id", nullable = false)
    public Long muscleGroupId;

    /** 'primary' 或 'secondary'，DDL 有 CHECK，Java 侧见 V2 里的约定。 */
    @Column(name = "role", nullable = false)
    public String role;
}
```

`ExerciseMuscleRepo`：

```java
package com.change.repo;

import com.change.domain.ExerciseMuscle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExerciseMuscleRepo extends JpaRepository<ExerciseMuscle, Long> {

    List<ExerciseMuscle> findByExerciseId(Long exerciseId);

    /** 撤销导入时按动作 id 批量删。 */
    void deleteByExerciseIdIn(List<Long> exerciseIds);
}
```

- [ ] **Step 4: 写导入服务**

要求：

1. **整个导入一个事务**。876 条 + 2587 行任何一个失败就整批回滚——半导入的库比没导入更糟。
2. **幂等**：靠 `source_id` 上的部分唯一索引 + 「已存在就跳过」。**不要用 `INSERT OR REPLACE`**——那会静默改掉已有的行。
3. **`origin = IMPORT`**（`ChangeLog.Origin` 已有这个值）。导入的动作与手工录入的动作来源不同，撤销时要能分开。
4. 返回一个结果对象：`inserted` / `skipped` / `musclesInserted`，供任务调用方报告。
5. 肌群查找走 `MuscleGroupRepo.findByNameEn`，**查不到就抛异常并说清是哪条动作的哪块肌肉**——静默跳过会让那个动作少一块标注而无人知晓。

- [ ] **Step 5: 让导入把 join 行走审计**

**这一步是 Task 1 那条 `undoing_every_log_row_returns_the_database_to_its_original_state` 去 `@Disabled` 的地方。**

对每一条写入的 `exercise_muscle` 行，用**真实的签名**记录（`origin` 是字符串，`chatSessionId` 传 `null`）：

```java
changeLog.recordInsert(
        "exercise_muscle",
        joinRowId,
        Map.of("exercise_id", exId, "muscle_group_id", muscleId, "role", role),
        "import",
        null);
```

`after` 的键必须与表的真实列一致（`exercise_id` / `muscle_group_id` / `role`）——`write` 会拿 `writableColumns` 逐个校验，写错键名会抛 `IllegalArgumentException`。

> **注意**：876 条 × 平均 3 块肌肉 ≈ 2587 条日志行。这是**故意的**——它让「这次导入动了什么」答得出来。如果将来嫌日志太胖，那是另一个决定，不要现在偷偷跳过。
>
> 另外注意「撤销导入」**不走**这些日志（Task 5 是按前缀删除的专用操作）。两者都保留：日志供追溯，专用操作供撤销。

- [ ] **Step 6: 跑测试**

Run: `cd backend && mvn -B clean test`

Expected: 全绿。Task 1 里那条 `undoing_every_log_row_returns_the_database_to_its_original_state` 现在**通过**（记得去掉它的 `@Disabled`）。

- [ ] **Step 7: 提交**

（提交信息说明幂等、事务、为什么日志行数是 2587 且不嫌它多。）

---

## Task 5: 撤销整个导入（按 `source_id` 前缀删除）

**用户拍板：导入的撤销不走逐行日志回放，而是一个意图明确的专用操作。**

理由：2596 次 undo 调用不是「撤销导入」，那是「撤销 2596 个东西」。而按前缀删除是**一次操作**，且它天然幂等。

**Files:**
- Modify: `backend/src/main/java/com/change/service/ExerciseImportService.java`
- Test: `backend/src/test/java/com/change/service/ExerciseImportServiceTest.java`

- [ ] **Step 1: 写失败的测试**

要求覆盖：

1. 导入后调 `unimport()`，`exercise` 里 `source_id LIKE 'free-exercise-db:%'` 的行为 **0**
2. 该动作的 `exercise_muscle` 行**全部被级联删掉**（靠 `ON DELETE CASCADE`，但要实测确认 `foreign_keys=true` 真的生效——计划 1 踩过 `PRAGMA foreign_keys = 0` 的坑）
3. **手工录入的动作一条不少**
4. **再导入一次能恢复全部 876 / 2587 行**（撤销是可逆的）
5. 在**没有导入过**的库上调 `unimport()` 不报错、返回 0（幂等）

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B -q -Dtest=ExerciseImportServiceTest test`

- [ ] **Step 3: 实现**

要点：

1. **一个事务**。
2. 先查出要删的 id 列表（`source_id LIKE 'free-exercise-db:%'`），**再**删 `exercise`。顺序不能反——删完就查不到了。
3. **记录一条日志**：`origin="import"`、`action=DELETE`（用 Task 1 Step 6 加的 `recordDelete`），`before` 里放一条汇总（删了多少动作、多少肌群标注、源前缀是什么）。这条日志**不是单行快照而是汇总**，所以它不该被 `undo` 回放——Task 1 Step 6 已经让 `undo` 显式拒绝 `action='delete'`，这条正好被那道守卫挡住。它的用途是**可追溯的证据**：用户将来问「我那 876 个动作哪去了」，库里能查到。
4. **不要删 `change_log`**。日志是历史，撤销不是抹除历史。

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn -B clean test`

- [ ] **Step 5: 提交**

---

## Task 6: `ExerciseRepo` 补验证 + 修 `archived` 过滤的不一致

**查出来的真 bug**：`findByArchivedFalse` 的注释写着「归档的动作…**不该出现在候选里**」，但搜索方法 `findByNameZhContainingOrNameEnContainingIgnoreCase` **没有 `archived` 过滤**——归档的动作会从搜索里冒出来。

两份 repo 一共 4 个查询方法、零测试，所以这个不一致一直没人发现。

**Files:**
- Modify: `backend/src/main/java/com/change/repo/ExerciseRepo.java`
- Test: `backend/src/test/java/com/change/repo/ExerciseRepoTest.java`

- [ ] **Step 1: 写失败的测试**

必须覆盖：

1. `findByArchivedFalse()` 返回未归档的、**不返回**已归档的
2. 搜索：中文命中、英文命中、**大小写不敏感**
3. **搜索必须排除已归档的** ← 这条现在会失败
4. 搜索：`nameZh` 为 null 的动作不会让查询出错
5. 搜索：空字符串的行为被明确钉住（返回全部，还是返回空？**选定一种并写进测试**）

- [ ] **Step 2: 跑测试，确认第 3 条失败**

- [ ] **Step 3: 改签名**

```java
    /**
     * 中英文混合搜索。
     *
     * <p>nameZh 用 Containing（区分大小写对中文无意义），nameEn 用 IgnoreCase。
     *
     * <p><b>必须排除已归档的</b>：归档的语义是「不再出现在候选里但仍要能被旧记录
     * 解析」。原来这个方法漏了 archived 过滤，归档的动作会从搜索里冒出来——
     * 而 findByArchivedFalse 的注释里写的就是这条规则。零测试让这个不一致
     * 一直没被发现。
     */
    List<Exercise> findByArchivedFalseAndNameZhContainingOrArchivedFalseAndNameEnContainingIgnoreCase(
            String zh, String en);
```

> 名字长，但 Spring Data 的方法名派生就是靠它。**不要**为它换 `@Query`——本项目的一条约定是「能用方法名派生就不用 JPQL」（计划 1 的 `ExerciseRepo` 注释里写了理由）。

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn -B clean test`

- [ ] **Step 5: 提交**

---

## Task 7: 中文名的批量产出（可重入、断点续）

**中文名必须自己产出**——三个数据集都只有英文名。设计文档「决定 6」定的方案是：智能体批量翻译 + 拿 `hasaneyldrm/exercises-dataset` 的中文说明按动作名匹配做交叉校验。

**这一步刻意排在导入之后**：导入不依赖它（`name_zh` 可空），所以它失败不会挡住计划 3。

**Files:**
- Create: `backend/src/main/java/com/change/service/ExerciseNameZhService.java`
- Test: `backend/src/test/java/com/change/service/ExerciseNameZhServiceTest.java`

- [ ] **Step 1: 先做一件不加模型依赖的事——把 hasaneyldrm 的中文名抓下来**

`hasaneyldrm/exercises-dataset` 的 `instructions.zh` 里有中文动作名。走镜像抓（本机 GitHub 直连被掐）：

先探测实际路径（仓库结构可能变），确认后再定抓取脚本。**这一步的产出是一份「英文名 → 中文名」的对照 JSON，放进 `seed/`，不入库（它只是校验素材）。**

- [ ] **Step 2: 写失败的测试（不调模型）**

要求：

1. 给定一份「英文名 → 中文名」的映射，能批量写进 `exercise.name_zh`
2. **`source_id IS NULL` 的手工动作不被改**
3. **已经是中文名的行不被覆盖**（幂等，可重跑）
4. **断点续**：只处理 `name_zh IS NULL` 的行——跑到一半崩掉，再跑接着来
5. 中文名**不能与已有动作重复**（`name_zh` 上没唯一索引，但重复会让人选错；测试要挡住）

- [ ] **Step 3: 实现「写中文名」这件纯机械的事**

**这一步不调模型**——它只接受一份现成的映射。模型调用是 Step 4 的事，分开是为了让 Step 3 可测。

- [ ] **Step 4: 产出映射（这一步会花模型调用）**

两条路，**先做更便宜的**：

- **先交叉校验**：拿 Step 1 抓到的 hasaneyldrm 中文名，按英文名精确匹配 free-exercise-db 的 876 条，看命中多少。命中率要在报告里给出。
- **剩下没命中的再调模型翻译**。逐批（比如 50 条一批）产出，每批落盘到 `seed/name-zh-batch-N.json`，**这样崩了不用从头再来**。

**验收**：报告 876 条里各有多少来自交叉校验、多少来自模型、多少仍是 null。**允许有剩余 null**，但要给出数量与原因。

- [ ] **Step 5: 跑测试 + 验收**

Run: `cd backend && mvn -B clean test`

- [ ] **Step 6: 提交**

---

## Task 8: 端到端验收与文档

- [ ] **Step 1: 干净库上跑一遍全链路**

```powershell
cd E:\idea\program\change\backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
# 用一个临时数据目录，不碰用户的真实库
$env:CHANGE_DATA_DIR = "$env:TEMP\change-import-e2e"
Remove-Item -Recurse -Force $env:CHANGE_DATA_DIR -ErrorAction SilentlyContinue
mvn -B -q -DskipTests package
java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

**逐条核对**：

| # | 要观察的 | 期望 |
|---|---|---|
| 1 | 启动日志里有没有导入 | 有，且报告 876 / 2587 |
| 2 | `SELECT COUNT(*) FROM exercise` | 876 |
| 3 | `SELECT COUNT(*) FROM exercise_muscle` | 2587 |
| 4 | `SELECT COUNT(*) FROM muscle_group` | 17 |
| 5 | 9 条重叠动作之一（`Barbell Step Ups`）的 quadriceps 标注 | 恰好 1 条，`role='primary'` |
| 6 | `SELECT COUNT(*) FROM change_log WHERE origin='IMPORT'` | ≈ 876 + 2587 + 1 |
| 7 | **再启动一次**（库已存在） | 不重复导入，行数不变 |
| 8 | 触发 `unimport()` 之后再查 | `exercise` 只剩手工动作，肌群标注全没 |
| 9 | 再导入一次 | 恢复 876 / 2587 |

- [ ] **Step 2: 把结果记进总览**

在 `docs/superpowers/plans/2026-10-14-change-phase1-overview.md` 里补一节，写清实际数字、`region_ids` 的口径、以及「撤销导入」是一个专用操作而不是日志回放。

- [ ] **Step 3: 提交**

---

## 完成标准

- [ ] `cd backend && mvn -B clean test` 全绿，**报告实际条数**（计划 1 结束时是 125，本计划会显著增加）
- [ ] 干净库上导入产出 **876 个动作 / 2587 条肌群标注 / 17 块肌群**
- [ ] **重跑导入行数不变**（幂等实测）
- [ ] **9 条重叠动作各只有一条标注且 `role='primary'`**（实测抽查至少一条）
- [ ] `unimport()` 之后手工录入的动作一条不少；再导入能恢复
- [ ] `exercise_muscle` 在 `ChangeLogService` 的 `ALLOWED_TABLES` 里
- [ ] `ExerciseRepo` 的搜索**排除**已归档动作（含测试）
- [ ] `region_ids` 的每个值都在 `MuscleRegion` 里声明过（含测试）
- [ ] `exercises.json` 的 sha256 与 1,005,327 字节在加载时被校验
- [ ] `LICENSE`（Unlicense）随包存在
- [ ] `git status --short` 无输出

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| **GitHub 直连被掐** | `raw.githubusercontent.com` 与 `cdn.jsdelivr.net` 实测被重置 | 走 `fastly.jsdelivr.net`（实测 319ms）；数据集 vendor 进仓库后**不再需要网络** |
| 数据集换了版本 | 11 个字段的分布是我在 1,005,327 字节那一版上实测的 | 加载时校验 sha256 与条数；不符就显式报错。876 / 2587 / 292 / 9 / 87 / 77 这些数字都有测试钉住 |
| 9 条重叠处理错 | 朴素两边都写会撞唯一索引**整批回滚** | 「primary 赢」+ 两条测试（整体唯一性 + 抽一条钉死规则） |
| 导入不是幂等 | 重跑会重复插入或撞唯一索引 | 整个导入一个事务 + 靠 `source_id` 部分唯一索引跳过已存在的 |
| 撤销留下孤儿 join 行 | SQLite 的 `PRAGMA foreign_keys` 默认是 0（计划 1 踩过） | JDBC URL 已带 `&foreign_keys=true`；Task 5 的测试**实测**级联生效 |
| 中文名靠模型 | 花调用、可能中断 | 排在导入之后（不挡路）+ 分批落盘可续跑 + **允许剩余 null**，给出数量与原因 |
| 日志变得很胖 | 2587 条 join 行日志 | 这是**故意的**（让撤销能走通用回放）。真嫌胖是另一个决定，不要现在偷偷跳过 |

## 非目标（本计划不做）

- **不下载那 1746 张姿势图**——安装包体积优先；需要时凭 `source_id` 能反查
- **不做 2D 人体图**——那是计划 6；本计划只定义 `region_ids` 的词汇
- **不导入 `food` / 食物库**——那是计划 4
- **不做「建议周容量」的界面**——本计划只填数值，计划 3 才用它
- **不翻译 `instructions`**——只产出 `name_zh`
- **不加 `level` / `force` 列**——第一阶段用不上，且 dataset 是 vendor 的，随时能反查

## 下一步

本计划完成后进入**计划 3：训练记录**——第一个能真正用来记一次训练的东西，也是「本周容量」那块界面第一次有数据可算。
