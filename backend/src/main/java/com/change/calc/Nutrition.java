package com.change.calc;

/**
 * 一份营养值。
 *
 * 单位：热量为 kcal，蛋白质/碳水/脂肪/纤维为克，钠为毫克。
 * （钠用毫克与 USDA 及国内成分表一致；按克读会得到致死量级的数字。）
 *
 * 这个类型同时用于「每 100 克基准值」和「一餐合计值」两种语义——
 * 二者形状相同，record 无法区分。约定：**持有 per-100g 基准值的变量一律带 per100 前缀**
 * （`forGrams` 的参数已经这么做），没有该前缀的就是实际份量的值。
 */
public record Nutrition(double kcal, double protein, double carb,
                        double fat, double fiber, double sodium) {

    public Nutrition {
        requireNonNegativeFinite(kcal, "kcal");
        requireNonNegativeFinite(protein, "protein");
        requireNonNegativeFinite(carb, "carb");
        requireNonNegativeFinite(fat, "fat");
        requireNonNegativeFinite(fiber, "fiber");
        requireNonNegativeFinite(sodium, "sodium");
    }

    /**
     * 拒绝负数与非有限值（NaN / ±Infinity）。
     *
     * 为什么在类型上拦：这两个值一旦流进统计，失效方式都是**静默**的——
     * NaN 经 {@link Round#to2} 会变成 0.0（一餐安静地显示 0 kcal），
     * ∞ 会被饱和成 9.2e16（一个荒谬但看起来像数字的值）。
     * 对会分发给他人的客户端，「看起来对但其实不对」比崩溃更坏。
     */
    private static void requireNonNegativeFinite(double v, String field) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(field + " 必须是有限值，实际为 " + v);
        }
        if (v < 0) {
            throw new IllegalArgumentException(field + " 不能为负，实际为 " + v);
        }
    }
}
