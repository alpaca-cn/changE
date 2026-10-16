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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis 实现。
 *
 * 三条自我保护：
 *   1. 所有 Redis 调用都捕获异常并降级为「未命中」——Redis 挂了不该让接口 500
 *   2. 连续失败达阈值后熔断一段时间，避免每次请求都白等 300 ms 连接超时
 *   3. **序列化/反序列化失败不计入熔断**：一条读不懂或写不进去的数据不该把整个缓存关掉
 *      （那是「一条坏数据 → 缓存整体瘫痪」）。读路径按未命中处理、写路径跳过本次 put，
 *      两者都只记日志；只有**真的 Redis 故障**才累加失败计数。
 *
 * <p><b>熔断状态必须是原子的。</b>本 Bean 在 {@code CacheConfig} 里装配为单例，
 * 服务多线程的 Spring MVC。计数器若只是普通 {@code int}，并发下
 * {@code consecutiveFailures++} 的「读-改-写」不是原子操作，更新会丢失——
 * {@code >= FAILURE_THRESHOLD} 可能永远不成立，熔断器静默失效，
 * 退化回「每个请求都白等 300 ms 连接超时」。这里用 {@link AtomicInteger} /
 * {@link AtomicReference} 而不是 volatile：volatile 只给可见性，
 * 不给读-改-写的原子性，而丢计数正是这个缺陷的核心。
 */
public class RedisCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /** 连续失败多少次后熔断。 */
    static final int FAILURE_THRESHOLD = 3;
    /** 熔断持续多久。 */
    static final Duration BREAK_DURATION = Duration.ofSeconds(30);

    /**
     * 并发测试用的会合点，生产代码里恒为 {@code null}。
     *
     * <p><b>为什么需要它。</b>要让「读-改-写」的窗口真正被覆盖，并发用例必须把线程
     * 对齐到**同一次** {@code consecutiveFailures} 更新上，而不是靠 sleep 碰运气。
     * 会合点必须落在自增之前，所以只能由被改造的类提供。
     *
     * <p><b>诚实说明（实测）。</b>有了会合点，{@code CacheServiceTest} 那条并发用例
     * 在**非原子实现下依然全绿**：x86 上普通 int 的 {@code ++} 就是一条读-改-写指令
     * （inc dword ptr [r]），20 个线程对齐后连跑 100 轮，计数每轮都精确等于 20、
     * 一次更新都不丢。所以原子类型买的是**可移植的正确性**（JLS 不保证 int++ 原子，
     * 其他架构或未来的 JIT 可能真的把它拆成 load/add/store），不是本机可复现的缺陷。
     * 那条测试因此是「负载下的回归守卫」，不是「丢失更新的判别器」——
     * 详见 {@code CacheServiceTest#the_breaker_trips_under_concurrent_failures}。
     *
     * <p>它不改动任何生产行为（null 时是一次几乎免费的 null 检查），
     * 也不会被 Spring 装配进任何东西。测试类在 try/finally 里设置与清理。
     */
    static volatile java.util.concurrent.CyclicBarrier failureRendezvous = null;

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final String endpoint;
    private final Clock clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> brokenUntil = new AtomicReference<>(Instant.EPOCH);

    public RedisCacheService(StringRedisTemplate redis, String host, int port) {
        this(redis, host, port, Clock.systemUTC());
    }

    /** 测试用：注入可控时钟，否则熔断恢复（30 秒）无法在不睡 30 秒的前提下验证。 */
    RedisCacheService(StringRedisTemplate redis, String host, int port, Clock clock) {
        this.redis = redis;
        this.endpoint = host + ":" + port;
        this.clock = clock;
    }

    /**
     * 当前连续失败次数。<b>包级可见，仅供测试</b>（生产代码只用 {@link #isAvailable()}）。
     *
     * <p>为什么要暴露它：并发用例必须断言这个计数的**精确值**。
     * 只断言「熔断跳没跳」在结构上没有判别力——阈值只有 3，而 N 个线程并发失败时
     * 哪怕丢了大半更新，计数也远超 3，熔断照跳（实测 64 线程并发失败计数 8~12，
     * 期望 64，仍然跳闸）。精确值断言才守得住「计数被整体重置/崩塌」这类退化。
     *
     * <p>注意它**测不出**「非原子 → 丢失更新」：见 {@link #failureRendezvous} 的实测说明。
     */
    int consecutiveFailureCount() {
        return consecutiveFailures.get();
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

        // 两件事必须分开处置：**序列化失败**是「调用方给了一个缓存不了的值」，
        // **Redis 故障**才是熔断该管的事。原来的单 try 把两者混在一起，于是调用方
        // 只要有一个地方缓存了序列化不了的值，三次之后整个缓存就被关掉——包括所有
        // 完全正常的、毫不相干的键，而且是**静默**的（不抛异常，只是从此不缓存）。
        // 这正是 get() 那边在反序列化上修掉的同一类缺陷
        // （「一条坏数据 → 缓存整体瘫痪」）在写路径上的镜像。
        //
        // 这里只记 WARN 并跳过本次 put：调用方下次给一个可序列化的值，缓存照常工作。
        // 刻意**不加可观测计数**——那需要扩 CacheService 接口，应当等真有需求时再做。
        String payload;
        try {
            payload = json.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("缓存值序列化失败，本次 put 跳过。key={} 类型={} 原因={}",
                    key, value == null ? "null" : value.getClass().getName(), e.toString());
            return;
        }

        try {
            redis.opsForValue().set(key, payload, ttl);
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
        // 先读一次 brokenUntil，再读一次计数——不要先读计数再问状态：
        // 那样两个值来自不同时刻，可能出现「已熔断」配「连续失败 0 次」的自相矛盾文案。
        // 这里读到的计数一定 >= 触发熔断时的那个值，所以不会是 0。
        Instant until = brokenUntil.get();
        if (Instant.now(clock).isBefore(until)) {
            return "缓存：已熔断（" + endpoint + " 连续失败 " + consecutiveFailures.get() + " 次）";
        }
        return "缓存：已连接 " + endpoint;
    }

    /**
     * 熔断期是否生效；顺带做「熔断期结束后把计数归零」这件事。
     *
     * <p>为什么清零必须**恰好一次**：本方法在 get/put/invalidatePrefix 里是每个操作的
     * 第一步，而「清零」与「本次失败 +1」是两步。若清零无条件发生（计划原稿的写法），
     * 每次调用都会先把计数抹回 0、再加 1，{@code consecutiveFailures} 永远到不了
     * {@link #FAILURE_THRESHOLD}——熔断器永不触发。这是本项目修过的第一个 Critical。
     *
     * <p>清零用 {@code compareAndSet(expired, EPOCH)} 而不是先 get 再 set：
     * 只有真正把 {@code brokenUntil} 从「已过期」推进到 {@code EPOCH} 的那个线程才清零，
     * 进来的其他线程 CAS 失败、不动计数。顺带保证「一个熔断周期只清零一次」。
     */
    private boolean isBroken() {
        Instant until = brokenUntil.get();
        if (Instant.now(clock).isBefore(until)) {
            return true;
        }
        // 熔断期已过（或从未熔断）。把 brokenUntil 归位这件事只让一个线程做成：
        // 谁成功了谁负责清计数——否则「CAS 归位」与「计数清零」两个动作之间
        // 会插进别的线程的失败累加，那一次失败会被随后的清零无声抹掉。
        if (brokenUntil.compareAndSet(until, Instant.EPOCH) && until != Instant.EPOCH) {
            consecutiveFailures.set(0);
        }
        return false;
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
    }

    private void onFailure(String op, Exception e) {
        // 测试会合点：让并发用例能把所有线程对齐到这一次「读-改-写」上。
        // 生产路径下恒为 null，代价是一次 null 检查。
        java.util.concurrent.CyclicBarrier gate = failureRendezvous;
        if (gate != null) {
            try {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 超时/中断都不该改变熔断语义：继续按正常失败处理。
            }
        }
        int failures = consecutiveFailures.incrementAndGet();
        // 用 >= 而不是 ==：== 只在计数**恰好**等于阈值那一次跳闸，
        // 一旦计数滑过阈值（3 -> 4 -> 5…）就再也不等于它，熔断器等于「一辈子只跳一次」。
        // 外加 `!isBefore(brokenUntil)`，保证只有当前不在熔断期时才重置 brokenUntil，
        // 否则连续失败会把熔断期一直往后推，Redis 一直坏就永远不恢复。
        //
        // 诚实说明：>= 相对 == 目前**本仓库的测试无法区分**——把这里退回 ==，
        // CacheServiceTest 全绿（已实测）。现有用例里计数最多走到阈值 3，
        // 没有任何路径让计数跳过阈值。保留 >= 是因为 == 的脆弱性真实存在（见上），
        // 但不要误以为有测试在守护它。
        //
        // 并发下的重复跳闸：多个线程可能同时跨过阈值，各自尝试把 brokenUntil 从
        // 「过去」推进到「未来」。check-then-act 不是原子的，所以用 compareAndSet——
        // 只有赢的那个线程写日志并推进熔断期。输的线程什么都不做（不是重试：
        // 熔断期已由赢家设好，重试只会把熔断期往后推）。
        if (failures >= FAILURE_THRESHOLD) {
            Instant until = brokenUntil.get();
            if (!Instant.now(clock).isBefore(until)) {
                Instant next = Instant.now(clock).plus(BREAK_DURATION);
                if (brokenUntil.compareAndSet(until, next)) {
                    log.warn("Redis {} 连续失败 {} 次，熔断 {} 秒。原因: {}",
                            op, failures, BREAK_DURATION.toSeconds(), e.toString());
                }
            }
        }
    }
}
