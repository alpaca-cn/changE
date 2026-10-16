package com.change.calc;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * 估算 1RM。
 *
 * 为什么默认用 1RM 而不是最大重量：最大重量只在冲重量那一次才动，
 * 中间几个月曲线是平的；1RM 由每次的「重量 × 次数」估算，每次训练都能让曲线动一点。
 *
 * 用 Epley 公式：1RM = w × (1 + reps / 30)。
 *
 * Epley 在 reps=1 处的拟合值比实测高 3.33%（w × 31/30），本项目在这一点取实测值——
 * 这与业界计算器的通行呈现一致（它们在 1 rep 一行也显示实测重量）。
 * 理由是定义的：1RM 就是「一组只能做一次的最大重量」，reps = 1 那一次举起的重量
 * 本身就是它，不需要任何外推。
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
     *
     * <p>注意 {@code weight == 0} **能构造出来**（0 是合法重量：上面那条校验只管负数与非有限值），
     * 但它**不可估算**：{@link #bestOf} 把 {@code weight <= 0} 当成「不是一次真实的负重」跳过。
     * 「能构造」与「可估算」是两件事——构造器若拒绝 0，会连带拒掉计时动作
     * {@code new Set(0, null)} 这种正常输入。所以 0 的处置落在 {@code bestOf}，不在这里。
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
     * <p>{@code reps == 1} 时**不外推**，直接返回实测 {@code weight}——
     * 本项目在这一点取实测值而不是 Epley 的拟合值（后者高 3.33%），理由见类注释。
     *
     * <p>注意：本方法是公式本身，不做领域过滤——{@code epley(0, r)} 返回 0。
     * 「0 重量不该产生 1RM 曲线上的点」由 {@link #bestOf} 负责（它会跳过非正重量）。
     * 领域入口是 {@code bestOf}，不要拿本方法的结果直接画图。
     *
     * <p>舍入是不对称的，这是有意的：{@code reps == 1} 走特判、**原值直接返回**、不经舍入
     * （单次的实测重量本身就是精确值，没有需要抹掉的浮点噪声）；
     * {@code reps >= 2} 才走 {@link Round#to2}。
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
        if (reps == 1) {
            // 1RM 的定义就是「一组只能做一次的最大重量」，reps=1 不需要外推。
            // Epley 是给 reps>=2 做的拟合，它在 reps=1 处给 w*31/30，即 3.3% 的系统性高估——
            // 而这是最可靠的一个数据点，高估它会让人不信任整条曲线。
            // 所以本项目在 reps=1 处取实测值，而不是 Epley 的外推值。
            return weight;
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
     * @return 最高的 1RM；**没有任何可估算的组时返回 {@link java.util.OptionalDouble#empty()}**。
     *         刻意不用 0.0 当哨兵：0 是一个看起来像数字的答案，会被画成曲线上的一个点，
     *         把进步曲线拽到地板上（一次只做了平板支撑的训练就是这种情况）。
     *         计时动作（repetitions 为 null）、次数超出 1..12、以及**重量不为正**的组都不可估算。
     */
    public static OptionalDouble bestOf(List<Set> sets) {
        Objects.requireNonNull(sets, "sets 不能为 null");
        OptionalDouble best = OptionalDouble.empty();
        for (Set s : sets) {
            Objects.requireNonNull(s, "sets 不能含 null 元素");
            Integer reps = s.repetitions();
            if (reps == null || reps < 1 || reps > MAX_REPS) {
                continue;   // 计时动作，或次数超出可估算范围
            }
            if (s.weight() <= 0) {
                continue;   // 0 kg 不是一次真实的负重——自重动作记 0 或漏填都会走到这里
            }
            double oneRm = epley(s.weight(), reps);
            if (best.isEmpty() || oneRm > best.getAsDouble()) {
                best = OptionalDouble.of(oneRm);
            }
        }
        return best;
    }
}
