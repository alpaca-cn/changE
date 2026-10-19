package com.change.web;

import com.change.service.NutritionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 饮食的读写入口。
 *
 * <p>校验失败由 {@code ApiErrors} 统一翻成 400 {@code {error}}——服务里抛的
 * {@link IllegalArgumentException}（日期为空、餐次不合法、找不到食物……）本来会被
 * Spring 映射成 500，对调用方就成了「服务器坏了」。
 */
@RestController
@RequestMapping("/api")
public class NutritionController {

    private final NutritionService nutrition;

    public NutritionController(NutritionService nutrition) {
        this.nutrition = nutrition;
    }

    /** 记一餐。请求体见 {@link NutritionService.MealInput}——**注意没有 grams 字段**。 */
    @PostMapping("/meals")
    public NutritionService.Result record(@RequestBody NutritionService.MealInput input) {
        return nutrition.recordMeal(input);
    }
}
