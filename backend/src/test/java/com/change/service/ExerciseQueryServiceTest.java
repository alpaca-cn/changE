package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 动作选择器的筛选规则。 */
@SpringBootTest
@Transactional
class ExerciseQueryServiceTest {

    @Autowired ExerciseQueryService query;
    @Autowired JdbcTemplate jdbc;

    private long newExercise(String nameEn, String nameZh, String category) {
        jdbc.update("INSERT INTO exercise(name_en, name_zh, default_unit, category) VALUES (?,?,'kg',?)",
                nameEn, nameZh, category);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void link(long exerciseId, long muscleGroupId) {
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?,?,'primary')",
                exerciseId, muscleGroupId);
    }

    private long muscleId(String nameEn) {
        return jdbc.queryForObject("SELECT id FROM muscle_group WHERE name_en = ?", Long.class, nameEn);
    }

    private List<String> names(List<ExerciseQueryService.ExerciseSummary> rows) {
        return rows.stream().map(ExerciseQueryService.ExerciseSummary::nameEn).toList();
    }

    @Test
    void by_default_stretching_is_excluded_but_the_other_categories_are_kept() {
        newExercise("ZZ Sel Strength", "力量", "strength");
        newExercise("ZZ Sel Olympic", "抓举", "olympic weightlifting");
        newExercise("ZZ Sel Stretch", "拉伸", "stretching");

        List<String> all = names(query.search(null, "ZZ Sel", null, null));

        assertThat(all).contains("ZZ Sel Strength", "ZZ Sel Olympic");
        assertThat(all).doesNotContain("ZZ Sel Stretch");
    }

    @Test
    void an_explicit_category_includes_stretching_again() {
        newExercise("ZZ Sel Stretch2", "拉伸", "stretching");

        assertThat(names(query.search(null, "ZZ Sel Stretch2", "stretching", null)))
                .containsExactly("ZZ Sel Stretch2");
    }

    @Test
    void filters_by_muscle_group_and_matches_chinese_or_english() {
        long chestId = muscleId("chest");
        long inChest = newExercise("ZZ Sel Chest", "胸部动作", "strength");
        newExercise("ZZ Sel Legs", "腿部动作", "strength");
        link(inChest, chestId);

        assertThat(names(query.search(chestId, "ZZ Sel", null, null))).containsExactly("ZZ Sel Chest");
        assertThat(names(query.search(null, "腿部动作", null, null))).containsExactly("ZZ Sel Legs");
        assertThat(names(query.search(null, "zz sel legs", null, null))).containsExactly("ZZ Sel Legs");
    }

    @Test
    void the_limit_is_capped() {
        for (int i = 0; i < 5; i++) {
            newExercise("ZZ Sel Limit " + i, "限量 " + i, "strength");
        }
        assertThat(query.search(null, "ZZ Sel Limit", null, 2)).hasSize(2);
        assertThat(query.search(null, "ZZ Sel Limit", null, 9999)).hasSize(5);
    }
}
