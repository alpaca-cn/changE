package com.change.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变更日志：让「智能体可写」这件事有安全带。
 *
 * 三条设计约束（设计文档决定 4）：
 *   1. 表名必须在白名单里 —— 工具集是白名单，不是让模型自由写 SQL
 *   2. 只记录 insert / update，不提供 delete —— 删除无法撤销，所以干脆不给
 *   3. 每条改动存下 before / after 的 JSON 快照，可一键回退
 *
 * ## 回退的语义（契约）
 *
 * - **update 的回退是按列写回**：只有 `before` 快照里出现的列会被恢复，
 *   未出现的列保持当前值。所以「回退一次部分修改」是合法且精确的——
 *   调用方只需把**这次改动实际动过的列**放进 `before`。
 * - 但这也意味着：如果调用方漏放了某一列，那一列**不会被恢复，且不会报错**。
 *   写 `before` 的地方应当由「改动前读到的整行」或「本次改动涉及的列」构造，
 *   不要凭记忆手写列名。
 * - **列名必须真实存在于目标表且不是主键**；SQLite 的 rowid / _rowid_ / oid
 *   是主键别名，会被拒绝（否则回退会搬走目标行的主键）。
 * - **撤销一个被外键 CASCADE 引用的父行会连带删掉子行**，而快照里不含子行。
 *   典型例子：撤销 `exercise` 的创建会级联删掉 `exercise_muscle` 的映射行，
 *   而快照两侧都不含映射——「撤销后再重做」会静默丢掉肌群标注。
 *   动作编辑功能落地时必须扩展快照（记下 muscle_map）或改为整组重建 join 行。
 */
@Service
public class ChangeLogService {

    /**
     * 允许被记录与回退的表。
     *
     * **准入条件：必须有单列整型主键 `id`。** 回退靠 `WHERE id = ?` 定位目标行，
     * 复合主键的表（如 exercise_muscle，主键是 (exercise_id, muscle_group_id)）
     * 定位不到，所以不进白名单——它的增删应通过所属的 exercise 记录来审计。
     *
     * 加表必须改这里，改这里必须同时加测试（见 ChangeLogServiceTest 的白名单自检）。
     */
    public static final Set<String> ALLOWED_TABLES = Set.of(
            "food", "food_portion", "meal", "meal_item",
            "exercise",
            "workout_session", "session_exercise", "workout_set",
            "body_metric", "photo");

    /**
     * 合法的变更来源。必须与 {@code V4__auxiliary.sql} 里
     * {@code change_log.origin} 的 {@code CHECK (origin IN ('user','agent'))} 一致。
     *
     * <p>用 {@link List} 而不是 {@code Set}：报错文案要把它拼进消息，而 {@code Set.of}
     * 的迭代顺序按 JVM 运行随机（同一个集合两次运行可能给出不同顺序），
     * 拼进消息就成了「同一处错误、两种文案」。包内 {@code VolumeCalculator.VALID_ROLES}
     * 出于同一理由也是 {@code List}。
     */
    public static final List<String> ALLOWED_ORIGINS = List.of("user", "agent");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public ChangeLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long recordInsert(String table, long targetId, Map<String, Object> after,
                             String origin, Long chatSessionId) {
        return write(table, targetId, "insert", null, after, origin, chatSessionId);
    }

    public long recordUpdate(String table, long targetId, Map<String, Object> before,
                             Map<String, Object> after, String origin, Long chatSessionId) {
        if (before == null || before.isEmpty()) {
            // 数据库侧有 CHECK (action <> 'update' OR before_json IS NOT NULL) 兜底，
            // 但在 Java 侧先拦能给出能读懂的报错，而不是一句 CHECK constraint failed。
            throw new IllegalArgumentException(
                    "update 记录必须带 before 快照，否则撤销时无从恢复");
        }
        return write(table, targetId, "update", before, after, origin, chatSessionId);
    }

    private long write(String table, long targetId, String action,
                       Map<String, Object> before, Map<String, Object> after,
                       String origin, Long chatSessionId) {
        requireAllowed(table);
        if (!ALLOWED_ORIGINS.contains(origin)) {
            // schema 侧同样有 CHECK (origin IN ('user','agent')) 兜底；
            // 在这里先拦的理由与上面 before 那条相同：把约束翻译成能读懂的话。
            throw new IllegalArgumentException(
                    "origin 必须是 " + String.join(" 或 ", ALLOWED_ORIGINS)
                            + "，实际为 " + origin);
        }
        if (after == null || after.isEmpty()) {
            // 不拦的话 toJson(null) 会写出**字面量字符串 "null"**：after_json 是 NOT NULL，
            // 于是库里留下一行「看起来合法、语义为空」的记录。
            throw new IllegalArgumentException("after 快照不能为空");
        }
        jdbc.update("INSERT INTO change_log(changed_at, origin, action, target_table, " +
                    "target_id, before_json, after_json, chat_session_id, undone) " +
                    "VALUES (?,?,?,?,?,?,?,?,0)",
                LocalDateTime.now().format(ISO), origin, action, table, targetId,
                before == null ? null : toJson(before), toJson(after), chatSessionId);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /**
     * 回退一条改动。insert 的回退是删除该行；update 的回退是写回 before 值。
     *
     * {@code @Transactional} 不是可选的：本方法要写两处（目标表 + change_log）。
     * 没有事务时第二步失败会留下「数据删了、日志说没撤」的矛盾状态，
     * 而审计日志与数据不一致正是这个类最不该有的失效。
     * 传播用默认（REQUIRED）——连接池只有 1 条连接，REQUIRES_NEW 会自己等自己。
     */
    @Transactional
    public void undo(long logId) {
        // 用 queryForList 而不是 queryForMap：查不到时前者给一个空列表，
        // 后者抛 Spring 的 EmptyResultDataAccessException——对调用方是一句读不懂的话。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, target_table, target_id, before_json, undone " +
                "FROM change_log WHERE id = ?", logId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到这条变更记录: " + logId);
        }
        Map<String, Object> row = rows.get(0);

        if (((Number) row.get("undone")).intValue() == 1) {
            throw new IllegalStateException("这条改动已经撤销过了: " + logId);
        }

        String table = (String) row.get("target_table");
        long targetId = ((Number) row.get("target_id")).longValue();
        String action = (String) row.get("action");
        // target_table 来自**数据库**（不是本次调用的参数，那个在写入时已经查过一遍），
        // 而 target_table 上没有 CHECK：一行被写坏的日志就能变成一条越权回退路径
        // （删掉/改写白名单之外的任何表）。所以这里必须重新校验。
        requireAllowed(table);

        int affected;
        try {
            if ("insert".equals(action)) {
                affected = jdbc.update("DELETE FROM " + table + " WHERE id = ?", targetId);
            } else {
                Map<String, Object> before = fromJson((String) row.get("before_json"));
                if (before == null || before.isEmpty()) {
                    throw new IllegalStateException("update 记录缺少 before 快照: " + logId);
                }
                // sets 与 values 必须来自**同一次** entrySet() 迭代。
                // 「keySet() 与 values() 顺序一致」是实现细节而不是 Map 的契约：
                // 两次独立迭代一旦错位，就会把 A 列的值写给 B 列——静默写错数据，且不报错。
                StringBuilder sets = new StringBuilder();
                List<Object> values = new ArrayList<>();
                Set<String> writable = writableColumns(table);
                for (Map.Entry<String, Object> e : before.entrySet()) {
                    String column = e.getKey();
                    if (!writable.contains(column)) {
                        throw new IllegalArgumentException(
                                "列名「" + column + "」不是 " + table + " 上可回退写入的列"
                                        + "（必须真实存在且不是主键——SQLite 的 rowid/_rowid_/oid 是主键别名）");
                    }
                    if (sets.length() > 0) {
                        sets.append(", ");
                    }
                    sets.append(column).append(" = ?");
                    values.add(e.getValue());
                }
                values.add(targetId);
                affected = jdbc.update(
                        "UPDATE " + table + " SET " + sets + " WHERE id = ?", values.toArray());
            }
        } catch (DataAccessException e) {
            // 回退一条 insert 是物理 DELETE，会被外键拦下：默认动作（NO ACTION）直接抛
            // SQLITE_CONSTRAINT_FOREIGNKEY，用户看到的是一句天书。这里翻译成能读懂的话。
            //
            // 为什么抓的是 DataAccessException 而不是 DataIntegrityViolationException（实测）：
            // Spring 的 SQLite 错误码映射表里 **没有** 19 = SQLITE_CONSTRAINT，所以
            // JdbcTemplate 把这个外键违例落到了兜底类 UncategorizedSQLException——
            // 它**不是** DataIntegrityViolationException 的子类（后者继承
            // NonTransientDataAccessException）。两者是兄弟，instanceof 判不出来。
            // 实测证据：catch DataIntegrityViolationException 版本跑
            // undo_of_a_referenced_row_translates_the_foreign_key_error，
            // 断言报 “Expecting actual throwable to be an instance of
            // java.lang.IllegalStateException but was:
            // org.springframework.jdbc.UncategorizedSQLException … [SQLITE_CONSTRAINT_FOREIGNKEY]”。
            // DataAccessException 是两者的共同父类，且本方法里不会有别的东西抛它。
            throw new IllegalStateException(
                    "回退失败：目标行被其他记录引用（" + table + ".id = " + targetId
                            + "）。请先撤销引用它的记录。原始错误: "
                            + e.getMostSpecificCause().getMessage(), e);
        }

        if (affected == 0) {
            // 影响 0 行却照样写 undone=1 的话，审计日志会声称「已撤销」而数据什么都没动。
            // 这条检查必须在 UPDATE change_log 之前——@Transactional 会把前面的写入一起回滚。
            throw new IllegalStateException(
                    "目标行已不存在，" + table + ".id = " + targetId + "，无法回退（未标记为已撤销）");
        }

        jdbc.update("UPDATE change_log SET undone = 1 WHERE id = ?", logId);
    }

    private void requireAllowed(String table) {
        if (!ALLOWED_TABLES.contains(table)) {
            throw new IllegalArgumentException(
                    "表「" + table + "」不在白名单里，拒绝记录或回退");
        }
    }

    /**
     * 某张表上「可被回退写入」的列名集合（即：真实存在、且不是主键的列）。
     *
     * 为什么要按 schema 校验而不是用黑名单：SQLite 的 rowid / _rowid_ / oid
     * 是主键的**别名**（在 INTEGER PRIMARY KEY 表上就是 id），它们能通过任何
     * 标识符正则，而 `SET rowid = ?` 是合法 SQL——用黑名单会漏，且维护不动。
     * 「必须真实存在且不是主键」同时关掉伪列、保留字、拼写错误三类问题。
     *
     * 缓存：schema 由 Flyway 冻结，进程内不会变。
     */
    private final Map<String, Set<String>> writableColumnsCache = new ConcurrentHashMap<>();

    private Set<String> writableColumns(String table) {
        return writableColumnsCache.computeIfAbsent(table, t -> Set.copyOf(jdbc.queryForList(
                "SELECT name FROM pragma_table_info('" + t + "') WHERE pk = 0", String.class)));
    }

    private String toJson(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("序列化快照失败", e);
        }
    }

    private Map<String, Object> fromJson(String s) {
        if (s == null) return null;
        try {
            return json.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("解析快照失败", e);
        }
    }
}
