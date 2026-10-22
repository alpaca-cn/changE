package com.change.agent;

import com.change.service.BodyMetricService;
import com.change.service.ExerciseQueryService;
import com.change.service.FoodQueryService;
import com.change.service.NutritionService;
import com.change.service.WeeklyVolumeService;
import com.change.service.WorkoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体可调用的工具集。**白名单，不是让模型自由写 SQL**（设计文档决定 4）。
 *
 * ## 安全线的三条，在这个文件里怎么落地
 *
 * 1. **白名单**：工具在一张不可变的 {@code Map} 里。模型只能在 {@link #specs()} 里挑，
 *    而 {@link #execute} 查不到就**抛异常**——不静默忽略、不返回空。模型幻觉出一个
 *    不存在的工具名时，我们要么明确报错让它重来，要么让整轮失败；装作成功是最坏的。
 * 2. **只允许新增与修改，不允许删除**：所以这里没有任何 delete 工具，
 *    也没有「把某条改成空」这类能实现删除的间接入口。
 * 3. **每次改动写 ChangeLog**：写入全部走三个服务，而它们已经带 {@code origin} 重载
 *    ——这里一律传 {@link #AGENT}，于是变更日志能分清「谁写的」，undo 才有依据。
 *
 * ## 为什么参数用 JSON 字符串而不是方法参数
 *
 * 工具是从模型输出里**动态派发**的，调用点在编译期不知道是哪个工具。
 * 用 {@code Function<JsonNode, String>} 统一形状，{@link #execute} 就是一次查表 + 一次调用，
 * 没有反射、没有字符串拼方法名。每个工具自己负责把 JSON 读成强类型再交给服务。
 */
@Component
public class AgentTools {

    /** 智能体写入时使用的来源标记，与 change_log.origin 的 CHECK 取值一致。 */
    public static final String AGENT = "agent";

    /** 一次查询最多返回多少条，防止把整个库塞进 prompt。 */
    private static final int LIST_LIMIT = 20;

    private final ObjectMapper json;
    private final Map<String, Tool> tools;

    /** 一个工具 = 给模型看的定义 + 真正执行它。 */
    private record Tool(LlmClient.ToolSpec spec, Runner run) {}

    /** 执行器：吃解析好的参数，吐一段给模型看的结果文本。失败就抛，不要吞。 */
    @FunctionalInterface
    private interface Runner {
        String run(JsonNode args) throws Exception;
    }

    public AgentTools(WeeklyVolumeService volume, WorkoutService workouts,
                      NutritionService nutrition, ExerciseQueryService exercises,
                      FoodQueryService foods, BodyMetricService metrics, ObjectMapper json) {
        this.json = json;
        Map<String, Tool> m = new LinkedHashMap<>();

        // ── 只读 ──────────────────────────────────────────────
        put(m, "weekly_volume", "查本周（周一起算）每块肌群的折算组数与建议区间。回答「这周练得怎么样」先调它。",
                schema(Map.of()),
                a -> write(volume.weekly(LocalDate.now())));

        put(m, "recent_sessions", "查最近的训练记录（日期、标题、主观状态）。查某个动作的历史表现时先看它。",
                schema(Map.of()),
                a -> write(workouts.recentSessions().stream().limit(LIST_LIMIT).toList()));

        put(m, "today_nutrition", "查今天的总热量与三大营养素，以及吃了几餐几条。",
                schema(Map.of()),
                a -> write(nutrition.daily(LocalDate.now())));

        put(m, "search_exercises", "按中英文名搜动作，返回 id 与名字。写训练前先用它拿到 exerciseId。",
                schema(Map.of("query", stringProp("中文名或英文名的一部分，例如 卧推 / bench"))),
                a -> write(exercises.search(null, text(a, "query"), null, 8)));

        // 这一条是「说话就是录入」能不能成立的关键：record_meal 要 foodId，
        // 而没有搜食物的工具时，模型只能猜 id —— 猜出来的必然是别人的食物。
        put(m, "search_foods", "按名称搜食物，返回 id、每 100 克营养值与份量单位。"
                        + "写饮食前**必须**先用它拿到 foodId，不要猜。",
                schema(Map.of("query", stringProp("食物名的一部分，例如 鸡胸 / 米饭 / 酸奶"))),
                a -> write(foods.search(text(a, "query"), 8)));

        // 拍照记饮食能不能落地的关键一条。
        //
        // 模型从图里认出「黄焖鸡米饭」，而库里 35 条预置食物里没有它 ——
        // 没有这个工具，模型只能二选一：**拿一个近似食物顶替**（记错），
        // 或者**放弃**（白看了图）。两者都不可接受，前者更糟：它会静默地把
        // 一顿 800 千卡的饭记成 400 千卡，而用户以为记对了。
        //
        // 所以给它「自己建一条」的能力。建完再 record_meal，链就通了。
        put(m, "create_food", "库里搜不到某种食物时，用这个新建一条，之后就能用它记饮食。"
                        + "参数都是**这一份**的值，不是每 100 克 —— 用户说「一份 400 克、蛋白 28 克」，"
                        + "就填 grams=400, protein=28。搜到了就**不要**建，直接用 search_foods 的结果。",
                schema(Map.of(
                        "name", stringProp("食物名字，例如 黄焖鸡米饭（大份）"),
                        "grams", numberProp("这一份有多少克，必须大于 0"),
                        "protein", numberProp("这一份含多少克蛋白质；不知道就填 0"),
                        "fat", numberProp("这一份含多少克脂肪；不知道就填 0"),
                        "carb", numberProp("这一份含多少克碳水；不知道就填 0"),
                        "kcal", numberProp("可选：这一份的热量。留空则按 4/4/9 从三大营养素推算"))),
                a -> write(foods.create(new com.change.service.FoodQueryService.CustomFoodInput(
                        text(a, "name"),
                        null,
                        num(a, "grams").asDouble(),
                        numOr0(a, "protein"),
                        numOr0(a, "fat"),
                        numOr0(a, "carb"),
                        a.has("kcal") && a.get("kcal").isNumber() ? a.get("kcal").asDouble() : null))));

        put(m, "body_metrics", "查身体数据每种指标的最新值（体重、体脂、各围度）。",
                schema(Map.of()),
                a -> write(metrics.types()));

        // ── 写入 ──────────────────────────────────────────────
        put(m, "record_workout", "记一次训练（一次会话，一个动作，若干组）。写完会进变更日志，可一键回退。",
                schema(Map.of(
                        "date", stringProp("训练日期，YYYY-MM-DD；不填就是今天"),
                        "title", stringProp("给这次训练起个名，例如 胸 · 三头"),
                        "exerciseId", numberProp("动作 id，必须来自 search_exercises 的结果"),
                        "sets", new ArrayProp("组，至少一条",
                                Map.of("weight", numberProp("重量 kg，自重动作可不填"),
                                        "reps", numberProp("次数"))))),
                a -> {
                    long exerciseId = num(a, "exerciseId").longValue();
                    JsonNode sets = a.path("sets");
                    if (!sets.isArray() || sets.isEmpty()) {
                        throw new IllegalArgumentException("sets 至少要有一条");
                    }
                    List<WorkoutService.SetInput> list = new ArrayList<>();
                    for (JsonNode s : sets) {
                        list.add(new WorkoutService.SetInput("normal",
                                s.hasNonNull("weight") ? s.get("weight").asDouble() : null,
                                s.hasNonNull("reps") ? s.get("reps").asInt() : null,
                                null, false, null, null, true, null, null));
                    }
                    var r = workouts.recordSession(new WorkoutService.SessionInput(
                            date(a), text(a, "title"), null, null, null, null,
                            List.of(new WorkoutService.ExerciseInput(exerciseId, null, null, list))), AGENT);
                    return write(Map.of("ok", true, "sessionId", r.sessionId(), "sets", r.sets()));
                });

        put(m, "record_meal", "记一餐（一个餐次下若干条目）。写完会进变更日志，可一键回退。",
                schema(Map.of(
                        "date", stringProp("日期 YYYY-MM-DD；不填就是今天"),
                        "slot", stringProp("餐次：breakfast / lunch / dinner / snack"),
                        "items", new ArrayProp("条目，至少一条",
                                Map.of("foodId", numberProp("食物 id"),
                                        "grams", numberProp("克数"))))),
                a -> {
                    JsonNode items = a.path("items");
                    if (!items.isArray() || items.isEmpty()) {
                        throw new IllegalArgumentException("items 至少要有一条");
                    }
                    List<NutritionService.ItemInput> list = new ArrayList<>();
                    for (JsonNode it : items) {
                        // unitName 传 null = amount 本身就是克数（PortionConverter 的约定）
                        list.add(new NutritionService.ItemInput(
                                num(it, "foodId").longValue(), num(it, "grams").asDouble(), null));
                    }
                    var r = nutrition.recordMeal(new NutritionService.MealInput(
                            date(a), text(a, "slot"), null, null, list), AGENT);
                    return write(Map.of("ok", true, "mealId", r.mealId(), "items", r.items(),
                            "kcal", r.nutrition().kcal()));
                });

        put(m, "record_body_metric", "记一条身体数据（体重/体脂/围度）。写完会进变更日志，可一键回退。",
                schema(Map.of(
                        "date", stringProp("测量日期 YYYY-MM-DD；不填就是今天"),
                        "metricType", stringProp("指标类型，例如 weight / bodyfat / waist / chest"),
                        "value", numberProp("数值"),
                        "unit", stringProp("单位，例如 kg / % / cm；不填按类型推断"))),
                a -> {
                    var r = metrics.record(new BodyMetricService.MetricInput(
                            text(a, "date"), text(a, "metricType"),
                            num(a, "value").asDouble(),
                            a.hasNonNull("unit") ? text(a, "unit") : null, null), AGENT);
                    return write(Map.of("ok", true, "id", r.id()));
                });

        this.tools = Map.copyOf(m);
    }

    /** 给模型看的工具清单。顺序即声明顺序（Map.of 不可保序，所以用 LinkedHashMap 再 copy）。 */
    public List<LlmClient.ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /** 工具名的白名单，供测试与报错信息用。 */
    public java.util.Set<String> names() {
        return tools.keySet();
    }

    /**
     * 执行一个工具。
     *
     * @throws IllegalArgumentException 工具名不在白名单里、或参数不合法——
     *                                  两种情况都该让模型看到明确的错误并重试，而不是装作成功
     */
    public String execute(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException(
                    "没有这个工具：" + name + "。可用的只有：" + String.join(", ", tools.keySet()));
        }
        JsonNode args;
        try {
            args = json.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("参数不是合法 JSON：" + argumentsJson, e);
        }
        try {
            return tool.run().run(args == null ? json.createObjectNode() : args);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // 服务层的校验文案是中文的，对模型和用户都有用，原样带上。
            throw new IllegalStateException(name + " 执行失败：" + e.getMessage(), e);
        }
    }

    // ── 小工具 ───────────────────────────────────────────────

    private void put(Map<String, Tool> m, String name, String desc, String params, Runner run) {
        m.put(name, new Tool(new LlmClient.ToolSpec(name, desc, params), run));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("工具结果序列化失败", e);
        }
    }

    private static LocalDate date(JsonNode a) {
        return a.hasNonNull("date") ? LocalDate.parse(a.get("date").asText()) : LocalDate.now();
    }

    private static String text(JsonNode a, String field) {
        JsonNode v = a.path(field);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new IllegalArgumentException("缺少参数 " + field);
        }
        return v.asText();
    }

    private static JsonNode num(JsonNode a, String field) {
        JsonNode v = a.path(field);
        if (!v.isNumber()) {
            throw new IllegalArgumentException("缺少数值参数 " + field);
        }
        return v;
    }

    // JSON Schema 的构造。写成小工厂而不是手拼字符串：字段名拼错时是编译错误，不是运行时惊喜。
    /**
     * 取一个数值，**缺失时按 0**。
     *
     * <p>为什么要这个版本：`num()` 在字段缺失时抛「缺少数值参数」。对
     * `record_workout` 的重量来说那是对的（缺了就没法记）；但对
     * `create_food` 的三项宏量来说，模型从一张照片里只认得出「碳水」、
     * 认不出脂肪，是完全正常的输出——那种情况下抛错等于把整次拍照作废。
     *
     * <p>所以这三项走缺省 0，而 `grams` 仍然走 `num()`：没有份量，
     * 后面所有换算都无从谈起，那个必须让模型重试。
     */
    private static double numOr0(JsonNode args, String name) {
        JsonNode v = args.get(name);
        return v != null && v.isNumber() ? v.asDouble() : 0.0;
    }

    private static String stringProp(String desc) {
        return "{\"type\":\"string\",\"description\":\"" + desc + "\"}";
    }

    private static String numberProp(String desc) {
        return "{\"type\":\"number\",\"description\":\"" + desc + "\"}";
    }

    private record ArrayProp(String desc, Map<String, String> itemProps) {}

    @SuppressWarnings("unchecked")
    private static String schema(Map<String, Object> props) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String s) {
                sb.append(s);
            } else if (v instanceof ArrayProp ap) {
                sb.append("{\"type\":\"array\",\"description\":\"").append(ap.desc())
                        .append("\",\"items\":{\"type\":\"object\",\"properties\":{");
                boolean f2 = true;
                for (Map.Entry<String, String> ie : ap.itemProps().entrySet()) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append('"').append(ie.getKey()).append("\":").append(ie.getValue());
                }
                sb.append("}}}");
            }
        }
        sb.append("},\"required\":[");
        // 只把非可选的字段列进 required：date 在所有写入工具里都可省略（默认今天）
        boolean f3 = true;
        for (String k : props.keySet()) {
            if (k.equals("date") || k.equals("title")) continue;
            if (!f3) sb.append(',');
            f3 = false;
            sb.append('"').append(k).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }
}
