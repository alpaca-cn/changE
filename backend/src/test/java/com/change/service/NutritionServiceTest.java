package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写一餐。
 *
 * <p>@Transactional：跑完回滚，不给共享库留行。服务自身也是 @Transactional，
 * 会加入这个测试事务，所以中途的插入在测试里都看得见、也一起回滚。
 *
 * <p>**注意：这里测不了「整批回滚」**——测试事务本来就会把一切回滚掉，
 * 断言「没有留下半条」在任何实现下都成立。真正证明回滚的是
 * {@code NutritionControllerTest} 里那条（它用独立库文件、真的提交）。
 */
@SpringBootTest
@Transactional
class NutritionServiceTest {

    @Autowired NutritionService nutrition;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChangeLogService changeLog;
    /**
     * JPA 的 save/delete 要等到 flush 才落库，而这里的断言用裸 jdbc 查。
     * 不手动 flush 的话，查到的永远是改动之前的状态——那会让「删了没有」
     * 这类断言在任何实现下都失败，看起来像服务的问题。
     */
    @Autowired jakarta.persistence.EntityManager em;

    /** 100 kcal/100g、10 克蛋白/100g 的测试食物。 */
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

    private NutritionService.MealInput meal(List<NutritionService.ItemInput> items) {
        return new NutritionService.MealInput(LocalDate.of(2026, 10, 19), "lunch",
                LocalDateTime.of(2026, 10, 19, 12, 30), "测试用餐", items);
    }

    private int logsFor(String table, long targetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE target_table = ? AND target_id = ?",
                Integer.class, table, targetId);
    }

    @Test
    void records_a_meal_and_logs_every_row() {
        long rice = newFood("ZZ 营养米饭", "碗", 150);

        NutritionService.Result r = nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(rice, 1, "碗"),
                new NutritionService.ItemInput(rice, 175, null))));

        assertThat(r.items()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE meal_id = ?",
                Integer.class, r.mealId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal WHERE id = ?",
                Integer.class, r.mealId())).isEqualTo(1);

        // 每一行都进审计，且按**本次写入的 target_id** 收口（不用全局计数——
        // 共享库里可能有别的测试提交过的 user 日志）
        assertThat(logsFor("meal", r.mealId())).as("一餐 1 条").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE target_table = 'meal_item' AND target_id IN "
                        + "(SELECT id FROM meal_item WHERE meal_id = ?)",
                Integer.class, r.mealId())).as("2 个条目各 1 条").isEqualTo(2);
    }

    @Test
    void computes_the_totals_from_the_stored_grams() {
        // 150 克 × (100 kcal / 100 克) = 150 kcal；蛋白 10 → 15 克
        long rice = newFood("ZZ 营养合计", "碗", 150);

        NutritionService.Result r = nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(rice, 1, "碗"))));

        assertThat(r.nutrition().kcal()).isEqualTo(150.0);
        assertThat(r.nutrition().protein()).isEqualTo(15.0);
        assertThat(r.nutrition().carb()).isEqualTo(30.0);
        assertThat(r.nutrition().fat()).isEqualTo(7.5);
        assertThat(r.nutrition().fiber()).isEqualTo(1.5);
        assertThat(r.nutrition().sodium()).isEqualTo(450.0);
    }

    @Test
    void computes_grams_from_the_unit_not_from_the_client() {
        // **这条是「前端不传克数」那个契约的杀手。** 契约里根本没有 grams 字段，
        // 克数只能是后端按 food_portion 算出来的。
        long rice = newFood("ZZ 营养单位", "碗", 150);

        nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(rice, 1, "碗"),
                new NutritionService.ItemInput(rice, 175, null))));

        List<Double> grams = jdbc.queryForList(
                "SELECT grams FROM meal_item ORDER BY order_index", Double.class);
        assertThat(grams).containsExactly(150.0, 175.0);
        // 单位名原样留下（null 表示「直接按克录」）
        List<String> units = jdbc.queryForList(
                "SELECT unit_name FROM meal_item ORDER BY order_index", String.class);
        assertThat(units).containsExactly("碗", null);
    }

    @Test
    void grams_are_stored_unrounded_not_display_scaled() {
        // 1.5 个 × 55.5 克 = 83.25。calc/package-info 约定二：定标（两位小数）属于显示层，
        // 先舍再累加会有方向性偏差，而且会让两条录入路径的热量不一致。
        long egg = newFood("ZZ 营养小数", "个", 55.5);

        NutritionService.Result r = nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(egg, 1.5, "个"))));

        assertThat(jdbc.queryForObject("SELECT grams FROM meal_item WHERE meal_id = ?",
                Double.class, r.mealId())).isEqualTo(83.25);
    }

    @Test
    void the_snapshot_stores_dates_as_strings_not_java_time_objects() {
        // ChangeLogService 的 ObjectMapper 没注册 JavaTimeModule：快照里放 LocalDate
        // 会在**记日志那一步**抛异常，把整次写入弄失败。这条是那个坑的杀手。
        long rice = newFood("ZZ 营养快照", "碗", 150);

        NutritionService.Result r = nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(rice, 1, "碗"))));

        String afterJson = jdbc.queryForObject(
                "SELECT after_json FROM change_log WHERE target_table = 'meal' AND target_id = ?",
                String.class, r.mealId());
        assertThat(afterJson).contains("\"meal_date\":\"2026-10-19\"");
        assertThat(afterJson).contains("\"eaten_at\":\"2026-10-19T12:30\"");
        assertThat(afterJson).contains("\"slot\":\"lunch\"");
    }

    @Test
    void refuses_a_meal_with_no_items() {
        assertThatThrownBy(() -> nutrition.recordMeal(meal(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("条目");
    }

    @Test
    void refuses_a_missing_date() {
        assertThatThrownBy(() -> nutrition.recordMeal(
                new NutritionService.MealInput(null, "lunch", null, null,
                        List.of(new NutritionService.ItemInput(1L, 1, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日期");
    }

    @Test
    void refuses_a_bad_slot_before_hitting_the_database() {
        // 库里 meal.slot 有 CHECK；不在服务层先拦的话，那个输入错误会变成 500。
        assertThatThrownBy(() -> nutrition.recordMeal(
                new NutritionService.MealInput(LocalDate.of(2026, 10, 19), "brunch", null, null,
                        List.of(new NutritionService.ItemInput(1L, 1, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("餐次不合法");
    }

    @Test
    void refuses_an_unknown_portion_unit() {
        long rice = newFood("ZZ 营养坏单位", "碗", 150);

        assertThatThrownBy(() -> nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(rice, 1, "盆")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的份量单位");
    }

    @Test
    void refuses_an_unknown_food() {
        assertThatThrownBy(() -> nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(999_999L, 1, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到食物");
    }

    @Test
    void refuses_an_item_without_a_food_id() {
        assertThatThrownBy(() -> nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(null, 1, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foodId");
    }

    @Test
    void refuses_a_non_positive_amount() {
        // amount > 0 由 calc.PortionConverter 与库里的 CHECK (amount > 0) 双重保证。
        long rice = newFood("ZZ 营养零份量", "碗", 150);

        assertThatThrownBy(() -> nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(rice, 0, "碗")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    // ---- 读（Task 4）----

    @Test
    void the_meal_detail_reads_items_in_order_with_their_nutrition() {
        long rice = newFood("ZZ 读一餐", "碗", 150);
        NutritionService.Result r = nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(rice, 1, "碗"),
                new NutritionService.ItemInput(rice, 175, null))));

        NutritionService.MealDetail d = nutrition.mealDetail(r.mealId()).orElseThrow();

        assertThat(d.mealDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(d.slot()).isEqualTo("lunch");
        assertThat(d.eatenAt()).isEqualTo(LocalDateTime.of(2026, 10, 19, 12, 30));
        assertThat(d.items()).extracting(NutritionService.ItemDetail::orderIndex).containsExactly(0, 1);
        assertThat(d.items()).extracting(NutritionService.ItemDetail::grams).containsExactly(150.0, 175.0);
        assertThat(d.items()).extracting(NutritionService.ItemDetail::unitName).containsExactly("碗", null);
        // 食物名跟着回来（界面不用再查一次食物库）
        assertThat(d.items()).extracting(NutritionService.ItemDetail::nameZh).containsOnly("ZZ 读一餐");
        // 每一项的营养值是**实时算**的：150 克 → 150 kcal，175 克 → 175 kcal
        assertThat(d.items().get(0).nutrition().kcal()).isEqualTo(150.0);
        assertThat(d.items().get(1).nutrition().kcal()).isEqualTo(175.0);
        assertThat(d.nutrition().kcal()).isEqualTo(325.0);
    }

    @Test
    void an_unknown_meal_has_no_detail() {
        assertThat(nutrition.mealDetail(-1L)).isEmpty();
    }

    @Test
    void daily_totals_sum_all_meals_of_the_day() {
        long rice = newFood("ZZ 当天合计", "碗", 150);
        nutrition.recordMeal(meal(List.of(new NutritionService.ItemInput(rice, 1, "碗")))); // 150 kcal
        nutrition.recordMeal(new NutritionService.MealInput(
                LocalDate.of(2026, 10, 19), "dinner", null, null,
                List.of(new NutritionService.ItemInput(rice, 2, "碗")))); // 300 kcal

        NutritionService.DailyNutrition daily = nutrition.daily(LocalDate.of(2026, 10, 19));

        assertThat(daily.date()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(daily.meals()).isEqualTo(2);
        assertThat(daily.items()).isEqualTo(2);
        assertThat(daily.nutrition().kcal()).isEqualTo(450.0);
        assertThat(daily.nutrition().protein()).isEqualTo(45.0);
    }

    @Test
    void an_empty_day_is_all_zeros_not_a_404() {
        // 「今天还没吃」是刚启用时的常态，不是错误。返回 404 会让人以为程序坏了。
        NutritionService.DailyNutrition daily = nutrition.daily(LocalDate.of(2030, 1, 1));

        assertThat(daily.meals()).isZero();
        assertThat(daily.items()).isZero();
        assertThat(daily.nutrition().kcal()).isZero();
        assertThat(daily.nutrition().protein()).isZero();
        assertThat(daily.nutrition().sodium()).isZero();
    }

    @Test
    void daily_totals_ignore_other_days() {
        long rice = newFood("ZZ 别的日子", "碗", 150);
        nutrition.recordMeal(meal(List.of(new NutritionService.ItemInput(rice, 1, "碗")))); // 2026-10-19
        nutrition.recordMeal(new NutritionService.MealInput(
                LocalDate.of(2026, 10, 18), "lunch", null, null,
                List.of(new NutritionService.ItemInput(rice, 3, "碗")))); // 前一天

        assertThat(nutrition.daily(LocalDate.of(2026, 10, 19)).nutrition().kcal()).isEqualTo(150.0);
        assertThat(nutrition.daily(LocalDate.of(2026, 10, 18)).nutrition().kcal()).isEqualTo(450.0);
    }

    @Test
    void meals_on_a_day_come_back_in_insertion_order_with_their_items() {
        long rice = newFood("ZZ 一天的餐", "碗", 150);
        NutritionService.Result breakfast = nutrition.recordMeal(new NutritionService.MealInput(
                LocalDate.of(2026, 10, 19), "breakfast", null, null,
                List.of(new NutritionService.ItemInput(rice, 1, "碗"))));
        NutritionService.Result lunch = nutrition.recordMeal(meal(List.of(
                new NutritionService.ItemInput(rice, 1, "碗"),
                new NutritionService.ItemInput(rice, 1, "碗"))));

        List<NutritionService.MealDetail> day = nutrition.mealsOn(LocalDate.of(2026, 10, 19));

        assertThat(day).extracting(NutritionService.MealDetail::id)
                .containsExactly(breakfast.mealId(), lunch.mealId());
        assertThat(day.get(0).items()).hasSize(1);
        assertThat(day.get(1).items()).hasSize(2);
        assertThat(day).extracting(NutritionService.MealDetail::slot)
                .containsExactly("breakfast", "lunch");
    }

    @Test
    void a_day_with_nothing_recorded_returns_an_empty_list() {
        assertThat(nutrition.mealsOn(LocalDate.of(2030, 1, 1))).isEmpty();
    }
    // ── 改餐次 ────────────────────────────────────────────────────────────

    /** 记一餐，返回它的 id。 */
    private long recordOneMeal(String foodName) {
        long rice = newFood(foodName, null, 0);
        nutrition.recordMeal(meal(List.of(new NutritionService.ItemInput(rice, 175, null))));
        return jdbc.queryForObject("SELECT MAX(id) FROM meal", Long.class);
    }

    @Test
    void moving_a_meal_changes_its_slot_and_logs_one_update() {
        long mealId = recordOneMeal("ZZ 挪餐米饭");
        int before = logsFor("meal", mealId);

        nutrition.moveMeal(mealId, "dinner");
        em.flush();

        assertThat(jdbc.queryForObject("SELECT slot FROM meal WHERE id = ?", String.class, mealId))
                .isEqualTo("dinner");
        // 改餐次会改变「早/午/晚各吃了多少」的分布，用户会想撤销 —— 所以必须留日志
        assertThat(logsFor("meal", mealId)).isEqualTo(before + 1);
    }

    @Test
    void moving_a_meal_to_the_same_slot_writes_no_log() {
        long mealId = recordOneMeal("ZZ 同餐次米饭");
        int before = logsFor("meal", mealId);

        nutrition.moveMeal(mealId, "lunch");

        // 没变就别写：撤销历史里多一条「什么也没改」的记录只会碍事
        assertThat(logsFor("meal", mealId)).isEqualTo(before);
    }

    @Test
    void moving_a_meal_to_an_invalid_slot_is_rejected() {
        long mealId = recordOneMeal("ZZ 坏餐次米饭");

        assertThatThrownBy(() -> nutrition.moveMeal(mealId, "brunch"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("餐次不合法");

        assertThat(jdbc.queryForObject("SELECT slot FROM meal WHERE id = ?", String.class, mealId))
                .isEqualTo("lunch");
    }

    @Test
    void moving_a_missing_meal_is_rejected() {
        assertThatThrownBy(() -> nutrition.moveMeal(999_999L, "dinner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到这一餐");
    }

    // ── 删除 ──────────────────────────────────────────────────────────────

    @Test
    void deleting_a_meal_removes_it_and_its_items() {
        long mealId = recordOneMeal("ZZ 删除米饭");

        nutrition.deleteMeal(mealId);
        em.flush();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM meal WHERE id = ?", Integer.class, mealId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM meal_item WHERE meal_id = ?", Integer.class, mealId)).isZero();
    }

    @Test
    void deleting_a_meal_is_logged_but_undo_refuses_it_by_design() {
        long mealId = recordOneMeal("ZZ 撤销米饭");

        nutrition.deleteMeal(mealId);
        em.flush();

        // 删除留下了日志（可审计）
        assertThat(logsFor("meal", mealId)).isGreaterThanOrEqualTo(1);

        // 但通用回放**拒绝**撤销删除：要回退就得重建整行，包括主键。
        // 把这条钉住，免得以后有人以为「有日志 = 能撤销」。
        assertThatThrownBy(() -> changeLog.undo(
                jdbc.queryForObject("SELECT MAX(id) FROM change_log", Long.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("删除");
    }

    @Test
    void deleting_a_missing_meal_is_rejected() {
        assertThatThrownBy(() -> nutrition.deleteMeal(999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到这一餐");
    }
}
