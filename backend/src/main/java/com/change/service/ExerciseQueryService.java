package com.change.service;

import com.change.domain.Exercise;
import com.change.repo.ExerciseRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 动作选择器：按肌群 / 关键词 / 分类筛动作。
 *
 * <p><b>为什么用既有的派生查询 + 内存过滤，而不是一条大 SQL。</b>文字搜索走
 * {@link ExerciseRepo} 那两个方法（计划 2 刚修过 {@code archived} 过滤，这里正好是它们的第一个
 * 真实调用方——不要另写一条 SQL 让它们变成死代码）；肌群过滤走一条取 id 的 SQL 后求交集；
 * 分类与截断在内存里做。动作库是 876 条的**随包数据**，不是会长的用户表，全表进内存完全够用。
 * 真到了几万条再下推 SQL——现在下推只会多一套要维护的 SQL 与它的测试。
 *
 * <p><b>分类规则</b>：{@code category} 给定时精确匹配；不给定时排除 {@code stretching}
 * （拉伸不是「记一组」的对象）。其余分类默认保留——把 292 条非 strength 一刀切掉会把
 * 「抓举」「挺举」这类真正的力量动作也滤掉。
 */
@Service
public class ExerciseQueryService {

    /** 选择器默认返回多少条。 */
    public static final int DEFAULT_LIMIT = 50;

    /** 上限，防止调用方一次把整个库拉走。 */
    public static final int MAX_LIMIT = 200;

    /** 不给 category 时排除掉的分类：拉伸不是「记一组」的对象。 */
    private static final String NOT_A_SET_CATEGORY = "stretching";

    /** 选择器里的一行。 */
    public record ExerciseSummary(long id, String nameEn, String nameZh, String equipment,
                                  boolean isCompound, String category) {}

    private final ExerciseRepo exercises;
    private final JdbcTemplate jdbc;

    public ExerciseQueryService(ExerciseRepo exercises, JdbcTemplate jdbc) {
        this.exercises = exercises;
        this.jdbc = jdbc;
    }

    /**
     * @param muscleGroupId 只看练这块肌群的动作；null 表示不限
     * @param query         关键词，匹配中文名或英文名（英文不分大小写）；null/空表示不限
     * @param category      精确匹配的分类；null/空表示「除 stretching 外都要」
     * @param limit         返回条数上限；null 用 {@link #DEFAULT_LIMIT}，并且封顶在 {@link #MAX_LIMIT}
     */
    public List<ExerciseSummary> search(Long muscleGroupId, String query, String category, Integer limit) {
        List<Exercise> candidates = (query == null || query.isBlank())
                ? exercises.findByArchivedFalse()
                : exercises.findByArchivedFalseAndNameZhContainingOrArchivedFalseAndNameEnContainingIgnoreCase(
                        query, query);

        Set<Long> forMuscle = muscleGroupId == null ? null : new HashSet<>(jdbc.queryForList(
                "SELECT exercise_id FROM exercise_muscle WHERE muscle_group_id = ?",
                Long.class, muscleGroupId));

        boolean categoryGiven = category != null && !category.isBlank();
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        return candidates.stream()
                .filter(e -> forMuscle == null || forMuscle.contains(e.id))
                .filter(e -> categoryGiven ? category.equals(e.category)
                                           : !NOT_A_SET_CATEGORY.equals(e.category))
                .sorted(Comparator.comparing(e -> e.nameZh == null ? e.nameEn : e.nameZh))
                .limit(cap)
                .map(ExerciseQueryService::toSummary)
                .toList();
    }

    private static ExerciseSummary toSummary(Exercise e) {
        return new ExerciseSummary(e.id, e.nameEn, e.nameZh, e.equipment, e.compound, e.category);
    }
}
