package com.change.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动后动作库带中文做法——这条测的是「接线」，不是「写译文的规则」
 * （后者在 {@link com.change.service.ExerciseInstructionsZhServiceTest}）。
 *
 * <p>单独一个类、单独一个库文件，理由同 {@code ExerciseImportRunnerTest} / {@code ExerciseNameZhRunnerTest}：
 * 测试套件默认把启动导入/写入关掉，要验证「开着时真的会写」，只能另起上下文并把开关打开。
 *
 * <p>它同时验证顺序：做法要写在**已导入**的动作上，所以
 * {@code ExerciseInstructionsZhRunner} 必须排在 {@code ExerciseImportRunner} 之后
 * （{@code @Order(3)} vs {@code @Order(1)}）。顺序错了这里就是 0 行被写入。
 *
 * <p>库文件在 {@code target/data} 下且跨运行持久：再跑一次时三个写入器都会跳过，
 * 断言 871（不是 1742）顺带钉住了启动写入是幂等的。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "change.exercise.import-on-startup=true",
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/instructions-zh-runner.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class ExerciseInstructionsZhRunnerTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void the_application_writes_chinese_instructions_on_startup() {
        String imported = "source_id LIKE 'free-exercise-db:%'";

        assertThat(scalar("SELECT COUNT(*) FROM exercise WHERE " + imported))
                .as("启动后动作库应当已经导入")
                .isEqualTo(876);

        assertThat(scalar("SELECT COUNT(*) FROM exercise WHERE " + imported
                + " AND instructions_zh IS NOT NULL"))
                .as("876 条里有英文原文的 871 条应当都拿到中文做法")
                .isEqualTo(871);

        assertThat(scalar("SELECT COUNT(*) FROM exercise WHERE " + imported
                + " AND instructions IS NULL"))
                .as("数据集里英文原文为空的 5 条")
                .isEqualTo(5);

        assertThat(scalar("SELECT COUNT(*) FROM exercise WHERE " + imported
                + " AND instructions IS NULL AND instructions_zh IS NOT NULL"))
                .as("没有英文原文的 5 条不该被编出一段中文")
                .isZero();

        // 中文名与中文做法由两个 Runner 分别写入；后者排在 @Order(2) 之后。
        // 这里同时断言两者都写好了，顺序错了（做法跑在导入之前）就会是 0 行。
        assertThat(scalar("SELECT COUNT(*) FROM exercise WHERE " + imported
                + " AND name_zh IS NOT NULL"))
                .as("同一次启动里中文名也应当就绪")
                .isEqualTo(876);

        // 真实数据特性：Straight-Arm Dumbbell Pullover 的第 3 段内部自带一个换行。
        // 6 段拼起来 = 5 个段间换行 + 1 个段内换行 = 6 个换行 / 7 行。
        String pullover = jdbc.queryForObject(
                "SELECT instructions_zh FROM exercise WHERE name_en = 'Straight-Arm Dumbbell Pullover'",
                String.class);
        assertThat(pullover).as("抽查一个能确认段内换行保留的条目").isNotNull();
        assertThat(pullover.split("\n", -1)).as("段内换行不能被压平").hasSize(7);
        assertThat(pullover).contains("。\n注意：");
    }

    private int scalar(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
