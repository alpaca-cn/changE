package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ChangeLogServiceTest {

    @Autowired ChangeLogService logs;
    @Autowired JdbcTemplate jdbc;

    private long insertFood(String name, double kcal) {
        jdbc.update("INSERT INTO food(name_zh, source, kcal_per100) VALUES (?,?,?)",
                name, "manual", kcal);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /**
     * 绕过服务层直写一行 change_log，用来构造「库里已经有一行服务层写不出来的坏数据」：
     * target_table 不在白名单、before_json 的键不是合法列名。
     * 服务层的正常路径写不出这种行，所以只能手工造——但库里的行**可以**长这样
     * （target_table 上没有 CHECK），undo 必须自己扛住。
     */
    private long insertRawLog(String action, String targetTable, String beforeJson) {
        jdbc.update("INSERT INTO change_log(changed_at, origin, action, target_table, " +
                    "target_id, before_json, after_json, undone) VALUES (?,?,?,?,?,?,?,0)",
                "2026-10-16T00:00:00", "agent", action, targetTable, 1L,
                beforeJson, "{\"x\":1}");
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Test
    void records_an_insert_with_null_before() {
        long id = insertFood("测试食物A", 100);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "测试食物A"),
                "user", null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT origin, action, target_table, before_json, after_json " +
                "FROM change_log WHERE id = ?", logId);
        assertThat(row.get("origin")).isEqualTo("user");
        assertThat(row.get("action")).isEqualTo("insert");
        assertThat(row.get("before_json")).isNull();
        assertThat((String) row.get("after_json")).contains("测试食物A");
    }

    @Test
    void records_the_chat_session_id_when_there_is_one() {
        // chat_session_id 是设计文档给 ChangeLog 列的字段之一（「会话 id」），
        // 而在此之前**没有任何测试传过非 null 的值**——把它整个写成 null 的变异能活下来
        // （实测 M17：11 条全绿）。所以补这一条，把「日志行真的挂在会话上」钉住。
        // 注意 change_log.chat_session_id 上没有 REFERENCES，这里仍先建一行会话，
        // 走的是将来真实调用会走的形状。
        jdbc.update("INSERT INTO chat_session(title, created_at) VALUES (?,?)",
                "会话", "2026-10-16T00:00:00");
        long sessionId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        long id = insertFood("测试食物I", 900);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "测试食物I"),
                "agent", sessionId);

        assertThat(jdbc.queryForObject(
                "SELECT chat_session_id FROM change_log WHERE id = ?", Long.class, logId))
                .isEqualTo(sessionId);
    }

    @Test
    void rollback_of_an_insert_deletes_the_row() {
        long id = insertFood("测试食物B", 200);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "测试食物B"),
                "agent", null);

        logs.undo(logId);

        Integer remain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, id);
        assertThat(remain).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId))
                .isEqualTo(1);
    }

    @Test
    void rollback_of_an_update_restores_previous_values() {
        long id = insertFood("测试食物C", 300);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100", 300.0), Map.of("kcal_per100", 999.0),
                "agent", null);

        jdbc.update("UPDATE food SET kcal_per100 = 999 WHERE id = ?", id);
        logs.undo(logId);

        Double kcal = jdbc.queryForObject(
                "SELECT kcal_per100 FROM food WHERE id = ?", Double.class, id);
        assertThat(kcal).isEqualTo(300.0);
    }

    @Test
    void cannot_undo_twice() {
        long id = insertFood("测试食物D", 400);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "测试食物D"),
                "agent", null);
        logs.undo(logId);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已经撤销过");
    }

    @Test
    void rejects_unknown_table() {
        assertThatThrownBy(() -> logs.recordInsert("drop_table_attempt", 1,
                Map.of("x", 1), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
        // 两条写入路径共用 write() 里的白名单校验，但「共用」是实现的巧合而不是契约，
        // 所以 recordUpdate 这一侧也单独钉一条（before 合法，卡住的只能是表名）。
        long id = insertFood("测试食物H", 800);
        assertThatThrownBy(() -> logs.recordUpdate("drop_table_attempt", id,
                Map.of("kcal_per100", 800.0), Map.of("kcal_per100", 801.0), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void every_allowed_table_has_a_single_integer_id_column() {
        // 回退靠 WHERE id = ? 定位目标行。复合主键的表（exercise_muscle）定位不到，
        // 所以白名单的准入条件是「必须有单列整型主键 id」。
        // 这条测试是给未来的自己设的闸：往白名单加表时忘了这个条件，它会立刻红。
        for (String table : com.change.service.ChangeLogService.ALLOWED_TABLES) {
            // 表名只能拼进 SQL（pragma_table_info 的参数是标识符，不是绑定值）；
            // 它来自本项目的常量，不是外部输入。
            // 另：PRAGMA table_info(...) 是**表函数**，只取一列——取多列会让
            // queryForList(sql, String.class) 抛 IncorrectResultSetColumnCountException。
            var cols = jdbc.queryForList(
                    "SELECT name FROM pragma_table_info('" + table + "')", String.class);
            assertThat(cols).as("表 %s 的列", table).contains("id");

            String idType = jdbc.queryForMap(
                    "SELECT type, pk FROM pragma_table_info('" + table + "') WHERE name = 'id'")
                    .get("type").toString().toUpperCase();
            assertThat(idType).as("表 %s 的 id 类型", table).isEqualTo("INTEGER");

            Integer pkCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE pk > 0",
                    Integer.class);
            assertThat(pkCount).as("表 %s 的主键列数", table).isEqualTo(1);
        }
    }

    @Test
    void undo_rejects_a_non_identifier_column_name() {
        // 参数占位符保护值，保护不了标识符——列名是拼进 SQL 的。
        long id = insertFood("测试食物E", 500);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100 = 1, name_zh", 1.0),   // 恶意键
                Map.of("kcal_per100", 1.0), "agent", null);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法的列名");
    }

    @Test
    void record_update_requires_a_before_snapshot() {
        long id = insertFood("测试食物F", 600);
        assertThatThrownBy(() -> logs.recordUpdate("food", id, null,
                Map.of("kcal_per100", 1.0), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before")
                // ⚠️ 只断言 "before" 是不够判别的一句话：数据库侧的
                // CHECK 报错原文里含 "before_json"，也含 "before"。
                // 再加一条只有 Java 侧文案才有的词，这条断言才真的在钉「Java 先拦」。
                .hasMessageContaining("快照");
        assertThatThrownBy(() -> logs.recordUpdate("food", id, Map.of(),
                Map.of("kcal_per100", 1.0), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before")
                .hasMessageContaining("快照");
    }

    @Test
    void rejects_empty_after_and_unknown_origin() {
        long id = insertFood("测试食物G", 700);
        assertThatThrownBy(() -> logs.recordInsert("food", id, null, "agent", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("after");
        assertThatThrownBy(() -> logs.recordInsert("food", id, Map.of(), "agent", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("after");
        assertThatThrownBy(() -> logs.recordInsert("food", id,
                Map.of("name_zh", "x"), "robot", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("origin");
    }

    @Test
    void undo_of_an_unknown_log_id_is_rejected() {
        assertThatThrownBy(() -> logs.undo(999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到");
    }

    @Test
    void undo_rejects_a_target_table_outside_the_whitelist() {
        // undo 的 target_table 来自**数据库**，不是来自本次调用的参数——
        // 库里一行坏数据就是一条越权回退路径（能删/改白名单外的任何表）。
        // 删掉 undo 里的 requireAllowed(table) 时，只有这条测试会红。
        long logId = insertRawLog("insert", "drop_table_attempt", null);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
        // 拦住之后不能留下「已撤销」的半截状态，否则再点一次撤销就无事可做。
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId))
                .isZero();
    }
}
