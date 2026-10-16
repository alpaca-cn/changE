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
    void single_rep_returns_the_weight_itself() {
        // 1RM 的定义就是「一组只能做一次的最大重量」。Epley 在 reps=1 处给 w*31/30（103.33），
        // 那是 3.3% 的系统性高估——最可靠的数据点反而最不该外推。所以 reps=1 直接返回实测重量。
        assertThat(OneRepMax.epley(100, 1)).isEqualTo(100.0);
        assertThat(OneRepMax.epley(62.5, 1)).isEqualTo(62.5);
        // reps=2 起才走 Epley
        assertThat(OneRepMax.epley(100, 2)).isEqualTo(Round.to2(100 * (1 + 2 / 30.0)));
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
        // 顺序刻意打乱、让最大值落在中间：否则「取最大」与「取第一个」不可区分。
        // 60x8 = 76.0（最大，放中间）；62.5x6 = 75.0；65x4 = 73.67
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(62.5, 6),
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(65, 4)))
                .orElseThrow();

        assertThat(best).isEqualTo(76.0);
    }

    @Test
    void timed_sets_are_ignored() {
        // 平板支撑没有 1RM 的概念，传 null 次数应被跳过而不是抛错
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(0, null)))
                .orElseThrow();

        assertThat(best).isEqualTo(76.0);
    }

    @Test
    void no_estimable_sets_yields_empty_not_zero() {
        // 0.0 是个看起来像数字的哨兵，会被画成曲线上的一个点。
        // 「没有可估算的组」与「1RM 是 0 kg」必须可区分（包内约定四）。
        assertThat(OneRepMax.bestOf(java.util.List.of())).isEmpty();
        assertThat(OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(0, null)))).isEmpty();                       // 只有计时动作
        assertThat(OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 0)))).isEmpty();                         // 次数 0
        assertThat(OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 20)))).isEmpty();                        // 次数超上限
        assertThat(OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(0, 8)))).isEmpty();                          // 重量为 0
    }

    @Test
    void epley_validates_its_own_inputs() {
        // 直接测公开入口：Set 的构造器会先挡住负数与非法重复次数，
        // 所以这三条守卫只有绕过 Set 才走得到（变异测试实测它们此前无覆盖）。
        assertThatThrownBy(() -> OneRepMax.epley(60, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reps");
        assertThatThrownBy(() -> OneRepMax.epley(-60, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> OneRepMax.epley(60, 13))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("12");
    }

    @Test
    void out_of_range_reps_are_skipped_not_thrown_by_best_of() {
        // javadoc 承诺「超范围跳过」而不是抛错——这条契约此前没有测试守住。
        // 一次训练里混进 20 次的组不该让整条 bestOf 炸掉。
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 20),
                new OneRepMax.Set(62.5, 6)))
                .orElseThrow();

        assertThat(best).isEqualTo(75.0);
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
        // 0 kg 也是合法的（能构造），只是不可估算——由 bestOf 跳过，不由构造器拒绝。
        // 拒绝它会连带拒掉计时动作 new Set(0, null)。
        assertThat(new OneRepMax.Set(0, 8).weight()).isZero();
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
