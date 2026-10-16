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
        // NaN 会同时绕过 < 0 与 > MAX_GRAMS 两个判断，一路走到 Round.to2 变成 0.0
        // ——「一餐安静地显示 0 kcal」。这条校验与 Nutrition 紧凑构造器的口径一致。
        if (!Double.isFinite(grams)) {
            throw new IllegalArgumentException("grams 必须是有限值，实际为 " + grams);
        }
        if (grams < 0) {
            throw new IllegalArgumentException("grams 不能为负，实际为 " + grams);
        }
        if (grams > MAX_GRAMS) {
            throw new IllegalArgumentException(
                    "grams 超出上限 " + MAX_GRAMS + "，实际为 " + grams);
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
        // 溢出守卫必须在构造器**之前**：两项各 1.7e308 相加得 Infinity，
        // 不作声地交给 Nutrition 的话，报错会落在 "kcal 必须是有限值" 这个**单字段**名上，
        // 而真相是「合计溢出」——字段名把人指向错误的方向（去看某一项的数据，
        // 而每一项其实都合法）。写成 !(x <= Double.MAX_VALUE) 而不是 Double.isFinite(x)，
        // 理由与包内其他比较类守卫一致：NaN 与任何比较都返回 false，
        // 用 isFinite 会把 NaN 也归到「溢出」这条消息上，而 NaN 是另一回事
        // （它由 Nutrition 的构造器按字段点名，信息更准）。
        if (!(kcal <= Double.MAX_VALUE) || !(p <= Double.MAX_VALUE)
                || !(c <= Double.MAX_VALUE) || !(f <= Double.MAX_VALUE)
                || !(fi <= Double.MAX_VALUE) || !(na <= Double.MAX_VALUE)) {
            throw new IllegalArgumentException(
                    "合计溢出：营养值之和超出 double 可表示范围，请检查输入（kcal=" + kcal
                            + ", protein=" + p + ", carb=" + c + ", fat=" + f
                            + ", fiber=" + fi + ", sodium=" + na + "）");
        }
        return new Nutrition(Round.to2(kcal), Round.to2(p), Round.to2(c),
                             Round.to2(f), Round.to2(fi), Round.to2(na));
    }
}
