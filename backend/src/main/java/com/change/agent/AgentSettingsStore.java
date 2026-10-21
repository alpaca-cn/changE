package com.change.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 模型配置的存储：数据目录下的 {@code agent.json}。
 *
 * ## 为什么是文件，不是库里的表
 *
 * 1. **API key 不进 {@code change.db}。** 用户为了排查问题把库发出来时，
 *    训练数据跟着走是可以接受的，key 跟着走不行。分开放，泄露面小一圈。
 * 2. 加一张表要同步改 {@code SchemaTest} 的五层快照（表清单、列、NOT NULL、
 *    索引、约束），那五处是给业务模型用的守卫，不该为一份配置付这个成本。
 *
 * ## 为什么不做加密
 *
 * 密钥只有落在**同一台机器**上才有用，而本机任何进程都能读这个文件——
 * 加密要解决的问题（磁盘被拿走）在桌面应用里并不成立，加了一层反而让人
 * 误以为它比明文更安全。这里如实按明文存，权限交给操作系统。
 *
 * ## 为什么要 read-modify-write 而不是整体覆盖
 *
 * 前端可能只改其中一项（比如只换 model）。若把没传的项覆盖成 null，
 * 用户会看到「配置了但用不了」——所以 update 只写**显式给出**的字段。
 */
@Service
public class AgentSettingsStore {

    /** 配置项。`apiKey` 永远不通过接口回传（见 AgentController）。 */
    public record Settings(String baseUrl, String model, String apiKey) {
        /** 三项齐全才算配好。缺一项时调模型只会拿到一个难懂的 400。 */
        public boolean configured() {
            return notBlank(baseUrl) && notBlank(model) && notBlank(apiKey);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }

        /** 回传给前端时不带 key。 */
        public Map<String, Object> redacted() {
            return Map.of(
                    "configured", configured(),
                    "baseUrl", baseUrl == null ? "" : baseUrl,
                    "model", model == null ? "" : model);
        }
    }

    /** 常见提供方的默认值，只用于「填表单时的占位」，不会被当作已配置。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private static final String FILE_NAME = "agent.json";

    private final Path file;
    private final ObjectMapper json;

    public AgentSettingsStore(@Value("${change.data-dir}") String dataDir, ObjectMapper json) {
        this.file = Path.of(dataDir).resolve(FILE_NAME);
        this.json = json;
    }

    /** 读配置。文件不存在、坏了、被手改乱了，一律当作「没配」——不要把启动搞挂。 */
    public Settings read() {
        if (!Files.isRegularFile(file)) {
            return new Settings(null, null, null);
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Settings s = json.readValue(text, Settings.class);
            return s == null ? new Settings(null, null, null) : s;
        } catch (IOException | RuntimeException e) {
            // 坏文件不抛：用户看到的是「还没配」，比「后端起不来」好处理得多。
            return new Settings(null, null, null);
        }
    }

    /**
     * 只改显式给出的字段。传 null 或空白表示「这项不动」。
     *
     * <p>注意 `apiKey` 传空串的语义是「清除」，而传 null 是「不动」——
     * 这两件事在界面上是不同的操作（清空重填 vs 只改模型），不能混。
     */
    public Settings update(String baseUrl, String model, String apiKey) {
        Settings cur = read();
        Settings next = new Settings(
                baseUrl == null ? cur.baseUrl() : blankToNull(baseUrl),
                model == null ? cur.model() : blankToNull(model),
                apiKey == null ? cur.apiKey() : blankToNull(apiKey));
        write(next);
        return next;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void write(Settings s) {
        try {
            Files.createDirectories(file.getParent());
            // 用 Jackson 的 writer 而不是手拼字符串：key 里出现引号或反斜杠时手拼会写出坏 JSON。
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), s);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入模型配置: " + file, e);
        }
    }

    /** 供测试与「清除配置」用。 */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("无法删除模型配置: " + file, e);
        }
    }

    /** 配置文件的绝对路径，便于报错与排查。 */
    public Path path() {
        return file;
    }
}
