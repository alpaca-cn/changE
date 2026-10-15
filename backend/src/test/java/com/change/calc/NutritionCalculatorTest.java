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
    }
}
