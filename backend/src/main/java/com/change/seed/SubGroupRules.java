package com.change.seed;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从「父肌群 + 动作名」推出「子部位」。
 *
 * <h2>为什么需要这个类，而不是一张静态对照表</h2>
 *
 * <p>{@code free-exercise-db} 的 {@code primaryMuscles} 只到粗肌群（{@code chest} /
 * {@code shoulders} / {@code traps} / {@code middle back} / {@code lower back}），
 * 所以「某个动作练的是上胸还是下胸」**在数据集里不存在**，只能从这个动作的名字推。
 *
 * <p>分类结果与依据写在 {@code docs/动作到子部位映射.md}，那是人眼审过的表；
 * 本类是那张表的**唯一实现** —— 导入器用它，补做历史数据的
 * {@code SubGroupRemapRunner} 也用它。两处共用一份规则，就不会出现
 * 「新装用户是细的、老用户是粗的」这种最难查的不一致。
 *
 * <h2>两条来源，可信度不同</h2>
 *
 * <ul>
 *   <li><b>名字写明</b>：{@code Incline Bench Press} → 上胸。不会错。</li>
 *   <li><b>按主导推断</b>：{@code Shoulder Press} → 前束主 + 中束辅。
 *       解剖上过头推举主要靠三角肌前束。这一类是**可以被推翻的** ——
 *       用户觉得哪个动作分错了，改 {@link #OVERRIDE} 里那一行即可。</li>
 * </ul>
 *
 * <p>没有「判断不了就留父级」这个出口：用户明确选了策略 B（每个动作都落一个子部位）。
 */
public final class SubGroupRules {

    private SubGroupRules() {}

    /** 一个子部位归属。{@code primary} 与前缀里的角色对应（主/次要）。 */
    public record Part(String nameEn, boolean primary) {}

    // ── 后侧：命中即后束，优先级最高 ──────────────────────────
    private static final Pattern REAR = Pattern.compile(
            "\\brear delt|\\breverse fly|\\breverse machine fly|\\bback fly|\\bface pull"
                    + "|\\bpull apart|\\bexternal rotation|\\bcuban|\\brow to neck"
                    + "|\\blow-pulley side lateral|\\bbent over .*lateral|\\bsled reverse|\\blying rear");

    // ── 前侧 ─────────────────────────────────────────────────
    private static final Pattern FRONT = Pattern.compile(
            "\\bfront raise\\b|\\bfront delt|\\bfront .*raise|\\bincline shoulder raise"
                    + "|\\bstraight-arm front|\\bcar drivers");

    // ── 中束 ─────────────────────────────────────────────────
    private static final Pattern LATERAL = Pattern.compile(
            "\\blateral raise\\b|\\bside lateral|\\blaterals\\b|\\bdeltoid raise\\b"
                    + "|\\bupright row\\b|\\bscaption\\b|\\bcrucifix\\b|\\biron cross\\b"
                    + "|\\bpower partials\\b|\\bcircles\\b|\\bstretch\\b|\\boverhead .*raise");

    // ── 推举类 ───────────────────────────────────────────────
    private static final Pattern PRESS = Pattern.compile(
            "\\bpress\\b|\\bpresses\\b|\\bjerk\\b|\\bsnatch\\b|\\bclean\\b|\\bthruster\\b"
                    + "|\\blog lift\\b|\\bhalo\\b|\\bmilitary\\b|\\bjammer\\b|\\bdelivery\\b");

    /**
     * 名字里没有部位线索、但解剖上明确的少数几个，逐个点名。
     *
     * <p>2026-10-23 逐个看过 129 个肩部动作之后补的。键是**小写动作名**。
     */
    private static final Map<String, List<Part>> OVERRIDE = Map.ofEntries(
            Map.entry("battling ropes", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            Map.entry("backward medicine ball throw", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            Map.entry("medicine ball scoop throw", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            Map.entry("standing two-arm overhead throw", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            Map.entry("shoulder raise", List.of(new Part("shoulders-lateral", true))),
            Map.entry("dumbbell raise", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            Map.entry("single dumbbell raise", List.of(new Part("shoulders-lateral", true))),
            Map.entry("arm drill", List.of(new Part("shoulders-lateral", true))),
            // 俯卧斜板直臂举：解剖上是后束
            Map.entry("straight raises on incline bench", List.of(new Part("shoulders-posterior", true))),
            Map.entry("kneeling arm drill", List.of(new Part("shoulders-lateral", true))),
            Map.entry("side wrist pull", List.of(new Part("shoulders-lateral", true))),
            Map.entry("sled overhead backward walk", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            Map.entry("return push from stance", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            Map.entry("anti-gravity press", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            Map.entry("circus bell", List.of(new Part("shoulders-anterior", true),
                    new Part("shoulders-lateral", false))),
            // 颈后推举：中束承担更多，主次对调
            Map.entry("push press - behind the neck", List.of(new Part("shoulders-lateral", true),
                    new Part("shoulders-anterior", false))),
            Map.entry("standing barbell press behind neck", List.of(new Part("shoulders-lateral", true),
                    new Part("shoulders-anterior", false))));

    /** 哪些父组是拆开的。返回空表示这个父组不拆，调用方应保持原样。 */
    public static boolean isSplit(String parentEn) {
        return switch (parentEn) {
            case "chest", "shoulders", "traps", "middle back", "lower back" -> true;
            default -> false;
        };
    }

    /**
     * @param parentEn        数据集给出的粗肌群名（{@code chest} …）
     * @param exerciseNameEn  动作英文名；为 null/空白时按主导兜底
     * @return 子部位列表（第一个是主部位）；父组不拆分时返回空列表
     */
    public static List<Part> partsFor(String parentEn, String exerciseNameEn) {
        if (parentEn == null || !isSplit(parentEn)) {
            return List.of();
        }
        String name = exerciseNameEn == null ? "" : exerciseNameEn.toLowerCase(Locale.ROOT).trim();

        List<Part> override = OVERRIDE.get(name);
        if (override != null && override.get(0).nameEn().startsWith(parentEn)) {
            return override;
        }

        return switch (parentEn) {
            case "chest" -> {
                if (REAR.matcher(name).find() && name.contains("chest")) {
                    yield List.of();   // 不该发生：胸部动作不会命中后束词
                }
                if (name.contains("incline")) {
                    yield List.of(new Part("chest-upper", true));
                }
                if (name.contains("decline") || name.contains("dip")) {
                    yield List.of(new Part("chest-lower", true));
                }
                yield List.of(new Part("chest-middle", true));
            }
            case "shoulders" -> {
                if (REAR.matcher(name).find()) {
                    yield List.of(new Part("shoulders-posterior", true));
                }
                if (FRONT.matcher(name).find()) {
                    yield List.of(new Part("shoulders-anterior", true),
                            new Part("shoulders-lateral", false));
                }
                if (LATERAL.matcher(name).find()) {
                    yield List.of(new Part("shoulders-lateral", true));
                }
                if (PRESS.matcher(name).find()) {
                    // 过头推举：前束主导、中束辅助
                    yield List.of(new Part("shoulders-anterior", true),
                            new Part("shoulders-lateral", false));
                }
                yield List.of(new Part("shoulders-lateral", true));
            }
            case "traps" -> {
                if (name.contains("shrug") || name.contains("upright row")) {
                    yield List.of(new Part("traps-upper", true));
                }
                if (name.contains("row") || name.contains("scapular")
                        || name.contains("high pull") || name.contains("ascending")) {
                    yield List.of(new Part("traps-middle", true));
                }
                yield List.of(new Part("traps-upper", true));
            }
            case "middle back" -> List.of(new Part("middle back-rhomboids", true));
            case "lower back" -> List.of(new Part("lower back-erector", true));
            default -> List.of();
        };
    }
}
