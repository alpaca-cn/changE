package com.change.calc;

import java.util.LinkedHashMap;
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

    /**
     * 合法的肌群角色。必须与 {@code V2__training.sql} 里 {@code exercise_muscle.role}
     * 的 {@code CHECK} 一致（DDL 原文：{@code role IN ('primary', 'secondary')}）。
     *
     * <p>抽成常量是为了让「Java 侧白名单」与「DDL 侧白名单」两侧**互相收口**：
     * {@code SchemaTest.java_whitelist_matches_the_ddl}（参数化用例，同时覆盖
     * {@code exercise_muscle.role} 与 {@code workout_set.set_type}）会把这里的值集与
     * {@code sqlite_master.sql} 里抽出来的值集逐项比对（含顺序），任何一侧改了另一侧就红。
     * 构造器与 {@link #accumulate} 的 {@code switch} 都引用它，所以不存在
     * 「常量改了但校验没跟着改」这种中间态。
     */
    public static final List<String> VALID_ROLES = List.of("primary", "secondary");

    /**
     * 容量与目标区间的比较结果。
     *
     * <b>本枚举混了两个维度</b>：{@link #NOT_CONFIGURED} 说的是「没有参照系」，
     * 其余四态说的是「实际量与参照系的相对位置」。所以**枚举顺序不是严重程度刻度，
     * 禁止依赖 ordinal()**；要按量排序请显式列出那四个态。
     */
    public enum Status {
        /** 该肌群没有配置目标区间（weekly_sets_min/max 为 NULL）。 */
        NOT_CONFIGURED,
        /** 实际 0 组——列在训练里但一组都没做。与 NOT_CONFIGURED 是两个不同的事实。 */
        UNTRAINED,
        /** 有训练量，但低于目标区间下限。 */
        BELOW,
        /** 落在目标区间内（闭区间，等于下限或上限都算）。 */
        IN_RANGE,
        /** 高于目标区间上限。 */
        ABOVE
    }

    /**
     * 一条贡献：某个肌群、若干组、扮演什么角色。
     *
     * 这里校验的是值：{@code exercise_muscle} 表上本来就有
     * {@code role TEXT NOT NULL CHECK (role IN ('primary', 'secondary'))}，
     * 所以 DB 层存不进非法角色——这道校验只是把对象层对齐到既有 schema，
     * 让**非法状态不可表示**，而不是等到下游再补救。
     *
     * <p>为什么不能省掉、只靠 {@link #accumulate} 的 switch：{@code role} 为 null 时
     * {@code switch} 抛的是 NPE（消息形如
     * {@code Cannot invoke "String.hashCode()" because "<...>" is null}），
     * 而非有用的 {@link IllegalArgumentException}——它既没点出 {@code role} 这个字段，
     * 也没点出调用点（实测于 OpenJDK 25），而这个值在「动作 → 肌群」的映射里只是一段
     * 可选的下游数据，出错的地方离得很远。
     * <b>刻意不写编译器内部的局部变量槽位号</b>（这里曾经钉了一个具体的槽位编号，而实测出来的
     * 编号与注释里写的并不是同一个）：那编号换个编译器版本、或者多一个局部变量就会变，
     * 把它钉在注释里只会制造一处随时会过期的「事实」。
     *
     * <p>所以 {@code role} 的 null 检查必须放在白名单之前：放到后面，消息会变成
     * 「未知的肌群角色: null」——那是<b>另一条</b>信息。这个顺序由
     * {@code null_role_is_rejected_before_the_whitelist} 锁死。
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
            if (!VALID_ROLES.contains(role)) {
                throw new IllegalArgumentException(
                        "未知的肌群角色: " + role + "（只接受 " + String.join(" / ", VALID_ROLES) + "）");
            }
        }
    }

    private VolumeCalculator() {
    }

    /**
     * 把一个训练里的所有贡献累加成「肌群 id → 折算组数」。
     *
     * <p>零组的贡献**不创建键**：对下游只有两种可用状态——<b>有量 / 无量</b>；
     * 多出来的 0 值键只会让热力图多画一个不该画的热区。在 Map 层面 `containsKey`
     * 当然分得清「不在 map 里」与「在 map 里且为 0」，分不清的是**按 `entrySet()`
     * 渲染的下游**——所以这里不给 0 值留位置。
     *
     * @param entries 贡献列表；可以跨动作重复出现同一肌群，会累加
     * @return 肌群 id 到折算组数的映射。三条契约：
     *         ①**键序为首次出现序**（用 LinkedHashMap；不要改成 Map.copyOf——那会让每次启动的顺序都变，
     *           而下游是按 entrySet() 渲染热力图的）；
     *         ②值恒为 0.5 的整数倍（0.5 是 2 的幂，累加精确，所以容量这一路不需要舍入）；
     *         ③**缺键表示该肌群 0 组**，调用方用 getOrDefault(id, 0.0) 读取。
     */
    public static Map<Long, Double> accumulate(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries 不能为 null");
        Map<Long, Double> out = new LinkedHashMap<>();
        for (Entry e : entries) {
            Objects.requireNonNull(e, "entries 中不能含 null 元素");
            double weight = switch (e.role()) {
                case "primary" -> 1.0;
                case "secondary" -> 0.5;
                // 纵深防御，正常不可达：Entry 的紧凑构造器已经拒绝了非法角色。
                // 留在这里是因为「合法角色集」将来可能扩容（比如加 'stabilizer'），
                // 那时若忘了改这里，容量会被**静默算成 0**——这条分支让它当场响。
                default -> throw new IllegalArgumentException(
                        "未知的肌群角色: " + e.role() + "（只接受 "
                                + String.join(" / ", VALID_ROLES) + "）");
            };
            if (e.sets() == 0) {
                continue;   // 无量的肌群不建键：多出来的 0 值键会让下游多画一个热区
            }
            out.merge(e.muscleId(), e.sets() * weight, Double::sum);
        }
        return out;
    }

    /**
     * 与建议区间比较。
     *
     * <p>区间参数用装箱类型是<b>有意</b>的：{@code muscle_group.weekly_sets_min/max}
     * 在 schema 里可空且无 CHECK，而「没配目标」是一个与「没练」不同的事实。
     * 如果这里收 {@code int}，服务层就必须 unbox，而任何兜底值都会静默说谎：
     * <ul>
     *   <li>兜成 0 → 下限失效，「练得不够」（{@code BELOW}）这一态永久消失；</li>
     *   <li>上限兜成 0 → 撞上下面的 {@code max < min}，每个肌群都抛异常，整页打不开；</li>
     *   <li>两者都兜成 0 → 任何训练量都判 {@code ABOVE}，用户看到全身标红。</li>
     * </ul>
     * 所以未配目标时直接返回 {@link Status#NOT_CONFIGURED}，并且<b>排在区间校验之前</b>
     * 短路——否则一个 NULL 上限（被兜成 0）就足以把整页变成异常。
     *
     * <p>注意：这个区间是预设的参照系，不是科学结论；用户可以在设置里改。
     *
     * <p>区间本身也要校验：{@code classify(5, 20, 10)}（上下限写反）会返回
     * {@code BELOW}——因为 {@code 5 < 20} 为真——给出一个<b>看起来合理</b>的错答案。
     * 用户可以在设置页逐肌群改这个区间，打错数字完全可能，所以宁可在这里响。
     *
     * @param sets 实际折算组数；必须是有限且非负的。{@code NaN} 与任何比较都返回
     *             {@code false}，所以它<b>同时</b>绕过下面三处比较（{@code sets <= 0} /
     *             {@code sets < min} / {@code sets > max}），一路落到最后的
     *             {@code return IN_RANGE}——给出「达标」这个最危险的错答案。
     *             这条校验<b>排在 null 短路之后</b>是有意的：没配目标的肌群不该因为
     *             实际值坏了而改抛异常
     * @param min  区间下限；不得为负。传 {@code null} 表示该肌群未设目标
     * @param max  区间上限；不得小于下限。传 {@code null} 表示该肌群未设目标。
     *             比较用的是闭区间
     */
    public static Status classify(double sets, Integer min, Integer max) {
        if (min == null || max == null) {
            return Status.NOT_CONFIGURED;
        }
        if (!(sets >= 0)) {
            // 用 !(sets >= 0) 而不是 sets < 0：NaN 与任何比较都返回 false，
            // 所以 NaN 会绕过 sets < 0，一路落到最后的 return IN_RANGE——
            // 给出「达标」这个最危险的错答案。包内另四个数值入口
            // （forGrams / sum / toGrams / epley）都用了同一个套路。
            // 这里曾经真的漏过：classify(NaN, 10, 20) 返回 IN_RANGE（见 package-info 约定三）。
            throw new IllegalArgumentException(
                    "sets 必须是有限且非负的组数，实际为 " + sets);
        }
        if (min < 0) {
            throw new IllegalArgumentException("区间下限不能为负，实际为 " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException(
                    "区间上限不能小于下限，实际为 min=" + min + ", max=" + max);
        }
        if (sets <= 0) return Status.UNTRAINED;
        if (sets < min) return Status.BELOW;
        if (sets > max) return Status.ABOVE;
        return Status.IN_RANGE;
    }
}
