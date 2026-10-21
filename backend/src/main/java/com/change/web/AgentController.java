package com.change.web;

import com.change.agent.AgentService;
import com.change.agent.AgentSettingsStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体的入口。
 *
 * <p><b>这个类的核心职责之一是「不把 key 回传」。</b>
 * {@code GET /api/agent/config} 只回 {@code configured / baseUrl / model}，
 * 由 {@link AgentSettingsStore.Settings#redacted()} 保证——不是靠这里自觉记得删字段。
 * 前端拿到 {@code configured: true} 就够了，它不需要看见 key 才能工作。
 *
 * <p>把 key 回传给浏览器意味着它会进内存、进 devtools、可能进日志；
 * 而前端从来不需要它。所以这不是「少写一个字段」，是**默认不提供**。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agent;
    private final AgentSettingsStore settings;

    public AgentController(AgentService agent, AgentSettingsStore settings) {
        this.agent = agent;
        this.settings = settings;
    }

    /** 配置状态。**不含 key**。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return settings.read().redacted();
    }

    /**
     * 写配置。三个字段都可省略：省略 = 这项不动。
     *
     * <p>用 POST 不用 PUT：外壳的 api_request 只放行 GET/POST（src-tauri/src/http.rs
     * 里那条白名单是有测试钉着的），为了一个 PUT 去放宽它，等于用一个安全边界换一个
     * 语义更漂亮的动词。
     *
     * <p>{@code apiKey} 传空串表示清除（界面上的「清除」按钮），传 null 表示不动。
     * 这两种意图在界面上是不同的操作，不能合并。
     */
    @PostMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody ConfigInput input) {
        return settings.update(input.baseUrl(), input.model(), input.apiKey()).redacted();
    }

    /** 请求体。三个字段都可空。 */
    public record ConfigInput(String baseUrl, String model, String apiKey) {}

    /** 一轮对话。 */
    @PostMapping("/chat")
    public AgentService.ChatResult chat(@RequestBody ChatInput input) {
        return agent.chat(input == null ? null : input.message());
    }

    public record ChatInput(String message) {}

    /** 最近一个会话的消息（打开页面时恢复上下文用）。 */
    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return agent.history();
    }

    /**
     * 回退智能体最近一次改动。没有可退的改动时 400（不是 500）。
     *
     * <p>服务层抛的是 {@link IllegalStateException}——它是一种「当前状态下做不了」，
     * 对调用方是 400 而不是服务器故障。这里转成带状态码的异常，而不是让服务层
     * 去认识 Web 的异常类型（那样服务就绑定了协议）。
     */
    @PostMapping("/undo")
    public Map<String, Object> undo() {
        try {
            return Map.of("message", agent.undoLast());
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
