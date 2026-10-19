package com.change.repo;

import com.change.domain.MealItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条目的按序读取。
 *
 * <p>为什么单独测顺序：调用方（餐详情、当天合计）依赖它展示，反了不会报错、只会错位。
 * 插入时故意打乱顺序，断言读回来是按 {@code order_index} 升序（不是插入序）。
 *
 * <p>{@code @Transactional}：跑完回滚，不给共享库留行。
 */
@SpringBootTest
@Transactional
class MealItemRepoTest {

    @Autowired MealItemRepo items;
    @Autowired JdbcTemplate jdbc;

    private long newFood(String nameZh) {
        jdbc.update("INSERT INTO food(name_zh, source) VALUES (?, 'manual')", nameZh);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long newMeal(LocalDate date) {
        jdbc.update("INSERT INTO meal(meal_date, slot) VALUES (?, 'lunch')", date.toString());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long newItem(long mealId, long foodId, int orderIndex, double amount, String unitName, double grams) {
        jdbc.update("INSERT INTO meal_item(meal_id, food_id, order_index, amount, unit_name, grams) "
                + "VALUES (?,?,?,?,?,?)", mealId, foodId, orderIndex, amount, unitName, grams);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Test
    void items_come_back_in_order_index_order_not_insertion_order() {
        long mealId = newMeal(LocalDate.of(2026, 10, 19));
        long rice = newFood("ZZ 测试米饭");
        long chicken = newFood("ZZ 测试鸡胸");

        long second = newItem(mealId, chicken, 1, 150.0, null, 150.0);
        long first = newItem(mealId, rice, 0, 1.0, "碗", 150.0);

        assertThat(items.findByMealIdOrderByOrderIndexAsc(mealId))
                .extracting(i -> i.id)
                .containsExactly(first, second);
    }

    @Test
    void a_null_unit_name_means_the_amount_is_already_grams() {
        // 这个语义与 calc.PortionConverter.toGrams 的 unit 参数一致（传 null 直接返回 amount）。
        // 库里 unit_name 可空，正是因为「直接填克数」是一个真实的录入方式。
        long mealId = newMeal(LocalDate.of(2026, 10, 19));
        long food = newFood("ZZ 测试按克");

        long id = newItem(mealId, food, 0, 175.0, null, 175.0);

        MealItem read = items.findById(id).orElseThrow();
        assertThat(read.unitName).isNull();
        assertThat(read.amount).isEqualTo(175.0);
        assertThat(read.grams).isEqualTo(175.0);
    }

    @Test
    void grams_keep_their_decimals_rather_than_being_display_scaled() {
        // 1.5 × 55.5 = 83.25。写完就把克数定标到两位（83.25 在这里碰巧也一样）或用整数
        // 截断（83）都会让「份量录入」与「直接填克数」两条路径算出的热量不再一致
        // （calc/package-info 约定二）。这里用一个**三位小数**的值把定标彻底钉住。
        long mealId = newMeal(LocalDate.of(2026, 10, 19));
        long food = newFood("ZZ 测试小数");

        long id = newItem(mealId, food, 0, 1.5, "个", 55.5 * 1.5);

        assertThat(items.findById(id).orElseThrow().grams).isEqualTo(83.25);
    }

    @Test
    void finds_the_items_of_several_meals_grouped_and_ordered() {
        // 一次取多餐的条目：先按 mealId、再按 orderIndex。
        long mealA = newMeal(LocalDate.of(2026, 10, 19));
        long mealB = newMeal(LocalDate.of(2026, 10, 19));
        long food = newFood("ZZ 测试多餐");

        long a1 = newItem(mealA, food, 1, 1.0, null, 1.0);
        long a0 = newItem(mealA, food, 0, 1.0, null, 1.0);
        long b0 = newItem(mealB, food, 0, 1.0, null, 1.0);

        List<MealItem> all = items.findByMealIdInOrderByMealIdAscOrderIndexAsc(List.of(mealA, mealB));

        assertThat(all).extracting(i -> i.id).containsExactly(a0, a1, b0);
    }
}
