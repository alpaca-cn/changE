package com.change.domain;

import jakarta.persistence.*;

import java.util.List;

/**
 * 餐中的一个条目：吃了某个食物的某个份量。
 *
 * <p><b>只存「食物 + 份量 + 换算克数」，绝不存绝对热量。</b>库里这张表上连 kcal 列都没有
 * （V4 的注释原文：{@code -- 注意：这里没有 kcal 字段，故意的。}）。存了就会出现
 * 「用户把份量从 1 碗改成 1.5 碗，而热量还是旧的那份」——漏一次账就永远对不上。
 *
 * <p>grams 是 {@code amount × 每单位克数}，由服务层用
 * {@code calc.PortionConverter.toGrams} 算好后写入。**存的是未定标的值**：
 * {@code calc/package-info} 约定二说明定标（两位小数）属于显示层，先舍再累加会引入
 * 有方向性的系统偏差。
 */
@Entity
@Table(name = "meal_item")
public class MealItem {

    /**
     * 条目的来源。必须与 {@code V4__auxiliary.sql} 里 {@code meal_item.origin} 的
     * {@code CHECK (origin IN ('manual','photo','agent','takeout'))} 一致。
     *
     * <p>计划 4a 只产生 {@code manual}；另外三个是给计划 4b（拍照/外卖/智能体）预留的——
     * 界面上将来要能区分「我自己填的」与「AI 猜的」。
     */
    public static final List<String> VALID_ORIGINS =
            List.of("manual", "photo", "agent", "takeout");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "meal_id", nullable = false)
    public Long mealId;

    @Column(name = "food_id", nullable = false)
    public Long foodId;

    /** 在这一餐里的顺序，同一 meal 内唯一。服务层按传入顺序赋 0、1、2… */
    @Column(name = "order_index", nullable = false)
    public int orderIndex;

    /** 份量数值。库里是 {@code CHECK (amount > 0)}——0 克份量的记录注定写不进去。 */
    @Column(nullable = false)
    public double amount;

    /**
     * 单位名；**null 表示 {@link #amount} 本身就是克数**。
     *
     * <p>这个「null = 按克算」的语义与 {@code PortionConverter.toGrams} 的
     * {@code unit} 参数一致（传 null 直接返回 amount），两处必须是同一个解释。
     */
    @Column(name = "unit_name")
    public String unitName;

    /** 换算后的克数，写入时算好。库里是 {@code CHECK (grams > 0)}，未定标。 */
    @Column(nullable = false)
    public double grams;

    /** 来源，合法值见 {@link #VALID_ORIGINS}。 */
    @Column(nullable = false)
    public String origin = "manual";
}
