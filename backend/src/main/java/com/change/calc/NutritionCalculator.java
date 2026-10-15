package com.change.calc;

import java.util.List;
import java.util.Objects;

/**
 * 热量与宏量营养素计算。
 *
 * 铁律：所有营养值都按「每 100 克」保存，份量单独保存。
 * 本类只做一件事：把每 100 克的值按实际克数换算出来。
 * 结果永远实时计算，绝不回存——这样改份量、换食物、换单位都不会算错。
 *
 * 命名约定：凡持有 per-100g 基准值的变量/参数一律带 per100 前缀
 * （forGrams 的参数已经这么做）。没有该前缀的，就是实际份量的值。
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
                Round.to2(per100g.kcal() * k),
                Round.to2(per100g.protein() * k),
                Round.to2(per100g.carb() * k),
                Round.to2(per100g.fat() * k),
                Round.to2(per100g.fiber() * k),
                Round.to2(per100g.sodium() * k));
    }

    /**
     * 合计若干份营养值。
     *
     * @param items 待合计的条目；**不得为 null，也不得含 null 元素**
     * @return 合计值；**空列表返回全 0**
     */
    public static Nutrition sum(List<Nutrition> items) {
        Objects.requireNonNull(items, "items 不能为 null");
        double kcal = 0, p = 0, c = 0, f = 0, fi = 0, na = 0;
        for (Nutrition n : items) {
            Objects.requireNonNull(n, "items 不能含 null 元素");
            kcal += n.kcal(); p += n.protein(); c += n.carb();
            f += n.fat();     fi += n.fiber();   na += n.sodium();
        }
        return new Nutrition(Round.to2(kcal), Round.to2(p), Round.to2(c),
                             Round.to2(f), Round.to2(fi), Round.to2(na));
    }
}
