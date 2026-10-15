package com.change.calc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 份量单位换算：「1 碗」折成多少克。
 *
 * 换算表存在 food_portion 表里（每食物一套单位），本类只负责查表与计算。
 */
public final class PortionConverter {

    /**
     * 某食物上的一个份量单位。
     *
     * 为什么在这里就把非法值拦掉：food_portion 表上已经有
     * {@code unit_name TEXT NOT NULL} 与 {@code grams REAL NOT NULL CHECK (grams > 0)}，
     * 也就是说 DB 层本来就存不进下面这几种值。在对象层再拦一道，为的是让
     * {@link #index} 里那个合并函数 {@code (a, b) -> a} 不至于**静默丢掉一个单位**——
     * 重复的单位名若连 grams 都不同，用户不会知道用的是哪一个。
     * 让非法对象根本构造不出来，比在查表处补救更早、也更清楚。
     */
    public record Unit(String name, double grams) {

        public Unit {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("单位名不能为空");
            }
            if (!Double.isFinite(grams)) {
                throw new IllegalArgumentException("grams 必须是有限值，实际为 " + grams);
            }
            if (grams <= 0) {
                throw new IllegalArgumentException("grams 必须为正，实际为 " + grams);
            }
        }
    }

    private PortionConverter() {
    }

    /**
     * @param amount 份量数值
     * @param unit   单位名；传 null（或空串/纯空白）表示 amount 本身就是克数
     */
    public static double toGrams(double amount, String unit, List<Unit> units) {
        // NaN < 0 是 false，所以少了这条有限性校验的话，NaN 会一路流成「热量显示 NaN」。
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount 必须是有限值，实际为 " + amount);
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount 不能为负: " + amount);
        }
        if (unit == null || unit.isBlank()) {
            return amount;
        }
        Map<String, Double> idx = index(units);
        Double gramsPerUnit = idx.get(unit);
        if (gramsPerUnit == null) {
            throw new IllegalArgumentException(
                    "未知的份量单位「" + unit + "」；该食物可用的单位: "
                            + String.join("、", idx.keySet()));
        }
        return amount * gramsPerUnit;
    }

    /**
     * 把单位行折成 {@code 单位名 → 克数} 的查找表。
     *
     * 单位名重复时保留先出现的那条：正常数据不可能重复——food_portion 上有
     * {@code UNIQUE INDEX ux_food_portion ON food_portion(food_id, unit_name)}，
     * 而本方法拿到的正是同一个 food_id 的若干行。所以这个合并函数是纯防御性的。
     */
    public static Map<String, Double> index(List<Unit> units) {
        Objects.requireNonNull(units, "units 不能为 null");
        return units.stream().collect(Collectors.toMap(
                Unit::name, Unit::grams, (a, b) -> a, LinkedHashMap::new));
    }
}
