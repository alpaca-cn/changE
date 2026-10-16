package com.change.cache;

import com.change.config.CacheConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 「连不上 Redis 时降级」这条路径的守卫。
 *
 * <h2>为什么必须单独存在</h2>
 *
 * {@code application.yml} 把 {@code change.redis.enabled} 的默认值设成了 {@code false}
 * ——这是要分发给他人的客户端，绝大多数使用者的机器上没有 Redis，开着会让每位朋友的
 * 首次启动白等一次连不上的探测。默认值一改，{@link CacheConfig} 里
 * 「enabled=true 但连接失败 → NoOp」这条分支就**没有任何测试再走到它**了：
 * 其余上下文测试都跑在默认配置下，直接从第一个 {@code if (!enabled)} 返回。
 *
 * <p>而这条分支正是 Task 11 的核心验收标准（「Redis 挂了不影响业务，且如实报告」）。
 * 所以这里显式把 enabled 打开、并把地址指到一个必然连不上的端口
 * （{@code 127.0.0.1:1}：TCP 上没有任何东西监听，连接被立即拒绝，不是超时，
 * 所以这条测试不需要等 300ms 的连接超时，也不依赖任何外部服务）。
 *
 * <p>换掉这个测试不是「删一条测试」，是把一条验收标准变成零覆盖——
 * 如果将来要合并它，请把 enabled=true 挪进别的上下文测试里。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "change.redis.enabled=true",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1"
})
class CacheDegradationTest {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    CacheService cache;

    @Test
    void an_unreachable_redis_degrades_to_noop_instead_of_failing_startup() {
        // 上下文能起来本身就是第一条验收：Redis 不是硬依赖。
        // 第二条是装配结果：真去探测了一次、连不上、于是换成 NoOp。
        assertThat(cache)
                .as("enabled=true 但连不上时必须降级成 NoOpCacheService，而不是抛异常或硬用 Redis")
                .isInstanceOf(NoOpCacheService.class);
        assertThat(cache.isAvailable()).isFalse();
    }

    @Test
    void the_degradation_reason_is_reported_and_names_the_endpoint_it_could_not_reach() {
        // 「为什么降级」必须能被用户看到，且必须点出它试过的地址——
        // 否则使用者只会看到「缓存：未启用」而不知道是不是自己配错了。
        assertThat(cache.describe())
                .startsWith("缓存：未启用")
                .contains("127.0.0.1:1");
    }

    @Test
    void business_calls_never_throw_while_redis_is_down() {
        // 缓存抽象的核心契约：任何方法都不得向上抛异常。
        // 用 NoOp 兜底的意义就在这里——业务代码不该为「Redis 关机了」写 try/catch。
        assertThatCode(() -> {
            assertThat(cache.get("change:any", String.class)).isEmpty();
            cache.put("change:any", "value", java.time.Duration.ofMinutes(5));
            cache.invalidatePrefix("change:");
        }).doesNotThrowAnyException();
    }
}
