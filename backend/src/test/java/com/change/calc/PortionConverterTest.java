package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PortionConverterTest {

    private static final List<PortionConverter.Unit> RICE_UNITS = List.of(
            new PortionConverter.Unit("碗", 150),
            new PortionConverter.Unit("勺", 15));

    @Test
    void converts_bowl_to_grams() {
        assertThat(PortionConverter.toGrams(1, "碗", RICE_UNITS)).isEqualTo(150.0);
    }

    @Test
    void converts_fractional_units() {
        assertThat(PortionConverter.toGrams(1.5, "碗", RICE_UNITS)).isEqualTo(225.0);
    }

    @Test
    void converts_spoon_to_grams() {
        assertThat(PortionConverter.toGrams(2, "勺", RICE_UNITS)).isEqualTo(30.0);
    }

    @Test
    void null_unit_means_amount_is_already_grams() {
        assertThat(PortionConverter.toGrams(150, null, RICE_UNITS)).isEqualTo(150.0);
    }

    @Test
    void unknown_unit_is_rejected_with_a_useful_message() {
        assertThatThrownBy(() -> PortionConverter.toGrams(1, "碗半", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("碗半")
                // 整段列表：光断言 "碗" 是恒真的（非法输入 "碗半" 里就有它），
                // 断言 "勺" 也只能挡住「列表被整个删掉」，挡不住「列表只剩一种」。
                .hasMessageContaining("可用的单位: 碗、勺");
    }

    @Test
    void three_conversion_paths_agree() {
        // 这是设计文档里点名的必测边界：三种录入路径结果必须一致
        double byBowl   = PortionConverter.toGrams(1, "碗", RICE_UNITS);       // 1 碗
        double bySpoon  = PortionConverter.toGrams(10, "勺", RICE_UNITS);      // 10 勺
        double byGram   = PortionConverter.toGrams(150, null, RICE_UNITS);     // 直接填克数

        assertThat(byBowl).isEqualTo(byGram);
        assertThat(bySpoon).isEqualTo(byGram);
    }

    @Test
    void decimal_amounts_agree_within_tolerance() {
        // 逐位相等只在「份量是 2 的幂」时成立：0.07 碗 × 150 = 10.500000000000002，
        // 而直接录 10.5 克得到 10.5——差 1 ulp。这里锁的是真实不变量（近似相等），
        // 逐位相等那条由 three_conversion_paths_agree 覆盖（它在整数份量下成立）。
        assertThat(PortionConverter.toGrams(0.07, "碗", RICE_UNITS))
                .isCloseTo(10.5, within(1e-9));
        assertThat(PortionConverter.toGrams(1.11, "勺", RICE_UNITS))
                .isCloseTo(16.65, within(1e-9));
    }

    @Test
    void builds_unit_index_from_rows() {
        Map<String, Double> idx = PortionConverter.index(RICE_UNITS);
        assertThat(idx).containsEntry("碗", 150.0).containsEntry("勺", 15.0);
    }

    @Test
    void rejects_non_finite_or_negative_amount() {
        // NaN < 0 是 false，所以不加有限性校验的话它会一路流到 forGrams：
        // 那里 Math.round(NaN) 是 0、Round.to2(NaN) 是 0.0——最终**静默显示 0 kcal**，
        // 比显示 NaN 更糟（NaN 一眼能看出坏了，0 看起来完全正常）。
        assertThatThrownBy(() -> PortionConverter.toGrams(Double.NaN, "碗", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> PortionConverter.toGrams(Double.POSITIVE_INFINITY, "碗", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> PortionConverter.toGrams(-1, "碗", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
    }

    @Test
    void rejects_invalid_unit_definitions() {
        assertThatThrownBy(() -> new PortionConverter.Unit("碗", 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
        assertThatThrownBy(() -> new PortionConverter.Unit("碗", -150))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
        assertThatThrownBy(() -> new PortionConverter.Unit("碗", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
        assertThatThrownBy(() -> new PortionConverter.Unit("  ", 150))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("单位名");
    }

    @Test
    void blank_unit_is_treated_as_grams() {
        // unit 为 null、空串、纯空白都表示「amount 本身就是克数」
        assertThat(PortionConverter.toGrams(150, "", RICE_UNITS)).isEqualTo(150.0);
        assertThat(PortionConverter.toGrams(150, "   ", RICE_UNITS)).isEqualTo(150.0);
    }

    @Test
    void index_rejects_null_list() {
        assertThatThrownBy(() -> PortionConverter.index(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("units");
    }

    @Test
    void index_rejects_duplicate_unit_names() {
        // 数据库的 ux_food_portion 只在 (food_id, unit_name) 上唯一；
        // 混进别的食物的单位时静默取首个会算出看起来合理的错值。
        assertThatThrownBy(() -> PortionConverter.index(List.of(
                new PortionConverter.Unit("碗", 150),
                new PortionConverter.Unit("碗", 200))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("碗")
                .hasMessageContaining("150")
                .hasMessageContaining("200");
    }
}
