package com.change.agent;

import java.util.List;

/**
 * 调大模型的那一层。**故意是一个我们自己的接口，不是直接用库的 ChatModel。**
 *
 * ## 为什么不直接调 LangChain4j
 *
 * 工具白名单是这套东西唯一不可协商的安全线（设计文档决定 4）：
 * 「工具集是白名单，不是让模型自由写 SQL」。
 * 如果白名单交给库去执行，那「模型只能调用清单里的工具」这件事就成了**别人的实现细节**，
 * 我们只能相信它；而在这个接口下，{@link AgentTools#execute} 里那个 Map 查不到就是拒绝，
 * 是我们自己的代码在拒绝。
 *
 * 附带的好处：这个接口可以被测试替身直接实现，于是「模型要调工具 → 我们执行 → 把结果
 * 喂回去 → 模型给最终回答」这条链**不需要真的 API key 就能端到端测**。
 *
 * ## 为什么消息用 role 字符串而不是继承一堆类
 *
 * 我们的消息种类只有四种（system / user / assistant / tool），而 OpenAI 兼容协议里
 * 它们本来就是这个形状。用一种结构 + 一个 role 字段，序列化与断言都直观。
 */
public interface LlmClient {

    /**
     * 一条消息。
     *
     * `toolCalls` 只在 assistant 上出现，`toolCallId` 只在 tool 上出现，
     * `images` 只在 user 上出现（base64 data URL：`data:image/jpeg;base64,...`）。
     *
     * 为什么图片挂在 user 上而不是单独一种 message 类型：OpenAI 兼容协议里
     * 图片就是 user 消息 content 的一部分（一个 parts 数组），没有独立的图片消息。
     * 照着协议的形状建模，序列化时不用做转换。
     */
    record Turn(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                List<String> images) {

        public Turn {
            images = images == null ? List.of() : List.copyOf(images);
        }

        public static Turn system(String content) {
            return new Turn("system", content, List.of(), null, List.of());
        }

        public static Turn user(String content) {
            return new Turn("user", content, List.of(), null, List.of());
        }

        /** 带图片的一条用户消息。`imageDataUrl` 必须以 `data:image/` 开头。 */
        public static Turn userWithImage(String content, List<String> images) {
            return new Turn("user", content, List.of(), null, images);
        }

        public static Turn assistant(String content, List<ToolCall> toolCalls) {
            return new Turn("assistant", content, toolCalls, null, List.of());
        }

        public static Turn tool(String toolCallId, String content) {
            return new Turn("tool", content, List.of(), toolCallId, List.of());
        }

        public boolean hasImages() {
            return !images.isEmpty();
        }
    }

    /** 模型请求调用一个工具。`argumentsJson` 是模型给的 JSON 对象字符串。 */
    record ToolCall(String id, String name, String argumentsJson) {}

    /**
     * 一个可被模型调用的工具。
     *
     * `parameters` 是 JSON Schema 的 `properties` + `required` 两部分拼起来的完整 schema，
     * 由 {@link AgentTools} 里的定义给出——不从模型的输出里学，也不允许模型自己发明工具。
     */
    record ToolSpec(String name, String description, String parametersJson) {}

    /** 模型的一次回复：要么是话，要么是要调工具（协议上两者可以同时有）。 */
    record Reply(String content, List<ToolCall> toolCalls) {
        public boolean wantsTools() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * 发一轮。
     *
     * @throws LlmException 网络失败、非 200、或响应里没有 `choices` ——
     *                      这些都该让用户看到一句话，而不是一个空白的对话气泡
     */
    Reply complete(AgentSettingsStore.Settings settings, List<Turn> turns, List<ToolSpec> tools);

    /** 调用失败。message 会原样显示给用户，所以必须是人能读懂的。 */
    class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
