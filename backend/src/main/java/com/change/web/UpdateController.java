package com.change.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 更新检查。
 *
 * ## 为什么这个请求必须由后端发
 *
 * 外壳的 CSP 是 `connect-src 'self'` —— 前端（WebView 里）去 fetch 任何外部地址
 * 都会被浏览器直接拒绝，报的还是「Refused to connect」这种一半人看不懂的错。
 * 为了一个更新检查去放宽 `connect-src`，等于用整台机器的出网权限换一个按钮。
 * 后端本来就是应用唯一的网络客户端（模型调用也走它），放在这里不用改任何安全边界。
 *
 * ## 这个类只做「取」，不做「比」
 *
 * 它把清单原样取回来，版本比较交给前端（{@code version.ts} 的 {@code isNewer}）。
 * 理由：比较逻辑要测很多边界（1.10 vs 1.9、预发布后缀、位数不齐），
 * 那是纯计算，放在有测试的那一侧更划算；后端只负责「能不能取到」。
 *
 * ## 真正的自动更新还没做
 *
 * Tauri 的 updater 需要一对签名密钥 + 一个能放 `latest.json` 的地址，
 * 而那是部署决定（放哪、谁维护），不是代码问题。这里先把「这个版本是不是最新的」
 * 做出来，剩下的等有了地址再接。
 */
@RestController
@RequestMapping("/api/version")
public class UpdateController {

    /** 清单只该是一个小 JSON。给 5 秒足够，避免一个挂住的地址把界面拖死。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 清单最大长度。防止有人把地址指向一个几百 MB 的文件。 */
    private static final int MAX_BYTES = 64 * 1024;

    private final ObjectMapper json;
    private final HttpClient http;

    public UpdateController(ObjectMapper json) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * 取更新清单。
     *
     * @param url 清单地址，必须是 http/https。省略或空白时 400——那表示前端
     *            还没配更新源（`__UPDATE_MANIFEST__` 是空的），这属于「你还没设置」，
     *            不是「服务器坏了」，所以不该是 500。
     */
    @GetMapping("/manifest")
    public Map<String, Object> manifest(@RequestParam(required = false) String url) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "还没有配置更新源地址，无法检查更新。");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "更新源地址不是合法 URL：" + url);
        }
        // 只放行 http/https：file:// 与 jar:// 之类在这个位置没有任何正当用途，
        // 而它们能让一个被篡改的地址读到本机文件
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "更新源只支持 http/https，收到：" + scheme);
        }

        HttpResponse<String> res;
        try {
            res = http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                            .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "检查更新时被中断。");
        } catch (IOException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "连不上更新源：" + e.getMessage());
        }

        if (res.statusCode() / 100 != 2) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "更新源返回 " + res.statusCode() + "。");
        }
        String body = res.body() == null ? "" : res.body();
        if (body.length() > MAX_BYTES) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "更新清单太大了（超过 " + (MAX_BYTES / 1024) + " KB）。");
        }

        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (IOException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "更新清单不是合法 JSON。");
        }
        if (node == null || !node.path("version").isTextual()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "更新清单里没有 version 字段。");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", node.path("version").asText());
        out.put("notes", node.path("notes").isTextual() ? node.path("notes").asText() : "");
        out.put("url", node.path("url").isTextual() ? node.path("url").asText() : "");
        return out;
    }
}
