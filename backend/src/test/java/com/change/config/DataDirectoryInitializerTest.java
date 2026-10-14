package com.change.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接对 DataDirectoryInitializer 的行为下断言，而不是透过 @SpringBootTest 看回写后的结果。
 *
 * 为什么需要这个测试：ChangeApplicationTests 里那条断言（读到的是绝对路径）是恒真的，
 * 因为初始化器在 Spring 读取属性之前就把值回写成了绝对路径——它永远看不到「配置被改坏」，
 * 真正被证明的只是「数据目录可用」。本类补上三个盲区：
 *   - 回写与规范化这条动作本身（删掉回写 / 删掉 normalize 立刻变红）；
 *   - 盘根兜底真的兜住了（空串 APPDATA 那条路径）；
 *   - 发布的默认值不是工作目录相对路径（只能靠读未经回写的原始配置来测）。
 *
 * 全部是纯单元测试：手搓 MockEnvironment、手搓 DeferredLogFactory，不起 Spring 上下文。
 */
class DataDirectoryInitializerTest {

    /**
     * 与 spring.factories 注册的构造函数一致：真实类依赖 DeferredLogFactory，
     * 就用 DeferredLog 直接顶上（DeferredLog 无参构造 + getLog(Class) 即可用），
     * 不为迁就测试去改生产代码的构造函数。
     */
    private static DataDirectoryInitializer initializer() {
        return new DataDirectoryInitializer(clazz -> new DeferredLog());
    }

    @Test
    void relative_input_is_rewritten_to_an_absolute_normalized_path() throws Exception {
        String configured = "./target/rel/";
        MockEnvironment env = new MockEnvironment();
        env.setProperty("change.data-dir", configured);

        initializer().postProcessEnvironment(env, null);

        // 这里断言的是「配置 → 绝对路径」的转换结果本身，而不是它是否可用。
        // 删掉回写那两行，或者去掉 .normalize()，这条立刻变红。
        assertThat(env.getProperty("change.data-dir"))
                .as("相对路径必须被回写成同一个绝对路径（下游的数据源 URL 用的是这个值）")
                .isEqualTo(Path.of(configured).toAbsolutePath().normalize().toString());
        assertThat(Files.isDirectory(Path.of(env.getProperty("change.data-dir"))))
                .as("回写的同时目录必须已经建好（SQLite 不会建父目录）")
                .isTrue();
    }

    @Test
    void root_level_path_falls_back_under_user_home() throws Exception {
        // 这条会真的 createDirectories，所以把 user.home 指到 target 下的临时目录，
        // 绝不碰真实用户数据目录（C:\Users\<me>\AppData\Roaming）。
        Path tempHome = Path.of("target", "user-home-probe").toAbsolutePath().normalize();
        deleteRecursively(tempHome);
        Files.createDirectories(tempHome);

        String previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            String configured = "/changE";
            MockEnvironment env = new MockEnvironment();
            env.setProperty("change.data-dir", configured);

            initializer().postProcessEnvironment(env, null);

            Path result = Path.of(env.getProperty("change.data-dir"));
            Path rootLevelCandidate = Path.of(configured).toAbsolutePath().normalize();

            // 复现前提：这个输入解析后确实只剩「盘根 + 一层」，否则这条测试就没有在测兜底。
            assertThat(rootLevelCandidate.getNameCount())
                    .as("复现前提：%s 应当解析成盘根下的一层目录", configured)
                    .isEqualTo(1);

            assertThat(result)
                    .as("盘根下的单个目录必须被兜底掉，而不是落在盘根")
                    .isNotEqualTo(rootLevelCandidate);
            assertThat(result.getNameCount())
                    .as("兜底结果必须至少是盘根下两层（盘根 + 一层正是被拦的形状）")
                    .isGreaterThan(1);
            assertThat(result.startsWith(tempHome.toAbsolutePath().normalize()))
                    .as("兜底应落在 user.home 下（本测试已把 user.home 指向临时目录）")
                    .isTrue();
            assertThat(Files.isDirectory(result)).as("兜底目录必须被创建").isTrue();
            assertThat(Files.exists(rootLevelCandidate))
                    .as("兜底生效时不应把目录建到盘根: %s", rootLevelCandidate)
                    .isFalse();
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
            deleteRecursively(tempHome);
        }
    }

    @Test
    void published_default_data_dir_is_not_a_working_directory_relative_path() throws Exception {
        // 唯一能防住「默认值被改回相对路径」的方式：读未经初始化器回写的原始配置。
        // 走 Spring 环境拿到的值一定已经是绝对路径，看不出默认值本身写的是什么。
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(sources).as("application.yml 必须能被解析出一个属性源").isNotEmpty();

        Object raw = null;
        for (PropertySource<?> source : sources) {
            raw = source.getProperty("change.data-dir");
            if (raw != null) {
                break;
            }
        }

        assertThat(raw)
                .as("application.yml 里必须有 change.data-dir 的默认值")
                .isNotNull();
        String rawValue = raw.toString();
        assertThat(rawValue)
                .as("默认值不能是工作目录相对路径（./ 开头），否则换个工作目录就换一个库；实际值: %s", rawValue)
                .doesNotStartWith("./")
                .doesNotStartWith(".\\");
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
