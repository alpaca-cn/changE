package com.change.calc;

/**
 * 舍入工具。
 *
 * 为什么单独抽出来：饮食与 1RM 两处都需要「保留两位小数」，
 * 而它各自私有会导致两份逐字相同的拷贝——改一处就得记得改两处。
 * 训练容量**不需要**它：那里的值恒为 0.5 的整数倍（见
 * {@link VolumeCalculator#accumulate} 的 @return），累加与比较都是精确的。
 *
 * 为什么用 Math.round：它按「四舍五入远离零」处理 .5，
 * 且对负数对称（-1.005 → -1.01 而非 -1.00），适合营养与重量这类只关心量级的场景。
 */
final class Round {

    private Round() {
    }

    /** 保留两位小数：避免 0.1+0.2 那类浮点噪声显示成 0.30000000000000004。 */
    static double to2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
