package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
        // 共享库里可能有别的测试提交过的 user 日志（ChangeLogJoinTableTest 就不回滚）。
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
