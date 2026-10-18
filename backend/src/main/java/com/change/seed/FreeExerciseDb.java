package com.change.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@code yuhonas/free-exercise-db} 数据集的解析与字段映射（**纯函数，不依赖 Spring**）。
 *
 * <p>数据集（{@code exercises.json}）由 {@code src/main/resources/seed/free-exercise-db/}
 * 随包分发。它是 Unlicense（可随包再分发），许可证原文是 {@code LICENSE.md}——
 * 注意**不是** {@code LICENSE}，仓库根目录没有 {@code LICENSE} 这个文件。
 *
 * <p><b>为什么把它做成纯函数</b>：字段映射的坑全在个例上——876 条里有 9 条主/次肌群重叠、
 * 87 条 {@code mechanic=null}、77 条 {@code equipment=null}、844 条 {@code id≠name}。
 * 这一点抽样的测试漏得掉，而起一个 Spring 上下文来跑全量又太重。做成不依赖 Spring 的
 * 静态映射之后，{@code FreeExerciseDbTest} 能毫秒级跑完全部 876 条。
 *
 * <p><b>加载即校验</b>：{@link #load()} 先核对字节数与 sha256，再核对条数。数据集换了
 * 版本时**必须立刻报错**而不是静默继续——本类的字段映射（连同那些个例数字）是在
 * 1,005,327 字节那一版上实测的，换版本意味着要重新核对。校验失败抛出
 * {@link IllegalStateException}，消息里带期望值与实际值。
 *
 * <p><b>为什么 source_id 用 {@code id} 而不是 {@code name}</b>：844 条两者不同
 * （{@code Ab_Crunch_Machine} vs {@code Ab Crunch Machine}），用 name 拼会让这些动作
 * 对不上源。前缀 {@code free-exercise-db:} 是约定的一部分（见 V2 迁移里 source_id 的注释），
 * 不带前缀会在接入第二个数据集时静默冲突。
 */
public final class FreeExerciseDb {

    private FreeExerciseDb() {}

    /** classpath 上的数据集位置（与 vendor 目录一致）。 */
    public static final String RESOURCE = "seed/free-exercise-db/exercises.json";

    /** source_id 的命名空间前缀：手工动作写 NULL，导入动作写 {@code 前缀 + id}。 */
    public static final String SOURCE_PREFIX = "free-exercise-db:";

    /** 实测的字节数。换版本时这个数会变，校验会立刻拦下。 */
    public static final long EXPECTED_BYTES = 1_005_327L;

    /** 实测的 sha256（1,005,327 字节那一版）。 */
    public static final String EXPECTED_SHA256 =
            "5bb747e3fc658f095a60dcbf6d53c96627acdcc6ffb6fffde86f7e26995d40bf";

    /** 实测的条数。 */
    public static final int EXPECTED_ROWS = 876;

    /**
     * 从 classpath 读取、校验并解析整个数据集。
     *
     * @throws IllegalStateException 字节数 / sha256 / 条数不符，或资源缺失 / 解析失败
     */
    public static List<Row> load() {
        return decode(readResource());
    }

    /**
     * 校验并解析给定字节。
     *
     * <p>包级可见（不是 private）是为了让测试能直接喂一份**被改过字节**的数据集进来，
     * 确认「加载即校验」真的会拦下它——否则那段校验只是一句注释：删掉它没有任何测试会红。
     * 见 {@code FreeExerciseDbTest#a_tampered_dataset_is_rejected_not_silently_parsed}。
     */
    static List<Row> decode(byte[] bytes) {
        verify(bytes);

        JsonNode root = parse(bytes);
        if (!root.isArray()) {
            throw new IllegalStateException(
                    "数据集顶层应当是一个 JSON 数组，实际是: " + root.getNodeType());
        }
        if (root.size() != EXPECTED_ROWS) {
            throw new IllegalStateException(
                    "数据集条数不符：期望 " + EXPECTED_ROWS + "，实际 " + root.size()
                            + "。数据集换了版本，字段映射需要重新核对。");
        }

        List<Row> rows = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            rows.add(toRow(node));
        }
        return List.copyOf(rows);
    }

    private static byte[] readResource() {
        try (InputStream in = FreeExerciseDb.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上找不到数据集: " + RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取数据集失败: " + RESOURCE, e);
        }
    }

    /** 字节数与 sha256 的校验。大小先比——它更快，且失败信息更容易读。 */
    private static void verify(byte[] bytes) {
        if (bytes.length != EXPECTED_BYTES) {
            throw new IllegalStateException(
                    "数据集大小不符：期望 " + EXPECTED_BYTES + " 字节，实际 " + bytes.length
                            + "。数据集换了版本，字段映射需要重新核对。");
        }
        String actual = sha256(bytes);
        if (!EXPECTED_SHA256.equals(actual)) {
            throw new IllegalStateException(
                    "数据集 sha256 不符：期望 " + EXPECTED_SHA256 + "，实际 " + actual
                            + "。数据集换了版本，字段映射需要重新核对。");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            return new ObjectMapper().readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("数据集不是合法 JSON: " + RESOURCE, e);
        }
    }

    private static Row toRow(JsonNode node) {
        String equipment = nullableText(node, "equipment");
        String mechanic = nullableText(node, "mechanic");
        return new Row(
                requiredText(node, "name"),
                SOURCE_PREFIX + requiredText(node, "id"),
                equipment,
                "compound".equals(mechanic),
                "body only".equals(equipment) ? "bw" : "kg",
                requiredText(node, "category"),
                joinInstructions(node.get("instructions")),
                mechanic,
                stringList(node.get("primaryMuscles")),
                stringList(node.get("secondaryMuscles")));
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("数据集的某条记录缺少必需字段: " + field);
        }
        return value.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            out.add(n.asText());
        }
        return List.copyOf(out);
    }

    /**
     * 多段说明合成一个字符串：段间用 {@code \n} 连接，空数组则 {@code null}。
     *
     * <p><b>为什么要做换行归一化</b>：SQLite 的 TEXT 里混进 {@code \r\n} 会让「按行比较」
     * 的代码在两个平台上看不等。实测这一版数据集的 {@code instructions} 里
     * {@code \r} 出现 **0 次**——所以这是**防御性代码，今天不触发**。留着是因为数据集是
     * vendor 的，换一版、或将来从别的来源补动作时，这个归一化的缺失会表现为「随机的
     * 一行对不上」，很难查。
     */
    private static String joinInstructions(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(arr.size());
        for (JsonNode part : arr) {
            parts.add(part.asText().replace("\r\n", "\n").replace('\r', '\n'));
        }
        return String.join("\n", parts);
    }

    /** 数据集的一条记录，已按本项目的口径映射好。字段公开（与 {@link MuscleGroupSeed.G} 同风格）。 */
    public static final class Row {
        public final String nameEn;
        public final String sourceId;
        /** 恒为 null：中文名由单独的翻译任务产出（空着比编一个错的诚实）。 */
        public final String nameZh;
        public final String equipment;
        public final boolean isCompound;
        public final String defaultUnit;
        public final String category;
        public final String instructions;
        /** 原值，可能为 null。保留是因为 is_compound 的推导要用它，测试也要用它。 */
        public final String mechanic;
        public final List<String> primaryMuscles;
        public final List<String> secondaryMuscles;

        Row(String nameEn, String sourceId, String equipment, boolean isCompound,
            String defaultUnit, String category, String instructions, String mechanic,
            List<String> primaryMuscles, List<String> secondaryMuscles) {
            this.nameEn = nameEn;
            this.sourceId = sourceId;
            this.nameZh = null;
            this.equipment = equipment;
            this.isCompound = isCompound;
            this.defaultUnit = defaultUnit;
            this.category = category;
            this.instructions = instructions;
            this.mechanic = mechanic;
            this.primaryMuscles = primaryMuscles;
            this.secondaryMuscles = secondaryMuscles;
        }

        /**
         * 这个动作要挂哪些肌群、各自什么角色。
         *
         * <p>先写全部 primary，再写 secondary 但**跳过已经作为 primary 出现过的**。
         * 规则是 <b>primary 赢</b>——不是「后写的覆盖先写的」，也不是「随便丢一个」。
         *
         * <p>为什么必须化解重叠：876 条里有 9 条的 primary 与 secondary 有交集，
         * 朴素地两边都写会撞 {@code (exercise_id, muscle_group_id)} 的唯一索引，
         * 让整批导入回滚。返回的列表里 {@code muscleNameEn} 保证唯一。
         */
        public List<Assignment> muscleAssignments() {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<Assignment> out = new ArrayList<>();
            for (String muscle : primaryMuscles) {
                if (seen.add(muscle)) {
                    out.add(new Assignment(muscle, "primary"));
                }
            }
            for (String muscle : secondaryMuscles) {
                if (seen.add(muscle)) {
                    out.add(new Assignment(muscle, "secondary"));
                }
            }
            return out;
        }

        /** primary 与 secondary 是否有交集（那 9 条重叠动作）。 */
        public boolean hasOverlap() {
            for (String muscle : secondaryMuscles) {
                if (primaryMuscles.contains(muscle)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 一条肌群标注：挂哪块肌肉、是主要（{@code primary}）还是次要（{@code secondary}）。 */
    public static final class Assignment {
        public final String muscleNameEn;
        /** {@code "primary"} 或 {@code "secondary"}，与 {@code exercise_muscle.role} 的 CHECK 一致。 */
        public final String role;

        Assignment(String muscleNameEn, String role) {
            this.muscleNameEn = muscleNameEn;
            this.role = role;
        }
    }
}
