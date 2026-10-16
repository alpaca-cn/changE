package com.change.calc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OneRepMaxTest {

    @Test
    void epley_for_a_typical_set() {
        // Epley: 1RM = w * (1 + reps/30)
        // 62.5 * (1 + 6/30) = 62.5 * 1.2 = 75.0
        assertThat(OneRepMax.epley(62.5, 6)).isEqualTo(75.0);
    }

    @Test
    void single_rep_is_still_scaled_by_epley() {
        // ⚠️ 这是第二处修正（原测试名 single_rep_returns_the_weight_itself，期望 100.0）。
        // 标准 Epley 在 reps=1 时**不**返回重量本身：100 × (1 + 1/30) = 100 × 31/30
        //   = 3100/30 = 103.333… → 103.33（实测 "expected: 100.0 but was: 103.33"）。
        // 「reps=1 时 1RM 就是举起的那个重量」是**物理事实**，也确实是 Brzycki 公式
        // （w × 36/(37−reps)，reps=1 时恰好退化成 w）的性质——原测试名沿用的是 Brzycki 的直觉，
        // 与计划里写死的 Epley 公式（1RM = w × (1 + reps/30)）不是同一个公式。
        // 这里按 Epley 的算术结果断言，并把测试名改成与断言相符的说法：
        // 一个叫「返回重量本身」却断言 103.33 的测试，本身就是一处会误导人的不实自述。
        assertThat(OneRepMax.epley(100, 1)).isEqualTo(103.33);
    }

    @Test
    void high_reps_are_rejected_because_the_formula_degrades() {
        // 超过 12 次后 Epley 的误差迅速变大，宁可不算也不要给个假精确的数
        assertThatThrownBy(() -> OneRepMax.epley(60, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12");
    }

    @Test
    void best_of_a_session_picks_the_max() {
        // ⚠️ 这是修正：计划里这条原写 75.0，与同文件 timed_sets_are_ignored 对
        // 同一组 Set(60, 8) 断言的 76.0 自相矛盾。独立核算（标准 Epley，1RM = w × (1 + reps/30)）：
        //   60   × (1 + 8/30) = 60   × 38/30 = 2280/30 = 76.00   ← 最大
        //   62.5 × (1 + 6/30) = 62.5 × 1.2   = 75.00
        //   65   × (1 + 4/30) = 65   × 34/30 = 2210/30 = 73.666… → 73.67
        // 所以正确值是 76.0；实现（标准 Epley）是对的，改的是这条期望值。
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(62.5, 6),
                new OneRepMax.Set(65, 4)));

        assertThat(best).isEqualTo(76.0);
    }

    @Test
    void timed_sets_are_ignored() {
        // 平板支撑没有 1RM 的概念，传 null 次数应被跳过而不是抛错
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(0, null)));

        assertThat(best).isEqualTo(76.0);
    }

    @Test
    void rejects_non_finite_weight() {
        // NaN 会绕过 weight < 0（比较恒为 false），一路走到 Round.to2 变成 0.0——
        // 静默给出「1RM 是 0」这个看起来像数字的错答案。
        assertThatThrownBy(() -> OneRepMax.epley(Double.NaN, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> OneRepMax.epley(Double.POSITIVE_INFINITY, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
    }

    @Test
    void set_validates_its_own_fields() {
        assertThatThrownBy(() -> new OneRepMax.Set(Double.NaN, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> new OneRepMax.Set(-60, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> new OneRepMax.Set(60, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repetitions");
        // 0 次是合法的（记了但没次数），与 null（计时动作）不同
        assertThat(new OneRepMax.Set(60, 0).repetitions()).isZero();
    }

    @Test
    void best_of_rejects_null_input() {
        // ⚠️ 断言用「不能」而不是「null」：JDK 裸 NPE 的消息里本来就含 "null"
        //（Cannot invoke "..." because "s" is null），用 "null" 做断言会恒真——
        // 删掉 requireNonNull 之后测试照样绿。这个坑在 Task 6/7 各踩过一次。
        assertThatThrownBy(() -> OneRepMax.bestOf(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("不能");
        assertThatThrownBy(() -> OneRepMax.bestOf(java.util.Arrays.asList(new OneRepMax.Set(60, 5), null)))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("不能");
    }
}
