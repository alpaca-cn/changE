package com.change.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具白名单：模型只能调用这里登记的东西。
 *
 * <p>这一层是「智能体可写」的安全线本身，而它**不需要 API key 就能完整验证**——
 * 所以没有理由把它留到有 key 之后再测。
 */
@SpringBootTest
@Transactional
class AgentToolsTest {

    @Autowired AgentTools tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void 白名单是固定的那八个() {
        assertThat(tools.names()).containsExactlyInAnyOrder(
                "weekly_volume", "recent_sessions", "today_nutrition",
                "search_exercises", "body_metrics",
                "record_workout", "record_meal", "record_body_metric");
    }

    @Test
    void 没有任何删除类工具() {
        // 设计文档：「只允许新增与修改，不允许删除」。这条不是靠模型自觉，
        // 是靠**根本不存在**这样的工具。
        assertThat(tools.names()).noneMatch(n ->
                n.contains("delete") || n.contains("remove") || n.contains("drop"));
    }

    @Test
    void 模型幻觉出一个不存在的工具时明确报错_并列出可用的() {
        assertThatThrownBy(() -> tools.execute("run_sql", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有这个工具")
                // 报错里要带上可用清单，否则模型没法自己纠正
                .hasMessageContaining("weekly_volume");
    }

    @Test
    void 参数不是合法_JSON_时报错而不是静默当成空对象() {
        // 静默当成 {} 会让「date 传成 2026-13-45」这类问题变成一次莫名其妙的写入
        assertThatThrownBy(() -> tools.execute("weekly_volume", "{不是 json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void 每个工具的定义都带合法_JSON_Schema() throws Exception {
        var specs = tools.specs();
        assertThat(specs).hasSize(8);
        for (var s : specs) {
            assertThat(s.name()).isNotBlank();
            assertThat(s.description()).isNotBlank();
            var node = json.readTree(s.parametersJson());
            assertThat(node.path("type").asText()).isEqualTo("object");
            assertThat(node.path("properties").isObject()).isTrue();
        }
    }

    @Test
    void 只读工具能真的返回数据() throws Exception {
        var volume = json.readTree(tools.execute("weekly_volume", "{}"));
        assertThat(volume.path("muscles")).hasSize(17);
    }

    @Test
    void 写入工具落库并记成_agent() {
        var out = tools.execute("record_body_metric",
                "{\"date\":\"2026-10-20\",\"metricType\":\"weight\",\"value\":71.3,\"unit\":\"kg\"}");
        assertThat(out).contains("\"ok\":true");

        // 审计来源必须是 agent —— 这就是我给三个服务加 origin 重载的全部理由
        String origin = jdbc.queryForObject(
                "SELECT origin FROM change_log WHERE target_table='body_metric' "
                        + "ORDER BY id DESC LIMIT 1", String.class);
        assertThat(origin).isEqualTo("agent");
    }

    @Test
    void 写入工具缺必填参数时拒绝_不写半条记录() {
        assertThatThrownBy(() -> tools.execute("record_body_metric",
                "{\"date\":\"2026-10-20\",\"metricType\":\"weight\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
    }

    @Test
    void 训练工具至少要求一组() {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES ('ZZ Tool Bench', 'kg')");
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        assertThatThrownBy(() -> tools.execute("record_workout",
                "{\"exerciseId\":" + id + ",\"sets\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sets");
    }
}

/**
 * 编排：假模型 → 真工具 → 真落库。
 *
 * <p>为什么这是唯一能证明「编排正确」的方式：真模型要 API key，而 key 是用户自己配的
 * （这是设计，不是缺陷）。但「模型要调工具 → 我们执行 → 结果喂回去 → 模型给最终回答」
 * 这条链**不依赖模型是谁**——用假模型把它跑一遍，就等于把它跑过了。
 */
@SpringBootTest
@Transactional
class AgentServiceTest {

    @Autowired AgentTools tools;
    @Autowired com.change.service.ChangeLogService changeLog;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    /** 按预设顺序吐回复的假模型，并记下每次被调用时看到的消息。 */
    static class FakeLlm implements LlmClient {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<List<Turn>> seen = new ArrayList<>();

        FakeLlm then(Reply r) {
            replies.add(r);
            return this;
        }

        @Override
        public Reply complete(AgentSettingsStore.Settings s, List<Turn> turns, List<ToolSpec> specs) {
            seen.add(List.copyOf(turns));
            Reply r = replies.poll();
            if (r == null) throw new IllegalStateException("假模型没有更多回复了");
            return r;
        }
    }

    private AgentService service(FakeLlm llm, Path dir) {
        AgentSettingsStore store = new AgentSettingsStore(dir.toString(), json);
        store.update("https://example.invalid/v1", "test-model", "k");
        return new AgentService(tools, llm, store, changeLog, jdbc, json);
    }

    @Test
    void 模型不吃工具_直接回话(@TempDir Path dir) {
        FakeLlm llm = new FakeLlm().then(new LlmClient.Reply("今天练胸吧", List.of()));
        var r = service(llm, dir).chat("今天练什么");

        assertThat(r.reply()).isEqualTo("今天练胸吧");
        assertThat(r.tools()).isEmpty();
        // 用户那句话与回答都落了库
        assertThat(jdbc.queryForList(
                "SELECT content FROM chat_message ORDER BY id DESC LIMIT 2", String.class))
                .containsExactly("今天练胸吧", "今天练什么");
    }

    @Test
    void 模型要工具_执行_再把结果喂回去(@TempDir Path dir) {
        FakeLlm llm = new FakeLlm()
                .then(new LlmClient.Reply(null, List.of(
                        new LlmClient.ToolCall("c1", "weekly_volume", "{}"))))
                .then(new LlmClient.Reply("这周 16 组，胸部偏少", List.of()));

        var r = service(llm, dir).chat("这周练得怎么样");

        assertThat(r.tools()).containsExactly("weekly_volume");
        assertThat(r.reply()).isEqualTo("这周 16 组，胸部偏少");

        // 第二次调用必须带着工具结果（role=tool），否则模型等于没看到数据
        List<LlmClient.Turn> second = llm.seen.get(1);
        assertThat(second).anyMatch(t -> "tool".equals(t.role()) && "c1".equals(t.toolCallId()));
        assertThat(second).anyMatch(t ->
                "tool".equals(t.role()) && t.content() != null && t.content().contains("muscles"));
    }

    @Test
    void 工具失败时把错误喂回模型_而不是直接抛给用户(@TempDir Path dir) {
        // 模型猜错参数是常事。直接把异常抛给用户，用户看到的是一句他无从下手的报错；
        // 喂回模型，它还有机会改参数重试或向用户解释。
        FakeLlm llm = new FakeLlm()
                .then(new LlmClient.Reply(null, List.of(
                        new LlmClient.ToolCall("c1", "run_sql", "{}"))))
                .then(new LlmClient.Reply("我没有这个能力", List.of()));

        var r = service(llm, dir).chat("帮我删掉所有记录");

        assertThat(r.reply()).isEqualTo("我没有这个能力");
        // 失败的工具不记进「已执行」
        assertThat(r.tools()).isEmpty();
        List<LlmClient.Turn> second = llm.seen.get(1);
        assertThat(second).anyMatch(t ->
                "tool".equals(t.role()) && t.content() != null && t.content().contains("没有这个工具"));
    }

    @Test
    void 模型无限要工具时停下来并如实报告(@TempDir Path dir) {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < AgentService.MAX_TOOL_ROUNDS + 3; i++) {
            llm.then(new LlmClient.Reply(null, List.of(
                    new LlmClient.ToolCall("c" + i, "weekly_volume", "{}"))));
        }

        var r = service(llm, dir).chat("一直查");

        assertThat(r.reply()).contains("停下了").contains("weekly_volume");
        // 不能静默返回空字符串——那会让用户以为没记住东西
        assertThat(llm.seen).hasSize(AgentService.MAX_TOOL_ROUNDS);
    }

    @Test
    void 没配置时提示去设置里填(@TempDir Path dir) {
        AgentSettingsStore empty = new AgentSettingsStore(dir.toString(), json);
        AgentService svc = new AgentService(tools, new FakeLlm(), empty, changeLog, jdbc, json);

        assertThatThrownBy(() -> svc.chat("你好"))
                .isInstanceOf(LlmClient.LlmException.class)
                .hasMessageContaining("设置");
    }

    @Test
    void 回退只退智能体的改动_不退用户手输的(@TempDir Path dir) {
        // 用户先手动记一条
        jdbc.update("INSERT INTO body_metric(measured_on, metric_type, value, unit) "
                + "VALUES ('2026-10-19','weight',70.0,'kg')");
        long userId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO change_log(changed_at, origin, action, target_table, target_id, "
                + "before_json, after_json) VALUES ('2026-10-19T10:00:00','user','insert',"
                + "'body_metric',?,NULL,'{\"value\":70.0}')", userId);

        // 智能体再记一条
        tools.execute("record_body_metric",
                "{\"date\":\"2026-10-20\",\"metricType\":\"weight\",\"value\":72.0,\"unit\":\"kg\"}");

        String msg = service(new FakeLlm(), dir).undoLast();
        assertThat(msg).contains("body_metric");

        // 用户的记录还在，智能体那条没了
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM body_metric WHERE measured_on='2026-10-19'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM body_metric WHERE measured_on='2026-10-20'", Integer.class))
                .isZero();
    }
}
