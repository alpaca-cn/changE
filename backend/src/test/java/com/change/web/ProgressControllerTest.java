package com.change.web;

import com.change.service.WorkoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/progress/{exerciseId}} 的 **JSON 形状契约**。
 *
 * <p>这一条测试存在的理由不是「端点没测过」——{@link com.change.service.ProgressServiceTest}
 * 已经把三条序列的口径钉得很紧。它钉的是**序列化之后的那层形状**：
 * 服务层断言的是 {@code ExerciseProgress} 这个 Java 对象，而前端读的是 JSON。两者之间
 * 隔着一个 Jackson：改个字段名、把 {@code LocalDate} 写成数组、或者让 null 被省略，
 * 服务层测试**全都还是绿的**，前端却会静默拿到 {@code undefined}。
 *
 * <p>所以这里全部断言**原始响应正文**（先解析成 {@link JsonNode} 看键，再对字符串看
 * {@code "e1rm":null}），而不是重新构造一个对象来比。前端 {@code ProgressView.spec.ts}
 * 里那份 fixture 就是从这条测试打印出来的响应原样复制的——两端的形状只有一份来源。
 *
 * <p>{@code @Transactional} 与其它服务测试一致：造的数据跑完回滚。断言只针对自己插入的
 * 动作与训练日，不碰共享库里别处留下的行。
 *
 * <p>数据库里造的数据故意包含「估不出 e1rm 的一天」（20 kg × 15 次：15 超出
 * {@code OneRepMax.MAX_REPS}）——那条 {@code "e1rm":null} 的键是契约的一部分，
 * 不是残缺数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgressControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkoutService workouts;
    @Autowired ObjectMapper json;

    // ---- 造数据 ----

    private long newExercise(String nameEn, String nameZh) {
        jdbc.update("INSERT INTO exercise(name_en, name_zh, default_unit) VALUES (?, ?, 'kg')",
                nameEn, nameZh);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /** 记一天：一个动作、一组。{@code reps > 12} 时那天估不出 e1rm。 */
    private void record(String date, long exerciseId, double weight, int reps) {
        WorkoutService.SetInput set = new WorkoutService.SetInput(
                "normal", weight, reps, null, false, null, null, true, null, null);
        workouts.recordSession(new WorkoutService.SessionInput(
                LocalDate.parse(date), null, null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null, List.of(set)))));
    }

    /**
     * 三天：有 e1rm → 估不出 e1rm（15 次）→ 有 e1rm。
     *
     * <p>中间那天同时是「点必须保留」的证据：它的 {@code maxWeight}/{@code totalVolume}
     * 仍然有值，只是 {@code e1rm} 为 null。
     */
    private long threeDays() {
        long ex = newExercise("ZZ Progress Http Bench", null);
        record("2026-10-14", ex, 60.0, 8);
        record("2026-10-15", ex, 20.0, 15);
        record("2026-10-17", ex, 65.0, 5);
        return ex;
    }

    private String getBody(long exerciseId) throws Exception {
        return mvc.perform(get("/api/progress/" + exerciseId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    // ---- 形状 ----

    @Test
    void the_response_has_exactly_the_four_contract_keys_and_iso_dates() throws Exception {
        long ex = threeDays();

        String body = getBody(ex);
        // 前端 fixture 的出处：这一行就是 ProgressView.spec.ts 里那串字符的来源。
        System.out.println("[PROGRESS_JSON] " + body);

        JsonNode root = json.readTree(body);

        // 1. 顶层恰好四个键（多一个少一个前端都可能在读 undefined）
        assertThat(keysOf(root))
                .as("顶层键")
                .containsExactly("exerciseId", "nameEn", "nameZh", "points");
        assertThat(root.get("exerciseId").asLong()).isEqualTo(ex);
        assertThat(root.get("nameEn").asText()).isEqualTo("ZZ Progress Http Bench");
        // 手工录入的动作可以没有中文名：键在、值是 null，不是被省略
        assertThat(root.has("nameZh")).isTrue();
        assertThat(root.get("nameZh").isNull()).isTrue();

        // 2. 每个点恰好四个键
        JsonNode points = root.get("points");
        assertThat(points.isArray()).isTrue();
        assertThat(points).hasSize(3);
        for (JsonNode point : points) {
            assertThat(keysOf(point))
                    .as("点 %s 的键", point.get("date"))
                    .containsExactly("date", "e1rm", "maxWeight", "totalVolume");
        }

        // 3. date 是 "YYYY-MM-DD" 字符串——**不是** [2026,10,14] 数组，也不是 epoch 毫秒
        JsonNode first = points.get(0);
        JsonNode date = first.get("date");
        assertThat(date.isTextual()).as("date 是字符串").isTrue();
        assertThat(date.isArray()).as("date 不是数组（WRITE_DATES_AS_TIMESTAMPS）").isFalse();
        assertThat(date.isNumber()).as("date 不是 epoch 毫秒").isFalse();
        assertThat(date.asText()).isEqualTo("2026-10-14");

        // 数值也顺带钉一下：形状对了但值串了同样没用
        assertThat(points.get(0).get("e1rm").asDouble()).isEqualTo(76.0);
        assertThat(points.get(0).get("maxWeight").asDouble()).isEqualTo(60.0);
        assertThat(points.get(0).get("totalVolume").asDouble()).isEqualTo(480.0);
        assertThat(points.get(2).get("date").asText()).isEqualTo("2026-10-17");
    }

    @Test
    void a_day_that_cannot_be_estimated_keeps_an_explicit_null_e1rm_key() throws Exception {
        long ex = newExercise("ZZ Progress Http Fifteen", "十五次");
        record("2026-10-15", ex, 20.0, 15);

        String body = getBody(ex);

        JsonNode point = json.readTree(body).get("points").get(0);
        assertThat(keysOf(point)).containsExactly("date", "e1rm", "maxWeight", "totalVolume");
        // 键必须在：前端靠「null 或不存在都算没有」是宽容，而后端只承诺 null 这一种
        assertThat(point.has("e1rm")).as("e1rm 键没有被省略").isTrue();
        assertThat(point.get("e1rm").isNull()).as("e1rm 是 JSON null").isTrue();
        // 同一天另外两条序列照常有值——「估不出来」不等于「没练」
        assertThat(point.get("maxWeight").asDouble()).isEqualTo(20.0);
        assertThat(point.get("totalVolume").asDouble()).isEqualTo(300.0);

        // 原始正文里就是那四个字符：改成 omit 或 0 都会让这条红，而服务层测试不会
        assertThat(body).contains("\"e1rm\":null");
    }

    @Test
    void an_unknown_exercise_is_a_404_with_our_error_shape() throws Exception {
        // 用「最大 id + 1000」而不是猜一个空号：共享测试库，别处可能已经用掉了某个 id
        long missing = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000 FROM exercise", Long.class);

        String body = mvc.perform(get("/api/progress/" + missing))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = json.readTree(body);
        // Spring 默认的 {"timestamp","status","error","path"} 形状在这里会让前端读到
        // undefined 的 message：必须是且只是我们的 {error}
        assertThat(keysOf(root)).as("404 正文的键").containsExactly("error");
        assertThat(root.get("error").asText())
                .contains("找不到这个动作")
                .contains(String.valueOf(missing));
    }
}
