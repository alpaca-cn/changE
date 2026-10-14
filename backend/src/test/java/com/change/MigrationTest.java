package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移机制的两条哨兵。
 *
 * 为什么第一条必须断言「结构」而不是「有一行数据」：把 V1 的内容清空、只留注释，
 * Flyway 照样会记一行 success=1——于是「数一行数据」式的断言仍然全绿，而 V1 其实
 * 什么也没做。对一个用来证明「迁移机制可靠」的测试来说，这恰好是反例。
 * 现在改成断言 V1 真正建出来的列与 DDL：空迁移、CREATE 没执行，都会立刻变红。
 */
@SpringBootTest
class MigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void baseline_migration_created_probe_table() {
        // 断言 V1 真正建出来的结构，而不是「某处有一行数据」：
        // 空迁移 / CREATE 没执行，都会让下面任一条失败。
        List<String> cols = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('schema_probe')", String.class);
        assertThat(cols).containsExactly("id", "note", "created_at");

        String ddl = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='schema_probe'",
                String.class);
        assertThat(ddl).contains("AUTOINCREMENT").contains("NOT NULL");
    }

    @Test
    void flyway_history_has_v1() {
        String desc = jdbc.queryForObject(
                "SELECT description FROM flyway_schema_history WHERE version = '1'", String.class);
        assertThat(desc).isEqualTo("baseline");
    }

    @Test
    void connection_pragmas_are_effective() {
        // 这两个 PRAGMA 靠 JDBC URL 参数开启（迁移脚本里开不了：SQLite 在事务内
        // 拒绝切 WAL）。它们一旦写错大小写，驱动会当成 PRAGMA 名执行并直接
        // SQLITE_CANTOPEN，应用根本起不来——所以这条断言是必要的哨兵。
        assertThat(queryPragma("foreign_keys")).isEqualTo("1");
        assertThat(queryPragma("journal_mode")).isEqualTo("wal");
    }

    private String queryPragma(String name) {
        return jdbc.queryForObject("PRAGMA " + name, String.class);
    }
}
