package com.change.spike;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 冒烟测试：验证以下四件事在同一次启动里能否共存。
 *   1. JDK 25 能跑 Spring Boot 3.5.16
 *   2. Hibernate 社区方言能连 SQLite 并建表
 *   3. LangChain4j 能加载并构造出 ChatModel（不发起网络调用）
 *   4. Redis 客户端能装配，但 Redis 不在时也能启动（降级路径）
 */
@SpringBootApplication
public class SpikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpikeApplication.class, args);
    }

    @Bean
    CommandLineRunner verify(DataSource ds) {
        return args -> {
            System.out.println("==> java.version = " + System.getProperty("java.version"));

            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS probe(id INTEGER PRIMARY KEY, name TEXT)");
                st.execute("INSERT INTO probe(name) VALUES ('sqlite-ok')");
                try (ResultSet rs = st.executeQuery("SELECT name FROM probe LIMIT 1")) {
                    rs.next();
                    System.out.println("==> sqlite     = " + rs.getString(1));
                }
                System.out.println("==> jdbc.url   = " + c.getMetaData().getURL());
                System.out.println("==> jdbc.driver= " + c.getMetaData().getDriverName()
                        + " " + c.getMetaData().getDriverVersion());
            }

            // 只构造对象，不发请求（没有 key 也能构造）
            ChatModel model = OpenAiChatModel.builder()
                    .baseUrl("https://api.deepseek.com/v1")
                    .apiKey("not-a-real-key")
                    .modelName("deepseek-chat")
                    .build();
            System.out.println("==> langchain4j= " + model.getClass().getName());

            System.out.println("==> RESULT: ALL_OK");
        };
    }
}
