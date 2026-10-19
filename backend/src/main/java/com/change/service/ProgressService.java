package com.change.service;

import com.change.calc.OneRepMax;
import com.change.domain.Exercise;
import com.change.repo.ExerciseRepo;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 进步曲线：一个动作按**训练日**的三条序列。
 *
 * <h2>三条口径（本类要守住的东西）</h2>
 *
 * <p><b>一、{@code e1rm} 是估算值，口径只有一处：</b>每个训练日，该动作全部已完成组里
 * {@link OneRepMax#bestOf} 的最大值。它跳过「次数超出 1..12」与「重量不为正」的组，
 * 也就在个别日子里**估不出来**——那种日子仍然出点，{@code e1rm} 为 null（见下）。
 * 不要在这里自己写 Epley：本项目的公式、{@code reps == 1} 取实测值这些约定都在
 * {@link OneRepMax} 里，重写一份就等于把它那 13 条测试的保证丢掉。
 *
 * <p><b>二、{@code maxWeight} 是原始事实，{@code e1rm} 是估算。</b>两个都留：读数的人对
 * 「那天真举起了 100 kg」和「按公式估出 103.33」信任度不同，只给一个都会让另一方消失。
 *
 * <p><b>三、{@code totalVolume} 是吨位（Σ 重量×次数），不是组数。</b>与
 * {@code VolumeCalculator} 的「组数」是两件事，名字撞在一起纯属中文都叫「容量」。
 * 只累加 {@code completed = 1} 且重量、次数都非空的组。
 *
 * <h2>e1rm 估不出来的那天：{@code e1rm} 为 null，但点必须保留</h2>
 *
 * <p>「做了 3 组 15 次轻重量」是真实训练：{@link OneRepMax#bestOf} 因为 15 &gt;
 * {@link OneRepMax#MAX_REPS} 返回 empty。这时 {@code maxWeight} 与 {@code totalVolume}
 * 仍然有值。整点丢掉等于**静默漏掉一次训练**；填 0 更坏——曲线上会多一个假的下跌，
 * 看着像退步。所以 {@link Point#e1rm} 是 {@link Double} 而不是 {@code double}。
 *
 * <p><b>JSON 里的表达方式是「写 null」，不是省略键。</b>Jackson 默认会把 null 字段照常
 * 序列化，本项目没有改 {@code default-property-inclusion}，所以 {@code "e1rm": null}
 * 会原样出现。键一直在，前端就不用区分「后端版本旧没这个字段」与「这天估不出来」——
 * 按「null 或不存在都算没有」画即可，但后端只承诺 null 这一种。
 *
 * <h2>每条序列都只含「有数据的那天」</h2>
 *
 * <p>中间没练的日子不补 0。补 0 会让曲线在休息日掉到地板上，看起来像退步；而休息日
 * 本来就不是「力量是 0」的意思。同理，某动作从没练过时 {@code points} 是空列表（不是 404）：
 * 「还没开始」是常态，不是错误。
 *
 * <h2>为什么一次 SQL，不在循环里查</h2>
 *
 * <p>点数是「训练日数」，一个练了两年的动作有几百个；每个点再查一次就是几百次往返。
 * 一次取回区间内该动作的全部组，在 Java 里按 {@code session_date} 分组。分组用
 * {@link LinkedHashMap} 保住 SQL 的 {@code ORDER BY ws.session_date} 给出的升序。
 *
 * <h2>per_side 不乘 2</h2>
 *
 * <p>{@code per_side = 1} 的意思是「左右各记了一组」（用户的输入方式），不是
 * 「这一组的重量要按两侧合计」。重量按记录值参与三条序列；乘 2 会静默把吨位翻倍，
 * 而且翻倍之后没有任何界面会显示「这是乘过的」。
 *
 * <h2>不缓存</h2>
 *
 * <p>缓存是计划 6a Task 4 的事，本类刻意只读库。
 */
@Service
public class ProgressService {

    /** 日期只认 ISO 的纯日期形状（与 {@code workout_session.session_date} 的 GLOB CHECK 对齐）。 */
    private static final String DATE_SHAPE = "YYYY-MM-DD（例 2026-10-14）";

    /**
     * 进步曲线上的一个点。{@code date} 序列化成 ISO 纯日期（{@code 2026-10-14}）。
     *
     * <p>{@code e1rm} 可空：那天没有任何「次数在 1..12、重量为正」的组时估不出来
     * （见类注释）。{@code maxWeight} 与 {@code totalVolume} 在同一天仍然有值，
     * 因为它们只依赖「重量/次数记了没有」，不依赖公式能不能估。
     */
    public record Point(LocalDate date, Double e1rm, Double maxWeight, Double totalVolume) {}

    /**
     * 一个动作的整条曲线。
     *
     * <p>{@code nameZh} 可空：动作字典里 name_zh 是可空列，876 条导入数据都有中文名，
     * 但手工录入的动作可能没有——读路径不能因此抛异常，界面回退到 {@code nameEn} 即可。
     */
    public record ExerciseProgress(long exerciseId, String nameEn, String nameZh, List<Point> points) {}

    /**
     * 一次取回区间内该动作的全部组。{@code (? IS NULL OR ...)} 让 from/to 可省略而不用拼 SQL：
     * 日期在库里是 {@code YYYY-MM-DD} 文本，字符串比较与日期比较同序。
     *
     * <p>过滤放在 SQL 里同时是「别把整条历史拉进内存」：日期区间之外的组连传都不传。
     * {@code completed = 1} 与两个 NOT NULL 也在这里，省得 Java 侧再逐行判一遍。
     */
    private static final String PROGRESS_SQL = """
            SELECT ws.session_date AS session_date,
                   s.weight        AS weight,
                   s.repetitions   AS repetitions
            FROM workout_set s
            JOIN session_exercise se ON se.id = s.session_exercise_id
            JOIN workout_session ws ON ws.id = se.session_id
            WHERE se.exercise_id = ?
              AND s.completed = 1
              AND s.weight IS NOT NULL
              AND s.repetitions IS NOT NULL
              AND (? IS NULL OR ws.session_date >= ?)
              AND (? IS NULL OR ws.session_date <= ?)
            ORDER BY ws.session_date
            """;

    private final JdbcTemplate jdbc;
    private final ExerciseRepo exercises;

    public ProgressService(JdbcTemplate jdbc, ExerciseRepo exercises) {
        this.jdbc = jdbc;
        this.exercises = exercises;
    }

    /**
     * 一个动作的进步曲线。
     *
     * @param exerciseId 动作 id；不存在时抛 {@link ResponseStatusException}（404）
     * @param from       起始训练日（含）；null/空白表示不限
     * @param to         截止训练日（含）；null/空白表示不限
     */
    public ExerciseProgress progress(long exerciseId, String from, String to) {
        Exercise exercise = exercises.findById(exerciseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "找不到这个动作: " + exerciseId));

        LocalDate fromDate = parseOptionalDate(from, "from");
        LocalDate toDate = parseOptionalDate(to, "to");
        // 绑定成字符串而不是 LocalDate：SQLite 的 session_date 是 TEXT，驱动对 java.time
        // 的绑定行为随版本变化，而 YYYY-MM-DD 的文本比较与日期比较同序（见 PROGRESS_SQL）。
        String fromParam = fromDate == null ? null : fromDate.toString();
        String toParam = toDate == null ? null : toDate.toString();

        Map<LocalDate, DayAccumulator> byDay = new LinkedHashMap<>();
        jdbc.query(PROGRESS_SQL, rs -> {
            LocalDate date = LocalDate.parse(rs.getString("session_date"));
            double weight = rs.getDouble("weight");
            int repetitions = rs.getInt("repetitions");

            DayAccumulator day = byDay.computeIfAbsent(date, d -> new DayAccumulator());
            // 交给 OneRepMax 判断可不可估，这里只把「组的原始事实」收好
            day.sets.add(new OneRepMax.Set(weight, repetitions));
            day.maxWeight = Math.max(day.maxWeight, weight);
            day.totalVolume += weight * repetitions;
        }, exerciseId, fromParam, fromParam, toParam, toParam);

        List<Point> points = new ArrayList<>(byDay.size());
        for (Map.Entry<LocalDate, DayAccumulator> entry : byDay.entrySet()) {
            DayAccumulator day = entry.getValue();
            OptionalDouble best = OneRepMax.bestOf(day.sets);
            Double e1rm = null;
            if (best.isPresent()) {
                e1rm = best.getAsDouble();
            }
            points.add(new Point(entry.getKey(), e1rm, day.maxWeight, day.totalVolume));
        }

        return new ExerciseProgress(exercise.id, exercise.nameEn, exercise.nameZh, points);
    }

    /**
     * 累积一天。只在 {@link #progress} 里活着，所以是私有静态类而不是 record：
     * 它要**可变**，record 做不到逐行累加。
     */
    private static final class DayAccumulator {
        private final List<OneRepMax.Set> sets = new ArrayList<>();
        /** 从 -∞ 起：每一行都带非空 weight，所以第一次 max 一定被它自己替换掉。 */
        private double maxWeight = Double.NEGATIVE_INFINITY;
        private double totalVolume = 0.0;
    }

    /** 可选日期：null 或空白表示不限。形状与真实性都在这里拦，翻成 400 由 ApiErrors 负责。 */
    private static LocalDate parseOptionalDate(String raw, String field) {
        String value = raw == null || raw.isBlank() ? null : raw;
        if (value == null) {
            return null;
        }
        // 先卡形状再解析：LocalDate.parse 会接受 '+12026-10-14' 这种 toString() 带加号的年份
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException(
                    field + " 必须是 " + DATE_SHAPE + "，实际为 " + value);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            // 形状对但日期不存在（2026-02-31）
            throw new IllegalArgumentException(
                    field + " 必须是 " + DATE_SHAPE + " 且为真实存在的日期，实际为 " + value, e);
        }
    }
}
