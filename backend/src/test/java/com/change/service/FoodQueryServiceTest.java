package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 食物搜索。
 *
 * <p>所有用例的关键词都带 {@code ZZ 查询} 前缀：共享库里还有随包 seed 的 35 条常吃项，
 * 不带前缀的断言会被它们影响（`an_empty_query_lists_foods` 那条还**依赖**它们存在）。
 */
@SpringBootTest
@Transactional
class FoodQueryServiceTest {

    @Autowired FoodQueryService query;
    @Autowired JdbcTemplate jdbc;

    private long newFood(String nameZh, String nameEn, double kcal) {
        jdbc.update("INSERT INTO food(name_zh, name_en, source, kcal_per100, protein_per100, "
                + "carb_per100, fat_per100, fiber_per100, sodium_per100) "
                + "VALUES (?, ?, 'manual', ?, 10, 20, 5, 1, 300)", nameZh, nameEn, kcal);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void newPortion(long foodId, String unitName, double grams) {
        jdbc.update("INSERT INTO food_portion(food_id, unit_name, grams) VALUES (?,?,?)",
                foodId, unitName, grams);
    }

    private List<String> names(String q) {
        return query.search(q, null).stream().map(FoodQueryService.FoodSummary::nameZh).toList();
    }

    @Test
    void matches_the_chinese_name() {
        newFood("ZZ 查询豆腐", "tofu", 98);
        assertThat(names("ZZ 查询豆腐")).containsExactly("ZZ 查询豆腐");
    }

    @Test
    void matches_the_english_name_ignoring_case() {
        // 英文名用一个**不可能与 seed 撞上**的记号。第一次写的是 "Chicken Breast"，
        // 而 seed 里有三条英文名含 "chicken" 的食物（cooked chicken breast /
        // chicken thigh / kung pao chicken）——`containsExactly` 因此多出三条而变红。
        // 测试数据与随包数据撞车时，坏的是测试而不是代码。
        newFood("ZZ 查询鸡胸", "ZzUniquePoultryToken", 165);

        assertThat(names("zz 查询鸡胸")).containsExactly("ZZ 查询鸡胸");
        assertThat(names("zzuniquepoultrytoken")).containsExactly("ZZ 查询鸡胸");
        assertThat(names("ZZUNIQUEPOULTRYTOKEN")).containsExactly("ZZ 查询鸡胸");
    }

    @Test
    void an_empty_query_lists_foods_rather_than_nothing() {
        // 选择器一打开就该有东西可挑。空关键词走 findAll，而不是返回空数组。
        // （共享库里本来就有 seed 的 35 条，所以这条不依赖下面这条新插入的。）
        newFood("ZZ 查询首屏", "first page", 1);

        assertThat(query.search(null, null)).isNotEmpty();
        assertThat(query.search("   ", null)).isNotEmpty();
    }

    @Test
    void the_limit_is_capped_at_two_hundred() {
        for (int i = 0; i < 205; i++) {
            newFood("ZZ 查询限流 " + i, "cap " + i, 1);
        }

        assertThat(query.search("ZZ 查询限流", 9999)).hasSize(FoodQueryService.MAX_LIMIT);
        assertThat(query.search("ZZ 查询限流", 3)).hasSize(3);
        // 不给 limit 就用默认值
        assertThat(query.search("ZZ 查询限流", null)).hasSize(FoodQueryService.DEFAULT_LIMIT);
    }

    @Test
    void the_page_is_sorted_so_the_same_data_gives_the_same_page() {
        // 截断在排序**之后**：否则「前 50 条」是数据库的返回顺序，
        // 同一份数据在不同运行里可能给出不同的页，用户会以为某条食物没了。
        newFood("ZZ 查询排序 B", "sort b", 1);
        newFood("ZZ 查询排序 A", "sort a", 1);
        newFood("ZZ 查询排序 C", "sort c", 1);

        assertThat(names("ZZ 查询排序")).containsExactly(
                "ZZ 查询排序 A", "ZZ 查询排序 B", "ZZ 查询排序 C");
    }

    @Test
    void portions_ride_along_and_a_food_without_them_gets_an_empty_list() {
        long withUnit = newFood("ZZ 查询有单位", "with unit", 100);
        newPortion(withUnit, "碗", 150);
        newPortion(withUnit, "份", 80);
        newFood("ZZ 查询无单位", "no unit", 100);

        FoodQueryService.FoodSummary a = query.search("ZZ 查询有单位", null).get(0);
        FoodQueryService.FoodSummary b = query.search("ZZ 查询无单位", null).get(0);

        assertThat(a.portions()).extracting(FoodQueryService.PortionSummary::unitName)
                .containsExactlyInAnyOrder("碗", "份");
        assertThat(a.portions()).extracting(FoodQueryService.PortionSummary::grams)
                .containsExactlyInAnyOrder(150.0, 80.0);
        // 没有单位的给空列表，**不是 null**——前端可以直接 .length 而不用先判空
        assertThat(b.portions()).isNotNull().isEmpty();
    }

    @Test
    void every_food_carries_the_per100_values() {
        // 前端预览要用。少一个字段界面会显示 NaN，而那看起来像「食物数据有问题」。
        newFood("ZZ 查询营养", "nutrition", 123);

        FoodQueryService.FoodSummary f = query.search("ZZ 查询营养", null).get(0);

        assertThat(f.per100().kcal()).isEqualTo(123.0);
        assertThat(f.per100().protein()).isEqualTo(10.0);
        assertThat(f.per100().carb()).isEqualTo(20.0);
        assertThat(f.per100().fat()).isEqualTo(5.0);
        assertThat(f.per100().fiber()).isEqualTo(1.0);
        assertThat(f.per100().sodium()).isEqualTo(300.0);
        assertThat(f.source()).isEqualTo("manual");
        assertThat(f.nameEn()).isEqualTo("nutrition");
    }
}
