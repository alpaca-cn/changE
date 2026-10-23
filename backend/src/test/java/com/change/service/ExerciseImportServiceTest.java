package com.change.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 导入的执行与幂等。
 *
 * <p><b>为什么是 @SpringBootTest 且不 @Transactional</b>：要验证的是「一次导入真的落盘、
 * 再跑一次结果不变」——回滚的事务观察不到这件事。
 *
 * <p><b>为什么要手动清理（这是本类最容易出错的地方）。</b>导入会**提交** 876 行动作
 * 与 2741 行肌群标注，而测试库是全类共享的磁盘库。其中最凶的一点：数据里有
 * {@code Plank}，而 {@code TrainingRepoTest} 也要插一个叫 {@code Plank} 的动作——
 * 导入留下的行会让那条测试撞 {@code ux_exercise_name_en} 唯一索引，红在一个与被测行为
 * 完全无关的地方。所以 {@link #cleanUp()} 必须在每个方法后把导入的行与日志删干净。
 *
 * <p>断言一律按 {@code source_id} 收口（而不是全表 COUNT）：这样既精确表达了「导入产出
 * 了多少」，又不会被别处留下的手工行干扰。
 */
@SpringBootTest
class ExerciseImportServiceTest {

    private static final String PREFIX = "free-exercise-db:";

    @Autowired
    ExerciseImportService importService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        removeImported();
        jdbc.update("DELETE FROM exercise WHERE name_en LIKE 'ZZ %'");
    }

    @AfterEach
    void tearDown() {
        // 必须在每个方法后清干净：导入留下的 "Plank" 会让 TrainingRepoTest 撞唯一索引。
        removeImported();
        jdbc.update("DELETE FROM exercise WHERE name_en LIKE 'ZZ %'");
    }

    @Test
    void first_import_creates_876_exercises_and_2741_muscle_links() {
        ExerciseImportService.Result r = importService.importAll();

        assertThat(r.inserted()).isEqualTo(876);
        assertThat(r.skipped()).isZero();
        assertThat(r.musclesInserted()).isEqualTo(2741);

        assertThat(importedExercises()).isEqualTo(876);
        assertThat(importedLinks()).isEqualTo(2741);
    }

    @Test
    void a_second_import_changes_nothing() {
        importService.importAll();

        ExerciseImportService.Result again = importService.importAll();

        assertThat(again.inserted()).as("已存在的动作应当被跳过").isZero();
        assertThat(again.skipped()).isEqualTo(876);
        assertThat(again.musclesInserted()).isZero();

        assertThat(importedExercises()).isEqualTo(876);
        assertThat(importedLinks()).isEqualTo(2741);
    }

    @Test
    void every_imported_exercise_carries_the_namespace_prefix() {
        importService.importAll();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise " +
                "WHERE source_id IS NOT NULL AND source_id NOT LIKE 'free-exercise-db:%'",
                Integer.class))
                .as("导入的动作 source_id 必须全部带命名空间前缀")
                .isZero();
    }

    @Test
    void the_overlapping_exercise_gets_exactly_one_primary_quadriceps_link() {
        importService.importAll();

        // Barbell Step Ups 的 quadriceps 同时出现在 primaryMuscles 与 secondaryMuscles 里。
        // 朴素两边都写会撞 (exercise_id, muscle_group_id) 唯一索引、整批回滚；
        // 规则是 primary 赢，所以此刻库里应当**恰好一条**、且 role='primary'。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT em.role FROM exercise_muscle em " +
                "JOIN exercise e ON e.id = em.exercise_id " +
                "JOIN muscle_group g ON g.id = em.muscle_group_id " +
                "WHERE e.name_en = 'Barbell Step Ups' AND g.name_en = 'quadriceps'");

        assertThat(rows).as("重叠的肌肉只能有一条标注").hasSize(1);
        assertThat(rows.get(0).get("role")).isEqualTo("primary");
    }

    @Test
    void every_imported_muscle_link_is_written_to_the_change_log() {
        importService.importAll();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE origin = 'import' AND target_table = 'exercise_muscle'",
                Integer.class))
                .as("2741 条 join 行都要有日志，否则「这次导入动了什么」答不出来")
                .isEqualTo(2741);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE origin = 'import' AND target_table = 'exercise'",
                Integer.class))
                .as("876 条动作本身也要有日志")
                .isEqualTo(876);

        // 抽挑一条具体的 join 行，确认它真的被记了（而非只是总数凑巧对上）。
        Long joinId = jdbc.queryForObject(
                "SELECT em.id FROM exercise_muscle em " +
                "JOIN exercise e ON e.id = em.exercise_id " +
                "JOIN muscle_group g ON g.id = em.muscle_group_id " +
                "WHERE e.name_en = 'Barbell Step Ups' AND g.name_en = 'quadriceps'",
                Long.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log " +
                "WHERE origin = 'import' AND target_table = 'exercise_muscle' AND target_id = ?",
                Integer.class, joinId))
                .as("Barbell Step Ups 的 quadriceps 连接行必须有一条对应日志")
                .isEqualTo(1);
    }

    @Test
    void manual_exercises_are_not_touched_by_import() {
        jdbc.update("INSERT INTO exercise (name_en, default_unit) VALUES ('ZZ Manual Import Test', 'kg')");
        int manualBefore = manualExercises();

        importService.importAll();

        assertThat(manualExercises()).as("手工动作的数量不受导入影响").isEqualTo(manualBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise " +
                "WHERE name_en = 'ZZ Manual Import Test' AND source_id IS NULL",
                Integer.class))
                .as("导入不能给手工动作补 source_id")
                .isEqualTo(1);
    }

    @Test
    void a_missing_muscle_aborts_the_whole_import_and_rolls_it_back() {
        // 两件事一起守：① 肌群查不到必须抛异常而不是静默跳过（否则那个动作少一块标注、
        // 没人知道）；② 整个导入必须是一个事务——已经写进去的动作与日志都要跟着回滚，
        // 半导入的库（有动作、没标注）是最坏的中间态。
        //
        // 用 neck：它被 9 条动作引用，首次出现在数据集的第 161 条附近，所以失败之前
        // 已经写入了约 160 个动作与它们的标注——足够证明「回滚」真的发生了。
        Long neckId = jdbc.queryForObject(
                "SELECT id FROM muscle_group WHERE name_en = 'neck'", Long.class);
        jdbc.update("DELETE FROM muscle_group WHERE name_en = 'neck'");
        try {
            assertThatThrownBy(() -> importService.importAll())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("neck");

            assertThat(importedExercises()).as("失败后不能留下半导入的动作").isZero();
            assertThat(importedLinks()).as("失败后不能留下半导入的肌群标注").isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM change_log WHERE origin = 'import'", Integer.class))
                    .as("回滚必须把导入写入的日志一起回滚")
                    .isZero();
        } finally {
            // 精确补回种子行（连 id 一起），否则 MuscleGroupSeedTest / MuscleRegionMappingTest
            // 会看到一张走样的表。
            // region_ids 必须是 V10 之后的值：V7 的占位名 'neck' 已被 V10 换成素材的
            // neck-left,neck-right,nape（见 V10__muscle_region_ids.sql）。
            jdbc.update(
                    "INSERT INTO muscle_group(id, name_zh, name_en, body_part, region_ids, "
                            + "weekly_sets_min, weekly_sets_max) "
                            + "VALUES (?, '颈部', 'neck', '颈', 'neck-left,neck-right,nape', NULL, NULL)",
                    neckId);
        }
    }

    @Test
    void unimport_removes_every_imported_exercise_and_its_muscle_links() {
        importService.importAll();
        assertThat(importedExercises()).isEqualTo(876);
        assertThat(importedLinks()).isEqualTo(2741);

        int removed = importService.unimport();

        assertThat(removed).isEqualTo(876);
        assertThat(importedExercises()).isZero();
        // 级联删除实测生效：unimport() 没有显式删 join 行，它们是被 ON DELETE CASCADE 带走的
        // （foreign_keys=true 靠 JDBC URL，计划 1 踩过它为 0 的坑）。
        assertThat(importedLinks()).as("join 行必须被级联删掉，不能留下孤儿").isZero();

        // 日志是历史，撤销不是抹除历史：3463 条 insert 日志仍在
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE origin = 'import' AND action = 'insert'",
                Integer.class))
                .isEqualTo(876 + 2741);
        // 外加一条汇总 delete 日志，作为「那 876 个动作哪去了」的证据
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE origin = 'import' AND action = 'delete'",
                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void unimport_is_idempotent_and_spares_manual_exercises() {
        jdbc.update("INSERT INTO exercise (name_en, default_unit) VALUES ('ZZ Manual Unimport', 'kg')");

        assertThat(importService.unimport())
                .as("没导入过的库上调 unimport() 应当返回 0、不报错")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE origin = 'import' AND action = 'delete'",
                Integer.class))
                .as("什么都没删时不该留下一条「删了 0 个」的日志")
                .isZero();

        importService.importAll();
        assertThat(importService.unimport()).isEqualTo(876);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise WHERE name_en = 'ZZ Manual Unimport'", Integer.class))
                .as("手工动作一条都不能少")
                .isEqualTo(1);
    }

    @Test
    void re_import_after_unimport_restores_the_full_dataset() {
        importService.importAll();
        importService.unimport();

        ExerciseImportService.Result again = importService.importAll();

        assertThat(again.inserted()).as("撤销是可逆的").isEqualTo(876);
        assertThat(importedExercises()).isEqualTo(876);
        assertThat(importedLinks()).isEqualTo(2741);
    }

    @Test
    void a_clean_database_should_install_the_library_on_startup() {
        assertThat(importService.shouldInstallOnStartup()).isTrue();
        assertThat(importService.hasImportedExercises()).isFalse();
        assertThat(importService.wasEverUnimported()).isFalse();
    }

    @Test
    void an_installed_library_is_not_reinstalled_and_a_removed_one_stays_removed() {
        importService.importAll();
        assertThat(importService.shouldInstallOnStartup())
                .as("已经装过之后，启动不该再装一遍").isFalse();

        importService.unimport();
        assertThat(importService.wasEverUnimported()).isTrue();
        assertThat(importService.shouldInstallOnStartup())
                .as("撤销过之后，重启不该把库装回来——否则「撤销」就没意义了").isFalse();

        // 但显式装回来仍然可以（这正是 POST /api/exercises/import 干的事）
        assertThat(importService.importAll().inserted()).isEqualTo(876);
        assertThat(importService.shouldInstallOnStartup()).isFalse();
    }

    // ------------------------------------------------------------------
    // 工具：按 source_id 收口的计数
    // ------------------------------------------------------------------

    private int importedExercises() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise WHERE source_id LIKE 'free-exercise-db:%'",
                Integer.class);
    }

    private int importedLinks() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_muscle em " +
                "JOIN exercise e ON e.id = em.exercise_id " +
                "WHERE e.source_id LIKE 'free-exercise-db:%'",
                Integer.class);
    }

    private int manualExercises() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise WHERE source_id IS NULL", Integer.class);
    }

    private void removeImported() {
        // 先删动作（ON DELETE CASCADE 会带走它的 join 行），再删导入日志。
        jdbc.update("DELETE FROM exercise WHERE source_id LIKE 'free-exercise-db:%'");
        jdbc.update("DELETE FROM change_log WHERE origin = 'import'");
    }
}
