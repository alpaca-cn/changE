package com.change.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「英文名 → 中文名」的映射批量写进 {@code exercise.name_zh}。
 *
 * <p><b>这一步不调模型</b>：它只接受一份现成的映射。中文名怎么产出是另一件事
 * （计划 2 · Task 7 Step 4）；把「产出映射」与「写映射」分开，是为了让这一半可测——
 * 它全是机械规则，而规则错了会静默改错数据，值得被测试钉住。
 *
 * <h2>四条规则（顺序即语义）</h2>
 * <ol>
 *   <li><b>只碰导入的动作</b>（{@code source_id IS NOT NULL}）。手工录入的动作是用户自己的
 *       东西，导入的中文名没有资格改它——哪怕名字撞上了。</li>
 *   <li><b>只改 {@code name_zh IS NULL} 的行</b>：这是**断点续**。跑到一半崩掉，再跑一次会
 *       跳过已经写好的，接着往后写；已经写好的也不会被覆盖（幂等，可重跑）。</li>
 *   <li><b>中文名不能重复</b>：{@code name_zh} 上没有唯一索引，但两个动作叫同一个中文名会让
 *       人在选择器里选错。已占用（库里已有的 + 本次已写的）的中文名一律跳过并计数。</li>
 *   <li>以上都不满足时，才写。</li>
 * </ol>
 *
 * <p>返回一个 {@link Result}，把「写了多少 / 因为什么跳过多少」分开报——只报一个
 * `updated` 的话，「映射给错了名字」和「本来就写好了」看起来一模一样。
 */
@Service
public class ExerciseNameZhService {

    /** 一次写入的结果。四个计数互不重叠，加起来等于映射里与库里动作能对上的条目数。 */
    public record Result(int updated, int skippedAlreadyNamed, int skippedDuplicate, int skippedNotImported) {}

    private final JdbcTemplate jdbc;

    public ExerciseNameZhService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 应用一份映射。可重复调用（幂等）。
     *
     * <p>{@code @Transactional}：半写一半不是想要的中间态——要么这批名字都进去，要么都不进。
     * 传播用默认（REQUIRED）：连接池只有 1 条连接，REQUIRES_NEW 会自己等自己。
     */
    @Transactional
    public Result apply(Map<String, String> nameEnToNameZh) {
        // 库里已被占用的中文名：一次读出来，包含**本次已写**的那些（见下面 usedZh.add）。
        Set<String> usedZh = new HashSet<>(jdbc.queryForList(
                "SELECT name_zh FROM exercise WHERE name_zh IS NOT NULL", String.class));

        int updated = 0;
        int alreadyNamed = 0;
        int duplicate = 0;
        int notImported = 0;

        for (Map.Entry<String, String> entry : nameEnToNameZh.entrySet()) {
            String nameEn = entry.getKey();
            String nameZh = entry.getValue();

            // 只认导入的动作（手工动作 source_id 为 NULL，永远匹配不到）。
            Long id = firstId(
                    "SELECT id FROM exercise WHERE name_en = ? AND source_id IS NOT NULL",
                    nameEn);
            if (id == null) {
                notImported++;
                continue;
            }

            // 断点续 + 幂等：已经有中文名的不再动。
            if (firstId("SELECT id FROM exercise WHERE id = ? AND name_zh IS NULL", id) == null) {
                alreadyNamed++;
                continue;
            }

            // 中文名去重：已占用的跳过，不写、也不覆盖。
            if (usedZh.contains(nameZh)) {
                duplicate++;
                continue;
            }

            jdbc.update("UPDATE exercise SET name_zh = ? WHERE id = ?", nameZh, id);
            usedZh.add(nameZh);
            updated++;
        }

        return new Result(updated, alreadyNamed, duplicate, notImported);
    }

    private Long firstId(String sql, Object... args) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, args);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
