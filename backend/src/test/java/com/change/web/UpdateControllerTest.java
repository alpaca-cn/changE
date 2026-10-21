package com.change.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 更新检查的「取清单」这一端。
 *
 * <p>为什么这个类在网上不查真地址：更新源是要部署时才定的事，测试不该依赖
 * 任何一个外部站点活不活着。所以对着一个本地 stub server 验协议：
 * 能不能取到、取到的形状对不对、坏清单与坏地址会不会给出能读懂的拒绝。
 *
 * <p>版本比较不在这里测 —— 那是纯计算，在 `version.ts` 的用例里（`isNewer`）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UpdateControllerTest {

    @Autowired MockMvc mvc;

    /** 起一个只应答一次的 stub，返回它的 base URL。 */
    private String stub(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest.json", exchange -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/latest.json";
    }

    @Test
    void 取回清单的版本与说明() throws Exception {
        String url = stub(200, """
                {"version":"9.9.9","notes":"修了很多","url":"https://example.com/x.exe"}
                """);
        mvc.perform(get("/api/version/manifest").param("url", url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("9.9.9"))
                .andExpect(jsonPath("$.notes").value("修了很多"))
                .andExpect(jsonPath("$.url").value("https://example.com/x.exe"));
    }

    @Test
    void 只有_version_时其余字段给空串而不是_null() throws Exception {
        // 前端拿到 null 会渲染成 "null" 字样；空串不会。
        String url = stub(200, "{\"version\":\"1.0.0\"}");
        mvc.perform(get("/api/version/manifest").param("url", url))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.notes").value(""))
                .andExpect(jsonPath("$.url").value(""));
    }

    @Test
    void 没给地址时是_400_而不是_500() throws Exception {
        // 「你还没配更新源」是调用方的事，不是服务器坏了
        mvc.perform(get("/api/version/manifest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("更新源")));
    }

    @Test
    void 只放行_http_与_https() throws Exception {
        // file:// 与 jar:// 在这个位置没有正当用途，而它们能读到本机文件
        for (String bad : new String[]{"file:///C:/Windows/win.ini", "jar:file:/x.jar!/a", "ftp://x/y"}) {
            mvc.perform(get("/api/version/manifest").param("url", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(
                            org.hamcrest.Matchers.containsString("http")));
        }
    }

    @Test
    void 清单不是_JSON_时给出能读懂的拒绝() throws Exception {
        String url = stub(200, "<html>not json</html>");
        mvc.perform(get("/api/version/manifest").param("url", url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("JSON")));
    }

    @Test
    void 清单里没有_version_时拒绝() throws Exception {
        // 没有 version 的清单什么也说明不了；当成「已是最新」会让人以为不用更新
        String url = stub(200, "{\"notes\":\"x\"}");
        mvc.perform(get("/api/version/manifest").param("url", url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("version")));
    }

    @Test
    void 更新源返回_404_时把状态码说出来() throws Exception {
        String url = stub(404, "not found");
        String body = mvc.perform(get("/api/version/manifest").param("url", url))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("404");
    }

    @Test
    void 连不上时是_400_并带上原因() throws Exception {
        // 端口 1 上没人监听
        mvc.perform(get("/api/version/manifest").param("url", "http://127.0.0.1:1/latest.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("连不上")));
    }
}
