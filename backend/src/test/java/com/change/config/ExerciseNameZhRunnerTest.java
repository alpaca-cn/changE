package com.change.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动后动作库带中文名——这条测的是「接线」，不是「写名字的规则」
 * （后者在 {@link com.change.service.ExerciseNameZhServiceTest}）。
 *
 * <p>单独一个类、单独一个库文件，理由同 {@code ExerciseImportRunnerTest}：测试套件默认把启动
 * 导入/写入关掉，要验证「开着时真的会写」，只能另起上下文并把开关打开。
 *
 * <p>它同时验证顺序：中文名要写在**已导入**的动作上，所以 {@code ExerciseNameZhRunner}
 * 必须排在 {@code ExerciseImportRunner} 之后（{@code @Order(2)} vs {@code @Order(1)}）。
 * 顺序错了这里就是 0 行被写入。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "change.exercise.import-on-startup=true",
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/name-zh-runner.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class ExerciseNameZhRunnerTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void the_application_writes_chinese_names_on_startup() {
        assertThat(scalar("SELECT COUNT(*) FROM exercise "
                + "WHERE source_id LIKE 'free-exercise-db:%' AND name_zh IS NOT NULL"))
                .as("876 个导入动作都应当拿到中文名")
                .isEqualTo(876);

        assertThat(scalar("SELECT COUNT(DISTINCT name_zh) FROM exercise "
                + "WHERE source_id LIKE 'free-exercise-db:%'"))
                .as("中文名不能有重复")
                .isEqualTo(876);

        assertThat(jdbc.queryForObject(
                "SELECT name_zh FROM exercise WHERE name_en = 'Plank'", String.class))
                .as("抽查一个众所周知的名字")
                .isEqualTo("平板支撑");

        assertThat(jdbc.queryForObject(
                "SELECT name_zh FROM exercise WHERE name_en = 'Barbell Bench Press - Medium Grip'",
                String.class))
                .as("抽查一个带限定词的名字")
                .isEqualTo("中握距杠铃卧推");
    }

    private int scalar(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
