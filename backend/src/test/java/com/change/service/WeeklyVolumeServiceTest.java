package com.change.service;

import com.change.repo.MuscleGroupRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本周容量：主 +1、次 +0.5，自然周（周一起算），只算已完成的组。
 *
 * <p>2026-10-14 是**周三**（2026-10-18 是周日 → 那周周一是 2026-10-12）。
 * 用例用固定日期而不是「今天」，否则测试会随运行日期换周、结果漂移。
 */
@SpringBootTest
@Transactional
class WeeklyVolumeServiceTest {

    @Autowired WeeklyVolumeService weeklyVolume;
    @Autowired WorkoutService workouts;
    @Autowired MuscleGroupRepo muscleGroups;
    @Autowired JdbcTemplate jdbc;

    private long muscleId(String nameEn) {
        return muscleGroups.findByNameEn(nameEn).orElseThrow().id;
    }

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void link(long exerciseId, long muscleGroupId, String role) {
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?,?,?)",
                exerciseId, muscleGroupId, role);
    }

    private void record(LocalDate date, long exerciseId, int completedSets, int unfinishedSets) {
        List<WorkoutService.SetInput> sets = new ArrayList<>();
        for (int i = 0; i < completedSets; i++) {
            sets.add(new WorkoutService.SetInput("normal", 60.0, 8, null, false, null, null, true, null, null));
        }
        for (int i = 0; i < unfinishedSets; i++) {
            sets.add(new WorkoutService.SetInput("normal", null, null, null, false, null, null, false, null, null));
        }
        workouts.recordSession(new WorkoutService.SessionInput(date, null, null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null, sets))));
    }

    @Test
    void counts_primary_one_secondary_half_and_only_completed_sets_in_the_week() {
        long ex = newExercise("ZZ Vol Bench");
        // chest 被拆开，这个动作（无 incline/decline 线索）落到中胸
        link(ex, muscleId("chest-middle"), "primary");
        link(ex, muscleId("triceps"), "secondary");

        LocalDate wednesday = LocalDate.of(2026, 10, 14);
        record(wednesday, ex, 3, 1);                        // 3 完成 + 1 未完成 → 只算 3
        record(LocalDate.of(2026, 10, 11), ex, 1, 0);       // 上一周（周日）→ 不算

        WeeklyVolumeService.WeeklyVolume v = weeklyVolume.weekly(wednesday);

        assertThat(v.weekStart()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(v.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 18));
        // 2026-10-23: 拆分后 17 个顶层 + 10 个子部位
        assertThat(v.muscles()).hasSize(27);

        assertThat(muscleSets(v, "chest-middle")).isEqualTo(3.0);
        // 父组 = 子部位之和，所以 chest 也是 3.0
        assertThat(muscleSets(v, "chest")).isEqualTo(3.0);
        assertThat(muscleSets(v, "triceps")).isEqualTo(1.5);
    }

    @Test
    void untrained_and_unconfigured_muscles_report_the_right_status() {
        WeeklyVolumeService.WeeklyVolume v = weeklyVolume.weekly(LocalDate.of(2026, 10, 14));

        assertThat(muscleStatus(v, "abdominals"))
                .isEqualTo(com.change.calc.VolumeCalculator.Status.UNTRAINED);
        assertThat(muscleStatus(v, "neck"))
                .isEqualTo(com.change.calc.VolumeCalculator.Status.NOT_CONFIGURED);
    }

    private double muscleSets(WeeklyVolumeService.WeeklyVolume v, String nameEn) {
        return v.muscles().stream()
                .filter(m -> m.nameEn().equals(nameEn)).findFirst().orElseThrow().sets();
    }

    private com.change.calc.VolumeCalculator.Status muscleStatus(WeeklyVolumeService.WeeklyVolume v, String nameEn) {
        return v.muscles().stream()
                .filter(m -> m.nameEn().equals(nameEn)).findFirst().orElseThrow().status();
    }
}
