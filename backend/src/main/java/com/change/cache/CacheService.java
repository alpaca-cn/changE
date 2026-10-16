package com.change.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存抽象。
 *
 * 关键约定：任何方法都不得向上抛异常。Redis 不可用是常态
 * （它跑在一台经常关机的虚拟机上），业务代码不该为此写 try/catch。
 */
public interface CacheService {

    <T> Optional<T> get(String key, Class<T> type);

    void put(String key, Object value, Duration ttl);

    /** 按前缀批量失效。写操作后调用，对应设计文档「写入即失效」。 */
    void invalidatePrefix(String prefix);

    boolean isAvailable();

    /** 给设置页显示的人话描述，例如「已连接 192.168.100.128:6379」。 */
    String describe();
}
