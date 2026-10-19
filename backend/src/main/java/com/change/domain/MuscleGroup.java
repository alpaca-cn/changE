package com.change.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一块肌群。种子数据见 {@link com.change.seed.MuscleGroupSeed}，
 * 库里的 17 行来自迁移 {@code V7__muscle_group_seed.sql}（由那份种子代码生成）。
 *
 * <p>{@code regionIds} 是**逗号分隔的字符串**而不是数组：SQLite 没有数组类型，
 * 而这张表的读取路径是「一次取 17 行、在内存里按需拆」——为它单独建一张
 * 多值表只会把一次查询变成一次 join，换不来任何约束或查询能力。
 * 写库时用 {@code String.join(",", regionIds)}；逗号两侧不能有空格，
 * 拆的时候直接 {@code split(",")}。词汇表见 {@link com.change.seed.MuscleRegion}。
 */
@Entity
@Table(name = "muscle_group")
public class MuscleGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "name_zh", nullable = false)
    public String nameZh;

    @Column(name = "name_en", nullable = false, unique = true)
    public String nameEn;

    /** 粗粒度分区（如「躯干前侧」「下肢」），给动作选择器分组用。 */
    @Column(name = "body_part", nullable = false)
    public String bodyPart;

    /** 逗号分隔的 {@link com.change.seed.MuscleRegion} id，如 "chest_left,chest_right"。 */
    @Column(name = "region_ids")
    public String regionIds;

    /** 「本周容量」提醒的下限；null 表示不提醒（见 MuscleGroupSeed 的类注释）。 */
    @Column(name = "weekly_sets_min")
    public Integer weeklySetsMin;

    /** 「本周容量」提醒的上限；与 min 成对出现。 */
    @Column(name = "weekly_sets_max")
    public Integer weeklySetsMax;
}
