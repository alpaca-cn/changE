package com.change.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 智能体的地基：配置存储 + OpenAI 兼容客户端。
 *
 * 为什么这两块先做、且必须测：它们是**在没有 API key 的情况下唯一能验证的部分**。
 * 工具编排要真的调模型才能验，配置与协议映射不需要——对着一个本地 stub server
 * 就能把请求形状、响应解析、错误翻译全部跑一遍。
 */
class AgentFoundationTest {

    private final ObjectMapper json = new ObjectMapper();

    // ───────────────────────── 配置存储 ─────────────────────────

    private AgentSettingsStore store(Path dir) {
        return new AgentSettingsStore(dir.toString(), json);
    }

    @Test
    void 没有配置文件时视为未配置而不是报错(@TempDir Path dir) {
        AgentSettingsStore s = store(dir);
        assertThat(s.read().configured()).isFalse();
        // 路径指向数据目录下的 agent.json，不是别处
        assertThat(s.path().getFileName().toString()).isEqualTo("agent.json");
    }

    @Test
    void 三项齐全才算配好(@TempDir Path dir) {
        AgentSettingsStore s = store(dir);
        assertThat(s.update("https://api.example.com/v1", "m", "k").configured()).isTrue();
        // 任何一项缺了都不算配好——否则调模型只会拿到一个难懂的 400
        assertThat(s.update(null, null, "").configured()).isFalse();
    }

    @Test
    void 只改显式给出的字段_其余保持不变(@TempDir Path dir) {
        AgentSettingsStore s = store(dir);
        s.update("https://a/v1", "model-1", "key-1");

        // 只换 model：key 与 baseUrl 不能被覆盖成 null，否则「配置了但用不了」
        AgentSettingsStore.Settings after = s.update(null, "model-2", null);
        assertThat(after.model()).isEqualTo("model-2");
        assertThat(after.baseUrl()).isEqualTo("https://a/v1");
        assertThat(after.apiKey()).isEqualTo("key-1");
    }

    @Test
    void apiKey_空串为清除_null为不动(@TempDir Path dir) {
        // 这两件事在界面上是不同的操作（清空重填 vs 只改模型），不能混
        AgentSettingsStore s = store(dir);
        s.update("https://a/v1", "m", "k");
        assertThat(s.update(null, null, "").apiKey()).isNull();

        s.update("https://a/v1", "m", "k2");
        assertThat(s.update(null, null, null).apiKey()).isEqualTo("k2");
    }

    @Test
    void 回传给前端时不带_key(@TempDir Path dir) {
        AgentSettingsStore s = store(dir);
        s.update("https://a/v1", "m", "super-secret");

        Map<String, Object> safe = s.read().redacted();
        assertThat(safe).containsEntry("configured", true)
                .containsEntry("model", "m")
                .containsEntry("baseUrl", "https://a/v1");
        // **这条是这个 design 的主要理由**：key 不能出现在任何回给浏览器的字段里
        assertThat(safe.toString()).doesNotContain("super-secret");
        assertThat(safe).doesNotContainKey("apiKey");
    }

    @Test
    void 配置文件被手改坏时当作未配置_不把后端起不来(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("agent.json"), "{ 这不是 json", StandardCharsets.UTF_8);
        assertThat(store(dir).read().configured()).isFalse();
    }

    @Test
    void 写入之后文件里确实有值(@TempDir Path dir) throws IOException {
        store(dir).update("https://a/v1", "m", "k");
        String raw = Files.readString(dir.resolve("agent.json"), StandardCharsets.UTF_8);
        assertThat(raw).contains("https://a/v1").contains("\"m\"");
    }

    @Test
    void clear_删掉文件(@TempDir Path dir) {
        AgentSettingsStore s = store(dir);
        s.update("https://a/v1", "m", "k");
        assertThat(s.path()).exists();
        s.clear();
        assertThat(s.path()).doesNotExist();
        assertThat(s.read().configured()).isFalse();
    }

    // ───────────────────────── 客户端（对着本地 stub）─────────────────────────

    /** 起一个只应答一次的 HTTP server，把收到的请求体与 Authorization 记下来。 */
    private HttpServer stub(int status, String replyBody, AtomicReference<String> bodyOut,
                            AtomicReference<String> authOut) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodyOut.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authOut.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return server;
    }

    private AgentSettingsStore.Settings pointingAt(HttpServer server) {
        return new AgentSettingsStore.Settings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", "k-123");
    }

    @Test
    void 请求体是标准的_chat_completions_形状() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = stub(200, "{\"choices\":[{\"message\":{\"content\":\"好\"}}]}", body, auth);
        try {
            new OpenAiCompatibleClient(json).complete(
                    pointingAt(server),
                    List.of(LlmClient.Turn.system("你是助手"), LlmClient.Turn.user("今天练了什么")),
                    List.of(new LlmClient.ToolSpec("weekly_volume", "本周容量",
                            "{\"type\":\"object\",\"properties\":{}}")));

            String sent = body.get();
            assertThat(sent).contains("\"model\":\"test-model\"");
            assertThat(sent).contains("\"role\":\"system\"");
            assertThat(sent).contains("\"role\":\"user\"");
            // 工具必须以 function 形式发出，且带 parameters
            assertThat(sent).contains("\"tools\"");
            assertThat(sent).contains("\"name\":\"weekly_volume\"");
            assertThat(sent).contains("\"parameters\"");
            // key 走 Authorization，绝不能出现在 body 里
            assertThat(auth.get()).isEqualTo("Bearer k-123");
            assertThat(sent).doesNotContain("k-123");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 解析出工具调用() throws IOException {
        String reply = """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"call_1","type":"function",
                   "function":{"name":"weekly_volume","arguments":"{}"}}]}}]}
                """;
        HttpServer server = stub(200, reply, new AtomicReference<>(), new AtomicReference<>());
        try {
            LlmClient.Reply r = new OpenAiCompatibleClient(json)
                    .complete(pointingAt(server), List.of(LlmClient.Turn.user("x")), List.of());
            assertThat(r.wantsTools()).isTrue();
            assertThat(r.toolCalls()).hasSize(1);
            assertThat(r.toolCalls().get(0).name()).isEqualTo("weekly_volume");
            assertThat(r.toolCalls().get(0).id()).isEqualTo("call_1");
            assertThat(r.toolCalls().get(0).argumentsJson()).isEqualTo("{}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 解析出普通回答() throws IOException {
        HttpServer server = stub(200, "{\"choices\":[{\"message\":{\"content\":\"练得不错\"}}]}",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            LlmClient.Reply r = new OpenAiCompatibleClient(json)
                    .complete(pointingAt(server), List.of(LlmClient.Turn.user("x")), List.of());
            assertThat(r.wantsTools()).isFalse();
            assertThat(r.content()).isEqualTo("练得不错");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 状态码401_翻成一句人话_而不是只给状态码() throws IOException {
        HttpServer server = stub(401, "{\"error\":{\"message\":\"invalid api key\"}}",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> new OpenAiCompatibleClient(json)
                    .complete(pointingAt(server), List.of(LlmClient.Turn.user("x")), List.of()))
                    .isInstanceOf(LlmClient.LlmException.class)
                    .hasMessageContaining("401")
                    .hasMessageContaining("key")
                    // 原文也要带上：不同提供方的错误体形状各异，猜不如给原文
                    .hasMessageContaining("invalid api key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 状态码404_提示_baseUrl_可能写错() throws IOException {
        HttpServer server = stub(404, "not found", new AtomicReference<>(), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> new OpenAiCompatibleClient(json)
                    .complete(pointingAt(server), List.of(LlmClient.Turn.user("x")), List.of()))
                    .isInstanceOf(LlmClient.LlmException.class)
                    .hasMessageContaining("baseUrl");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 没配置时提示去设置里填_不发出请求() {
        assertThatThrownBy(() -> new OpenAiCompatibleClient(json).complete(
                new AgentSettingsStore.Settings(null, null, null),
                List.of(LlmClient.Turn.user("x")), List.of()))
                .isInstanceOf(LlmClient.LlmException.class)
                .hasMessageContaining("设置");
    }

    @Test
    void 状态码200_但_body_里是错误_也能读出来() throws IOException {
        // 有些提供方把错误放在 200 的 body 里，只看状态码会得到一句空话
        HttpServer server = stub(200, "{\"error\":{\"message\":\"余额不足\"}}",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> new OpenAiCompatibleClient(json)
                    .complete(pointingAt(server), List.of(LlmClient.Turn.user("x")), List.of()))
                    .isInstanceOf(LlmClient.LlmException.class)
                    .hasMessageContaining("余额不足");
        } finally {
            server.stop(0);
        }
    }
}
