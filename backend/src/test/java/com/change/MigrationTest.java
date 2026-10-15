package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移机制的两条哨兵。
 *
 * 为什么第一条断言「结构」而不是「有一行数据」：把 V1 的内容清空、只留注释，
 * Flyway 照样会记一行 success=1——于是「数一行数据」式的断言仍然全绿，而 V1 其实
 * 什么也没做。对一个用来证明「迁移机制可靠」的测试来说，这恰好是反例。
 *
 * 为什么从 Task 3 起这条改成读迁移脚本本身：V2 的第一行就是 DROP TABLE schema_probe，
 * 迁移在上下文启动时就跑完，**任何**测试方法都看不到这张表了——继续断言「表在」
 * 无论怎么写都必然红，而断言「表不在」只是把 SchemaTest 那条抄一遍。
 * 想守住的其实还是同一件事：V1 里那句 CREATE 不能消失或被改坏。这件事只有读
 * 脚本原文才问得出来，顺带还补上了原断言查不到的盲区——改 V1 只让「已经跑过 V1 的库」
 * 报 checksum 失配，**全新的库照样跑得通**，所以光靠运行时断言拦不住这类改动。
 */
@SpringBootTest
class MigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void baseline_migration_still_defines_the_probe_table_it_is_known_for() {
        // 读资源原文而不是数据库：schema_probe 已被 V2 删掉（见 SchemaTest 的对应断言）。
        String v1 = readMigration("V1__baseline.sql");

        // 空迁移、CREATE 被删掉 / 改名 / 拆成别的表，都会在这里变红。
        assertThat(v1).contains("CREATE TABLE");
        assertThat(v1).contains("schema_probe");
        assertThat(v1).contains("INTEGER PRIMARY KEY AUTOINCREMENT");
        assertThat(v1).contains("NOT NULL");

        // 反过来说：V1 真的建了这张表，才轮得到 V2 去 DROP 它——V2 里那句
        // DROP TABLE schema_probe（无 IF EXISTS）在空 schema 上必然报
        // "no such table: schema_probe"，整个上下文起不来。这条链子本身就是
        // V1 执行过的证据。
    }

    private String readMigration(String fileName) {
        try (var in = new ClassPathResource("db/migration/" + fileName).getInputStream()) {
            // 统一换行符：断言只关心内容，不该因为 checkout 的行尾设置而变红。
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception e) {
            throw new IllegalStateException("读不到迁移脚本: " + fileName, e);
        }
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
