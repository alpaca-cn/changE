package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把「会漂的一方」（数据库里的 {@code muscle_group.region_ids}）与「不漂的一方」
 * （vendored 的人体图素材）对起来。
 *
 * <p><b>这是计划 6b 里最重要的一条测试。</b>前端拿 {@code region_ids} 去 SVG 里找
 * 同 id 的 {@code <path>} 着色，id 拼错**不会报错**：那块肌肉只是永远不着色。
 * 整张图看起来很正常，只是某一块一直是灰的——没人会注意到「背阔肌这里应该是红的」。
 *
 * <p>所以 id 集合必须从素材原文里读：读 {@code frontend/src/assets/body-map/data/}
 * 下的两个 {@code .ts} 文件，用正则抽 {@code id: "..."}。
 * <b>不要从 {@code region-ids.ts} 读</b>——那是我们的副本，副本和数据库可以一起错；
 * <b>也不要在测试里手抄一份 id</b>——同样的理由，手抄的 89 个里错一个就前功尽弃。
 *
 * <p>数据库一侧来自 V10 迁移；V10 的 {@code UPDATE} 写在
 * {@code db/migration/V10__muscle_region_ids.sql}。两边由本测试钉在一起。
 */
@SpringBootTest
class MuscleRegionMappingTest {

    /**
     * 素材文件的路径。
     *
     * <p>Maven 在 {@code backend/} 下跑测试，所以这里用 {@code ..} 回到仓库根。
     * 读不到时 {@link #readRegionIds(Path)} 会先断言文件存在——**必须红，不能静默跳过**：
     * 跳过意味着这条最重要的交叉校验在 CI 上悄悄失效。
     */
    private static final Path FRONT_MUSCLES =
            Path.of("..", "frontend", "src", "assets", "body-map", "data", "muscles.front.ts");
    private static final Path BACK_MUSCLES =
            Path.of("..", "frontend", "src", "assets", "body-map", "data", "muscles.back.ts");

    /** 只匹配对象字面量里独占一行的 {@code id: "chest-upper-left",}。 */
    private static final Pattern ID_LITERAL =
            Pattern.compile("^\\s*id:\\s*\"([^\"]+)\",\\s*$", Pattern.MULTILINE);

    /**
     * 素材里**刻意不映射**的 24 个区域。逐个列出而不是只数个数：
     * 素材升级后如果多出一个区域，`unmapped` 会变成 25 个，这条立刻红——
     * 那正是需要人显式决定「它归哪块肌群，还是继续不映射」的时刻。
     */
    private static final Set<String> EXPECTED_UNMAPPED = Set.of(
            // 头脸：不是训练目标
            "head", "face", "head-back",
            // 标志线，不是肌肉
            "spine",
            // 我们 17 块里没有的肌肉
            "serratus-anterior-left", "serratus-anterior-right",
            "tibialis-anterior-left", "tibialis-anterior-right",
            "hip-flexor-left", "hip-flexor-right",
            // 关节与四肢末端
            "elbow-left", "elbow-right",
            "knee-left", "knee-right",
            "knee-back-left", "knee-back-right",
            "hand-left", "hand-right",
            "hand-back-left", "hand-back-right",
            "foot-left", "foot-right",
            "foot-back-left", "foot-back-right");

    @Autowired
    JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // 1. 素材本身的哨兵：40 / 49 / 89
    // ------------------------------------------------------------------

    @Test
    void the_vendored_assets_really_have_40_front_and_49_back_regions() {
        // 40 / 49 是 Task 1 vendor 时的实测数字。它们变了意味着素材升级了，
        // 下面的 65 / 24 与整张映射表都需要重新确认，不能默默跟着变。
        assertThat(readRegionIds(FRONT_MUSCLES)).as("正面区域数").hasSize(40);
        assertThat(readRegionIds(BACK_MUSCLES)).as("背面区域数").hasSize(49);

        // 用 Set 的 size 同时守住「正面与背面没有重叠」：40 + 49 若有交集，
        // 并集就会小于 89。
        assertThat(allRegionIds())
                .as("正面 + 背面应为 89 个不重复的区域（有重叠时会小于 89）")
                .hasSize(89);
    }

    // ------------------------------------------------------------------
    // 2. 17 行都非空
    // ------------------------------------------------------------------

    @Test
    void all_seventeen_groups_have_non_blank_region_ids() {
        // 没有区域的肌群在人体图上永远点不亮——那是静默的空白。
        Map<String, List<String>> byGroup = databaseRegionIds();

        assertThat(byGroup).as("muscle_group 应有 17 行").hasSize(17);
        for (Map.Entry<String, List<String>> e : byGroup.entrySet()) {
            assertThat(e.getValue())
                    .as("%s 的 region_ids 不应为空", e.getKey())
                    .isNotEmpty();
            assertThat(e.getValue())
                    .as("%s 的 region_ids 不应含空项", e.getKey())
                    .allMatch(id -> !id.isBlank());
        }
    }

    // ------------------------------------------------------------------
    // 3. 核心：数据库里的每个 id 都必须在素材里真实存在
    // ------------------------------------------------------------------

    @Test
    void every_region_id_in_the_database_exists_in_the_vendored_assets() {
        Set<String> real = allRegionIds();

        // 逐个检查而不是只比集合大小：失败信息要能直接指出「哪块肌群的哪个 id」
        // 是素材里不存在的，否则一个 lft/lower 的拼写错误很难定位。
        for (Map.Entry<String, List<String>> e : databaseRegionIds().entrySet()) {
            for (String id : e.getValue()) {
                assertThat(real)
                        .as("%s 的 region id `%s` 不在 vendored 素材里（拼错？素材升级？）",
                                e.getKey(), id)
                        .contains(id);
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. 没有任何区域同时属于两块肌群（否则着色互相覆盖）
    // ------------------------------------------------------------------

    @Test
    void no_region_belongs_to_two_muscle_groups() {
        // 这正是 glutes / abductors 那条注释防的事：两块肌群共用
        // gluteus-medius-* 的话，练完臀推亮起来的可能是「外展肌」，
        // 两边的容量统计也会互相污染。
        assertThat(mappedRegionIds(databaseRegionIds()))
                .as("同一个区域 id 不能同时出现在两块肌群的 region_ids 里")
                .doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------
    // 5. 65 个被映射 / 24 个刻意不映射，且未映射的逐个对得上
    // ------------------------------------------------------------------

    @Test
    void exactly_65_regions_are_mapped() {
        // 数字变了就说明映射被改过，需要显式确认（不是「跑一下看看」）。
        assertThat(mappedRegionIds(databaseRegionIds()))
                .as("17 块肌群应共映射 65 个区域")
                .hasSize(65);
    }

    @Test
    void exactly_24_regions_are_intentionally_unmapped() {
        Set<String> real = allRegionIds();
        Set<String> unmapped = new LinkedHashSet<>(real);
        unmapped.removeAll(mappedRegionIds(databaseRegionIds()));

        assertThat(unmapped)
                .as("未映射的素材区域应恰好是这 24 个；多一个或少一个都要显式确认")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_UNMAPPED);
        assertThat(unmapped).as("未映射区域数").hasSize(24);

        // 反过来：65 + 24 = 89，且没有既不在映射里、也不在「刻意不映射」清单里的
        // 漏网区域。素材升级后新增一个 id 时，这里会直接报出它是谁。
        Set<String> union = new LinkedHashSet<>(mappedRegionIds(databaseRegionIds()));
        union.addAll(unmapped);
        assertThat(union)
                .as("素材里的每个区域都应被显式归类：要么映射，要么在 24 个未映射清单里")
                .containsExactlyInAnyOrderElementsOf(real);
    }

    // ------------------------------------------------------------------
    // 6. 两条解剖学判断的定点守卫
    // ------------------------------------------------------------------

    @Test
    void glutes_use_maximus_and_abductors_use_medius() {
        // 素材没有单独的 abductors 区域；髋外展肌解剖上就是臀中肌 / 臀小肌。
        // glutes 只拿 maximus，把 medius 留给 abductors——否则两块肌群共用
        // 同一片区域，着色时互相覆盖。上面那条「无重复」已经能拦住共用的写法，
        // 这里再把「谁拿哪块」钉死，防止有人把两者对调（依然无重复，但解剖错了）。
        Map<String, List<String>> byGroup = databaseRegionIds();

        assertThat(byGroup.get("glutes"))
                .as("glutes 只应映射 gluteus-maximus-*")
                .containsExactly("gluteus-maximus-left", "gluteus-maximus-right");
        assertThat(byGroup.get("abductors"))
                .as("abductors（髋外展肌）应映射 gluteus-medius-*")
                .containsExactly("gluteus-medius-left", "gluteus-medius-right");
    }

    // ------------------------------------------------------------------
    // 素材一侧
    // ------------------------------------------------------------------

    /** 正面 + 背面的全部真实 id。顺序按素材文件里的出现顺序。 */
    private Set<String> allRegionIds() {
        Set<String> all = new LinkedHashSet<>(readRegionIds(FRONT_MUSCLES));
        all.addAll(readRegionIds(BACK_MUSCLES));
        return all;
    }

    /**
     * 从 vendored 的 {@code muscles.*.ts} **原文**里抽 {@code id: "..."}。
     *
     * <p>不读 {@code region-ids.ts}：那是我们的副本，副本和数据库可以一起错。
     * 也不在测试里手抄一份：同样的理由。
     */
    private static List<String> readRegionIds(Path file) {
        assertThat(Files.isRegularFile(file))
                .as("读不到素材 %s（解析为 %s）。测试要在 backend/ 下跑；"
                                + "读不到必须红，不能跳过——否则这条交叉校验会静默失效",
                        file, file.toAbsolutePath().normalize())
                .isTrue();

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读素材失败: " + file.toAbsolutePath().normalize(), e);
        }

        List<String> ids = new ArrayList<>();
        Matcher m = ID_LITERAL.matcher(text);
        while (m.find()) {
            ids.add(m.group(1));
        }
        assertThat(ids)
                .as("%s 里应当抽到 `id: \"...\"` 字面量", file.getFileName())
                .isNotEmpty();
        return ids;
    }

    // ------------------------------------------------------------------
    // 数据库一侧
    // ------------------------------------------------------------------

    /**
     * 读出 17 行的 region_ids，并顺手断言字符串的基本形状：
     * 逗号分隔、无空项、逗号两侧没有空格（见 MuscleGroup 的类注释）。
     */
    private Map<String, List<String>> databaseRegionIds() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name_en, region_ids FROM muscle_group ORDER BY id");

        assertThat(rows).as("muscle_group 应有 17 行").hasSize(17);

        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("name_en");
            String raw = (String) row.get("region_ids");

            assertThat(raw).as("%s 的 region_ids（V10 之后不应为 NULL / 空串）", name)
                    .isNotBlank();

            List<String> ids = Arrays.asList(raw.split(",", -1));
            for (String id : ids) {
                assertThat(id).as("%s 的 region_ids 里有空项: `%s`", name, raw).isNotBlank();
                assertThat(id).as("%s 的 region_ids 里逗号旁有空格: `%s`", name, raw)
                        .isEqualTo(id.trim());
            }
            List<String> previous = byGroup.put(name, ids);
            assertThat(previous).as("name_en 不应重复: %s", name).isNull();
        }
        return byGroup;
    }

    /** 把 17 行的 id 按行展开成一个列表；重复项会在这里保留，供「无重复」断言发现。 */
    private static List<String> mappedRegionIds(Map<String, List<String>> byGroup) {
        List<String> mapped = new ArrayList<>();
        byGroup.values().forEach(mapped::addAll);
        return mapped;
    }
}
