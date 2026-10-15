package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .hasMessageContaining("碗");   // 提示里要带上可用单位，方便前端显示
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
    void builds_unit_index_from_rows() {
        Map<String, Double> idx = PortionConverter.index(RICE_UNITS);
        assertThat(idx).containsEntry("碗", 150.0).containsEntry("勺", 15.0);
    }

    @Test
    void rejects_non_finite_or_negative_amount() {
        // NaN < 0 是 false，所以不加有限性校验的话它会一路流成「热量显示 NaN」。
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
}
