package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文加载 + 数据目录约定的验收。
 *
 * 这里刻意保持默认的 MOCK web 环境（不起真容器），端口协商与监听地址由 Task 10
 * 用 RANDOM_PORT / 显式绑定覆盖；本测试只关心「换个工作目录也不换库」这条约定。
 */
@SpringBootTest
class ChangeApplicationTests {

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Test
    void contextLoads() {
        // 能加载即通过。Redis 不可达时也必须通过——这是降级的验收点。
    }

    @Test
    void data_directory_is_absolute_created_and_usable() throws Exception {
        Path dir = Path.of(env.getProperty("change.data-dir"));

        assertThat(dir.isAbsolute())
                .as("数据目录必须是绝对路径，否则换个工作目录就换一个库")
                .isTrue();
        assertThat(Files.isDirectory(dir))
                .as("数据目录必须被自动创建（SQLite 不会建父目录）")
                .isTrue();

        try (Connection c = dataSource.getConnection()) {
            assertThat(c.isValid(1)).as("连接必须真的可用").isTrue();
        }
    }
}
