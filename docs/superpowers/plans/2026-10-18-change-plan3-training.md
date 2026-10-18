# 计划 3：训练记录（后端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 后端起得来就能记一次完整训练（训练 → 动作 → 组，一个事务且可追溯），能带出某动作「上次练了多重」，能算出「本周每个肌群折算多少组」。

**Architecture:** 写路径走 `WorkoutService.recordSession(...)`——一个 `@Transactional` 方法，逐层插入 `workout_session` / `session_exercise` / `workout_set`，每插入一行就往 `change_log` 记一条（`origin='user'`），与动作库导入（计划 2）同一套审计思路。读路径是三条互不依赖的查询：训练列表/详情、某动作的上次表现、本周容量。容量一律走既有的 `VolumeCalculator`（主肌群 +1、次肌群 +0.5），周界按自然周（周一起算）。**只做后端**：前端「今日 / 训练记录」页面另起一份计划。

**Tech Stack:** JDK 25 · Spring Boot 3.5.16 · Spring Data JPA + JdbcTemplate · SQLite + Flyway · JUnit 5。

> 本计划**不含前端**。`frontend/src/views/TodayView.vue` 的占位文案（「计划 3 会在这里放本周容量」）留给下一份计划处理。

---

## 前置事实（已实测/已存在，不需要重新验证）

写计划前把会用到的既有事实核过一遍，避免计划自己重述失真（计划 2 里这类错误出现过 6 次）。

| 事实 | 值 / 位置 |
|---|---|
| 三层表 | `workout_session` / `session_exercise` / `workout_set`，V2 建好；**本计划不需要新迁移** |
| 实体 | `com.change.domain.WorkoutSession` / `SessionExercise` / `WorkoutSet` 已存在 |
| `WorkoutSession` 的坑 | `sessionDate` 是 `LocalDate` 且带 `@JdbcTypeCode(SqlTypes.LOCAL_DATE)`；`startedAt`/`endedAt` 带 `LOCAL_DATE_TIME`。**加这些注解不是装饰**——xerial 驱动默认把 java.time 写成纪元毫秒，`session_date` 有 GLOB CHECK 会响亮失败，`started_at` **没有 CHECK 会静默存成数字串**。照抄实体上的注解，不要自己重写映射 |
| `ChangeLogService` 的真实签名 | `recordInsert(String table, long targetId, Map<String,Object> after, String origin, Long chatSessionId)`；`recordUpdate` / `recordDelete` 同理。**没有 `Origin` 枚举，`origin` 是字符串** |
| `ALLOWED_TABLES` | 已含 `workout_session` / `session_exercise` / `workout_set`，无需改动 |
| `ALLOWED_ORIGINS` | `user` / `agent` / `import`。本计划的写路径用 **`"user"`** |
| `after` 快照的键 | 必须是该表真实存在、且不是主键的列；`write()` 会逐个校验。三张表的可写列见各任务 |
| **快照里不要放 `java.time` 对象** | `ChangeLogService` 用的是裸 `new ObjectMapper()`（**没注册 JavaTimeModule**），序列化 `LocalDate` / `LocalDateTime` 会抛 `InvalidDefinitionException`。日期与时间戳一律 **`.toString()`** 成字符串再放进快照（列本来就是 TEXT，回退时 `UPDATE ... SET session_date = ?` 绑字符串完全正确） |
| **布尔值放进快照用 0/1 整数** | 与计划 2 的 `is_compound` 一致（`x ? 1 : 0`）。别放 Java `boolean`，回退时的类型绑定不确定 |
| `VolumeCalculator` | `accumulate(List<Entry>) -> Map<Long,Double>`（**0 组的肌群不建键**，缺键 = 0 组，用 `getOrDefault(id, 0.0)` 读）；`classify(double sets, Integer min, Integer max) -> Status`；`Status` = `NOT_CONFIGURED / UNTRAINED / BELOW / IN_RANGE / ABOVE` |
| 容量口径 | 每记一组：该动作**主要肌群 +1、次要肌群 +0.5**；**自然周、周一起算**（设计文档决定 5） |
| `MuscleGroup` | 字段 `id / nameZh / nameEn / bodyPart / regionIds / weeklySetsMin / weeklySetsMax`；`weeklySetsMin/Max` 可为 null（`neck` 就是 null） |
| 既有查询方法 | `WorkoutSessionRepo.findBySessionDateBetweenOrderBySessionDateDesc` / `findTop5ByOrderBySessionDateDesc`；`ExerciseRepo.findByArchivedFalse` / `findByArchivedFalseAndNameZhContainingOrArchivedFalseAndNameEnContainingIgnoreCase`（计划 2 修过 `archived` 过滤）；`ExerciseMuscleRepo.findByExerciseId` / `deleteByExerciseIdIn` |
| 环境 | JDK `C:\Users\alpaca\.jdks\openjdk-25.0.2`；Maven `E:\maven\apache-maven-3.9.4-bin\apache-maven-3.9.4\bin\mvn.cmd`。每个新 shell 先设 `JAVA_HOME` 与 `Path` |
| 测试库 | `@SpringBootTest` 共用 `target/data/change.db`（`src/test/resources/application.properties`）；`@Transactional` 的测试自我回滚；需要提交/观察真实的测试用**独立库文件**（见 `ChangeLogServiceUndoTransactionTest` 的做法） |
| 当前测试数 | `mvn -B clean test` → **190 条、0 失败、1 skipped** |
| 顺手可修的小事 | `com.change.domain.MuscleGroup` 类注释写「库里的 17 行来自 `V8__muscle_group_seed.sql`」，实际是 **V7**（计划 2 把两个迁移编号对调过）。改这一句注释即可 |

---

## 文件结构

**新建**

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/change/repo/SessionExerciseRepo.java` | 一次训练里的动作，按 `orderIndex` 取 |
| `backend/src/main/java/com/change/repo/WorkoutSetRepo.java` | 一个动作下的组，按 `setIndex` 取 |
| `backend/src/main/java/com/change/service/WorkoutService.java` | 写一次训练（事务 + 审计）、读训练列表/详情 |
| `backend/src/main/java/com/change/service/LastPerformanceService.java` | 「某动作上次练了多重」 |
| `backend/src/main/java/com/change/service/WeeklyVolumeService.java` | 「本周每个肌群折算多少组」+ 与目标区间比较 |
| `backend/src/main/java/com/change/service/ExerciseQueryService.java` | 动作选择器：按肌群 / 关键词 / 分类筛动作 |
| `backend/src/main/java/com/change/web/TrainingController.java` | `POST /api/sessions`、`GET /api/sessions/recent`、`GET /api/sessions/{id}`、`GET /api/exercises/{id}/last-performance` |
| `backend/src/main/java/com/change/web/VolumeController.java` | `GET /api/volume/weekly` |
| `backend/src/main/java/com/change/web/ApiErrors.java` | `@RestControllerAdvice`：把 `IllegalArgumentException` 映射成 400（而不是默认的 500） |

**修改**

| 文件 | 改什么 |
|---|---|
| `backend/src/main/java/com/change/web/ExerciseController.java` | 加 `GET /api/exercises`（动作选择器） |
| `backend/src/main/java/com/change/domain/MuscleGroup.java` | 注释里 `V8__muscle_group_seed.sql` → `V7__muscle_group_seed.sql` |

**测试**

| 文件 |
|---|
| `backend/src/test/java/com/change/repo/SessionExerciseRepoTest.java` |
| `backend/src/test/java/com/change/service/WorkoutServiceTest.java` |
| `backend/src/test/java/com/change/service/LastPerformanceServiceTest.java` |
| `backend/src/test/java/com/change/service/WeeklyVolumeServiceTest.java` |
| `backend/src/test/java/com/change/service/ExerciseQueryServiceTest.java` |
| `backend/src/test/java/com/change/web/TrainingControllerTest.java` |
| `backend/src/test/java/com/change/web/VolumeControllerTest.java` |

---

## Task 1: 两个仓储（动作与组，按序取）

**Files:**
- Create: `backend/src/main/java/com/change/repo/SessionExerciseRepo.java`
- Create: `backend/src/main/java/com/change/repo/WorkoutSetRepo.java`
- Test: `backend/src/test/java/com/change/repo/SessionExerciseRepoTest.java`

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/change/repo/SessionExerciseRepoTest.java`：

```java
package com.change.repo;

import com.change.domain.SessionExercise;
import com.change.domain.WorkoutSession;
import com.change.domain.WorkoutSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个派生查询的守卫：它们必须**按 orderIndex / setIndex 升序**返回。
 *
 * <p>为什么单独测这个：这两个查询的调用方（训练详情、上次重量带出）都依赖顺序——
 * 反了的话动作在界面上会乱序、组与组之间会错位，而查询本身不会报错。
 * 插入时故意打乱顺序，断言读回来的顺序是升序（不是插入序）。
 *
 * <p>@Transactional：跑完回滚，不给共享库留行。
 */
@SpringBootTest
@Transactional
class SessionExerciseRepoTest {

    @Autowired SessionExerciseRepo sessionExercises;
    @Autowired WorkoutSetRepo sets;
    @Autowired JdbcTemplate jdbc;

    private long newSession() {
        jdbc.update("INSERT INTO workout_session(session_date) VALUES (?)", LocalDate.now().toString());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long newSessionExercise(long sessionId, long exerciseId, int orderIndex) {
        jdbc.update("INSERT INTO session_exercise(session_id, exercise_id, order_index) VALUES (?,?,?)",
                sessionId, exerciseId, orderIndex);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Test
    void exercises_come_back_in_order_index_order_not_insertion_order() {
        long sessionId = newSession();
        long exA = newExercise("ZZ Repo SE A");
        long exB = newExercise("ZZ Repo SE B");

        long second = newSessionExercise(sessionId, exB, 1);
        long first = newSessionExercise(sessionId, exA, 0);

        assertThat(sessionExercises.findBySessionIdOrderByOrderIndexAsc(sessionId))
                .extracting(se -> se.id)
                .containsExactly(first, second);
    }

    @Test
    void sets_come_back_in_set_index_order_not_insertion_order() {
        long sessionExerciseId = newSessionExercise(newSession(), newExercise("ZZ Repo SE C"), 0);

        jdbc.update("INSERT INTO workout_set(session_exercise_id, set_index, set_type) VALUES (?,?,?)",
                sessionExerciseId, 2, "normal");
        long last = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO workout_set(session_exercise_id, set_index, set_type) VALUES (?,?,?)",
                sessionExerciseId, 0, "normal");
        long first = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        assertThat(sets.findBySessionExerciseIdOrderBySetIndexAsc(sessionExerciseId))
                .extracting(ws -> ws.id)
                .containsExactly(first, last);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -q -Dtest=SessionExerciseRepoTest test
```

Expected: 编译失败 —— `cannot find symbol: class SessionExerciseRepo` / `WorkoutSetRepo`。

- [ ] **Step 3: 写两个 Repo**

`backend/src/main/java/com/change/repo/SessionExerciseRepo.java`：

```java
package com.change.repo;

import com.change.domain.SessionExercise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 一次训练里的动作。 */
public interface SessionExerciseRepo extends JpaRepository<SessionExercise, Long> {

    /**
     * 一次训练里的动作，**按 orderIndex 升序**。顺序是这方法契约的一部分：
     * 详情页与「上次重量带出」都按它展示，乱了不会报错、只会错位。
     */
    List<SessionExercise> findBySessionIdOrderByOrderIndexAsc(Long sessionId);
}
```

`backend/src/main/java/com/change/repo/WorkoutSetRepo.java`：

```java
package com.change.repo;

import com.change.domain.WorkoutSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 一个动作下的组。 */
public interface WorkoutSetRepo extends JpaRepository<WorkoutSet, Long> {

    /** 一个动作下的组，**按 setIndex 升序**。理由同 {@link SessionExerciseRepo}。 */
    List<WorkoutSet> findBySessionExerciseIdOrderBySetIndexAsc(Long sessionExerciseId);
}
```

- [ ] **Step 4: 跑测试，确认通过**

```powershell
mvn -B -q -Dtest=SessionExerciseRepoTest test
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/repo/SessionExerciseRepo.java \
        backend/src/main/java/com/change/repo/WorkoutSetRepo.java \
        backend/src/test/java/com/change/repo/SessionExerciseRepoTest.java
git commit -m "feat(training): 动作与组的按序派生查询"
```

---

## Task 2: 写一次完整训练（一个事务 + 审计）

**Files:**
- Create: `backend/src/main/java/com/change/service/WorkoutService.java`
- Create: `backend/src/main/java/com/change/web/TrainingController.java`
- Create: `backend/src/main/java/com/change/web/ApiErrors.java`
- Test: `backend/src/test/java/com/change/service/WorkoutServiceTest.java`
- Test: `backend/src/test/java/com/change/web/TrainingControllerTest.java`

**要守住的两件事**：① 三层一次写完、任何一步失败整批回滚；② 每个快照里的日期/时间戳是**字符串**（`ChangeLogService` 的 `ObjectMapper` 没注册 `JavaTimeModule`，放 `LocalDate` 会抛异常——而且这条异常会发生在**记日志时**，把一次正常的训练记录整个弄失败）。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/change/service/WorkoutServiceTest.java`：

```java
package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写一次完整训练。
 *
 * <p>@Transactional：跑完回滚，不给共享库留行。服务自身也是 @Transactional，会加入这个测试事务，
 * 所以中途的插入在测试里都看得见、也一起回滚。
 */
@SpringBootTest
@Transactional
class WorkoutServiceTest {

    @Autowired WorkoutService workouts;
    @Autowired JdbcTemplate jdbc;

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private WorkoutService.SetInput set(double weight, int reps) {
        return new WorkoutService.SetInput("normal", weight, reps, null, false, null, null, true, null, null);
    }

    private WorkoutService.SessionInput oneSession(List<WorkoutService.ExerciseInput> exercises) {
        return new WorkoutService.SessionInput(LocalDate.of(2026, 10, 18), "胸 + 三头",
                "睡得好", "测试用", LocalDateTime.of(2026, 10, 18, 19, 30), null, exercises);
    }

    @Test
    void records_a_full_session_and_logs_every_row() {
        long bench = newExercise("ZZ Workout Bench");
        long fly = newExercise("ZZ Workout Fly");

        WorkoutService.Result r = workouts.recordSession(oneSession(List.of(
                new WorkoutService.ExerciseInput(bench, "主项", null, List.of(set(60, 8), set(62.5, 6))),
                new WorkoutService.ExerciseInput(fly, null, "A", List.of(set(12, 12))))));

        assertThat(r.exercises()).isEqualTo(2);
        assertThat(r.sets()).isEqualTo(3);

        // 三层都落库了，且顺序与传入一致
        assertThat(jdbc.queryForObject(
                "SELECT session_date FROM workout_session WHERE id = ?", String.class, r.sessionId()))
                .isEqualTo("2026-10-18");
        assertThat(jdbc.queryForList(
                "SELECT exercise_id FROM session_exercise WHERE session_id = ? ORDER BY order_index",
                Long.class, r.sessionId()))
                .containsExactly(bench, fly);
        long benchSe = jdbc.queryForObject(
                "SELECT id FROM session_exercise WHERE session_id = ? AND order_index = 0",
                Long.class, r.sessionId());
        assertThat(jdbc.queryForList(
                "SELECT set_index FROM workout_set WHERE session_exercise_id = ? ORDER BY set_index",
                Integer.class, benchSe))
                .containsExactly(0, 1);

        // 每一行都进了审计：1 训练 + 2 动作 + 3 组 = 6 条。
        // 按**本次写入的目标 id** 收口，而不是 `COUNT(*) WHERE origin='user'`——
        // 共享库里可能有别的测试提交过的 user 日志（ChangeLogJoinTableTest 就不回滚），
        // 用全局计数会让这条断言依赖库的历史。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE target_table = 'workout_session' AND target_id = ?",
                Integer.class, r.sessionId()))
                .as("训练本身 1 条").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE target_table = 'session_exercise' AND target_id IN "
                        + "(SELECT id FROM session_exercise WHERE session_id = ?)",
                Integer.class, r.sessionId()))
                .as("2 个动作各 1 条").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE target_table = 'workout_set' AND target_id IN "
                        + "(SELECT ws.id FROM workout_set ws JOIN session_exercise se ON se.id = ws.session_exercise_id "
                        + "WHERE se.session_id = ?)",
                Integer.class, r.sessionId()))
                .as("3 组各 1 条").isEqualTo(3);
    }

    @Test
    void the_snapshot_stores_the_date_as_a_string_not_a_java_time_object() {
        // 这条守的是 ChangeLogService 的 ObjectMapper 没注册 JavaTimeModule 这个坑：
        // 一旦快照里放了 LocalDate，recordInsert 会在**序列化时**抛异常，把整次记录弄失败。
        long bench = newExercise("ZZ Workout Snapshot");
        WorkoutService.Result r = workouts.recordSession(oneSession(List.of(
                new WorkoutService.ExerciseInput(bench, null, null, List.of(set(60, 8))))));

        String afterJson = jdbc.queryForObject(
                "SELECT after_json FROM change_log WHERE target_table = 'workout_session' AND target_id = ?",
                String.class, r.sessionId());
        assertThat(afterJson).contains("\"session_date\":\"2026-10-18\"");
        assertThat(afterJson).contains("\"started_at\":\"2026-10-18T19:30\"");
    }

    @Test
    void refuses_an_empty_session_and_an_exercise_without_sets() {
        long bench = newExercise("ZZ Workout Invalid");

        assertThatThrownBy(() -> workouts.recordSession(oneSession(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("动作");

        assertThatThrownBy(() -> workouts.recordSession(oneSession(List.of(
                new WorkoutService.ExerciseInput(bench, null, null, List.of())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一组");
    }
}
```

> `started_at` 落库时是 `'2026-10-18T19:30'`（秒为 0 时 `LocalDateTime.toString()` 省略 `:00`，16 字符）——这是设计文档十四节实测过的行为，不是笔误。

`backend/src/test/java/com/change/web/TrainingControllerTest.java`：

```java
package com.change.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 训练端点。独立库文件：这些端点是**真的提交**的（不受测试事务保护），
 * 用共享库会污染别的测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/training-controller.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class TrainingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /**
     * 每个测试前把**训练域**清干净。库文件跨运行持久，而 `session_exercise.exercise_id` 是指向
     * `exercise(id)` 的**裸外键（没有 ON DELETE CASCADE）**——不清干净就删动作会直接撞 FK。
     * 顺序必须是子表在前。
     */
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM workout_set");
        jdbc.update("DELETE FROM session_exercise");
        jdbc.update("DELETE FROM workout_session");
    }

    private long newExercise(String nameEn) {
        jdbc.update("DELETE FROM exercise WHERE name_en = ?", nameEn);
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Test
    void posts_a_session_and_returns_its_counts() throws Exception {
        long bench = newExercise("ZZ Ctrl Bench");
        String body = """
                {
                  "sessionDate": "2026-10-18",
                  "title": "胸日",
                  "startedAt": "2026-10-18T19:30",
                  "exercises": [
                    { "exerciseId": %d, "sets": [
                        { "weight": 60, "repetitions": 8, "completed": true },
                        { "weight": 62.5, "repetitions": 6, "completed": true } ] }
                  ]
                }
                """.formatted(bench);

        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exercises").value(1))
                .andExpect(jsonPath("$.sets").value(2))
                .andExpect(jsonPath("$.sessionId").isNumber());
    }

    @Test
    void a_bad_request_is_a_400_with_a_readable_message_not_a_500() throws Exception {
        String body = """
                { "sessionDate": "2026-10-18", "exercises": [] }
                """;
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```powershell
mvn -B -q -Dtest=WorkoutServiceTest test
```

Expected: 编译失败 —— `cannot find symbol: class WorkoutService`。

- [ ] **Step 3: 写 `WorkoutService`**

`backend/src/main/java/com/change/service/WorkoutService.java`：

```java
package com.change.service;

import com.change.domain.SessionExercise;
import com.change.domain.WorkoutSession;
import com.change.domain.WorkoutSet;
import com.change.repo.SessionExerciseRepo;
import com.change.repo.WorkoutSetRepo;
import com.change.repo.WorkoutSessionRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写一次训练。
 *
 * <p><b>三层一个事务。</b>训练 → 动作 → 组任何一步失败就整批回滚：半写入的一次训练比没记更糟
 * （用户以为记好了，其实少了几个动作或几组，而且他不会知道少了哪个）。
 *
 * <p><b>每一行都进审计（{@code origin="user"}）。</b>与动作库导入（计划 2）同一套思路：
 * 「这次改动动了什么」要答得出来，也才回退得动。父行与子行**都**记——只记父行的话，
 * 回退父行会级联删掉子行，而日志里没有东西让它们回来（见 {@link ChangeLogService} 的类注释）。
 *
 * <p><b>快照里的日期/时间戳一律 {@code toString()} 成字符串。</b>{@link ChangeLogService} 用的是
 * 裸 {@code new ObjectMapper()}（没注册 JavaTimeModule），把 {@code LocalDate} 放进去会在
 * 序列化时抛异常——那会把一次正常的训练记录整个弄失败。布尔值同理用 0/1 整数
 * （与计划 2 的 {@code is_compound} 一致，回退时的类型绑定也更确定）。
 */
@Service
public class WorkoutService {

    private static final String ORIGIN = "user";

    /** 一组要写的数据。除组类型外都允许为 null（「还没记」是一个真实状态）。 */
    public record SetInput(String setType, Double weight, Integer repetitions, Integer durationSeconds,
                           boolean perSide, Double rir, Integer restSeconds, boolean completed,
                           Double targetWeight, Integer targetReps) {}

    /** 训练里的一个动作。{@code sets} 至少一条。 */
    public record ExerciseInput(Long exerciseId, String note, String supersetGroup, List<SetInput> sets) {}

    /** 一次训练。{@code exercises} 至少一个。 */
    public record SessionInput(LocalDate sessionDate, String title, String perceivedState, String note,
                               LocalDateTime startedAt, LocalDateTime endedAt,
                               List<ExerciseInput> exercises) {}

    /** 写入结果。 */
    public record Result(long sessionId, int exercises, int sets) {}

    private final WorkoutSessionRepo sessions;
    private final SessionExerciseRepo sessionExercises;
    private final WorkoutSetRepo sets;
    private final ChangeLogService changeLog;

    public WorkoutService(WorkoutSessionRepo sessions, SessionExerciseRepo sessionExercises,
                          WorkoutSetRepo sets, ChangeLogService changeLog) {
        this.sessions = sessions;
        this.sessionExercises = sessionExercises;
        this.sets = sets;
        this.changeLog = changeLog;
    }

    /** 记一次完整训练。 */
    @Transactional
    public Result recordSession(SessionInput input) {
        requireValid(input);

        WorkoutSession session = new WorkoutSession();
        session.sessionDate = input.sessionDate();
        session.startedAt = input.startedAt();
        session.endedAt = input.endedAt();
        session.title = input.title();
        session.perceivedState = input.perceivedState();
        session.note = input.note();
        sessions.save(session);
        changeLog.recordInsert("workout_session", session.id, sessionSnapshot(session), ORIGIN, null);

        int exerciseCount = 0;
        int setCount = 0;
        for (int i = 0; i < input.exercises().size(); i++) {
            ExerciseInput ei = input.exercises().get(i);
            SessionExercise se = new SessionExercise();
            se.sessionId = session.id;
            se.exerciseId = ei.exerciseId();
            se.orderIndex = i;
            se.note = ei.note();
            se.supersetGroup = ei.supersetGroup();
            sessionExercises.save(se);
            changeLog.recordInsert("session_exercise", se.id, sessionExerciseSnapshot(se), ORIGIN, null);
            exerciseCount++;

            for (int j = 0; j < ei.sets().size(); j++) {
                WorkoutSet ws = toSet(se.id, j, ei.sets().get(j));
                sets.save(ws);
                changeLog.recordInsert("workout_set", ws.id, setSnapshot(ws), ORIGIN, null);
                setCount++;
            }
        }
        return new Result(session.id, exerciseCount, setCount);
    }

    private static WorkoutSet toSet(long sessionExerciseId, int setIndex, SetInput in) {
        WorkoutSet ws = new WorkoutSet();
        ws.sessionExerciseId = sessionExerciseId;
        ws.setIndex = setIndex;
        ws.setType = in.setType() == null ? "normal" : in.setType();
        ws.weight = in.weight();
        ws.repetitions = in.repetitions();
        ws.durationSeconds = in.durationSeconds();
        ws.perSide = in.perSide();
        ws.rir = in.rir();
        ws.restSeconds = in.restSeconds();
        ws.completed = in.completed();
        ws.targetWeight = in.targetWeight();
        ws.targetReps = in.targetReps();
        return ws;
    }

    private static void requireValid(SessionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("训练内容不能为空");
        }
        if (input.sessionDate() == null) {
            throw new IllegalArgumentException("训练日期不能为空");
        }
        if (input.exercises() == null || input.exercises().isEmpty()) {
            throw new IllegalArgumentException("一次训练至少要有 1 个动作");
        }
        for (ExerciseInput ei : input.exercises()) {
            if (ei == null || ei.exerciseId() == null) {
                throw new IllegalArgumentException("每个动作都必须带 exerciseId");
            }
            if (ei.sets() == null || ei.sets().isEmpty()) {
                throw new IllegalArgumentException("动作 " + ei.exerciseId() + " 至少要有一组");
            }
        }
    }

    private static Map<String, Object> sessionSnapshot(WorkoutSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_date", s.sessionDate.toString());
        m.put("started_at", s.startedAt == null ? null : s.startedAt.toString());
        m.put("ended_at", s.endedAt == null ? null : s.endedAt.toString());
        m.put("title", s.title);
        m.put("perceived_state", s.perceivedState);
        m.put("note", s.note);
        return m;
    }

    private static Map<String, Object> sessionExerciseSnapshot(SessionExercise se) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", se.sessionId);
        m.put("exercise_id", se.exerciseId);
        m.put("order_index", se.orderIndex);
        m.put("note", se.note);
        m.put("superset_group", se.supersetGroup);
        return m;
    }

    private static Map<String, Object> setSnapshot(WorkoutSet ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_exercise_id", ws.sessionExerciseId);
        m.put("set_index", ws.setIndex);
        m.put("set_type", ws.setType);
        m.put("weight", ws.weight);
        m.put("repetitions", ws.repetitions);
        m.put("duration_seconds", ws.durationSeconds);
        m.put("per_side", ws.perSide ? 1 : 0);
        m.put("rir", ws.rir);
        m.put("rest_seconds", ws.restSeconds);
        m.put("completed", ws.completed ? 1 : 0);
        m.put("target_weight", ws.targetWeight);
        m.put("target_reps", ws.targetReps);
        return m;
    }
}
```

- [ ] **Step 4: 写控制器与统一的 400 映射**

`backend/src/main/java/com/change/web/TrainingController.java`：

```java
package com.change.web;

import com.change.service.WorkoutService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 训练记录的写入口。读取端点在 Task 3 加。 */
@RestController
@RequestMapping("/api/sessions")
public class TrainingController {

    private final WorkoutService workouts;

    public TrainingController(WorkoutService workouts) {
        this.workouts = workouts;
    }

    /** 记一次完整训练。请求体见 {@link WorkoutService.SessionInput}。 */
    @PostMapping
    public WorkoutService.Result record(@RequestBody WorkoutService.SessionInput input) {
        return workouts.recordSession(input);
    }
}
```

`backend/src/main/java/com/change/web/ApiErrors.java`：

```java
package com.change.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把校验失败翻译成 400。
 *
 * <p>不加这一层的话，服务里抛的 {@link IllegalArgumentException}（日期为空、动作没有组……）
 * 会被 Spring 默认映射成 **500**——对调用方来说是「服务器坏了」，而实际是「你传的东西不对」。
 * 前端拿到 400 才知道该提示用户改输入。
 *
 * <p>只映射这一种异常：其余异常仍然按 500 暴露（它们的栈是我们需要看到的）。
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "请求不合法" : e.getMessage();
        return Map.of("error", message);
    }
}
```

- [ ] **Step 5: 跑测试，确认通过**

```powershell
mvn -B -q -Dtest='WorkoutServiceTest,TrainingControllerTest' test
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`（服务 3 条 + 控制器 2 条）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/change/service/WorkoutService.java \
        backend/src/main/java/com/change/web/TrainingController.java \
        backend/src/main/java/com/change/web/ApiErrors.java \
        backend/src/test/java/com/change/service/WorkoutServiceTest.java \
        backend/src/test/java/com/change/web/TrainingControllerTest.java
git commit -m "feat(training): 写一次完整训练（一个事务 + 逐行审计）"
```

---

## Task 3: 读训练（最近几次 / 详情）

**Files:**
- Modify: `backend/src/main/java/com/change/service/WorkoutService.java`
- Modify: `backend/src/main/java/com/change/web/TrainingController.java`
- Test: `backend/src/test/java/com/change/service/WorkoutServiceTest.java`
- Test: `backend/src/test/java/com/change/web/TrainingControllerTest.java`

- [ ] **Step 1: 给 `WorkoutServiceTest` 补两条测试**

```java
    @Test
    void the_session_detail_reads_back_exercises_in_order_with_their_sets() {
        long bench = newExercise("ZZ Workout Detail Bench");
        long fly = newExercise("ZZ Workout Detail Fly");
        WorkoutService.Result r = workouts.recordSession(oneSession(List.of(
                new WorkoutService.ExerciseInput(bench, "主项", null, List.of(set(60, 8), set(62.5, 6))),
                new WorkoutService.ExerciseInput(fly, null, "A", List.of(set(12, 12))))));

        WorkoutService.SessionDetail d = workouts.sessionDetail(r.sessionId()).orElseThrow();

        assertThat(d.sessionDate()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(d.title()).isEqualTo("胸 + 三头");
        assertThat(d.exercises()).extracting(WorkoutService.ExerciseDetail::exerciseId)
                .containsExactly(bench, fly);
        assertThat(d.exercises().get(0).sets()).extracting(WorkoutService.SetDetail::weight)
                .containsExactly(60.0, 62.5);
        assertThat(d.exercises().get(1).supersetGroup()).isEqualTo("A");
    }

    @Test
    void an_unknown_session_has_no_detail() {
        assertThat(workouts.sessionDetail(-1L)).isEmpty();
    }
```

- [ ] **Step 2: 跑测试，确认失败**

```powershell
mvn -B -q -Dtest=WorkoutServiceTest#the_session_detail_reads_back_exercises_in_order_with_their_sets test
```

Expected: 编译失败 —— `cannot find symbol: method sessionDetail(long)`。

- [ ] **Step 3: 给 `WorkoutService` 加读取**

在 `WorkoutService` 里加这些 record（与现有 DTO 放在一起）：

```java
    /** 训练列表里的一行。 */
    public record SessionSummary(long id, LocalDate sessionDate, String title, String perceivedState) {}

    /** 一组（读回用，带 id）。 */
    public record SetDetail(long id, int setIndex, String setType, Double weight, Integer repetitions,
                            Integer durationSeconds, boolean perSide, Double rir, Integer restSeconds,
                            boolean completed, Double targetWeight, Integer targetReps) {}

    /** 训练里的一个动作（读回用，带上动作名）。 */
    public record ExerciseDetail(long sessionExerciseId, long exerciseId, String nameZh, String nameEn,
                                 int orderIndex, String note, String supersetGroup, List<SetDetail> sets) {}

    /** 一次训练的完整内容。 */
    public record SessionDetail(long id, LocalDate sessionDate, String title, String perceivedState,
                                String note, LocalDateTime startedAt, LocalDateTime endedAt,
                                List<ExerciseDetail> exercises) {}
```

加依赖与读取方法（`ExerciseRepo` 用来取动作名；构造器多一个参数）：

```java
    private final ExerciseRepo exercises;

    // 构造器参数里加 ExerciseRepo exercises，并 this.exercises = exercises;
```

```java
    /** 最近 5 次训练，新的在前（首页用）。 */
    public List<SessionSummary> recentSessions() {
        return sessions.findTop5ByOrderBySessionDateDesc().stream()
                .map(s -> new SessionSummary(s.id, s.sessionDate, s.title, s.perceivedState))
                .toList();
    }

    /** 一次训练的完整内容；找不到返回 {@link Optional#empty()}（调用方翻译成 404）。 */
    public Optional<SessionDetail> sessionDetail(long sessionId) {
        Optional<WorkoutSession> found = sessions.findById(sessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorkoutSession s = found.get();
        List<SessionExercise> ses = sessionExercises.findBySessionIdOrderByOrderIndexAsc(sessionId);

        Map<Long, Exercise> exerciseById = exercises
                .findAllById(ses.stream().map(se -> se.exerciseId).toList()).stream()
                .collect(Collectors.toMap(e -> e.id, e -> e));

        List<ExerciseDetail> details = new ArrayList<>();
        for (SessionExercise se : ses) {
            Exercise e = exerciseById.get(se.exerciseId);
            List<SetDetail> setDetails = sets.findBySessionExerciseIdOrderBySetIndexAsc(se.id)
                    .stream().map(WorkoutService::toSetDetail).toList();
            details.add(new ExerciseDetail(se.id, se.exerciseId,
                    e == null ? null : e.nameZh, e == null ? null : e.nameEn,
                    se.orderIndex, se.note, se.supersetGroup, setDetails));
        }
        return Optional.of(new SessionDetail(s.id, s.sessionDate, s.title, s.perceivedState,
                s.note, s.startedAt, s.endedAt, details));
    }

    private static SetDetail toSetDetail(WorkoutSet ws) {
        return new SetDetail(ws.id, ws.setIndex, ws.setType, ws.weight, ws.repetitions,
                ws.durationSeconds, ws.perSide, ws.rir, ws.restSeconds, ws.completed,
                ws.targetWeight, ws.targetReps);
    }
```

需要的 import：`com.change.domain.Exercise`、`com.change.repo.ExerciseRepo`、`java.util.ArrayList`、`java.util.Optional`、`java.util.stream.Collectors`。

- [ ] **Step 4: 给 `TrainingController` 加两个端点**

```java
    /** 最近 5 次训练。 */
    @GetMapping("/recent")
    public List<WorkoutService.SessionSummary> recent() {
        return workouts.recentSessions();
    }

    /** 一次训练的详情；不存在时 404。 */
    @GetMapping("/{id}")
    public WorkoutService.SessionDetail detail(@PathVariable long id) {
        return workouts.sessionDetail(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "找不到这次训练: " + id));
    }
```

需要的 import：`org.springframework.web.bind.annotation.GetMapping`、`PathVariable`、`org.springframework.http.HttpStatus`、`org.springframework.web.server.ResponseStatusException`、`java.util.List`。

- [ ] **Step 5: 给 `TrainingControllerTest` 补 HTTP 层断言**

```java
    @Test
    void reads_a_session_back_through_the_api() throws Exception {
        long bench = newExercise("ZZ Ctrl Detail Bench");
        String body = """
                { "sessionDate": "2026-10-18", "title": "腿日",
                  "exercises": [ { "exerciseId": %d, "sets": [ { "weight": 100, "repetitions": 5, "completed": true } ] } ] }
                """.formatted(bench);

        String created = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int sessionId = com.jayway.jsonpath.JsonPath.read(created, "$.sessionId");

        mvc.perform(get("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("腿日"))
                .andExpect(jsonPath("$.exercises[0].exerciseId").value((int) bench))
                .andExpect(jsonPath("$.exercises[0].sets[0].weight").value(100.0));

        mvc.perform(get("/api/sessions/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber());

        mvc.perform(get("/api/sessions/999999"))
                .andExpect(status().isNotFound());
    }
```

> `com.jayway.jsonpath` 随 `spring-boot-starter-test` 一起来，不用加依赖。

  需要的 import：`import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;`

- [ ] **Step 6: 跑测试，确认通过**

```powershell
mvn -B -q -Dtest='WorkoutServiceTest,TrainingControllerTest' test
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/change/service/WorkoutService.java \
        backend/src/main/java/com/change/web/TrainingController.java \
        backend/src/test/java/com/change/service/WorkoutServiceTest.java \
        backend/src/test/java/com/change/web/TrainingControllerTest.java
git commit -m "feat(training): 读最近几次训练与一次训练的详情"
```

---

## Task 4: 上次重量带出

**Files:**
- Create: `backend/src/main/java/com/change/service/LastPerformanceService.java`
- Modify: `backend/src/main/java/com/change/web/ExerciseController.java`
- Test: `backend/src/test/java/com/change/service/LastPerformanceServiceTest.java`
- Test: `backend/src/test/java/com/change/web/ExerciseControllerTest.java`

设计文档流程①的硬约束：**「填重量时默认值取该动作上一次的重量」**。所以这里回答的是「这个动作上次那次训练、每个组各是多少」。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/change/service/LastPerformanceServiceTest.java`：

```java
package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 「上次这个动作练了多重」。 */
@SpringBootTest
@Transactional
class LastPerformanceServiceTest {

    @Autowired LastPerformanceService lastPerformance;
    @Autowired WorkoutService workouts;
    @Autowired JdbcTemplate jdbc;

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void record(LocalDate date, long exerciseId, double weight, int reps) {
        workouts.recordSession(new WorkoutService.SessionInput(date, null, null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null,
                        List.of(new WorkoutService.SetInput("normal", weight, reps, null, false,
                                null, null, true, null, null))))));
    }

    @Test
    void never_done_is_empty() {
        assertThat(lastPerformance.lastPerformance(newExercise("ZZ LP Never"))).isEmpty();
    }

    @Test
    void returns_the_most_recent_session_that_contained_the_exercise() {
        long ex = newExercise("ZZ LP Bench");
        record(LocalDate.of(2026, 10, 10), ex, 60.0, 8);
        record(LocalDate.of(2026, 10, 17), ex, 65.0, 5);

        LastPerformanceService.LastPerformance lp = lastPerformance.lastPerformance(ex).orElseThrow();

        assertThat(lp.sessionDate()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(lp.sets()).extracting(LastPerformanceService.SetSnapshot::weight).containsExactly(65.0);
        assertThat(lp.sets()).extracting(LastPerformanceService.SetSnapshot::repetitions).containsExactly(5);
    }

    @Test
    void another_exercise_does_not_leak_in() {
        long bench = newExercise("ZZ LP Bench2");
        long squat = newExercise("ZZ LP Squat2");
        record(LocalDate.of(2026, 10, 16), squat, 100.0, 5);
        record(LocalDate.of(2026, 10, 12), bench, 60.0, 8);

        assertThat(lastPerformance.lastPerformance(bench).orElseThrow().sessionDate())
                .isEqualTo(LocalDate.of(2026, 10, 12));
    }
}
```

给 `ExerciseControllerTest` 补一条 HTTP 断言（注入 `WorkoutService` 记一次，再取）。先把它的 `@BeforeEach clean()` **改成**下面这样——原来的版本只清导入的动作，而跨运行残留的 `session_exercise` 会让 `DELETE FROM exercise` 撞**裸外键**（`session_exercise.exercise_id` 没有 `ON DELETE CASCADE`）：

```java
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM workout_set");
        jdbc.update("DELETE FROM session_exercise");
        jdbc.update("DELETE FROM workout_session");
        jdbc.update("DELETE FROM exercise WHERE source_id IS NOT NULL");
        jdbc.update("DELETE FROM change_log WHERE origin = 'import'");
    }
```

要补的测试：

```java
    @Test
    void last_performance_is_204_when_never_done_and_200_after_a_session() throws Exception {
        jdbc.update("DELETE FROM exercise WHERE name_en = 'ZZ Ctrl LastPerf'");
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES ('ZZ Ctrl LastPerf', 'kg')");
        long ex = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        mvc.perform(get("/api/exercises/" + ex + "/last-performance"))
                .andExpect(status().isNoContent());

        workouts.recordSession(new WorkoutService.SessionInput(java.time.LocalDate.of(2026, 10, 18),
                null, null, null, null, null,
                java.util.List.of(new WorkoutService.ExerciseInput(ex, null, null,
                        java.util.List.of(new WorkoutService.SetInput("normal", 70.0, 5, null, false,
                                null, null, true, null, null))))));

        mvc.perform(get("/api/exercises/" + ex + "/last-performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionDate").value("2026-10-18"))
                .andExpect(jsonPath("$.sets[0].weight").value(70.0));
    }
```

  需要的 import：`import com.change.service.WorkoutService;`、`import org.springframework.beans.factory.annotation.Autowired;`（已有）、`import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;`、`import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;`、`jsonPath`。并加字段 `@Autowired WorkoutService workouts;`。

- [ ] **Step 2: 跑测试，确认失败**

```powershell
mvn -B -q -Dtest=LastPerformanceServiceTest test
```

Expected: 编译失败 —— `cannot find symbol: class LastPerformanceService`。

- [ ] **Step 3: 写 `LastPerformanceService`**

`backend/src/main/java/com/change/service/LastPerformanceService.java`：

```java
package com.change.service;

import com.change.domain.WorkoutSet;
import com.change.repo.WorkoutSetRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「这个动作上次练了多重、每次几次」——给「记一组」时带出默认值用。
 *
 * <p><b>为什么用 JdbcTemplate 而不是派生查询。</b>要问的是「**包含这个动作的、最近的那次训练**」，
 * 排序键（`workout_session.session_date`）在另一张表上，而这两张表之间没有 JPA 关联
 * （本项目一律用 Long 存外键 id）。派生方法名表达不了跨表排序，所以走一条 SQL；
 * 组本身仍用 {@link WorkoutSetRepo} 的派生查询取（那部分能派生就派生）。
 */
@Service
public class LastPerformanceService {

    /** 供预填的一组：只有「记一组」需要的字段。 */
    public record SetSnapshot(String setType, Double weight, Integer repetitions,
                              Integer durationSeconds, boolean perSide) {}

    /** 上次表现：那次训练的日期 + 那个动作的组。 */
    public record LastPerformance(LocalDate sessionDate, long sessionExerciseId, List<SetSnapshot> sets) {}

    private static final String LATEST_SESSION_EXERCISE_SQL =
            "SELECT se.id AS se_id, s.session_date AS session_date "
                    + "FROM session_exercise se "
                    + "JOIN workout_session s ON s.id = se.session_id "
                    + "WHERE se.exercise_id = ? "
                    + "ORDER BY s.session_date DESC, s.id DESC, se.order_index ASC "
                    + "LIMIT 1";

    private final JdbcTemplate jdbc;
    private final WorkoutSetRepo sets;

    public LastPerformanceService(JdbcTemplate jdbc, WorkoutSetRepo sets) {
        this.jdbc = jdbc;
        this.sets = sets;
    }

    /** 从没做过这个动作时返回 {@link Optional#empty()}（调用方翻译成 204）。 */
    public Optional<LastPerformance> lastPerformance(long exerciseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(LATEST_SESSION_EXERCISE_SQL, exerciseId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long sessionExerciseId = ((Number) rows.get(0).get("se_id")).longValue();
        LocalDate date = LocalDate.parse((String) rows.get(0).get("session_date"));

        List<SetSnapshot> snapshots = sets.findBySessionExerciseIdOrderBySetIndexAsc(sessionExerciseId)
                .stream()
                .map(LastPerformanceService::toSnapshot)
                .toList();
        return Optional.of(new LastPerformance(date, sessionExerciseId, snapshots));
    }

    private static SetSnapshot toSnapshot(WorkoutSet ws) {
        return new SetSnapshot(ws.setType, ws.weight, ws.repetitions, ws.durationSeconds, ws.perSide);
    }
}
```

- [ ] **Step 4: 给 `ExerciseController` 加端点**

在 `ExerciseController` 里加依赖 `LastPerformanceService lastPerformanceService`（构造器多一个参数），并加方法：

```java
    /** 某动作上次练了多重；从没做过时 204（不是 404——「没做过」不是错误）。 */
    @GetMapping("/{id}/last-performance")
    public ResponseEntity<LastPerformanceService.LastPerformance> lastPerformance(@PathVariable long id) {
        return lastPerformanceService.lastPerformance(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
```

  需要的 import：`com.change.service.LastPerformanceService`、`org.springframework.http.ResponseEntity`、`org.springframework.web.bind.annotation.PathVariable`。

- [ ] **Step 5: 跑测试，确认通过**

```powershell
mvn -B -q -Dtest='LastPerformanceServiceTest,ExerciseControllerTest' test
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/change/service/LastPerformanceService.java \
        backend/src/main/java/com/change/web/ExerciseController.java \
        backend/src/test/java/com/change/service/LastPerformanceServiceTest.java \
        backend/src/test/java/com/change/web/ExerciseControllerTest.java
git commit -m "feat(training): 带出某动作上次的重量"
```

---

## Task 5: 本周容量（主 +1 / 次 +0.5，自然周）

**Files:**
- Create: `backend/src/main/java/com/change/service/WeeklyVolumeService.java`
- Create: `backend/src/main/java/com/change/web/VolumeController.java`
- Test: `backend/src/test/java/com/change/service/WeeklyVolumeServiceTest.java`
- Test: `backend/src/test/java/com/change/web/VolumeControllerTest.java`

口径（设计文档决定 5）：**每记一组，该动作主要肌群 +1、次要肌群 +0.5；按自然周（周一起算）汇总。**
只统计 `completed = 1` 的组——没打勾的组是「还没做/没记」，不是训练量。

> 与计划 6 的 `GET /api/body-map/volume` 的关系：那一条是**人体图**要的契约（`volumeByMuscle` 映射，设计文档决定 5 冻结），属于计划 6。这里给的是首页「本周容量」要的**带目标区间与状态**的列表；两者以后都从本任务这个服务取数，不重复实现。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/change/service/WeeklyVolumeServiceTest.java`：

```java
package com.change.service;

import com.change.repo.MuscleGroupRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本周容量：主 +1、次 +0.5，自然周（周一起算），只算已完成的组。
 *
 * <p>2026-10-14 是**周三**（已核过：2026-10-18 是周日 → 那周周一是 2026-10-12）。
 * 用例用固定日期而不是「今天」，否则测试会随运行日期换周、结果漂移。
 */
@SpringBootTest
@Transactional
class WeeklyVolumeServiceTest {

    @Autowired WeeklyVolumeService weeklyVolume;
    @Autowired WorkoutService workouts;
    @Autowired MuscleGroupRepo muscleGroups;
    @Autowired JdbcTemplate jdbc;

    private long muscleId(String nameEn) {
        return muscleGroups.findByNameEn(nameEn).orElseThrow().id;
    }

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void link(long exerciseId, long muscleGroupId, String role) {
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?,?,?)",
                exerciseId, muscleGroupId, role);
    }

    private void record(LocalDate date, long exerciseId, int completedSets, int unfinishedSets) {
        List<WorkoutService.SetInput> sets = new java.util.ArrayList<>();
        for (int i = 0; i < completedSets; i++) {
            sets.add(new WorkoutService.SetInput("normal", 60.0, 8, null, false, null, null, true, null, null));
        }
        for (int i = 0; i < unfinishedSets; i++) {
            sets.add(new WorkoutService.SetInput("normal", null, null, null, false, null, null, false, null, null));
        }
        workouts.recordSession(new WorkoutService.SessionInput(date, null, null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null, sets))));
    }

    @Test
    void counts_primary_one_secondary_half_and_only_completed_sets_in_the_week() {
        long ex = newExercise("ZZ Vol Bench");
        link(ex, muscleId("chest"), "primary");
        link(ex, muscleId("triceps"), "secondary");

        LocalDate wednesday = LocalDate.of(2026, 10, 14);
        record(wednesday, ex, 3, 1);                        // 3 完成 + 1 未完成 → 只算 3
        record(LocalDate.of(2026, 10, 11), ex, 1, 0);       // 上一周（周日）→ 不算

        WeeklyVolumeService.WeeklyVolume v = weeklyVolume.weekly(wednesday);

        assertThat(v.weekStart()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(v.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(v.muscles()).hasSize(17);

        assertThat(muscleSets(v, "chest")).isEqualTo(3.0);
        assertThat(muscleSets(v, "triceps")).isEqualTo(1.5);
    }

    @Test
    void untrained_and_unconfigured_muscles_report_the_right_status() {
        WeeklyVolumeService.WeeklyVolume v = weeklyVolume.weekly(LocalDate.of(2026, 10, 14));

        // 一块没练过、但有目标区间的肌群 → UNTRAINED（0 组，与「没配目标」是两回事）
        assertThat(muscleStatus(v, "abdominals")).isEqualTo(com.change.calc.VolumeCalculator.Status.UNTRAINED);
        // neck 的区间是 null → NOT_CONFIGURED
        assertThat(muscleStatus(v, "neck")).isEqualTo(com.change.calc.VolumeCalculator.Status.NOT_CONFIGURED);
    }

    private double muscleSets(WeeklyVolumeService.WeeklyVolume v, String nameEn) {
        return v.muscles().stream()
                .filter(m -> m.nameEn().equals(nameEn)).findFirst().orElseThrow().sets();
    }

    private com.change.calc.VolumeCalculator.Status muscleStatus(WeeklyVolumeService.WeeklyVolume v, String nameEn) {
        return v.muscles().stream()
                .filter(m -> m.nameEn().equals(nameEn)).findFirst().orElseThrow().status();
    }
}
```

`backend/src/test/java/com/change/web/VolumeControllerTest.java`：

```java
package com.change.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 周容量端点：日期参数解析 + 17 块肌群的形状。具体数值由 WeeklyVolumeServiceTest 覆盖。 */
@SpringBootTest
@AutoConfigureMockMvc
class VolumeControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void weekly_volume_accepts_an_iso_date_and_returns_all_seventeen_muscles() throws Exception {
        mvc.perform(get("/api/volume/weekly").param("date", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value("2026-10-12"))
                .andExpect(jsonPath("$.weekEnd").value("2026-10-18"))
                .andExpect(jsonPath("$.muscles.length()").value(17))
                .andExpect(jsonPath("$.muscles[0].nameEn").exists())
                .andExpect(jsonPath("$.muscles[0].status").exists());
    }

    @Test
    void a_bad_date_is_a_400() throws Exception {
        mvc.perform(get("/api/volume/weekly").param("date", "2026/10/14"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```powershell
mvn -B -q -Dtest=WeeklyVolumeServiceTest test
```

Expected: 编译失败 —— `cannot find symbol: class WeeklyVolumeService`。

- [ ] **Step 3: 写 `WeeklyVolumeService`**

`backend/src/main/java/com/change/service/WeeklyVolumeService.java`：

```java
package com.change.service;

import com.change.calc.VolumeCalculator;
import com.change.domain.MuscleGroup;
import com.change.repo.MuscleGroupRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 「本周每个肌群折算多少组」。
 *
 * <p>口径：每记一组，该动作主要肌群 +1、次要肌群 +0.5（{@link VolumeCalculator}）；按自然周
 * （周一起算）汇总；<b>只算 {@code completed = 1} 的组</b>——没打勾的组是「还没做/没记」，
 * 不是训练量。
 *
 * <p>返回**全部 17 块肌群**（含 0 组的）：设计文档要求首页把「腹/核心本周 0 组」这类提醒
 * 摆在最显眼的位置，所以「没练」必须以 0 出现，而不是从列表里消失。
 */
@Service
public class WeeklyVolumeService {

    /** 一块肌群的周容量。{@code status} 由 {@link VolumeCalculator#classify} 给出。 */
    public record MuscleVolume(long muscleGroupId, String nameZh, String nameEn, double sets,
                               Integer min, Integer max, VolumeCalculator.Status status) {}

    /** 一周的容量。 */
    public record WeeklyVolume(LocalDate weekStart, LocalDate weekEnd, List<MuscleVolume> muscles) {}

    private static final String ENTRIES_SQL =
            "SELECT em.muscle_group_id AS muscle_id, em.role AS role, COUNT(*) AS sets "
                    + "FROM workout_set ws "
                    + "JOIN session_exercise se ON se.id = ws.session_exercise_id "
                    + "JOIN workout_session s ON s.id = se.session_id "
                    + "JOIN exercise_muscle em ON em.exercise_id = se.exercise_id "
                    + "WHERE s.session_date BETWEEN ? AND ? AND ws.completed = 1 "
                    + "GROUP BY em.muscle_group_id, em.role";

    private final JdbcTemplate jdbc;
    private final MuscleGroupRepo muscleGroups;

    public WeeklyVolumeService(JdbcTemplate jdbc, MuscleGroupRepo muscleGroups) {
        this.jdbc = jdbc;
        this.muscleGroups = muscleGroups;
    }

    /** {@code anyDay} 所在的那一周（周一到周日）。 */
    public WeeklyVolume weekly(LocalDate anyDay) {
        LocalDate start = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(6);

        List<VolumeCalculator.Entry> entries = jdbc.query(ENTRIES_SQL,
                (rs, rowNum) -> new VolumeCalculator.Entry(
                        rs.getLong("muscle_id"), rs.getInt("sets"), rs.getString("role")),
                start.toString(), end.toString());

        Map<Long, Double> setsByMuscle = VolumeCalculator.accumulate(entries);

        List<MuscleVolume> muscles = muscleGroups.findAll().stream()
                .sorted(Comparator.comparing(g -> g.id))
                .map(g -> toMuscleVolume(g, setsByMuscle))
                .toList();

        return new WeeklyVolume(start, end, muscles);
    }

    private static MuscleVolume toMuscleVolume(MuscleGroup g, Map<Long, Double> setsByMuscle) {
        double sets = setsByMuscle.getOrDefault(g.id, 0.0);   // 缺键 = 0 组
        return new MuscleVolume(g.id, g.nameZh, g.nameEn, sets, g.weeklySetsMin, g.weeklySetsMax,
                VolumeCalculator.classify(sets, g.weeklySetsMin, g.weeklySetsMax));
    }
}
```

- [ ] **Step 4: 写 `VolumeController`**

`backend/src/main/java/com/change/web/VolumeController.java`：

```java
package com.change.web;

import com.change.service.WeeklyVolumeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 容量的读入口。 */
@RestController
@RequestMapping("/api/volume")
public class VolumeController {

    private final WeeklyVolumeService weeklyVolume;

    public VolumeController(WeeklyVolumeService weeklyVolume) {
        this.weeklyVolume = weeklyVolume;
    }

    /**
     * 某一天所在那一周的各肌群折算组数。{@code date} 省略时取今天。
     *
     * <p>格式锁死 ISO（{@code YYYY-MM-DD}）；写错的日期是 400，不是静默取今天。
     */
    @GetMapping("/weekly")
    public WeeklyVolumeService.WeeklyVolume weekly(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return weeklyVolume.weekly(date == null ? LocalDate.now() : date);
    }
}
```

- [ ] **Step 5: 跑测试，确认通过**

```powershell
mvn -B -q -Dtest='WeeklyVolumeServiceTest,VolumeControllerTest' test
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`。

  若 `a_bad_date_is_a_400` 报 500：说明 `MethodArgumentTypeMismatchException` 没被
  `ApiErrors` 覆盖——它**不是** `IllegalArgumentException` 的子类。补一个处理器：

```java
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badParameter(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return Map.of("error", "参数 " + e.getName() + " 格式不对");
    }
```

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/change/service/WeeklyVolumeService.java \
        backend/src/main/java/com/change/web/VolumeController.java \
        backend/src/test/java/com/change/service/WeeklyVolumeServiceTest.java \
        backend/src/test/java/com/change/web/VolumeControllerTest.java
git commit -m "feat(training): 本周容量（主 +1 / 次 +0.5，自然周）"
```

---

## Task 6: 动作选择器（按肌群 / 关键词 / 分类）

**Files:**
- Create: `backend/src/main/java/com/change/service/ExerciseQueryService.java`
- Modify: `backend/src/main/java/com/change/web/ExerciseController.java`
- Test: `backend/src/test/java/com/change/service/ExerciseQueryServiceTest.java`
- Test: `backend/src/test/java/com/change/web/ExerciseControllerTest.java`

流程①的「选肌群 → 出动作列表」与「搜索动作」都走这里。

**分类过滤的口径**（这是本任务唯一需要拍板的地方，写清楚免得后面反复）：`category` 参数给定时**精确匹配**；**不给定时排除 `stretching`**——拉伸不是「记一组」的对象，不该混进选择器。其余分类（`strength` / `powerlifting` / `olympic weightlifting` / `strongman` / `plyometrics` / `cardio`）默认保留：把 292 条非 strength 一刀切掉会把「抓举」「挺举」这类真正的力量动作也滤掉（它们分别是 `olympic weightlifting` 与 `strength` 之外的值）。计划 2 里那句「292 条要能滤掉」指的是**可滤**，不是**默认全滤**。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/change/service/ExerciseQueryServiceTest.java`：

```java
package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 动作选择器的筛选规则。 */
@SpringBootTest
@Transactional
class ExerciseQueryServiceTest {

    @Autowired ExerciseQueryService query;
    @Autowired JdbcTemplate jdbc;

    private long newExercise(String nameEn, String nameZh, String category) {
        jdbc.update("INSERT INTO exercise(name_en, name_zh, default_unit, category) VALUES (?,?,'kg',?)",
                nameEn, nameZh, category);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void link(long exerciseId, long muscleGroupId) {
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?,?,'primary')",
                exerciseId, muscleGroupId);
    }

    private long muscleId(String nameEn) {
        return jdbc.queryForObject("SELECT id FROM muscle_group WHERE name_en = ?", Long.class, nameEn);
    }

    private List<String> names(List<ExerciseQueryService.ExerciseSummary> rows) {
        return rows.stream().map(ExerciseQueryService.ExerciseSummary::nameEn).toList();
    }

    @Test
    void by_default_stretching_is_excluded_but_the_other_categories_are_kept() {
        newExercise("ZZ Sel Strength", "力量", "strength");
        newExercise("ZZ Sel Olympic", "抓举", "olympic weightlifting");
        newExercise("ZZ Sel Stretch", "拉伸", "stretching");

        List<String> all = names(query.search(null, "ZZ Sel", null, null));

        assertThat(all).contains("ZZ Sel Strength", "ZZ Sel Olympic");
        assertThat(all).doesNotContain("ZZ Sel Stretch");
    }

    @Test
    void an_explicit_category_includes_stretching_again() {
        newExercise("ZZ Sel Stretch2", "拉伸", "stretching");

        assertThat(names(query.search(null, "ZZ Sel Stretch2", "stretching", null)))
                .containsExactly("ZZ Sel Stretch2");
    }

    @Test
    void filters_by_muscle_group_and_matches_chinese_or_english() {
        long chestId = muscleId("chest");
        long inChest = newExercise("ZZ Sel Chest", "胸部动作", "strength");
        long notInChest = newExercise("ZZ Sel Legs", "腿部动作", "strength");
        link(inChest, chestId);

        assertThat(names(query.search(chestId, "ZZ Sel", null, null))).containsExactly("ZZ Sel Chest");
        assertThat(names(query.search(null, "腿部动作", null, null))).containsExactly("ZZ Sel Legs");
        assertThat(names(query.search(null, "zz sel legs", null, null))).containsExactly("ZZ Sel Legs");
        assertThat(notInChest).isPositive();
    }

    @Test
    void the_limit_is_capped() {
        for (int i = 0; i < 5; i++) {
            newExercise("ZZ Sel Limit " + i, "限量 " + i, "strength");
        }
        assertThat(query.search(null, "ZZ Sel Limit", null, 2)).hasSize(2);
        assertThat(query.search(null, "ZZ Sel Limit", null, 9999)).hasSize(5);
    }
}
```

给 `ExerciseControllerTest` 补一条 HTTP 断言：

```java
    @Test
    void lists_exercises_through_the_api() throws Exception {
        jdbc.update("DELETE FROM exercise WHERE name_en = 'ZZ Ctrl Selector'");
        jdbc.update("INSERT INTO exercise(name_en, name_zh, default_unit, category) "
                + "VALUES ('ZZ Ctrl Selector', '选择器', 'kg', 'strength')");

        mvc.perform(get("/api/exercises").param("q", "选择器"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nameZh").value("选择器"))
                .andExpect(jsonPath("$[0].category").value("strength"));
    }
```

- [ ] **Step 2: 跑测试，确认失败**

```powershell
mvn -B -q -Dtest=ExerciseQueryServiceTest test
```

Expected: 编译失败 —— `cannot find symbol: class ExerciseQueryService`。

- [ ] **Step 3: 写 `ExerciseQueryService`**

`backend/src/main/java/com/change/service/ExerciseQueryService.java`：

```java
package com.change.service;

import com.change.domain.Exercise;
import com.change.repo.ExerciseRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 动作选择器：按肌群 / 关键词 / 分类筛动作。
 *
 * <p><b>为什么用既有的派生查询 + 内存过滤，而不是一条大 SQL。</b>文字搜索走
 * {@link ExerciseRepo} 那两个方法（计划 2 刚修过 `archived` 过滤，这里正好是它们的第一个真实
 * 调用方——不要另写一条 SQL 让它们变成死代码）；肌群过滤走一条取 id 的 SQL 后求交集；
 * 分类与截断在内存里做。动作库是 876 条的**随包数据**，不是会长的用户表，全表进内存完全够用。
 * 真到了几万条再下推 SQL——现在下推只会多一套要维护的 SQL 与它的测试。
 *
 * <p><b>分类规则</b>：{@code category} 给定时精确匹配；不给定时排除 {@code stretching}
 * （理由见计划文档 Task 6 的说明）。
 */
@Service
public class ExerciseQueryService {

    /** 选择器默认返回多少条。 */
    public static final int DEFAULT_LIMIT = 50;

    /** 上限，防止调用方一次把整个库拉走。 */
    public static final int MAX_LIMIT = 200;

    /** 不给 category 时排除掉的分类：拉伸不是「记一组」的对象。 */
    private static final String NOT_A_SET_CATEGORY = "stretching";

    /** 选择器里的一行。 */
    public record ExerciseSummary(long id, String nameEn, String nameZh, String equipment,
                                  boolean isCompound, String category) {}

    private final ExerciseRepo exercises;
    private final JdbcTemplate jdbc;

    public ExerciseQueryService(ExerciseRepo exercises, JdbcTemplate jdbc) {
        this.exercises = exercises;
        this.jdbc = jdbc;
    }

    /**
     * @param muscleGroupId 只看练这块肌群的动作；null 表示不限
     * @param query         关键词，匹配中文名或英文名（英文不分大小写）；null/空表示不限
     * @param category      精确匹配的分类；null/空表示「除 stretching 外都要」
     * @param limit         返回条数上限；null 用 {@link #DEFAULT_LIMIT}，并且封顶在 {@link #MAX_LIMIT}
     */
    public List<ExerciseSummary> search(Long muscleGroupId, String query, String category, Integer limit) {
        List<Exercise> candidates = (query == null || query.isBlank())
                ? exercises.findByArchivedFalse()
                : exercises.findByArchivedFalseAndNameZhContainingOrArchivedFalseAndNameEnContainingIgnoreCase(
                        query, query);

        Set<Long> forMuscle = muscleGroupId == null ? null : new HashSet<>(jdbc.queryForList(
                "SELECT exercise_id FROM exercise_muscle WHERE muscle_group_id = ?",
                Long.class, muscleGroupId));

        boolean categoryGiven = category != null && !category.isBlank();
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        return candidates.stream()
                .filter(e -> forMuscle == null || forMuscle.contains(e.id))
                .filter(e -> categoryGiven ? category.equals(e.category)
                                           : !NOT_A_SET_CATEGORY.equals(e.category))
                .sorted(Comparator.comparing(e -> e.nameZh == null ? e.nameEn : e.nameZh))
                .limit(cap)
                .map(ExerciseQueryService::toSummary)
                .toList();
    }

    private static ExerciseSummary toSummary(Exercise e) {
        return new ExerciseSummary(e.id, e.nameEn, e.nameZh, e.equipment, e.compound, e.category);
    }
}
```

- [ ] **Step 4: 给 `ExerciseController` 加端点**

```java
    /**
     * 动作选择器：按肌群 / 关键词 / 分类筛。
     *
     * <p>不给 {@code category} 时**排除拉伸**（见 {@code ExerciseQueryService} 的说明）。
     */
    @GetMapping
    public List<ExerciseQueryService.ExerciseSummary> list(
            @RequestParam(required = false) Long muscleGroupId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer limit) {
        return query.search(muscleGroupId, q, category, limit);
    }
```

  需要的 import：`com.change.service.ExerciseQueryService`、`java.util.List`，并给构造器加 `ExerciseQueryService query`。

- [ ] **Step 5: 跑测试，确认通过**

```powershell
mvn -B -q -Dtest='ExerciseQueryServiceTest,ExerciseControllerTest' test
```

Expected: `Tests run: 9, Failures: 0, Errors: 0`。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/change/service/ExerciseQueryService.java \
        backend/src/main/java/com/change/web/ExerciseController.java \
        backend/src/test/java/com/change/service/ExerciseQueryServiceTest.java \
        backend/src/test/java/com/change/web/ExerciseControllerTest.java
git commit -m "feat(training): 动作选择器（按肌群 / 关键词 / 分类）"
```

---

## Task 7: 端到端验收与文档

**Files:**
- Modify: `docs/superpowers/plans/2026-10-14-change-phase1-overview.md`

- [ ] **Step 1: 干净库上跑一遍全链路**

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:CHANGE_DATA_DIR = "$env:TEMP\change-training-e2e"
Remove-Item -Recurse -Force $env:CHANGE_DATA_DIR -ErrorAction SilentlyContinue
mvn -B -q -DskipTests package
java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

  启动日志里会先出现动作库导入（计划 2），再出现 `CHANGE_PORT=<port>`。用那个端口逐条核对：

| # | 请求 | 期望 |
|---|---|---|
| 1 | `POST /api/sessions`，body 是一个含 1 个动作、3 组（2 组"卧推 60×8"已完成）的训练，`sessionDate` = 今天 | 200，`{"sessionId":<n>,"exercises":1,"sets":3}` |
| 2 | `GET /api/sessions/recent` | 200，数组第一条就是刚才那次 |
| 3 | `GET /api/sessions/<n>` | 200，`exercises[0].sets` 长度 3、顺序按 `setIndex` |
| 4 | `GET /api/exercises/<benchId>/last-performance` | 200，`sessionDate` 是今天、`sets[0].weight` 是 60 |
| 5 | `GET /api/volume/weekly?date=<今天>` | 200，`muscles` 长度 17；卧推主要肌群那块的 `sets` = 2（+1/组）、次要肌群 = 1.0（+0.5/组） |
| 6 | `SELECT COUNT(*) FROM change_log WHERE origin='user'` | = 1 + 1 + 3 = **5** |
| 7 | **再启动一次**（库已存在） | 动作库不再导入；`GET /api/sessions/recent` 仍有刚才那次 |
| 8 | `GET /api/volume/weekly?date=` 传一个不在今天的日期 | 该周没有训练 → 全 0 / `UNTRAINED`，不是 500 |

  > 每一步的**真实输出**（状态码、响应体、SQL 结果）都要抄进文档，不要只写"符合预期"。

- [ ] **Step 2: 把结果记进总览**

在 `docs/superpowers/plans/2026-10-14-change-phase1-overview.md` 的「计划 2」一节之后，补一节「计划 3：训练记录（后端）」：写清热量之外的三个数字来源（上次重量怎么定义「上次」、周容量的口径与只算 completed、选择的默认分类过滤），以及第 1 步里 8 条的真实结果。

- [ ] **Step 3: `mvn -B clean test` 全绿并报告条数**

```powershell
mvn -B clean test
```

Expected: 全绿；把实际条数写进提交信息（本计划开始时是 190 条）。

- [ ] **Step 4: 提交**

```bash
git add docs/superpowers/plans/2026-10-14-change-phase1-overview.md
git commit -m "docs: 总览补计划 3 的端到端实测结果"
```

---

## 完成标准

- [ ] `cd backend && mvn -B clean test` 全绿，**报告实际条数**
- [ ] **写一次完整训练**：三层一个事务；每行都进 `change_log`（`origin='user'`）；快照里的日期是字符串（不是 java.time 对象）
- [ ] **上次重量带出**：从没做过 → 204；做过 → 最近那次（按 `session_date`，不是按 id）的组
- [ ] **周容量**：主 +1 / 次 +0.5；自然周（周一起算）；只算 `completed = 1`；返回 17 块肌群，`neck` 是 `NOT_CONFIGURED`、没练的是 `UNTRAINED`
- [ ] **动作选择器**：默认排除 `stretching`；`category` 给定时精确匹配；英文搜索不分大小写
- [ ] 参数写错是 **400**（不是 500），找不到的训练是 **404**，「从没做过」是 **204**
- [ ] `git status --short` 无输出
- [ ] 总览里补上计划 3 的真实结果

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| 快照放进 `java.time` 对象 | `ChangeLogService` 的 `ObjectMapper` 没有 `JavaTimeModule`，序列化时抛异常——而且是在**记日志**那一步，会把整次记录弄失败 | Task 2 的 `the_snapshot_stores_the_date_as_a_string_not_a_java_time_object` 钉死；日期/时间戳一律 `toString()` |
| `@JdbcTypeCode` 被"简化"掉 | xerial 驱动默认把 java.time 写成纪元毫秒；`started_at` 没有 CHECK，会**静默**存成数字串 | 用既有实体的映射，不要另写；Task 7 第 8 条顺带看 `started_at` 是否是可读的 ISO 串 |
| 周界算错（周日/周一分不清） | 算错了只是数字小一点或大一点，**看起来完全正常** | Task 5 用固定日期 `2026-10-14`（周三）并显式断言 `weekStart` / `weekEnd` |
| 只算 `completed` 这个口径没人记住 | 以后有人把未完成的组也算进去，容量会悄悄变大 | Task 5 的测试里专门放一条未完成的组并断言不影响结果；总览里也写明 |
| 选择器默认过滤被误解成「全滤非 strength」 | 会把抓举/挺举一并滤掉 | Task 6 的第一条测试同时钉住「排除 stretching」与「保留 olympic weightlifting」 |
| 876 条全进内存做选择器 | 目前完全够用（随包数据、不会长） | 写在 `ExerciseQueryService` 的类注释里；真要下推 SQL 时连测试一起改 |

## 非目标（本计划不做）

- **不做前端**——`TodayView.vue`（本周容量）与训练记录页另起一份计划，`frontend/` 这一轮不动
- **不做 `GET /api/body-map/volume`**——那是计划 6 的人体图契约（设计文档决定 5）
- **不做增量保存**（训练中逐字段 PATCH）——本计划只做「一次写完」；设计文档说过训练中界面靠增量保存，但那要等前端一起设计
- **不做 1RM / 进步曲线**——计划 6（`OneRepMax` 已就绪，但曲线是那边的事）
- **不做重复动作的合并、不做 `superset_group` 的界面表达**——字段留着，语义由前端决定
- **不给训练加删除/修改端点**——设计的智能体安全线是「只增改不删」；改训练等智能体那一步一起做（`ChangeLog` 已能回退）




