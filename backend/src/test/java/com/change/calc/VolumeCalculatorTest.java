package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VolumeCalculatorTest {

    /** 一个动作在一次训练里的若干组，以及它牵连的肌群。 */
    private static VolumeCalculator.Entry entry(long muscleId, int sets, String role) {
        return new VolumeCalculator.Entry(muscleId, sets, role);
    }

    @Test
    void primary_muscle_counts_one_per_set() {
        var volume = VolumeCalculator.accumulate(List.of(entry(1, 4, "primary")));
        assertThat(volume).containsEntry(1L, 4.0);
    }

    @Test
    void secondary_muscle_counts_half_per_set() {
        var volume = VolumeCalculator.accumulate(List.of(entry(2, 4, "secondary")));
        assertThat(volume).containsEntry(2L, 2.0);
    }

    @Test
    void bench_press_example_from_the_design_doc() {
        // 设计文档里的算例：卧推 4 组 → 胸 +4、三头 +2、三角肌前束 +2
        var volume = VolumeCalculator.accumulate(List.of(
                entry(100, 4, "primary"),     // 胸
                entry(101, 4, "secondary"),   // 三头
                entry(102, 4, "secondary"))); // 三角肌前束

        assertThat(volume).containsEntry(100L, 4.0)
                          .containsEntry(101L, 2.0)
                          .containsEntry(102L, 2.0);
    }

    @Test
    void same_muscle_from_two_exercises_accumulates() {
        // 卧推 4 组让三头 +2，绳索下压 3 组让三头 +3，合计 5
        var volume = VolumeCalculator.accumulate(List.of(
                entry(101, 4, "secondary"),
                entry(101, 3, "primary")));

        assertThat(volume).containsEntry(101L, 5.0);
    }

    @Test
    void unknown_role_is_rejected() {
        assertThatThrownBy(() -> VolumeCalculator.accumulate(List.of(entry(1, 3, "tertiary"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tertiary");
    }

    @Test
    void classify_against_target_range() {
        assertThat(VolumeCalculator.classify(0, 10, 20)).isEqualTo(VolumeCalculator.Status.UNTRAINED);
        assertThat(VolumeCalculator.classify(3, 10, 20)).isEqualTo(VolumeCalculator.Status.BELOW);
        assertThat(VolumeCalculator.classify(14, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(26, 10, 20)).isEqualTo(VolumeCalculator.Status.ABOVE);
    }

    @Test
    void invalid_role_cannot_even_be_constructed() {
        // 与 Unit 同一条思路：让非法状态不可表示，比在下游补救更早。
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, 3, "tertiary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tertiary");
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, 3, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
        assertThatThrownBy(() -> new VolumeCalculator.Entry(0, 3, "primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("muscleId");
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, -1, "primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sets");
    }

    @Test
    void zero_set_contribution_does_not_create_a_key() {
        // 「不在 map 里」= 未训练；「在 map 里且为 0」= 练了 0 组。
        // 后者无法与前者区分，而 UI 是按键渲染的，会让该肌群出现在图上。
        assertThat(VolumeCalculator.accumulate(List.of(entry(1, 0, "primary")))).isEmpty();
        assertThat(VolumeCalculator.accumulate(List.of(
                entry(1, 0, "primary"),
                entry(2, 3, "primary"))))
                .containsOnlyKeys(2L);
    }

    @Test
    void null_entries_are_rejected_with_a_useful_message() {
        // 没有这两道校验时，null 会一路流进 for 循环，以 NPE 的形式炸出来——
        // 消息是 JDK 内部的 Cannot invoke "Entry.role()" / "String.hashCode()"，
        // 报错点离调用点很远，消息里也不点出是哪个参数坏了。
        //
        // 断言用「不能」而不是「null」：那些 JDK 消息本身**也含 "null" 字样**，
        // 用 "null" 断言就恒真了——实测过，它拦不住「删掉校验」的变异。
        assertThatThrownBy(() -> {
            VolumeCalculator.accumulate(null);
        }).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("不能");
        assertThatThrownBy(() -> {
            VolumeCalculator.accumulate(Arrays.asList(entry(1, 3, "primary"), null));
        }).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("不能");
    }

    @Test
    void rejects_inverted_or_negative_target_range() {
        // 区间写反时会静默返回 BELOW（5 < 20 为真），给出一个看起来合理的错答案。
        assertThatThrownBy(() -> VolumeCalculator.classify(5, 20, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min=20")
                .hasMessageContaining("max=10");
        assertThatThrownBy(() -> VolumeCalculator.classify(5, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("下限");
    }

    @Test
    void range_boundaries_are_inclusive() {
        // 区间是闭区间：等于下限或上限都算 IN_RANGE
        assertThat(VolumeCalculator.classify(10, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(20, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(9.5, 10, 20)).isEqualTo(VolumeCalculator.Status.BELOW);
        assertThat(VolumeCalculator.classify(20.5, 10, 20)).isEqualTo(VolumeCalculator.Status.ABOVE);
    }
}
