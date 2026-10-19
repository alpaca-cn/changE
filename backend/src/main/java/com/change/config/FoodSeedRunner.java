package com.change.config;

import com.change.calc.Nutrition;
import com.change.domain.Food;
import com.change.domain.FoodPortion;
import com.change.repo.FoodPortionRepo;
import com.change.repo.FoodRepo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时把随包的常吃项写进食物库。
 *
 * <h2>⚠️ 数值口径（这个类存在的理由之一就是把它说清楚）</h2>
 *
 * {@code seed/food-zh.json} 里每 100 克的六项数值是**近似值**，取自常见公开资料的量级，
 * 由智能体填写、**未经逐条核对**。中国食物成分表的转录版有版权、不可再分发
 * （设计文档明确要求分发版排除它），所以随包只带这一份手工近似值。
 *
 * <p>任何地方都不许把这些数字说成「权威值」。设计文档对精度的定位是
 * 「不需要太精准，大致热量即可；精度不是目标，可修正是」——食物库不做管理界面，
 * 要精确就改这张表。
 *
 * <h2>安装策略：只在食物库为空时装一次</h2>
 *
 * <p>{@code food} 表里只要**有任何一条**就跳过。比「逐条按名字 upsert」好在：
 * 用户删掉某条不想再看到的 seed 食物之后，重启**不会把它复活**（逐条 upsert 会把
 * 删掉的重新插回去，而设计文档说食物库就是「直接改数据即可」）。代价是把整张表删空
 * 会重新装一遍——那反而是想要的。
 *
 * <h2>为什么自己 new 一个 ObjectMapper</h2>
 *
 * <p>要的是**严格**：把 {@code FAIL_ON_UNKNOWN_PROPERTIES} 打开，这样 seed 文件里
 * 写错一个键（比如把 {@code protein} 拼成 {@code protien}）会**当场报错**，
 * 而不是默默按 0 处理——那会让一条食物的蛋白质永远是 0，且没有任何迹象。
 */
@Component
public class FoodSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FoodSeedRunner.class);

    /** 随包 seed 的位置。 */
    public static final String RESOURCE = "seed/food-zh.json";

    /** 缺省来源。JSON 里不写 source 就是这个。 */
    static final String DEFAULT_SOURCE = "manual";

    /** 一个份量单位。 */
    public record PortionSpec(String unitName, double grams) {
    }

    /** 一条种子食物。{@code source} 在 JSON 里可省略。 */
    public record FoodSpec(String nameZh, String nameEn, String source,
                           Nutrition per100, List<PortionSpec> portions) {
    }

    /** seed 文件的顶层。{@code _note} 是随数据走的说明（JSON 不能写注释）。 */
    public record SeedFile(@JsonProperty("_note") String note, List<FoodSpec> foods) {
    }

    private final FoodRepo foods;
    private final FoodPortionRepo portions;

    public FoodSeedRunner(FoodRepo foods, FoodPortionRepo portions) {
        this.foods = foods;
        this.portions = portions;
    }

    @Override
    public void run(ApplicationArguments args) {
        long existing = foods.count();
        if (existing > 0) {
            log.info("食物库：库里已有 {} 条，跳过 seed 安装（删掉的食物不会被复活）", existing);
            return;
        }

        List<FoodSpec> specs = parse(readResource());
        int inserted = 0;
        int units = 0;
        for (FoodSpec spec : specs) {
            Food food = new Food();
            food.nameZh = spec.nameZh();
            food.nameEn = spec.nameEn();
            food.source = spec.source();
            food.kcalPer100 = spec.per100().kcal();
            food.proteinPer100 = spec.per100().protein();
            food.carbPer100 = spec.per100().carb();
            food.fatPer100 = spec.per100().fat();
            food.fiberPer100 = spec.per100().fiber();
            food.sodiumPer100 = spec.per100().sodium();
            foods.save(food);

            for (PortionSpec p : spec.portions()) {
                FoodPortion fp = new FoodPortion();
                fp.foodId = food.id;
                fp.unitName = p.unitName();
                fp.grams = p.grams();
                portions.save(fp);
                units++;
            }
            inserted++;
        }
        log.info("食物库：写入 {} 条（{} 个份量单位）/ 共 {} 条 seed。数值为**近似值**，未经逐条核对",
                inserted, units, specs.size());
    }

    /** 读随包 seed。读不到或读不动就抛——一个装不上的 seed 比静默少 35 条食物更该被发现。 */
    static String readResource() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不到随包 seed " + RESOURCE, e);
        }
    }

    /**
     * 解析并校验 seed。**纯函数**（不碰数据库、不碰 Spring），所以坏数据的每一种形态
     * 都能在毫秒级的测试里试出来，不用起上下文。
     *
     * <p>校验的是「进得了库」：{@code food_portion.grams} 有 {@code CHECK (grams > 0)}、
     * 六项营养值由 {@link Nutrition} 的紧凑构造器保证非负有限、{@code food.name_zh} 是
     * {@code NOT NULL}。这些在这里先拦一道，报错才能指出是**哪一条**食物坏在哪。
     *
     * @throws IllegalArgumentException 任何一条不合法
     */
    public static List<FoodSpec> parse(String json) {
        // 显式打开 FAIL_ON_UNKNOWN_PROPERTIES。**诚实标注（变异实测）**：这一行今天是
        // **冗余的**——裸 `new ObjectMapper()` 本来就把它默认为 true（是 **Spring 容器里那个**
        // ObjectMapper 被 Spring Boot 关掉了）。所以「删掉这一行」不会让任何测试变红
        // （已实测：删掉后 15 条仍全绿）。留着它的理由是：① 把「必须严格」这个要求写在
        // 它该在的地方；② 哪天有人改成注入 Spring 的 ObjectMapper（那是个很自然的想法），
        // 这一行就是唯一还拦得住「protein 拼成 protien 而静默变 0」的东西——
        // `rejects_a_misspelled_key_inside_per100` 会在同一刻变红。
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        SeedFile file;
        try {
            file = mapper.readValue(json, SeedFile.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("seed 文件不是合法的 JSON（或字段名对不上）: " + e.getMessage(), e);
        }
        if (file == null || file.foods() == null || file.foods().isEmpty()) {
            throw new IllegalArgumentException("seed 文件里一条食物都没有");
        }

        List<FoodSpec> out = new ArrayList<>();
        for (FoodSpec spec : file.foods()) {
            if (spec == null) {
                throw new IllegalArgumentException("seed 里出现了 null 条目");
            }
            String where = spec.nameZh() == null || spec.nameZh().isBlank()
                    ? "<无名条目>" : spec.nameZh();
            if (spec.nameZh() == null || spec.nameZh().isBlank()) {
                throw new IllegalArgumentException("有一条食物没有中文名");
            }
            if (spec.per100() == null) {
                throw new IllegalArgumentException("食物「" + where + "」缺少 per100");
            }
            String source = spec.source() == null || spec.source().isBlank()
                    ? DEFAULT_SOURCE : spec.source();
            if (!Food.VALID_SOURCES.contains(source)) {
                throw new IllegalArgumentException("食物「" + where + "」的 source 不合法：" + source
                        + "（合法值 " + Food.VALID_SOURCES + "）");
            }
            if (spec.portions() == null || spec.portions().isEmpty()) {
                throw new IllegalArgumentException(
                        "食物「" + where + "」没有任何份量单位——那样「1 碗」这种常用量就选不了");
            }
            for (PortionSpec p : spec.portions()) {
                if (p == null || p.unitName() == null || p.unitName().isBlank()) {
                    throw new IllegalArgumentException("食物「" + where + "」有一个份量单位没有名字");
                }
                if (!Double.isFinite(p.grams()) || p.grams() <= 0) {
                    throw new IllegalArgumentException("食物「" + where + "」的单位「" + p.unitName()
                            + "」克数必须为正且有限，实际 " + p.grams()
                            + "（库里是 CHECK (grams > 0)）");
                }
            }
            out.add(new FoodSpec(spec.nameZh(), spec.nameEn(), source, spec.per100(),
                    List.copyOf(spec.portions())));
        }
        return List.copyOf(out);
    }
}
