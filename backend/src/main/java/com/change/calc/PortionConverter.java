package com.change.calc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 份量单位换算：「1 碗」折成多少克。
 *
 * 换算表存在 food_portion 表里（每食物一套单位），本类只负责查表与计算。
 */
public final class PortionConverter {

    /**
     * 某食物上的一个份量单位。
     *
     * 这里校验的是**值**：名字非空、grams 有限且为正。food_portion 表上本来就有
     * {@code unit_name TEXT NOT NULL} 与 {@code grams REAL NOT NULL CHECK (grams > 0)}，
     * 所以 DB 层存不进下面这几种值——这道校验只是把 Java 侧对齐到既有 schema：
     * DB 愿意接受的每一行，{@code Unit} 也都接受；反过来，被拦下的值在 DB 侧同样进不去。
     *
     * 它**不校验名字唯一性**：同一个食物的单位名唯一由
     * {@code UNIQUE INDEX ux_food_portion(food_id, unit_name)} 保证，而那个约束管不到
     * 手工拼出来的列表（{@code new Unit("碗", 150)} 与 {@code new Unit("碗", 200)} 都能构造成功）。
     * 所以重复名由 {@link #index} 在检出时抛异常作为第二道防线——而不是静默取首个。
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
     * 把份量折成克数。
     *
     * 返回的是**未定标的原始乘积**：定标（两位小数）属于显示层，不在这里做——
     * 先舍再累加会引入有方向性的系统偏差，而且会让「份量录入」与「直接填克数」
     * 两条路径显示的热量不再一致（见 package-info 的约定二）。
     *
     * @param amount 份量数值；必须为正——{@code meal_item.amount} 是
     *               {@code CHECK (amount > 0)}，0 克份量的记录注定写不进去，
     *               在这里拒绝它只是把失败提前到录入那一刻
     * @param unit   单位名；传 null（或空串/纯空白）表示 amount 本身就是克数
     * @param units  该食物的份量单位表；仅当 unit 需要查表时使用
     * @return 克数；上界与 {@link NutritionCalculator#forGrams} 共用
     *         {@link NutritionCalculator#MAX_GRAMS}，上游放行、下游拒绝的缝隙会
     *         产生「份量存得下、热量算不出来」的记录
     */
    public static double toGrams(double amount, String unit, List<Unit> units) {
        // NaN < 0 是 false，所以少了这条有限性校验的话 NaN 会一路流到 forGrams：
        // 那里 Math.round(NaN) 是 0、Round.to2(NaN) 是 0.0，最终**静默显示 0 kcal**。
        // 这比显示 NaN 更糟——NaN 一眼能看出坏了，0 看起来完全正常。
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount 必须是有限值，实际为 " + amount);
        }
        // 对齐 schema：meal_item.amount 是 CHECK (amount > 0)，与 Unit.grams 的 grams <= 0 同一口径。
        if (amount <= 0) {
            throw new IllegalArgumentException("amount 必须为正: " + amount);
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
        double grams = amount * gramsPerUnit;
        if (!(grams <= NutritionCalculator.MAX_GRAMS)) {
            // 写成 !(grams <= MAX) 而不是 grams > MAX，是因为 NaN 与 > 比较恒为 false。
            // 本类已有的 NaN 处理用的是同一个套路。
            throw new IllegalArgumentException(
                    "换算后的克数超出单次录入上限 " + NutritionCalculator.MAX_GRAMS + ": " + grams);
        }
        return grams;
    }

    /**
     * 把份量单位表转成「单位名 → 每单位克数」的索引。
     *
     * 单位名重复时**抛异常**而不是静默取首个：数据库侧的 ux_food_portion
     * 只在 (food_id, unit_name) 上唯一，而本方法收到的是调用方拼的列表——
     * 如果混进了别的食物的单位，静默取首个会让 toGrams 按错误的克数换算，
     * 而调用方无从察觉。让它在拼错时就响，比让它算出一个看起来合理的值更划算。
     */
    public static Map<String, Double> index(List<Unit> units) {
        Objects.requireNonNull(units, "units 不能为 null");
        Map<String, Double> out = new LinkedHashMap<>();
        for (Unit u : units) {
            Objects.requireNonNull(u, "units 不能含 null 元素");
            Double prev = out.putIfAbsent(u.name(), u.grams());
            if (prev != null) {
                throw new IllegalArgumentException(
                        "份量单位名重复：「" + u.name() + "」既是 " + prev + " 克又是 " + u.grams() + " 克");
            }
        }
        return out;
    }
}
