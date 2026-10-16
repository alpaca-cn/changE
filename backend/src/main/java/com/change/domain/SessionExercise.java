package com.change.domain;

import jakarta.persistence.*;

/**
 * 三层结构的中间层：一次训练里的一个动作（「今天胸日第 1 个动作是杠铃卧推」）。
 *
 * <p>外键用 Long 存 id（sessionId / exerciseId），不做 @ManyToOne 关联：这套结构的所有读取
 * 都是「拿一次训练的 id 取它的动作、再按 id 取组」，显式 id 查询不会有懒加载和 N+1 的意外。
 *
 * <p>orderIndex 是「这个动作在本次训练里排第几」。库里有唯一索引
 * ux_session_exercise_session_order(session_id, order_index)——同一次训练里两行同序没有意义，
 * 重排时必须先挪开再落位（或整体重编号），不能直接覆盖。
 *
 * <p>supersetGroup 同值即超级组，NULL 表示不参与超级组。
 */
@Entity
@Table(name = "session_exercise")
public class SessionExercise {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "session_id", nullable = false)
    public Long sessionId;

    @Column(name = "exercise_id", nullable = false)
    public Long exerciseId;

    /** 在本次训练里的排序位次，同一 session 内唯一。 */
    @Column(name = "order_index", nullable = false)
    public int orderIndex;

    public String note;

    @Column(name = "superset_group")
    public String supersetGroup;
}
