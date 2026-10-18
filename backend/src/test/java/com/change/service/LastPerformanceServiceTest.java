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
