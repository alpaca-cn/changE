package com.change.agent;

import com.change.service.BodyMetricService;
import com.change.service.NutritionService;
import com.change.service.WorkoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计来源：智能体写的数据必须记成 {@code agent}，不能记成 {@code user}。
 *
 * <p>为什么这值得单独一个测试类：设计文档决定 4 的安全线是「只允许新增与修改，
 * 每次改动写一条 ChangeLog（来源：用户/智能体），支持一键回退」。
 * 三个写服务原来把 {@code ORIGIN = "user"} 写死在类里，于是智能体通过它们写的数据
 * 在审计表里长得**和用户手输的一模一样**——回退时分不清该撤谁，追责时也分不清。
 * 那不是「少了个字段」，是安全带系在假人身上。
 *
 * <p>这一轮给三个服务加了带 origin 的重载，老的单参版本转调它并传 "user"。
 * 所以这里两头都要钉：**默认仍然是 user**（老路径不能被悄悄改掉），
 * **显式传 agent 要真的落进库**（新路径不能只是收下参数然后丢掉）。
 */
@SpringBootTest
@Transactional   // 跑完回滚，不给共享库留行（与 WorkoutServiceTest 一致）
class AgentOriginTest {

    @Autowired
    BodyMetricService metrics;

    @Autowired
    NutritionService nutrition;

    @Autowired
    WorkoutService workout;

    @Autowired
    JdbcTemplate jdbc;

    /** 查某一行最后一次被记进 change_log 时的来源。 */
    private String originOf(String table, long id) {
        List<String> rows = jdbc.queryForList(
                "SELECT origin FROM change_log WHERE target_table = ? AND target_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, table, id);
        assertThat(rows).as("没有为 %s#%d 写下审计行", table, id).hasSize(1);
        return rows.get(0);
    }

    @Test
    void 老的单参重载仍然记成_user() {
        var created = metrics.record(new BodyMetricService.MetricInput(
                "2026-10-20", "weight", 70.1, "kg", "回归：默认来源"));

        assertThat(originOf("body_metric", created.id()))
                .as("老路径的语义必须完全不变，否则用户手输的记录会被当成智能体写的")
                .isEqualTo("user");
    }

    @Test
    void 智能体写身体数据记成_agent() {
        var created = metrics.record(new BodyMetricService.MetricInput(
                "2026-10-20", "weight", 70.2, "kg", "智能体写入"), "agent");

        assertThat(originOf("body_metric", created.id())).isEqualTo("agent");
    }

    @Test
    void 智能体记饮食记成_agent() {
        // 一顿只有一个条目的最简餐。「说一句话记饮食」是设计文档的旗舰用例，
        // 所以这条路径的来源必须是对的——它将来是 undo 唯一的依据。
        long foodId = jdbc.queryForObject("SELECT id FROM food ORDER BY id LIMIT 1", Long.class);
        var result = nutrition.recordMeal(new NutritionService.MealInput(
                LocalDate.of(2026, 10, 20), "lunch", null, "智能体写入",
                List.of(new NutritionService.ItemInput(foodId, 100.0, null))), "agent");

        assertThat(originOf("meal", result.mealId())).isEqualTo("agent");
    }

    @Test
    void 智能体记训练记成_agent() {
        // 测试库没有种动作库（导入只在真实启动时跑），所以自己造一条。
        // 形状照 WorkoutServiceTest#newExercise：exercise 表只有 name_en 与 default_unit 是必填。
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES ('ZZ Agent Bench', 'kg')");
        long exerciseId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        var result = workout.recordSession(new WorkoutService.SessionInput(
                LocalDate.of(2026, 10, 20), "智能体写入", null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null,
                        List.of(new WorkoutService.SetInput(
                                "normal", 60.0, 8, null, false, null, null, true, null, null))))),
                "agent");

        // 一次训练会写三张表（session / session_exercise / workout_set），每张都要是 agent
        assertThat(originOf("workout_session", result.sessionId())).isEqualTo("agent");
    }
}
