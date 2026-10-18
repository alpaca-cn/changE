package com.change.service;

import com.change.domain.Exercise;
import com.change.domain.ExerciseMuscle;
import com.change.repo.ExerciseMuscleRepo;
import com.change.repo.ExerciseRepo;
import com.change.repo.MuscleGroupRepo;
import com.change.seed.FreeExerciseDb;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 vendor 的 free-exercise-db（876 条）导入动作库。
 *
 * <h2>三条设计约束</h2>
 *
 * <p><b>1. 整个导入在一个事务里。</b>876 条动作 + 2587 条肌群标注，任何一条失败就整批回滚。
 * 半导入的库比没导入更糟：用户会以为「动作库有 500 条」，而其中一半的动作没有肌群标注
 * （也就是选择器里点不亮、容量算不出来），且没有任何东西告诉他少了什么。
 *
 * <p><b>2. 幂等：靠 {@code source_id} 的跳过，而不是 {@code INSERT OR REPLACE}。</b>
 * 每次导入先读出库里已有的 {@code source_id}，命中的行整条跳过。用 OR REPLACE 会静默改掉
 * 已有行——包括用户可能已经改过的字段，而且不会报错。
 *
 * <p><b>3. 导入写入的每一行都进审计日志，{@code origin='import'}。</b>876 条动作 + 2587 条
 * 肌群标注 = 3463 条日志，这是**故意的**：它是「这次导入动了什么」的唯一证据，也是为什么
 * 「撤销导入」能作为一次意图明确的操作被追溯（Task 5 的 {@code unimport()} 不走回放，
 * 但那条 delete 汇总日志同样进这里）。
 *
 * <p><b>为什么动作行也要记日志</b>：只记 join 行的话，「这 876 个动作从哪来的」答不出来。
 * 分开 {@code import} / {@code user} / {@code agent} 三种 origin 之后，界面才能把
 * 「一次导入的 3463 条改动」与「你手改的 3 条」分组显示。
 */
@Service
public class ExerciseImportService {

    /** 与 {@link FreeExerciseDb#SOURCE_PREFIX} 同一个命名空间，撤销时按它前缀匹配。 */
    public static final String SOURCE_PREFIX = FreeExerciseDb.SOURCE_PREFIX;

    /** 导入产生的日志来源。必须是 {@code change_log.origin} 的 CHECK 里列出的值。 */
    private static final String ORIGIN = "import";

    private final ExerciseRepo exercises;
    private final ExerciseMuscleRepo exerciseMuscles;
    private final MuscleGroupRepo muscleGroups;
    private final ChangeLogService changeLog;
    private final JdbcTemplate jdbc;

    public ExerciseImportService(ExerciseRepo exercises,
                                 ExerciseMuscleRepo exerciseMuscles,
                                 MuscleGroupRepo muscleGroups,
                                 ChangeLogService changeLog,
                                 JdbcTemplate jdbc) {
        this.exercises = exercises;
        this.exerciseMuscles = exerciseMuscles;
        this.muscleGroups = muscleGroups;
        this.changeLog = changeLog;
        this.jdbc = jdbc;
    }

    /** 一次导入的结果，供调用方（启动日志、界面）报告。 */
    public record Result(int inserted, int skipped, int musclesInserted) {}

    /**
     * 把整个数据集导入库。可重复调用：已存在（按 {@code source_id}）的动作会被跳过。
     *
     * <p>{@code @Transactional} 在这里是核心要求而不是装饰——它保证「要么全进、要么全不进」。
     * 传播用默认（REQUIRED）：连接池只有 1 条连接，REQUIRES_NEW 会自己等自己。
     */
    @Transactional
    public Result importAll() {
        List<FreeExerciseDb.Row> rows = FreeExerciseDb.load();
        Set<String> existing = existingSourceIds();

        int inserted = 0;
        int skipped = 0;
        int musclesInserted = 0;

        for (FreeExerciseDb.Row row : rows) {
            if (existing.contains(row.sourceId)) {
                skipped++;
                continue;
            }

            Exercise exercise = persistExercise(row);
            // 先记动作本身，再记它的肌群标注——顺序与真实写入顺序一致，
            // 这样「逆序 undo 全部日志」能精确回到导入之前。
            changeLog.recordInsert("exercise", exercise.id, exerciseSnapshot(row), ORIGIN, null);
            inserted++;

            for (FreeExerciseDb.Assignment assignment : row.muscleAssignments()) {
                long muscleId = resolveMuscleId(row, assignment);
                ExerciseMuscle link = persistLink(exercise.id, muscleId, assignment.role);
                changeLog.recordInsert("exercise_muscle", link.id,
                        Map.of("exercise_id", exercise.id,
                               "muscle_group_id", muscleId,
                               "role", assignment.role),
                        ORIGIN, null);
                musclesInserted++;
            }
        }

        return new Result(inserted, skipped, musclesInserted);
    }

    /**
     * 撤销整个导入：删掉所有带命名空间前缀的动作，返回删掉的动作数。
     *
     * <p><b>为什么是一个专用操作，而不是 3463 次逐行 {@code undo}</b>：那是「撤销 3463 个
     * 东西」，不是「撤销这次导入」；而且逐行回放的代价与顺序都难以控制。按前缀删除是
     * <b>一次操作</b>，且天然幂等（没导入过的库上返回 0、不报错）。
     *
     * <p><b>顺序不能反</b>：先按前缀查出 id（用来报告删了什么），再删。反过来先删就查不到了。
     * 删 {@code exercise} 会经 {@code ON DELETE CASCADE} 带走 {@code exercise_muscle}——
     * 这里**不**显式删 join 行，正是为了让级联真的发生（{@code foreign_keys=true} 由 JDBC URL
     * 打开，测试实测它生效）。
     *
     * <p><b>记一条汇总 delete 日志，但不删任何 change_log。</b>日志是历史，撤销不是抹除历史。
     * 这条日志是「我那 876 个动作哪去了」的可追溯证据；它**不该**被 {@code undo} 回放，
     * 而 {@code ChangeLogService.undo} 已经显式拒绝 {@code action='delete'}，正好被那道守卫挡住。
     */
    @Transactional
    public int unimport() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM exercise WHERE source_id LIKE ?",
                Long.class, SOURCE_PREFIX + "%");
        if (ids.isEmpty()) {
            return 0;
        }

        int links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_muscle em "
                        + "JOIN exercise e ON e.id = em.exercise_id "
                        + "WHERE e.source_id LIKE ?",
                Integer.class, SOURCE_PREFIX + "%");

        jdbc.update("DELETE FROM exercise WHERE source_id LIKE ?", SOURCE_PREFIX + "%");

        // 汇总，而不是单行快照。target_id 用 0：批量删除没有单个目标行，而 AUTOINCREMENT
        // 的 id 从 1 起，0 永远不是一个真实动作的 id，不会被误读成「第 0 号动作」。
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source_prefix", SOURCE_PREFIX);
        summary.put("exercises", ids.size());
        summary.put("muscle_links", links);
        changeLog.recordDelete("exercise", 0L, summary, ORIGIN, null);

        return ids.size();
    }

    /**
     * 启动时是否应当自动安装动作库。
     *
     * <p>两个条件同时成立才装：库里当前**没有**导入的动作，且用户**从没撤销过**导入。
     *
     * <p>第二个条件是关键：撤销导入之后重启**不该**把库装回来——否则「撤销」这个动作就没有
     * 意义（用户点了移除，下次打开又回来了）。「撤销过」的证据就是 {@link #unimport()} 写下的
     * 那条汇总 delete 日志（{@code origin='import'} / {@code action='delete'} /
     * {@code target_table='exercise'}），它是那次撤销唯一留下的、可追溯的记录。
     *
     * <p>要把库重新装回来是一个**显式**动作（{@code POST /api/exercises/import}），
     * 不是重启的副作用。
     */
    public boolean shouldInstallOnStartup() {
        return !hasImportedExercises() && !wasEverUnimported();
    }

    /** 库里当前有没有导入的动作（{@code source_id} 带命名空间前缀）。 */
    public boolean hasImportedExercises() {
        return count("SELECT COUNT(*) FROM exercise WHERE source_id LIKE ?", SOURCE_PREFIX + "%") > 0;
    }

    /** 用户有没有撤销过导入——读 {@link #unimport()} 写下的那条汇总日志。 */
    public boolean wasEverUnimported() {
        return count("SELECT COUNT(*) FROM change_log "
                + "WHERE origin = ? AND action = 'delete' AND target_table = 'exercise'", ORIGIN) > 0;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Set<String> existingSourceIds() {
        return new HashSet<>(jdbc.queryForList(
                "SELECT source_id FROM exercise WHERE source_id LIKE '" + SOURCE_PREFIX + "%'",
                String.class));
    }

    private Exercise persistExercise(FreeExerciseDb.Row row) {
        Exercise e = new Exercise();
        e.nameEn = row.nameEn;
        e.nameZh = row.nameZh;            // 恒 null：中文名由 Task 7 单独产出
        e.equipment = row.equipment;
        e.compound = row.isCompound;
        e.defaultUnit = row.defaultUnit;
        e.sourceId = row.sourceId;
        e.category = row.category;
        e.instructions = row.instructions;
        return exercises.save(e);
    }

    private ExerciseMuscle persistLink(long exerciseId, long muscleId, String role) {
        ExerciseMuscle link = new ExerciseMuscle();
        link.exerciseId = exerciseId;
        link.muscleGroupId = muscleId;
        link.role = role;
        return exerciseMuscles.save(link);
    }

    /**
     * 把数据集的英文肌群名解析成库里的肌群 id。
     *
     * <p>查不到就抛异常并把「哪个动作的哪块肌肉」说清楚——静默跳过会让那个动作少一块标注，
     * 而它不会报错、也没有日志，是典型的一路静默失效。名字与数据集逐字对齐，
     * 所以这里不做模糊匹配（见 {@code MuscleGroupRepo} 的注释）。
     */
    private long resolveMuscleId(FreeExerciseDb.Row row, FreeExerciseDb.Assignment assignment) {
        return muscleGroups.findByNameEn(assignment.muscleNameEn)
                .orElseThrow(() -> new IllegalStateException(
                        "导入失败：动作「" + row.nameEn + "」的肌群「" + assignment.muscleNameEn
                                + "」在 muscle_group 里找不到。数据集与种子数据对不上。"))
                .id;
    }

    /**
     * 动作的 after 快照。键必须是 {@code exercise} 上真实存在、且不是主键的列——
     * {@link ChangeLogService#recordInsert} 会逐个校验。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：导入时 {@code name_zh} 恒为 null，
     * 且 {@code equipment} / {@code instructions} 可能为 null，而 {@code Map.of} 不接受 null 值。
     */
    private Map<String, Object> exerciseSnapshot(FreeExerciseDb.Row row) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name_en", row.nameEn);
        after.put("name_zh", row.nameZh);
        after.put("equipment", row.equipment);
        after.put("is_compound", row.isCompound ? 1 : 0);
        after.put("default_unit", row.defaultUnit);
        after.put("source_id", row.sourceId);
        after.put("category", row.category);
        after.put("instructions", row.instructions);
        return after;
    }
}
