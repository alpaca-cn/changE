package com.change.calc;

import java.util.List;
import java.util.Objects;

/**
 * 估算 1RM。
 *
 * 为什么默认用 1RM 而不是最大重量：最大重量只在冲重量那一次才动，
 * 中间几个月曲线是平的；1RM 由每次的「重量 × 次数」估算，每次训练都能让曲线动一点。
 *
 * 用 Epley 公式：1RM = w × (1 + reps / 30)。
 */
public final class OneRepMax {

    /** 超过这个次数后 Epley 误差迅速变大，不再估算。 */
    public static final int MAX_REPS = 12;

    /**
     * 一组的数据。
     *
     * {@code repetitions} 为 null 表示计时动作（平板支撑等），{@link #bestOf} 会跳过它；
     * 而 {@code repetitions == 0} 是**另一回事**——「这一组记了但没次数」，
     * 它是合法输入（构造器放行），由 {@link #bestOf} 按 {@code < 1} 跳过。
     * 两者的区别是「没有次数这个概念」与「有这个概念但值是 0」，别把它们合并成一个状态。
     *
     * <p>校验放在紧凑构造器里，沿用包内约定：值对象内部字段非法抛
     * {@link IllegalArgumentException}（见 {@link Nutrition} 与 {@link VolumeCalculator.Entry}）。
     * 这里挡掉的是 {@code NaN} 与 {@code ±∞}：{@code NaN} 会绕过 {@code weight < 0}
     * （比较恒为 false），一路走到 {@link Round#to2} 变成 {@code 0.0}——
     * 静默给出「1RM 是 0」这个看起来像数字的错答案。
     */
    public record Set(double weight, Integer repetitions) {

        public Set {
            if (!Double.isFinite(weight)) {
                throw new IllegalArgumentException("weight 必须是有限值，实际为 " + weight);
            }
            if (weight < 0) {
                throw new IllegalArgumentException("weight 不能为负，实际为 " + weight);
            }
            if (repetitions != null && repetitions < 0) {
                throw new IllegalArgumentException("repetitions 不能为负，实际为 " + repetitions);
            }
        }
    }

    private OneRepMax() {
    }

    /**
     * Epley 公式估算单组 1RM。
     *
     * @param weight 重量；必须是有限且非负的
     * @param reps   次数；{@code 1..}{@link #MAX_REPS}。超过 {@link #MAX_REPS} 直接拒绝——
     *               Epley 在 15 次以上误差迅速变大，宁可不算也不要给个假精确的数
     */
    public static double epley(double weight, int reps) {
        if (reps < 1) {
            throw new IllegalArgumentException("reps 必须 >= 1: " + reps);
        }
        if (reps > MAX_REPS) {
            throw new IllegalArgumentException(
                    "reps 超过 " + MAX_REPS + " 时 Epley 估算不可靠: " + reps);
        }
        if (!Double.isFinite(weight)) {
            // 排在 weight < 0 之前：NaN 与任何比较都返回 false，会绕过 `weight < 0`，
            // 一路走到 Round.to2(NaN) = 0.0（静默返回 0），∞ 则被饱和成 9.2e16。
            throw new IllegalArgumentException("weight 必须是有限值，实际为 " + weight);
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight 不能为负: " + weight);
        }
        return Round.to2(weight * (1 + reps / 30.0));
    }

    /**
     * 一次训练里某个动作的所有组，取估算出的最大 1RM。
     *
     * 计时动作（{@code repetitions} 为 null）与超范围的次数**跳过而不是抛错**：
     * 一次训练里混着平板支撑和卧推是正常的，因为一组平板支撑让整条曲线算不出来才是不正常的。
     *
     * @param sets 组列表；不能为 null，也不能含 null 元素
     * @return 最大的估算 1RM；列表为空（或全部被跳过）时返回 {@code 0}，
     *         这个 0 表示「本次没有可估算的组」，不是「1RM 是 0」
     */
    public static double bestOf(List<Set> sets) {
        Objects.requireNonNull(sets, "sets 不能为 null");
        double best = 0;
        for (Set s : sets) {
            Objects.requireNonNull(s, "sets 不能含 null 元素");
            if (s.repetitions() == null || s.repetitions() < 1 || s.repetitions() > MAX_REPS) {
                continue;   // 计时动作或超范围，跳过
            }
            best = Math.max(best, epley(s.weight(), s.repetitions()));
        }
        return best;
    }
}
