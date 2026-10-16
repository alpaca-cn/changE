package com.change.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final CacheStatus cacheStatus;

    public HealthController(CacheStatus cacheStatus) {
        this.cacheStatus = cacheStatus;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "up",
                "cache", cacheStatus.describe());
    }

    /** 由 Task 11 提供实现；此处先声明接口，让健康端点不必依赖 Redis。 */
    public interface CacheStatus {
        String describe();
    }
}
