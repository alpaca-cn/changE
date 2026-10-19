package com.change.web;

import com.change.service.BodyMetricService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 身体数据的读写入口。
 *
 * <p>这一层只做路由：解析与校验全在 {@link BodyMetricService} 里。日期参数是
 * **字符串**而不是 LocalDate，也正是为了让「格式不对」走服务层的
 * {@link IllegalArgumentException}，由 {@link ApiErrors} 翻成与其它 400 同一种形状的
 * `{error}`——用 {@code @DateTimeFormat} 的话 from/to 会变成
 * {@code MethodArgumentTypeMismatchException}，错误正文里还带着参数名和类型名。
 *
 * <p>三个端点：写一条、读时间线、读每个类型的当前值。没有「一条身体数据的详情」
 * 端点——一行就是一次测量，详情与列表里的那一行是同一个东西。
 */
@RestController
@RequestMapping("/api/body-metrics")
public class BodyMetricController {

    private final BodyMetricService bodyMetrics;

    public BodyMetricController(BodyMetricService bodyMetrics) {
        this.bodyMetrics = bodyMetrics;
    }

    /**
     * 记一条身体数据。
     *
     * <p>请求体：{@code {"measuredOn":"2026-10-19","metricType":"weight","value":72.5,
     * "unit":null,"note":"早上空腹"}}。响应只有 {@code {"id":12}}——体重这类数字没有
     * 可实时算的派生值，不像一餐的热量。
     */
    @PostMapping
    public BodyMetricService.Created record(@RequestBody BodyMetricService.MetricInput input) {
        return bodyMetrics.record(input);
    }

    /**
     * 时间线：{@code ?type=weight&from=&to=&limit=}，全部可省略。
     *
     * <p>省略 type = 全部类型；from/to 是闭区间；limit 默认 200、封顶 1000。
     * 没有数据（或筛选出来是空的）返回 {@code []}，不是 404。
     */
    @GetMapping
    public List<BodyMetricService.MetricView> timeline(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        return bodyMetrics.timeline(type, from, to, limit);
    }

    /**
     * 库里实际出现过的类型 + 每个的最新值（身体数据页顶部的「当前值」卡片）。
     *
     * <p>固定路径放在 {@code @GetMapping} 之后声明只是可读性；Spring 的映射不依赖顺序。
     */
    @GetMapping("/types")
    public List<BodyMetricService.TypeSummary> types() {
        return bodyMetrics.types();
    }
}
