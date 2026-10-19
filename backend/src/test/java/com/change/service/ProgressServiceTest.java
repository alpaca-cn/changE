package com.change.service;

import com.change.service.ProgressService.ExerciseProgress;
import com.change.service.ProgressService.Point;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进步曲线：一个动作按训练日的三条序列（e1rm / maxWeight / totalVolume）。
 *
 * <p>@Transactional 与其它服务测试一致：跑完回滚。每条用例都**自己造动作**
 * （名字带 {@code ZZ Progress} 前缀，撞不上共享库里的 876 条），断言只针对自己插入的行。
 * 这里**没有**一条「表里共有几条」「列表恰好空」这类全局断言：测试库是共享的，
 * 每次新上下文启动都可能有别处写进来的行，那种断言会在别处改动时莫名其妙地红。
 *
 * <p>期望值全是**手算的字面量**，不是用 {@code OneRepMax.epley} 现算的——
 * 拿被测的公式去算期望值，公式错了测试也跟着错。
 */
@SpringBootTest
@Transactional
class ProgressServiceTest {

    @Autowired ProgressService progress;
    @Autowired WorkoutService workouts;
    @Autowired JdbcTemplate jdbc;

    // ---- 造数据 ----

    private long newExercise(String nameEn) {
        return newExercise(nameEn, null);
    }

    private long newExercise(String nameEn, String nameZh) {
        jdbc.update("INSERT INTO exercise(name_en, name_zh, default_unit) VALUES (?, ?, 'kg')",
                nameEn, nameZh);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private static WorkoutService.SetInput set(Double weight, Integer repetitions, boolean completed) {
        return new WorkoutService.SetInput("normal", weight, repetitions, null, false,
                null, null, completed, null, null);
    }

    private static WorkoutService.SetInput perSideSet(Double weight, Integer repetitions) {
        return new WorkoutService.SetInput("normal", weight, repetitions, null, true,
                null, null, true, null, null);
    }

    private void record(LocalDate date, long exerciseId, WorkoutService.SetInput... sets) {
        workouts.recordSession(new WorkoutService.SessionInput(date, null, null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null, List.of(sets)))));
    }

    private static Point pointOn(ExerciseProgress p, LocalDate date) {
        return p.points().stream()
                .filter(point -> point.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 " + date + " 这个点：" + p.points()));
    }

    // ---- 三条序列 ----

    @Test
    void two_training_days_become_two_points_in_ascending_date_order_with_all_three_values() {
        long ex = newExercise("ZZ Progress Bench");
        // 故意先记晚的那天：升序不能靠「插入顺序」碰巧对
        record(LocalDate.of(2026, 10, 17), ex, set(65.0, 5, true), set(62.5, 6, true));
        record(LocalDate.of(2026, 10, 14), ex, set(60.0, 8, true), set(55.0, 10, true));

        ExerciseProgress p = progress.progress(ex, null, null);

        assertThat(p.exerciseId()).isEqualTo(ex);
        assertThat(p.nameEn()).isEqualTo("ZZ Progress Bench");
        assertThat(p.points()).extracting(Point::date)
                .containsExactly(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 17));

        Point first = p.points().get(0);
        // e1rm = max(60×(1+8/30)=76.00, 55×(1+10/30)=73.33)
        assertThat(first.e1rm()).isEqualTo(76.0);
        assertThat(first.maxWeight()).isEqualTo(60.0);
        assertThat(first.totalVolume()).isEqualTo(1030.0);   // 60×8 + 55×10

        Point second = p.points().get(1);
        assertThat(second.e1rm()).isEqualTo(75.83);          // 65×(1+5/30)=75.8333…
        assertThat(second.maxWeight()).isEqualTo(65.0);
        assertThat(second.totalVolume()).isEqualTo(700.0);   // 65×5 + 62.5×6
    }

    @Test
    void only_completed_sets_are_counted() {
        long ex = newExercise("ZZ Progress Completed");
        // 两条没做完的组，重量都远大于做完的那组：漏掉 completed 条件时三个值都会变
        record(LocalDate.of(2026, 10, 15), ex,
                set(60.0, 8, true),
                set(100.0, 3, false),
                set(120.0, 1, false));

        Point p = pointOn(progress.progress(ex, null, null), LocalDate.of(2026, 10, 15));

        // 把未完成的组算进去：e1rm 会变成 120.0（100×3=110.0、120×1=120.0）
        assertThat(p.e1rm()).isEqualTo(76.0);
        // 把未完成的组算进去：maxWeight 会变成 120.0
        assertThat(p.maxWeight()).isEqualTo(60.0);
        // 把未完成的组算进去：volume 会变成 480 + 300 + 120 = 900.0
        assertThat(p.totalVolume()).isEqualTo(480.0);
    }

    @Test
    void a_set_with_a_null_weight_or_null_repetitions_is_skipped() {
        long ex = newExercise("ZZ Progress SkipNull");
        record(LocalDate.of(2026, 10, 15), ex,
                set(60.0, 8, true),
                set(80.0, null, true),   // 没有次数（计时动作或漏填）
                set(null, 12, true));    // 没有重量

        Point p = pointOn(progress.progress(ex, null, null), LocalDate.of(2026, 10, 15));

        assertThat(p.e1rm()).isEqualTo(76.0);
        assertThat(p.maxWeight()).isEqualTo(60.0);
        assertThat(p.totalVolume()).isEqualTo(480.0);
    }

    @Test
    void a_single_rep_estimates_its_own_weight_not_the_epley_extrapolation() {
        long ex = newExercise("ZZ Progress Single");
        record(LocalDate.of(2026, 10, 15), ex, set(100.0, 1, true));

        Point p = pointOn(progress.progress(ex, null, null), LocalDate.of(2026, 10, 15));

        // 自己写 Epley 会得到 100×(1+1/30)=103.33——这是 OneRepMax 刻意纠正过的偏差
        assertThat(p.e1rm()).isEqualTo(100.0);
        assertThat(p.totalVolume()).isEqualTo(100.0);
    }

    // ---- 边界：那天估不出 e1rm ----

    @Test
    void a_day_whose_sets_cannot_be_estimated_keeps_the_point_with_a_null_e1rm() {
        long ex = newExercise("ZZ Progress Fifteen");
        // 15 次超出 OneRepMax.MAX_REPS=12：估不出来，但那天确实练了
        record(LocalDate.of(2026, 10, 15), ex, set(20.0, 15, true));

        ExerciseProgress p = progress.progress(ex, null, null);

        assertThat(p.points()).hasSize(1);
        Point day = p.points().get(0);
        assertThat(day.e1rm()).isNull();
        assertThat(day.maxWeight()).isEqualTo(20.0);
        assertThat(day.totalVolume()).isEqualTo(300.0);
    }

    // ---- 动作本身 ----

    @Test
    void an_exercise_that_was_never_trained_has_no_points_and_is_not_404() {
        long ex = newExercise("ZZ Progress Never", "从没练过");

        ExerciseProgress p = progress.progress(ex, null, null);

        assertThat(p.points()).isEmpty();
        assertThat(p.exerciseId()).isEqualTo(ex);
        assertThat(p.nameZh()).isEqualTo("从没练过");
        assertThat(p.nameEn()).isEqualTo("ZZ Progress Never");
    }

    @Test
    void an_unknown_exercise_is_404() {
        // 取一个**确定不存在**的 id：库是共享的，不能用「猜一个空号」
        long missing = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000 FROM exercise", Long.class);

        assertThatThrownBy(() -> progress.progress(missing, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("找不到这个动作")
                            .contains(String.valueOf(missing));
                });
    }

    @Test
    void an_exercise_without_a_chinese_name_still_works() {
        // 手工录入的动作可能没有中文名。name_zh 可空，读路径必须照常工作
        long ex = newExercise("ZZ Progress NoZh");
        record(LocalDate.of(2026, 10, 15), ex, set(60.0, 8, true));

        ExerciseProgress p = progress.progress(ex, null, null);

        assertThat(p.nameZh()).isNull();
        assertThat(p.nameEn()).isEqualTo("ZZ Progress NoZh");
        assertThat(p.points()).hasSize(1);
    }

    // ---- per_side ----

    @Test
    void a_per_side_set_keeps_the_recorded_weight_without_doubling_it() {
        long ex = newExercise("ZZ Progress PerSide");
        record(LocalDate.of(2026, 10, 15), ex, perSideSet(20.0, 10));

        Point p = pointOn(progress.progress(ex, null, null), LocalDate.of(2026, 10, 15));

        // 左右各记一组是用户的输入方式，不是「一组的重量要乘 2」
        assertThat(p.maxWeight()).isEqualTo(20.0);          // 乘 2 会是 40.0
        assertThat(p.totalVolume()).isEqualTo(200.0);       // 乘 2 会是 400.0
        assertThat(p.e1rm()).isEqualTo(26.67);              // 乘 2 会是 53.33
    }

    // ---- 日期过滤 ----

    @Test
    void from_and_to_are_closed_bounds_on_the_session_date() {
        long ex = newExercise("ZZ Progress Range");
        record(LocalDate.of(2026, 10, 10), ex, set(50.0, 8, true));
        record(LocalDate.of(2026, 10, 14), ex, set(55.0, 8, true));
        record(LocalDate.of(2026, 10, 17), ex, set(60.0, 8, true));

        assertThat(progress.progress(ex, null, null).points()).hasSize(3);

        assertThat(progress.progress(ex, "2026-10-14", "2026-10-17").points())
                .extracting(Point::date)
                .containsExactly(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 17));
        assertThat(progress.progress(ex, "2026-10-15", null).points())
                .extracting(Point::date)
                .containsExactly(LocalDate.of(2026, 10, 17));
        assertThat(progress.progress(ex, null, "2026-10-13").points())
                .extracting(Point::date)
                .containsExactly(LocalDate.of(2026, 10, 10));
        // 两侧都是闭区间：from=to=那一天
        assertThat(progress.progress(ex, "2026-10-14", "2026-10-14").points())
                .extracting(Point::date)
                .containsExactly(LocalDate.of(2026, 10, 14));
    }

    @Test
    void a_malformed_from_or_to_is_rejected_with_a_readable_message() {
        long ex = newExercise("ZZ Progress BadDate");

        assertThatThrownBy(() -> progress.progress(ex, "2026/10/14", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> progress.progress(ex, null, "2026-02-31"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD");
    }

    // ---- 分组 ----

    @Test
    void two_sessions_on_the_same_day_are_one_point() {
        long ex = newExercise("ZZ Progress SameDay");
        record(LocalDate.of(2026, 10, 15), ex, set(60.0, 8, true), set(50.0, 10, true));
        record(LocalDate.of(2026, 10, 15), ex, set(65.0, 5, true));

        ExerciseProgress p = progress.progress(ex, null, null);

        assertThat(p.points()).hasSize(1);
        Point day = p.points().get(0);
        assertThat(day.e1rm()).isEqualTo(76.0);          // max(76.00, 66.67, 75.83)
        assertThat(day.maxWeight()).isEqualTo(65.0);
        assertThat(day.totalVolume()).isEqualTo(1305.0); // 480 + 500 + 325
    }
}
