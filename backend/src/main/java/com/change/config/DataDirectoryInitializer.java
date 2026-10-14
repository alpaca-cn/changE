package com.change.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 在 Flyway 与 DataSource 之前创建数据目录。
 *
 * 为什么必须这么早：SQLite 不会自动创建父目录，目录不存在时
 * Flyway 会在建连阶段直接抛 SQLITE_CANTOPEN，Hibernate 根本轮不到执行。
 *
 * 为什么用 EnvironmentPostProcessor 而不是普通 Bean：它是环境准备阶段就跑的，
 * 早于任何 Bean 实例化，也早于 @SpringBootTest 的环境装配——main() 与测试都能覆盖到。
 */
public class DataDirectoryInitializer implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String dir = env.getProperty("change.data-dir");
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path path = Path.of(dir);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法创建数据目录: " + path.toAbsolutePath(), e);
        }
    }
}
