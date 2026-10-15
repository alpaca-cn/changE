package com.change.calc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 训练容量统计：把「记了多少组」折算成「每个肌群多少组」。
 *
 * 规则（设计文档决定 5）：
 *   主要肌群 +1，次要肌群 +0.5。
 * 次要肌群算半分是训练量统计的通行做法——卧推对三头确实有刺激，
 * 但不该和臂屈伸算成一样的量。
 *
 * <p><b>本类的错误为什么特别难发现。</b>热量算错了，用户当天就能看出不对；
 * 容量算错了，它只是画出一张<b>看起来合理</b>的人体图热力。所以这里的
 * 失效模式不是「崩溃」，而是「静默地给出一个像样的错答案」，校验一律选「让它响」。
 */
public final class VolumeCalculator {

    public enum Status { UNTRAINED, BELOW, IN_RANGE, ABOVE }

    /**
     * 一条贡献：某个肌群、若干组、扮演什么角色。
     *
     * 这里校验的是值：{@code exercise_muscle} 表上本来就有
     * {@code role TEXT NOT NULL CHECK (role IN ('primary', 'secondary'))}，
     * 所以 DB 层存不进非法角色——这道校验只是把对象层对齐到既有 schema，
     * 让**非法状态不可表示**，而不是等到下游再补救。
     *
     * <p>为什么不能省掉、只靠 {@link #accumulate} 的 switch：{@code role} 为 null 时
     * {@code switch} 抛的 NPE 消息是
     * {@code Cannot invoke "String.hashCode()" because "<local4>" is null}——
     * 它既没点出 {@code role} 这个字段，也没点出调用点（实测于 OpenJDK 25），
     * 而这个值在「动作 → 肌群」的映射里只是一段可选的下游数据，出错的地方离得很远。
     */
    public record Entry(long muscleId, int sets, String role) {

        public Entry {
            if (muscleId <= 0) {
                throw new IllegalArgumentException("muscleId 必须为正，实际为 " + muscleId);
            }
            if (sets < 0) {
                throw new IllegalArgumentException("sets 不能为负，实际为 " + sets);
            }
            if (role == null) {
                throw new IllegalArgumentException("role 不能为 null");
            }
            if (!role.equals("primary") && !role.equals("secondary")) {
                throw new IllegalArgumentException(
                        "未知的肌群角色: " + role + "（只接受 primary / secondary）");
            }
        }
    }

    private VolumeCalculator() {
    }

    /**
     * 把一个训练里的所有贡献累加成「肌群 id → 折算组数」。
     *
     * <p>零组的贡献**不创建键**：下游的人体图是按 {@code map.entrySet()} 渲染的，
     * 建一个值为 0 的键会让那个肌群出现在图上。而「不在 map 里」表示未训练，
     * 「在 map 里且为 0」表示练了 0 组——后者无法与前者区分，属于静默失效，
     * 所以这里不给它留位置。
     *
     * @param entries 贡献列表；可以跨动作重复出现同一肌群，会累加
     * @return 肌群 id → 折算组数；没有贡献的肌群不在 map 里（不是 0）
     */
    public static Map<Long, Double> accumulate(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries 不能为 null");
        Map<Long, Double> out = new HashMap<>();
        for (Entry e : entries) {
            Objects.requireNonNull(e, "entries 中不能含 null 元素");
            double weight = switch (e.role()) {
                case "primary" -> 1.0;
                case "secondary" -> 0.5;
                // 纵深防御，正常不可达：Entry 的紧凑构造器已经拒绝了非法角色。
                // 留在这里是因为「合法角色集」将来可能扩容（比如加 'stabilizer'），
                // 那时若忘了改这里，容量会被**静默算成 0**——这条分支让它当场响。
                default -> throw new IllegalArgumentException(
                        "未知的肌群角色: " + e.role() + "（只接受 primary / secondary）");
            };
            if (e.sets() == 0) {
                continue;   // 「不在 map 里」表示未训练；创建 0 值键会让下游无法区分这两态
            }
            out.merge(e.muscleId(), e.sets() * weight, Double::sum);
        }
        return out;
    }

    /**
     * 与建议区间比较。
     * 注意：这个区间是预设的参照系，不是科学结论；用户可以在设置里改。
     *
     * <p>区间本身也要校验：{@code classify(5, 20, 10)}（上下限写反）会返回
     * {@code BELOW}——因为 {@code 5 < 20} 为真——给出一个<b>看起来合理</b>的错答案。
     * 用户可以在设置页逐肌群改这个区间，打错数字完全可能，所以宁可在这里响。
     *
     * @param sets 实际折算组数
     * @param min  区间下限；不得为负
     * @param max  区间上限；不得小于下限。比较用的是闭区间
     */
    public static Status classify(double sets, int min, int max) {
        if (min < 0) {
            throw new IllegalArgumentException("区间下限不能为负: " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException(
                    "区间上限不能小于下限: min=" + min + ", max=" + max);
        }
        if (sets <= 0) return Status.UNTRAINED;
        if (sets < min) return Status.BELOW;
        if (sets > max) return Status.ABOVE;
        return Status.IN_RANGE;
    }
}
