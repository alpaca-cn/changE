package com.change.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 「这个动作练哪块肌肉、主要还是次要」。
 *
 * <p>有代理主键是因为审计网（{@link com.change.service.ChangeLogService}）的准入条件是
 * 「单列整型 id」——见 {@code V5__exercise_muscle_surrogate_id.sql} 的注释。业务唯一性是
 * {@code (exercise_id, muscle_group_id)} 上的唯一索引 {@code ux_exercise_muscle_pair}：
 * 索引漏建时同一个动作能把同一块肌肉标两次，而「本周 20 组」会被静默算成 40 组。
 */
@Entity
@Table(name = "exercise_muscle")
public class ExerciseMuscle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "exercise_id", nullable = false)
    public Long exerciseId;

    @Column(name = "muscle_group_id", nullable = false)
    public Long muscleGroupId;

    /** {@code 'primary'} 或 {@code 'secondary'}，DDL 有 CHECK（见 V2 里的约定）。 */
    @Column(name = "role", nullable = false)
    public String role;
}
