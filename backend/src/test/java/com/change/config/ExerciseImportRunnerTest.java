package com.change.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动钩子真的会导入——这条测的是「接线」，不是「导入逻辑」（后者在
 * {@link com.change.service.ExerciseImportServiceTest}）。
 *
 * <p><b>为什么单独一个类、单独一个库文件</b>：测试套件默认把启动导入**关掉**了
 * （见 {@code src/test/resources/application.properties}），否则 876 行会污染共享库。
 * 要验证「开着的时候真的会导入」，只能另起一个上下文、把开关打开，并用一个**独立库文件**
 * 把它的影响限制在自己身上（同 {@code ChangeLogServiceUndoTransactionTest} 的做法）。
 *
 * <p>它验证的是计划 2 · Task 8 核对清单第 1 条缺的那一环：干净库上启动后，
 * {@code exercise} 应当是 876 而不是 0。
 *
 * <p>库文件在 {@code target/data} 下且**跨运行持久**，所以再跑一次时导入钩子会全部跳过——
 * 断言 876（不是 1752）顺带钉住了这一点：启动导入是幂等的，不会随重启翻倍。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "change.exercise.import-on-startup=true",
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/import-runner.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class ExerciseImportRunnerTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void the_application_imports_the_exercise_library_when_it_starts() {
        assertThat(scalar("SELECT COUNT(*) FROM exercise WHERE source_id LIKE 'free-exercise-db:%'"))
                .as("启动后动作库应当已经导入（而不是像接线之前那样为 0）")
                .isEqualTo(876);

        assertThat(scalar("SELECT COUNT(*) FROM exercise_muscle em "
                + "JOIN exercise e ON e.id = em.exercise_id "
                + "WHERE e.source_id LIKE 'free-exercise-db:%'"))
                .as("肌群标注应当有 2741 条")
                .isEqualTo(2741);

        assertThat(scalar("SELECT COUNT(*) FROM muscle_group"))
                // V7 的 17 块 + V11 的 10 个子部位
                .as("27 块肌群来自 V7 与 V11 种子迁移")
                .isEqualTo(27);

        assertThat(scalar("SELECT COUNT(*) FROM change_log "
                + "WHERE origin = 'import' AND target_table = 'exercise'"))
                .as("导入的动作行本身也要进审计日志")
                .isEqualTo(876);
        assertThat(scalar("SELECT COUNT(*) FROM change_log "
                + "WHERE origin = 'import' AND target_table = 'exercise_muscle'"))
                .as("导入的 join 行也要进审计日志")
                .isEqualTo(2741);
    }

    private int scalar(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
