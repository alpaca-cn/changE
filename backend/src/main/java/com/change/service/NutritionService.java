package com.change.service;

import com.change.calc.Nutrition;
import com.change.calc.NutritionCalculator;
import com.change.calc.PortionConverter;
import com.change.domain.Food;
import com.change.domain.Meal;
import com.change.domain.MealItem;
import com.change.repo.FoodPortionRepo;
import com.change.repo.FoodRepo;
import com.change.repo.MealItemRepo;
import com.change.repo.MealRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写一餐、读一餐、算当天摄入。
 *
 * <h2>三条口径（本类要守住的东西）</h2>
 *
 * <p><b>一、克数由后端算。</b>调用方（前端）给的是「多少份 + 什么单位」——
 * {@code amount} 与 {@code unitName}，**不给克数**。克数在这里用
 * {@link PortionConverter#toGrams} 把 {@code amount × 每单位克数} 算出来。
 * 让界面自己算就等于把「1 碗 = 150 克」抄一份到前端，两边一旦不一致，
 * 写进去的就是错的克数，而**热量永远是实时算的、没有快照可以兜底**。
 *
 * <p><b>二、热量永远实时算，绝不回存。</b>{@code meal_item} 上连 kcal 列都没有
 * （V4 的注释原文：{@code -- 注意：这里没有 kcal 字段，故意的。}）。这里算出来的合计
 * 只出现在**返回值**里，供界面显示；库里只有「食物 + 份量 + 克数」，
 * 所以改份量、换食物、修食物的营养值之后，任何一个数字都是新的。
 *
 * <p><b>三、grams 存未定标的值。</b>{@code calc/package-info} 约定二：
 * 定标（两位小数）属于显示层，先舍再累加会引入有方向性的系统偏差，
 * 而且会让「按份量录」与「按克录」两条路径算出的热量不再一致。
 *
 * <h2>审计</h2>
 *
 * <p>三层逐行写 {@code change_log}（{@code origin='user'}），与 {@link WorkoutService}
 * 同一套思路：父行与子行**都**记，只记父行的话，回退父行会级联删掉子行而日志里
 * 没有东西让它们回来。
 */
@Service
public class NutritionService {

    private static final String ORIGIN = "user";

    /** 一个条目要写的数据。{@code unitName} 为 null（或空）表示 {@code amount} 已经是克。 */
    public record ItemInput(Long foodId, double amount, String unitName) {
    }

    /** 一次用餐。{@code items} 至少一条。 */
    public record MealInput(LocalDate mealDate, String slot, LocalDateTime eatenAt, String note,
                            List<ItemInput> items) {
    }

    /** 写入结果。{@code nutrition} 是**实时算出来**的合计，不落库。 */
    public record Result(long mealId, int items, Nutrition nutrition) {
    }

    private final MealRepo meals;
    private final MealItemRepo items;
    private final FoodRepo foods;
    private final FoodPortionRepo portions;
    private final ChangeLogService changeLog;

    public NutritionService(MealRepo meals, MealItemRepo items, FoodRepo foods,
                            FoodPortionRepo portions, ChangeLogService changeLog) {
        this.meals = meals;
        this.items = items;
        this.foods = foods;
        this.portions = portions;
        this.changeLog = changeLog;
    }

    /**
     * 记一餐。
     *
     * <p><b>一个事务。</b>餐与它的条目任何一步失败就整批回滚：半写入的一餐比没记更糟——
     * 用户以为记好了，其实少了几个条目，而且他不会知道少了哪个。
     */
    @Transactional
    public Result recordMeal(MealInput input) {
        requireValid(input);

        Meal meal = new Meal();
        meal.mealDate = input.mealDate();
        meal.slot = input.slot();
        meal.eatenAt = input.eatenAt();
        meal.note = input.note();
        meals.save(meal);
        changeLog.recordInsert("meal", meal.id, mealSnapshot(meal), ORIGIN, null);

        int count = 0;
        List<Nutrition> perItem = new ArrayList<>();
        for (int i = 0; i < input.items().size(); i++) {
            ItemInput in = input.items().get(i);
            Food food = foods.findById(in.foodId())
                    .orElseThrow(() -> new IllegalArgumentException("找不到食物 id " + in.foodId()));

            double grams = PortionConverter.toGrams(in.amount(), blankToNull(in.unitName()), unitsOf(food.id));

            MealItem item = new MealItem();
            item.mealId = meal.id;
            item.foodId = food.id;
            item.orderIndex = i;
            item.amount = in.amount();
            item.unitName = blankToNull(in.unitName());
            item.grams = grams;
            item.origin = "manual";
            items.save(item);
            changeLog.recordInsert("meal_item", item.id, itemSnapshot(item), ORIGIN, null);

            perItem.add(NutritionCalculator.forGrams(per100(food), grams));
            count++;
        }
        return new Result(meal.id, count, NutritionCalculator.sum(perItem));
    }

    /** 某食物的份量单位表，转成 {@code calc} 层认的形状。 */
    private List<PortionConverter.Unit> unitsOf(long foodId) {
        return portions.findByFoodId(foodId).stream()
                .map(p -> new PortionConverter.Unit(p.unitName, p.grams))
                .toList();
    }

    /** 食物的每 100 克基准值。变量名带 per100 前缀是 {@code calc} 包的约定。 */
    static Nutrition per100(Food food) {
        return new Nutrition(food.kcalPer100, food.proteinPer100, food.carbPer100,
                food.fatPer100, food.fiberPer100, food.sodiumPer100);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void requireValid(MealInput input) {
        if (input == null) {
            throw new IllegalArgumentException("用餐内容不能为空");
        }
        if (input.mealDate() == null) {
            throw new IllegalArgumentException("用餐日期不能为空");
        }
        // 在服务层先拦：不然非法的 slot 会撞 meal.slot 的 CHECK 变成 500，
        // 而它其实是一个该回 400 的输入错误。
        if (input.slot() == null || !Meal.VALID_SLOTS.contains(input.slot())) {
            throw new IllegalArgumentException("餐次不合法：" + input.slot()
                    + "（合法值 " + Meal.VALID_SLOTS + "）");
        }
        if (input.items() == null || input.items().isEmpty()) {
            throw new IllegalArgumentException("一餐至少要有一个条目");
        }
        for (ItemInput in : input.items()) {
            if (in == null || in.foodId() == null) {
                throw new IllegalArgumentException("每个条目都必须带 foodId");
            }
            // amount > 0 由 PortionConverter 与库里的 CHECK (amount > 0) 双重保证，
            // 这里不重复判——重复的校验会让人以为少写一处就漏了。
        }
    }

    private static Map<String, Object> mealSnapshot(Meal m) {
        Map<String, Object> map = new LinkedHashMap<>();
        // 日期/时间戳一律 toString()：ChangeLogService 用的是裸 ObjectMapper
        // （没注册 JavaTimeModule），放 LocalDate 会在**记日志时**抛异常，
        // 把一次正常的记录整个弄失败。
        map.put("meal_date", m.mealDate.toString());
        map.put("slot", m.slot);
        map.put("eaten_at", m.eatenAt == null ? null : m.eatenAt.toString());
        map.put("note", m.note);
        map.put("photo_id", m.photoId);
        return map;
    }

    private static Map<String, Object> itemSnapshot(MealItem i) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("meal_id", i.mealId);
        map.put("food_id", i.foodId);
        map.put("order_index", i.orderIndex);
        map.put("amount", i.amount);
        map.put("unit_name", i.unitName);
        map.put("grams", i.grams);
        map.put("origin", i.origin);
        return map;
    }
}
