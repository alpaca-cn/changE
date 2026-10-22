package com.change.service;

import com.change.calc.Nutrition;
import com.change.domain.Food;
import com.change.domain.FoodPortion;
import com.change.repo.FoodPortionRepo;
import com.change.repo.FoodRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 食物搜索与**用户自建食物**。
 *
 * <h2>为什么一次把 portions 也带出来</h2>
 *
 * <p>前端要在**录入时**预览「1 碗米饭大约多少千卡」，而那就得知道「1 碗等于多少克」——
 * 这一份数据在 {@code food_portion} 里。让前端再发一次请求既慢又容易和 POST 时后端
 * 算出来的克数不一致。
 *
 * <p><b>但预览只是预览</b>：真正写进 {@code meal_item.grams} 的，永远是后端在
 * {@link NutritionService#recordMeal} 里用 {@code PortionConverter} 算的那个值。
 *
 * <h2>为什么在内存里做过滤与截断</h2>
 *
 * <p>食物库是**随包的 30 多条 + 用户自己长出来的常吃项**。全表进内存完全够用，
 * 而把过滤/排序/截断下推成 SQL 只会多一套要维护的查询与它的测试——与
 * {@code ExerciseQueryService} 对 876 条动作做的取舍相同。真到了几万条再下推。
 *
 * <h2>2026-10-22 起：用户可以自己建食物</h2>
 *
 * <p>起因很具体：大学生基本吃外卖，而外卖的名字是无穷的（「张亮麻辣烫」「沙县小吃」
 * 「黄焖鸡米饭」……），任何预置库都追不上。用户实测搜「包子」得到空列表，
 * 于是那一餐根本记不进去。预置库扩到 5000 条也解决不了，因为明天平台上又出新品。
 *
 * <p>所以食物库从「只读的随包数据」变成「随包数据 + 用户自己长出来的」。见 {@link #create}。
 */
@Service
public class FoodQueryService {

    /** 默认返回多少条。 */
    public static final int DEFAULT_LIMIT = 50;

    /** 上限，防止调用方一次把整个库拉走。 */
    public static final int MAX_LIMIT = 200;

    /**
     * 用户自建食物的来源标记。
     *
     * <p>**用库里已有的 {@code manual}，不新造一个 {@code user}。**
     * {@code food.source} 上有一条 CHECK 约束：
     * {@code source IN ('manual','cn-food-table','agent','takeout')}。
     * 新增一个值要写迁移：V1..V10 之后再加一条只为了换个词，不值得。
     * 而 `manual` 的语义本来就是「人工填的」，与「随包的 cn-food-table」区分得开。
     *
     * <p>2026-10-22 第一次实现时写了 "user"，11 个用例里 6 个撞在这条约束上 ——
     * 这类错在测试里撞出来是最便宜的，上线后撞就是「加食物报 500」。
     */
    public static final String USER_SOURCE = "manual";

    /**
     * 每 100 克里热量与各宏量的上界。用来挡住「把每份值当成每 100 克填」这类错误。
     *
     * <p>800 千卡/100 克：纯油脂是 900，几乎没有任何现实食物能到 800 以上。
     * 宏量 100 克/100 克：再高就物理上不可能了。
     */
    static final double MAX_KCAL_PER_100 = 800;
    static final double MAX_MACRO_PER_100 = 100;

    /** 一个份量单位。前端拿它算预览的克数；**入库的克数不用它**。 */
    public record PortionSummary(String unitName, double grams) {
    }

    /** 搜索结果里的一行。 */
    public record FoodSummary(long id, String nameZh, String nameEn, String source,
                              Nutrition per100, List<PortionSummary> portions) {
    }

    /**
     * 用户自建一条食物时提交的东西。
     *
     * <p><b>份量口径是这里最容易搞错的一处</b>，所以说清楚：用户填的是
     * 「**我这一份**吃了多少克、含多少克蛋白」——也就是**每份值**，
     * 而库里存的是**每 100 克值**。换算在 {@link #create} 里做一次。
     *
     * <p>为什么不直接让用户填每 100 克：包装上印的是「每份 250ml」，外卖单上写的是
     * 「一份约 400 克」，没有哪个场景会告诉他每 100 克是多少。要求他心算除以几，
     * 是把这个应用最没耐心的一步抬高。
     *
     * @param grams   这一份有多少克；必填且 &gt; 0
     * @param protein 这一份里含多少克蛋白（不是每 100 克）
     * @param fat     这一份里含多少克脂肪
     * @param carb    这一份里含多少克碳水
     * @param kcal    可选。**留空就按 4/4/9 算**（蛋白 4、碳水 4、脂肪 9 千卡/克）
     */
    public record CustomFoodInput(
            String nameZh,
            String nameEn,
            Double grams,
            Double protein,
            Double fat,
            Double carb,
            Double kcal) {
    }

    private final FoodRepo foods;
    private final FoodPortionRepo portions;

    public FoodQueryService(FoodRepo foods, FoodPortionRepo portions) {
        this.foods = foods;
        this.portions = portions;
    }

    /**
     * @param query 关键词，匹配中文名或英文名（英文不分大小写）；null/空白表示「列出全部」
     * @param limit 返回条数上限；null 用 {@link #DEFAULT_LIMIT}，并封顶在 {@link #MAX_LIMIT}
     */
    public List<FoodSummary> search(String query, Integer limit) {
        List<Food> candidates = (query == null || query.isBlank())
                ? foods.findAll()
                : foods.findByNameZhContainingOrNameEnContainingIgnoreCase(query, query);

        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        // 排序再截断，而不是截断再排序：否则「前 50 条」是数据库的返回顺序，
        // 同一份数据在不同运行里可能给出不同的页，用户会以为食物没了。
        List<Food> page = candidates.stream()
                .sorted(Comparator.comparing((Food f) -> f.nameZh).thenComparing(f -> f.id))
                .limit(cap)
                .toList();

        Map<Long, List<PortionSummary>> byFood = page.isEmpty() ? Map.of()
                : portions.findByFoodIdIn(page.stream().map(f -> f.id).toList()).stream()
                        .collect(Collectors.groupingBy(p -> p.foodId,
                                Collectors.mapping(FoodQueryService::toSummary, Collectors.toList())));

        return page.stream()
                .map(f -> new FoodSummary(f.id, f.nameZh, f.nameEn, f.source,
                        NutritionService.per100(f), byFood.getOrDefault(f.id, List.of())))
                .toList();
    }

    /**
     * 新建一条用户自己的食物，返回它（形状与搜索结果的每一项相同）。
     *
     * <h2>热量怎么算</h2>
     *
     * <p>用户填了 kcal 就用它；没填就按 **4/4/9** 从宏量推：蛋白 4、碳水 4、脂肪 9
     * 千卡每克。这是营养学的通用近似，误差通常在 5% 以内。
     *
     * <p><b>为什么不总是用 4/4/9 覆盖用户给的热量</b>：包装上的热量往往包含了
     * 膳食纤维、酒精、有机酸等不按 4/4/9 计的能量，硬替换会让「我抄的标签」
     * 和「应用显示的」对不上，而用户会怀疑是应用算错了。反过来，用户只填了热量、
     * 没填宏量时（很常见），宏量留 0 —— 那是「不知道」，界面上要据此显示，
     * 而不是假装很精确。
     *
     * <h2>为什么重名直接拒绝</h2>
     *
     * <p>同一个名字存两条，搜索页会出现两个长得一样、数值不同的选项，
     * 用户每次都得猜哪个是上次用的。拒绝并告诉他「直接搜它」更省事。
     *
     * @throws IllegalArgumentException 校验失败。由 {@code ApiErrors} 翻成 400 的中文说明。
     */
    @Transactional
    public FoodSummary create(CustomFoodInput input) {
        String nameZh = input.nameZh() == null ? "" : input.nameZh().trim();
        if (nameZh.isEmpty()) {
            throw new IllegalArgumentException("食物名字不能为空。");
        }
        if (nameZh.length() > 60) {
            throw new IllegalArgumentException("食物名字太长了（最多 60 个字）。");
        }

        double grams = input.grams() == null ? 0 : input.grams();
        if (!(grams > 0)) {
            throw new IllegalArgumentException("请填「这一份有多少克」，而且要大于 0。");
        }

        double protein = nonNegative(input.protein(), "蛋白质");
        double fat = nonNegative(input.fat(), "脂肪");
        double carb = nonNegative(input.carb(), "碳水");

        boolean hasMacros = protein > 0 || fat > 0 || carb > 0;
        boolean hasKcal = input.kcal() != null && input.kcal() > 0;
        if (!hasMacros && !hasKcal) {
            throw new IllegalArgumentException(
                    "至少填一项：热量，或者蛋白/脂肪/碳水里的任意一个。");
        }

        // 这一份的热量：用户给了就用，没给按 4/4/9 从宏量推
        double kcalPerServing = hasKcal ? input.kcal() : (4 * protein + 4 * carb + 9 * fat);

        // 每份 -> 每 100 克
        double kcalPer100 = kcalPerServing / grams * 100;
        double proteinPer100 = protein / grams * 100;
        double fatPer100 = fat / grams * 100;
        double carbPer100 = carb / grams * 100;

        // 合理性：这里挡的是「把每份当成每 100 克填」这类错误。
        // 不挡的话，一份 600 千卡的麻辣烫会被当成每 100 克 600 千卡，
        // 用户下次吃 300 克就会看到 1800 千卡。
        if (kcalPer100 > MAX_KCAL_PER_100) {
            throw new IllegalArgumentException(String.format(
                    "换算成每 100 克是 %.0f 千卡，超过常识范围。是不是「这一份多少克」填小了？",
                    kcalPer100));
        }
        if (proteinPer100 > MAX_MACRO_PER_100 || fatPer100 > MAX_MACRO_PER_100
                || carbPer100 > MAX_MACRO_PER_100) {
            throw new IllegalArgumentException(
                    "换算成每 100 克后，蛋白/脂肪/碳水有一项超过 100 克。是不是份量填小了？");
        }

        boolean exists = foods.findAll().stream()
                .anyMatch(f -> f.nameZh != null && f.nameZh.trim().equals(nameZh));
        if (exists) {
            throw new IllegalArgumentException(
                    "食物库里已经有「" + nameZh + "」了，直接搜它就行。");
        }

        Food food = new Food();
        food.nameZh = nameZh;
        food.nameEn = input.nameEn() == null || input.nameEn().isBlank()
                ? nameZh : input.nameEn().trim();
        food.source = USER_SOURCE;
        food.kcalPer100 = round1(kcalPer100);
        food.proteinPer100 = round1(proteinPer100);
        food.carbPer100 = round1(carbPer100);
        food.fatPer100 = round1(fatPer100);
        food.fiberPer100 = 0;
        food.sodiumPer100 = 0;
        Food saved = foods.save(food);

        // 顺手给一个「1 份 = grams 克」的份量单位：用户是按「份」想的，
        // 没有这个单位，他下次录入还得自己算克数。
        FoodPortion portion = new FoodPortion();
        portion.foodId = saved.id;
        portion.unitName = "1 份";
        portion.grams = grams;
        portions.save(portion);

        return new FoodSummary(saved.id, saved.nameZh, saved.nameEn, saved.source,
                NutritionService.per100(saved),
                List.of(new PortionSummary(portion.unitName, portion.grams)));
    }

    private static double nonNegative(Double v, String label) {
        if (v == null) {
            return 0;
        }
        if (v < 0) {
            throw new IllegalArgumentException(label + "不能是负数。");
        }
        return v;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static PortionSummary toSummary(FoodPortion p) {
        return new PortionSummary(p.unitName, p.grams);
    }
}
