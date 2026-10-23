package com.change.web;

import com.change.service.ExerciseInstructionsZhService;
import com.change.service.WorkoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 动作库三个端点：看状态 / 装回来 / 整体移除。
 *
 * <p>用独立的库文件（同 {@code ExerciseImportRunnerTest} 的理由），并在每个测试前清干净
 * 导入的动作与 import 日志——库文件跨运行持久，不清就会依赖上一次残留，变成「靠顺序侥幸
 * 通过」的测试。
 *
 * <p>启动钩子在这个上下文里是关的（测试默认 {@code change.exercise.import-on-startup=false}），
 * 所以这里的一切都由端点在测试里显式触发；启动那条路径由 {@code ExerciseImportRunnerTest} /
 * {@code ExerciseNameZhRunnerTest} 用各自的上下文覆盖。{@code POST /import} 会**连同中文名一起**写，
 * 否则库会停在「有动作、没中文名」的半成品状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/exercise-controller.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class ExerciseControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WorkoutService workouts;

    @Autowired
    ObjectMapper json;

    @Autowired
    ExerciseInstructionsZhService instructionsZhService;

    @BeforeEach
    void clean() {
        // 训练域先清（session_exercise.exercise_id 是裸外键，没有级联），否则删动作会撞 FK。
        jdbc.update("DELETE FROM workout_set");
        jdbc.update("DELETE FROM session_exercise");
        jdbc.update("DELETE FROM workout_session");
        jdbc.update("DELETE FROM exercise WHERE source_id IS NOT NULL");
        jdbc.update("DELETE FROM change_log WHERE origin = 'import'");
    }

    @Test
    void status_reports_an_empty_library_before_import() throws Exception {
        mvc.perform(get("/api/exercises/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.withChineseName").value(0))
                .andExpect(jsonPath("$.everUnimported").value(false));
    }

    @Test
    void import_then_unimport_roundtrip() throws Exception {
        mvc.perform(post("/api/exercises/import"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(876))
                .andExpect(jsonPath("$.musclesInserted").value(2741));

        mvc.perform(get("/api/exercises/status"))
                .andExpect(jsonPath("$.imported").value(876))
                .andExpect(jsonPath("$.withChineseName").value(876));

        mvc.perform(post("/api/exercises/unimport"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(876));

        mvc.perform(get("/api/exercises/status"))
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.everUnimported").value(true));
    }

    @Test
    void import_is_idempotent() throws Exception {
        mvc.perform(post("/api/exercises/import"))
                .andExpect(jsonPath("$.inserted").value(876));

        mvc.perform(post("/api/exercises/import"))
                .andExpect(jsonPath("$.inserted").value(0))
                .andExpect(jsonPath("$.skipped").value(876));
    }

    @Test
    void unimport_on_a_never_imported_library_is_a_noop() throws Exception {
        mvc.perform(post("/api/exercises/unimport"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(0));
    }

    @Test
    void lists_exercises_through_the_api() throws Exception {
        jdbc.update("DELETE FROM exercise WHERE name_en = 'ZZ Ctrl Selector'");
        jdbc.update("INSERT INTO exercise(name_en, name_zh, default_unit, category) "
                + "VALUES ('ZZ Ctrl Selector', '选择器', 'kg', 'strength')");

        mvc.perform(get("/api/exercises").param("q", "选择器"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nameZh").value("选择器"))
                .andExpect(jsonPath("$[0].category").value("strength"));
    }

    @Test
    void last_performance_is_204_when_never_done_and_200_after_a_session() throws Exception {
        jdbc.update("DELETE FROM exercise WHERE name_en = 'ZZ Ctrl LastPerf'");
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES ('ZZ Ctrl LastPerf', 'kg')");
        long ex = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        mvc.perform(get("/api/exercises/" + ex + "/last-performance"))
                .andExpect(status().isNoContent());

        workouts.recordSession(new WorkoutService.SessionInput(java.time.LocalDate.of(2026, 10, 18),
                null, null, null, null, null,
                java.util.List.of(new WorkoutService.ExerciseInput(ex, null, null,
                        java.util.List.of(new WorkoutService.SetInput("normal", 70.0, 5, null, false,
                                null, null, true, null, null))))));

        mvc.perform(get("/api/exercises/" + ex + "/last-performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionDate").value("2026-10-18"))
                .andExpect(jsonPath("$.sets[0].weight").value(70.0));
    }

    // ------------------------------------------------------------------
    // GET /api/exercises/{id} —— 动作详情（Task 6）
    //
    // 这一组测试钉的是**序列化之后的形状**：前端 Task 7 会手写一份 TS interface，
    // 后端 record 与它的键名一旦漂移，前端只会静默拿到 undefined。所以这里逐字断言
    // 键集合（照 ProgressControllerTest 的做法），而不是比一个重新构造的 Java 对象。
    // ------------------------------------------------------------------

    /** 造一个手工动作；先按名字删掉上一次运行可能留下的同名行。 */
    private long insertExercise(String nameEn, String nameZh, String equipment, boolean compound,
                                String category, String instructions, String instructionsZh) {
        jdbc.update("DELETE FROM exercise WHERE name_en = ?", nameEn);
        jdbc.update("INSERT INTO exercise(name_en, name_zh, equipment, is_compound, default_unit, "
                        + "category, instructions, instructions_zh) VALUES (?, ?, ?, ?, 'kg', ?, ?, ?)",
                nameEn, nameZh, equipment, compound ? 1 : 0, category, instructions, instructionsZh);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long muscleId(String nameEn) {
        return jdbc.queryForObject(
                "SELECT id FROM muscle_group WHERE name_en = ?", Long.class, nameEn);
    }

    private void linkMuscle(long exerciseId, String muscleNameEn, String role) {
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?, ?, ?)",
                exerciseId, muscleId(muscleNameEn), role);
    }

    private String detailBody(long exerciseId) throws Exception {
        return mvc.perform(get("/api/exercises/" + exerciseId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<String> muscleNames(JsonNode muscles) {
        List<String> names = new ArrayList<>();
        muscles.forEach(m -> names.add(m.get("nameEn").asText()));
        return names;
    }

    @Test
    void detail_returns_exactly_the_contract_keys_and_primary_muscles_first() throws Exception {
        long ex = insertExercise("ZZ Detail Bench", "详情卧推", "barbell", true, "strength",
                "Lie on a flat bench.\nPress the bar.", "躺在平板凳上。\n推起杠铃。");
        // chest 被拆开；这个动作没有 incline/decline 线索 -> 中胸
        linkMuscle(ex, "chest-middle", "primary");
        linkMuscle(ex, "triceps", "secondary");

        String body = detailBody(ex);

        JsonNode root = json.readTree(body);

        // 前端 fixture 的出处：这一行就是 Task 7 里那串字符的来源。
        System.out.println("[EXERCISE_DETAIL_JSON] " + body);

        // 1. 顶层恰好九个键。多一个（sourceId/note/archived）或少一个都不行。
        assertThat(keysOf(root)).as("顶层键").containsExactly(
                "id", "nameEn", "nameZh", "equipment", "isCompound", "category",
                "instructions", "instructionsZh", "muscles");
        assertThat(root.get("id").asLong()).isEqualTo(ex);
        assertThat(root.get("nameEn").asText()).isEqualTo("ZZ Detail Bench");
        assertThat(root.get("nameZh").asText()).isEqualTo("详情卧推");
        assertThat(root.get("equipment").asText()).isEqualTo("barbell");
        assertThat(root.get("isCompound").asBoolean()).isTrue();
        assertThat(root.get("category").asText()).isEqualTo("strength");
        assertThat(root.get("instructions").asText())
                .isEqualTo("Lie on a flat bench.\nPress the bar.");
        assertThat(root.get("instructionsZh").asText()).isEqualTo("躺在平板凳上。\n推起杠铃。");

        // 2. 每个肌群恰好四个键，且主要肌群在前。
        JsonNode muscles = root.get("muscles");
        assertThat(muscles.isArray()).isTrue();
        assertThat(muscles).hasSize(2);
        for (JsonNode muscle : muscles) {
            assertThat(keysOf(muscle)).as("肌群键").containsExactly(
                    "muscleGroupId", "nameZh", "nameEn", "role");
        }
        assertThat(muscles.get(0).get("nameEn").asText()).isEqualTo("chest-middle");
        assertThat(muscles.get(0).get("nameZh").asText()).isEqualTo("中胸");
        assertThat(muscles.get(0).get("role").asText()).isEqualTo("primary");
        assertThat(muscles.get(1).get("nameEn").asText()).isEqualTo("triceps");
        assertThat(muscles.get(1).get("nameZh").asText()).isEqualTo("肱三头肌");
        assertThat(muscles.get(1).get("role").asText()).isEqualTo("secondary");
    }

    /**
     * 排序必须是「primary 先于 secondary，同 role 内按 muscle_group.id」——
     * 不是按 id 排、也不是按插入顺序排。
     *
     * <p>数据故意构造得能区分这三种实现：主要肌群 quadriceps 的 id（11）比两个次要肌群
     * （chest=1、biceps=8）都大，而且插入顺序是「次要、主要、次要」。
     */
    @Test
    void detail_orders_primary_before_secondary_then_by_muscle_group_id() throws Exception {
        long ex = insertExercise("ZZ Detail Order", null, "bodyweight", false, "strength",
                "Do the thing.", "做这个动作。");
        linkMuscle(ex, "biceps", "secondary");    // id 8，先插
        linkMuscle(ex, "quadriceps", "primary"); // id 11，主要但 id 最大
        linkMuscle(ex, "chest", "secondary");    // id 1

        JsonNode muscles = json.readTree(detailBody(ex)).get("muscles");

        assertThat(muscleNames(muscles))
                .as("主要在前；次要内部按 id；不依赖插入顺序")
                // chest 被拆开 -> chest-middle（该动作无 incline/decline 线索）
                // linkMuscle 直接插父组，不走导入器的子部位映射
                .containsExactly("quadriceps", "chest", "biceps");
        assertThat(muscles.get(0).get("role").asText()).isEqualTo("primary");
        assertThat(muscles.get(1).get("role").asText()).isEqualTo("secondary");
        assertThat(muscles.get(2).get("role").asText()).isEqualTo("secondary");
    }

    @Test
    void detail_keeps_an_explicit_null_instructions_zh_key() throws Exception {
        long ex = insertExercise("ZZ Detail No Zh", "没有中文做法", "dumbbell", false, "strength",
                "Curl the dumbbell up.", null);

        String body = detailBody(ex);

        // 原文是 null 时必须是 JSON null，不是空串、不是省略键
        assertThat(body).contains("\"instructionsZh\":null");
        JsonNode root = json.readTree(body);
        assertThat(root.has("instructionsZh")).as("instructionsZh 键没有被省略").isTrue();
        assertThat(root.get("instructionsZh").isNull()).as("null 而不是空串").isTrue();
        assertThat(root.get("instructions").asText()).isEqualTo("Curl the dumbbell up.");
    }

    /**
     * 数据集里 5 条英文原文为空的动作（Iron Cross / One-Arm Kettlebell Swings / Push Press /
     * Side Bridge / Side Jackknife）：两列都是 null，但动作本身存在，**不能 404**。
     * muscles 也刻意留空，顺带钉住 LEFT JOIN 的路径。
     *
     * <p>名字刻意不叫 Push Press：这一行是手工动作（source_id 为 NULL），会留在共享库的
     * 两次测试之间，而 {@code import_then_unimport_roundtrip} 随后要导入数据集里同名的
     * 那一条，会撞 {@code ux_exercise_name_en}——那是测试数据互相污染，不是端点的问题。
     */
    @Test
    void detail_without_any_instructions_is_still_a_200_with_two_nulls() throws Exception {
        long ex = insertExercise("ZZ Detail Empty Instructions", null, "barbell", true,
                "strength", null, null);

        String body = detailBody(ex);

        JsonNode root = json.readTree(body);
        assertThat(root.has("instructions")).isTrue();
        assertThat(root.get("instructions").isNull()).isTrue();
        assertThat(root.has("instructionsZh")).isTrue();
        assertThat(root.get("instructionsZh").isNull()).isTrue();
        assertThat(root.get("muscles").isArray()).isTrue();
        assertThat(root.get("muscles").size()).isZero();
    }

    @Test
    void an_unknown_exercise_detail_is_a_404_with_our_error_shape() throws Exception {
        long missing = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000 FROM exercise", Long.class);

        String body = mvc.perform(get("/api/exercises/" + missing))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = json.readTree(body);
        assertThat(keysOf(root)).as("404 正文的键").containsExactly("error");
        assertThat(root.get("error").asText())
                .contains("找不到这个动作")
                .contains(String.valueOf(missing));
    }

    /**
     * 真实数据里的段数一致性：{@code instructionsZh} 按 {@code \n} 拼接，
     * 而 Straight-Arm Dumbbell Pullover 的第 3 段内部**自带一个换行**。
     * 英文 6 段 + 1 个段内换行 = 7 行；中文必须也是 7 行（段内换行不能被压平）。
     *
     * <p>这里走真实的导入端点 + 真实的中文做法映射，不是手写一段假数据——
     * 段数错位只会在真实译文里暴露。顺带在真实数据上验证两件事：
     * 这个动作同时有主要与次要肌群（chest primary + lats/shoulders/triceps secondary），
     * 以及数据集里英文原文为空的那 5 条之一（Push Press）仍然是 200 而不是 404。
     */
    @Test
    void the_real_pullover_has_the_same_number_of_lines_in_both_languages() throws Exception {
        mvc.perform(post("/api/exercises/import")).andExpect(status().isOk());
        instructionsZhService.applyMappingFromClasspath();

        long ex = jdbc.queryForObject(
                "SELECT id FROM exercise WHERE name_en = 'Straight-Arm Dumbbell Pullover'",
                Long.class);

        JsonNode root = json.readTree(detailBody(ex));
        String en = root.get("instructions").asText();
        String zh = root.get("instructionsZh").asText();

        assertThat(en.split("\n", -1)).as("英文原文 7 行").hasSize(7);
        assertThat(zh.split("\n", -1)).as("中文译文 7 行").hasSize(7);
        assertThat(zh.split("\n", -1)).as("中英行数一致").hasSameSizeAs(en.split("\n", -1));

        // 真实数据里的 role：主要肌群在前，次要肌群按 muscle_group.id 排。
        JsonNode muscles = root.get("muscles");
        assertThat(muscleNames(muscles))
                // chest 与 shoulders 在被拆分的父组里，落到子部位：
        // 分类依据见 docs/动作到子部位映射.md（pullover 不含 incline/decline -> 中胸）
        .containsExactly("chest-middle", "lats", "triceps", "shoulders-lateral");
        assertThat(muscles.get(0).get("role").asText()).isEqualTo("primary");
        assertThat(muscles.get(1).get("role").asText()).isEqualTo("secondary");
        assertThat(muscles.get(0).get("nameZh").asText()).isEqualTo("中胸");

        // 5 条没有英文原文的动作之一：两个 null、键都在、仍是 200。
        long pushPress = jdbc.queryForObject(
                "SELECT id FROM exercise WHERE name_en = 'Push Press'", Long.class);
        JsonNode empty = json.readTree(detailBody(pushPress));
        assertThat(empty.has("instructions")).isTrue();
        assertThat(empty.get("instructions").isNull()).isTrue();
        assertThat(empty.has("instructionsZh")).isTrue();
        assertThat(empty.get("instructionsZh").isNull()).isTrue();
    }

    /**
     * 「移除再重新导入动作库」不能让 871 条中文做法静默消失。
     *
     * <p>缺陷的本体在 {@code POST /api/exercises/import}：它调了
     * {@link com.change.service.ExerciseNameZhService#applyMappingFromClasspath()} 补中文名，
     * 却**没有**调 {@link ExerciseInstructionsZhService#applyMappingFromClasspath()} 补中文做法。
     * 重新导入是「删了重建」，{@code instructions_zh} 因此被写成 NULL，并把
     * {@code ExerciseInstructionsZhService} 注释里承诺的「import 之后也用」变成空话——
     * 用户要等到下一次重启（{@code ExerciseInstructionsZhRunner}）才看得回中文做法。
     *
     * <p>测试上下文把启动钩子整体关掉了（见类注释），所以第一次导入之后显式跑一遍
     * 「启动时那条路径」，把库恢复成用户机器上的真实状态：有动作、有中文名、有中文做法。
     * 之后的两步全部走真实端点，不再手工补任何东西——补了就等于替缺陷打掩护。
     */
    @Test
    void reimport_keeps_the_chinese_instructions_of_the_imported_library() throws Exception {
        // 1. 装一次库，并补上启动 Runner 会写的那些内容。
        mvc.perform(post("/api/exercises/import"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(876));
        instructionsZhService.applyMappingFromClasspath();

        long before = importedExerciseId("Straight-Arm Dumbbell Pullover");
        assertThat(instructionsZhOf(before))
                .as("初始状态：动作库里有中文做法").isNotNull().isNotBlank();

        // 2. 用户点了「移除动作库」：动作行被物理删除，中文做法一并消失。
        mvc.perform(post("/api/exercises/unimport"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(876));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise WHERE source_id IS NOT NULL", Integer.class))
                .as("移除之后库里没有导入的动作").isEqualTo(0);

        // 3. 用户又点了「重新导入动作库」。
        mvc.perform(post("/api/exercises/import"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(876));

        // 4. 不重启，中文做法就应该回来 —— 这就是缺陷：只补中文名的 import 会把它弄丢。
        long after = importedExerciseId("Straight-Arm Dumbbell Pullover");
        assertThat(instructionsZhOf(after))
                .as("重新导入后中文做法不该为 NULL（不重启）").isNotNull().isNotBlank();

        JsonNode detail = json.readTree(detailBody(after));
        assertThat(detail.get("instructionsZh").isNull())
                .as("详情端点也不该拿到 instructionsZh:null").isFalse();
        assertThat(detail.get("instructionsZh").asText()).isNotBlank();
    }

    /** 按英文名取一个**导入的**动作的 id。 */
    private long importedExerciseId(String nameEn) {
        return jdbc.queryForObject(
                "SELECT id FROM exercise WHERE name_en = ? AND source_id IS NOT NULL",
                Long.class, nameEn);
    }

    private String instructionsZhOf(long exerciseId) {
        return jdbc.queryForObject(
                "SELECT instructions_zh FROM exercise WHERE id = ?", String.class, exerciseId);
    }
}
