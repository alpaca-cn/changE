package com.change.web;

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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/body-map/volume} 的 **JSON 形状契约**。
 *
 * <p>服务层测试断言的是 {@code BodyMapService.BodyMapVolume} 这个 Java 对象，而前端读的是
 * JSON。两者之间隔着一个 Jackson：改个字段名、把 {@code LocalDate} 写成数组、
 * 把 {@code regionIds} 从数组改成逗号分隔的字符串、或者让 null 被省略，服务层测试
 * **全都还是绿的**，前端却会静默拿到 {@code undefined} 或一个画不出热力图的字符串。
 *
 * <p>所以这里全部断言**原始响应正文**（先解析成 {@link JsonNode} 看键，再对字符串看
 * {@code "\"daysSince\":null}），照 {@code ProgressControllerTest} / {@code ExerciseControllerTest}
 * 的做法。Task 5 的 {@code body-map.spec.ts} 里那份 fixture 就是从这条测试打印出来的响应
 * 原样复制的——两端的形状只有一份来源。
 *
 * <p>独立库文件 + 每个用例前清空训练域：与 {@code BodyMapServiceTest} 同一个理由，
 * 「空库 → 17 块全 0、lastTrainedOn 全 null」是关于整张表的断言，共享库里别处留下的行
 * 会让它凭空变红。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/body-map-web.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class BodyMapControllerTest {

    /** 2026-10-20 是周二，它所在的自然周是 10-19（周一）..10-25（周日）。 */
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 20);

    /** 契约里的十个键，顺序就是 record 的字段顺序。 */
    private static final List<String> MUSCLE_KEYS = List.of(
            "muscleGroupId", "nameZh", "nameEn", "sets", "min", "max", "status",
            "lastTrainedOn", "daysSince", "regionIds",
            // V11：子部位用 parentEn 声明自己属于哪个父组
            "parentEn");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkoutService workouts;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM workout_set");
        jdbc.update("DELETE FROM session_exercise");
        jdbc.update("DELETE FROM workout_session");
        jdbc.update("DELETE FROM exercise_muscle");
        jdbc.update("DELETE FROM exercise");
        jdbc.update("DELETE FROM change_log");
    }

    // ---- 造数据 ----

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void link(long exerciseId, String muscleNameEn, String role) {
        long muscleId = jdbc.queryForObject(
                "SELECT id FROM muscle_group WHERE name_en = ?", Long.class, muscleNameEn);
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?,?,?)",
                exerciseId, muscleId, role);
    }

    private void record(LocalDate date, long exerciseId, int completedSets) {
        List<WorkoutService.SetInput> sets = new ArrayList<>();
        for (int i = 0; i < completedSets; i++) {
            sets.add(new WorkoutService.SetInput("normal", 60.0, 8, null, false, null, null, true, null, null));
        }
        workouts.recordSession(new WorkoutService.SessionInput(date, null, null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null, sets))));
    }

    private String getBody(String date) throws Exception {
        var request = get("/api/body-map/volume");
        if (date != null) {
            request = request.param("date", date);
        }
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static JsonNode muscle(JsonNode muscles, String nameEn) {
        for (JsonNode m : muscles) {
            if (nameEn.equals(m.get("nameEn").asText())) {
                return m;
            }
        }
        throw new AssertionError("响应里没有肌群 " + nameEn);
    }

    // ------------------------------------------------------------------
    // 形状
    // ------------------------------------------------------------------

    /**
     * 顶层四个键、每块肌群十个键，逐个断言——多一个（比如顺手把 {@code bodyPart} /
     * {@code weekNumber} 也塞进来）少一个都红。
     *
     * <p>{@code regionIds} 必须是**字符串数组**：前端拿它去 SVG 里找同 id 的 {@code path}。
     * 逗号分隔的字符串不报错，只是那块肌肉永远不着色。
     */
    @Test
    void the_response_has_exactly_the_contract_keys_and_region_ids_is_an_array() throws Exception {
        long ex = newExercise("ZZ BodyMap Http Bench");
        link(ex, "chest", "primary");
        link(ex, "triceps", "secondary");
        record(TUESDAY, ex, 3);

        String body = getBody("2026-10-20");
        // 前端 fixture 的出处：这一行就是 Task 5 里那串字符的来源。
        System.out.println("[BODY_MAP_JSON] " + body);

        JsonNode root = json.readTree(body);

        // 1. 顶层恰好四个键
        assertThat(keysOf(root)).as("顶层键")
                .containsExactly("weekStart", "weekEnd", "today", "muscles");

        // 2. 日期是 "YYYY-MM-DD" 字符串——不是 [2026,10,19] 数组，也不是 epoch 毫秒
        for (String key : List.of("weekStart", "weekEnd", "today")) {
            JsonNode date = root.get(key);
            assertThat(date.isTextual()).as("%s 是字符串", key).isTrue();
            assertThat(date.isArray()).as("%s 不是数组（WRITE_DATES_AS_TIMESTAMPS）", key).isFalse();
            assertThat(date.isNumber()).as("%s 不是 epoch 毫秒", key).isFalse();
        }
        assertThat(root.get("weekStart").asText()).isEqualTo("2026-10-19");
        assertThat(root.get("weekEnd").asText()).isEqualTo("2026-10-25");
        assertThat(root.get("today").asText()).isEqualTo("2026-10-20");

        // 3. 17 块，每块恰好十个键
        JsonNode muscles = root.get("muscles");
        assertThat(muscles.isArray()).isTrue();
        assertThat(muscles).// 2026-10-23: 胸/肩/斜方/中背/下背拆成子部位，17 块变成 27 块
        // （17 个顶层 + 10 个子部位）。这是模型的有意变更。
        hasSize(27);
        for (JsonNode m : muscles) {
            assertThat(keysOf(m)).as("肌群 %s 的键", m.get("nameEn").asText())
                    .containsExactlyElementsOf(MUSCLE_KEYS);

            JsonNode regionIds = m.get("regionIds");
            assertThat(regionIds.isArray())
                    .as("%s 的 regionIds 是数组，不是逗号分隔的字符串", m.get("nameEn").asText())
                    .isTrue();
            assertThat(regionIds.isTextual()).as("%s 的 regionIds 不是字符串", m.get("nameEn").asText())
                    .isFalse();
            // 子部位（chest-upper 这种）**故意**没有 regionIds：
            // 2D 人体图仍是父组粒度，子部位带区域 id 会让同一块区域被两个肌群争着上色。
            boolean isSubGroup = m.get("parentEn") != null && !m.get("parentEn").isNull();
            if (isSubGroup) {
                // 子部位不带区域 id：2D 人体图仍是父组粒度
                assertThat(regionIds.size())
                        .as("%s 是子部位，regionIds 应为空", m.get("nameEn").asText())
                        .isZero();
            } else {
                assertThat(regionIds.size())
                        .as("%s 的 regionIds 不应为空", m.get("nameEn").asText())
                        .isPositive();
            }
            for (JsonNode id : regionIds) {
                assertThat(id.isTextual()).as("region id 是字符串").isTrue();
                assertThat(id.asText()).as("region id 不含逗号").doesNotContain(",");
            }
        }

        // 4. 具体数值：3 组卧推 → 胸 3 / 三头 1.5；min/max/status 来自 muscle_group 与 VolumeCalculator
        JsonNode chest = muscle(muscles, "chest");
        assertThat(chest.get("muscleGroupId").asLong()).isEqualTo(1L);
        assertThat(chest.get("nameZh").asText()).isEqualTo("胸部");
        assertThat(chest.get("sets").asDouble()).isEqualTo(3.0);
        assertThat(chest.get("min").asInt()).isEqualTo(10);
        assertThat(chest.get("max").asInt()).isEqualTo(20);
        assertThat(chest.get("status").asText()).isEqualTo("BELOW");
        assertThat(chest.get("lastTrainedOn").asText()).isEqualTo("2026-10-20");
        assertThat(chest.get("daysSince").asInt()).isZero();
        assertThat(chest.get("regionIds").toString()).as("原样是四个连字符 id")
                .isEqualTo("[\"chest-upper-left\",\"chest-lower-left\","
                        + "\"chest-upper-right\",\"chest-lower-right\"]");

        assertThat(muscle(muscles, "triceps").get("sets").asDouble()).isEqualTo(1.5);

        // neck 的 weekly_sets_min/max 在库里是 NULL：键在、值是 JSON null（不是被省略，
        // 也不是兜成 0——那会让「未配目标」与「目标为 0 组」混成一个）
        JsonNode neck = muscle(muscles, "neck");
        assertThat(neck.has("min")).as("min 键没有被省略").isTrue();
        assertThat(neck.get("min").isNull()).isTrue();
        assertThat(neck.get("max").isNull()).isTrue();
        assertThat(neck.get("status").asText()).isEqualTo("NOT_CONFIGURED");
    }

    /**
     * 同一次请求里 {@code daysSince} 的 0 与 null 同时出现。
     *
     * <p>服务层已经钉过一次；这里钉的是**序列化**：0 不能被写成 null，
     * null 也不能被省略成「键不存在」（前端的 {@code daysSince === null} 判断会失效）。
     * 所以除了 JsonNode 断言，还直接看原始正文里的两个字符片段。
     */
    @Test
    void days_since_zero_and_null_are_both_present_in_the_same_payload() throws Exception {
        long ex = newExercise("ZZ BodyMap Http Today");
        link(ex, "chest", "primary");
        record(TUESDAY, ex, 2);

        String body = getBody("2026-10-20");
        JsonNode muscles = json.readTree(body).get("muscles");

        assertThat(muscle(muscles, "chest").get("daysSince").isNull()).isFalse();
        assertThat(muscle(muscles, "chest").get("daysSince").asInt()).isZero();

        JsonNode never = muscle(muscles, "abdominals");
        assertThat(never.has("daysSince")).as("daysSince 键没有被省略").isTrue();
        assertThat(never.get("daysSince").isNull()).as("从没练过 → JSON null").isTrue();
        assertThat(never.get("lastTrainedOn").isNull()).isTrue();

        // 原始正文：一个 0、一个 null，两种字面量都在
        assertThat(body).contains("\"daysSince\":0");
        assertThat(body).contains("\"daysSince\":null");
        assertThat(body).contains("\"lastTrainedOn\":null");
    }

    /** 空库：17 块全在，{@code sets} 全 0，{@code lastTrainedOn} / {@code daysSince} 全 null。 */
    @Test
    void an_empty_database_still_returns_all_muscles_with_zero_sets() throws Exception {
        JsonNode muscles = json.readTree(getBody("2026-10-20")).get("muscles");

        assertThat(muscles).// 2026-10-23: 胸/肩/斜方/中背/下背拆成子部位，17 块变成 27 块
        // （17 个顶层 + 10 个子部位）。这是模型的有意变更。
        hasSize(27);
        for (JsonNode m : muscles) {
            assertThat(m.get("sets").asDouble()).as("%s 的本周组数", m.get("nameEn").asText()).isZero();
            assertThat(m.get("lastTrainedOn").isNull()).isTrue();
            assertThat(m.get("daysSince").isNull()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // date 参数
    // ------------------------------------------------------------------

    @Test
    void without_a_date_it_uses_today_and_todays_week() throws Exception {
        JsonNode root = json.readTree(getBody(null));
        LocalDate today = LocalDate.now();

        assertThat(root.get("today").asText()).isEqualTo(today.toString());
        assertThat(root.get("weekStart").asText()).isEqualTo(
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString());
        assertThat(root.get("muscles")).// 2026-10-23: 胸/肩/斜方/中背/下背拆成子部位，17 块变成 27 块
        // （17 个顶层 + 10 个子部位）。这是模型的有意变更。
        hasSize(27);
    }

    @Test
    void a_date_selects_its_week_and_is_also_the_reference_day() throws Exception {
        long ex = newExercise("ZZ BodyMap Http Two Weeks");
        link(ex, "chest", "primary");
        record(TUESDAY.minusDays(7), ex, 4);      // 2026-10-13
        record(TUESDAY, ex, 1);                   // 2026-10-20

        String thisWeek = getBody("2026-10-20");
        assertThat(muscle(json.readTree(thisWeek).get("muscles"), "chest").get("sets").asDouble())
                .as("本周只有今天那一组").isEqualTo(1.0);

        String lastWeek = getBody("2026-10-13");
        JsonNode root = json.readTree(lastWeek);
        assertThat(root.get("weekStart").asText()).isEqualTo("2026-10-12");
        assertThat(root.get("weekEnd").asText()).isEqualTo("2026-10-18");
        assertThat(muscle(root.get("muscles"), "chest").get("sets").asDouble())
                .as("上周那 4 组").isEqualTo(4.0);
    }

    @Test
    void a_bad_date_is_a_400_with_our_error_shape() throws Exception {
        String body = mvc.perform(get("/api/body-map/volume").param("date", "2026/10/14"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = json.readTree(body);
        // Spring 默认的 {"timestamp","status","error","path"} 形状在这里会让前端读到 undefined
        assertThat(keysOf(root)).as("400 正文的键").containsExactly("error");
        assertThat(root.get("error").asText()).contains("date").contains("2026/10/14");
    }
}
