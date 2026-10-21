package com.change.service;

import com.change.domain.BodyMetric;
import com.change.domain.MetricType;
import com.change.repo.BodyMetricRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 身体数据：写入一条、读时间线、读每个类型的当前值。
 *
 * <h2>三条口径（本类要守住的东西）</h2>
 *
 * <p><b>一、类型是 Java 侧的词汇，不是数据库约束。</b>{@code body_metric.metric_type}
 * 在库里没有 CHECK（见 {@link MetricType} 的类注释：开放词汇，加「小腿围」时不该写迁移）。
 * 所以写入时在这里用枚举校验，读取时**不**校验——历史数据里可能已经有枚举外的类型，
 * 读路径要为它照常工作，而不是让整页打不开。
 *
 * <p><b>二、去重是不做的。</b>同一天同一类型的两次测量是两条记录。早上空腹与晚上饭后
 * 本来就是两个数；合并任何一个都等于替用户篡改历史，而这条历史正是进步曲线的原料。
 *
 * <p><b>三、日期在服务层解析成 {@link LocalDate}。</b>请求体里的 measuredOn 与查询参数
 * from/to 都是字符串，在这里按 {@code YYYY-MM-DD} 校验。理由有两条：
 * 库里的 measured_on 有 GLOB 形状 CHECK，形状错必须回一句能读懂的 400；
 * 而 {@code ApiErrors} 只把 {@link IllegalArgumentException} 翻成 400 `{error}`——
 * 交给 Jackson 的 LocalDate 反序列化的话，同一个错误会变成另一种形状的 400 正文。
 *
 * <h2>审计</h2>
 *
 * <p>写入记一条 {@code change_log}（{@code origin='user'}）。设计文档决定 4 说智能体
 * **也能改数据**——将来它给你补一条体重记录时，这条日志是「谁在什么时候改了它」的唯一证据。
 * 快照的键必须与真实列名逐字一致（{@code ChangeLogService} 会按 pragma 校验），
 * 日期必须先 {@code toString()}：快照用的是裸 {@code ObjectMapper}，放 LocalDate
 * 会在**记日志那一步**抛异常，把一次正常的记录整个弄失败。
 */
@Service
public class BodyMetricService {

    /** 时间线不传 limit 时返回多少条。与 {@code ExerciseQueryService} 同一套「默认 + 封顶」思路。 */
    public static final int DEFAULT_LIMIT = 200;

    /** 上限，防止调用方一次把整条历史拉走。 */
    public static final int MAX_LIMIT = 1000;

    private static final String USER_ORIGIN = "user";

    /** 日期只认 ISO 的纯日期形状（与库里 measured_on 的 GLOB CHECK 逐字对齐）。 */
    private static final String DATE_SHAPE = "YYYY-MM-DD（例 2026-10-19）";

    /**
     * 一条要写入的身体数据。
     *
     * <p>{@code measuredOn} 是**字符串**而不是 LocalDate：见类注释「三」。
     * {@code value} 用 {@link Double} 而不是 {@code double}：JSON 里漏掉它时是 null，
     * 让人报错；用基本类型会悄悄变成 0.0，而「0 公斤」会一直躺在历史里。
     * {@code unit} 为 null（或空白）时由 {@link MetricType#defaultUnit} 补上。
     */
    public record MetricInput(String measuredOn, String metricType, Double value,
                              String unit, String note) {
    }

    /** 写入结果。只有 id——这里没有可实时算的派生值（对比 NutritionService.Result）。 */
    public record Created(long id) {
    }

    /** 时间线上的一行。{@code measuredOn} 序列化成 ISO 纯日期（{@code 2026-10-19}）。 */
    public record MetricView(long id, LocalDate measuredOn, String metricType,
                             double value, String unit, String note) {
    }

    /**
     * 一个类型的当前值卡片：最新值 + 最新日期 + 总条数。
     *
     * <p>{@code unit} 是**最新那条记录自己的单位**（不是枚举默认单位）：库里真有一条
     * 用「斤」记的体重时，卡片必须显示斤，否则数字会被当成公斤读。
     */
    public record TypeSummary(String metricType, String unit, double latestValue,
                              LocalDate latestOn, long count) {
    }

    /**
     * 「当前值」卡片的排序：{@link MetricType} 的声明顺序（体重 → 体脂 → 各围度）。
     *
     * <p>不是字母序，也不是插入顺序：这三张卡片的先后是界面设计的一部分。
     * 枚举外的类型（有人绕过 Java 直接写库）排在已知类型之后，同组内按字面量排——
     * 它们不消失，只是靠后。
     */
    private static final Map<String, Integer> TYPE_ORDER = typeOrder();

    private final BodyMetricRepo metrics;
    private final ChangeLogService changeLog;
    private final JdbcTemplate jdbc;

    public BodyMetricService(BodyMetricRepo metrics, ChangeLogService changeLog, JdbcTemplate jdbc) {
        this.metrics = metrics;
        this.changeLog = changeLog;
        this.jdbc = jdbc;
    }

    /**
     * 记一条测量。
     *
     * <p><b>一个事务。</b>数据行与审计行同生共死：只写进数据而日志失败会留下一条
     * 「查不到来源」的记录，而这正是审计网要防的事。
     */
    @Transactional
        /**
     * 用户来源的写入（老签名，保持不动）。
     *
     * <p>转调带 origin 的版本。留这个重载是为了不让现有调用方与测试为了一个
     * 参数全面改动；语义与以前完全一致。
     */
    public Created record(MetricInput input) {
        return record(input, USER_ORIGIN);
    }

    /** 带来源的版本：智能体写数据时传 "agent"。 */
    public Created record(MetricInput input, String origin) {
        if (input == null) {
            throw new IllegalArgumentException("身体数据不能为空");
        }
        LocalDate measuredOn = parseDate(input.measuredOn(), "measuredOn");
        MetricType type = requireKnownType(input.metricType());
        double value = requireValue(input.value());
        String unit = blankToNull(input.unit());
        if (unit == null) {
            unit = type.defaultUnit;
        }

        BodyMetric metric = new BodyMetric();
        metric.measuredOn = measuredOn;
        metric.metricType = type.id;
        metric.value = value;
        metric.unit = unit;
        metric.note = input.note();
        metrics.save(metric);

        changeLog.recordInsert("body_metric", metric.id, snapshot(metric), origin, null);
        return new Created(metric.id);
    }

    /**
     * 时间线：某类型（或全部类型）在某段日期区间里的测量，按日期升序。
     *
     * <p>三个筛选条件都可省略。{@code type} **不校验**是否在枚举里：读路径为
     * 「枚举外的历史类型」工作；认不出的类型给空列表，而不是报错。
     *
     * @param type  类型字面量；null/空白表示全部类型
     * @param from  起始日期（含）；null/空白表示不限
     * @param to    截止日期（含）；null/空白表示不限
     * @param limit 返回条数；null 用 {@link #DEFAULT_LIMIT}，封顶 {@link #MAX_LIMIT}
     */
    public List<MetricView> timeline(String type, String from, String to, Integer limit) {
        String metricType = blankToNull(type);
        LocalDate fromDate = parseOptionalDate(from, "from");
        LocalDate toDate = parseOptionalDate(to, "to");
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        return metrics.search(metricType, fromDate, toDate, PageRequest.of(0, cap)).stream()
                .map(BodyMetricService::toView)
                .toList();
    }

    /**
     * 库里**实际出现过**的类型，各带最新值与总条数。没有数据时返回空列表（不是 404）。
     *
     * <p>「最新」按 {@code measured_on} 最大，不是 id 最大：补录一条历史数据时它的 id
     * 更大而日期更早，按 id 取会把「三天前补录的体重」当成当前值。同一天有多条时取
     * 最后录入的那条（次级键 id DESC）——那一天里用户最后看到的就是它。
     *
     * <p>用窗口函数一次取回所有类型的最新行，而不是「先 GROUP BY 再每个类型查一次」：
     * 后者是 N+1 次往返，且两次查询之间数据可能变化，卡片上的值与日期可能来自两行。
     */
    public List<TypeSummary> types() {
        List<TypeSummary> rows = jdbc.query("""
                SELECT metric_type, unit, value, measured_on, n
                FROM (
                    SELECT metric_type, unit, value, measured_on,
                           COUNT(*) OVER (PARTITION BY metric_type) AS n,
                           ROW_NUMBER() OVER (PARTITION BY metric_type
                                              ORDER BY measured_on DESC, id DESC) AS rn
                    FROM body_metric
                )
                WHERE rn = 1
                """,
                (rs, rowNum) -> new TypeSummary(
                        rs.getString("metric_type"),
                        rs.getString("unit"),
                        rs.getDouble("value"),
                        LocalDate.parse(rs.getString("measured_on")),
                        rs.getLong("n")));

        return rows.stream()
                .sorted(Comparator
                        .comparingInt((TypeSummary s) ->
                                TYPE_ORDER.getOrDefault(s.metricType(), Integer.MAX_VALUE))
                        .thenComparing(TypeSummary::metricType))
                .toList();
    }

    /** 某类型是否在词汇里（写路径的校验；读路径不用它）。 */
    private static MetricType requireKnownType(String metricType) {
        String id = blankToNull(metricType);
        if (id == null) {
            throw new IllegalArgumentException(
                    "metricType 不能为空（合法值 " + MetricType.validIds() + "）");
        }
        MetricType type = MetricType.byId(id);
        if (type == null) {
            // 把合法值拼进去：用户看到「bicep 不合法」还得再猜能写什么。
            throw new IllegalArgumentException(
                    "metricType 不合法：" + id + "（合法值 " + MetricType.validIds() + "）");
        }
        return type;
    }

    /**
     * 数值校验。
     *
     * <p>只拦负数与 null，不拦 0：本计划没有「值必须为正」这条约定（DB 也没有 CHECK），
     * 而替用户猜「0 一定是笔误」会让他记不下真值。NaN / Infinity 拦下——它们能进 REAL 列，
     * 但之后任何图表都画不出来。
     */
    private static double requireValue(Double value) {
        if (value == null) {
            throw new IllegalArgumentException("value 不能为空");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value 必须是有限数值：" + value);
        }
        if (value < 0) {
            throw new IllegalArgumentException("value 不能为负：" + value);
        }
        return value;
    }

    /** 必填日期（写路径）。 */
    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空，格式为 " + DATE_SHAPE);
        }
        // 先卡形状再解析：GLOB CHECK 只认 \d{4}-\d{2}-\d{2}，而 LocalDate.parse 会接受
        // '+12026-10-19' 这种 toString() 带加号的年份——它进库会被 CHECK 拒绝。
        if (!raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException(
                    field + " 必须是 " + DATE_SHAPE + "，实际为 " + raw);
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            // 形状对但日期不存在（2026-02-31）。同样是调用方的输入错误，同样回 400。
            throw new IllegalArgumentException(
                    field + " 必须是 " + DATE_SHAPE + " 且为真实存在的日期，实际为 " + raw, e);
        }
    }

    /** 可选日期（读路径）：null 或空白表示不限。 */
    private static LocalDate parseOptionalDate(String raw, String field) {
        String value = blankToNull(raw);
        return value == null ? null : parseDate(value, field);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static MetricView toView(BodyMetric m) {
        return new MetricView(m.id, m.measuredOn, m.metricType, m.value, m.unit, m.note);
    }

    /**
     * 审计快照。键必须与真实列名一致；日期先 toString（见类注释「审计」）。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：日志里列的顺序不承载信息，
     * 但顺序不确定的 JSON 每次读都长得不一样，排查时得多扫两遍。
     *
     * <p><b>note 只在非 null 时放进来</b>，两个理由缺一不可：
     * <ol>
     *   <li>{@code Map.of} 不接受 null 值——但这正是本方法用 {@code LinkedHashMap} 的原因之一；</li>
     *   <li>更重要的是：{@code note} 是这条记录里**唯一携带上下文**的列（「早上空腹」
     *       「饭后两小时」「换了台秤」）。审计的目的是回答「这次改动动了什么」，
     *       少了它就只能答一半——而数值本身在 {@code value} 里已经有了。</li>
     * </ol>
     * 非 null 才放，是为了让「没写备注」在 JSON 里表现为**键不存在**而不是 {@code null}：
     * 后者会被读日志的人当成「备注被清空了」。
     */
    private static Map<String, Object> snapshot(BodyMetric m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("measured_on", m.measuredOn.toString());
        map.put("metric_type", m.metricType);
        map.put("value", m.value);
        map.put("unit", m.unit);
        if (m.note != null) {
            map.put("note", m.note);
        }
        return map;
    }

    private static Map<String, Integer> typeOrder() {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (MetricType type : MetricType.values()) {
            order.put(type.id, order.size());
        }
        return Map.copyOf(order);
    }
}
