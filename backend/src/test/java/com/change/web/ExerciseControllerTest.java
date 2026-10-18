package com.change.web;

import com.change.service.WorkoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
                .andExpect(jsonPath("$.musclesInserted").value(2587));

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
}
