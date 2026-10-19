package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「把中文做法映射写进 instructions_zh」这件纯机械的事。
 *
 * <p>不调模型——译文是喂进来的。规则错了会**静默写坏数据**：段与段之间粘成一个长句、
 * 覆盖掉已经写好的译文、给没有英文原文的动作编一段中文。所以每条规则都有测试。
 *
 * <p>@Transactional：每条测试跑完回滚，不给共享库留行（{@code name_en} 有唯一索引）。
 */
@SpringBootTest
@Transactional
class ExerciseInstructionsZhServiceTest {

    @Autowired
    ExerciseInstructionsZhService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void writes_the_paragraphs_joined_with_newlines() {
        long a = insertImported("ZZ Zh Instr A", "English step one. English step two.", null);
        long b = insertImported("ZZ Zh Instr B", "English step one.", null);

        Map<String, List<String>> mapping = new LinkedHashMap<>();
        mapping.put("ZZ Zh Instr A", List.of("第一段。", "第二段。"));
        mapping.put("ZZ Zh Instr B", List.of("只有一段。"));

        ExerciseInstructionsZhService.Result r = service.apply(mapping);

        assertThat(r.updated()).isEqualTo(2);
        assertThat(r.skippedAlready()).isZero();
        assertThat(r.skippedEmptySource()).isZero();
        assertThat(r.skippedNotImported()).isZero();
        assertThat(zhOf(a)).as("段与段之间用一个换行连接").isEqualTo("第一段。\n第二段。");
        assertThat(zhOf(b)).isEqualTo("只有一段。");
    }

    @Test
    void keeps_a_newline_that_already_lives_inside_a_paragraph() {
        // 真实数据里有这么一条：Straight-Arm Dumbbell Pullover 的第 3 段内部自带一个换行。
        // 拼接只能**追加**段间换行，不能把段内换行「修正」掉——否则那一段会少一行。
        long id = insertImported("ZZ Zh Instr Newline", "English source.", null);

        ExerciseInstructionsZhService.Result r = service.apply(
                Map.of("ZZ Zh Instr Newline", List.of("a", "b\nc")));

        assertThat(r.updated()).isEqualTo(1);
        assertThat(zhOf(id)).as("段内换行保留，段间再加一个换行").isEqualTo("a\nb\nc");
        assertThat(zhOf(id).lines()).as("拼接后正好三行").hasSize(3);
    }

    @Test
    void a_second_apply_skips_rows_it_already_wrote_instead_of_overwriting_them() {
        long a = insertImported("ZZ Zh Instr Idem A", "English source a.", null);
        long b = insertImported("ZZ Zh Instr Idem B", "English source b.", null);

        Map<String, List<String>> firstMapping = new LinkedHashMap<>();
        firstMapping.put("ZZ Zh Instr Idem A", List.of("第一次写的 A。"));
        firstMapping.put("ZZ Zh Instr Idem B", List.of("第一次写的 B。"));

        ExerciseInstructionsZhService.Result first = service.apply(firstMapping);
        assertThat(first.updated()).isEqualTo(2);
        assertThat(first.skippedAlready()).isZero();

        // 同一份映射再跑一次：全部走「已有跳过」。
        ExerciseInstructionsZhService.Result second = service.apply(firstMapping);
        assertThat(second.updated()).isZero();
        assertThat(second.skippedAlready()).isEqualTo(2);

        // 第三次故意用**不同的译文**（相同的键）：如果实现没跳过已有行，这里就会把内容改掉。
        // 只重跑同一份映射的话，「覆盖」与「跳过」写进去的字节完全一样，测试看不出来。
        Map<String, List<String>> changed = new LinkedHashMap<>();
        changed.put("ZZ Zh Instr Idem A", List.of("不该覆盖 A。"));
        changed.put("ZZ Zh Instr Idem B", List.of("不该覆盖 B。"));

        ExerciseInstructionsZhService.Result third = service.apply(changed);
        assertThat(third.updated()).isZero();
        assertThat(third.skippedAlready()).isEqualTo(2);

        assertThat(zhOf(a)).as("库里保留第一次写入的内容").isEqualTo("第一次写的 A。");
        assertThat(zhOf(b)).as("库里保留第一次写入的内容").isEqualTo("第一次写的 B。");
    }

    @Test
    void never_writes_chinese_when_the_english_source_is_missing() {
        // 真实数据里那 5 条（Iron Cross 等）的英文原文在导入时写成了 NULL；
        // 这里同时钉住空串——两种「没有原文」都不该凭空编一段中文。
        long nullSource = insertImported("ZZ Zh Instr Null Source", null, null);
        long emptySource = insertImported("ZZ Zh Instr Empty Source", "", null);
        long writable = insertImported("ZZ Zh Instr Has Source", "English source.", null);

        ExerciseInstructionsZhService.Result r = service.apply(
                Map.of("ZZ Zh Instr Has Source", List.of("有原文才写。")));

        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.skippedEmptySource()).as("NULL 与空串都算「没有原文」").isEqualTo(2);
        assertThat(zhOf(nullSource)).as("没有原文就没有可翻的东西").isNull();
        assertThat(zhOf(emptySource)).as("没有原文就没有可翻的东西").isNull();
        assertThat(zhOf(writable)).isEqualTo("有原文才写。");
    }

    @Test
    void an_empty_source_entry_in_the_mapping_is_counted_once_and_not_written() {
        long id = insertImported("ZZ Zh Instr Empty Mapped", null, null);

        ExerciseInstructionsZhService.Result r = service.apply(
                Map.of("ZZ Zh Instr Empty Mapped", List.of("不该写的中文。")));

        assertThat(r.updated()).isZero();
        assertThat(r.skippedEmptySource()).as("映射里出现也只数一次").isEqualTo(1);
        assertThat(zhOf(id)).isNull();
    }

    @Test
    void a_mapping_name_that_is_not_in_the_library_is_counted_not_thrown() {
        // 与 ExerciseNameZhService 的 skippedNotImported 同一个处理：
        // 映射比库新（或库被裁剪过）时，缺的那条计数即可，不该让启动失败。
        ExerciseInstructionsZhService.Result r = service.apply(
                Map.of("ZZ Zh Instr Not Imported", List.of("库里没有这条。")));

        assertThat(r.updated()).isZero();
        assertThat(r.skippedNotImported()).isEqualTo(1);
        assertThat(r.skippedEmptySource()).isZero();
    }

    @Test
    void does_not_touch_manual_exercises() {
        long manual = insertManual("ZZ Zh Instr Manual");

        ExerciseInstructionsZhService.Result r = service.apply(
                Map.of("ZZ Zh Instr Manual", List.of("手工做法。")));

        assertThat(r.updated()).isZero();
        assertThat(r.skippedNotImported()).isEqualTo(1);
        assertThat(zhOf(manual)).as("手工动作的译文不该被导入的映射写入").isNull();
    }

    @Test
    void result_counts_every_bucket_separately() {
        insertImported("ZZ Zh Instr Count Write 1", "English source.", null);
        insertImported("ZZ Zh Instr Count Write 2", "English source.", null);
        insertImported("ZZ Zh Instr Count Already", "English source.", "已经写好的中文。");
        insertImported("ZZ Zh Instr Count Empty", null, null);

        Map<String, List<String>> mapping = new LinkedHashMap<>();
        mapping.put("ZZ Zh Instr Count Write 1", List.of("写入一。"));
        mapping.put("ZZ Zh Instr Count Write 2", List.of("写入二。"));
        mapping.put("ZZ Zh Instr Count Already", List.of("不该覆盖。"));
        mapping.put("ZZ Zh Instr Count Absent", List.of("库里没有。"));

        ExerciseInstructionsZhService.Result r = service.apply(mapping);

        assertThat(r.updated()).isEqualTo(2);
        assertThat(r.skippedAlready()).isEqualTo(1);
        assertThat(r.skippedEmptySource()).isEqualTo(1);
        assertThat(r.skippedNotImported()).isEqualTo(1);
    }

    @Test
    void reports_whether_there_is_still_something_to_write() {
        // 启动钩子靠它决定「要不要读那份 473 KB 的映射」。
        long id = insertImported("ZZ Zh Instr Pending", "English source.", null);
        assertThat(service.hasUnwrittenImportedInstructions()).isTrue();

        service.apply(Map.of("ZZ Zh Instr Pending", List.of("补上。")));

        assertThat(service.hasUnwrittenImportedInstructions()).isFalse();
        assertThat(zhOf(id)).isEqualTo("补上。");
    }

    // ------------------------------------------------------------------

    private long insertImported(String nameEn, String instructions, String instructionsZh) {
        String sourceId = "free-exercise-db:zz-instr-" + nameEn.toLowerCase().replace(' ', '-');
        jdbc.update("INSERT INTO exercise(name_en, default_unit, source_id, instructions, instructions_zh) "
                + "VALUES (?, 'kg', ?, ?, ?)", nameEn, sourceId, instructions, instructionsZh);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long insertManual(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private String zhOf(long id) {
        return jdbc.queryForObject(
                "SELECT instructions_zh FROM exercise WHERE id = ?", String.class, id);
    }
}
