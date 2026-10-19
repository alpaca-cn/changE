package com.change.repo;

import com.change.domain.Meal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Meal} 的两个日期字段在**真实数据库**里的落库形态。
 *
 * <p>为什么必须绕过实体、用 {@link JdbcTemplate} 读原始列：这两个字段的失效方式都是
 * **在写入那一刻**发生的（驱动把 java.time 写成纪元毫秒），而读回实体时 Hibernate
 * 会把同一串数字再解释回来——**往返测试因此是绿的**，看不出一列到底存了什么。
 * 只有直接读列才能看见。
 *
 * <p>两列的性质还不一样：`meal_date` 有形状 CHECK，写错会**响亮失败**；
 * `eaten_at` **没有 CHECK**，写错只会**静默**留下一串数字。后者才是真正需要测试守的。
 */
@SpringBootTest
@Transactional
class MealRepoTest {

    @Autowired MealRepo meals;
    @Autowired JdbcTemplate jdbc;

    private Meal newMeal(LocalDate date, LocalDateTime eatenAt) {
        Meal m = new Meal();
        m.mealDate = date;
        m.slot = "lunch";
        m.eatenAt = eatenAt;
        meals.save(m);
        return m;
    }

    private String rawColumn(String column, long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM meal WHERE id = ?", String.class, id);
    }

    @Test
    void the_meal_date_round_trips_as_an_iso_string() {
        // 写错会被 GLOB CHECK 拒绝（因为那一列有 CHECK）——这条主要钉住「形状是 YYYY-MM-DD」，
        // 因为按日期查询的 WHERE 依赖它：'2026-10-19T03:22:11' 会让等值查询返回 0 行且不报错。
        Meal m = newMeal(LocalDate.of(2026, 10, 19), null);
        assertThat(rawColumn("meal_date", m.id)).isEqualTo("2026-10-19");
    }

    @Test
    void the_eaten_at_is_not_stored_as_epoch_millis() {
        // **这条是「静默存成纪元毫秒」的杀手。** eaten_at 没有形状 CHECK：
        // 少了 @JdbcTypeCode(LOCAL_DATE_TIME)，xerial 驱动会写进 '1792...' 这样一串数字，
        // 不报错、也不影响任何测试——直到有人真的按时间排序或显示它。
        // 断言前缀而不是整串：秒为 0 时 LocalDateTime.toString() 会省略 ':00'。
        Meal m = newMeal(LocalDate.of(2026, 10, 19), LocalDateTime.of(2026, 10, 19, 12, 30));

        String raw = rawColumn("eaten_at", m.id);

        assertThat(raw).startsWith("2026-10-19T12:30");
        assertThat(raw).as("纪元毫秒是一条纯数字串（13 位）").doesNotMatch("\\d{13}");
    }

    @Test
    void eaten_at_may_be_null() {
        // eaten_at 可空：只记「吃了什么」不记几点吃，是一个真实状态。
        Meal m = newMeal(LocalDate.of(2026, 10, 19), null);
        assertThat(rawColumn("eaten_at", m.id)).isNull();
    }

    @Test
    void finds_only_the_meals_of_that_day_in_insertion_order() {
        Meal first = newMeal(LocalDate.of(2026, 10, 19), null);
        Meal other = newMeal(LocalDate.of(2026, 10, 18), null);
        Meal second = newMeal(LocalDate.of(2026, 10, 19), null);

        assertThat(meals.findByMealDateOrderByIdAsc(LocalDate.of(2026, 10, 19)))
                .extracting(m -> m.id)
                .containsExactly(first.id, second.id);

        // 反面：别的那天不该混进来
        assertThat(other.id).isNotNull();
        assertThat(meals.findByMealDateOrderByIdAsc(LocalDate.of(2026, 10, 18)))
                .extracting(m -> m.id)
                .containsExactly(other.id);
    }
}
