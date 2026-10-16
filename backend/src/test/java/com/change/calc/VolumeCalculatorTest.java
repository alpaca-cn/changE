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
        // containsEntry 抓不住**多余键**（实测：往 accumulate 的结果里注入 out.put(999L, 1.0)，
        // 上面三条断言照旧全绿）。「设计文档算例恰好就是胸 / 三头 / 三角前束这三个肌群」
        // 本来就是这条测试的职责，所以这里必须把键集也锁死。
        assertThat(volume).containsOnlyKeys(100L, 101L, 102L);
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
        // ⚠️ 本测试守的是 **Entry 的白名单**，不是 accumulate 的 switch default。
        // 实测：只删掉 Entry 的 role 白名单（让非法状态可表示）时，本测试**照旧全绿**
        // ——异常此时由 accumulate 的 switch default 抛出，消息里有 "tertiary"。
        // 真正守住 switch 那条纵深防线的是**组合变异体**（同时放开白名单 + 把 default
        // 改成 0.0），那时本测试与 invalid_role_is_rejected 才会一起变红。
        // 第 2 道防线为何保留、以及为什么它单测不可达，见计划「规格评审轮记录」第 2 节。
        assertThatThrownBy(() -> VolumeCalculator.accumulate(List.of(entry(1, 3, "tertiary"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tertiary");
    }

    @Test
    void invalid_role_is_rejected() {
        // 与 Unit 同一条思路：让非法状态不可表示，比在下游补救更早。
        // 白名单取自 VolumeCalculator.VALID_ROLES（与 V2__training.sql 的 CHECK 两侧收口）。
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, 3, "tertiary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tertiary");
    }

    @Test
    void null_role_is_rejected_before_the_whitelist() {
        // 顺序是有意的：若把 null 检查放到白名单之后，消息会变成「未知的肌群角色: null」
        // ——那是**另一条**信息（分不清「没填」与「填错」）。
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, 3, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    void non_positive_muscle_id_is_rejected() {
        assertThatThrownBy(() -> new VolumeCalculator.Entry(0, 3, "primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("muscleId");
    }

    @Test
    void negative_sets_on_an_entry_is_rejected() {
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, -1, "primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sets");
    }

    @Test
    void classify_against_target_range() {
        assertThat(VolumeCalculator.classify(0, 10, 20)).isEqualTo(VolumeCalculator.Status.UNTRAINED);
        assertThat(VolumeCalculator.classify(3, 10, 20)).isEqualTo(VolumeCalculator.Status.BELOW);
        assertThat(VolumeCalculator.classify(14, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(26, 10, 20)).isEqualTo(VolumeCalculator.Status.ABOVE);
    }

    @Test
    void zero_set_contribution_does_not_create_a_key() {
        // 对下游只有两种可用状态：有量 / 无量。若建了 0 值键，按 entrySet() 渲染的
        // 热力图就会多画一个不该画的热区。
        assertThat(VolumeCalculator.accumulate(List.of(entry(1, 0, "primary")))).isEmpty();
        assertThat(VolumeCalculator.accumulate(List.of(
                entry(1, 0, "primary"),
                entry(2, 3, "primary"))))
                .containsOnlyKeys(2L);
    }

    @Test
    void null_entries_are_rejected_with_a_useful_message() {
        // 没有这两道校验时，null 会一路流进 for 循环，以 NPE 的形式炸出来——
        //   accumulate(null)          → Cannot invoke "java.util.List.iterator()" because "entries" is null
        //   accumulate([Entry, null]) → Cannot invoke "com.change.calc.VolumeCalculator$Entry.role()" because "e" is null
        // （两条都是实测消息；注意后者点的是 $Entry.role() 而不是 List.iterator()，
        //  曾把这两条弄混。）报错点离调用点很远，消息里也不点出是哪个参数坏了。
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
    void rejects_inverted_target_range() {
        // 区间写反时会静默返回 BELOW（5 < 20 为真），给出一个看起来合理的错答案。
        assertThatThrownBy(() -> VolumeCalculator.classify(5, 20, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min=20")
                .hasMessageContaining("max=10");
    }

    @Test
    void rejects_negative_target_range_lower_bound() {
        assertThatThrownBy(() -> VolumeCalculator.classify(5, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("下限");
    }

    @Test
    void rejects_non_finite_or_negative_sets() {
        // NaN 会同时绕过 sets<=0 / sets<min / sets>max 三个比较，落到 return IN_RANGE，
        // 给出「达标」这个最危险的错答案；负组数会静默变成「没练」。
        assertThatThrownBy(() -> VolumeCalculator.classify(Double.NaN, 10, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sets");
        assertThatThrownBy(() -> VolumeCalculator.classify(-5, 10, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sets");
        // 未配目标时优先报「未配目标」，而不是因为 sets 坏掉而抛异常
        assertThat(VolumeCalculator.classify(Double.NaN, null, null))
                .isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
    }

    @Test
    void range_boundaries_are_inclusive() {
        // 区间是闭区间：等于下限或上限都算 IN_RANGE
        assertThat(VolumeCalculator.classify(10, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(20, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(9.5, 10, 20)).isEqualTo(VolumeCalculator.Status.BELOW);
        assertThat(VolumeCalculator.classify(20.5, 10, 20)).isEqualTo(VolumeCalculator.Status.ABOVE);
    }

    @Test
    void missing_target_range_is_its_own_state_and_short_circuits() {
        // 这条测试钉两件事（原先分在两条方法里，而实测「把 null 短路挪到区间校验之后」
        // 会让**两条同时变红**——边际内容只剩第二件，所以合并）：
        //
        // ①「没配目标」与「没练」是两个不同的事实。若把 null unbox 成 0：
        //   下限 0 → 「练得不够」永久消失；上限 0 → 撞上 max<min 全页抛异常；
        //   两者皆 0 → 任何训练量都判 ABOVE。
        assertThat(VolumeCalculator.classify(25, null, 20)).isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
        assertThat(VolumeCalculator.classify(25, 10, null)).isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
        assertThat(VolumeCalculator.classify(0, null, null)).isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
        // 与真正的「没练」区分开
        assertThat(VolumeCalculator.classify(0, 10, 20)).isEqualTo(VolumeCalculator.Status.UNTRAINED);
        // ② null 短路**排在区间校验之前**：`(null, 0)` 这种看起来写反的配置（max=0 < min 兜底）
        //   不该抛异常，而应直接回报「未设目标」——否则一个 NULL 上限就足以让整页打不开。
        assertThat(VolumeCalculator.classify(5, null, 0)).isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
    }
}
