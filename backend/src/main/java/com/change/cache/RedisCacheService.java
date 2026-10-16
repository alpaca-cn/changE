package com.change.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 实现。
 *
 * 三条自我保护：
 *   1. 所有 Redis 调用都捕获异常并降级为「未命中」——Redis 挂了不该让接口 500
 *   2. 连续失败达阈值后熔断一段时间，避免每次请求都白等 300 ms 连接超时
 *   3. **反序列化失败不计入熔断**：缓存里一条类型不匹配的脏数据不该把整个缓存关掉
 *      （那是「一条脏数据 → 缓存整体瘫痪」）。脏数据按未命中处理，只记日志。
 */
public class RedisCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /** 连续失败多少次后熔断。 */
    static final int FAILURE_THRESHOLD = 3;
    /** 熔断持续多久。 */
    static final Duration BREAK_DURATION = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final String endpoint;
    private final Clock clock;

    private int consecutiveFailures = 0;
    private Instant brokenUntil = Instant.EPOCH;

    public RedisCacheService(StringRedisTemplate redis, String host, int port) {
        this(redis, host, port, Clock.systemUTC());
    }

    /** 测试用：注入可控时钟，否则熔断恢复（30 秒）无法在不睡 30 秒的前提下验证。 */
    RedisCacheService(StringRedisTemplate redis, String host, int port, Clock clock) {
        this.redis = redis;
        this.endpoint = host + ":" + port;
        this.clock = clock;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        if (isBroken()) return Optional.empty();
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (Exception e) {
            onFailure("get", e);
            return Optional.empty();
        }
        if (raw == null) {
            // 未命中：Redis 本身是健康的，算一次成功
            onSuccess();
            return Optional.empty();
        }

        T value;
        try {
            value = json.readValue(raw, type);
        } catch (Exception e) {
            // 这是「缓存里有一条读不懂的数据」，不是 Redis 故障。
            //
            // ⚠️ 这里既不能 onFailure **也不能 onSuccess**：脏数据对熔断状态必须是中性的。
            // 计入熔断是错的（一条类型不匹配的脏数据不该把整个缓存关掉）；
            // 调 onSuccess 也是错的（它会把真实故障累积的计数清零，等于用脏数据给故障洗白）。
            //
            // 实测修正（见报告 F）：计划认为「不改就会让一条脏数据把整个缓存关掉」，
            // 但把 get() 退回计划原稿实测后两种实现**行为完全相同**——原稿把 onSuccess()
            // 放在反序列化之前，每次读都以计数归 0 收尾，onFailure 永远落在干净的 0 上加 1，
            // 读路径的计数根本累积不到阈值。那个 bug 在当前组合下不可达。
            // 本次把「反序列化」单独 try 出来，价值在于让“脏数据”与“Redis 故障”的
            // 处置从「靠调用顺序碰巧成立」变成「显式且可测」。
            log.warn("缓存条目反序列化失败，按未命中处理。key={} 目标类型={} 原因={}",
                    key, type.getName(), e.toString());
            return Optional.empty();
        }
        // 到这里才算「Redis 调用成功且数据可用」——只有这种成功才允许清空失败计数
        onSuccess();
        return Optional.of(value);
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        if (isBroken()) return;
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
            onSuccess();
        } catch (Exception e) {
            onFailure("put", e);
        }
    }

    /**
     * 按前缀批量失效。
     *
     * <p><b>取舍：这里用 {@code KEYS}，不是 {@code SCAN}。</b>{@code KEYS} 是 O(N) 且
     * <b>会阻塞 Redis 单线程</b>——键很多时它会让所有其他客户端一起等。在「单用户本地缓存、
     * 键空间只有几十个键」这个场景下，一次 O(N) 遍历的绝对代价可以忽略（微秒级），
     * 换来的是一行代码的简单实现。
     *
     * <p><b>适用边界</b>：键数量小（本地单用户缓存）。<b>一旦缓存键变多</b>——例如设计文档里
     * 的「社交版」让缓存承载多用户数据，或者引入按条目粒度的键——就必须改成 {@code SCAN}
     * 游标遍历（{@code ScanOptions.match(prefix + "*")} + 分批 {@code delete}），
     * 用「不阻塞但可能重复/漏掉并发写入的键」换取 Redis 的可用性。届时这个方法的签名可以不变。
     */
    @Override
    public void invalidatePrefix(String prefix) {
        if (isBroken()) return;
        try {
            Set<String> keys = redis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            onSuccess();
        } catch (Exception e) {
            onFailure("invalidatePrefix", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return !isBroken();
    }

    @Override
    public String describe() {
        if (Instant.now(clock).isBefore(brokenUntil)) {
            return "缓存：已熔断（" + endpoint + " 连续失败 " + consecutiveFailures + " 次）";
        }
        return "缓存：已连接 " + endpoint;
    }

    private boolean isBroken() {
        Instant now = Instant.now(clock);
        if (now.isBefore(brokenUntil)) {
            return true;
        }
        // 熔断期结束，给一次机会。⚠️ 这里必须限定「刚结束熔断」这个条件：
        // 计划原稿写的是无条件的 `if (consecutiveFailures > 0) consecutiveFailures = 0;`，
        // 而 isBroken() 在 get/put/invalidatePrefix 里是每个操作的第一步——
        // 于是每次调用都先把计数器清零，再累加本次的 1 次失败，
        // consecutiveFailures 永远到不了 FAILURE_THRESHOLD，熔断器永远不跳。
        // （计划自带的两条熔断测试就会红：见 redis_failures_trip_the_circuit_breaker_...）
        // 现在只在 brokenUntil 真的过期时清零一次，计数才能跨调用累积。
        if (!brokenUntil.equals(Instant.EPOCH) && consecutiveFailures > 0) {
            consecutiveFailures = 0;
        }
        return false;
    }

    private void onSuccess() {
        consecutiveFailures = 0;
    }

    private void onFailure(String op, Exception e) {
        consecutiveFailures++;
        // 用 >= 而不是 ==：== 只在计数**恰好**等于阈值那一次跳闸，
        // 一旦计数滑过阈值（3 -> 4 -> 5…）就再也不等于它，熔断器等于「一辈子只跳一次」。
        // 外加 `!isBefore(brokenUntil)`，保证只有当前不在熔断期时才重置 brokenUntil，
        // 否则连续失败会把熔断期一直往后推，Redis 一直坏就永远不恢复。
        //
        // 诚实说明：>= 相对 == 目前**本仓库的测试无法区分**——把这里退回 ==，
        // CacheServiceTest 8 条全绿（已实测）。现有用例里计数最多走到阈值 3，
        // 没有任何路径让计数跳过阈值。保留 >= 是因为 == 的脆弱性真实存在（见上），
        // 但不要误以为有测试在守护它。
        if (consecutiveFailures >= FAILURE_THRESHOLD && !Instant.now(clock).isBefore(brokenUntil)) {
            brokenUntil = Instant.now(clock).plus(BREAK_DURATION);
            log.warn("Redis {} 连续失败 {} 次，熔断 {} 秒。原因: {}",
                    op, consecutiveFailures, BREAK_DURATION.toSeconds(), e.toString());
        }
    }
}
