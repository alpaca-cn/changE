package com.change.service;

import com.change.calc.VolumeCalculator;
import com.change.domain.MuscleGroup;
import com.change.repo.MuscleGroupRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 人体图（2D 热力）的数据：本周每块肌群折算多少组 + 上次训练是哪天、距今几天。
 *
 * <h2>一、本周组数一格都不重算</h2>
 *
 * <p>{@code sets} / {@code min} / {@code max} / {@code status} 全部**逐字**抄自
 * {@link WeeklyVolumeService#weekly} 的结果。那份口径（每记一组主要肌群 +1、次要 +0.5，
 * 按自然周、只算 {@code completed = 1}）在 {@link VolumeCalculator} 与
 * {@link WeeklyVolumeService} 里各只有一处，本类不再写第二份：两份权重逻辑迟早会漂，
 * 而漂了之后首页的「本周容量」与人体图会**同时看起来都正常**、数字却不一样。
 *
 * <p>这条「不要重写」不能只靠数值断言守住（重写一份一模一样的 1.0 / 0.5 算出来的数字
 * 完全相同）。所以本类通过构造器**依赖** {@link WeeklyVolumeService}，
 * 测试 {@code the_weekly_numbers_are_copied_from_the_weekly_volume_service_not_recomputed}
 * 给它注入一个返回假数字的替身来钉死这一点。
 *
 * <h2>二、{@code lastTrainedOn} 是历史上任意一天</h2>
 *
 * <p>它回答的是「这块肌肉上次是什么时候练的」，与「本周练了多少」是两个独立的问题：
 * 本周 0 组、上周练过的肌群必须仍然答得出那天的日期，否则界面上它就变成「从没练过」
 * （那正是「长期未练」这种着色要区分的东西的反面）。
 *
 * <p>所以下面的 SQL **不带任何日期范围**，直接 {@code MAX(session_date)}。
 * 加一个「本周」的 {@code WHERE} 会让上周练过的肌群全部变成 {@code null}，
 * 而 {@code sets: 0} 与 {@code lastTrainedOn: null} 在界面上是两条完全不同的信息。
 *
 * <h2>三、{@code daysSince} 的 null 与 0 是两个状态</h2>
 *
 * <p>今天练过 → {@code 0}；从没练过 → {@code null}。
 * 用 0 表示「从没练过」会把两者混成一个，前端「恢复中 / 长期未练」两种着色就再也分不开。
 * 这也是 {@link #daysSince} 返回 {@link Integer} 而不是 {@code int} 的唯一理由。
 *
 * <h2>四、{@code today} 是「这次请求的参照日」</h2>
 *
 * <p>省略 {@code date} 时它就是真实的今天；给了 {@code date} 时它就是那一天。
 * 两种情况都满足「{@code today} 与 {@code daysSince} 自洽」以及「{@code today} 所在的那一周
 * 就是返回的 {@code weekStart..weekEnd}」。**让它跟着 {@code date} 走是有意的**：
 * 响应于是成为「一个日期」的函数，测试可以用固定日期钉死 {@code daysSince}
 * （否则用例会随运行日期漂移）；如果它恒等于真实的今天，那么带 {@code date} 浏览旧周时
 * {@code daysSince} 就会与 {@code weekStart} 各说各话。
 *
 * <h2>五、{@code regionIds} 为什么在这里补齐，而不是塞进 {@link WeeklyVolumeService} 的 record</h2>
 *
 * <p>前端拿 {@code regionIds} 去 SVG 里找同 id 的 {@code <path>} 着色，所以它必须随这个端点
 * 一起返回（前端不该自己维护一份「肌群 → 区域」的映射）。但它与「多少组」无关，
 * 而 {@link WeeklyVolumeService.MuscleVolume} 是首页「本周容量」也在用的契约——
 * 往那个 record 里加字段会连带改变首页依赖的形状，而这个任务只需要一个只读的补充查询。
 * 所以本类**另外**注入 {@link MuscleGroupRepo}，把 {@code muscle_group.region_ids}
 * 那一列（逗号分隔，见 {@link MuscleGroup#regionIds}）拆成列表挂到自己的 DTO 上。
 */
@Service
public class BodyMapService {

    /**
     * 人体图上的一块肌群。
     *
     * <p>{@code lastTrainedOn} / {@code daysSince} 可空，且**同时**为 null 就是「从没练过」：
     * 序列化成 JSON 时是 {@code "lastTrainedOn": null} / {@code "daysSince": null}
     * （键照常出现，不是被省略——前端只要认 null 这一种）。
     *
     * <p>{@code regionIds} 是**数组**，不是库里的逗号分隔字符串：
     * 前端直接 {@code regions.includes(id)} 去匹配 SVG 的 path。
     */
    public record MuscleBodyMap(long muscleGroupId, String nameZh, String nameEn, double sets,
                                Integer min, Integer max, VolumeCalculator.Status status,
                                LocalDate lastTrainedOn, Integer daysSince, List<String> regionIds) {}

    /**
     * 一次人体图请求的全部数据。
     *
     * @param weekStart 参照日所在自然周的周一
     * @param weekEnd   那一周的周日
     * @param today     参照日（省略 {@code date} 时是真实的今天，给了就是那一天）
     * @param muscles   全部 17 块（**含本周 0 组的**，见类注释二）
     */
    public record BodyMapVolume(LocalDate weekStart, LocalDate weekEnd, LocalDate today,
                                List<MuscleBodyMap> muscles) {}

    /** 日期只认 ISO 的纯日期形状（与 {@code workout_session.session_date} 的 GLOB CHECK 对齐）。 */
    private static final String DATE_SHAPE = "YYYY-MM-DD（例 2026-10-20）";

    /**
     * 每块肌群最后一次被训练（{@code completed = 1}）的日期。
     *
     * <p><b>没有日期范围</b>：见类注释二。{@code completed = 1} 不可省——没打勾的组是
     * 「还没做/没记」，不是训练。同一动作同一肌群在 {@code exercise_muscle} 里出现两次
     * （主要 + 次要）时会有重复行，交给 {@code MAX} 去重。
     *
     * <p>一次查回 17 块（而不是每块一次）：这是「按肌群聚合」，一个练了两年的库有几千组，
     * 在循环里查 17 次纯属浪费往返。
     */
    private static final String LAST_TRAINED_SQL =
            "SELECT em.muscle_group_id AS muscle_id, MAX(s.session_date) AS last_on "
                    + "FROM workout_set ws "
                    + "JOIN session_exercise se ON se.id = ws.session_exercise_id "
                    + "JOIN workout_session s ON s.id = se.session_id "
                    + "JOIN exercise_muscle em ON em.exercise_id = se.exercise_id "
                    + "WHERE ws.completed = 1 "
                    + "GROUP BY em.muscle_group_id";

    private final WeeklyVolumeService weeklyVolume;
    private final MuscleGroupRepo muscleGroups;
    private final JdbcTemplate jdbc;

    public BodyMapService(WeeklyVolumeService weeklyVolume, MuscleGroupRepo muscleGroups,
                          JdbcTemplate jdbc) {
        this.weeklyVolume = weeklyVolume;
        this.muscleGroups = muscleGroups;
        this.jdbc = jdbc;
    }

    /**
     * 端点入口：{@code date} 省略（或空白）取今天，否则取那一天的 ISO 纯日期。
     *
     * <p>格式错抛 {@link IllegalArgumentException}，由 {@code ApiErrors} 翻成
     * 400 + {@code {error}}——与 {@code ProgressService} 的 from/to 同一套做法。
     * 先卡形状再解析：{@code LocalDate.parse} 会接受 {@code +12026-10-20} 这种
     * 带加号的扩展年份，而那不是这个端点的词法。
     */
    public BodyMapVolume volume(String date) {
        return volumeOn(date == null || date.isBlank() ? null : parseDate(date));
    }

    /**
     * 指定参照日的人体图数据。
     *
     * <p>刻意做成**两个名字**而不是 {@code volume(LocalDate)} 重载：
     * 重载会让 {@code volume(null)} 在调用点歧义（读者也得猜是「取今天」还是「解析空串」）。
     *
     * @param anchorDate 参照日；null 表示今天
     */
    public BodyMapVolume volumeOn(LocalDate anchorDate) {
        LocalDate today = anchorDate == null ? LocalDate.now() : anchorDate;

        WeeklyVolumeService.WeeklyVolume week = weeklyVolume.weekly(today);
        Map<Long, LocalDate> lastTrainedOn = lastTrainedOnByMuscle();
        Map<Long, List<String>> regionIds = regionIdsByMuscle();

        List<MuscleBodyMap> muscles = week.muscles().stream()
                .map(m -> {
                    LocalDate last = lastTrainedOn.get(m.muscleGroupId());
                    return new MuscleBodyMap(m.muscleGroupId(), m.nameZh(), m.nameEn(), m.sets(),
                            m.min(), m.max(), m.status(), last, daysSince(last, today),
                            regionIds.getOrDefault(m.muscleGroupId(), List.of()));
                })
                .toList();

        return new BodyMapVolume(week.weekStart(), week.weekEnd(), today, muscles);
    }

    /**
     * {@code today - lastTrainedOn}。从没练过时**必须**返回 null：
     * 返回 0 会把「今天练过」与「从没练过」压成同一个值（见类注释三）。
     *
     * <p>{@link ChronoUnit#DAYS} 按日历日算，不受夏令时影响；{@link Math#toIntExact}
     * 让「离谱的日期」（比如手工改库改出 1970 年）当场响，而不是静默截断成一个像样的数。
     */
    private static Integer daysSince(LocalDate lastTrainedOn, LocalDate today) {
        if (lastTrainedOn == null) {
            return null;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(lastTrainedOn, today));
    }

    /** 肌群 id → 最后一次训练日。缺键 = 从没练过（不是「很久以前」）。 */
    private Map<Long, LocalDate> lastTrainedOnByMuscle() {
        Map<Long, LocalDate> out = new HashMap<>();
        jdbc.query(LAST_TRAINED_SQL, rs -> {
            String lastOn = rs.getString("last_on");
            // 聚合列在「有行」时不可能为 null（session_date 非空），这里只是不替数据库撒谎
            if (lastOn != null) {
                out.put(rs.getLong("muscle_id"), LocalDate.parse(lastOn));
            }
        });
        return out;
    }

    /**
     * 肌群 id → 人体图区域 id 列表。
     *
     * <p>只读一次 17 行、在内存里拆（{@link MuscleGroup#regionIds} 的类注释就是这么约定的）。
     * 列可空（老库 / 未配区域的肌群）：null 或空白给空列表——空数组在 JSON 里仍然是
     * 数组，前端拿到的是「这块没有可着色的区域」，而不是 {@code undefined}。
     */
    private Map<Long, List<String>> regionIdsByMuscle() {
        Map<Long, List<String>> out = new HashMap<>();
        for (MuscleGroup g : muscleGroups.findAll()) {
            out.put(g.id, g.regionIds == null || g.regionIds.isBlank()
                    ? List.of()
                    : List.of(g.regionIds.split(",", -1)));
        }
        return out;
    }

    /** 形状与真实性都在这里拦，翻成 400 由 {@code ApiErrors} 负责。 */
    private static LocalDate parseDate(String raw) {
        if (!raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException(
                    "date 必须是 " + DATE_SHAPE + "，实际为 " + raw);
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            // 形状对但日期不存在（2026-02-31）
            throw new IllegalArgumentException(
                    "date 必须是 " + DATE_SHAPE + " 且为真实存在的日期，实际为 " + raw, e);
        }
    }
}
