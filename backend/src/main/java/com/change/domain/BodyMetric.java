package com.change.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

/**
 * 一条身体数据：某天、某类型、一个数值（体重 / 体脂率 / 围度）。
 *
 * <p>这张表没有「组」「条目」那种层级：**一行就是一次测量**。同一天同一类型录两次是
 * 两条独立记录（不去重）——早上空腹与晚上饭后本来就是两个数，抹掉其中一个等于
 * 篡改用户看到的历史。
 *
 * <p><b>measuredOn 必须是纯日期。</b>库里的 measured_on 有形状 CHECK
 * （{@code GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'}），写成完整时间戳会被数据库
 * 直接拒绝。理由与 {@code workout_session.session_date} / {@code meal.meal_date} 完全相同：
 * 一旦库里存着 {@code '2026-10-19T03:22:11'}，{@code WHERE measured_on = '2026-10-19'}
 * 返回 0 行——不报错，只是永远查不到，索引 {@code ix_body_metric_type_date} 也用不上。
 * 所以这个字段刻意是 LocalDate 而不是 LocalDateTime。
 */
@Entity
@Table(name = "body_metric")
public class BodyMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 测量日期，纯日期（见类注释）。
     *
     * <p>{@code @JdbcTypeCode(LOCAL_DATE)} 不是装饰，是必需的：默认映射走 JDBC 的
     * {@code setDate()}，而 xerial 驱动在 {@code date_class=integer}（默认）下把日期存成
     * **纪元毫秒**（实测写进去的是 {@code '1791907200000'}），会被 measured_on 的 GLOB CHECK
     * 拒绝。LOCAL_DATE 让 Hibernate 改用 {@code setObject(LocalDate)} 绑定，落库即
     * {@code '2026-10-19'}；读回与按区间查询同理。这条坑在
     * {@link WorkoutSession#sessionDate} 上已经踩过一次，这里是第三次照抄同一个解。
     */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE)
    @Column(name = "measured_on", nullable = false)
    public LocalDate measuredOn;

    /**
     * 指标类型的字面量，取值见 {@link MetricType}。
     *
     * <p>**刻意映射成 String 而不是 {@code @Enumerated(EnumType.STRING)}**：这一列是
     * 开放词汇（见 {@link MetricType} 的类注释），DB 层没有 CHECK。将来某次迁移或智能体
     * 直接写入一个新类型（例如 {@code "shoulder_width"}）时，映射成枚举会让**读整张表**
     * 抛 {@code IllegalArgumentException}——「多了一条新数据，身体数据页整个打不开」。
     * 用 String 时未知类型只是排在枚举已知类型之后显示（见
     * {@code BodyMetricService#types}），页面照常可用。
     */
    @Column(name = "metric_type", nullable = false)
    public String metricType;

    /** 数值。单位由 {@link #unit} 说明；无符号约定（围度、体重都是正数，体脂率是百分数）。 */
    @Column(nullable = false)
    public double value;

    /** 单位。客户端不传时由 {@link MetricType#defaultUnit} 填上，所以库里不会有 null。 */
    @Column(nullable = false)
    public String unit;

    /** 备注（"早上空腹"之类），可空。 */
    public String note;
}
