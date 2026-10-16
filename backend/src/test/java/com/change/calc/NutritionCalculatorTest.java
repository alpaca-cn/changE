package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NutritionCalculatorTest {

    /** 每 100 克含 116 kcal、2.6 g 蛋白的米饭。 */
    private static final Nutrition RICE_PER_100G =
            new Nutrition(116, 2.6, 25.9, 0.3, 0.3, 2.0);

    @Test
    void computes_nutrition_for_150_grams() {
        Nutrition actual = NutritionCalculator.forGrams(RICE_PER_100G, 150);

        assertThat(actual.kcal()).isEqualTo(174.0);
        assertThat(actual.protein()).isEqualTo(3.9);
        assertThat(actual.carb()).isEqualTo(38.85);
    }

    @Test
    void changing_portion_recomputes_from_scratch() {
        // 这条是「不存绝对热量」那条铁律的验收点：
        // 改份量不需要「记得回去改旧值」，因为旧值从来没被存下来。
        Nutrition oneBowl = NutritionCalculator.forGrams(RICE_PER_100G, 150);
        Nutrition oneAndHalf = NutritionCalculator.forGrams(RICE_PER_100G, 225);

        assertThat(oneBowl.kcal()).isEqualTo(174.0);
        assertThat(oneAndHalf.kcal()).isEqualTo(261.0);
        assertThat(oneAndHalf.kcal()).isNotEqualTo(oneBowl.kcal());
    }

    @Test
    void zero_grams_yields_zero() {
        assertThat(NutritionCalculator.forGrams(RICE_PER_100G, 0).kcal()).isZero();
    }

    @Test
    void rejects_negative_grams() {
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grams");
    }

    @Test
    void rejects_absurd_grams() {
        // 份量上限校验：1 万克显然是录错了
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grams");
    }

    @Test
    void sums_a_meal() {
        Nutrition meal = NutritionCalculator.sum(List.of(
                NutritionCalculator.forGrams(RICE_PER_100G, 150),
                new Nutrition(240, 18, 6, 14, 1, 300)));

        assertThat(meal.kcal()).isEqualTo(414.0);
        assertThat(meal.protein()).isEqualTo(21.9);
        assertThat(meal.carb()).isEqualTo(44.85);
        assertThat(meal.fat()).isEqualTo(14.45);
        assertThat(meal.fiber()).isEqualTo(1.45);
        assertThat(meal.sodium()).isEqualTo(303.0);
    }

    @Test
    void rounding_is_exactly_two_decimals() {
        // 5 克的碳水 = 25.9 × 0.05 = 1.295；两位舍入给 1.3，三位会给 1.295。
        // 这条锁住 Round.to2 的精度本身，而不只是「不粗于两位」。
        assertThat(NutritionCalculator.forGrams(RICE_PER_100G, 5).carb()).isEqualTo(1.3);
    }

    @Test
    void accepts_exactly_the_upper_bound_and_rejects_just_above() {
        // 边界：5000 必须被接受，5000.001 必须被拒。
        // 只测「深处越界」（10000）的话，把 > 写成 >= 无人察觉。
        assertThat(NutritionCalculator.forGrams(RICE_PER_100G, 5_000).kcal()).isEqualTo(5_800.0);
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, 5_000.001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grams");
    }

    @Test
    void rejects_non_finite_grams() {
        // NaN 会同时绕过 < 0 与 > MAX_GRAMS 两个判断，一路走到 Round.to2
        // 变成 0.0——「一餐安静地显示 0 kcal」。
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
    }

    @Test
    void rejects_non_finite_and_negative_nutrition() {
        // NaN 经 round2 会变成 0.0（一餐安静地显示 0 kcal）；
        // ∞ 会被饱和成 9.2e16。两者都必须在构造时就拦住。
        assertThatThrownBy(() -> new Nutrition(Double.NaN, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kcal");
        assertThatThrownBy(() -> new Nutrition(0, Double.POSITIVE_INFINITY, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("protein");
        assertThatThrownBy(() -> new Nutrition(0, 0, -1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("carb");
    }

    @Test
    void gram_based_fields_are_validated_too() {
        assertThatThrownBy(() -> new Nutrition(0, 0, 0, Double.NaN, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fat");
        assertThatThrownBy(() -> new Nutrition(0, 0, 0, 0, Double.NEGATIVE_INFINITY, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fiber");
        assertThatThrownBy(() -> new Nutrition(0, 0, 0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sodium");
    }

    @Test
    void sum_of_empty_list_is_all_zero() {
        assertThat(NutritionCalculator.sum(List.of()))
                .isEqualTo(new Nutrition(0, 0, 0, 0, 0, 0));
    }

    @Test
    void sum_rejects_null_list_and_null_elements() {
        assertThatThrownBy(() -> NutritionCalculator.sum(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("items");
        assertThatThrownBy(() -> NutritionCalculator.sum(
                Arrays.asList(RICE_PER_100G, null)))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("不能");
        // 断言用「不能」而不是「null」：删掉 sum 里那行 Objects.requireNonNull(n, ...) 之后
        // 仍然会抛 NPE，而 JDK 的消息本身**也含 "null" 字样**
        // （Cannot invoke "com.change.calc.Nutrition.kcal()" because "n" is null），
        // 用 "null" 断言就恒真了——实测过，它拦不住「删掉校验」的变异。
    }

    @Test
    void sum_reports_overflow_as_an_overflow_not_as_a_single_field() {
        // 两项各 1e308 求和得 Infinity（double 的上限约 1.8e308）。没有这条守卫时，
        // 唯一拦下它的是 Nutrition 的紧凑构造器，报错落在 "kcal 必须是有限值" 这个
        // **单字段**名上——而 kcal 这一项本身完全合法，真相是「合计溢出」。
        // 断言「合计溢出」而不是字段名：这条测试守的正是「别把溢出归咎于某个字段」。
        Nutrition large = new Nutrition(1e308, 1e308, 0, 0, 0, 0);
        assertThatThrownBy(() -> NutritionCalculator.sum(List.of(large, large)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合计溢出");

        // 反过来说：大但可表示的和不能被误报为溢出——守卫若写成 `x > someLimit`
        // 就会把合法输入也拦下。1e308 + 0 仍在 double 范围内。
        assertThat(NutritionCalculator.sum(List.of(large, new Nutrition(0, 0, 0, 0, 0, 0))))
                .as("合法的大值必须放行（注意 Round.to2 会把 1e308 饱和成 9.2e16，那是另一条既有行为）")
                .isEqualTo(NutritionCalculator.sum(List.of(large)));
    }
}
