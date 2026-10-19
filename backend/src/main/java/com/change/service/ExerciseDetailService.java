package com.change.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作详情：一个动作怎么做（中英文）以及它练到哪些肌群。
 *
 * <h2>这个类为什么存在</h2>
 *
 * <p>{@code exercise.instructions}（英文做法）从导入那天起就在库里，但**从来没有被任何端点
 * 暴露过**；中文做法 {@code instructions_zh} 是 V9 才加的一列。前端的「点一个动作，看它怎么做」
 * 需要这两列 + 肌群一次拿到，所以这里把它们聚合成一个只读 DTO——不新增表，也不写库。
 *
 * <h2>一次 SQL 查三张表，不 N+1</h2>
 *
 * <p>一个动作可能挂 1–11 块肌群（数据实测最多 11 块）。先查动作、再逐个查肌群就是 1+N 次往返；
 * 而这条 SQL 用两个 LEFT JOIN 一次取回「动作 × 肌群」的行，在内存里折叠成一个动作。
 *
 * <p>用 <b>LEFT JOIN</b> 而不是 INNER JOIN：手工录入的动作可能没有肌群标注，那是合法状态
 * （详情页只是没有「练哪里」那一栏），不能因此让动作本身变成 404。没有肌群时 LEFT JOIN 会给出
 * 一行、肌群列为 NULL，折叠时跳过即可。
 *
 * <h2>肌群的排序（前端直接按返回顺序渲染）</h2>
 *
 * <p>{@code exercise_muscle.role} 的真实取值只有两个：{@code 'primary'} 与
 * {@code 'secondary'}（来源是 {@code V2__training.sql} 的
 * {@code CHECK (role IN ('primary', 'secondary'))}，与 {@code VolumeCalculator.VALID_ROLES}
 * 一致）。排序规则就是计划里写的那条：<b>primary 在 secondary 之前，同 role 内按
 * {@code muscle_group.id}</b>。
 *
 * <p>role 的先后用显式 {@code CASE} 而不是 {@code ORDER BY em.role}：后者只是碰巧因为
 * 字母序 {@code primary < secondary} 才对；将来若有人把值改成 {@code 'main'/'assist'}，
 * 字母序会静默把主要肌群排到最后，而界面上看不出那是错的。
 *
 * <h2>刻意不返回的列</h2>
 *
 * <p>{@code source_id} / {@code note} / {@code archived} 不在 DTO 里。前端不需要它们，
 * 而每多一个键，Task 7 手写的 TS interface 就多一处会漂的契约——漂了不会报错，
 * 前端只会静默拿到 {@code undefined}。键集合由测试逐字钉住。
 *
 * <h2>为什么用 JdbcTemplate 而不是 JPA</h2>
 *
 * <p>这是纯读、单行聚合，实体 {@code Exercise} 的字段比这里需要的多（还有 sourceId/note/
 * archived/defaultUnit），走实体反而要额外屏蔽。项目里同类只读聚合（例如
 * {@link ProgressService} 的曲线）也用 JdbcTemplate。
 */
@Service
public class ExerciseDetailService {

    /**
     * 详情里的一个肌群。
     *
     * <p>字段顺序即 JSON 键顺序：{@code muscleGroupId / nameZh / nameEn / role}。
     * 这是与前端 TS interface 共享的契约，改顺序/改名字都要同步改测试。
     */
    public record MuscleRef(long muscleGroupId, String nameZh, String nameEn, String role) {}

    /**
     * 一个动作的详情。
     *
     * <p>字段顺序即 JSON 键顺序：
     * {@code id / nameEn / nameZh / equipment / isCompound / category / instructions /
     * instructionsZh / muscles}。
     *
     * <p>{@code nameZh}、{@code instructions}、{@code instructionsZh} 都可能为 null：
     * 手工动作可以没有中文名；数据集里 5 条动作的英文原文为空（Iron Cross、
     * One-Arm Kettlebell Swings、Push Press、Side Bridge、Side Jackknife）。它们**不是错误**，
     * 所以不 404；Jackson 默认把 null 照常序列化，键一直都在。
     */
    public record ExerciseDetail(long id, String nameEn, String nameZh, String equipment,
                                 boolean isCompound, String category,
                                 String instructions, String instructionsZh,
                                 List<MuscleRef> muscles) {}

    /**
     * 一次取回动作 + 肌群。{@code ORDER BY} 同时决定 {@code muscles} 的顺序：
     * 先 primary（CASE 0）后 secondary（CASE 1），同 role 内按肌群 id。
     *
     * <p>{@code ELSE 2} 是防御：DDL 的 CHECK 已经挡住第三个值，真出现时排在最后而不是
     * 打乱前两组的顺序。
     */
    private static final String DETAIL_SQL = """
            SELECT e.id,
                   e.name_en,
                   e.name_zh,
                   e.equipment,
                   e.is_compound,
                   e.category,
                   e.instructions,
                   e.instructions_zh,
                   em.muscle_group_id,
                   em.role,
                   mg.name_zh AS muscle_name_zh,
                   mg.name_en AS muscle_name_en
            FROM exercise e
            LEFT JOIN exercise_muscle em ON em.exercise_id = e.id
            LEFT JOIN muscle_group mg   ON mg.id = em.muscle_group_id
            WHERE e.id = ?
            ORDER BY CASE em.role WHEN 'primary' THEN 0 WHEN 'secondary' THEN 1 ELSE 2 END,
                     em.muscle_group_id
            """;

    private final JdbcTemplate jdbc;

    public ExerciseDetailService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一个动作的详情。
     *
     * @param exerciseId 动作 id；不存在时抛 {@link ResponseStatusException}（404），
     *                   由 {@code ApiErrors} 翻成 {@code {error}} 形状
     */
    public ExerciseDetail detail(long exerciseId) {
        List<DetailRow> rows = jdbc.query(DETAIL_SQL, (rs, rowNum) -> {
            long muscleGroupId = rs.getLong("muscle_group_id");
            Long muscleId = rs.wasNull() ? null : muscleGroupId;
            return new DetailRow(
                    rs.getLong("id"),
                    rs.getString("name_en"),
                    rs.getString("name_zh"),
                    rs.getString("equipment"),
                    rs.getInt("is_compound") != 0,
                    rs.getString("category"),
                    rs.getString("instructions"),
                    rs.getString("instructions_zh"),
                    muscleId,
                    rs.getString("role"),
                    rs.getString("muscle_name_zh"),
                    rs.getString("muscle_name_en"));
        }, exerciseId);

        // 查不到行只有一种原因：这个 id 不存在。动作存在但没有肌群时 LEFT JOIN 仍给一行。
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这个动作: " + exerciseId);
        }

        DetailRow head = rows.get(0);
        List<MuscleRef> muscles = new ArrayList<>(rows.size());
        for (DetailRow row : rows) {
            if (row.muscleGroupId() != null) {
                muscles.add(new MuscleRef(row.muscleGroupId(), row.muscleNameZh(),
                        row.muscleNameEn(), row.role()));
            }
        }

        return new ExerciseDetail(head.id(), head.nameEn(), head.nameZh(), head.equipment(),
                head.isCompound(), head.category(), head.instructions(), head.instructionsZh(),
                List.copyOf(muscles));
    }

    /** SQL 的一行：动作字段在每行重复，肌群列为 LEFT JOIN 的可空结果。 */
    private record DetailRow(long id, String nameEn, String nameZh, String equipment,
                             boolean isCompound, String category,
                             String instructions, String instructionsZh,
                             Long muscleGroupId, String role,
                             String muscleNameZh, String muscleNameEn) {}
}
