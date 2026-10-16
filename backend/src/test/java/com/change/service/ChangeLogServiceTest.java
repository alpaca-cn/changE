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
        // 这一条同时也是「注入确实会被执行」的反证：键名本身是 `kcal_per100 = 1, name_zh`，
        // 去掉校验后它就会变成 SQL 的一部分（M02 实测变红）。
        // 报错文案随校验方式改成了按 schema 校验（详见 writableColumns 的注释），
        // 断言跟着改成钉「这个恶意列名被点名」+ 那句说明，而不是钉具体的措辞。
        long id = insertFood("测试食物E", 500);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100 = 1, name_zh", 1.0),   // 恶意键
                Map.of("kcal_per100", 1.0), "agent", null);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kcal_per100 = 1, name_zh")
                .hasMessageContaining("可回退写入的列");
    }

    @Test
    void undo_refuses_to_rewrite_the_primary_key() {
        // SQLite 的 rowid / _rowid_ / oid 是主键的别名（INTEGER PRIMARY KEY 表上就是 id），
        // 它们能通过任何标识符正则，而 SET rowid = ? 是合法 SQL。
        // 不拦的话，撤销会静默把目标行的主键搬走，而 change_log.target_id 仍指向旧 id——
        // 后续 undo 会定位到别的行。
        long id = insertFood("测试食物H", 800);
        long targetId = id;

        for (String pkAlias : java.util.List.of("id", "rowid", "_rowid_", "oid")) {
            long logId = logs.recordUpdate("food", targetId,
                    Map.of(pkAlias, 7L), Map.of("kcal_per100", 1.0), "agent", null);

            assertThatThrownBy(() -> logs.undo(logId))
                    .as("主键别名 %s 必须被拒绝", pkAlias)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(pkAlias);

            // 拒绝之后 undone 必须保持 0：不能留下「日志说撤了、数据没动」的半截状态
            assertThat(jdbc.queryForObject(
                    "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)).isZero();
            // 目标行必须原地不动
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, targetId)).isEqualTo(1);
        }
    }

    @Test
    void undo_refuses_a_column_that_does_not_exist() {
        // 拼写错误与保留字都落到这里（select 这类保留字通过任何标识符正则，只在 SQLite 层报语法错）
        long id = insertFood("测试食物I", 900);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100_typo", 1.0), Map.of("kcal_per100", 1.0), "agent", null);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kcal_per100_typo");
    }

    @Test
    void undo_fails_when_the_target_row_is_gone() {
        // 影响 0 行却照样置 undone=1 的话，审计日志会声称「已撤销」而数据什么都没动。
        long id = insertFood("测试食物J", 1000);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100", 1000.0), Map.of("kcal_per100", 1.0), "agent", null);

        jdbc.update("DELETE FROM food WHERE id = ?", id);   // 目标行先没了

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("目标行已不存在");
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)).isZero();
    }

    @Test
    void undo_of_a_referenced_row_translates_the_foreign_key_error() {
        // undo 一条 insert 是物理 DELETE。这条 food 已被 meal_item.food_id 引用
        // （meal_item.food_id 是裸 REFERENCES，默认 NO ACTION），DELETE 会被外键拦下。
        // 不翻译的话，用户看到的是一句 SQLITE_CONSTRAINT_FOREIGNKEY 天书。
        long foodId = insertFood("测试食物K", 1100);
        long logId = logs.recordInsert("food", foodId, Map.of("name_zh", "测试食物K"),
                "agent", null);

        jdbc.update("INSERT INTO meal(meal_date, slot) VALUES (?,?)", "2026-10-16", "lunch");
        long mealId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO meal_item(meal_id, food_id, order_index, amount, grams) " +
                    "VALUES (?,?,?,?,?)", mealId, foodId, 0, 100.0, 100.0);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("引用");
        // 报错路径同样不能留下半截状态：food 还在，日志也还是「没撤」。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, foodId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)).isZero();
    }

    @Test
    void undo_of_a_cascaded_parent_row_silently_drops_the_join_rows() {
        // 契约的另一半（见 ChangeLogService 的类 javadoc）：撤销一个被 CASCADE 引用的父行
        // 会连带删掉子行，而快照两侧都不含子行。这条测试把这个**实际后果**钉下来，
        // 免得将来做「动作编辑」的人以为撤销后再重做能拿回肌群标注。
        jdbc.update("INSERT INTO muscle_group(name_zh, name_en, body_part) VALUES (?,?,?)",
                "胸", "ChangE Test Chest", "chest");
        long muscleId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO exercise(name_en, name_zh) VALUES (?,?)",
                "ChangE Test Bench Press", "测试卧推");
        long exerciseId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?,?,?)",
                exerciseId, muscleId, "primary");
        long logId = logs.recordInsert("exercise", exerciseId,
                Map.of("name_en", "ChangE Test Bench Press"), "agent", null);

        logs.undo(logId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise WHERE id = ?", Integer.class, exerciseId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_muscle WHERE exercise_id = ?",
                Integer.class, exerciseId))
                .as("级联删掉了映射行，而快照里没有它——这就是契约里写明的缺口")
                .isZero();
    }

    @Test
    void undo_of_a_set_null_parent_row_silently_clears_the_referencing_column() {
        // meal.photo_id 是 ON DELETE SET NULL：撤销 photo 的创建会**静默**清空 meal.photo_id，
        // 被引用的那一侧没有任何报错可言。契约里写明这个后果。
        jdbc.update("INSERT INTO photo(taken_on, rel_path, sha256, kind) VALUES (?,?,?,?)",
                "2026-10-16", "photos/2026/10/16/changelog-test.png",
                "changelog-test-sha256", "food");
        long photoId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO meal(meal_date, slot, photo_id) VALUES (?,?,?)",
                "2026-10-16", "dinner", photoId);
        long mealId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        long logId = logs.recordInsert("photo", photoId,
                Map.of("rel_path", "photos/2026/10/16/changelog-test.png"), "agent", null);

        logs.undo(logId);

        assertThat(jdbc.queryForObject(
                "SELECT photo_id FROM meal WHERE id = ?", Long.class, mealId))
                .as("SET NULL 把这一格清空了，而回退过程一声不响")
                .isNull();
    }

    @Test
    void record_update_requires_a_before_snapshot() {
        long id = insertFood("测试食物F", 600);
        assertThatThrownBy(() -> logs.recordUpdate("food", id, null,
                Map.of("kcal_per100", 1.0), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before")
                // ⚠️ 上面那行 hasMessageContaining("before") 在本测试里**实际没有判别力**：
                // 真正先失败的是它前一行 isInstanceOf(IllegalArgumentException.class)——
                // 去掉 Java 侧校验后 DB 兜底抛的是 UncategorizedSQLException（M03 实测），
                // 类型断言先失败，消息断言根本执行不到。
                // （原先写的理由「DB 原文含 before_json，稀释了判别力」不对：DB 原文确实含
                // before_json，但那句话不是这条断言失效的原因，类型断言才是。）
                // 保留并补上「快照」是因为将来若有人放松类型断言，它是消息层面的真防线。
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
