package com.change.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「英文名 → 中文做法（分段）」的映射批量写进 {@code exercise.instructions_zh}。
 *
 * <p><b>这一步不调模型</b>：它只接受一份现成的译文。译文怎么产出是另一件事
 * （计划 2 · Task 2–4）；把「产出译文」与「写译文」分开，是为了让这一半可测——
 * 它全是机械规则，而规则错了会静默写坏数据，值得被测试钉住。
 *
 * <h2>四条规则（顺序即语义）</h2>
 * <ol>
 *   <li><b>只碰导入的动作</b>（{@code source_id IS NOT NULL}）。手工录入的动作是用户自己的
 *       东西，导入的译文没有资格改它——哪怕名字撞上了。</li>
 *   <li><b>没有英文原文就不写中文</b>：数据集里 5 条动作的 {@code instructions} 是 NULL
 *       （Iron Cross、One-Arm Kettlebell Swings、Push Press、Side Bridge、Side Jackknife）。
 *       没有可翻的内容，凭空编一段中文就是「增」。这一档单独计数，不从映射判断——那 5 条
 *       根本不在 {@code instructions-zh.json} 里，详见 {@link #apply}。</li>
 *   <li><b>只改 {@code instructions_zh IS NULL} 的行</b>：这是**断点续**。跑到一半崩掉，
 *       再跑一次会跳过已经写好的，接着往后写；已经写好的也不会被覆盖（幂等，可重跑）。</li>
 *   <li>以上都不满足时，才写。</li>
 * </ol>
 *
 * <p><b>段怎么拼</b>：产物里每条是 {@code ["段1", "段2", ...]}，写库时用
 * {@link String#join(CharSequence, Iterable)} 以 {@code \n} 连接。特别地，
 * {@code Straight-Arm Dumbbell Pullover} 的第 3 段**内部**自带一个换行——拼接只能追加
 * 段间换行，不能把段内换行「修正」掉，否则那一段会少一行。
 *
 * <p>返回一个 {@link Result}，把「写了多少 / 因为什么跳过多少」分开报——只报一个
 * {@code updated} 的话，「映射给错了名字」和「本来就写好了」看起来一模一样。
 */
@Service
public class ExerciseInstructionsZhService {

    /** classpath 上的「英文名 → 中文做法（分段）」映射。随包分发。 */
    public static final String MAPPING_RESOURCE = "seed/instructions-zh.json";

    /**
     * 一次写入的结果。四个计数互不重叠。
     *
     * <p>与 {@link ExerciseNameZhService.Result} 的差别只在第三项：
     * {@code skippedEmptySource} 数的**不是映射里的条目**，而是库里「已导入、但英文原文为空」
     * 的动作数（那 5 条根本不在映射里，见 {@link #apply}）。
     */
    public record Result(int updated, int skippedAlready, int skippedEmptySource,
                         int skippedNotImported) {}

    private final JdbcTemplate jdbc;

    public ExerciseInstructionsZhService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 应用一份映射。可重复调用（幂等）。
     *
     * <p><b>为什么「原文为空」从库里数、而不是从映射里数</b>：产物
     * {@code instructions-zh.json} 里只有 871 条——那 5 条原文为空的根本没有译文可写，
     * 所以它们**不在映射里**。「映射里出现它但值是空数组」这种判断永远不会命中；
     * 唯一诚实的来源是库里的 {@code instructions IS NULL}。于是：
     * <ul>
     *   <li>库里原文为空 → 计入 {@code skippedEmptySource}（每次运行都按库里实况报，
     *       它是动作库的属性，不是「本次做了什么」）；</li>
     *   <li>映射里有、库里连动作都没有（或只有手工动作）→ 计入 {@code skippedNotImported}。</li>
     * </ul>
     *
     * <p>{@code @Transactional}：半写一半不是想要的中间态——要么这批译文都进去，要么都不进。
     * 传播用默认（REQUIRED）：连接池只有 1 条连接，REQUIRES_NEW 会自己等自己。
     */
    @Transactional
    public Result apply(Map<String, List<String>> instructionsZh) {
        int updated = 0;
        int alreadyWritten = 0;
        int notImported = 0;

        for (Map.Entry<String, List<String>> entry : instructionsZh.entrySet()) {
            // 只认导入的动作（手工动作 source_id 为 NULL，永远匹配不到）。
            Long id = firstId(
                    "SELECT id FROM exercise WHERE name_en = ? AND source_id IS NOT NULL",
                    entry.getKey());
            if (id == null) {
                notImported++;
                continue;
            }

            // 没有原文就没有可翻的东西。这一档在末尾按库里实况统一计数，
            // 这里不能 ++ ——否则映射里出现的空原文行会被数两次。
            if (hasNoEnglishSource(id)) {
                continue;
            }

            // 断点续 + 幂等：已经有中文做法的不再动。
            if (hasChineseInstructions(id)) {
                alreadyWritten++;
                continue;
            }

            String joined = String.join("\n", entry.getValue());
            jdbc.update("UPDATE exercise SET instructions_zh = ? WHERE id = ?", joined, id);
            updated++;
        }

        // 按库里实况数「原文为空」的导入动作。实际数据这 5 条是 NULL（不是空串），
        // 但两种都算「没有原文」——空串在 SQL 里与 NULL 不同，漏掉一种就会少数。
        int emptySource = count(
                "SELECT COUNT(*) FROM exercise WHERE source_id IS NOT NULL "
                        + "AND (instructions IS NULL OR TRIM(instructions) = '')");

        return new Result(updated, alreadyWritten, emptySource, notImported);
    }

    /**
     * 从 classpath 读 {@link #MAPPING_RESOURCE} 并应用。
     *
     * <p>启动时用，{@code POST /api/exercises/import} 之后也用——导入完不写做法的话，库会停在
     * 「有动作、有中文名、没中文做法」的半成品状态（要等下一次重启才补上）。
     *
     * @return 应用结果；classpath 上没有这份映射时返回 {@code null}（不是错误）
     */
    @Transactional
    public Result applyMappingFromClasspath() {
        ClassPathResource resource = new ClassPathResource(MAPPING_RESOURCE);
        if (!resource.exists()) {
            return null;
        }
        Map<String, List<String>> mapping;
        try (var in = resource.getInputStream()) {
            mapping = new ObjectMapper().readValue(
                    in, new TypeReference<LinkedHashMap<String, List<String>>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("读取中文做法映射失败: " + MAPPING_RESOURCE, e);
        }
        return apply(mapping);
    }

    /**
     * 有没有「已导入、有英文原文、但还没有中文做法」的动作。
     *
     * <p>启动时用它决定要不要读那份 473 KB 的映射：正常启动（都已写好）时直接跳过，
     * 不必每次启动都解析一遍 JSON。也让「映射新增了译文」这类情况能被自动补上——
     * 只要还有空着的行就会处理。
     *
     * <p>原文为空的那 5 条不算「还没写」：它们永远不会有中文，算进去会让每次启动
     * 都白读一遍映射。
     */
    public boolean hasUnwrittenImportedInstructions() {
        return count("SELECT COUNT(*) FROM exercise WHERE source_id IS NOT NULL "
                + "AND instructions IS NOT NULL AND TRIM(instructions) <> '' "
                + "AND instructions_zh IS NULL") > 0;
    }

    private boolean hasNoEnglishSource(long id) {
        return count("SELECT COUNT(*) FROM exercise WHERE id = ? "
                + "AND (instructions IS NULL OR TRIM(instructions) = '')", id) > 0;
    }

    private boolean hasChineseInstructions(long id) {
        return count("SELECT COUNT(*) FROM exercise WHERE id = ? AND instructions_zh IS NOT NULL",
                id) > 0;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Long firstId(String sql, Object... args) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, args);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
