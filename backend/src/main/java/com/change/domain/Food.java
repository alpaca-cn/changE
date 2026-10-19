package com.change.domain;

import jakarta.persistence.*;

import java.util.List;

/**
 * 食物库里的一条。
 *
 * <p><b>铁律：营养值一律按每 100 克存。</b>这两个问题的分辨率完全不同——「这个食物有多少热量」
 * 是它的固定属性，「我这次吃了多少」每次都变。所以绝对份量的换算结果**绝不落库**
 * （{@link MealItem} 上连 kcal 列都没有，故意的），热量永远实时算。
 *
 * <p>钠的单位是**毫克**，其余是克。按克读会得到致死量级的数字（见 {@code calc.Nutrition}）。
 */
@Entity
@Table(name = "food")
public class Food {

    /**
     * 合法的数据来源。必须与 {@code V3__nutrition.sql} 里 {@code food.source} 的
     * {@code CHECK (source IN ('manual','cn-food-table','agent','takeout'))} 一致。
     *
     * <p>与 {@link WorkoutSet#VALID_SET_TYPES} 同一套路：把「Java 侧白名单」提成常量，
     * 好让 schema 测试把它与 DDL 里抽出来的值集逐项比对。
     *
     * <p>用 {@link List} 而不是 {@code Set}：报错与文档要按声明顺序读，
     * 而 {@code Set.of} 的迭代顺序按 JVM 运行随机。
     */
    public static final List<String> VALID_SOURCES =
            List.of("manual", "cn-food-table", "agent", "takeout");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 中文名。库里**刻意没有唯一约束**：中式食物里「同名不同做法」真实存在
     * （同一个菜名对应完全不同的配料与热量），在数据库层唯一化会逼导入器丢掉真实条目。
     */
    @Column(name = "name_zh", nullable = false)
    public String nameZh;

    @Column(name = "name_en")
    public String nameEn;

    /** 数据来源，合法值见 {@link #VALID_SOURCES}。 */
    @Column(nullable = false)
    public String source;

    @Column(name = "kcal_per100", nullable = false)
    public double kcalPer100;

    @Column(name = "protein_per100", nullable = false)
    public double proteinPer100;

    @Column(name = "carb_per100", nullable = false)
    public double carbPer100;

    @Column(name = "fat_per100", nullable = false)
    public double fatPer100;

    @Column(name = "fiber_per100", nullable = false)
    public double fiberPer100;

    /** 钠，单位**毫克**。 */
    @Column(name = "sodium_per100", nullable = false)
    public double sodiumPer100;
}
