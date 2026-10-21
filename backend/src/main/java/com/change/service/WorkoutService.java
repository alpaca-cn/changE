package com.change.service;

import com.change.cache.CacheService;
import com.change.domain.Exercise;
import com.change.domain.SessionExercise;
import com.change.domain.WorkoutSession;
import com.change.domain.WorkoutSet;
import com.change.repo.ExerciseRepo;
import com.change.repo.SessionExerciseRepo;
import com.change.repo.WorkoutSessionRepo;
import com.change.repo.WorkoutSetRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 写一次训练。
 *
 * <p><b>三层一个事务。</b>训练 → 动作 → 组任何一步失败就整批回滚：半写入的一次训练比没记更糟
 * （用户以为记好了，其实少了几个动作或几组，而且他不会知道少了哪个）。
 *
 * <p><b>每一行都进审计（{@code origin="user"}）。</b>与动作库导入（计划 2）同一套思路：
 * 「这次改动动了什么」要答得出来，也才回退得动。父行与子行**都**记——只记父行的话，
 * 回退父行会级联删掉子行，而日志里没有东西让它们回来（见 {@link ChangeLogService} 的类注释）。
 *
 * <p><b>快照里的日期/时间戳一律 {@code toString()} 成字符串。</b>{@link ChangeLogService} 用的是
 * 裸 {@code new ObjectMapper()}（没注册 JavaTimeModule），把 {@code LocalDate} 放进去会在
 * 序列化时抛异常——那会把一次正常的训练记录整个弄失败。布尔值同理用 0/1 整数
 * （与计划 2 的 {@code is_compound} 一致，回退时的类型绑定也更确定）。
 */
@Service
public class WorkoutService {

    private static final String USER_ORIGIN = "user";

    /** 一组要写的数据。除组类型外都允许为 null（「还没记」是一个真实状态）。 */
    public record SetInput(String setType, Double weight, Integer repetitions, Integer durationSeconds,
                           boolean perSide, Double rir, Integer restSeconds, boolean completed,
                           Double targetWeight, Integer targetReps) {}

    /** 训练里的一个动作。{@code sets} 至少一条。 */
    public record ExerciseInput(Long exerciseId, String note, String supersetGroup, List<SetInput> sets) {}

    /** 一次训练。{@code exercises} 至少一个。 */
    public record SessionInput(LocalDate sessionDate, String title, String perceivedState, String note,
                               LocalDateTime startedAt, LocalDateTime endedAt,
                               List<ExerciseInput> exercises) {}

    /** 写入结果。 */
    public record Result(long sessionId, int exercises, int sets) {}

    /** 训练列表里的一行。 */
    public record SessionSummary(long id, LocalDate sessionDate, String title, String perceivedState) {}

    /** 一组（读回用，带 id）。 */
    public record SetDetail(long id, int setIndex, String setType, Double weight, Integer repetitions,
                            Integer durationSeconds, boolean perSide, Double rir, Integer restSeconds,
                            boolean completed, Double targetWeight, Integer targetReps) {}

    /** 训练里的一个动作（读回用，带上动作名）。 */
    public record ExerciseDetail(long sessionExerciseId, long exerciseId, String nameZh, String nameEn,
                                 int orderIndex, String note, String supersetGroup, List<SetDetail> sets) {}

    /** 一次训练的完整内容。 */
    public record SessionDetail(long id, LocalDate sessionDate, String title, String perceivedState,
                                String note, LocalDateTime startedAt, LocalDateTime endedAt,
                                List<ExerciseDetail> exercises) {}

    private final WorkoutSessionRepo sessions;
    private final SessionExerciseRepo sessionExercises;
    private final WorkoutSetRepo sets;
    private final ExerciseRepo exercises;
    private final ChangeLogService changeLog;
    private final CacheService cache;

    public WorkoutService(WorkoutSessionRepo sessions, SessionExerciseRepo sessionExercises,
                          WorkoutSetRepo sets, ExerciseRepo exercises, ChangeLogService changeLog,
                          CacheService cache) {
        this.sessions = sessions;
        this.sessionExercises = sessionExercises;
        this.sets = sets;
        this.exercises = exercises;
        this.changeLog = changeLog;
        this.cache = cache;
    }

    /** 记一次完整训练。 */
    @Transactional
        /**
     * 用户来源的写入（老签名，保持不动）。
     *
     * <p>转调带 origin 的版本。留这个重载是为了不让现有调用方与测试为了一个
     * 参数全面改动；语义与以前完全一致。
     */
    public Result recordSession(SessionInput input) {
        return recordSession(input, USER_ORIGIN);
    }

    /** 带来源的版本：智能体写数据时传 "agent"。 */
    public Result recordSession(SessionInput input, String origin) {
        requireValid(input);

        WorkoutSession session = new WorkoutSession();
        session.sessionDate = input.sessionDate();
        session.startedAt = input.startedAt();
        session.endedAt = input.endedAt();
        session.title = input.title();
        session.perceivedState = input.perceivedState();
        session.note = input.note();
        sessions.save(session);
        changeLog.recordInsert("workout_session", session.id, sessionSnapshot(session), origin, null);

        int exerciseCount = 0;
        int setCount = 0;
        for (int i = 0; i < input.exercises().size(); i++) {
            ExerciseInput ei = input.exercises().get(i);
            SessionExercise se = new SessionExercise();
            se.sessionId = session.id;
            se.exerciseId = ei.exerciseId();
            se.orderIndex = i;
            se.note = ei.note();
            se.supersetGroup = ei.supersetGroup();
            sessionExercises.save(se);
            changeLog.recordInsert("session_exercise", se.id, sessionExerciseSnapshot(se), origin, null);
            exerciseCount++;

            for (int j = 0; j < ei.sets().size(); j++) {
                WorkoutSet ws = toSet(se.id, j, ei.sets().get(j));
                sets.save(ws);
                changeLog.recordInsert("workout_set", ws.id, setSnapshot(ws), origin, null);
                setCount++;
            }
        }

        // 缓存失效：写训练会让进步曲线变旧，按前缀清掉（计划 6a Task 4）。
        //
        // 落点是**本方法内最后一步、return 之前**。@Transactional 的提交发生在方法返回时，
        // 所以「提交 → 失效」的先后成立。顺序的理由：事务回滚了却失效了缓存，只是白清一次
        // （无害）；反过来（先失效、后回滚）会让缓存里留着该失效的值（有害）。
        //
        // 前缀引用 ProgressService 的同一个常量，而不是再写一遍 "progress:"：
        // 两处字符串一旦漂移，失效会静默地什么都没清掉，而测试也可能因为各测各的而看不出来。
        //
        // CacheService 约定任何方法都不向上抛异常，所以这里不需要 try/catch。
        cache.invalidatePrefix(ProgressService.CACHE_PREFIX);

        return new Result(session.id, exerciseCount, setCount);
    }

    /** 最近 5 次训练，新的在前（首页用）。 */
    public List<SessionSummary> recentSessions() {
        return sessions.findTop5ByOrderBySessionDateDesc().stream()
                .map(s -> new SessionSummary(s.id, s.sessionDate, s.title, s.perceivedState))
                .toList();
    }

    /** 一次训练的完整内容；找不到返回 {@link Optional#empty()}（调用方翻译成 404）。 */
    public Optional<SessionDetail> sessionDetail(long sessionId) {
        Optional<WorkoutSession> found = sessions.findById(sessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorkoutSession s = found.get();
        List<SessionExercise> ses = sessionExercises.findBySessionIdOrderByOrderIndexAsc(sessionId);

        Map<Long, Exercise> exerciseById = exercises
                .findAllById(ses.stream().map(se -> se.exerciseId).toList()).stream()
                .collect(Collectors.toMap(e -> e.id, e -> e));

        List<ExerciseDetail> details = new ArrayList<>();
        for (SessionExercise se : ses) {
            Exercise e = exerciseById.get(se.exerciseId);
            List<SetDetail> setDetails = sets.findBySessionExerciseIdOrderBySetIndexAsc(se.id)
                    .stream().map(WorkoutService::toSetDetail).toList();
            details.add(new ExerciseDetail(se.id, se.exerciseId,
                    e == null ? null : e.nameZh, e == null ? null : e.nameEn,
                    se.orderIndex, se.note, se.supersetGroup, setDetails));
        }
        return Optional.of(new SessionDetail(s.id, s.sessionDate, s.title, s.perceivedState,
                s.note, s.startedAt, s.endedAt, details));
    }

    private static SetDetail toSetDetail(WorkoutSet ws) {
        return new SetDetail(ws.id, ws.setIndex, ws.setType, ws.weight, ws.repetitions,
                ws.durationSeconds, ws.perSide, ws.rir, ws.restSeconds, ws.completed,
                ws.targetWeight, ws.targetReps);
    }

    private static WorkoutSet toSet(long sessionExerciseId, int setIndex, SetInput in) {
        WorkoutSet ws = new WorkoutSet();
        ws.sessionExerciseId = sessionExerciseId;
        ws.setIndex = setIndex;
        ws.setType = in.setType() == null ? "normal" : in.setType();
        ws.weight = in.weight();
        ws.repetitions = in.repetitions();
        ws.durationSeconds = in.durationSeconds();
        ws.perSide = in.perSide();
        ws.rir = in.rir();
        ws.restSeconds = in.restSeconds();
        ws.completed = in.completed();
        ws.targetWeight = in.targetWeight();
        ws.targetReps = in.targetReps();
        return ws;
    }

    private static void requireValid(SessionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("训练内容不能为空");
        }
        if (input.sessionDate() == null) {
            throw new IllegalArgumentException("训练日期不能为空");
        }
        if (input.exercises() == null || input.exercises().isEmpty()) {
            throw new IllegalArgumentException("一次训练至少要有 1 个动作");
        }
        for (ExerciseInput ei : input.exercises()) {
            if (ei == null || ei.exerciseId() == null) {
                throw new IllegalArgumentException("每个动作都必须带 exerciseId");
            }
            if (ei.sets() == null || ei.sets().isEmpty()) {
                throw new IllegalArgumentException("动作 " + ei.exerciseId() + " 至少要有一组");
            }
        }
    }

    private static Map<String, Object> sessionSnapshot(WorkoutSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_date", s.sessionDate.toString());
        m.put("started_at", s.startedAt == null ? null : s.startedAt.toString());
        m.put("ended_at", s.endedAt == null ? null : s.endedAt.toString());
        m.put("title", s.title);
        m.put("perceived_state", s.perceivedState);
        m.put("note", s.note);
        return m;
    }

    private static Map<String, Object> sessionExerciseSnapshot(SessionExercise se) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", se.sessionId);
        m.put("exercise_id", se.exerciseId);
        m.put("order_index", se.orderIndex);
        m.put("note", se.note);
        m.put("superset_group", se.supersetGroup);
        return m;
    }

    private static Map<String, Object> setSnapshot(WorkoutSet ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_exercise_id", ws.sessionExerciseId);
        m.put("set_index", ws.setIndex);
        m.put("set_type", ws.setType);
        m.put("weight", ws.weight);
        m.put("repetitions", ws.repetitions);
        m.put("duration_seconds", ws.durationSeconds);
        m.put("per_side", ws.perSide ? 1 : 0);
        m.put("rir", ws.rir);
        m.put("rest_seconds", ws.restSeconds);
        m.put("completed", ws.completed ? 1 : 0);
        m.put("target_weight", ws.targetWeight);
        m.put("target_reps", ws.targetReps);
        return m;
    }
}
