package com.change.repo;

import com.change.domain.Exercise;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExerciseRepo} 两个查询方法的守卫。
 *
 * <p>为什么此前一直没人发现 bug：这两个方法**零测试**。搜索方法漏了 {@code archived}
 * 过滤——而 {@code findByArchivedFalse} 的注释白纸黑字写着「归档的动作不该出现在候选里」。
 * 归档的语义是「不再出现在候选里，但仍要能被旧记录解析出来」，搜索是「候选」的一种，
 * 所以它必须排除归档的。
 *
 * <p>@Transactional：每条测试跑完回滚，测试之间不互相污染，也不会给共享的磁盘库留下行
 * （{@code exercise.name_en} 上有唯一索引，留下重名会连累别的测试）。
 * 断言一律按 id 收口（contains / doesNotContain），不假设库里只有本测试建的行。
 */
@SpringBootTest
@Transactional
class ExerciseRepoTest {

    @Autowired
    ExerciseRepo exercises;

    @Test
    void findByArchivedFalse_returns_active_rows_and_not_archived_ones() {
        long active = create("ZZ Repo Active", "活跃动作", false);
        long archived = create("ZZ Repo Archived", "归档动作", true);

        List<Long> ids = exercises.findByArchivedFalse().stream().map(e -> e.id).toList();

        assertThat(ids).contains(active);
        assertThat(ids).doesNotContain(archived);
    }

    @Test
    void search_matches_chinese_and_english_case_insensitively() {
        long byZh = create("ZZ Repo Search Squat", "深蹲搜索标记", false);
        long byEn = create("ZZ Repo Search Bench", "卧推搜索标记", false);

        assertThat(search("深蹲搜索标记", "zzz-no-such-token"))
                .as("中文命中").contains(byZh);
        assertThat(search("zzz-no-such-token", "search bench"))
                .as("英文命中且大小写不敏感").contains(byEn);
    }

    @Test
    void search_excludes_archived_rows() {
        long archived = create("ZZ Repo Archived Token", "ZZ归档搜索标记", true);

        // 这一条是 Task 6 要修的真 bug：搜索方法原本没有 archived 过滤，
        // 归档的动作会从搜索里冒出来——本条现在会红。
        assertThat(search("ZZ归档搜索标记", "zzz-no-such-token"))
                .as("搜索必须排除已归档的动作")
                .doesNotContain(archived);
    }

    @Test
    void search_does_not_error_when_the_chinese_name_is_null() {
        long englishOnly = create("ZZ Repo Null Zh Token", null, false);

        // nameZh 为 null 的行，nameZh LIKE 的谓词结果是 NULL（既非真也非假），
        // 但英文那一路命中它，所以查询既不能报错，也要把它带出来。
        assertThat(search("zzz-no-such-token", "null zh token"))
                .contains(englishOnly);
    }

    @Test
    void an_empty_query_matches_every_non_archived_row() {
        // 明确钉住空串的行为（计划要求二选一）：Containing("") 生成 LIKE '%%'，
        // 于是空串匹配**所有未归档**的行；归档的仍被排除。
        long active = create("ZZ Repo Empty Token", "空串标记", false);
        long archived = create("ZZ Repo Empty Archived", "空串归档标记", true);

        List<Long> ids = search("", "");

        assertThat(ids).as("空串匹配所有未归档的行").contains(active);
        assertThat(ids).as("即使空串也不能把归档的带出来").doesNotContain(archived);
    }

    private long create(String nameEn, String nameZh, boolean archived) {
        Exercise e = new Exercise();
        e.nameEn = nameEn;
        e.nameZh = nameZh;
        e.archived = archived;
        return exercises.saveAndFlush(e).id;
    }

    private List<Long> search(String zh, String en) {
        return exercises
                .findByArchivedFalseAndNameZhContainingOrArchivedFalseAndNameEnContainingIgnoreCase(zh, en)
                .stream().map(e -> e.id).toList();
    }
}
