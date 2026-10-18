package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「把英文名映射写进 name_zh」这件纯机械的事。
 *
 * <p>不调模型——映射是喂进来的。规则错了会**静默改错数据**（覆盖用户手工名、把两个动作写成
 * 同名、把已经写好的覆盖掉），所以每条规则都有测试。
 *
 * <p>@Transactional：每条测试跑完回滚，不给共享库留行（{@code name_en} 有唯一索引）。
 */
@SpringBootTest
@Transactional
class ExerciseNameZhServiceTest {

    @Autowired
    ExerciseNameZhService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void writes_the_mapping_onto_imported_exercises() {
        long a = insertImported("ZZ Zh Name A", null);
        long b = insertImported("ZZ Zh Name B", null);

        ExerciseNameZhService.Result r = service.apply(Map.of(
                "ZZ Zh Name A", "甲动作", "ZZ Zh Name B", "乙动作"));

        assertThat(r.updated()).isEqualTo(2);
        assertThat(zhOf(a)).isEqualTo("甲动作");
        assertThat(zhOf(b)).isEqualTo("乙动作");
    }

    @Test
    void does_not_touch_manual_exercises() {
        long manual = insertManual("ZZ Zh Manual One");

        ExerciseNameZhService.Result r = service.apply(Map.of("ZZ Zh Manual One", "手工动作"));

        assertThat(r.updated()).isZero();
        assertThat(r.skippedNotImported()).isEqualTo(1);
        assertThat(zhOf(manual)).as("手工动作的中文名不该被导入的名字覆盖").isNull();
    }

    @Test
    void does_not_overwrite_an_existing_chinese_name_and_is_idempotent() {
        long id = insertImported("ZZ Zh Already Named", "已有中文名");

        ExerciseNameZhService.Result first = service.apply(Map.of("ZZ Zh Already Named", "想覆盖的名字"));
        ExerciseNameZhService.Result second = service.apply(Map.of("ZZ Zh Already Named", "又一个名字"));

        assertThat(first.skippedAlreadyNamed()).isEqualTo(1);
        assertThat(first.updated()).isZero();
        assertThat(second.skippedAlreadyNamed()).isEqualTo(1);
        assertThat(zhOf(id)).as("已有中文名不能被覆盖").isEqualTo("已有中文名");
    }

    @Test
    void resumes_from_a_partial_run_processing_only_the_null_rows() {
        long done = insertImported("ZZ Zh Partial Done", "已经写好");
        long todo = insertImported("ZZ Zh Partial Todo", null);

        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("ZZ Zh Partial Done", "不该动");
        mapping.put("ZZ Zh Partial Todo", "这次补上");

        ExerciseNameZhService.Result r = service.apply(mapping);

        assertThat(r.skippedAlreadyNamed()).as("跑过的那条应当被跳过").isEqualTo(1);
        assertThat(r.updated()).as("只补没写的那些").isEqualTo(1);
        assertThat(zhOf(done)).isEqualTo("已经写好");
        assertThat(zhOf(todo)).isEqualTo("这次补上");
    }

    @Test
    void refuses_to_write_a_chinese_name_that_is_already_taken() {
        // 情形一：库里已有的中文名被再次使用
        long existing = insertImported("ZZ Zh Taken Existing", "占用的名字");
        long fresh = insertImported("ZZ Zh Taken Fresh", null);

        ExerciseNameZhService.Result r = service.apply(Map.of("ZZ Zh Taken Fresh", "占用的名字"));

        assertThat(r.skippedDuplicate()).isEqualTo(1);
        assertThat(r.updated()).isZero();
        assertThat(zhOf(fresh)).as("重名的中文名不该被写入").isNull();
        assertThat(zhOf(existing)).isEqualTo("占用的名字");
    }

    @Test
    void refuses_when_the_mapping_itself_assigns_one_name_twice() {
        long one = insertImported("ZZ Zh Collide One", null);
        long two = insertImported("ZZ Zh Collide Two", null);

        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("ZZ Zh Collide One", "撞车的名字");
        mapping.put("ZZ Zh Collide Two", "撞车的名字");

        ExerciseNameZhService.Result r = service.apply(mapping);

        assertThat(r.updated()).as("只有一个能拿到这个名字").isEqualTo(1);
        assertThat(r.skippedDuplicate()).isEqualTo(1);
        assertThat("撞车的名字".equals(zhOf(one)) ^ "撞车的名字".equals(zhOf(two)))
                .as("恰好一条被写上，另一条保持 null")
                .isTrue();
    }

    // ------------------------------------------------------------------

    private long insertImported(String nameEn, String nameZh) {
        String sourceId = "free-exercise-db:zz-" + nameEn.toLowerCase().replace(' ', '-');
        jdbc.update("INSERT INTO exercise(name_en, name_zh, default_unit, source_id) "
                + "VALUES (?, ?, 'kg', ?)", nameEn, nameZh, sourceId);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long insertManual(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private String zhOf(long id) {
        return jdbc.queryForObject("SELECT name_zh FROM exercise WHERE id = ?", String.class, id);
    }
}
