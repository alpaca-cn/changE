package com.change.service;

import com.change.domain.WorkoutSet;
import com.change.repo.WorkoutSetRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「这个动作上次练了多重、每次几次」——给「记一组」时带出默认值用。
 *
 * <p><b>为什么用 JdbcTemplate 而不是派生查询。</b>要问的是「**包含这个动作的、最近的那次训练**」，
 * 排序键（{@code workout_session.session_date}）在另一张表上，而这两张表之间没有 JPA 关联
 * （本项目一律用 Long 存外键 id）。派生方法名表达不了跨表排序，所以走一条 SQL；
 * 组本身仍用 {@link WorkoutSetRepo} 的派生查询取（那部分能派生就派生）。
 */
@Service
public class LastPerformanceService {

    /** 供预填的一组：只有「记一组」需要的字段。 */
    public record SetSnapshot(String setType, Double weight, Integer repetitions,
                              Integer durationSeconds, boolean perSide) {}

    /** 上次表现：那次训练的日期 + 那个动作的组。 */
    public record LastPerformance(LocalDate sessionDate, long sessionExerciseId, List<SetSnapshot> sets) {}

    private static final String LATEST_SESSION_EXERCISE_SQL =
            "SELECT se.id AS se_id, s.session_date AS session_date "
                    + "FROM session_exercise se "
                    + "JOIN workout_session s ON s.id = se.session_id "
                    + "WHERE se.exercise_id = ? "
                    + "ORDER BY s.session_date DESC, s.id DESC, se.order_index ASC "
                    + "LIMIT 1";

    private final JdbcTemplate jdbc;
    private final WorkoutSetRepo sets;

    public LastPerformanceService(JdbcTemplate jdbc, WorkoutSetRepo sets) {
        this.jdbc = jdbc;
        this.sets = sets;
    }

    /** 从没做过这个动作时返回 {@link Optional#empty()}（调用方翻译成 204）。 */
    public Optional<LastPerformance> lastPerformance(long exerciseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(LATEST_SESSION_EXERCISE_SQL, exerciseId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long sessionExerciseId = ((Number) rows.get(0).get("se_id")).longValue();
        LocalDate date = LocalDate.parse((String) rows.get(0).get("session_date"));

        List<SetSnapshot> snapshots = sets.findBySessionExerciseIdOrderBySetIndexAsc(sessionExerciseId)
                .stream()
                .map(LastPerformanceService::toSnapshot)
                .toList();
        return Optional.of(new LastPerformance(date, sessionExerciseId, snapshots));
    }

    private static SetSnapshot toSnapshot(WorkoutSet ws) {
        return new SetSnapshot(ws.setType, ws.weight, ws.repetitions, ws.durationSeconds, ws.perSide);
    }
}
