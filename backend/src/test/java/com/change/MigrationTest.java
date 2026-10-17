package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移机制的哨兵。
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
 *
 * 本轮（Task 3 审查后）补了三条：
 * - v1_script_still_produces_the_probe_table_it_is_known_for：把 V1 的脚本文本对一条
 *   **内存**库执行一遍，再断言它真的建出那张表、列名与 AUTOINCREMENT / NOT NULL 都在。
 *   这是对上面那条子串断言的收窄：子串断言问不出「脚本还能不能跑」，也问不出列级细节。
 * - published_migrations_are_byte_frozen：把已发布版本的 checksum 钉死。
 * - （原有的 PRAGMA 哨兵不变。）
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

    /**
     * 把 V1 的脚本文本对一条**内存** SQLite 连接执行一遍，然后断言列级细节。
     *
     * 为什么需要它：上面那条子串断言只看「文里有没有这几段字」，看不出脚本还能不能执行，
     * 也看不出列类型 / 顺序 / 非空标记。审查正是据此指出它「丢掉了列级精确性」。
     *
     * 三个必须注意的点（都是实测踩出来的）：
     * 1. `jdbc:sqlite::memory:` 是**一连接一库**：交给连接池（或每条语句各取一次连接）
     *    就会拿到一个空库，`no such table: schema_probe`。所以这里手动
     *    DriverManager.getConnection 并且全程只有这一条 Connection。
     * 2. `PRAGMA table_info` 是表函数（一行多列），queryForList(..., String.class) 会对它抛
     *    IncorrectResultSetColumnCountException；写成 `SELECT name FROM pragma_table_info(...)`。
     * 3. 它验证的是「脚本文本能跑出什么」，**不能替代** Flyway 那条断言——它绕过了
     *    flyway_schema_history、绕过了真实的迁移执行路径，也不检查 V1 是否真的被 Flyway
     *    应用过。两条断言守的是不同的东西，删哪一条都会露出一个洞。
     */
    @Test
    void v1_script_still_produces_the_probe_table_it_is_known_for() throws Exception {
        // 手动开连接、手动关：内存库随连接存在，不能交给连接池。
        try (Connection memory = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            ScriptUtils.executeSqlScript(memory, new ClassPathResource("db/migration/V1__baseline.sql"));

            // 包一层 SingleConnectionDataSource，让下面几条查询都走这一条 Connection
            // （内存库是一连接一库，换一条连接就换一个空库）。
            JdbcTemplate mem = new JdbcTemplate(new SingleConnectionDataSource(memory, true));

            // 1) 列清单：少一列、改名、顺序变了都会红。
            assertThat(mem.queryForList(
                    "SELECT name FROM pragma_table_info('schema_probe')", String.class))
                    .containsExactly("id", "note", "created_at");

            // 2) DDL 原文里的 AUTOINCREMENT 与列级 NOT NULL。
            //    DDL 是按列对齐存的（`note       TEXT NOT NULL`），所以断言前先把空白折叠成
            //    单空格：这样断言既不怕迁移被重新排版，又能真正问出「这一列上有没有 NOT NULL」。
            String ddl = collapseWhitespace(mem.queryForObject(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='schema_probe'",
                    String.class));
            assertThat(ddl).contains("INTEGER PRIMARY KEY AUTOINCREMENT");
            assertThat(ddl).contains("note TEXT NOT NULL,");
            assertThat(ddl).contains("created_at TEXT NOT NULL");

            // 3) 脚本里的那行数据也真的进去了（空脚本 / INSERT 被删掉会红）。
            assertThat(mem.queryForObject("SELECT note FROM schema_probe", String.class))
                    .isEqualTo("baseline");
        }
    }

    /**
     * 「已发布迁移不可变」这条纪律，此前只剩文档在守。
     *
     * 与 Flyway 自己的校验**不重复**：Flyway 只在「已经跑过该版本的库」上比 checksum，
     * 而本地删掉 target 之后每次跑测试都是全新的库，checksum 校验根本不会被触发——
     * 于是给 V1 加一行注释、或者把 V2 的某个 CHECK 删掉，套件照样全绿。
     * 这里把 checksum 钉死在测试里，任何对 V1–V4 的编辑（哪怕只加一行注释）都会让它变红。
     *
     * checksum 是 Flyway 对迁移文件内容算出的 CRC32，必须从**实跑后的库**里读出来填，
     * 不能猜。当前值对应 V1–V4 发布时的字节内容：
     *   1:baseline / 2:training / 3:nutrition / 4:auxiliary。
     *
     * V2 的 checksum 在「删除冗余索引 ix_workout_set_session_exercise」时更新过一次
     * （-962992177 → 1428719958）。这正是这条断言存在意义的现场：V2 尚未分发，删除是
     * 允许的，而**改动必须在这里显式确认一次**，不能悄悄发生。V1 / V3 / V4 的 checksum
     * 没有变，说明那次改动只落在 V2 一个文件上。
     *
     * 版本号写死成 containsExactly（不是「前四个必须是」）：将来新增 V5 时这条会红，
     * 那是**有意的**——新迁移一旦发布就该被一起冻结，加进来的同时也要确认它已发布。
     *
     * 计划 2 的 Task 1 新增 V5 / V6，这条如约变红（红得对：两份新迁移必须被一起冻结），
     * 于是把它们的 checksum 一并钉进来：
     *   5:exercise muscle surrogate id / 6:change log origin import。
     * V1–V4 的四个值一个都没变，说明这一轮只落了两个新文件，没有回头改已发布的迁移。
     */
    @Test
    void published_migrations_are_byte_frozen() {
        assertThat(jdbc.queryForList(
                "SELECT version || ':' || checksum FROM flyway_schema_history ORDER BY installed_rank",
                String.class))
                .containsExactly(
                        "1:1904735359", "2:1428719958", "3:1207314981", "4:1460974919",
                        "5:-468526944", "6:1910071904");
    }

    private String readMigration(String fileName) {
        try (var in = new ClassPathResource("db/migration/" + fileName).getInputStream()) {
            // 统一换行符：断言只关心内容，不该因为 checkout 的行尾设置而变红。
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception e) {
            throw new IllegalStateException("读不到迁移脚本: " + fileName, e);
        }
    }

    /** 把空白折叠成单空格：DDL 是按列对齐存的，断言片段不该因为排版而失配。 */
    private static String collapseWhitespace(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
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
