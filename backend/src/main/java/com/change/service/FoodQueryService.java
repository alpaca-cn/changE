package com.change.service;

import com.change.calc.Nutrition;
import com.change.domain.Food;
import com.change.domain.FoodPortion;
import com.change.repo.FoodPortionRepo;
import com.change.repo.FoodRepo;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 食物搜索：按关键词找食物，连它的每 100 克营养值与份量单位一起给出。
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
 * <p>食物库是**随包的 30 多条 + 手补的常吃项**，不是会长的用户表（设计文档说
 * 食物库不做管理界面、直接改数据即可）。全表进内存完全够用，而把过滤/排序/截断
 * 下推成 SQL 只会多一套要维护的查询与它的测试——与 {@code ExerciseQueryService}
 * 对 876 条动作做的取舍相同。真到了几万条再下推。
 */
@Service
public class FoodQueryService {

    /** 默认返回多少条。 */
    public static final int DEFAULT_LIMIT = 50;

    /** 上限，防止调用方一次把整个库拉走。 */
    public static final int MAX_LIMIT = 200;

    /** 一个份量单位。前端拿它算预览的克数；**入库的克数不用它**。 */
    public record PortionSummary(String unitName, double grams) {
    }

    /** 搜索结果里的一行。 */
    public record FoodSummary(long id, String nameZh, String nameEn, String source,
                              Nutrition per100, List<PortionSummary> portions) {
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

    private static PortionSummary toSummary(FoodPortion p) {
        return new PortionSummary(p.unitName, p.grams);
    }
}
