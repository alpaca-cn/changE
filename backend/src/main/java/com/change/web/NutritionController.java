package com.change.web;

import com.change.service.FoodQueryService;
import com.change.service.NutritionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 饮食的读写入口。
 *
 * <p>校验失败由 {@code ApiErrors} 统一翻成 400 {@code {error}}——服务里抛的
 * {@link IllegalArgumentException}（日期为空、餐次不合法、找不到食物……）本来会被
 * Spring 映射成 500，对调用方就成了「服务器坏了」。找不到的餐走
 * {@link ResponseStatusException}，{@code ApiErrors} 也会给它包上同一套 {@code {error}}。
 *
 * <p>两个带日期的查询都把 {@code date} 做成**可省略＝今天**。这样它没有
 * 「缺少必填参数」那条路径，也就不会出现一个形状与其它错误都不一样的 400。
 */
@RestController
@RequestMapping("/api")
public class NutritionController {

    private final NutritionService nutrition;
    private final FoodQueryService foodQuery;

    public NutritionController(NutritionService nutrition, FoodQueryService foodQuery) {
        this.nutrition = nutrition;
        this.foodQuery = foodQuery;
    }

    /**
     * 搜食物（选择器）。返回的每一条都带每 100 克营养值与份量单位——
     * 前端靠这两样在录入时**预览**热量；真正入库的克数仍由后端算。
     */
    @GetMapping("/foods")
    public List<FoodQueryService.FoodSummary> foods(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        return foodQuery.search(q, limit);
    }

    /**
     * 新建一条用户自己的食物。
     *
     * <p>用户填的是「**我这一份**多少克、含什么」，后端换算成每 100 克存 ——
     * 为什么要这么转，见 {@link FoodQueryService.CustomFoodInput} 的注释。
     *
     * <p>校验失败一律 400 带中文说明（重名、份量为 0、换算后超常识范围……）；
     * 报错信息是给人看的，措辞会直接出现在输入框下面。
     */
    @PostMapping("/foods")
    public FoodQueryService.FoodSummary createFood(
            @RequestBody FoodQueryService.CustomFoodInput input) {
        return foodQuery.create(input);
    }

    /** 记一餐。请求体见 {@link NutritionService.MealInput}——**注意没有 grams 字段**。 */
    @PostMapping("/meals")
    public NutritionService.Result record(@RequestBody NutritionService.MealInput input) {
        return nutrition.recordMeal(input);
    }

    /**
     * 改一餐的餐次。
     *
     * <p>用 POST 而不是 PATCH：外壳的 {@code api_request} 只放行 GET / POST
     * （见 http.rs 的白名单），PATCH 会让这个接口从应用里根本调不到。
     */
    @PostMapping("/meals/{id}/move")
    public Map<String, Object> moveMeal(@PathVariable long id,
                                        @RequestBody Map<String, String> body) {
        nutrition.moveMeal(id, body == null ? null : body.get("slot"));
        return Map.of("ok", true);
    }

    /** 删除一餐（连条目）。走变更日志，可以撤销。 */
    @PostMapping("/meals/{id}/delete")
    public Map<String, Object> deleteMeal(@PathVariable long id) {
        nutrition.deleteMeal(id);
        return Map.of("ok", true);
    }

    /** 一餐的详情；不存在时 404。 */
    @GetMapping("/meals/{id}")
    public NutritionService.MealDetail meal(@PathVariable long id) {
        return nutrition.mealDetail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这一餐: " + id));
    }

    /** 某一天的餐（省略 date = 今天）。没记过就返回空数组，不是 404。 */
    @GetMapping("/meals")
    public List<NutritionService.MealDetail> mealsOn(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return nutrition.mealsOn(date == null ? LocalDate.now() : date);
    }

    /** 某一天的热量与宏量营养素合计（省略 date = 今天）。**空的一天是全 0，不是 404。** */
    @GetMapping("/nutrition/daily")
    public NutritionService.DailyNutrition daily(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return nutrition.daily(date == null ? LocalDate.now() : date);
    }
}
