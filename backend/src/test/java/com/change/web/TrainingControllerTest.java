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
}
