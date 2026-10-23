package com.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2D 人体图的区域归属：数据库 ←→ 素材 ←→ 清单，三者交叉校验。
 *
 * <h2>2026-10-23 重写：从「写死计数」改成「对照清单」</h2>
 *
 * <p>这个类原来钉着「恰好 65 个区域已映射 / 恰好 24 个未映射」。子部位落地后要把
 * 区域从父组下放到子部位，那两个数字就得重新推导 —— 而它们散在 SQL、测试、素材三处，
 * 改一处另两处就打脸。
 *
 * <p>现在只有**一份**事实源：{@code seed/region-inventory.json}（由
 * {@code scripts/body-map/gen-region-inventory.py} 生成，V12 也从它生成）。
 * 这里的断言变成「数据库必须与清单逐条一致」，比计数强得多，也不会再因为
 * 调整归属而失配。
 *
 * <h2>区域被两个肌群共用是允许的，但必须在清单里写明</h2>
 *
 * <p>素材是按解剖画的，比我们的分组粗：胸只有 upper/lower 两块（没有中胸），
 * 菱形肌没有独立区域（画在 traps-mid 里深于中斜方）。这种共用由前端切成横带渲染，
 * 所以「共享」本身不违法 —— 但**没有写进清单的共享**会静默覆盖着色，必须报错。
 */
@SpringBootTest
class MuscleRegionMappingTest {

    private static final Pattern ID_LITERAL = Pattern.compile("id:\\s*['\"]([a-z0-9-]+)['\"]");

    @Autowired
    JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // 1. 素材本身
    // ------------------------------------------------------------------

    @Test
    void the_vendored_assets_really_have_40_front_and_49_back_regions() {
        assertThat(readAssetIds("muscles.front.ts")).as("正面区域数").hasSize(40);
        assertThat(readAssetIds("muscles.back.ts")).as("背面区域数").hasSize(49);
    }

    // ------------------------------------------------------------------
    // 2. 数据库与清单逐条一致（核心）
    // ------------------------------------------------------------------

    @Test
    void the_database_matches_the_region_inventory_exactly() {
        Map<String, List<String>> byGroup = databaseRegionIds();
        Map<String, List<String>> expected = inventoryByGroup();

        assertThat(byGroup.keySet())
                .as("库里「有区域」的肌群应当与清单里的完全一致（多一个少一个都是漂移）")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());

        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            assertThat(byGroup.get(e.getKey()))
                    .as("%s 的 region_ids 应当与清单一致", e.getKey())
                    .containsExactlyInAnyOrderElementsOf(e.getValue());
        }
    }

    // ------------------------------------------------------------------
    // 3. 数据库里的每个 id 都必须在素材里真实存在
    // ------------------------------------------------------------------

    @Test
    void every_region_id_in_the_database_exists_in_the_vendored_assets() {
        Set<String> real = allRegionIds();

        // 逐个检查而不是只比集合大小：失败信息要能直接指出「哪块肌群的哪个 id」不存在
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
    // 4. 共用只允许出现在清单里写明的那几个区域上
    // ------------------------------------------------------------------

    @Test
    void shared_regions_are_exactly_the_ones_the_inventory_declares() {
        List<String> mapped = mappedRegionIds(databaseRegionIds());
        List<String> shared = mapped.stream()
                .filter(id -> mapped.stream().filter(id::equals).count() > 1)
                .distinct()
                .toList();

        assertThat(shared)
                .as("被两个肌群共用的区域必须是清单里写明的那几个"
                        + "（别的共用会让 2D 着色静默互相覆盖）")
                .containsExactlyInAnyOrderElementsOf(inventorySharedRegionIds());
    }

    // ------------------------------------------------------------------
    // 5. 已映射 / 未映射的覆盖度（数量从清单推导，不写死）
    // ------------------------------------------------------------------

    @Test
    void every_asset_region_is_either_mapped_or_explicitly_unmapped() {
        Set<String> real = allRegionIds();
        Set<String> mapped = new LinkedHashSet<>(mappedRegionIds(databaseRegionIds()));

        Set<String> unmapped = new LinkedHashSet<>(real);
        unmapped.removeAll(mapped);

        Set<String> expectedMapped = inventoryByGroup().values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(mapped)
                .as("库里映射的区域集合应与清单算出的一致")
                .containsExactlyInAnyOrderElementsOf(expectedMapped);
        assertThat(unmapped)
                .as("未映射区域 = 素材全部 - 已映射；清单与素材必须刚好互补")
                .containsExactlyInAnyOrderElementsOf(
                        real.stream().filter(id -> !expectedMapped.contains(id)).toList());

        Set<String> union = new LinkedHashSet<>(mapped);
        union.addAll(unmapped);
        assertThat(union)
                .as("素材里的每个区域都应被显式归类：要么映射，要么未映射（不存在漏网）")
                .containsExactlyInAnyOrderElementsOf(real);
    }

    // ------------------------------------------------------------------
    // 6. 解剖学判断的定点守卫（不随归属调整而变）
    // ------------------------------------------------------------------

    @Test
    void glutes_use_maximus_and_abductors_use_medius() {
        // 素材没有单独的 abductors 区域；髋外展肌解剖上就是臀中肌 / 臀小肌。
        // glutes 只拿 maximus，把 medius 留给 abductors——否则两块肌群共用同一片区域，
        // 着色时互相覆盖。上面那条「共用必须在清单里」能拦住写法错误，
        // 这里再把「谁拿哪块」钉死，防止有人把两者对调（依然无重复，但解剖错了）。
        Map<String, List<String>> byGroup = databaseRegionIds();

        assertThat(byGroup.get("glutes"))
                .as("glutes 只应映射 gluteus-maximus-*")
                .containsExactly("gluteus-maximus-left", "gluteus-maximus-right");
        assertThat(byGroup.get("abductors"))
                .as("abductors 只应映射 gluteus-medius-*")
                .containsExactly("gluteus-medius-left", "gluteus-medius-right");
    }

    // ------------------------------------------------------------------
    // 素材一侧
    // ------------------------------------------------------------------

    /**
     * 读前端素材的**真身**（不是副本）。
     *
     * <p>测试跑在 {@code backend/} 下，所以素材在 {@code ../frontend/src/assets/body-map/data/}。
     * 刻意读源码而不是 classpath 副本：这个类的意义就是「库里的值与素材原文对得上」，
     * 读副本就变成了「与某份快照对得上」。
     */
    private List<String> readAssetIds(String fileName) {
        java.nio.file.Path file = java.nio.file.Path.of(
                "..", "frontend", "src", "assets", "body-map", "data", fileName);
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new AssertionError("找不到素材: " + file.toAbsolutePath().normalize());
        }
        String text;
        try {
            text = java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读素材失败: " + file.toAbsolutePath().normalize(), e);
        }

        List<String> ids = new ArrayList<>();
        Matcher m = ID_LITERAL.matcher(text);
        while (m.find()) {
            ids.add(m.group(1));
        }
        assertThat(ids)
                .as("%s 里应当抽到 `id: \"...\"` 字面量", fileName)
                .isNotEmpty();
        return ids;
    }

    private Set<String> allRegionIds() {
        Set<String> all = new LinkedHashSet<>(readAssetIds("muscles.front.ts"));
        all.addAll(readAssetIds("muscles.back.ts"));
        return all;
    }

    // ------------------------------------------------------------------
    // 清单一侧
    // ------------------------------------------------------------------

    /** 读清单。 */
    private static JsonNode inventory() {
        try (InputStream in = new ClassPathResource("seed/region-inventory.json").getInputStream()) {
            return new ObjectMapper().readTree(in.readAllBytes());
        } catch (IOException e) {
            throw new AssertionError("读 region-inventory.json 失败", e);
        }
    }

    /** 清单里「有归属」的肌群 -> 它的区域列表。 */
    private static Map<String, List<String>> inventoryByGroup() {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (JsonNode region : inventory().get("regions")) {
            String id = region.get("id").asText();
            for (JsonNode g : region.get("groups")) {
                byGroup.computeIfAbsent(g.asText(), k -> new ArrayList<>()).add(id);
            }
        }
        return byGroup;
    }

    /** 清单里被两个以上肌群共同认领的区域。 */
    private static List<String> inventorySharedRegionIds() {
        List<String> shared = new ArrayList<>();
        for (JsonNode region : inventory().get("regions")) {
            if (region.get("groups").size() > 1) {
                shared.add(region.get("id").asText());
            }
        }
        return shared;
    }

    // ------------------------------------------------------------------
    // 数据库一侧
    // ------------------------------------------------------------------

    /**
     * 读出「有区域」的那些肌群及其 region_ids，并顺手断言字符串的基本形状：
     * 逗号分隔、无空项、逗号两侧没有空格。
     *
     * <p><b>不假设行数</b>：V12 之后有的顶层故意没有区域（被拆的 5 个），
     * 子部位则各自有。行数由清单决定，这里只断言形状。
     */
    private Map<String, List<String>> databaseRegionIds() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name_en, region_ids FROM muscle_group "
                        + "WHERE region_ids IS NOT NULL AND region_ids <> '' ORDER BY id");

        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("name_en");
            String raw = (String) row.get("region_ids");

            assertThat(raw).as("%s 的 region_ids 不应为 NULL / 空串", name).isNotBlank();

            List<String> ids = Arrays.asList(raw.split(",", -1));
            for (String id : ids) {
                assertThat(id).as("%s 的 region_ids 里有空项: `%s`", name, raw).isNotBlank();
                assertThat(id).as("%s 的 region_ids 里逗号旁有空格: `%s`", name, raw)
                        .isEqualTo(id.trim());
            }
            assertThat(byGroup.put(name, ids)).as("name_en 不应重复: %s", name).isNull();
        }
        return byGroup;
    }

    /** 把各行的 id 按行展开成一个列表；重复项保留，供「共用」断言发现。 */
    private static List<String> mappedRegionIds(Map<String, List<String>> byGroup) {
        List<String> mapped = new ArrayList<>();
        byGroup.values().forEach(mapped::addAll);
        return mapped;
    }
}
