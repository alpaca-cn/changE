package com.change.domain;

import jakarta.persistence.*;

/**
 * 动作字典：一个可被记录的动作（杠铃卧推、平板支撑……）。
 *
 * <p>库里的 name_en 带唯一索引 ux_exercise_name_en：同名动作必须挡在库外。两行同名会把
 * 进步曲线拆成两条线、让周容量翻倍、把 PR 分成两个，而且事后合并要重挂所有
 * session_exercise.exercise_id 并重算衍生指标。因此**任何往这张表插数据的地方
 * （测试、导入、种子数据）都要保证 name_en 互不相同**。
 *
 * <p>source_id 是外部数据集的主键，约定必须带命名空间前缀（如
 * {@code free-exercise-db:barbell-bench-press}）；手工录入的动作写 NULL。库里的唯一索引是
 * 部分索引（{@code WHERE source_id IS NOT NULL}），**空串不等于 NULL**：写 "" 的动作在
 * 第二次导入时撞唯一约束、整批回滚。所以「没有来源」只能表达成 null。
 *
 * <p>字段用 public（本项目的既定风格：实体只做数据载体，不加 getter/setter），
 * 外键一律用 Long 存 id 而不是 @ManyToOne 关联——避免懒加载与 N+1。
 */
@Entity
@Table(name = "exercise")
public class Exercise {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 英文名。唯一（ux_exercise_name_en），不可为空。 */
    @Column(name = "name_en", nullable = false)
    public String nameEn;

    @Column(name = "name_zh")
    public String nameZh;

    /** 器械，自由文本（barbell / dumbbell / bodyweight……）。 */
    public String equipment;

    /** 是否复合动作。用来区分「深蹲」和「腿屈伸」，影响容量统计的口径。 */
    @Column(name = "is_compound", nullable = false)
    public boolean compound = false;

    /** 默认单位，建表默认 'kg'。 */
    @Column(name = "default_unit", nullable = false)
    public String defaultUnit = "kg";

    /** 外部来源主键，带命名空间前缀；手工动作必须 null，不要写空串。 */
    @Column(name = "source_id")
    public String sourceId;

    public String note;

    /** 归档而不是删除：旧训练记录仍要能解析出这个动作的名字。 */
    @Column(nullable = false)
    public boolean archived = false;
}
