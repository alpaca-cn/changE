package com.change.seed;

import com.change.domain.MuscleGroup;
import com.change.repo.MuscleGroupRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 17 块肌群种子数据的守卫。
 *
 * <p>三类断言，各自拦一类失效：
 * <ul>
 *   <li>常量自身的形状（17 行、中英文名、无重复、region id 在词汇表里、min/max 成对、
 *       英文名与数据集逐字一致）——纯 Java 常量就能查；</li>
 *   <li><b>生成物与常量一致</b>（{@link #the_seed_migration_matches_the_java_constants}）——
 *       V7 那份 SQL 是从 {@link MuscleGroupSeed#ALL} 生成的。两层比对：先把 SQL 里
 *       每一行的 6 个字段解析出来与常量逐字段比（失败信息能指出哪一行哪个字段），
 *       再逐字节比对整个文件（连注释、排版、行数、有没有多出一条 UPDATE 都算）。</li>
 *   <li><b>库里真的有 17 行</b>（{@link #after_migration_the_database_really_has_twenty_seven_groups}）——
 *       「写了 SQL」不等于「迁移跑过、INSERT 生效」。这条读真实的库，按 id 顺序
 *       （= AUTOINCREMENT 的插入顺序）把每一列与常量对齐。
 *       例外：{@code region_ids} 在 V10 之后不再等于 V7 的占位值，值与素材的交叉
 *       校验在 {@code com.change.MuscleRegionMappingTest}；这里只查字符串形状。</li>
 * </ul>
 *
 * <p>为什么要 @SpringBootTest：这些断言里有两类必须碰到真实的库与真实的 Spring
 * Data 派生查询（V7 是否被 Flyway 应用、{@code findByNameEn} 是否真能解析出
 * {@code name_en} 列）。代价是这个类的纯常量测试也会启动一次上下文；上下文在
 * 整个测试套件里是复用的，值得。
 *
 * <p><b>V7 是生成的，不要手改。</b>改种子请改 {@link MuscleGroupSeed#ALL}，然后：
 * <pre>
 *   cd backend
 *   mvn -B clean test -Dtest=MuscleGroupSeedTest#regenerate_the_migration \
 *       -Dchange.seed.write=true
 * </pre>
 * 再跑一遍完整测试。{@code clean} 是必要的：V7 的字节变了，旧库的 Flyway checksum
 * 会对不上，而测试库在 {@code target/data} 下。
 */
@SpringBootTest
class MuscleGroupSeedTest {

    /** 生成物在 classpath 上的位置（也用于读文件比对）。 */
    private static final String MIGRATION_RESOURCE = "db/migration/V7__muscle_group_seed.sql";

    /** 生成器写盘的位置。Maven 在 backend/ 下跑测试，所以相对路径是稳定的。 */
    private static final Path MIGRATION_PATH = Path.of("src/main/resources", MIGRATION_RESOURCE);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MuscleGroupRepo muscleGroups;

    // ------------------------------------------------------------------
    // 常量自身的形状
    // ------------------------------------------------------------------

    @Test
    void the_java_constant_list_has_seventeen_entries() {
        // 数据集的 primaryMuscles 是 17 值固定枚举（实测确认）。
        // 数量一变就说明数据集换了版本，或者我们漏加/多加了一块肌肉。
        // **常量仍是 17**：V11 的 10 个子部位是手写迁移、不来自这个常量列表。
        // 库里的 27 行由 after_migration_the_database_really_has_twenty_seven_groups 守。
        assertThat(MuscleGroupSeed.ALL).hasSize(17);
    }

    @Test
    void every_group_has_a_chinese_and_an_english_name() {
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            assertThat(g.nameEn).as("nameEn").isNotBlank();
            assertThat(g.nameZh).as("nameZh").isNotBlank();
            // 中文名必须真的含中文，否则只是把英文抄了一遍
            assertThat(g.nameZh.chars().anyMatch(c -> c > 0x4E00 && c < 0x9FFF))
                    .as("nameZh 应含汉字: %s", g.nameZh).isTrue();
        }
    }

    @Test
    void english_names_are_unique() {
        // 库里有唯一索引，这里提前挡住，免得导入时才发现
        List<String> names = MuscleGroupSeed.ALL.stream().map(g -> g.nameEn).toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void every_region_id_comes_from_the_declared_vocabulary() {
        // 这是「region_ids 只有一份词汇表」的守卫：任何写进种子的 id
        // 都必须是 MuscleRegion 里声明过的，不能是随手打的字符串。
        Set<String> vocabulary = Arrays.stream(MuscleRegion.values())
                .map(r -> r.id).collect(Collectors.toSet());
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            for (String id : g.regionIds) {
                assertThat(vocabulary).as("region id %s 不在词汇表里", id).contains(id);
            }
        }
    }

    @Test
    void every_group_has_at_least_one_region() {
        // 没有区域的肌群在人体图上永远点不亮，那是静默的空白。
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            assertThat(g.regionIds).as("%s 没有 region", g.nameEn).isNotEmpty();
        }
    }

    @Test
    void weekly_targets_are_either_both_null_or_a_valid_pair() {
        // classify 对「半个区间」返回 NOT_CONFIGURED，所以 min/max 必须成对。
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            boolean minNull = g.weeklySetsMin == null;
            boolean maxNull = g.weeklySetsMax == null;
            assertThat(minNull).as("%s 的 min/max 必须成对", g.nameEn).isEqualTo(maxNull);
            if (!minNull) {
                assertThat(g.weeklySetsMin).isGreaterThanOrEqualTo(0);
                assertThat(g.weeklySetsMax).isGreaterThan(g.weeklySetsMin);
            }
        }
    }

    @Test
    void the_seventeen_english_names_match_the_dataset_vocabulary() {
        // 与数据集逐字对齐：差一个字符，876 条里所有挂这块肌肉的动作都会导不进来。
        // 这份期望值是从缓存的 exercises.json 独立算出来的 17 个值，不是从数据集文档抄的。
        assertThat(MuscleGroupSeed.ALL.stream().map(g -> g.nameEn).collect(Collectors.toSet()))
                .isEqualTo(Set.of(
                        "abdominals", "abductors", "adductors", "biceps", "calves",
                        "chest", "forearms", "glutes", "hamstrings", "lats",
                        "lower back", "middle back", "neck", "quadriceps",
                        "shoulders", "traps", "triceps"));
    }

    // ------------------------------------------------------------------
    // 生成物（V7）与常量的一致性 —— 本任务最重要的一条
    // ------------------------------------------------------------------

    @Test
    void the_seed_migration_matches_the_java_constants() throws IOException {
        // V7 的 SQL 是由 MuscleGroupSeed 生成的，但生成之后两者可能各自被改。
        // 这条测试把它们重新钉在一起：读 V7 文件，把每一行的
        // (name_zh, name_en, body_part, region_ids, min, max) 与 Java 常量逐个比。
        // 少了这条，「Java 改了、SQL 没改」不会有任何东西报警，
        // 而库里的 17 块肌肉就和代码里的不一致了。
        String onDisk = readMigration();

        List<SeedRow> expected = MuscleGroupSeed.ALL.stream()
                .map(g -> new SeedRow(g.nameZh, g.nameEn, g.bodyPart,
                        String.join(",", g.regionIds), g.weeklySetsMin, g.weeklySetsMax))
                .toList();
        List<SeedRow> actual = parseSeedRows(onDisk);

        assertThat(actual)
                .as("V7 里每一行的 6 个字段必须与 MuscleGroupSeed.ALL 逐字段相同（含顺序）")
                .containsExactlyElementsOf(expected);

        // 第二层：逐字节。字段级比对放过的东西（注释、排版、多一条 UPDATE、
        // 某一行被删掉但字段恰好凑巧对得上）在这里全部被拦住。
        // 生成物的唯一正确形态就是生成器此刻给出的文本。
        assertThat(onDisk)
                .as("V7 是生成物：它与生成器输出必须逐字相同。"
                        + "改种子请改 MuscleGroupSeed.ALL 并重新生成，不要手改这个文件")
                .isEqualTo(toSql());
    }

    /**
     * 重新生成 V7。默认**跳过**，只有显式传 {@code -Dchange.seed.write=true} 才落盘。
     *
     * <p>为什么生成器写在测试里而不是 production 代码里：它只在开发时跑一次，
     * 属于构建工具而不是运行时逻辑；把它放进 {@code src/main} 会让一份永远不会
     * 被调用的 SQL 拼接代码跟着 jar 走。数据本身的唯一权威仍是
     * {@link MuscleGroupSeed#ALL}。
     *
     * <p>为什么写盘之后还要读回来断言：编码错误（BOM、CRLF）或路径写错时，
     * 生成命令应当立刻失败，而不是留下一份「看起来生成了」的坏文件。
     */
    @Test
    void regenerate_the_migration() throws IOException {
        assumeTrue(Boolean.getBoolean("change.seed.write"),
                "只有 -Dchange.seed.write=true 时才重新生成迁移");

        Files.writeString(MIGRATION_PATH, toSql(), StandardCharsets.UTF_8);

        assertThat(Files.readString(MIGRATION_PATH, StandardCharsets.UTF_8))
                .as("落盘后读回的字节必须与生成结果一致（UTF-8、无 BOM、LF）")
                .isEqualTo(toSql());
    }

    // ------------------------------------------------------------------
    // 真实的库
    // ------------------------------------------------------------------

    @Test
    void after_migration_the_database_really_has_twenty_seven_groups() {
        // 「写了」不等于「生效」：迁移可能没跑、可能被 Flyway 跳过、
        // 也可能 INSERT 的列名写错而 sqlite 宽容地接受。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name_en, name_zh, body_part, region_ids, weekly_sets_min, weekly_sets_max, parent_en " +
                "FROM muscle_group ORDER BY id");

        assertThat(rows).hasSize(27);

        int topLevel = MuscleGroupSeed.ALL.size();   // 17:V7 生成的那一批
        assertThat(rows.subList(0, topLevel).stream().map(r -> (String) r.get("name_en")).toList())
                .as("前 17 行（V7 生成）按 id 顺序应与 Java 常量一致")
                .isEqualTo(MuscleGroupSeed.ALL.stream().map(g -> g.nameEn).toList());

        // 后 10 行是 V11 手写的子部位：不来自常量列表，但必须都挂得住父组
        assertThat(rows.subList(topLevel, rows.size()))
                .as("V11 追加的 10 行子部位")
                .hasSize(10)
                .allMatch(r -> r.get("parent_en") != null);
        assertThat(rows.subList(0, topLevel))
                .as("顶层不该有 parent_en")
                .allMatch(r -> r.get("parent_en") == null);

        for (int i = 0; i < topLevel; i++) {
            MuscleGroupSeed.G g = MuscleGroupSeed.ALL.get(i);
            Map<String, Object> row = rows.get(i);
            String at = "第 " + (i + 1) + " 行（name_en=" + g.nameEn + "）";

            assertThat(row.get("name_zh")).as("%s 的 name_zh", at).isEqualTo(g.nameZh);
            assertThat(row.get("name_en")).as("%s 的 name_en", at).isEqualTo(g.nameEn);
            assertThat(row.get("body_part")).as("%s 的 body_part", at).isEqualTo(g.bodyPart);
            // region_ids 在库里是**一个字符串**，不是数组。
            //
            // 这里不再要求它等于 Java 常量：V7 写入的是占位名
            // （chest_left,chest_right），而 V10 已把它们换成 vendored 人体图素材的
            // 真实 id（chest-upper-left,...）。Java 常量与 V7 描述的是 V7 那个时点的
            // 历史值（V1–V9 已发布、不可改），不是库里的当前值。
            //
            // 值本身的正确性由 MuscleRegionMappingTest 守护：它把库里的每个 id 与
            // 素材原文里的 id 对起来——那比「与旧常量一致」有意义得多。这里只守住
            // 字符串的形状：非空、逗号分隔、逗号两侧没有空格。
            String regionIds = (String) row.get("region_ids");
            // V12 之后被拆的 5 个父组故意没有区域（2D 交给子部位），
            // 所以这里只要求在**有值**时形状正确。
            if (regionIds == null) {
                assertThat(java.util.Set.of("chest", "shoulders", "traps",
                        "middle back", "lower back"))
                        .as("%s 没有区域，只允许被拆的父组这样", at)
                        .contains(g.nameEn);
                continue;
            }
            assertThat(regionIds).as("%s 的 region_ids", at).isNotBlank();
            assertThat(regionIds.split(",", -1))
                    .as("%s 的 region_ids 不应含空项或前后空格: %s", at, regionIds)
                    .allMatch(id -> !id.isBlank() && id.equals(id.trim()));
            assertThat(asInteger(row.get("weekly_sets_min")))
                    .as("%s 的 weekly_sets_min", at).isEqualTo(g.weeklySetsMin);
            assertThat(asInteger(row.get("weekly_sets_max")))
                    .as("%s 的 weekly_sets_max", at).isEqualTo(g.weeklySetsMax);
        }
    }

    @Test
    void the_repo_finds_groups_by_their_exact_english_name() {
        // Task 4 的导入会用 findByNameEn，查不到就抛异常。这里先确认派生查询
        // 真能解析到 name_en 列，并且**不做**大小写/下划线的模糊匹配。
        for (MuscleGroupSeed.G g : MuscleGroupSeed.ALL) {
            assertThat(muscleGroups.findByNameEn(g.nameEn))
                    .as("findByNameEn(%s) 应当命中种子行", g.nameEn)
                    .isPresent();
        }

        MuscleGroup middleBack = muscleGroups.findByNameEn("middle back").orElseThrow();
        assertThat(middleBack.nameZh).isEqualTo("中背部");

        // 这是最容易发生的写错方式：空格变下划线。派生查询必须精确匹配，
        // 否则导入会把动作挂到错误的肌群上。
        assertThat(muscleGroups.findByNameEn("middle_back")).isEmpty();
        assertThat(muscleGroups.findByNameEn("Chest")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 生成器
    // ------------------------------------------------------------------

    /**
     * 从 {@link MuscleGroupSeed#ALL} 生成 V7 的完整正文。
     *
     * <p><b>{@code V7__muscle_group_seed.sql} 是本方法产出的生成物，不要手改。</b>
     * 改种子请改 {@link MuscleGroupSeed#ALL}，再用上面类注释里的命令重新生成。
     *
     * <p>输出格式是**固定的、可解析的**：一条 INSERT，每行一个元组，字段顺序
     * ({@code name_zh, name_en, body_part, region_ids, min, max}) 与 INSERT 的列清单
     * 一致，字符串一律单引号、数字与 NULL 裸写。{@link #parseSeedRows} 按这个格式
     * 反向解析；两者由 {@link #the_seed_migration_matches_the_java_constants} 互相钉住。
     */
    static String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("-- V7: 17 块肌群的种子数据。\n");
        sb.append("--\n");
        sb.append("-- ⚠️ 这个文件是**生成的**，不要手改。它由 MuscleGroupSeedTest 从\n");
        sb.append("-- com.change.seed.MuscleGroupSeed.ALL 生成；改种子请改那个类，然后重新生成：\n");
        sb.append("--   cd backend\n");
        sb.append("--   mvn -B clean test -Dtest=MuscleGroupSeedTest#regenerate_the_migration \\\n");
        sb.append("--       -Dchange.seed.write=true\n");
        sb.append("--\n");
        sb.append("-- 为什么要生成而不是手写：同一份 17 行数据会同时存在于 Java 常量与这份 SQL 里，\n");
        sb.append("-- 手抄两份中文名与数值必然漂移；而「Java 改了、SQL 没改」没有任何东西会报警，\n");
        sb.append("-- 库里的 17 块肌肉就会和代码里的不一致。生成之后两者仍可能被各自改动，所以\n");
        sb.append("-- MuscleGroupSeedTest#the_seed_migration_matches_the_java_constants 先逐行解析\n");
        sb.append("-- 下面的 6 个字段与 Java 常量比对，再逐字节比对本文件与生成器输出。\n");
        sb.append("--\n");
        sb.append("-- region_ids 是**逗号分隔的字符串**（不是数组）：多区域肌群写两个 id。\n");
        sb.append("-- 词汇表见 com.change.seed.MuscleRegion。\n");
        sb.append("--\n");
        sb.append("-- weekly_sets_min/max 是界面上「本周容量」提醒的比较基准，不是训练建议；\n");
        sb.append("-- neck 写 NULL：颈部训练不该被「本周 0 组」追着（见 MuscleGroupSeed 的类注释）。\n");
        sb.append("\n");
        sb.append("INSERT INTO muscle_group (name_zh, name_en, body_part, region_ids, ")
                .append("weekly_sets_min, weekly_sets_max) VALUES\n");

        for (int i = 0; i < MuscleGroupSeed.ALL.size(); i++) {
            MuscleGroupSeed.G g = MuscleGroupSeed.ALL.get(i);
            sb.append("    (")
                    .append(sqlString(g.nameZh)).append(", ")
                    .append(sqlString(g.nameEn)).append(", ")
                    .append(sqlString(g.bodyPart)).append(", ")
                    .append(sqlString(String.join(",", g.regionIds))).append(", ")
                    .append(g.weeklySetsMin == null ? "NULL" : g.weeklySetsMin.toString()).append(", ")
                    .append(g.weeklySetsMax == null ? "NULL" : g.weeklySetsMax.toString())
                    .append(")")
                    .append(i == MuscleGroupSeed.ALL.size() - 1 ? ";\n" : ",\n");
        }
        return sb.toString();
    }

    /** SQL 字符串字面量：单引号内的单引号写两遍。 */
    private static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------
    // 解析生成物（故意做成格式严格的反向解析，而不是脆弱的整行正则）
    // ------------------------------------------------------------------

    /** 种子的一行。字段类型与 {@link MuscleGroupSeed.G} 对齐，便于直接比较。 */
    record SeedRow(String nameZh, String nameEn, String bodyPart,
                   String regionIds, Integer min, Integer max) {}

    /** 解析后的一个字段：是否带引号决定了它是字符串还是 NULL/数字。 */
    private record Token(String value, boolean quoted) {}

    /**
     * 从 V7 的 SQL 里解出每一行元组。
     *
     * <p>只认生成器写出的固定形状：一条 {@code INSERT INTO muscle_group ... VALUES}，
     * 之后每行一个以 {@code (} 开头、以 {@code ),} 或 {@code );} 结尾的元组。
     * 任何偏离（少字段、多字段、结尾不对、引号不闭合）都直接让断言失败——
     * 这正是想要的：生成器与解析器对格式的共同约定，比「用正则猜整行」牢固得多。
     */
    static List<SeedRow> parseSeedRows(String sql) {
        String[] lines = sql.replace("\r\n", "\n").split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("INSERT INTO muscle_group ")) {
                start = i + 1;
                break;
            }
        }
        assertThat(start)
                .as("V7 里应当有一条 `INSERT INTO muscle_group ...` 语句")
                .isPositive();

        List<SeedRow> rows = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (!line.startsWith("(")) break;

            String tail = line.endsWith(");") ? ");" : line.endsWith("),") ? ")," : null;
            assertThat(tail)
                    .as("V7 第 %d 行必须以 `),` 或 `);` 结尾，实际是: %s", i + 1, line)
                    .isNotNull();

            String body = line.substring(1, line.length() - tail.length());
            List<Token> tokens = splitTokens(body);
            assertThat(tokens)
                    .as("V7 第 %d 行必须恰好 6 个字段（name_zh/name_en/body_part/region_ids/min/max）: %s",
                            i + 1, line)
                    .hasSize(6);

            rows.add(new SeedRow(
                    text(tokens.get(0), i + 1),
                    text(tokens.get(1), i + 1),
                    text(tokens.get(2), i + 1),
                    text(tokens.get(3), i + 1),
                    number(tokens.get(4), i + 1),
                    number(tokens.get(5), i + 1)));

            if (line.endsWith(");")) break;
        }
        return rows;
    }

    /**
     * 按逗号切字段，但**逗号在引号里不算分隔符**（region_ids 本身含逗号）。
     *
     * <p>SQL 字符串里的 {@code ''} 还原成一个单引号。引号不闭合时返回的 token
     * 仍会被后续断言拦下（字段数或结尾必然不对），这里不另抛异常。
     */
    private static List<Token> splitTokens(String body) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean quoted = false;

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuote) {
                if (c == '\'') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '\'') {
                inQuote = true;
                quoted = true;
            } else if (c == ',') {
                tokens.add(new Token(current.toString().trim(), quoted));
                current.setLength(0);
                quoted = false;
            } else {
                current.append(c);
            }
        }
        tokens.add(new Token(current.toString().trim(), quoted));
        return tokens;
    }

    private static String text(Token token, int line) {
        assertThat(token.quoted())
                .as("V7 第 %d 行的字符串字段必须带单引号，实际是: %s", line, token.value())
                .isTrue();
        return token.value();
    }

    private static Integer number(Token token, int line) {
        assertThat(token.quoted())
                .as("V7 第 %d 行的数值字段不应带引号，实际是: %s", line, token.value())
                .isFalse();
        if (token.value().equals("NULL")) return null;
        try {
            return Integer.valueOf(token.value());
        } catch (NumberFormatException e) {
            throw new AssertionError(
                    "V7 第 " + line + " 行的数值字段既不是 NULL 也不是整数: " + token.value(), e);
        }
    }

    // ------------------------------------------------------------------
    // 文件与库的小工具
    // ------------------------------------------------------------------

    private static String readMigration() throws IOException {
        try (var in = new ClassPathResource(MIGRATION_RESOURCE).getInputStream()) {
            // 只归一化行尾：断言关心的是内容，不该因为 checkout 的行尾设置而变红。
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }
    }

    /** SQLite 的 INTEGER 可能以 Integer 或 Long 返回，统一收成 Integer。 */
    private static Integer asInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
