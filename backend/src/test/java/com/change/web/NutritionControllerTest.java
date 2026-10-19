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
 * 饮食端点的 HTTP 层。**独立库文件**：这些端点是**真的提交**的（不受测试事务保护），
 * 用共享库会污染别的测试。
 *
 * <p>正因为它是真的提交，「整批回滚」这件事**只有在这里才测得出来**——
 * {@code NutritionServiceTest} 的 @Transactional 会把一切回滚掉，
 * 那条断言在任何实现下都成立。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/nutrition-controller.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class NutritionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /**
     * 每个测试前把饮食域清干净。库文件跨运行持久，而 {@code meal_item.food_id} 是指向
     * {@code food(id)} 的**裸外键**（没有 ON DELETE CASCADE）——不清干净就删食物会撞 FK。
     * 顺序必须是子表在前。
     */
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM meal_item");
        jdbc.update("DELETE FROM meal");
        jdbc.update("DELETE FROM food_portion");
        jdbc.update("DELETE FROM food");
        // 只清**本域**的审计行。change_log 里还有启动时动作库导入写下的 3463 条
        // （origin='import'），那些不属于这里、也不能删。
        jdbc.update("DELETE FROM change_log WHERE target_table IN ('meal','meal_item')");
    }

    private long newFood(String nameZh, String unitName, double gramsPerUnit) {
        jdbc.update("INSERT INTO food(name_zh, source, kcal_per100, protein_per100, carb_per100, "
                + "fat_per100, fiber_per100, sodium_per100) VALUES (?, 'manual', 100, 10, 20, 5, 1, 300)",
                nameZh);
        long foodId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        if (unitName != null) {
            jdbc.update("INSERT INTO food_portion(food_id, unit_name, grams) VALUES (?,?,?)",
                    foodId, unitName, gramsPerUnit);
        }
        return foodId;
    }

    @Test
    void posts_a_meal_and_returns_its_totals() throws Exception {
        long rice = newFood("ZZ Ctrl 米饭", "碗", 150);
        String body = """
                {
                  "mealDate": "2026-10-19",
                  "slot": "lunch",
                  "eatenAt": "2026-10-19T12:30",
                  "note": "食堂",
                  "items": [
                    { "foodId": %d, "amount": 1, "unitName": "碗" },
                    { "foodId": %d, "amount": 150, "unitName": null }
                  ]
                }
                """.formatted(rice, rice);

        mvc.perform(post("/api/meals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mealId").isNumber())
                .andExpect(jsonPath("$.items").value(2))
                // 300 克 × 100 kcal/100 克
                .andExpect(jsonPath("$.nutrition.kcal").value(300.0))
                .andExpect(jsonPath("$.nutrition.protein").value(30.0));
    }

    @Test
    void a_bad_request_is_a_400_with_a_readable_message() throws Exception {
        String body = """
                { "mealDate": "2026-10-19", "slot": "lunch", "items": [] }
                """;
        mvc.perform(post("/api/meals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void a_bad_slot_is_a_400_not_a_500() throws Exception {
        // 库里 meal.slot 有 CHECK。服务层先拦，所以调用方拿到的是「你传错了」而不是「服务器坏了」。
        long rice = newFood("ZZ Ctrl 餐次", null, 0);
        String body = """
                { "mealDate": "2026-10-19", "slot": "brunch",
                  "items": [ { "foodId": %d, "amount": 100 } ] }
                """.formatted(rice);

        mvc.perform(post("/api/meals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("餐次不合法")));
    }

    @Test
    void a_failure_in_the_middle_of_the_meal_leaves_nothing_behind() throws Exception {
        // **这条是「一个事务」的杀手，而且只有在这里成立**（独立库、真的提交，没有测试事务
        // 兜底）。第一个条目合法、第二个指向一个不存在的食物：没有事务的话，
        // 库里会留下 1 餐 + 1 条目——用户以为记好了，其实少了一条，而且他不会知道少了哪个。
        long rice = newFood("ZZ Ctrl 回滚", "碗", 150);
        String body = """
                { "mealDate": "2026-10-19", "slot": "lunch",
                  "items": [
                    { "foodId": %d, "amount": 1, "unitName": "碗" },
                    { "foodId": 999999, "amount": 100 }
                  ] }
                """.formatted(rice);

        mvc.perform(post("/api/meals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        assertCount("meal", 0);
        assertCount("meal_item", 0);
        // 审计也不该留下半条（它在同一个事务里）。
        // 不能断言整张 change_log 为空：这张独立库里还有启动时动作库导入写下的 3463 条。
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM change_log WHERE target_table IN ('meal','meal_item')",
                        Integer.class))
                .as("回滚后本域的审计行").isZero();
    }

    // ---- 读（Task 4）----

    /** 记一餐并返回它的 id。 */
    private int postMeal(long foodId, String slot, double amount, String unitName) throws Exception {
        String unit = unitName == null ? "null" : "\"" + unitName + "\"";
        String body = """
                { "mealDate": "2026-10-19", "slot": "%s", "eatenAt": "2026-10-19T12:30",
                  "items": [ { "foodId": %d, "amount": %s, "unitName": %s } ] }
                """.formatted(slot, foodId, String.valueOf(amount), unit);
        String created = mvc.perform(post("/api/meals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(created, "$.mealId");
    }

    @Test
    void reads_a_meal_back_through_the_api() throws Exception {
        long rice = newFood("ZZ Ctrl 读一餐", "碗", 150);
        int mealId = postMeal(rice, "lunch", 1, "碗");

        mvc.perform(get("/api/meals/" + mealId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot").value("lunch"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].nameZh").value("ZZ Ctrl 读一餐"))
                .andExpect(jsonPath("$.items[0].grams").value(150.0))
                .andExpect(jsonPath("$.items[0].nutrition.kcal").value(150.0))
                .andExpect(jsonPath("$.nutrition.kcal").value(150.0));
    }

    @Test
    void an_unknown_meal_is_404_carrying_our_reason() throws Exception {
        mvc.perform(get("/api/meals/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("找不到这一餐: 999999"));
    }

    @Test
    void reads_the_days_meals_and_totals_through_the_api() throws Exception {
        long rice = newFood("ZZ Ctrl 当天", "碗", 150);
        postMeal(rice, "breakfast", 1, "碗");   // 150 kcal
        postMeal(rice, "lunch", 2, "碗");       // 300 kcal

        mvc.perform(get("/api/meals").param("date", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // 按录入顺序（breakfast 先记）
                .andExpect(jsonPath("$[0].slot").value("breakfast"))
                .andExpect(jsonPath("$[1].items.length()").value(1));

        mvc.perform(get("/api/nutrition/daily").param("date", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-19"))
                .andExpect(jsonPath("$.meals").value(2))
                .andExpect(jsonPath("$.items").value(2))
                .andExpect(jsonPath("$.nutrition.kcal").value(450.0))
                .andExpect(jsonPath("$.nutrition.protein").value(45.0));
    }

    @Test
    void an_empty_day_is_200_with_all_zeros() throws Exception {
        // **不是 404**：刚装上的人打开今日页，看到的是「今天 0 千卡」，不是「查不到」。
        mvc.perform(get("/api/nutrition/daily").param("date", "2030-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meals").value(0))
                .andExpect(jsonPath("$.nutrition.kcal").value(0.0));

        mvc.perform(get("/api/meals").param("date", "2030-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void a_bad_date_is_a_400() throws Exception {
        mvc.perform(get("/api/nutrition/daily").param("date", "2026/10/19"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ---- 食物搜索（Task 5）----

    @Test
    void lists_foods_through_the_api() throws Exception {
        newFood("ZZ Ctrl 选择器", null, 0);

        mvc.perform(get("/api/foods").param("q", "ZZ Ctrl 选择器"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nameZh").value("ZZ Ctrl 选择器"))
                // 每 100 克的值必须跟着回来：前端预览要用，少一个界面会显示 NaN
                .andExpect(jsonPath("$[0].per100.kcal").value(100.0))
                .andExpect(jsonPath("$[0].per100.sodium").value(300.0))
                .andExpect(jsonPath("$[0].portions").isArray());
    }

    @Test
    void a_food_carries_its_portions_so_the_client_can_preview() throws Exception {
        // 「1 碗 = 150 克」这一条是**预览**用的：入库的克数仍由后端算，
        // 但界面要在录入时就把大概热量显示出来。
        newFood("ZZ Ctrl 预览", "碗", 150);

        mvc.perform(get("/api/foods").param("q", "ZZ Ctrl 预览"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].portions[0].unitName").value("碗"))
                .andExpect(jsonPath("$[0].portions[0].grams").value(150.0));
    }

    private void assertCount(String table, int expected) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        org.assertj.core.api.Assertions.assertThat(n)
                .as("回滚后 %s 的行数", table).isEqualTo(expected);
    }
}
