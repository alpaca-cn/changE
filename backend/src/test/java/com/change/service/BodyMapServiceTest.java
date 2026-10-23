package com.change.service;

import com.change.calc.VolumeCalculator;
import com.change.repo.MuscleGroupRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 人体图的数据：本周每块肌群多少组 + 上次训练是哪天。
 *
 * <h2>这里要守住的三件事</h2>
 *
 * <p><b>一、27 块一块都不能少。</b>「本周 0 组」是设计文档要求摆在最显眼位置的提醒，
 * 它必须以 {@code sets: 0} 出现——从列表里消失等于提醒消失。所以「空库」不是空数组。
 *
 * <p><b>二、{@code lastTrainedOn} 是历史上任意一天，不是本周的快照。</b>本周没练过、
 * 但上周练过的肌群必须仍然答得出「上次是 10-18」；否则界面上它就变成「从没练过」。
 *
 * <p><b>三、{@code daysSince} 的 0 与 null 是两件事。</b>今天练过 → {@code 0}；
 * 从没练过 → {@code null}。用 0 表示「从没练过」会把两者混成一个，
 * 前端「恢复中 / 长期未练」两种着色就再也分不开。这条由
 * {@code days_since_is_zero_for_today_and_null_for_never_in_the_same_response} 钉住：
 * **同一次请求**里必须一个 0、一个 null。
 *
 * <h2>为什么用独立库文件</h2>
 *
 * <p>「空库 → 27 块全在、sets 全 0、lastTrainedOn 全 null」是一条关于**整张表**的断言，
 * 共享库（{@code target/data/change.db}）里别处留下的训练行会让它凭空变红；
 * 而把它拆成「只断言自己插的那几行」就再也测不出「空库返回什么」。
 * 所以照 {@code ExerciseControllerTest} 的做法给这个类一个独立库文件，
 * 每个用例前把训练域清空——清完之后库里只剩 V7 的 27 块肌群，那就是确定的「空库」。
 *
 * <p>日期用固定的 {@code 2026-10-20}（**周二**，它那一周的周一是 2026-10-19），
 * 不用 {@code LocalDate.now()}：否则用例会随运行日期换周，`daysSince` 每天都不一样。
 * 「不给 date 时默认今天」那一条是唯一的例外，它本来就是在测这个默认值。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:${user.dir}/target/data/body-map-service.db"
                + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL"
})
class BodyMapServiceTest {

    /** 参照日：2026-10-20 是周二，它所在的自然周是 10-19（周一）..10-25（周日）。 */
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 25);

    @Autowired BodyMapService bodyMap;
    @Autowired WeeklyVolumeService weeklyVolume;
    @Autowired WorkoutService workouts;
    @Autowired MuscleGroupRepo muscleGroups;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // FK 顺序：组 → 动作实例 → 训练 → 肌群标注 → 动作。清完之后库里只剩 V7 的 27 块肌群。
        jdbc.update("DELETE FROM workout_set");
        jdbc.update("DELETE FROM session_exercise");
        jdbc.update("DELETE FROM workout_session");
        jdbc.update("DELETE FROM exercise_muscle");
        jdbc.update("DELETE FROM exercise");
        jdbc.update("DELETE FROM change_log");
    }

    // ---- 造数据 ----

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void link(long exerciseId, String muscleNameEn, String role) {
        long muscleId = muscleGroups.findByNameEn(muscleNameEn).orElseThrow().id;
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?,?,?)",
                exerciseId, muscleId, role);
    }

    /** 记一天：{@code completedSets} 组打勾 + {@code unfinishedSets} 组没打勾（「还没做」）。 */
    private void record(LocalDate date, long exerciseId, int completedSets, int unfinishedSets) {
        List<WorkoutService.SetInput> sets = new ArrayList<>();
        for (int i = 0; i < completedSets; i++) {
            sets.add(new WorkoutService.SetInput("normal", 60.0, 8, null, false, null, null, true, null, null));
        }
        for (int i = 0; i < unfinishedSets; i++) {
            sets.add(new WorkoutService.SetInput("normal", null, null, null, false, null, null, false, null, null));
        }
        workouts.recordSession(new WorkoutService.SessionInput(date, null, null, null, null, null,
                List.of(new WorkoutService.ExerciseInput(exerciseId, null, null, sets))));
    }

    private static BodyMapService.MuscleBodyMap byName(BodyMapService.BodyMapVolume v, String nameEn) {
        return v.muscles().stream()
                .filter(m -> m.nameEn().equals(nameEn))
                .findFirst()
                .orElseThrow(() -> new AssertionError("响应里没有肌群 " + nameEn));
    }

    // ------------------------------------------------------------------
    // 1. 27 块一块都不能少
    // ------------------------------------------------------------------

    /**
     * 空库：没有任何训练，「没练」必须以 {@code sets: 0} 出现，而不是从列表里消失。
     *
     * <p>这是变异「只返回有训练量的肌群」的杀手：过滤掉 0 组之后这里只剩 0 块。
     */
    @Test
    void all_muscles_come_back_with_zero_when_nothing_was_trained() {
        BodyMapService.BodyMapVolume v = bodyMap.volumeOn(TUESDAY);

        assertThat(v.weekStart()).isEqualTo(MONDAY);
        assertThat(v.weekEnd()).isEqualTo(SUNDAY);
        assertThat(v.today()).isEqualTo(TUESDAY);

        assertThat(v.muscles()).as("空库也要返回全部 27 块").hasSize(27);
        // 不是「恰好 17 条硬编码」而是 muscle_group 的全部行：加了一块肌群这条会跟着变，
        // 而写死 17 的断言会在那时静默地少一条（或者要求人肉去数）。
        assertThat(v.muscles()).extracting(BodyMapService.MuscleBodyMap::nameEn)
                .containsExactlyInAnyOrderElementsOf(
                        muscleGroups.findAll().stream().map(g -> g.nameEn).toList());

        for (BodyMapService.MuscleBodyMap m : v.muscles()) {
            assertThat(m.sets()).as("%s 的本周组数", m.nameEn()).isZero();
            assertThat(m.lastTrainedOn()).as("%s 从没练过", m.nameEn()).isNull();
            assertThat(m.daysSince()).as("%s 从没练过", m.nameEn()).isNull();
            // 子部位（chest-upper 这种）**故意**没有区域 id：2D 人体图仍是父组粒度，
            // 子部位带区域 id 会让同一块区域被两个肌群争着上色。
            // 区域有无由 region-inventory.json 决定，逐条核对在 MuscleRegionMappingTest；
            // 这里只要求 DTO 与库里的那一列**形状一致**。
            assertThat(m.regionIds()).as("%s 的 regionIds 不应含空项", m.nameEn())
                    .allMatch(id -> !id.isBlank());
        }
    }

    /** 练了一块，另外 16 块仍然在（且都是 0）——「0」与「不在列表里」必须是两种结果。 */
    @Test
    void training_one_muscle_does_not_remove_the_others() {
        long ex = newExercise("ZZ BodyMap Only Chest");
        link(ex, "chest", "primary");
        record(TUESDAY, ex, 2, 0);

        BodyMapService.BodyMapVolume v = bodyMap.volumeOn(TUESDAY);

        assertThat(v.muscles()).hasSize(27);
        assertThat(byName(v, "chest").sets()).isEqualTo(2.0);
        // 拆分后「其余」是 17 个顶层 + 10 个子部位 - 1 个练过的 = 26
        assertThat(v.muscles()).filteredOn(m -> m.sets() == 0.0)
                .as("其余 26 块以 0 出现").hasSize(26);
    }

    // ------------------------------------------------------------------
    // 2. sets / min / max / status 全部来自 WeeklyVolumeService
    // ------------------------------------------------------------------

    /**
     * 逐块与 {@link WeeklyVolumeService#weekly} 的结果比对：口径（主要 +1、次要 +0.5）、
     * 自然周、只算已完成组这些规则**一处也不许在本类里重算**。
     *
     * <p>顺带把「3 组卧推 → 胸 3 / 三头 1.5 / 肩 1.5」这个具体数字钉住，方便读。
     */
    @Test
    void sets_min_max_and_status_are_taken_verbatim_from_the_weekly_volume_service() {
        long ex = newExercise("ZZ BodyMap Bench");
        link(ex, "chest", "primary");
        link(ex, "triceps", "secondary");
        link(ex, "shoulders", "secondary");
        record(TUESDAY, ex, 3, 1);                       // 3 完成 + 1 未完成 → 只算 3
        record(LocalDate.of(2026, 10, 18), ex, 5, 0);    // 上一周（周日）→ 不算本周

        BodyMapService.BodyMapVolume v = bodyMap.volumeOn(TUESDAY);
        WeeklyVolumeService.WeeklyVolume w = weeklyVolume.weekly(TUESDAY);

        assertThat(v.muscles()).hasSameSizeAs(w.muscles());
        for (int i = 0; i < w.muscles().size(); i++) {
            WeeklyVolumeService.MuscleVolume expected = w.muscles().get(i);
            BodyMapService.MuscleBodyMap actual = v.muscles().get(i);
            assertThat(actual.muscleGroupId()).as("第 %d 块的 muscleGroupId", i)
                    .isEqualTo(expected.muscleGroupId());
            assertThat(actual.nameZh()).isEqualTo(expected.nameZh());
            assertThat(actual.nameEn()).isEqualTo(expected.nameEn());
            assertThat(actual.sets()).as("%s 的 sets", expected.nameEn()).isEqualTo(expected.sets());
            assertThat(actual.min()).as("%s 的 min", expected.nameEn()).isEqualTo(expected.min());
            assertThat(actual.max()).as("%s 的 max", expected.nameEn()).isEqualTo(expected.max());
            assertThat(actual.status()).as("%s 的 status", expected.nameEn())
                    .isEqualTo(expected.status());
        }

        assertThat(byName(v, "chest").sets()).as("主要肌群 +1").isEqualTo(3.0);
        assertThat(byName(v, "triceps").sets()).as("次要肌群 +0.5").isEqualTo(1.5);
        assertThat(byName(v, "shoulders").sets()).as("次要肌群 +0.5").isEqualTo(1.5);
    }

    /**
     * 「不要重写第二份权重逻辑」这条**光靠数值断言守不住**：重写一份一模一样的
     * 1.0 / 0.5 算出来的数字完全相同，上面那条比对照样是绿的。
     *
     * <p>所以这里换一个更硬的办法：注入一个 {@link WeeklyVolumeService} 的替身，
     * 让它返回一组**不可能是真实数据**的数字（42.5 组、区间 999..1000）。
     * 若实现自己再从 SQL 算一遍，响应里就是 0 组 / 10..20，这条立刻红。
     *
     * <p>替身用匿名子类而不是 Mockito：本项目的测试里没有一处 mock，
     * 一个只覆盖 {@code weekly} 的子类足够表达「这组数字是外面给的」。
     */
    @Test
    void the_weekly_numbers_are_copied_from_the_weekly_volume_service_not_recomputed() {
        WeeklyVolumeService sentinel = new WeeklyVolumeService(null, null) {
            @Override
            public WeeklyVolume weekly(LocalDate anyDay) {
                return new WeeklyVolume(MONDAY, SUNDAY, List.of(
                        // 2026-10-23: MuscleVolume 加了 parentEn（子部位用），
                        // 顶层组传 null
                        new MuscleVolume(1L, "胸部", "chest", 42.5, 999, 1000,
                                VolumeCalculator.Status.ABOVE, null)));
            }
        };
        BodyMapService withSentinel = new BodyMapService(sentinel, muscleGroups, jdbc);

        BodyMapService.BodyMapVolume v = withSentinel.volumeOn(TUESDAY);

        assertThat(v.muscles()).hasSize(1);
        BodyMapService.MuscleBodyMap chest = v.muscles().get(0);
        assertThat(chest.sets()).as("sets 必须是 WeeklyVolumeService 给的那个数").isEqualTo(42.5);
        assertThat(chest.min()).isEqualTo(999);
        assertThat(chest.max()).isEqualTo(1000);
        assertThat(chest.status()).isEqualTo(VolumeCalculator.Status.ABOVE);
        // regionIds 不是 WeeklyVolumeService 的职责，仍然来自数据库。
        // **V12 之后 chest 自己没有区域了**（区域下放到子部位），所以这里不能再断言非空。
        // 想验「regionIds 真的来自数据库」，用一个确实有区域的肌群：biceps。
        assertThat(chest.regionIds()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 3. lastTrainedOn：历史上任意一天
    // ------------------------------------------------------------------

    /**
     * 本周一组都没有、但历史里练过的肌群：{@code sets} 是 0，
     * {@code lastTrainedOn} 必须仍然是那天的日期。
     *
     * <p>这是变异「lastTrainedOn 的 SQL 加上本周范围限制」的杀手：加了限制之后这里变成 null，
     * 界面上它就变成「从没练过」——而用户上周明明练过。
     */
    @Test
    void last_trained_on_reaches_back_past_this_week() {
        long ex = newExercise("ZZ BodyMap History");
        link(ex, "chest", "primary");
        record(LocalDate.of(2026, 10, 13), ex, 3, 0);     // 上一周的周二
        record(LocalDate.of(2026, 10, 18), ex, 1, 0);     // 上周日（本周之外）

        BodyMapService.BodyMapVolume v = bodyMap.volumeOn(TUESDAY);
        BodyMapService.MuscleBodyMap chest = byName(v, "chest");

        assertThat(chest.sets()).as("本周（10-19..10-25）一组都没有").isZero();
        assertThat(chest.lastTrainedOn())
                .as("上一次训练是历史里的 10-18（取 MAX，不是第一次也不是本周）")
                .isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(chest.daysSince()).as("10-20 - 10-18").isEqualTo(2);
    }

    /** 只数 {@code completed = 1}：没打勾的组是「还没做」，不是训练。 */
    @Test
    void an_unfinished_set_is_not_training() {
        long ex = newExercise("ZZ BodyMap Unfinished");
        link(ex, "biceps", "primary");
        record(TUESDAY, ex, 0, 2);

        BodyMapService.BodyMapVolume v = bodyMap.volumeOn(TUESDAY);
        BodyMapService.MuscleBodyMap biceps = byName(v, "biceps");

        assertThat(biceps.sets()).isZero();
        assertThat(biceps.lastTrainedOn()).as("没打勾的组不算训练过").isNull();
        assertThat(biceps.daysSince()).isNull();
    }

    // ------------------------------------------------------------------
    // 4/5. daysSince 的 0 与 null（同一个响应里）
    // ------------------------------------------------------------------

    /**
     * **同一次请求里**同时出现 {@code daysSince = 0} 与 {@code daysSince = null}。
     *
     * <p>这条是「从没练过时 daysSince 返回 0」这个变异的杀手：两者混成一个值之后，
     * 「今天刚练」与「从没练过」在界面上就再也分不开。
     *
     * <p>三个状态一起钉：今天练过 → 0；三天前练过 → 3；从没练过 → null
     * （不是 0、不是 1970-01-01、不是很大的数）。
     */
    @Test
    void days_since_is_zero_for_today_and_null_for_never_in_the_same_response() {
        long bench = newExercise("ZZ BodyMap Today");
        link(bench, "chest", "primary");
        record(TUESDAY, bench, 2, 0);

        long curl = newExercise("ZZ BodyMap Three Days");
        link(curl, "biceps", "primary");
        record(TUESDAY.minusDays(3), curl, 2, 0);

        // abdominals 这个用例里一次都没有出现 → 从没练过

        BodyMapService.BodyMapVolume v = bodyMap.volumeOn(TUESDAY);

        BodyMapService.MuscleBodyMap trainedToday = byName(v, "chest");
        assertThat(trainedToday.lastTrainedOn()).isEqualTo(TUESDAY);
        assertThat(trainedToday.daysSince()).as("今天练过 → 0，不是 null").isEqualTo(0);
        assertThat(trainedToday.sets()).isPositive();

        BodyMapService.MuscleBodyMap never = byName(v, "abdominals");
        assertThat(never.lastTrainedOn()).as("从没练过 → lastTrainedOn 是 null").isNull();
        assertThat(never.daysSince()).as("从没练过 → daysSince 是 null，不是 0").isNull();

        BodyMapService.MuscleBodyMap threeDaysAgo = byName(v, "biceps");
        assertThat(threeDaysAgo.lastTrainedOn()).isEqualTo(TUESDAY.minusDays(3));
        assertThat(threeDaysAgo.daysSince()).as("10-20 - 10-17").isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 6. regionIds 来自库里的那一列
    // ------------------------------------------------------------------

    /**
     * 每块肌群的 {@code regionIds} 必须与 {@code muscle_group.region_ids} 逐项一致——
     * 它是前端去 SVG 里找 {@code path} 的唯一依据，拼错一个字符那块肌肉就永远不着色，
     * 而且**不会报错**。
     *
     * <p>「数组还是逗号分隔的字符串」在 JSON 层由 {@code BodyMapControllerTest} 钉住；
     * 这里钉的是服务层的类型与内容。
     */
    @Test
    void region_ids_are_the_database_column_split_into_a_list() {
        BodyMapService.BodyMapVolume v = bodyMap.volumeOn(TUESDAY);

        for (BodyMapService.MuscleBodyMap m : v.muscles()) {
            String raw = jdbc.queryForObject(
                    "SELECT region_ids FROM muscle_group WHERE id = ?", String.class, m.muscleGroupId());
            // 区域有无由 region-inventory.json 决定（逐条核对在 MuscleRegionMappingTest）；
            // 这里只要求 DTO 与库里的那一列形状一致。
            if (raw == null || raw.isBlank()) {
                assertThat(m.regionIds()).as("%s 库里没有区域，DTO 应为空", m.nameEn()).isEmpty();
                continue;
            }
            assertThat(m.regionIds())
                    .as("%s 的 regionIds 应与库里的那一列逐项一致", m.nameEn())
                    .containsExactly(raw.split(",", -1));
            assertThat(m.regionIds()).as("%s 的 regionIds 里不应有空项", m.nameEn())
                    .allMatch(id -> !id.isBlank());
        }

        // V12 之后区域下放到子部位：chest 自己没有区域，上胸那块有
        assertThat(byName(v, "chest").regionIds()).isEmpty();
        assertThat(byName(v, "chest-upper").regionIds()).containsExactly(
                "chest-upper-left", "chest-upper-right");
    }

    // ------------------------------------------------------------------
    // 8. date 参数：不给 → 今天；给了 → 那一天所在的那一周
    // ------------------------------------------------------------------

    @Test
    void without_a_date_the_week_is_the_one_today_falls_in() {
        LocalDate today = LocalDate.now();

        BodyMapService.BodyMapVolume v = bodyMap.volume(null);

        assertThat(v.today()).isEqualTo(today);
        assertThat(v.weekStart()).isEqualTo(
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        assertThat(v.weekEnd()).isEqualTo(v.weekStart().plusDays(6));
        assertThat(v.muscles()).hasSize(27);
    }

    @Test
    void a_date_selects_the_week_it_falls_in() {
        // 周日属于**同一周**（不是下一周）：2026-10-25 是周日，10-25 那周的周一是 10-19
        assertThat(bodyMap.volume("2026-10-25").weekStart()).isEqualTo(MONDAY);
        assertThat(bodyMap.volume("2026-10-25").weekEnd()).isEqualTo(SUNDAY);
        assertThat(bodyMap.volume("2026-10-25").today()).isEqualTo(SUNDAY);
        // 周一之后的那一天进新的一周
        assertThat(bodyMap.volume("2026-10-26").weekStart()).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void a_blank_date_means_the_default_but_a_malformed_one_is_rejected() {
        assertThat(bodyMap.volume("").today()).isEqualTo(LocalDate.now());
        assertThat(bodyMap.volume("   ").today()).isEqualTo(LocalDate.now());

        // 形状不对：不是 ISO 纯日期
        assertThatThrownBy(() -> bodyMap.volume("2026/10/14"))
                .as("斜杠不是 ISO 纯日期").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date");
        // 形状对但日期不存在
        assertThatThrownBy(() -> bodyMap.volume("2026-02-31"))
                .as("2 月 31 日不存在").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date");
        // LocalDate.parse 会接受 '+12026-10-14'（ISO 的扩展年份写法）——先卡形状再解析
        assertThatThrownBy(() -> bodyMap.volume("+12026-10-14"))
                .as("带加号的年份不是这个端点的词法").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date");
    }
}
