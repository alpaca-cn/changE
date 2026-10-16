package com.change.config;

import com.change.web.HealthController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    /** 占位实现：Task 11 会换成 Redis / NoOp 二选一。 */
    @Bean
    public HealthController.CacheStatus cacheStatus() {
        return () -> "disabled(placeholder)";
    }
}
