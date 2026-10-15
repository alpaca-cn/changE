package com.change.calc;

import java.util.List;

/**
 * 热量与宏量营养素计算。
 *
 * 铁律：所有营养值都按「每 100 克」保存，份量单独保存。
 * 本类只做一件事：把每 100 克的值按实际克数换算出来。
 * 结果永远实时计算，绝不回存——这样改份量、换食物、换单位都不会算错。
 */
public final class NutritionCalculator {

    /** 单次录入的份量上限（克）。超过这个数几乎一定是录错了。 */
    public static final double MAX_GRAMS = 5_000;

    private NutritionCalculator() {
    }

    public static Nutrition forGrams(Nutrition per100g, double grams) {
        if (grams < 0) {
            throw new IllegalArgumentException("grams 不能为负: " + grams);
        }
        if (grams > MAX_GRAMS) {
            throw new IllegalArgumentException(
                    "grams 超出上限 " + MAX_GRAMS + ": " + grams);
        }
        double k = grams / 100.0;
        return new Nutrition(
                round2(per100g.kcal() * k),
                round2(per100g.protein() * k),
                round2(per100g.carb() * k),
                round2(per100g.fat() * k),
                round2(per100g.fiber() * k),
                round2(per100g.sodium() * k));
    }

    public static Nutrition sum(List<Nutrition> items) {
        double kcal = 0, p = 0, c = 0, f = 0, fi = 0, na = 0;
        for (Nutrition n : items) {
            kcal += n.kcal(); p += n.protein(); c += n.carb();
            f += n.fat();     fi += n.fiber();   na += n.sodium();
        }
        return new Nutrition(round2(kcal), round2(p), round2(c),
                             round2(f), round2(fi), round2(na));
    }

    /** 保留两位小数：避免 0.1+0.2 那类浮点噪声显示成 0.30000000000000004。 */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
