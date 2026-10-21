package com.change.agent;

import com.change.service.ChangeLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能体的一轮对话：把话发给模型，模型要工具就执行，最多来回几轮，最后落库。
 *
 * ## 三条安全约束（设计文档决定 4）
 *
 * 1. **工具白名单**：能被执行的东西只有 {@link AgentTools} 里那张表。这个类不解释 SQL、
 *    不拼表名，连"执行一段话"的能力都没有。
 * 2. **只允许新增与修改**：{@link AgentTools} 里没有任何 delete 工具。
 * 3. **每次改动进 ChangeLog 并可回退**：写入工具一律传 origin=agent，于是
 *    {@link #undoLast} 只要找"最近一条 agent 的改动"就能退掉。
 *
 * ## 为什么循环有上限
 *
 * 模型可以无限地要工具。没有上限时，一次抽风的对话能连续写几十条记录，
 * 而每一次都是真的落库。{@link #MAX_TOOL_ROUNDS} 是一个硬闸：超过就停下，
 * 把已经发生的事告诉用户，而不是继续。
 *
 * ## 为什么写入不要求二次确认
 *
 * 设计文档没有要求确认，它要求的是**可回退**。所以这里让模型直接写、每条进日志、
 * 并提供 {@link #undoLast}。代价是一次误解析会真的落库——但撤得回来。
 * （若将来发现误写太频繁，再加"待确认"这一层；那是个产品决定，不是安全问题。）
 */
@Service
public class AgentService {

    /** 一次对话里最多几次工具往返。够用（写一餐 + 查一次容量 = 3 轮），又不是无底洞。 */
    static final int MAX_TOOL_ROUNDS = 6;

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AgentTools tools;
    private final LlmClient llm;
    private final AgentSettingsStore settings;
    private final ChangeLogService changeLog;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AgentService(AgentTools tools, LlmClient llm, AgentSettingsStore settings,
                        ChangeLogService changeLog, JdbcTemplate jdbc, ObjectMapper json) {
        this.tools = tools;
        this.llm = llm;
        this.settings = settings;
        this.changeLog = changeLog;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 一次对话的结果。`tools` 是本轮真正执行过的工具名（顺序即执行顺序）。 */
    public record ChatResult(String reply, List<String> tools, long sessionId) {}

    /**
     * 系统提示。
     *
     * 它做四件事：定身份、定口径、定写入纪律、挡掉「让我帮你写 SQL」这类越界请求。
     * 口径那一段不是装饰——「组数」与「容量」在这个应用里有明确的定义（主肌群 +1、
     * 次要 +0.5、只算完成的组），模型不知道就会自己发明一套，用户看到两个数打架。
     */
    static final String SYSTEM_PROMPT = """
            你是 changE 的训练助手。用户用它记训练、饮食、身体数据。

            说话方式：中文，简短，像一个懂行的训练伙伴。不要复述工具返回的原始 JSON。

            口径（必须遵守，不要自己发明）：
            - 「组数」= 折算组数：动作的主要肌群每组 +1，次要肌群每组 +0.5，只算标记完成的组。
            - 一周从周一起算。
            - 估算 1RM 用 Epley 公式，只对 12 次以内的组计算。

            写数据的纪律：
            - 写之前先用 search_exercises 拿到 exerciseId；不要猜 id。
            - 用户说得含糊（「练了一会儿」「吃了点东西」）时先问清楚，不要编。
            - 用户没有明确说要记的时候，只回答、不要调用写入工具。
            - 写完用一句话确认记了什么，不要贴 JSON。

            边界：
            - 你只能调用给你的工具。不能写 SQL、不能删除任何东西、不能修改表结构。
            - 用户要求删除时，告诉他目前只能在界面上手动删；你没有这个能力。
            - 涉及伤病、用药、极端节食时，不要给医疗建议，建议找专业人士。
            """;

    /** 配置是否齐了。前端用它决定是显示对话还是显示「去设置里配」。 */
    public boolean configured() {
        return settings.read().configured();
    }

    /**
     * 一轮对话。
     *
     * @throws LlmClient.LlmException 模型侧失败（没配、key 错、网络）
     * @throws IllegalArgumentException 参数问题
     */
    public ChatResult chat(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("说点什么吧");
        }
        var cfg = settings.read();
        if (!cfg.configured()) {
            throw new LlmClient.LlmException("还没有配置模型，先去「设置」里填 API key。");
        }

        long sessionId = currentSession();
        appendMessage(sessionId, "user", userMessage, List.of());

        List<LlmClient.Turn> turns = new ArrayList<>(turnsFor(sessionId));
        List<LlmClient.ToolSpec> specs = tools.specs();
        List<String> executed = new ArrayList<>();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmClient.Reply reply = llm.complete(cfg, turns, specs);

            if (!reply.wantsTools()) {
                String text = reply.content() == null ? "" : reply.content();
                appendMessage(sessionId, "assistant", text, executed);
                return new ChatResult(text, executed, sessionId);
            }

            // 把模型的"要调工具"原样记进对话，再把每个工具的结果喂回去。
            turns.add(LlmClient.Turn.assistant(reply.content(), reply.toolCalls()));
            for (LlmClient.ToolCall call : reply.toolCalls()) {
                String result;
                try {
                    result = tools.execute(call.name(), call.argumentsJson());
                    executed.add(call.name());
                } catch (RuntimeException e) {
                    // 工具失败**不**中断整轮：把错误文本喂回给模型，让它改参数重试
                    // 或者向用户解释。这比直接抛给用户「执行失败」有用得多。
                    result = "{\"error\":" + json.getNodeFactory().textNode(e.getMessage()) + "}";
                }
                turns.add(LlmClient.Turn.tool(call.id(), result));
            }
        }

        // 到上限还没收敛：停下，并把「已经做了什么」如实说出来。
        // 悄悄继续或静默返回空字符串都会让用户以为没记住东西。
        String stopped = "这一轮我调工具太多，先停下了。已经执行的有："
                + (executed.isEmpty() ? "没有" : String.join("、", executed))
                + "。要不要我把剩下的一步一步来？";
        appendMessage(sessionId, "assistant", stopped, executed);
        return new ChatResult(stopped, executed, sessionId);
    }

    /** 最近一轮对话的消息（用于打开页面时恢复上下文）。 */
    public List<Map<String, Object>> history() {
        return history(currentSession());
    }

    /**
     * 回退智能体最近一次改动。
     *
     * <p>依据就是 change_log：找最近一条 origin=agent 且未回退的 insert/update，
     * 交给 {@link ChangeLogService#undo}。用户的改动不会被它退掉——
     * 这条边界是刻意划的：用户手动改的东西，智能体不该替他撤。
     *
     * @return 一句话说明撤了什么
     * @throws IllegalStateException 没有可退的改动
     */
    public String undoLast() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, target_table, target_id FROM change_log "
                        + "WHERE origin = ? AND undone = 0 ORDER BY id DESC LIMIT 1", AgentTools.AGENT);
        if (rows.isEmpty()) {
            throw new IllegalStateException("没有可回退的智能体改动");
        }
        Map<String, Object> row = rows.get(0);
        long logId = ((Number) row.get("id")).longValue();
        String table = String.valueOf(row.get("target_table"));
        changeLog.undo(logId);
        return "已回退：撤销了对 " + table + " 的一次改动。";
    }

    // ── 会话持久化 ────────────────────────────────────────────

    /** 目前只有一个会话：最近创建的那个，没有就建一个。 */
    private long currentSession() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM chat_session ORDER BY id DESC LIMIT 1", Long.class);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        jdbc.update("INSERT INTO chat_session(title, created_at) VALUES (?, ?)",
                "和训练助手聊天", LocalDateTime.now().format(TS));
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void appendMessage(long sessionId, String role, String content, List<String> toolNames) {
        String calls = null;
        if (toolNames != null && !toolNames.isEmpty()) {
            try {
                calls = json.writeValueAsString(toolNames);
            } catch (Exception e) {
                calls = null;   // tool_calls 只是给人看的记录，写不进去不该让对话失败
            }
        }
        jdbc.update(
                "INSERT INTO chat_message(session_id, role, content, tool_calls, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sessionId, role, content, calls, LocalDateTime.now().format(TS));
    }

    /** 读回最近的消息作为上下文。上限 20 条：聊得再久，模型也不需要全部。 */
    private List<LlmClient.Turn> turnsFor(long sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content FROM chat_message WHERE session_id = ? "
                        + "ORDER BY id ASC", sessionId);
        List<LlmClient.Turn> turns = new ArrayList<>();
        turns.add(LlmClient.Turn.system(SYSTEM_PROMPT));
        int skip = Math.max(0, rows.size() - 20);
        for (Map<String, Object> r : rows.subList(skip, rows.size())) {
            String role = String.valueOf(r.get("role"));
            String content = r.get("content") == null ? "" : String.valueOf(r.get("content"));
            if ("user".equals(role)) {
                turns.add(LlmClient.Turn.user(content));
            } else if ("assistant".equals(role)) {
                turns.add(LlmClient.Turn.assistant(content, List.of()));
            }
        }
        return turns;
    }

    private List<Map<String, Object>> history(long sessionId) {
        return jdbc.queryForList(
                "SELECT role, content, tool_calls, created_at FROM chat_message "
                        + "WHERE session_id = ? ORDER BY id ASC", sessionId);
    }
}
