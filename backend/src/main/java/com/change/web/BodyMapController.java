package com.change.web;

import com.change.service.BodyMapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人体图（2D 热力）的读入口。
 *
 * <p>路径是设计文档冻结的 {@code /api/body-map/volume}——**不要**改成
 * {@code /api/volume/...} 去和既有的「本周容量」端点挤在一起：那两个端点的形状与
 * 演进节奏不同（人体图还带 {@code lastTrainedOn} / {@code regionIds}）。
 *
 * <p>{@code date} 收成 {@link String} 而不是 {@code LocalDate} + {@code @DateTimeFormat}：
 * 解析放在 {@link BodyMapService#volume}，格式错抛 {@link IllegalArgumentException}，
 * 由 {@link ApiErrors} 统一翻成 400 + {@code {error}}（与 {@code ProgressService} 的
 * from/to 一致）。用注解绑定的 {@code LocalDate} 也能得到 400，但错误正文是
 * 「参数 date 格式不对」那句通用文案，说不出「date 必须是 YYYY-MM-DD，实际为 …」。
 */
@RestController
@RequestMapping("/api/body-map")
public class BodyMapController {

    private final BodyMapService bodyMap;

    public BodyMapController(BodyMapService bodyMap) {
        this.bodyMap = bodyMap;
    }

    /**
     * 某一天（省略 = 今天）所在那一周的各肌群容量 + 上次训练日期。
     *
     * <p>没有 404：这是聚合端点，17 块肌群永远在库里，「空」是 {@code sets: 0}
     * 与 {@code lastTrainedOn: null}，不是「找不到」。
     */
    @GetMapping("/volume")
    public BodyMapService.BodyMapVolume volume(@RequestParam(required = false) String date) {
        return bodyMap.volume(date);
    }
}
