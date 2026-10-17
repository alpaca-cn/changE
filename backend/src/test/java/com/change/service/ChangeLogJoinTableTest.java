package com.change.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
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

    /**
     * 每个测试方法开始前把夹具准备好。两件事：
     *
     * <p>1) <b>清掉上一轮留下的 ZZ 行。</b>本类没有 {@code @Transactional}（它要观察真实的
     * 提交），所以任何一条测试失败都会在磁盘上的库里留下行，而 exercise.name_en 上有唯一
     * 索引——下一轮会在**准备阶段**红成一条与真正原因无关的 UNIQUE 失败。
     * 清理必须放在这里（测试体之前）而不是放在 {@code newExercise()} 里：
     * {@link #undoing_every_log_row_returns_the_database_to_its_original_state()} 会在测试
     * 体开头数一遍基线行数，若「删旧行再插新行」发生在基线之后，收尾时的行数就会比基线
     * 少 1——**那条测试会红在一个完全无关的原因上**（这版第一稿正是如此，实测）。
     *
     * <p>2) <b>补足 3 块肌肉。</b>17 行肌群种子在**计划 2 的 Task 2**（V8）里，本次任务
     * 结束时 {@code muscle_group} 还是空表。空表会让这里的测试以两种与被测行为无关的
     * 方式失效：
     * <ul>
     *   <li>{@link #someMuscleId()} 的 {@code queryForObject} 在 0 行上抛
     *       {@code EmptyResultDataAccessException}——报的错与「join 行能不能进日志」无关；</li>
     *   <li>更糟的是 {@link #undoing_every_log_row_returns_the_database_to_its_original_state()}
     *       里那句 {@code LIMIT 3} 会取到 0 行，循环一次都不进，于是那条测试
     *       <b>空转着变绿</b>——对一个「守日志完整性」的测试来说，假绿比红危险得多。</li>
     * </ul>
     * 只补到 3 行而不是硬塞 17 行：Task 2 落地后这里是 no-op（表里已经有种子）。
     */
    @BeforeEach
    void freshFixtures() {
        jdbc.update("DELETE FROM exercise WHERE name_en LIKE 'ZZ %'");

        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM muscle_group", Integer.class);
        for (int i = existing; i < 3; i++) {
            jdbc.update("INSERT INTO muscle_group(name_zh, name_en, body_part) VALUES (?,?,?)",
                    "ZZ 测试肌群 " + (i + 1), "ZZ Test Muscle " + (i + 1), "chest");
        }
    }

    private long newExercise(String name) {
        return jdbc.queryForObject(
                "INSERT INTO exercise (name_en, default_unit) VALUES (?, 'kg') RETURNING id",
                Long.class, name);
    }

    private long someMuscleId() {
        return jdbc.queryForObject("SELECT id FROM muscle_group ORDER BY id LIMIT 1", Long.class);
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

        List<Long> joinLogIds = new ArrayList<>();
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

    @Test
    void the_unique_index_on_the_pair_still_holds() {
        // 加代理主键之后，业务唯一性从「主键」降级成了唯一索引。
        // 降级是有风险的：如果索引没建上，同一个动作就能对同一块肌肉标两次，
        // 而「本周 20 组」会被静默算成 40 组。
        long exId = newExercise("ZZ Pair Unique Test");
        long muscleId = someMuscleId();

        jdbc.update("INSERT INTO exercise_muscle (exercise_id, muscle_group_id, role) VALUES (?,?,'primary')",
                exId, muscleId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO exercise_muscle (exercise_id, muscle_group_id, role) VALUES (?,?,'secondary')",
                exId, muscleId))
                .as("同一动作同一肌肉不能有两行")
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        jdbc.update("DELETE FROM exercise WHERE id = ?", exId);
    }
}
