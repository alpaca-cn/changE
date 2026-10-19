package com.change.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一餐。
 *
 * <p><b>mealDate 必须是纯日期。</b>库里的 meal_date 有形状 CHECK
 * （{@code GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'}），写成完整时间戳会被数据库
 * 直接拒绝；而理由与 {@code workout_session.session_date} 完全相同：一旦库里存着
 * {@code '2026-10-19T03:22:11'}，{@code WHERE meal_date = '2026-10-19'} 返回 0 行——
 * 不报错，只是永远查不到。
 *
 * <p>eatenAt 相反，它是真时间戳，本来就该含时间，库里**没有**形状 CHECK。
 */
@Entity
@Table(name = "meal")
public class Meal {

    /**
     * 合法的餐次。必须与 {@code V4__auxiliary.sql} 里 {@code meal.slot} 的
     * {@code CHECK (slot IN ('breakfast','lunch','dinner','snack'))} 一致。
     *
     * <p>服务层要在写库**之前**用这份清单拦一道：不然非法的 slot 会撞 DB 的 CHECK
     * 变成 500，而它其实是一个该回 400 的输入错误。
     */
    public static final List<String> VALID_SLOTS = List.of("breakfast", "lunch", "dinner", "snack");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 用餐日期，纯日期（见类注释）。
     *
     * <p>{@code @JdbcTypeCode(LOCAL_DATE)} 不是装饰：默认映射走 JDBC 的 {@code setDate()}，
     * 而 xerial 驱动在 {@code date_class=integer}（默认）下把日期存成**纪元毫秒**，
     * 会被 meal_date 的 GLOB CHECK 拒绝。LOCAL_DATE 改用 {@code setObject(LocalDate)}。
     * 这条坑在 {@link WorkoutSession#sessionDate} 上已经踩过一次。
     */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE)
    @Column(name = "meal_date", nullable = false)
    public LocalDate mealDate;

    /** 餐次，合法值见 {@link #VALID_SLOTS}。 */
    @Column(nullable = false)
    public String slot;

    /**
     * 实际用餐时间，真时间戳。
     *
     * <p>同样必需 {@code @JdbcTypeCode(LOCAL_DATE_TIME)}：默认映射走 {@code setTimestamp()}，
     * xerial 在默认 {@code date_class} 下把它写成纪元毫秒——而这一列**没有形状 CHECK**，
     * 所以不会报错，只会静默变成一串谁也读不出含义的数字。这是本类唯一一处
     * 「写错了也看不出来」的字段，测试因此**用 JdbcTemplate 读原始列**来验它。
     */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "eaten_at")
    public LocalDateTime eatenAt;

    public String note;

    /** 可选的照片。库里是 {@code ON DELETE SET NULL}：删照片不该连带删掉用餐记录。 */
    @Column(name = "photo_id")
    public Long photoId;
}
