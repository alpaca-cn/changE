package com.change.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时配置的「写了 ≠ 生效」哨兵。
 *
 * application.yml 里写了 spring.data.redis.timeout: 500ms 与 connect-timeout: 300ms，
 * 但这两个值只有真的落到 Lettuce 上才有意义。历史背景：Task 1 的审查确认过它们生效，
 * 但那时的 CacheConfig 与本任务的完全不同，所以这里重新确认一次——
 * CacheConfig 只做启动探测，不重建连接工厂，但任何「顺手改 Redis 装配」的改动
 * 都可能顺手改坏这两个数字。
 *
 * 为什么断言具体数值：这两个数字是「Redis 不可达时不要拖垮启动/请求」的唯一保证。
 */
@SpringBootTest
class RedisTimeoutConfigTest {

    @Autowired
    LettuceConnectionFactory connectionFactory;

    @Test
    void the_command_timeout_reaches_lettuce() {
        // spring.data.redis.timeout -> LettuceClientConfiguration.commandTimeout
        Duration commandTimeout = connectionFactory.getClientConfiguration().getCommandTimeout();

        assertThat(commandTimeout)
                .as("spring.data.redis.timeout: 500ms 必须落到 Lettuce 的命令超时上")
                .isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void the_connect_timeout_reaches_lettuce_socket_options() throws Exception {
        // spring.data.redis.connect-timeout 不挂在 LettuceClientConfiguration 的公开 getter 上，
        // 它在 io.lettuce.core.ClientOptions(SocketOptions) 里，而 LettuceClientConfiguration.clientOptions
        // 是私有字段——所以这里用反射取。取值本身是确定的（同一个对象），不是时序敏感的东西。
        ClientOptions clientOptions = readClientOptions(connectionFactory);

        assertThat(clientOptions.getSocketOptions().getConnectTimeout())
                .as("spring.data.redis.connect-timeout: 300ms 必须落到 Lettuce 的 socket 连接超时上")
                .isEqualTo(Duration.ofMillis(300));
    }

    private static ClientOptions readClientOptions(LettuceConnectionFactory factory) throws Exception {
        Object config = factory.getClientConfiguration();
        Field f = config.getClass().getDeclaredField("clientOptions");
        f.setAccessible(true);
        Object raw = f.get(config);
        // 该字段是 Optional<ClientOptions>：空表示「用 Lettuce 默认」，这里不做假设。
        if (raw instanceof java.util.Optional<?> opt) {
            assertThat(opt).as("Lettuce 的 ClientOptions 必须是显式装配的，否则连接超时无从谈起").isPresent();
            return (ClientOptions) opt.get();
        }
        return (ClientOptions) raw;
    }
}
