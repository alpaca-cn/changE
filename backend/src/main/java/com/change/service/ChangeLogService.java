package com.change.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 变更日志：让「智能体可写」这件事有安全带。
 *
 * 三条设计约束（设计文档决定 4）：
 *   1. 表名必须在白名单里 —— 工具集是白名单，不是让模型自由写 SQL
 *   2. 只记录 insert / update，不提供 delete —— 删除无法撤销，所以干脆不给
 *   3. 每条改动存下 before / after 的 JSON 快照，可一键回退
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

    /** 合法的 SQL 标识符：字母或下划线开头，其余为字母/数字/下划线。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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

    /** 回退一条改动。insert 的回退是删除该行；update 的回退是写回 before 值。 */
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

        if ("insert".equals(action)) {
            jdbc.update("DELETE FROM " + table + " WHERE id = ?", targetId);
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
            for (Map.Entry<String, Object> e : before.entrySet()) {
                requireIdentifier(e.getKey());
                if (sets.length() > 0) {
                    sets.append(", ");
                }
                sets.append(e.getKey()).append(" = ?");
                values.add(e.getValue());
            }
            values.add(targetId);
            jdbc.update("UPDATE " + table + " SET " + sets + " WHERE id = ?", values.toArray());
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
     * 校验一列列名是合法的 SQL 标识符。
     *
     * 为什么必须校验：undo 的 UPDATE 路径要把列名拼进 SQL——
     * 参数占位符只能保护**值**，保护不了**标识符**。
     * 目前 before 的键由本项目的服务层构造，但这是一条注入面，
     * 一旦将来有人把外部来源（模型输出、导入文件）的键传进来就会失效。
     */
    private static void requireIdentifier(String column) {
        if (column == null || !IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("非法的列名: " + column);
        }
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
