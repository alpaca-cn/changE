package com.change.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code undo} 的**事务**行为验证——单独一个类，因为这里必须跑在**没有测试事务**的上下文里。
 *
 * <p>为什么不能并进 {@link ChangeLogServiceTest}：那个类上有 {@code @Transactional}，
 * 里面的测试全跑在同一个测试事务里，测试自己包了一层事务，于是
 * 「本方法有没有 {@code @Transactional}」这件事**观察不到**——把注解整个删掉，
 * 那个类依然全绿（实测：本类出现之前，12 条全绿）。这一整个类就是为这条缝存在的。
 *
 * <p>这个类**不能**加 {@code @Transactional}：加了就回到上面那个盲区。
 *
 * <p>为什么用独立的库文件（{@code @TestPropertySource} 覆盖 datasource url）：
 * 这个类要往库里建一个**故意让第 3 步失败**的触发器，而触发器是库级对象。
 * 若与别的测试类共用 {@code target/data/change.db}，那个触发器会跟着库留在磁盘上，
 * 把随后任何一条 {@code undo} 测试打红（JUnit 的类间顺序是确定的而不是显式的，
 * 这种「靠顺序侥幸通过」的测试不能留）。独立库文件把这个类的破坏面限制在它自己身上。
 *
 * <p>{@code @TestPropertySource} 的属性在上下文创建时解析，{@code ${user.dir}} 是 JVM
 * 系统属性，此时已可用（已实测）。库文件放在 {@code target/data} 下是**故意的**：
 * 那个目录由 {@code DataDirectoryInitializer} 建出来，而 SQLite **不会**创建不存在的父目录，
 * 指到一个新目录上会以 {@code [SQLITE_CANTOPEN] Unable to open the database file} 起不来
 * （第一版指向 {@code ${java.io.tmpdir}/changelog-undo-tx/} 就是这个报错，已实测）。
 * 文件名与主库 {@code change.db} 不同，所以两个上下文互不干扰。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/changelog-undo-tx.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class ChangeLogServiceUndoTransactionTest {

    @Autowired ChangeLogService logs;
    @Autowired JdbcTemplate jdbc;

    private long insertFood(String name, double kcal) {
        jdbc.update("INSERT INTO food(name_zh, source, kcal_per100) VALUES (?,?,?)",
                name, "manual", kcal);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /**
     * 每个测试方法开始前把触发器清掉。
     *
     * <p>这不是洁癖：上一轮若在 {@link #abortTheFinalUndoneUpdate()} 之后失败（或整轮被杀在
     * 测试中间），触发器会**留在磁盘上的库文件里**，于是这一轮的正证（undo 应当真的删行）
     * 会被它挡住，报成 expected: 0 but was: 1 —— 一条指向错误原因的假红。第一版就踩到了。
     */
    @BeforeEach
    void dropAnyLeftoverTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_abort_undo");
    }

    /**
     * 让 {@code undo} 的第 3 步（{@code UPDATE change_log SET undone = 1}）必然失败。
     * 这一步在实现里是**最后**一条语句，所以它能精确地把「两处写入不在一个事务里」
     * 这件事暴露出来：第 1 步的 DELETE 已经执行完了，就看它会不会被回滚。
     */
    private void abortTheFinalUndoneUpdate() {
        // 先 DROP IF EXISTS：本类用的是磁盘上的独立库文件，同一个工作区里重跑
        // （mvn test 不删 target）时触发器还在，不先删就会撞 "trigger already exists"。
        jdbc.execute("DROP TRIGGER IF EXISTS test_abort_undo");
        jdbc.execute("""
                CREATE TRIGGER test_abort_undo BEFORE UPDATE OF undone ON change_log
                WHEN NEW.undone = 1
                BEGIN
                    SELECT RAISE(ABORT, '测试触发器：禁止把 undone 置为 1');
                END
                """);
    }

    /** 本类自己的测试数据，按名字清掉上一次运行的残留（磁盘库文件不会被测试事务收尾）。 */
    private void cleanUpMyFoodRows() {
        for (String name : List.of("事务测试食物A", "事务测试食物B", "事务测试食物K",
                "事务测试食物L")) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM food WHERE name_zh = ?", Long.class, name);
            for (Long victim : ids) {
                jdbc.update("DELETE FROM meal_item WHERE food_id = ?", victim);
                jdbc.update("DELETE FROM change_log WHERE target_table = 'food' AND target_id = ?", victim);
                jdbc.update("DELETE FROM food WHERE id = ?", victim);
            }
        }
        jdbc.update("DELETE FROM meal WHERE note = '回退外键测试用的餐'");
    }

    @Test
    void primary_key_aliases_are_refused_on_the_real_undo_path() {
        // 与 ChangeLogServiceTest 里那条同题，但这里的重点不同：**这条跑在没有测试事务的
        // 上下文里**，走的是「先 recordUpdate 写日志 → 再 undo」的真实路径，落地在磁盘上，
        // 不接受「测试事务掩盖了它」这种解释。
        // rowid / _rowid_ / oid 是 SQLite 给主键的别名（INTEGER PRIMARY KEY 表上就是 id），
        // 它们能通过任何标识符正则，而 SET rowid = ? 是合法 SQL。
        cleanUpMyFoodRows();
        long victimId = insertFood("事务测试食物L", 900);
        System.out.println("PROBE PK victim id = " + victimId);

        for (String alias : List.of("id", "rowid", "_rowid_", "oid")) {
            long logId = logs.recordUpdate("food", victimId,
                    Map.of(alias, 7L), Map.of("kcal_per100", 1.0), "agent", null);
            System.out.println("PROBE PK alias=" + alias + " before_json="
                    + jdbc.queryForObject("SELECT before_json FROM change_log WHERE id = ?",
                            String.class, logId));
            Throwable thrown = null;
            try {
                logs.undo(logId);
            } catch (RuntimeException e) {
                thrown = e;
            }
            System.out.println("PROBE PK alias=" + alias + " undo -> "
                    + (thrown == null ? "SUCCEEDED (!!)" : "THREW " + thrown.getClass().getSimpleName()
                            + ": " + thrown.getMessage()));
            System.out.println("PROBE PK alias=" + alias + " undone=" + jdbc.queryForObject(
                            "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)
                    + " victimStillAtaId=" + jdbc.queryForObject(
                            "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, victimId)
                    + " foodIds=" + jdbc.queryForList("SELECT id FROM food", Long.class));

            assertThat(thrown)
                    .as("主键别名 %s 必须被拒绝", alias)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage()).contains(alias);
            assertThat(jdbc.queryForObject(
                    "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, victimId)).isEqualTo(1);
        }
    }

    @Test
    void a_foreign_key_blocked_undo_reads_as_chinese_not_sqlite_nonsense() {
        // 第四节的验收：把 SQLITE_CONSTRAINT_FOREIGNKEY 翻译成能读懂的话，并**把实际文案打出来**。
        cleanUpMyFoodRows();
        long foodId = insertFood("事务测试食物K", 1100);
        long logId = logs.recordInsert("food", foodId, Map.of("name_zh", "事务测试食物K"),
                "agent", null);
        jdbc.update("INSERT INTO meal(meal_date, slot, note) VALUES (?,?,?)",
                "2026-10-16", "lunch", "回退外键测试用的餐");
        long mealId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO meal_item(meal_id, food_id, order_index, amount, grams) " +
                    "VALUES (?,?,?,?,?)", mealId, foodId, 0, 100.0, 100.0);

        Throwable thrown = null;
        try {
            logs.undo(logId);
        } catch (RuntimeException e) {
            thrown = e;
        }
        System.out.println("PROBE FK thrown = " + (thrown == null ? "<none>"
                : thrown.getClass().getName()));
        System.out.println("PROBE FK message = " + (thrown == null ? "<none>" : thrown.getMessage()));
        System.out.println("PROBE FK root    = " + (thrown == null || thrown.getCause() == null
                ? "<none>" : thrown.getCause().getMessage()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).contains("引用").contains("food.id = " + foodId);
        // 报错路径不能留下半截状态
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, foodId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)).isZero();
    }

    @Test
    void undo_writes_both_places_in_one_transaction() {
        // 正证 + 反证放在同一条测试里，避免依赖测试执行顺序：
        //   (1) 没有触发器时，撤销真的把行删掉——用一条 **insert** 日志（只有 insert 的回退
        //       是物理 DELETE；update 的回退只是把值写回去，行本来就不会消失）。
        //       第一版这里误用了 update 日志，于是「行还在」被当成失败，查了半天才发现是
        //       测试自己的正证选错了路径——记在这里免得下一个人重犯。
        //   (2) 第 3 步失败时，第 1 步的 DELETE 必须被回滚——目标行还在。
        // 没有 @Transactional 时（2）会红：DELETE 已经自动提交，行就回不来了。
        cleanUpMyFoodRows();

        // (1) 正证：insert 的回退是 DELETE，会真正提交（本类没有测试事务）
        long insertedId = insertFood("事务测试食物A", 600);
        long logId = logs.recordInsert("food", insertedId,
                Map.of("name_zh", "事务测试食物A"), "agent", null);

        logs.undo(logId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, insertedId))
                .as("没有触发器时，undo 应当真的把目标行删掉")
                .isZero();

        // (2) 反证：同样的路径，第 3 步被触发器挡住时必须整体回滚
        long victimId = insertFood("事务测试食物B", 700);
        long victimLogId = logs.recordInsert("food", victimId,
                Map.of("name_zh", "事务测试食物B"), "agent", null);
        abortTheFinalUndoneUpdate();

        Throwable failure = null;
        try {
            logs.undo(victimLogId);
        } catch (RuntimeException e) {
            failure = e;
        }
        System.out.println("PROBE TX step3 failure = " + (failure == null ? "<none>"
                : failure.getClass().getName() + ": "
                        + String.valueOf(failure.getMessage()).replace('\n', ' ')));

        assertThat(failure)
                .as("第 3 步被触发器挡住时，undo 必须抛异常（而不是静默成功）")
                .isNotNull();
        // 这条断言就是本类的全部意义：没有 @Transactional 时它是 0（DELETE 已提交），
        // 有事务时它是 1（DELETE 被回滚）。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, victimId))
                .as("第 3 步失败时，第 1 步的 DELETE 必须被回滚——目标行还在")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE id = ? AND undone = 0",
                Integer.class, victimLogId))
                .as("日志必须保持「没撤」，与数据一致")
                .isEqualTo(1);
    }

    @Test
    void undo_is_annotated_transactional_with_default_propagation() {
        // 补一条反射断言的意义有限（行为那条才是真防线），留它的理由有两条：
        //   1. 它是**快**的那条：行为测试要起一个独立库文件的上下文，反射不需要；
        //   2. 它把「传播必须是默认 REQUIRED」也钉住——@Transactional(REQUIRES_NEW)
        //      同样能让行为测试变绿，却会在**单连接连接池**上自己等自己（配置里
        //      maximum-pool-size=1，connection-timeout=3000 会先失败）。这条能挡住它。
        Method undo;
        try {
            undo = ChangeLogService.class.getMethod("undo", long.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("ChangeLogService 上找不到 undo(long)", e);
        }

        Transactional tx = undo.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("undo 上必须有 @Transactional：它要写两处（目标表 + change_log）")
                .isNotNull();
        assertThat(tx.propagation())
                .as("传播必须是默认的 REQUIRED——单连接连接池上 REQUIRES_NEW 会自己等自己")
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
    }

    @Test
    void the_trigger_really_does_block_marking_undone() {
        // 反证（本类自己的支撑测试）：证明那个触发器**确实**会挡住第 3 步。
        // 少了这一条，上面 (2) 的「第 3 步失败」就只是我的说法——测试可能因为别的原因
        // （比如 undo 提前抛了别的异常）而变绿。
        long id = insertFood("事务测试食物B", 800);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "事务测试食物B"), "agent", null);
        abortTheFinalUndoneUpdate();

        assertThatThrownBy(() -> jdbc.update("UPDATE change_log SET undone = 1 WHERE id = ?", logId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("测试触发器");
    }
}
