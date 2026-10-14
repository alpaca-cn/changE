package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ChangeApplicationTests {

    @Test
    void contextLoads() {
        // 能加载即通过。Redis 不可达时也必须通过——这是降级的验收点。
    }
}
