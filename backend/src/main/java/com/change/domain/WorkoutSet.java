package com.change.domain;

import jakarta.persistence.*;

import java.util.List;

/**
 * 三层结构的最内层：一个动作下的一组。
 *
 * <p><b>目标值与实际值同表。</b>weight / repetitions 是「实际做了多少」，
 * targetWeight / targetReps 是「打算做多少」，两者刻意放在同一行：分开建表会让
 * 「今天练得比计划重还是轻」变成一次跨表对齐。目标值也可以为空（没设目标的自由训练）。
 *
 * <p><b>setIndex 的语义已冻结：每个子组各占一个 setIndex，用 setType 区分性质。</b>
 * 递减组的三段是 setIndex 0/1/2 + setType 'dropset'，而不是三段共用同一个 setIndex。
 * 理由：setIndex 是「带出上次重量」的对齐键，两行同号会让对齐随机取到其中一行，而且取错
 * 不会有任何报错。库里 ux_workout_set_session_exercise_index(session_exercise_id, set_index)
 * 把这个语义钉死了。
 *
 * <p>weight / repetitions / durationSeconds 刻意可为空：三空的行是一个真实状态
 * （「这组还没做/没记」），训练中界面靠增量保存逐字段补上。库里对应的 CHECK 是
 * {@code IS NULL OR >= 0}（durationSeconds 是 {@code > 0}），所以空值放行、有值必须合法。
 */
@Entity
@Table(name = "workout_set")
public class WorkoutSet {

    /**
     * 合法的组类型。必须与 {@code V2__training.sql} 里 {@code workout_set.set_type}
     * 的 {@code CHECK} 一致（DDL 原文：{@code set_type IN ('normal','warmup','dropset',
     * 'myo','partial','forced','tut','iso','jump')}）。
     *
     * <p>与 {@link com.change.calc.VolumeCalculator#VALID_ROLES} 同一套路：把「Java 侧白名单」
     * 提成常量，好让 {@code SchemaTest} 把它与 {@code sqlite_master.sql} 里抽出来的值集
     * **逐项比对**（含顺序），任何一侧改了另一侧就红。
     *
     * <p>在此之前这张清单只以**注释**形式手抄在本类里（下面 {@link #setType} 那行），
     * 注释与 DDL 之间没有任何机械收口——DDL 加了第十种组类型，注释照样写着九种。
     *
     * <p>用 {@link List} 而不是 {@code Set}：报错与文档要按声明顺序读，
     * 而 {@code Set.of} 的迭代顺序按 JVM 运行随机。
     */
    public static final List<String> VALID_SET_TYPES = List.of(
            "normal", "warmup", "dropset", "myo", "partial", "forced", "tut", "iso", "jump");

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "session_exercise_id", nullable = false)
    public Long sessionExerciseId;

    /** 在本动作内的组序号，同一 session_exercise 内唯一（每个子组各占一个）。 */
    @Column(name = "set_index", nullable = false)
    public int setIndex;

    /**
     * 组类型。合法值见 {@link #VALID_SET_TYPES}（九种，库里有 CHECK 白名单）；
     * 这里**不再手抄**那份清单——抄一份就多一份会过期的东西。
     */
    @Column(name = "set_type", nullable = false)
    public String setType = "normal";

    /** 实际重量。null = 没记（不是 0）。 */
    public Double weight;

    /** 实际次数。与 durationSeconds 二选一。 */
    public Integer repetitions;

    /** 计时动作（平板支撑等）用秒数，与 repetitions 二选一。 */
    @Column(name = "duration_seconds")
    public Integer durationSeconds;

    /** 每侧计数（弓步、单臂动作）。 */
    @Column(name = "per_side", nullable = false)
    public boolean perSide = false;

    /** 剩余次数（Reps In Reserve），主观强度。 */
    public Double rir;

    @Column(name = "rest_seconds")
    public Integer restSeconds;

    @Column(nullable = false)
    public boolean completed = false;

    /** 目标重量，与 weight 同行以便直接对比计划与实际。 */
    @Column(name = "target_weight")
    public Double targetWeight;

    @Column(name = "target_reps")
    public Integer targetReps;
}
