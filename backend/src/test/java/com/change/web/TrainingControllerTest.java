package com.change.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 训练端点。独立库文件：这些端点是**真的提交**的（不受测试事务保护），
 * 用共享库会污染别的测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/training-controller.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class TrainingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /**
     * 每个测试前把**训练域**清干净。库文件跨运行持久，而 `session_exercise.exercise_id` 是指向
     * `exercise(id)` 的**裸外键（没有 ON DELETE CASCADE）**——不清干净就删动作会直接撞 FK。
     * 顺序必须是子表在前。
     */
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM workout_set");
        jdbc.update("DELETE FROM session_exercise");
        jdbc.update("DELETE FROM workout_session");
    }

    private long newExercise(String nameEn) {
        jdbc.update("DELETE FROM exercise WHERE name_en = ?", nameEn);
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Test
    void posts_a_session_and_returns_its_counts() throws Exception {
        long bench = newExercise("ZZ Ctrl Bench");
        String body = """
                {
                  "sessionDate": "2026-10-18",
                  "title": "胸日",
                  "startedAt": "2026-10-18T19:30",
                  "exercises": [
                    { "exerciseId": %d, "sets": [
                        { "weight": 60, "repetitions": 8, "completed": true },
                        { "weight": 62.5, "repetitions": 6, "completed": true } ] }
                  ]
                }
                """.formatted(bench);

        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exercises").value(1))
                .andExpect(jsonPath("$.sets").value(2))
                .andExpect(jsonPath("$.sessionId").isNumber());
    }

    @Test
    void a_bad_request_is_a_400_with_a_readable_message_not_a_500() throws Exception {
        String body = """
                { "sessionDate": "2026-10-18", "exercises": [] }
                """;
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void reads_a_session_back_through_the_api() throws Exception {
        long bench = newExercise("ZZ Ctrl Detail Bench");
        String body = """
                { "sessionDate": "2026-10-18", "title": "腿日",
                  "exercises": [ { "exerciseId": %d, "sets": [ { "weight": 100, "repetitions": 5, "completed": true } ] } ] }
                """.formatted(bench);

        String created = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int sessionId = com.jayway.jsonpath.JsonPath.read(created, "$.sessionId");

        mvc.perform(get("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("腿日"))
                .andExpect(jsonPath("$.exercises[0].exerciseId").value((int) bench))
                .andExpect(jsonPath("$.exercises[0].sets[0].weight").value(100.0));

        mvc.perform(get("/api/sessions/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber());

        mvc.perform(get("/api/sessions/999999"))
                .andExpect(status().isNotFound());
    }

    /**
     * 404 的正文必须是**我们写的那句话**，而不是 Spring 的默认错误体。
     *
     * <p>这条是**真机端到端**跑出来的：前端在真机上打开一个不存在的训练时，界面上只有英文的
     * `Not Found`。根因是 {@code ResponseStatusException} 默认由 Spring 渲染成
     * {@code {"timestamp":…,"status":404,"error":"Not Found","path":…}}——reason 里那句中文
     * **根本不出现**（默认 {@code server.error.include-message=never}）。
     * {@code ApiErrors} 现在把它收进与 400 同一套 {@code {error}} 形状。
     *
     * <p><b>这条测试在 MockMvc 里到底杀得掉什么（变异实测后的诚实结论）</b>：
     * 把 {@code ApiErrors} 那个 handler 的注解改成匹配不到的类型，这一条会红（报
     * {@code No value at JSON path "$.error"}）——但**不是因为**正文变成了 Spring 的默认体，
     * 而是因为 <b>MockMvc 不跑 {@code /error} 那一页</b>，没有 handler 时正文干脆是空的。
     * 换句话说：`status 仍是 404` 与 `error 是那句中文` 两条在 MockMvc 里有牙齿，
     * 而 `timestamp` / `path` **不该存在**那两条在这里是**恒真**的——真正给它们牙齿的是
     * Rust 侧那条真机 e2e（`end_to_end_proxy_reads_and_reports_statuses_from_the_real_backend`
     * 里的 `!body.contains("timestamp")`，它打的是真实服务器）。
     * 留着它们是因为它们把契约写下来了，而且哪天有人改成真的走 `/error` 页就会响。
     */
    @Test
    void a_missing_session_carries_our_reason_not_springs_default_error_body() throws Exception {
        mvc.perform(get("/api/sessions/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("找不到这次训练: 999999"))
                // 恒真（MockMvc 不跑 /error 页）；真正的守卫在 Rust 侧的真机 e2e
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }
}
