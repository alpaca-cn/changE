package com.change.domain;

import jakarta.persistence.*;

/**
 * 份量换算：某个食物的「1 碗 = 150 克」。
 *
 * <p>换算表按食物一套单位，而不是全局一套：一碗米饭和一碗汤的克数差得远。
 * 库里 {@code UNIQUE(food_id, unit_name)} 保证同一个食物的单位名不重复。
 */
@Entity
@Table(name = "food_portion")
public class FoodPortion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "food_id", nullable = false)
    public Long foodId;

    /** 单位名（碗 / 个 / 勺 / 份）。不能为空——「空单位」等于「按克算」，那件事用 null 表达。 */
    @Column(name = "unit_name", nullable = false)
    public String unitName;

    /** 一个该单位等于多少克。库里是 {@code CHECK (grams > 0)}。 */
    @Column(nullable = false)
    public double grams;
}
