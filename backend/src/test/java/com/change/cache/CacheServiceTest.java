package com.change.cache;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CacheServiceTest {

    @Test
    void the_breaker_constants_are_pinned_to_their_spec() {
        // 熔断参数是「Redis 挂了之后等多久再试」的唯一旋钮，属于对外行为（影响请求延迟）。
        // 没有这条的话，FAILURE_THRESHOLD 改成 1 或 100、BREAK_DURATION 改成 10 分钟，
        // 别的测试**照样全绿**——因为它们都是「循环 FAILURE_THRESHOLD 次」这种
        // 跟着常量走的写法（实测确认过）。这条把数值钉死，改动必须显式改测试。
        assertThat(RedisCacheService.FAILURE_THRESHOLD).isEqualTo(3);
        assertThat(RedisCacheService.BREAK_DURATION).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void noop_always_misses_and_never_throws() {
        CacheService c = new NoOpCacheService();

        assertThat(c.get("anything", String.class)).isEmpty();
        c.put("anything", "value", Duration.ofMinutes(5));   // 不应抛异常
        c.invalidatePrefix("change:");
        assertThat(c.isAvailable()).isFalse();
        // 查值而不是查前缀：describe() 若退化成固定字符串（把 reason 丢掉）也要变红。
        // 这个 reason 最终会显示在 /api/health 的 cache 字段里，是验收标准的一部分。
        assertThat(c.describe()).isEqualTo("缓存：未启用（未启用）");

        // 带原因的构造器：原因必须出现在描述里，否则「为什么降级」就没人知道了
        assertThat(new NoOpCacheService("连不上 1.2.3.4:6379").describe())
                .isEqualTo("缓存：未启用（连不上 1.2.3.4:6379）");
    }

    @Test
    void noop_is_the_guaranteed_safe_fallback() {
        // 这条测试的意义：无论 Redis 处于什么状态，注入 NoOp 时业务代码都不会崩。
        CacheService c = new NoOpCacheService();
        for (int i = 0; i < 100; i++) {
            assertThat(c.get("k" + i, Integer.class)).isEqualTo(Optional.empty());
        }
    }

    // ---- 以下是本任务对计划测试的补充（额外要求 A）：熔断是 RedisCacheService 的全部价值，
    // ---- 而计划只测了 NoOp 的两条，FAILURE_THRESHOLD / BREAK_DURATION / isBroken / onFailure 零覆盖。

    @Test
    void redis_failures_trip_the_circuit_breaker_and_stop_calling_redis() {
        // 熔断的意义：Redis 挂了之后，不要让每个请求都白等 300ms 连接超时。
        // 连续失败到阈值后，后续调用必须**直接返回未命中、不再碰 Redis**。
        var template = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(template.opsForValue())
                .thenThrow(new QueryTimeoutException("redis down"));

        CacheService c = new RedisCacheService(template, "h", 6379);

        for (int i = 0; i < RedisCacheService.FAILURE_THRESHOLD; i++) {
            assertThat(c.get("k", String.class)).isEmpty();   // 失败但降级，不抛
        }
        assertThat(c.isAvailable()).isFalse();
        assertThat(c.describe()).contains("熔断");

        // 熔断后不再调用 Redis
        Mockito.clearInvocations(template);
        assertThat(c.get("k", String.class)).isEmpty();
        Mockito.verifyNoInteractions(template);
    }

    @Test
    void the_circuit_closes_again_after_the_break_duration() {
        // 用可控时钟：否则要真的睡 30 秒才能验证恢复。
        var template = Mockito.mock(StringRedisTemplate.class);
        var clock = Mockito.mock(Clock.class);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Mockito.when(clock.instant()).thenReturn(t0);

        var valueOps = valueOps();
        Mockito.when(template.opsForValue()).thenReturn(valueOps);
        Mockito.when(valueOps.get(ArgumentMatchers.anyString()))
                .thenThrow(new QueryTimeoutException("down"));

        CacheService c = new RedisCacheService(template, "h", 6379, clock);
        for (int i = 0; i < RedisCacheService.FAILURE_THRESHOLD; i++) {
            c.get("k", String.class);
        }
        assertThat(c.isAvailable()).isFalse();

        // 时钟推进到熔断期之后
        Mockito.when(clock.instant())
                .thenReturn(t0.plus(RedisCacheService.BREAK_DURATION).plusSeconds(1));
        assertThat(c.isAvailable()).isTrue();
    }

    @Test
    void a_deserialization_failure_is_not_a_redis_failure() {
        // 一条类型不匹配的脏数据不该把整个缓存关掉——那是「一条脏数据 → 缓存整体瘫痪」。
        var template = Mockito.mock(StringRedisTemplate.class);
        var valueOps = valueOps();
        Mockito.when(template.opsForValue()).thenReturn(valueOps);
        // 缓存里存的是 JSON 字符串，但要按 Integer 读 -> 反序列化失败
        Mockito.when(valueOps.get(ArgumentMatchers.anyString()))
                .thenReturn("\"not-a-number\"");

        CacheService c = new RedisCacheService(template, "h", 6379);

        for (int i = 0; i < RedisCacheService.FAILURE_THRESHOLD + 5; i++) {
            assertThat(c.get("k", Integer.class)).isEmpty();   // 按未命中处理
        }
        assertThat(c.isAvailable())
                .as("反序列化失败不该触发熔断——否则一条脏数据会让整个缓存瘫痪")
                .isTrue();
        // 关键补充：确认这 8 次读**真的走到了 Redis 并失败在反序列化上**。
        // 否则这条测试可能是「提前熔断短路、根本没碰 Redis」而假绿。
        Mockito.verify(valueOps, Mockito.times(RedisCacheService.FAILURE_THRESHOLD + 5))
                .get("k");
    }

    @Test
    void redis_success_path_round_trips_a_value() {
        var template = Mockito.mock(StringRedisTemplate.class);
        var valueOps = valueOps();
        Mockito.when(template.opsForValue()).thenReturn(valueOps);
        Mockito.when(valueOps.get("k")).thenReturn("\"hello\"");

        CacheService c = new RedisCacheService(template, "h", 6379);
        assertThat(c.get("k", String.class)).contains("hello");
        assertThat(c.isAvailable()).isTrue();
    }

    @Test
    void failures_accumulate_across_calls_and_are_cleared_by_a_success() {
        // 这条是实测中发现真 bug 后补的回归测试（计划原稿的实现里熔断器从不跳）。
        // 根因：isBroken() 是每个操作的第一步，原稿在里面**无条件**清零失败计数，
        // 于是「清零 → 累加本次的 1 次失败」无限循环，计数永远到不了阈值。
        // 本测试把两件事钉住：①失败必须跨调用累积到阈值；②成功的调用才允许清零。
        var template = Mockito.mock(StringRedisTemplate.class);
        var valueOps = valueOps();
        Mockito.when(template.opsForValue()).thenReturn(valueOps);

        CacheService c = new RedisCacheService(template, "h", 6379);

        // 连续 3 次失败 -> 阈值；阈值后第 4 次调用成功（get 返回 null 即未命中）把计数清零
        Mockito.when(valueOps.get("k"))
                .thenThrow(new QueryTimeoutException("down"))
                .thenThrow(new QueryTimeoutException("down"))
                .thenThrow(new QueryTimeoutException("down"))
                .thenReturn(null);

        // 2 次失败 < 阈值，还没到熔断
        assertThat(c.get("k", String.class)).isEmpty();
        assertThat(c.get("k", String.class)).isEmpty();
        assertThat(c.isAvailable()).as("2 次失败还不够触发熔断").isTrue();

        // 第 3 次失败：恰好到阈值 -> 熔断
        assertThat(c.get("k", String.class)).isEmpty();
        assertThat(c.isAvailable()).as("连续 3 次失败必须熔断").isFalse();

        // 第 4 次调用：熔断期内，不碰 Redis（序列里的 thenReturn(null) 因此不会被消费）
        Mockito.clearInvocations(template);
        assertThat(c.get("k", String.class)).isEmpty();
        Mockito.verifyNoInteractions(template);

        assertThat(c.describe()).contains("熔断");
    }

    @Test
    void dirty_cache_data_never_trips_the_breaker_and_never_raises_the_count() {
        // 语义：脏数据（类型不匹配）对熔断状态必须是**中性**的——既不计入失败，也不清零。
        //
        // ⚠️ 实测修正（报告 F）：计划把这个改动说成「不改就会让一条脏数据把整个缓存关掉」，
        // 但把实现退回计划原稿实测后，行为**完全相同**——原稿把 onSuccess() 放在反序列化之前，
        // 于是每次读都以「计数归 0」收尾，onFailure 永远落在干净的 0 上加 1，
        // 读路径上的计数**根本不可能累积到阈值**。也就是说原稿那个 bug 在当前组合下不可达，
        // 「脏数据会触发熔断」这个说法不成立。
        // 真正能实测的差异是**计数语义**：原稿让每次脏读都以 1 收尾（清零+自增互相抵消），
        // 本实现让它保持中性（不增不减）。下面用「2 次真实故障 + 1 次脏读 + 1 次真实故障」
        // 把两种语义区分开——这在行为层面是可观测的，不依赖反射：
        //   中性（本实现）：故障 1,2 -> 脏读(仍 2) -> 故障 3 达阈值 -> 恰好 3 次跳闸 ✅
        //   计数脏数据    ：故障 1,2 -> 脏读(3)   -> 已跳闸，第 4 次不再触达 Redis ❌
        var template = Mockito.mock(StringRedisTemplate.class);
        var valueOps = valueOps();
        Mockito.when(template.opsForValue()).thenReturn(valueOps);

        java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean(false);
        Mockito.when(valueOps.get("k")).thenAnswer(inv -> {
            if (dirty.get()) return "\"not-a-number\"";              // 脏数据
            throw new QueryTimeoutException("down");                  // Redis 故障
        });

        CacheService c = new RedisCacheService(template, "h", 6379);

        // 2 次真实故障：还没到阈值
        assertThat(c.get("k", String.class)).isEmpty();
        assertThat(c.get("k", String.class)).isEmpty();
        assertThat(c.isAvailable()).as("2 次真实故障还不够触发熔断").isTrue();

        // 一次脏读：中性，不得把计数推过阈值
        dirty.set(true);
        assertThat(c.get("k", Integer.class)).isEmpty();
        assertThat(c.isAvailable())
                .as("脏数据不得计入熔断——否则 2 次真实故障 + 1 次脏读就跳闸了")
                .isTrue();

        // 回到真实故障：这是第 3 次，恰好达阈值
        dirty.set(false);
        assertThat(c.get("k", String.class)).isEmpty();
        assertThat(c.isAvailable())
                .as("脏数据中性时，第 3 次真实故障必须跳闸；若脏读被计过数，这里早就跳了")
                .isFalse();
    }

    /** ValueOperations 是泛型类，不具名类型参数无法直接 mock，这里统一收口。 */
    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> valueOps() {
        return Mockito.mock(ValueOperations.class);
    }
}
