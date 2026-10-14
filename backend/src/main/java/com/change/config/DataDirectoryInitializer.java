package com.change.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 在 Flyway 与 DataSource 之前把数据目录落成绝对路径并创建出来。
 *
 * 为什么必须这么早：SQLite 不会自动创建父目录，目录不存在时
 * Flyway 会在建连阶段直接抛 SQLITE_CANTOPEN，Hibernate 根本轮不到执行。
 *
 * 为什么用 EnvironmentPostProcessor 而不是普通 Bean：它是环境准备阶段就跑的，
 * 早于任何 Bean 实例化，也早于 @SpringBootTest 的环境装配——main() 与测试都能覆盖到。
 *
 * 为什么要把规范化结果回写环境：目录的创建者与消费者如果各自解释同一个字符串，
 * 就会出现「目录建在这里、库开在那里」。典型例子是 CHANGE_DATA_DIR 带尾部反斜杠
 * （Windows 上很自然的写法），Path.of() 会规范化掉，而 SQLite 拿到的是原串，
 * 结果就是 SQLITE_CANTOPEN。回写成同一个绝对路径后，数据源 URL、Flyway、
 * 将来的照片根目录消费的都是同一个值。
 */
public class DataDirectoryInitializer implements EnvironmentPostProcessor {

    /** 规范化后回写的属性源名，放在最前面以确保覆盖原占位符值。 */
    static final String PROPERTY_SOURCE_NAME = "changeDataDir";

    private static final String DATA_DIR_PROPERTY = "change.data-dir";
    private static final String APPDATA_PROPERTY = "APPDATA";

    /** 与 application.yml 里 change.data-dir 的兜底保持一致的子目录名。 */
    private static final String SUBDIRECTORY = "changE";

    private final DeferredLogFactory logFactory;

    /**
     * 日志用 DeferredLogFactory 注入：本类在日志系统就绪前后都有机会执行，
     * 用延迟日志可以保证这行启动信息在任何环境下都不会被丢掉。
     */
    public DataDirectoryInitializer(DeferredLogFactory logFactory) {
        this.logFactory = logFactory;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        var log = logFactory.getLog(DataDirectoryInitializer.class);

        String configured = env.getProperty(DATA_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "数据目录未配置或为空，请显式设置 CHANGE_DATA_DIR 指向一个真实目录。");
        }

        Path path = Path.of(configured).toAbsolutePath().normalize();

        // application.yml 里的多级兜底只能处理「变量不存在」；APPDATA 存在但为空串时，
        // ${APPDATA:...} 会解析成空串（Spring 的默认值只在变量缺失时生效），
        // 拼出来就是 "/changE"，在 Windows 上落到「当前盘:\changE」。
        // 这一层按 user.home 再兜一次。
        if (isSuspiciousRootPath(configured, path)) {
            Path fallback = userHomeAppData();
            if (fallback != null) {
                log.warn("数据目录 " + configured + " 落到了盘根（" + APPDATA_PROPERTY
                        + " 存在但为空串时会这样），已改用 " + fallback + "；"
                        + "如需固定位置请显式设置 CHANGE_DATA_DIR。");
                path = fallback.resolve(SUBDIRECTORY).toAbsolutePath().normalize();
            }
        }

        // 兜底之后仍不合格（连 user.home 都拿不到）：宁可启动失败，也不要把库悄悄写进盘根。
        if (isSuspiciousRootPath(configured, path)) {
            throw new IllegalStateException(
                    "数据目录解析成了盘根下的单个目录: " + path + "（change.data-dir 原始值: " + configured
                            + "）。这通常是因为 CHANGE_DATA_DIR 或 APPDATA 被设成了空串，"
                            + "请显式设置 CHANGE_DATA_DIR 指向一个真实目录。");
        }

        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法创建数据目录: " + path + "，请检查 CHANGE_DATA_DIR 是否指向可写位置。", e);
        }

        // 让下游（数据源 URL、Flyway、将来的照片根目录）消费同一个绝对路径。
        env.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(DATA_DIR_PROPERTY, path.toString())));

        // 这一行在 ApplicationPreparedEvent 时由 EnvironmentPostProcessorApplicationListener 重放，
        // 所以它真的会出现在启动日志里，而不是被丢掉。
        log.info("数据目录: " + path);
    }

    /**
     * 是否是「盘根下的单个目录」：{@code E:\changE}、{@code /changE} 这类。
     *
     * 判据刻意看原始串而不是只看 Path：空串 APPDATA 拼出来的 "/changE" 在 Windows 上
     * 被 JDK 当作「相对路径」解析（{@code Path.of("/changE").isAbsolute()} 是 false），
     * 但 {@code toAbsolutePath()} 又会把它挂到当前盘根上，光看 Path 很容易漏掉。
     * 合法的数据目录（如 {@code E:\devtools\cache\dt\changE}）前一跳都在，不会误伤。
     */
    private boolean isSuspiciousRootPath(String configured, Path path) {
        if (path.getParent() == null) {
            return true;                                    // 真的是根：E:\ 或 /
        }
        boolean rootedInput = configured.startsWith("/") || configured.startsWith("\\");
        return rootedInput && path.getParent().getParent() == null;   // 根 + 一层目录
    }

    /**
     * user.home 下的漫游目录，与 application.yml 的兜底同源。
     * user.home 也拿不到时返回 null，交给下面的根路径检查报错。
     */
    private Path userHomeAppData() {
        String userHome = System.getProperty("user.home");
        return (userHome == null || userHome.isBlank())
                ? null
                : Path.of(userHome, "AppData", "Roaming");
    }
}
