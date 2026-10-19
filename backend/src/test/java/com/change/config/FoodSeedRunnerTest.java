package com.change.config;

import com.change.calc.Nutrition;
import com.change.domain.Food;
import com.change.repo.FoodPortionRepo;
import com.change.repo.FoodRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 常吃项 seed 的解析与安装。
 *
 * <p>解析部分是**纯函数**（{@link FoodSeedRunner#parse}），所以坏数据的每一种形态都能直接试；
 * 安装部分是「跑两遍」这种只有真库才说得清的事。
 *
 * <p>{@code @Transactional}：跑完回滚，不给共享库留行。tearDown 也不删——回滚就够了。
 */
@SpringBootTest
@Transactional
class FoodSeedRunnerTest {

    @Autowired FoodSeedRunner runner;
    @Autowired FoodRepo foods;
    @Autowired FoodPortionRepo portions;
    @Autowired JdbcTemplate jdbc;

    private static final String REAL_SEED = FoodSeedRunner.readResource();

    @BeforeEach
    void clean() {
        // 子表在前（food_portion 对 food 是 ON DELETE CASCADE，但显式删更不依赖 PRAGMA）
        jdbc.update("DELETE FROM food_portion");
        jdbc.update("DELETE FROM food");
    }

    // ---- 解析与校验（纯函数，不碰库）----

    @Test
    void the_real_seed_parses_and_has_at_least_thirty_foods() {
        List<FoodSeedRunner.FoodSpec> specs = FoodSeedRunner.parse(REAL_SEED);
        assertThat(specs).hasSizeGreaterThanOrEqualTo(30);
    }

    @Test
    void every_food_has_a_name_and_at_least_one_usable_portion() {
        // 份量单位不是可选的：没有它，「1 碗米饭」这种最常用的录法就选不出来。
        // grams > 0 是硬要求——库里是 CHECK (grams > 0)，一条 0 克的单位会让启动直接炸。
        for (FoodSeedRunner.FoodSpec spec : FoodSeedRunner.parse(REAL_SEED)) {
            assertThat(spec.nameZh()).as("中文名").isNotBlank();
            assertThat(spec.portions()).as("%s 的份量单位", spec.nameZh()).isNotEmpty();
            for (FoodSeedRunner.PortionSpec p : spec.portions()) {
                assertThat(p.unitName()).as("%s 的单位名", spec.nameZh()).isNotBlank();
                assertThat(p.grams()).as("%s 的「%s」克数", spec.nameZh(), p.unitName()).isGreaterThan(0);
            }
        }
    }

    @Test
    void every_value_is_non_negative_and_finite() {
        // Nutrition 的紧凑构造器本来就拒绝 NaN/负值，所以这条主要是防止有人**绕过**
        // 构造器直接改 JSON 里的值——它在解析阶段就会抛，报错里带得出是哪一条。
        for (FoodSeedRunner.FoodSpec spec : FoodSeedRunner.parse(REAL_SEED)) {
            Nutrition n = spec.per100();
            for (double v : new double[] {n.kcal(), n.protein(), n.carb(), n.fat(), n.fiber(), n.sodium()}) {
                assertThat(Double.isFinite(v) && v >= 0)
                        .as("%s 的 per100 值 %s", spec.nameZh(), v).isTrue();
            }
        }
    }

    @Test
    void every_food_declares_manual_as_its_source() {
        // **这条守的是一个法律事实，不是风格**：中国食物成分表的转录版有版权、
        // 不可再分发（设计文档明确要求分发版排除它）。这 35 条是手工写的近似值，
        // 一旦有一条标成 cn-food-table，就等于在数据里谎称它来自那份不可分发的表。
        List<String> sources = FoodSeedRunner.parse(REAL_SEED).stream()
                .map(FoodSeedRunner.FoodSpec::source).distinct().toList();
        assertThat(sources).containsExactly("manual");
        assertThat(sources).doesNotContain("cn-food-table");
    }

    @Test
    void the_note_says_the_values_are_approximate() {
        // 数值口径必须随数据一起走（JSON 不能写注释，所以说明是文件里的一个字段）。
        // 少了它，这份 seed 会被后来的人当成核对过的权威表。
        assertThat(REAL_SEED).contains("近似");
        assertThat(REAL_SEED).contains("未经逐条核对");
    }

    // ---- 坏数据必须响亮失败 ----

    @Test
    void rejects_a_misspelled_key_inside_per100() {
        // **这条是「静默变成 0」的杀手。** 把 protein 拼成 protien 时，宽容的解析器会
        // 把这份食物的蛋白质永远读成 0——一条含 31 克蛋白的鸡胸显示成 0 克，而没有任何迹象。
        // FoodSeedRunner 的 ObjectMapper 特意打开了 FAIL_ON_UNKNOWN_PROPERTIES。
        String bad = """
                {"foods":[
                  {"nameZh":"测试食物","per100":{"kcal":100,"protien":31,"carb":0,"fat":0,"fiber":0,"sodium":0},
                   "portions":[{"unitName":"份","grams":100}]}
                ]}""";
        assertThatThrownBy(() -> FoodSeedRunner.parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void rejects_a_portion_with_zero_grams() {
        assertThatThrownBy(() -> FoodSeedRunner.parse(withPortionGrams(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("克数必须为正");
    }

    @Test
    void rejects_a_portion_with_a_blank_unit_name() {
        assertThatThrownBy(() -> FoodSeedRunner.parse(withPortionGrams(100).replace("\"份\"", "\"  \"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有名字");
    }

    @Test
    void rejects_an_illegal_source() {
        String bad = withPortionGrams(100).replace("\"nameZh\"", "\"source\":\"随便\",\"nameZh\"");
        assertThatThrownBy(() -> FoodSeedRunner.parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source 不合法");
    }

    @Test
    void rejects_a_food_without_portions() {
        String bad = """
                {"foods":[{"nameZh":"测试食物","per100":{"kcal":1,"protein":1,"carb":0,"fat":0,"fiber":0,"sodium":0},
                           "portions":[]}]}""";
        assertThatThrownBy(() -> FoodSeedRunner.parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何份量单位");
    }

    @Test
    void rejects_an_empty_food_list() {
        assertThatThrownBy(() -> FoodSeedRunner.parse("{\"foods\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一条食物都没有");
    }

    /** 一条最小合法的 seed，便于在它上面做定点破坏。 */
    private static String withPortionGrams(double grams) {
        return "{\"foods\":[{\"nameZh\":\"测试食物\","
                + "\"per100\":{\"kcal\":100,\"protein\":1,\"carb\":0,\"fat\":0,\"fiber\":0,\"sodium\":0},"
                + "\"portions\":[{\"unitName\":\"份\",\"grams\":" + grams + "}]}]}";
    }

    // ---- 安装（真库）----

    @Test
    void seeds_the_library_when_it_is_empty() {
        int expected = FoodSeedRunner.parse(REAL_SEED).size();
        assertThat(foods.count()).isZero();

        runner.run(null);

        assertThat(foods.count()).isEqualTo(expected);
        // 单位也要真的落库，且总数对得上（每条至少一个）
        assertThat(portions.count()).isGreaterThanOrEqualTo(expected);
    }

    @Test
    void does_not_re_seed_when_the_library_already_has_anything() {
        runner.run(null);
        long afterFirst = foods.count();
        long portionsAfterFirst = portions.count();

        runner.run(null);

        assertThat(foods.count()).isEqualTo(afterFirst);
        assertThat(portions.count()).isEqualTo(portionsAfterFirst);
    }

    @Test
    void does_not_resurrect_a_food_the_user_deleted() {
        // **安装策略的核心行为**：用户删掉不想再看到的 seed 食物之后，重启不该把它加回来。
        // 「逐条按名字 upsert」的实现会在这条上红——那种实现每启动一次就复活一次。
        runner.run(null);
        long afterFirst = foods.count();
        jdbc.update("DELETE FROM food WHERE name_zh = '油条'");

        runner.run(null);

        assertThat(foods.count()).isEqualTo(afterFirst - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM food WHERE name_zh = '油条'", Integer.class))
                .as("删掉的不该被复活").isZero();
    }

    @Test
    void the_stored_values_match_the_parsed_ones() {
        // 落库这一段的六列必须逐个对应上——写错一列（比如 protein 落到 carb）就全错了，
        // 而界面上只会看到一个「差不多」的数字。
        runner.run(null);
        Food chicken = jdbc.queryForObject(
                "SELECT * FROM food WHERE name_zh = '鸡胸肉（熟）'", (rs, i) -> {
                    Food f = new Food();
                    f.nameZh = rs.getString("name_zh");
                    f.kcalPer100 = rs.getDouble("kcal_per100");
                    f.proteinPer100 = rs.getDouble("protein_per100");
                    f.carbPer100 = rs.getDouble("carb_per100");
                    f.fatPer100 = rs.getDouble("fat_per100");
                    f.fiberPer100 = rs.getDouble("fiber_per100");
                    f.sodiumPer100 = rs.getDouble("sodium_per100");
                    return f;
                });
        assertThat(chicken.kcalPer100).isEqualTo(165.0);
        assertThat(chicken.proteinPer100).isEqualTo(31.0);
        assertThat(chicken.carbPer100).isZero();
        assertThat(chicken.fatPer100).isEqualTo(3.6);
    }
}
