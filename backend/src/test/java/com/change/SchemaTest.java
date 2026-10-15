package com.change;

import com.change.calc.VolumeCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 十五张业务表的结构快照。
 *
 * 为什么用一张「期望清单」而不是逐个断言表存在：逐个断言只能发现「少建了」，
 * 发现不了「多建了」——多出来的表（比如 V1 的探针表没被清掉）会让下一个人以为
 * 它是模型的一部分。containsExactlyInAnyOrderElementsOf 两侧都收口。
 *
 * 为什么这一轮要补列级 / 索引级 / 约束级三层快照：Task 3 的代码质量审查做了 8 处
 * 破坏——去掉 NOT NULL、多一列、删掉部分唯一索引、删掉 CHECK、删掉外键——套件**依然
 * 12/12 全绿**。当时只有 4 条断言，且都只问「某张表某几列在不在」，对约束、
 * 索引、可空性完全不设防。而迁移一旦发布就不可再改（改 V1/V2/V3/V4 的任何一个字节都会
 * 让跑过它的库抛 FlywayValidateException），所以现在这一版 schema 的守卫只能建在测试里。
 *
 * 三层各拦一类破坏：
 * - 列快照（列清单 + NOT NULL 清单）拦「多一列 / 少一列 / NOT NULL 被拿掉」；
 * - 索引快照（名字 + 唯一性 + 是否部分索引）拦「索引被删 / 被改名 / 唯一性被去掉」——
 *   SQLite 对「另起一个名字再建一次」不报错，只会静默留下两个功能重复的索引；
 * - 约束快照（sqlite_master.sql 里的关键片段）拦「CHECK / REFERENCES ... ON DELETE
 *   CASCADE / DEFAULT 被删」——这些在 pragma 里问不出来（pragma 只报外键的目标表与动作，
 *   报不出 CHECK 的内容），只有 DDL 原文里有。
 *
 * 注意约束快照断言的是 DDL 片段，不是「写入真的被拒」。两者都值得有，但只有前者能
 * 精确指出「哪一条约束没了」；后者已经在验收里逐条实测过。
 */
@SpringBootTest
class SchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private static final Set<String> EXPECTED = Set.of(
            "exercise", "workout_session", "session_exercise", "workout_set",
            "muscle_group", "exercise_muscle",
            "food", "food_portion", "meal", "meal_item",
            "photo", "body_metric", "chat_session", "chat_message", "change_log");

    @Test
    void all_fifteen_tables_exist() {
        // sqlite_% 排除的是 SQLite 自己维护的表（AUTOINCREMENT 会建 sqlite_sequence、
        // ANALYZE 会建 sqlite_stat1）；flyway_schema_history 是迁移框架的历史表，
        // 不属于业务模型。
        // 这里刻意不排除 schema_probe：它若还在，这条断言就该红。
        //
        // 将来新增**内部**表时要同步更新 EXPECTED，否则这条「多建了也要红」的收口会先把
        // 真正的业务表变化挡住，让人误以为只是内部表噪音。要补的只有**不以 sqlite_ 开头**
        // 的那些：FTS5 的影子表（影子表名由虚拟表名派生，如 content= 指向的表，以及
        // <表>_data / _idx / _content / _docsize / _config）不在本次排除之列，必须逐个写进
        // EXPECTED；而 sqlite_stat1 / sqlite_sequence 已被上面的 NOT LIKE 排除，不必也不该
        // 写进去——写了反而让「这份清单等于业务模型」这句话失真。
        List<String> names = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'", String.class);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void probe_table_was_dropped_by_v2() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='schema_probe'",
                Integer.class);
        assertThat(n)
                .as("V2 的第一行应当 DROP 掉 V1 的探针表，让它不再留在用户的库里")
                .isZero();
    }

    @Test
    void meal_item_has_no_stored_calorie_column() {
        // 这条是防回归：一旦有人「顺手」加了 kcal 列，测试立刻失败。
        // 走 pragma_table_info 而不是 PRAGMA table_info：后者是「一行多列」的表函数，
        // queryForList(..., String.class) 会对它抛 IncorrectResultSetColumnCountException
        // （org.springframework.jdbc 下的类，与 IncorrectResultSizeDataAccessException 是
        // 兄弟关系，instanceof 判不出来），断言就永远走不到 doesNotContain 那一行。
        assertThat(columns("meal_item")).doesNotContain("kcal", "calories", "energy");
    }

    @Test
    void workout_set_supports_timed_and_per_side() {
        // 用 containsExactlyInAnyOrder 而不是 contains：contains 对「多出一列」恒绿，
        // 而「多出一列没人察觉」正是本轮审查实测发生的破坏之一。
        assertThat(columns("workout_set")).containsExactlyInAnyOrder(
                "id", "session_exercise_id", "set_index", "set_type", "weight", "repetitions",
                "duration_seconds", "per_side", "rir", "rest_seconds", "completed",
                "target_weight", "target_reps");
    }

    // ------------------------------------------------------------------
    // 第一层：列级快照
    // ------------------------------------------------------------------

    /**
     * 列清单**逐表、按声明顺序**精确断言，外加每表的 NOT NULL 列清单。
     *
     * 顺序也算在内是有意的：pragma_table_info 按声明顺序返回，而列顺序是「一份迁移
     * 建出来什么」的一部分，插入到中间的新列值得被看见（它往往意味着有人在原地改
     * 一份已发布的迁移）。
     */
    @Test
    void column_snapshot_is_exact_for_all_fifteen_tables() {
        Map<String, List<String>> expectedColumns = new LinkedHashMap<>();
        expectedColumns.put("muscle_group", List.of(
                "id", "name_zh", "name_en", "body_part", "region_ids",
                "weekly_sets_min", "weekly_sets_max"));
        expectedColumns.put("exercise", List.of(
                "id", "name_en", "name_zh", "equipment", "is_compound", "default_unit",
                "source_id", "note", "archived"));
        expectedColumns.put("exercise_muscle", List.of(
                "exercise_id", "muscle_group_id", "role"));
        expectedColumns.put("workout_session", List.of(
                "id", "session_date", "started_at", "ended_at", "title", "perceived_state",
                "note", "source_routine_day_id"));
        expectedColumns.put("session_exercise", List.of(
                "id", "session_id", "exercise_id", "order_index", "note", "superset_group"));
        expectedColumns.put("workout_set", List.of(
                "id", "session_exercise_id", "set_index", "set_type", "weight", "repetitions",
                "duration_seconds", "per_side", "rir", "rest_seconds", "completed",
                "target_weight", "target_reps"));
        expectedColumns.put("food", List.of(
                "id", "name_zh", "name_en", "source", "kcal_per100", "protein_per100",
                "carb_per100", "fat_per100", "fiber_per100", "sodium_per100"));
        expectedColumns.put("food_portion", List.of(
                "id", "food_id", "unit_name", "grams"));
        expectedColumns.put("photo", List.of(
                "id", "taken_on", "rel_path", "sha256", "width", "height", "kind", "note"));
        expectedColumns.put("meal", List.of(
                "id", "meal_date", "slot", "eaten_at", "note", "photo_id"));
        expectedColumns.put("meal_item", List.of(
                "id", "meal_id", "food_id", "order_index", "amount", "unit_name", "grams",
                "origin"));
        expectedColumns.put("body_metric", List.of(
                "id", "measured_on", "metric_type", "value", "unit", "note"));
        expectedColumns.put("chat_session", List.of(
                "id", "title", "created_at"));
        expectedColumns.put("chat_message", List.of(
                "id", "session_id", "role", "content", "tool_calls", "created_at"));
        expectedColumns.put("change_log", List.of(
                "id", "changed_at", "origin", "action", "target_table", "target_id",
                "before_json", "after_json", "chat_session_id", "undone"));

        // 双侧收口：EXPECTED 与这里的键集合必须一致，少写一张表也会红。
        assertThat(expectedColumns.keySet())
                .as("列快照必须覆盖全部十五张表")
                .containsExactlyInAnyOrderElementsOf(EXPECTED);

        expectedColumns.forEach((table, columns) ->
                assertThat(columns(table))
                        .as("表 %s 的列清单（多一列、少一列、改名、换序都会红）", table)
                        .containsExactlyElementsOf(columns));
    }

    /**
     * NOT NULL 清单。这是本轮审查第 1、6 条破坏的唯一守卫：去掉
     * `muscle_group.name_zh` 或 `food.kcal_per100` 的 NOT NULL 之后，列清单纹丝不动。
     */
    @Test
    void not_null_snapshot_is_exact_for_all_fifteen_tables() {
        Map<String, List<String>> expectedNotNull = new LinkedHashMap<>();
        expectedNotNull.put("muscle_group", List.of("name_zh", "name_en", "body_part"));
        expectedNotNull.put("exercise", List.of("name_en", "is_compound", "default_unit", "archived"));
        expectedNotNull.put("exercise_muscle", List.of("exercise_id", "muscle_group_id", "role"));
        expectedNotNull.put("workout_session", List.of("session_date"));
        expectedNotNull.put("session_exercise", List.of("session_id", "exercise_id", "order_index"));
        expectedNotNull.put("workout_set", List.of(
                "session_exercise_id", "set_index", "set_type", "per_side", "completed"));
        expectedNotNull.put("food", List.of(
                "name_zh", "source", "kcal_per100", "protein_per100", "carb_per100",
                "fat_per100", "fiber_per100", "sodium_per100"));
        expectedNotNull.put("food_portion", List.of("food_id", "unit_name", "grams"));
        expectedNotNull.put("photo", List.of("taken_on", "rel_path", "sha256", "kind"));
        expectedNotNull.put("meal", List.of("meal_date", "slot"));
        expectedNotNull.put("meal_item", List.of(
                "meal_id", "food_id", "order_index", "amount", "grams", "origin"));
        expectedNotNull.put("body_metric", List.of("measured_on", "metric_type", "value", "unit"));
        expectedNotNull.put("chat_session", List.of("created_at"));
        expectedNotNull.put("chat_message", List.of("session_id", "role", "created_at"));
        expectedNotNull.put("change_log", List.of(
                "changed_at", "origin", "action", "target_table", "target_id", "after_json",
                "undone"));

        assertThat(expectedNotNull.keySet())
                .as("NOT NULL 快照必须覆盖全部十五张表")
                .containsExactlyInAnyOrderElementsOf(EXPECTED);

        expectedNotNull.forEach((table, columns) ->
                assertThat(notNullColumns(table))
                        .as("表 %s 的 NOT NULL 列（漏写 NOT NULL 在 SQLite 里等于静默可空）", table)
                        .containsExactlyElementsOf(columns));
    }

    // ------------------------------------------------------------------
    // 第二层：索引级快照
    // ------------------------------------------------------------------

    /**
     * 业务索引的完整清单（名字 + 唯一性 + 是否部分索引）。sqlite_ 开头的自动索引
     * （UNIQUE 约束 / 复合主键产生的）不算业务索引，不进这张表。
     *
     * 命名规则（与 V2 文件头同一句）：唯一索引 `ux_<表名全称>_<列>`，普通索引
     * `ix_<表名全称>_<列>`，表名不缩写。这里把名字也钉死，是因为 SQLite 允许
     * 同名索引以外的新名字静默重建——两个功能重复的索引不会报错，只会悄悄变慢。
     */
    @Test
    void index_snapshot_names_and_uniqueness() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ux_muscle_group_en", "UNIQUE");
        expected.put("ux_exercise_source", "UNIQUE PARTIAL");
        expected.put("ux_exercise_name_en", "UNIQUE");
        expected.put("ix_exercise_name_zh", "NOT UNIQUE");
        expected.put("ix_exercise_muscle_muscle_group", "NOT UNIQUE");
        expected.put("ix_session_date", "NOT UNIQUE");
        expected.put("ix_session_exercise_session", "NOT UNIQUE");
        expected.put("ux_session_exercise_session_order", "UNIQUE");
        // 这里**没有** ix_workout_set_session_exercise：它的访问路径由下面这条复合唯一
        // 索引的左前缀提供，单列索引已按冗余删除。这条清单是双侧收口的，所以「又把它
        // 加回来」同样会让本测试变红——冗余索引不会报错，只会悄悄变慢，只能靠这里挡。
        expected.put("ux_workout_set_session_exercise_index", "UNIQUE");
        expected.put("ix_food_name_zh", "NOT UNIQUE");
        expected.put("ux_food_portion", "UNIQUE");
        expected.put("ux_photo_sha", "UNIQUE");
        expected.put("ix_photo_kind_date", "NOT UNIQUE");
        expected.put("ix_meal_date", "NOT UNIQUE");
        expected.put("ix_meal_item_meal", "NOT UNIQUE");
        expected.put("ix_body_metric_type_date", "NOT UNIQUE");
        expected.put("ix_chat_message_session", "NOT UNIQUE");
        expected.put("ix_change_log_target", "NOT UNIQUE");
        expected.put("ix_change_log_time", "NOT UNIQUE");

        assertThat(businessIndexes())
                .as("业务索引清单（名字 + 唯一性 + 是否部分索引，多一个少一个都要红）")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    // ------------------------------------------------------------------
    // 第三层：约束级快照（DDL 原文片段）
    // ------------------------------------------------------------------

    /**
     * 每张表的 DDL 里必须出现的关键片段：CHECK / REFERENCES ... ON DELETE ... /
     * NOT NULL / DEFAULT。
     *
     * 为什么读 sqlite_master.sql 而不是 pragma：CHECK 的内容在 pragma 里根本问不出来
     * （PRAGMA table_info 只给非空标记与默认值），`REFERENCES` 的目标动作虽然能用
     * pragma_foreign_key_list 读到，但读不出「这一列上到底写没写约束」。DDL 原文是
     * 唯一能同时覆盖这几类的地方；对应地，断言必须写成**带尾随逗号或整行**的形式，
     * 否则 `REFERENCES photo(id)` 会把 `REFERENCES photo(id) ON DELETE SET NULL`
     * 也算通过——那正好丢掉我们要守的那个动作。
     */
    @Test
    void constraint_snapshot_key_ddl_clauses() {
        assertThat(ddlOf("muscle_group")).contains(
                collapseWhitespace("name_zh         TEXT    NOT NULL"));

        // 部分唯一索引的定义体：只约束有外部来源的动作，手工动作可以重复。
        // 名字层面的检查在 index_snapshot 里，这里确认「部分」这个语义没被丢掉
        // （去掉 WHERE 就变成「手工动作也只能有一个」，那是另一种错）。
        assertThat(indexDdl("ux_exercise_source"))
                .contains(collapseWhitespace("WHERE source_id IS NOT NULL"));

        // 四个纯日期列的 GLOB 形状约束。少一个，那一路的等值查询就会在某天静默失效。
        assertThat(ddlOf("workout_session")).contains(collapseWhitespace(
                "CHECK (session_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
        assertThat(ddlOf("meal")).contains(collapseWhitespace(
                "CHECK (meal_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
        assertThat(ddlOf("body_metric")).contains(collapseWhitespace(
                "CHECK (measured_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
        assertThat(ddlOf("photo")).contains(collapseWhitespace(
                "CHECK (taken_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));

        // 真时间戳列**不该**被套上日期形状约束——它们本来就含时间。
        assertThat(ddlOf("workout_session")).doesNotContain("CHECK (started_at");
        assertThat(ddlOf("workout_session")).doesNotContain("CHECK (ended_at");
        assertThat(ddlOf("meal")).doesNotContain("CHECK (eaten_at");
        assertThat(ddlOf("chat_message")).doesNotContain("CHECK (created_at");
        assertThat(ddlOf("change_log")).doesNotContain("CHECK (changed_at");

        // 枚举 CHECK
        assertThat(ddlOf("exercise_muscle")).contains(
                collapseWhitespace("CHECK (role IN ('primary', 'secondary'))"));
        assertThat(ddlOf("workout_set")).contains(collapseWhitespace(
                "CHECK (set_type IN ('normal','warmup','dropset','myo','partial', "
                        + "'forced','tut','iso','jump'))"));
        assertThat(ddlOf("food")).contains(
                collapseWhitespace("CHECK (source IN ('manual','cn-food-table','agent','takeout'))"));
        assertThat(ddlOf("meal")).contains(
                collapseWhitespace("CHECK (slot IN ('breakfast','lunch','dinner','snack'))"));
        assertThat(ddlOf("meal_item")).contains(
                collapseWhitespace("CHECK (origin IN ('manual','photo','agent','takeout'))"));
        assertThat(ddlOf("photo")).contains(
                collapseWhitespace("CHECK (kind IN ('food','body','other'))"));
        assertThat(ddlOf("chat_message")).contains(
                collapseWhitespace("CHECK (role IN ('user','assistant','tool'))"));
        assertThat(ddlOf("change_log")).contains(
                collapseWhitespace("CHECK (origin IN ('user','agent'))"));
        assertThat(ddlOf("change_log")).contains(
                collapseWhitespace("CHECK (action IN ('insert','update'))"));
        // 「一键回退」的守卫：action='update' 必须有 before 快照，否则这行永远回不去，
        // 而 UI 只看 action 就会画出一个按下去无事可撤的「撤销」按钮。
        assertThat(ddlOf("change_log")).contains(
                collapseWhitespace("CHECK (action <> 'update' OR before_json IS NOT NULL)"));

        // workout_set 的数值合法性（刻意不加 NOT NULL：三空行是有意的状态）
        assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (weight IS NULL OR weight >= 0)"));
        assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (repetitions IS NULL OR repetitions >= 0)"));
        assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (duration_seconds IS NULL OR duration_seconds > 0)"));
        assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (target_weight IS NULL OR target_weight >= 0)"));
        assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (target_reps IS NULL OR target_reps >= 0)"));

        // 级联删除：少一个 REFERENCES ... ON DELETE CASCADE，删训练/删会话就会留下孤儿行，
        // 而且**不会有任何报错**。尾随逗号是必须的，防止基础外键形式蒙混通过。
        assertThat(ddlOf("exercise_muscle")).contains(collapseWhitespace(
                "exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE"));
        assertThat(ddlOf("exercise_muscle")).contains(collapseWhitespace(
                "muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE"));
        assertThat(ddlOf("session_exercise")).contains(collapseWhitespace(
                "session_id    INTEGER NOT NULL REFERENCES workout_session(id) ON DELETE CASCADE"));
        assertThat(ddlOf("workout_set")).contains(collapseWhitespace(
                "session_exercise_id INTEGER NOT NULL REFERENCES session_exercise(id) ON DELETE CASCADE"));
        assertThat(ddlOf("food_portion")).contains(collapseWhitespace(
                "food_id   INTEGER NOT NULL REFERENCES food(id) ON DELETE CASCADE"));
        assertThat(ddlOf("meal_item")).contains(collapseWhitespace(
                "meal_id      INTEGER NOT NULL REFERENCES meal(id) ON DELETE CASCADE"));
        assertThat(ddlOf("chat_message")).contains(collapseWhitespace(
                "session_id    INTEGER NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE"));

        // 不级联的外键：动作与食物被引用时不允许连带删除，所以这里是裸 REFERENCES（带逗号）。
        assertThat(ddlOf("session_exercise")).contains(collapseWhitespace(
                "exercise_id   INTEGER NOT NULL REFERENCES exercise(id),"));
        assertThat(ddlOf("meal_item")).contains(collapseWhitespace(
                "food_id      INTEGER NOT NULL REFERENCES food(id),"));

        // meal.photo_id：照片是餐的可选元数据，删照片只能清空这一格，绝不能删掉用餐记录。
        assertThat(ddlOf("meal")).contains(
                collapseWhitespace("REFERENCES photo(id) ON DELETE SET NULL"));

        // 营养值必须有默认 0 且非空：既非空又有默认，去掉哪一个都会让「只填了名字的食物」
        // 在读取时炸掉或者悄悄变成 NULL 热量。
        assertThat(ddlOf("food")).contains(collapseWhitespace("kcal_per100 REAL NOT NULL DEFAULT 0"));
        assertThat(ddlOf("food")).contains(collapseWhitespace("protein_per100 REAL NOT NULL DEFAULT 0"));
        assertThat(ddlOf("food")).contains(collapseWhitespace("carb_per100 REAL NOT NULL DEFAULT 0"));
        assertThat(ddlOf("food")).contains(collapseWhitespace("fat_per100 REAL NOT NULL DEFAULT 0"));
        assertThat(ddlOf("food")).contains(collapseWhitespace("fiber_per100 REAL NOT NULL DEFAULT 0"));
        assertThat(ddlOf("food")).contains(collapseWhitespace("sodium_per100 REAL NOT NULL DEFAULT 0"));

        // autoincrement：丢掉它，SQLite 会把删掉的最大 rowid 重新发出去，
        // 于是「删掉第 7 条训练之后再建一条」会拿到 7，而 change_log 里还留着旧的 7。
        // 十四张表都有单列 id 主键；exercise_muscle 是复合主键的关联表
        // （PRIMARY KEY (exercise_id, muscle_group_id)），本来就没有 AUTOINCREMENT，
        // 它由上面那条 DDL 断言里的 PRIMARY KEY 片段守着。
        EXPECTED.stream()
                .filter(table -> !"exercise_muscle".equals(table))
                .forEach(table -> assertThat(ddlOf(table))
                        .as("表 %s 的主键必须显式 AUTOINCREMENT", table)
                        .contains(collapseWhitespace("INTEGER PRIMARY KEY AUTOINCREMENT")));
        assertThat(ddlOf("exercise_muscle")).contains(
                collapseWhitespace("PRIMARY KEY (exercise_id, muscle_group_id)"));
    }

    /**
     * 「Java 侧白名单 == DDL 侧白名单」的防漂移测试。
     *
     * 上面那条只钉住了 **DB 侧**（`sqlite_master.sql` 里逐字的
     * `CHECK (role IN ('primary', 'secondary'))`）。缺的是另一半：
     * `VolumeCalculator.Entry` 的合法角色集是**另一份手写拷贝**，某一侧改了另一侧不会红。
     * 这条把两份值集拉在一起逐项比对（含**顺序**——DDL 里写的就是
     * `'primary', 'secondary'`），任何一侧改动都会失败。
     *
     * 将来若合法的肌群角色扩容（比如加 `'stabilizer'`），**两处都要改**：
     * `V2__training.sql` 的 CHECK 与 `VolumeCalculator.VALID_ROLES`。迁移一旦发布
     * 就不能再原地改（见本类顶部的说明），所以那种扩容只能新加一份迁移去重建该列的约束。
     */
    @Test
    void role_whitelist_matches_the_ddl() {
        String ddl = ddlOf("exercise_muscle");

        // 只取 role 那一条 CHECK 的 IN (...) 值集
        Matcher m = Pattern.compile("role\\s+IN\\s*\\(([^)]*)\\)").matcher(ddl);
        assertThat(m.find())
                .as("exercise_muscle 的 DDL 里应当有 role IN (...) 形式的 CHECK：%s", ddl)
                .isTrue();
        List<String> fromDdl = new ArrayList<>();
        Matcher v = Pattern.compile("'([^']*)'").matcher(m.group(1));
        while (v.find()) {
            fromDdl.add(v.group(1));
        }

        assertThat(fromDdl)
                .as("DDL 里的合法角色集")
                .isNotEmpty();
        assertThat(VolumeCalculator.VALID_ROLES)
                .as("Java 侧 VALID_ROLES 必须与 exercise_muscle.role 的 CHECK 逐项一致")
                .containsExactlyElementsOf(fromDdl);
    }

    // ------------------------------------------------------------------
    // 结构查询工具
    // ------------------------------------------------------------------

    /** 按声明顺序返回列名。用 pragma_table_info 表函数，不能用 PRAGMA table_info。 */
    private List<String> columns(String table) {
        return jdbc.queryForList("SELECT name FROM pragma_table_info('" + table + "')", String.class);
    }

    /** 按声明顺序返回非空列名（不含主键：pragma 的 notnull 对 INTEGER PRIMARY KEY 恒为 0）。 */
    private List<String> notNullColumns(String table) {
        return jdbc.queryForList(
                "SELECT name FROM pragma_table_info('" + table + "') WHERE \"notnull\" = 1",
                String.class);
    }

    /**
     * 业务索引 -> "UNIQUE" / "NOT UNIQUE"（可加 " PARTIAL"）。sqlite_ 开头的自动索引
     * 由 UNIQUE 约束与复合主键产生，不属于本迁移显式建的索引，不计入。
     *
     * `PRAGMA index_list` 是「一行多列」的表函数，按 SQLite 的规则加表名参数写成
     * `pragma_index_list('表')` 就能在 FROM 里用。注意**不要**写 pragma_index_list_all：
     * 实测该名字不存在（SQLite 3.53.1 报 `no such table: pragma_index_list_all`）。
     */
    private Map<String, String> businessIndexes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String table : EXPECTED) {
            jdbc.query(
                    "SELECT name, \"unique\", partial FROM pragma_index_list('" + table + "') "
                            + "WHERE name NOT LIKE 'sqlite_%'",
                    rs -> {
                        while (rs.next()) {
                            out.put(rs.getString("name"),
                                    (rs.getInt("unique") == 1 ? "UNIQUE" : "NOT UNIQUE")
                                            + (rs.getInt("partial") == 1 ? " PARTIAL" : ""));
                        }
                        return null;
                    });
        }
        return out;
    }

    /** 建表 DDL 原文，空白折叠成单空格，便于断言跨行的 CHECK。 */
    private String ddlOf(String table) {
        return collapseWhitespace(jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name = ?", String.class, table));
    }

    private String indexDdl(String index) {
        return collapseWhitespace(jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='index' AND name = ?", String.class, index));
    }

    private static String collapseWhitespace(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
