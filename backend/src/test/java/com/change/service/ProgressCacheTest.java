package com.change.service;

import com.change.cache.CacheService;
import com.change.cache.NoOpCacheService;
import com.change.repo.ExerciseRepo;
import com.change.service.ProgressService.ExerciseProgress;
import com.change.service.ProgressService.Point;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进步曲线的缓存：命中、写训练后失效、缓存不可用时照常工作、key 的隔离性。
 *
 * <h2>为什么这里必须有自己的内存版 CacheService（额外要求 A2）</h2>
 *
 * <p>测试环境里 {@code change.redis.enabled} 默认是 {@code false}，装配到的是
 * {@link NoOpCacheService}。拿它测这两件事会得到**恒真的假绿**：
 * <ul>
 *   <li><b>命中</b>：NoOp 永远未命中，所以「第二次也拿到了数据」只能证明它重算了一遍，
 *       完全测不出缓存有没有生效。</li>
 *   <li><b>失效</b>：NoOp 没有状态，{@code invalidatePrefix} 是空操作。
 *       「写训练后拿到新数据」在这种环境下永远成立——因为没有缓存，读当然永远新鲜。
 *       删掉 {@code WorkoutService} 里的失效调用，测试照样全绿。</li>
 * </ul>
 *
 * <p>所以本类用 {@link TestConfiguration} 提供一个 {@link RecordingCacheService}
 * ——{@link Map} 支撑、记录每一次 {@code get} / 命中 / {@code put} / TTL /
 * {@code invalidatePrefix}——并标 {@code @Primary}，让整条链路
 * （{@link ProgressService}、{@link WorkoutService}）都真的经过它。
 * 它**只存在于测试目录**，production 的 {@code NoOpCacheService} 一个字没动。
 *
 * <p>缓存里存的是对象本身（内存引用），不经过 JSON。序列化那一层由
 * {@code CacheServiceTest#redis_round_trips_a_value_that_contains_a_local_date} 负责：
 * 两条测试合起来才覆盖「值真的能从 Redis 出去再回来」。
 *
 * <p>和本项目其它服务测试一致：{@code @Transactional} 跑完回滚，每条用例自己造动作，
 * 断言只针对自己插入的行；{@link RecordingCacheService} 在每条用例前清空，
 * 不把上一条的状态带进来。
 */
@SpringBootTest
@Transactional
class ProgressCacheTest {

    /** 测试自己的内存版缓存：有状态、可观测。生产代码不知道它的存在。 */
    @TestConfiguration
    static class RecordingCacheConfig {
        @Bean
        @Primary
        RecordingCacheService recordingCacheService() {
            return new RecordingCacheService();
        }
    }

    @Autowired ProgressService progress;
    @Autowired WorkoutService workouts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExerciseRepo exercises;
    @Autowired RecordingCacheService cache;

    @BeforeEach
    void resetCache() {
        cache.reset();
    }

    // ---- 造数据 ----

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private static WorkoutService.SetInput set(double weight, int repetitions) {
        return new WorkoutService.SetInput("normal", weight, repetitions, null, false,
                null, null, true, null, null);
    }

    private void record(LocalDate date, long exerciseId, WorkoutService.SetInput... sets) {
        workouts.recordSession(new WorkoutService.SessionInput(date, null, null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null, List.of(sets)))));
    }

    /**
     * 绕过 {@link WorkoutService} 直接往库里补一次训练——因此**不会**触发缓存失效。
     * 用来证明「第二次读到的确实是缓存，而不是又查了一遍库」。
     */
    private void insertSessionDirectly(LocalDate date, long exerciseId, double weight, int repetitions) {
        jdbc.update("INSERT INTO workout_session(session_date) VALUES (?)", date.toString());
        long sessionId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO session_exercise(session_id, exercise_id, order_index) VALUES (?, ?, 0)",
                sessionId, exerciseId);
        long sessionExerciseId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO workout_set(session_exercise_id, set_index, weight, repetitions, completed) "
                + "VALUES (?, 0, ?, ?, 1)", sessionExerciseId, weight, repetitions);
    }

    private static Point pointOn(ExerciseProgress p, LocalDate date) {
        return p.points().stream()
                .filter(point -> point.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 " + date + " 这个点：" + p.points()));
    }

    // ---- 1. 命中 ----

    @Test
    void a_second_identical_request_is_served_from_the_cache_instead_of_recomputing() {
        long ex = newExercise("ZZ ProgressCache Hit");
        record(LocalDate.of(2026, 10, 14), ex, set(60.0, 8));

        ExerciseProgress first = progress.progress(ex, null, null);
        ExerciseProgress second = progress.progress(ex, null, null);

        assertThat(pointOn(first, LocalDate.of(2026, 10, 14)).e1rm()).isEqualTo(76.0);
        assertThat(second).isEqualTo(first);

        assertThat(cache.getKeys()).hasSize(2);
        assertThat(cache.hitKeys())
                .as("第二次读必须命中第一次写进去的那个键")
                .containsExactly(cache.putKeys().get(0));
        assertThat(cache.putKeys())
                .as("命中之后不该再算一遍、更不该再 put 一次")
                .hasSize(1);

        // 更强的证明：绕过 WorkoutService 直接补一行训练（所以缓存不会被失效）。
        // 如果第三次调用重算了库，结果里会多出 2026-10-20 这个点；它没有，说明读的是缓存。
        insertSessionDirectly(LocalDate.of(2026, 10, 20), ex, 100.0, 1);
        ExerciseProgress third = progress.progress(ex, null, null);

        assertThat(third).isEqualTo(first);
        assertThat(third.points()).extracting(Point::date)
                .containsExactly(LocalDate.of(2026, 10, 14));
        assertThat(cache.putKeys()).hasSize(1);
    }

    // ---- 2. 写训练后失效（整链） ----

    @Test
    void recording_a_workout_invalidates_the_progress_cache_and_the_next_read_sees_the_new_point() {
        long ex = newExercise("ZZ ProgressCache Invalidate");
        record(LocalDate.of(2026, 10, 14), ex, set(60.0, 8));

        ExerciseProgress before = progress.progress(ex, null, null);
        assertThat(before.points()).extracting(Point::date)
                .containsExactly(LocalDate.of(2026, 10, 14));
        assertThat(cache.storeSize()).as("第一次读必须把结果放进缓存").isEqualTo(1);

        // 走真正的写路径：WorkoutService.recordSession 内部必须在返回前清掉 progress: 前缀
        record(LocalDate.of(2026, 10, 17), ex, set(65.0, 5));

        assertThat(cache.invalidatedPrefixes())
                .as("写训练成功后必须调用 invalidatePrefix(\"progress:\")——"
                        + "只用 NoOpCacheService 测这件事会恒真（它没有状态）")
                .contains("progress:");
        assertThat(cache.storeSize())
                .as("失效必须真的把键清掉，而不只是被调用过")
                .isZero();

        ExerciseProgress after = progress.progress(ex, null, null);
        assertThat(after.points()).extracting(Point::date)
                .as("失效之后必须读到新数据，而不是缓存里的旧值")
                .containsExactly(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 17));
        assertThat(pointOn(after, LocalDate.of(2026, 10, 17)).maxWeight()).isEqualTo(65.0);
    }

    // ---- 3. 缓存不可用时照常工作 ----

    @Test
    void when_the_cache_is_unavailable_the_curve_is_recomputed_and_never_throws() {
        long ex = newExercise("ZZ ProgressCache NoOp");
        record(LocalDate.of(2026, 10, 14), ex, set(60.0, 8));

        // 真实世界里 Redis 关掉时装配的就是 NoOpCacheService：永远未命中、没有状态。
        // 直接注入它，把「缓存不可用」这条路径**真的**走一遍。
        ProgressService withoutCache = new ProgressService(jdbc, exercises, new NoOpCacheService());

        ExerciseProgress first = withoutCache.progress(ex, null, null);
        ExerciseProgress second = withoutCache.progress(ex, null, null);

        assertThat(first.points()).hasSize(1);
        assertThat(first.points().get(0).maxWeight()).isEqualTo(60.0);
        assertThat(first.points().get(0).e1rm()).isEqualTo(76.0);
        // 没有缓存时每次都重算，结果必须一致、且不得抛异常
        assertThat(second).isEqualTo(first);

        // 404 也不能因为缓存缺失而变形
        long missing = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000 FROM exercise", Long.class);
        assertThatThrownBy(() -> withoutCache.progress(missing, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- 4. 不同动作是不同的 key ----

    @Test
    void two_exercises_use_different_keys_and_never_cross_their_data() {
        long a = newExercise("ZZ ProgressCache A");
        long b = newExercise("ZZ ProgressCache B");
        record(LocalDate.of(2026, 10, 14), a, set(60.0, 8));
        record(LocalDate.of(2026, 10, 15), b, set(100.0, 1));

        ExerciseProgress pa = progress.progress(a, null, null);
        ExerciseProgress pb = progress.progress(b, null, null);

        assertThat(pa.exerciseId()).isEqualTo(a);
        assertThat(pa.nameEn()).isEqualTo("ZZ ProgressCache A");
        assertThat(pa.points()).hasSize(1);
        assertThat(pa.points().get(0).maxWeight()).isEqualTo(60.0);

        assertThat(pb.exerciseId()).isEqualTo(b);
        assertThat(pb.nameEn()).isEqualTo("ZZ ProgressCache B");
        assertThat(pb.points()).hasSize(1);
        assertThat(pb.points().get(0).maxWeight())
                .as("key 里去掉 exerciseId 时，查 B 会拿回 A 的缓存值")
                .isEqualTo(100.0);

        assertThat(cache.putKeys()).hasSize(2);
        assertThat(cache.putKeys().get(0)).contains(String.valueOf(a));
        assertThat(cache.putKeys().get(1)).contains(String.valueOf(b));

        // 再查 A：必须命中 A 自己的键
        assertThat(progress.progress(a, null, null)).isEqualTo(pa);
        assertThat(cache.hitKeys()).containsExactly(cache.putKeys().get(0));
    }

    // ---- 5. from/to 必须进 key（最容易漏的一条） ----

    @Test
    void a_date_filtered_request_is_never_served_from_the_unfiltered_cache_entry() {
        long ex = newExercise("ZZ ProgressCache Range");
        record(LocalDate.of(2026, 10, 10), ex, set(50.0, 8));
        record(LocalDate.of(2026, 10, 14), ex, set(55.0, 8));
        record(LocalDate.of(2026, 10, 17), ex, set(60.0, 8));

        ExerciseProgress all = progress.progress(ex, null, null);
        assertThat(all.points()).hasSize(3);

        // 先查不带过滤的，再查带过滤的：key 若漏了 from/to，这里会拿到 3 个点的旧值
        ExerciseProgress fromOnly = progress.progress(ex, "2026-10-15", null);
        assertThat(fromOnly.points()).extracting(Point::date)
                .as("key 必须含 from，否则会拿到未过滤的 3 个点")
                .containsExactly(LocalDate.of(2026, 10, 17));

        ExerciseProgress toOnly = progress.progress(ex, null, "2026-10-13");
        assertThat(toOnly.points()).extracting(Point::date)
                .as("key 必须含 to")
                .containsExactly(LocalDate.of(2026, 10, 10));

        ExerciseProgress both = progress.progress(ex, "2026-10-10", "2026-10-14");
        assertThat(both.points()).extracting(Point::date)
                .as("from 与 to 组合也必须自成一键")
                .containsExactly(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 14));

        // 再查一次不带过滤的：必须命中最初那个键，不能被子集串掉
        assertThat(progress.progress(ex, null, null)).isEqualTo(all);

        // key 形状本身也钉住（计划 6a Task 4 冻结的形状；_ 表示该侧不限）
        assertThat(cache.putKeys()).containsExactly(
                "progress:v1:" + ex + ":_:_",
                "progress:v1:" + ex + ":2026-10-15:_",
                "progress:v1:" + ex + ":_:2026-10-13",
                "progress:v1:" + ex + ":2026-10-10:2026-10-14");
    }

    // ---- TTL ----

    @Test
    void a_cached_curve_is_written_with_a_ten_minute_ttl() {
        long ex = newExercise("ZZ ProgressCache Ttl");
        record(LocalDate.of(2026, 10, 14), ex, set(60.0, 8));

        progress.progress(ex, null, null);

        // TTL 是兜底（某个写路径忘了失效时最多旧 10 分钟），不是主失效手段。
        // 钉住它：Duration.ZERO / 负数会让缓存等于不存在，而且没有任何别的测试会发现。
        assertThat(cache.ttls()).containsExactly(Duration.ofMinutes(10));
    }

    /**
     * 测试自己的内存版缓存。
     *
     * <p>刻意做成「有状态 + 全部可观测」：命中、写下、TTL、按前缀失效都能被断言，
     * 这是 NoOp 做不到的。{@code get} 的类型检查模仿真实缓存反序列化失败的降级
     * （类型不符按未命中处理，而不是抛异常）——与 {@code RedisCacheService} 同一条约定。
     */
    static class RecordingCacheService implements CacheService {

        private final Map<String, Object> store = new LinkedHashMap<>();
        private final List<String> getKeys = new ArrayList<>();
        private final List<String> hitKeys = new ArrayList<>();
        private final List<String> putKeys = new ArrayList<>();
        private final List<Duration> ttls = new ArrayList<>();
        private final List<String> invalidatedPrefixes = new ArrayList<>();

        void reset() {
            store.clear();
            getKeys.clear();
            hitKeys.clear();
            putKeys.clear();
            ttls.clear();
            invalidatedPrefixes.clear();
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            getKeys.add(key);
            Object value = store.get(key);
            if (value == null || !type.isInstance(value)) {
                return Optional.empty();
            }
            hitKeys.add(key);
            return Optional.of(type.cast(value));
        }

        @Override
        public void put(String key, Object value, Duration ttl) {
            putKeys.add(key);
            ttls.add(ttl);
            store.put(key, value);
        }

        @Override
        public void invalidatePrefix(String prefix) {
            invalidatedPrefixes.add(prefix);
            store.keySet().removeIf(key -> key.startsWith(prefix));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "缓存：测试用内存实现";
        }

        List<String> getKeys() {
            return List.copyOf(getKeys);
        }

        List<String> hitKeys() {
            return List.copyOf(hitKeys);
        }

        List<String> putKeys() {
            return List.copyOf(putKeys);
        }

        List<Duration> ttls() {
            return List.copyOf(ttls);
        }

        List<String> invalidatedPrefixes() {
            return List.copyOf(invalidatedPrefixes);
        }

        int storeSize() {
            return store.size();
        }
    }
}
