package com.change.config;

import com.change.cache.CacheService;
import com.change.cache.NoOpCacheService;
import com.change.cache.RedisCacheService;
import com.change.web.HealthController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存装配。
 *
 * 设计原则：**没有 Redis 时应用必须完全正常工作**。
 * 所以这里不是「连不上就启动失败」，而是「连不上就换成 NoOp，并在设置页说清楚」。
 */
@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public CacheService cacheService(
            // 默认值与 application.yml 里的 ${CHANGE_REDIS_ENABLED:false} 保持一致：
            // 这是分发给他人的客户端，大多数使用者的机器上没有 Redis。
            // 两处默认值不一致的话，单独跑本类的测试或改配置来源时会走另一条分支，
            // 而且是**静默**地走——正是本项目一路在消灭的那类失效。
            @Value("${change.redis.enabled:false}") boolean enabled,
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            ObjectProvider<StringRedisTemplate> templateProvider) {

        if (!enabled) {
            log.info("缓存：配置为关闭，使用 NoOp");
            return new NoOpCacheService("配置为关闭");
        }

        StringRedisTemplate t = templateProvider.getIfAvailable();
        if (t == null) {
            return new NoOpCacheService("未装配 Redis 客户端");
        }

        try {
            // 探测一次：连不上就立刻降级，不要让第一个请求去承担 300ms 超时
            t.execute((RedisCallback<String>) conn -> {
                conn.ping();
                return "PONG";
            });
            log.info("缓存：已连接 {}:{}", host, port);
            return new RedisCacheService(t, host, port);
        } catch (Exception e) {
            log.info("缓存：连不上 {}:{}，本次运行使用 NoOp（原因: {}）", host, port, e.toString());
            return new NoOpCacheService("连不上 " + host + ":" + port);
        }
    }

    @Bean
    public HealthController.CacheStatus cacheStatus(CacheService cache) {
        return cache::describe;
    }
}
