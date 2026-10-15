package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 十五张业务表的验收。
 *
 * 为什么用一张「期望清单」而不是逐个断言表存在：逐个断言只能发现「少建了」，
 * 发现不了「多建了」——多出来的表（比如 V1 的探针表没被清掉）会让下一个人以为
 * 它是模型的一部分。containsExactlyInAnyOrderElementsOf 两侧都收口。
 *
 * 另外两条是针对设计约束的防回归断言，不是「顺便再查一下」：
 * meal_item 里一旦出现 kcal，营养就出现了两个真相（per-100g 与绝对热量），
 * 而 per-100g 才是改份量重算的依据；workout_set 少了 duration_seconds / per_side，
 * 平板支撑与弓步就记不进库——这两条都是设计文档点名的边界，值得钉在测试里。
 *
 * 这一轮不再需要「排除 schema_probe」那个特例：V2 的第一行已经把它删掉了，
 * 它现在应当不存在，而且有一条断言专门盯着这件事。
 */
@SpringBootTest
class SchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private static final Set<String> EXPECTED = Set.of(
            "exercise", "workout_session", "session_exercise", "workout_set",
            "muscle_group", "exercise_muscle",
            "food", "food_portion", "meal", "meal_item",
            "photo", "body_metric", "chat_session", "chat_message", "change_log");

    @Test
    void all_fifteen_tables_exist() {
        // sqlite_% 排除的是 SQLite 自己维护的表（AUTOINCREMENT 会建 sqlite_sequence）；
        // flyway_schema_history 是迁移框架的历史表，不属于业务模型。
        // 这里刻意不排除 schema_probe：它若还在，这条断言就该红。
        List<String> names = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'", String.class);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void probe_table_was_dropped_by_v2() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='schema_probe'",
                Integer.class);
        assertThat(n)
                .as("V2 的第一行应当 DROP 掉 V1 的探针表，让它不再留在用户的库里")
                .isZero();
    }

    @Test
    void meal_item_has_no_stored_calorie_column() {
        // 这条是防回归：一旦有人「顺手」加了 kcal 列，测试立刻失败。
        // 走 pragma_table_info 而不是 PRAGMA table_info：后者是「一行多列」的表函数，
        // queryForList(..., String.class) 会对它抛 IncorrectResultSizeDataAccessException，
        // 断言就永远走不到 doesNotContain 那一行。
        Set<String> cols = new HashSet<>(
                jdbc.queryForList("SELECT name FROM pragma_table_info('meal_item')", String.class));
        assertThat(cols).doesNotContain("kcal", "calories", "energy");
    }

    @Test
    void workout_set_supports_timed_and_per_side() {
        Set<String> cols = new HashSet<>(
                jdbc.queryForList("SELECT name FROM pragma_table_info('workout_set')", String.class));
        assertThat(cols).contains("duration_seconds", "per_side", "target_weight", "target_reps");
    }
}
