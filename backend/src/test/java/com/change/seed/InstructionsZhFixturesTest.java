package com.change.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 合并产物 {@code seed/instructions-zh.json} 的守卫：条数、键、段数、中文。
 *
 * <p>它守的是「合并之后没丢段、没漏条」——翻译质量本身没法机器判断，但
 * <b>结构</b>可以：少一段就是丢一步做法，而页面上完全看不出来。
 *
 * <h2>为什么文件缺失时用 {@link org.junit.jupiter.api.Assumptions#assumeTrue} 而不是 {@code @Disabled}</h2>
 * <p>产物在 Task 3 之前不存在。{@code @Disabled} 需要有人记得回来删——这个项目被「记得以后做 X」
 * 坑过。{@code assumeTrue} 的好处是**文件一出现，这些检查自动开始真的跑**。
 *
 * <p>代价是「产物被删掉时它会静默跳过」。所以 Task 4 另外加一条硬测试
 * （{@code the_merged_product_exists}，文件不存在就红）；那一条不在这里，是刻意的分工。
 *
 * <p><b>英文原文的段数从哪来</b>：直接解析 {@code exercises.json} 的原始数组，而不是用
 * {@link FreeExerciseDb} 拼好的那一整段字符串——{@code Straight-Arm Dumbbell Pullover}
 * 有一段的**内部**自带换行，按 {@code \n} 切会把那段数错。
 */
class InstructionsZhFixturesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 合并产物。生成物：由 {@code scripts/merge-instructions-zh.py} 从 part 文件合并。 */
    private static final String MERGED = "seed/instructions-zh.json";

    /** 英文原文（vendor 好的 876 条）。 */
    private static final String DATASET = "seed/free-exercise-db/exercises.json";

    /** 中文名映射：只用来确认两边是同一套键。 */
    private static final String NAME_ZH = "seed/name-zh.json";

    /** 数据集的原始顺序（876 条）。 */
    private static final List<String> DATASET_NAMES = new ArrayList<>();

    /** 每条动作的英文段落（原始数组，保留空段）。 */
    private static final Map<String, List<String>> SOURCE = new LinkedHashMap<>();

    static {
        loadDataset();
    }

    /**
     * 产物还不存在时跳过每条检查（翻译从 Task 3 才开始）。
     *
     * <p>放在 {@code @BeforeEach} 而不是 {@code @BeforeAll}：类级跳过时 surefire 报
     * {@code Tests run: 0, Skipped: 0}，等于这条测试从报告里消失了；逐条跳过才会在
     * 报告里留下 4 条 skipped，看得见。
     */
    @BeforeEach
    void the_checks_only_mean_something_once_the_product_exists() {
        assumeTrue(new ClassPathResource(MERGED).exists(),
                "seed/instructions-zh.json 还没有生成（翻译从 Task 3 才开始）；"
                        + "它一出现，这个测试就会开始校验。");
    }

    @Test
    void the_product_has_one_entry_per_exercise_that_has_an_english_source() {
        Map<String, List<String>> merged = readMerged();
        List<String> translatable = translatableNames();
        List<String> withoutSource = withoutSourceNames();

        assertThat(DATASET_NAMES).as("数据集换了版本（这里钉的是 876 条那一版）").hasSize(876);
        assertThat(withoutSource).as("英文原文为空的条数").hasSize(5);

        assertThat(nameZhKeys())
                .as("name-zh.json 与数据集是同一套键、同一顺序（两边可以按名字对齐）")
                .containsExactlyElementsOf(DATASET_NAMES);

        assertThat(merged)
                .as("876 条里 5 条原文为空、没有可翻的内容，产物应当是 871 条")
                .hasSize(translatable.size());

        assertThat(merged.keySet())
                .as("键集合 = 有英文原文的那些，且按数据集顺序排列")
                .containsExactlyElementsOf(translatable);

        assertThat(merged.keySet())
                .as("英文原文为空的 5 条不该出现在产物里")
                .doesNotContainAnyElementsOf(withoutSource);

        // 「与 name-zh.json 同一套键」的精确说法：差集恰好是那 5 条原文为空的。
        Set<String> nameZhMinusSkipped = new LinkedHashSet<>(nameZhKeys());
        nameZhMinusSkipped.removeAll(withoutSource);
        assertThat(merged.keySet()).containsExactlyElementsOf(nameZhMinusSkipped);
    }

    @Test
    void every_entry_keeps_the_paragraph_count_of_the_english_source() {
        for (Map.Entry<String, List<String>> entry : readMerged().entrySet()) {
            String name = entry.getKey();
            List<String> source = SOURCE.get(name);
            assertThat(source).as("%s 不在英文数据集里——键写错了", name).isNotNull();
            assertThat(entry.getValue())
                    .as("%s 的段数应当与英文原文一致（%d 段）", name, source.size())
                    .hasSize(source.size());
        }
    }

    @Test
    void no_entry_is_an_empty_array_and_paragraphs_are_never_blank_where_the_source_is_not() {
        for (Map.Entry<String, List<String>> entry : readMerged().entrySet()) {
            String name = entry.getKey();
            List<String> chinese = entry.getValue();
            List<String> source = SOURCE.get(name);
            assertThat(source).as("%s 不在英文数据集里——键写错了", name).isNotNull();

            assertThat(chinese).as("%s 的做法是空数组", name).isNotEmpty();

            for (int index = 0; index < chinese.size(); index++) {
                if (source.get(index).isBlank()) {
                    // 英文原文这一段本来就是空的（2 条：Barbell Squat To A Bench、Clean）。
                    // 为了段数对齐，中文也必须留空；往里写内容就是「增」（不增不减）。
                    assertThat(chinese.get(index))
                            .as("%s 第 %d 段：英文原文为空，中文也应留空", name, index + 1)
                            .isBlank();
                } else {
                    assertThat(chinese.get(index))
                            .as("%s 第 %d 段是空的；英文原文这一段有内容", name, index + 1)
                            .isNotBlank();
                }
            }
        }
    }

    @Test
    void every_entry_is_chinese_and_not_the_english_source_copied_back() {
        for (Map.Entry<String, List<String>> entry : readMerged().entrySet()) {
            String name = entry.getKey();
            String joined = String.join("", entry.getValue());
            List<String> source = SOURCE.get(name);
            assertThat(source).as("%s 不在英文数据集里——键写错了", name).isNotNull();

            assertThat(hasHan(joined)).as("%s 里一个汉字都没有", name).isTrue();
            assertThat(joined)
                    .as("%s 的「中文」与英文原文一模一样——英文被抄回来了", name)
                    .isNotEqualTo(String.join("", source));
        }
    }

    // ------------------------------------------------------------------

    private static void loadDataset() {
        JsonNode root;
        try (InputStream in = new ClassPathResource(DATASET).getInputStream()) {
            root = JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取英文数据集失败: " + DATASET, e);
        }
        if (!root.isArray()) {
            throw new IllegalStateException("数据集顶层应当是一个 JSON 数组: " + DATASET);
        }
        for (JsonNode node : root) {
            String name = node.path("name").asText();
            List<String> paragraphs = new ArrayList<>();
            JsonNode instructions = node.get("instructions");
            if (instructions != null && instructions.isArray()) {
                for (JsonNode paragraph : instructions) {
                    paragraphs.add(paragraph.asText());
                }
            }
            DATASET_NAMES.add(name);
            SOURCE.put(name, List.copyOf(paragraphs));
        }
    }

    private static Map<String, List<String>> readMerged() {
        try (InputStream in = new ClassPathResource(MERGED).getInputStream()) {
            return JSON.readValue(in, new TypeReference<LinkedHashMap<String, List<String>>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("读取合并产物失败: " + MERGED, e);
        }
    }

    private static List<String> nameZhKeys() {
        try (InputStream in = new ClassPathResource(NAME_ZH).getInputStream()) {
            Map<String, String> mapping = JSON.readValue(
                    in, new TypeReference<LinkedHashMap<String, String>>() {});
            return List.copyOf(mapping.keySet());
        } catch (IOException e) {
            throw new IllegalStateException("读取中文名映射失败: " + NAME_ZH, e);
        }
    }

    private static List<String> translatableNames() {
        return DATASET_NAMES.stream().filter(name -> !SOURCE.get(name).isEmpty()).toList();
    }

    private static List<String> withoutSourceNames() {
        return DATASET_NAMES.stream().filter(name -> SOURCE.get(name).isEmpty()).toList();
    }

    private static boolean hasHan(String text) {
        return text.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
