package com.change.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容协议的客户端（DeepSeek / OpenAI / Moonshot / Qwen / Ollama 都是这个形状）。
 *
 * 用 JDK 的 HttpClient 手写这一层，而不是接 LangChain4j：见 {@link LlmClient} 的说明
 * ——工具白名单必须由我们执行，而那要求我们真的看见模型的 tool_calls。
 *
 * 协议细节（都按 OpenAI 的 chat completions）：
 *   - 请求：{@code POST {baseUrl}/chat/completions}，body 带 model / messages / tools
 *   - 回复：{@code choices[0].message.content} 与 {@code choices[0].message.tool_calls[]}
 *   - 工具结果：一条 role=tool 的消息，用 {@code tool_call_id} 关联到那次调用
 */
@Component
public class OpenAiCompatibleClient implements LlmClient {

    /**
     * 读超时给得比较长：模型先想再调工具，一轮里可能有好几秒。
     * 但**必须有**——没有超时的话网络半死会让界面永远转圈。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private final ObjectMapper json;
    private final HttpClient http;

    public OpenAiCompatibleClient(ObjectMapper json) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public Reply complete(AgentSettingsStore.Settings settings, List<Turn> turns, List<ToolSpec> tools) {
        if (!settings.configured()) {
            throw new LlmException("还没有配置模型，先去「设置」里填 API key。");
        }

        HttpResponse<String> res;
        try {
            res = http.send(buildRequest(settings, turns, tools),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("调用模型时被中断。", e);
        } catch (IOException e) {
            // 最常见的是 baseUrl 写错或没网。把底层原因带上，否则用户只能看到「失败」。
            throw new LlmException("连不上模型服务：" + e.getMessage(), e);
        }

        if (res.statusCode() / 100 != 2) {
            throw new LlmException(explainStatus(res.statusCode(), res.body()));
        }
        return parseReply(res.body());
    }

    /**
     * 拉服务商的可用模型列表（OpenAI 兼容的 {@code GET {baseUrl}/models}）。
     *
     * <p><b>为什么要有这个</b>：型号名会下线。2026-10-25 用户先后填了
     * {@code qwen-vl-max}（已下线）和 {@code Qwen3.8-Flash}（不存在），两次都只拿到
     * 一句 {@code model_not_found} —— 而「现在到底有哪些能用的」只有问服务商才知道。
     * 让人手打型号就是在赌。
     *
     * <p>返回的 id **按字母序**排好：服务商的顺序不稳定，每次刷新都换位置很难扫。
     */
    public List<String> listModels(AgentSettingsStore.Settings settings) {
        if (!settings.configured()) {
            throw new LlmException("还没有配置模型，先去「设置」里填 API key。");
        }

        String url = trimTrailingSlash(settings.baseUrl()) + "/models";
        HttpRequest req = HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("拉模型列表时被中断。", e);
        } catch (IOException e) {
            throw new LlmException("连不上模型服务：" + e.getMessage(), e);
        }

        if (res.statusCode() / 100 != 2) {
            throw new LlmException("拉模型列表失败：" + explainStatus(res.statusCode(), res.body()));
        }

        JsonNode root = readTreeOrEmptyObject(res.body());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            // 有的服务商不实现 /models。这是「服务商不支持」，不是我们的错，
            // 但要说得让人能判断：那就手动填型号。
            throw new LlmException(
                    "这个服务商没有返回模型列表（它可能不支持 GET /models）。请手动填写型号名。");
        }

        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode n : data) {
            JsonNode id = n.get("id");
            if (id != null && !id.asText().isBlank()) {
                ids.add(id.asText());
            }
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    private static String trimTrailingSlash(String s) {
        String out = s == null ? "" : s.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private HttpRequest buildRequest(AgentSettingsStore.Settings s, List<Turn> turns, List<ToolSpec> tools) {
        ObjectNode body = json.createObjectNode();
        body.put("model", s.model());

        ArrayNode msgs = body.putArray("messages");
        for (Turn t : turns) {
            ObjectNode m = msgs.addObject();
            m.put("role", t.role());
            if (t.hasImages()) {
                // 多模态：content 是一个 parts 数组。文字在前、图片在后——
                // 有些提供方对顺序敏感，文字在前更稳。
                ArrayNode parts = m.putArray("content");
                if (t.content() != null && !t.content().isBlank()) {
                    ObjectNode tp = parts.addObject();
                    tp.put("type", "text");
                    tp.put("text", t.content());
                }
                for (String url : t.images()) {
                    ObjectNode ip = parts.addObject();
                    ip.put("type", "image_url");
                    ip.putObject("image_url").put("url", url);
                }
            } else if (t.content() != null) {
                m.put("content", t.content());
            } else if (!"assistant".equals(t.role())) {
                m.put("content", "");
            }
            if (t.toolCalls() != null && !t.toolCalls().isEmpty()) {
                ArrayNode calls = m.putArray("tool_calls");
                for (ToolCall c : t.toolCalls()) {
                    ObjectNode oc = calls.addObject();
                    oc.put("id", c.id());
                    oc.put("type", "function");
                    ObjectNode fn = oc.putObject("function");
                    fn.put("name", c.name());
                    fn.put("arguments", c.argumentsJson() == null ? "{}" : c.argumentsJson());
                }
            }
            if (t.toolCallId() != null) {
                m.put("tool_call_id", t.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode arr = body.putArray("tools");
            for (ToolSpec spec : tools) {
                ObjectNode t = arr.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", readTreeOrEmptyObject(spec.parametersJson()));
            }
            body.put("tool_choice", "auto");
        }

        String url = s.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        // ObjectNode 序列化不会真的失败（里面全是 JSON 节点）；但 writeValueAsString
        // 声明了受检异常，这里如实转成运行时错误并带上原文，而不是硬吞。
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("请求体序列化失败", e);
        }
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + s.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }

    private JsonNode readTreeOrEmptyObject(String raw) {
        try {
            JsonNode n = json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            return n != null && n.isObject() ? n : json.createObjectNode();
        } catch (IOException e) {
            // 工具定义的 schema 是我们自己写的常量，坏了属于编码错误。
            // 这里不静默吞：写成非法 schema 会让模型看不到参数说明，行为莫名其妙。
            throw new IllegalStateException("工具的 parameters 不是合法 JSON: " + raw, e);
        }
    }

    private Reply parseReply(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new LlmException("模型返回的不是 JSON。", e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // 有些提供方把错误放在 200 的 body 里（比如 {error:{message}}）。
            JsonNode err = root.path("error").path("message");
            throw new LlmException(err.isTextual()
                    ? "模型服务报错：" + err.asText()
                    : "模型没有返回 choices。");
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.hasNonNull("content") ? message.get("content").asText() : null;

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : message.path("tool_calls")) {
            JsonNode fn = c.path("function");
            calls.add(new ToolCall(
                    c.path("id").asText("call_" + calls.size()),
                    fn.path("name").asText(""),
                    fn.path("arguments").asText("{}")));
        }
        return new Reply(content, calls);
    }

    /**
     * 把 HTTP 错误翻成一句人能读懂的话。
     *
     * 三个最常见的：401 key 错、404 baseUrl 错、429 限流。除此之外原样带上 body 的前一段
     * ——不同提供方的错误体形状各异，猜不如给原文。
     */
    private String explainStatus(int status, String body) {
        String hint = switch (status) {
            case 401, 403 -> "API key 不对或没有权限。";
            // 404 有两种常见原因，**必须都说出来**：地址错，或者型号不存在。
            // 2026-10-25 实测：用户把 qwen-vl-max 填进百炼，返回的是
            // {"error":{"message":"Model not exist."}} —— 地址完全正确。
            // 只说「baseUrl 可能写错了」会把人往错误的方向带。
            case 404 -> "接口返回 404：多半是**型号名不存在**（服务商会下线旧型号），其次是 baseUrl 写错（它应当以 /v1 这类版本号结尾，且不带 /chat/completions）。下面原文里的 message 会说明是哪一种。";
            case 429 -> "被限流了，等一会儿再试。";
            default -> null;
        };
        String snippet = body == null ? "" : body.strip();
        if (snippet.length() > 300) {
            snippet = snippet.substring(0, 300) + "…";
        }
        return hint == null
                ? "模型服务返回 " + status + "：" + snippet
                : "模型服务返回 " + status + "：" + hint + " 原文：" + snippet;
    }
}
