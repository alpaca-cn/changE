package com.change.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一次训练（三层结构的最外层：训练 → 动作 → 组）。
 *
 * <p><b>sessionDate 必须是纯日期。</b>库里的 session_date 有形状 CHECK
 * （{@code GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'}），写成完整时间戳会被数据库直接
 * 拒绝。这条约束拦的不是脏数据而是静默失效：一旦库里存着 '2026-10-14T03:22:11'，
 * {@code WHERE session_date = '2026-10-14'} 返回 0 行——不报错，只是永远查不到，
 * 索引 ix_session_date 也用不上。所以这个字段刻意是 LocalDate 而不是 LocalDateTime。
 *
 * <p>startedAt / endedAt 相反，它们是真时间戳，本来就该含时间，库里没有形状 CHECK。
 *
 * <p>sourceRoutineDayId 为将来「计划驱动训练」预留，第一阶段不写。
 */
@Entity
@Table(name = "workout_session")
public class WorkoutSession {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 训练日期，纯日期（见类注释：写成时间戳会被 CHECK 拒绝）。
     *
     * <p>{@code @JdbcTypeCode(LOCAL_DATE)} 不是装饰，是必需的：默认映射走 JDBC 的
     * {@code setDate()}，而 xerial 驱动的 {@code setDate} 按 {@code date_class} 参数
     * （默认 integer）把日期存成**纪元毫秒**——实测写进去的是 {@code '1791907200000'}，
     * 直接被 session_date 的 GLOB CHECK 拒绝。LOCAL_DATE 让 Hibernate 改用
     * {@code setObject(LocalDate)} 绑定，驱动对 java.time 类型是原生支持的，
     * 落库即 {@code '2026-10-14'}。读回与查询参数同理（所以按日期区间查得到）。
     */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE)
    @Column(name = "session_date", nullable = false)
    public LocalDate sessionDate;

    /**
     * 开始时间，真时间戳。
     *
     * <p>同样要 {@code @JdbcTypeCode(LOCAL_DATE_TIME)}：默认映射走 JDBC 的
     * {@code setTimestamp()}，xerial 驱动在默认 {@code date_class=integer} 下把它存成
     * **纪元毫秒**（实测 {@code '1791977415000'}）——列类型是 TEXT、又没有形状 CHECK，
     * 所以不会报错，只会静默变成一串谁也读不出含义的数字。LOCAL_DATE_TIME 改用
     * {@code setObject(LocalDateTime)}，落库即 ISO-8601 的 {@code '2026-10-14T19:30:15'}，
     * 与设计约定「时间戳用 TEXT + ISO-8601」一致，也能被 SQLite 的 date()/strftime() 直接吃。
     */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "started_at")
    public LocalDateTime startedAt;

    /** 结束时间。理由同 startedAt。 */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "ended_at")
    public LocalDateTime endedAt;

    public String title;

    /** 主观状态：训练当天的体感（睡眠/疲劳等），自由文本。 */
    @Column(name = "perceived_state")
    public String perceivedState;

    public String note;

    /** 预留：这次训练由哪个计划日生成。用 id 而不是关联，理由同 Exercise。 */
    @Column(name = "source_routine_day_id")
    public Long sourceRoutineDayId;
}
