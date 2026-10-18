package com.change.service;

import com.change.calc.VolumeCalculator;
import com.change.domain.MuscleGroup;
import com.change.repo.MuscleGroupRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 「本周每个肌群折算多少组」。
 *
 * <p>口径：每记一组，该动作主要肌群 +1、次要肌群 +0.5（{@link VolumeCalculator}）；按自然周
 * （周一起算）汇总；<b>只算 {@code completed = 1} 的组</b>——没打勾的组是「还没做/没记」，
 * 不是训练量。
 *
 * <p>返回**全部 17 块肌群**（含 0 组的）：设计文档要求首页把「腹/核心本周 0 组」这类提醒
 * 摆在最显眼的位置，所以「没练」必须以 0 出现，而不是从列表里消失。
 */
@Service
public class WeeklyVolumeService {

    /** 一块肌群的周容量。{@code status} 由 {@link VolumeCalculator#classify} 给出。 */
    public record MuscleVolume(long muscleGroupId, String nameZh, String nameEn, double sets,
                               Integer min, Integer max, VolumeCalculator.Status status) {}

    /** 一周的容量。 */
    public record WeeklyVolume(LocalDate weekStart, LocalDate weekEnd, List<MuscleVolume> muscles) {}

    private static final String ENTRIES_SQL =
            "SELECT em.muscle_group_id AS muscle_id, em.role AS role, COUNT(*) AS sets "
                    + "FROM workout_set ws "
                    + "JOIN session_exercise se ON se.id = ws.session_exercise_id "
                    + "JOIN workout_session s ON s.id = se.session_id "
                    + "JOIN exercise_muscle em ON em.exercise_id = se.exercise_id "
                    + "WHERE s.session_date BETWEEN ? AND ? AND ws.completed = 1 "
                    + "GROUP BY em.muscle_group_id, em.role";

    private final JdbcTemplate jdbc;
    private final MuscleGroupRepo muscleGroups;

    public WeeklyVolumeService(JdbcTemplate jdbc, MuscleGroupRepo muscleGroups) {
        this.jdbc = jdbc;
        this.muscleGroups = muscleGroups;
    }

    /** {@code anyDay} 所在的那一周（周一到周日）。 */
    public WeeklyVolume weekly(LocalDate anyDay) {
        LocalDate start = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(6);

        List<VolumeCalculator.Entry> entries = jdbc.query(ENTRIES_SQL,
                (rs, rowNum) -> new VolumeCalculator.Entry(
                        rs.getLong("muscle_id"), rs.getInt("sets"), rs.getString("role")),
                start.toString(), end.toString());

        Map<Long, Double> setsByMuscle = VolumeCalculator.accumulate(entries);

        List<MuscleVolume> muscles = muscleGroups.findAll().stream()
                .sorted(Comparator.comparing(g -> g.id))
                .map(g -> toMuscleVolume(g, setsByMuscle))
                .toList();

        return new WeeklyVolume(start, end, muscles);
    }

    private static MuscleVolume toMuscleVolume(MuscleGroup g, Map<Long, Double> setsByMuscle) {
        double sets = setsByMuscle.getOrDefault(g.id, 0.0);   // 缺键 = 0 组
        return new MuscleVolume(g.id, g.nameZh, g.nameEn, sets, g.weeklySetsMin, g.weeklySetsMax,
                VolumeCalculator.classify(sets, g.weeklySetsMin, g.weeklySetsMax));
    }
}
