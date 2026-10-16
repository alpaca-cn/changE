package com.change.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 降级实现：永远未命中，永远不抛异常。
 *
 * 什么时候用它：
 *   - Redis 配置为关闭
 *   - 启动时探测不到 Redis
 *   - 连续失败触发了熔断
 */
public class NoOpCacheService implements CacheService {

    private final String reason;

    public NoOpCacheService() {
        this("未启用");
    }

    public NoOpCacheService(String reason) {
        this.reason = reason;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return Optional.empty();
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        // 故意什么都不做
    }

    @Override
    public void invalidatePrefix(String prefix) {
        // 故意什么都不做
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String describe() {
        return "缓存：未启用（" + reason + "）";
    }
}
