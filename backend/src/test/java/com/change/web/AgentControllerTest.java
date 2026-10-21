package com.change.web;

import com.change.agent.AgentSettingsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 智能体端点。
 *
 * <p>最重要的一条是**配置回传里没有 key**。那不是「少写一个字段」，是默认不提供：
 * 前端拿到 {@code configured:true} 就能工作，从来不需要看见 key。
 * 把它回传意味着它会进内存、进 devtools、可能进日志。
 *
 * <p>这里刻意不测「对话成功」——真模型要 API key，而 key 是用户自己配的。
 * 对话的内部行为（工具执行、轮数上限、错误回喂）由 AgentToolsTest / AgentServiceTest
 * 用假模型覆盖；这个类只管 HTTP 契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired AgentSettingsStore settings;

    @AfterEach
    void cleanup() {
        // 配置存在数据目录里、不在事务里，所以必须手动清，否则会影响后面的用例
        settings.clear();
    }

    @Test
    void 没配置时_configured_是_false() throws Exception {
        mvc.perform(get("/api/agent/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void 写了配置之后回传里没有_key() throws Exception {
        mvc.perform(post("/api/agent/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"https://api.example.com/v1","model":"m-1","apiKey":"sk-secret-xyz"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("m-1"))
                .andExpect(jsonPath("$.baseUrl").value("https://api.example.com/v1"))
                // 整个响应体里都不能出现 key 的任何片段
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sk-secret-xyz"))))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        mvc.perform(get("/api/agent/config"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sk-secret-xyz"))));
    }

    @Test
    void 部分更新不会把没传的字段清空() throws Exception {
        mvc.perform(post("/api/agent/config").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"baseUrl":"https://a/v1","model":"m-1","apiKey":"k-1"}
                        """));

        // 只改 model：baseUrl 与 key 必须留着，否则用户会看到「配置了但用不了」
        mvc.perform(post("/api/agent/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"m-2\"}"))
                .andExpect(jsonPath("$.model").value("m-2"))
                .andExpect(jsonPath("$.baseUrl").value("https://a/v1"))
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void 没配置时对话返回_400_并提示去设置() throws Exception {
        // 不能是 500：对用户来说「没配 key」是他能改的事，不是服务器坏了
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"今天练什么\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("设置")));
    }

    @Test
    void 空消息是_400() throws Exception {
        mvc.perform(post("/api/agent/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"https://a/v1\",\"model\":\"m\",\"apiKey\":\"k\"}"));

        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 没有可回退的改动时_undo_是_400_而不是_500() throws Exception {
        // 「当前状态下做不了」对调用方是 400。500 会让用户以为服务器坏了。
        mvc.perform(post("/api/agent/undo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("没有可回退")));
    }

    @Test
    void history_在没有对话时是空数组_不是_404() throws Exception {
        mvc.perform(get("/api/agent/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
