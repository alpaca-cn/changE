package com.change.calc;

import org.junit.jupiter.api.Test;

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
        Nutrition meal = NutritionCalculator.sum(java.util.List.of(
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
        // 这条锁住 round2 的精度本身，而不只是「不粗于两位」。
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
}
