package com.change.web;

import com.change.config.PortReporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthControllerTest {

    @LocalServerPort int port;

    @Autowired TestRestTemplate http;

    @Autowired Environment env;

    @Test
    void random_port_was_assigned() {
        assertThat(port).isGreaterThan(0);
    }

    @Test
    void health_reports_ok_without_touching_redis() {
        // ⚠️ 这条测试的名字比断言强：它断言的是「健康端点在 Redis 不可达时仍能响应」。
        // 这本身就是隔离的证据——Spring 上下文能起来就说明缓存不是硬依赖
        // （CacheConfig 的条件装配 + NoOp 兜底）。它**不是**「不碰 Redis」的机制断言，
        // 没有拦在 Redis 客户端上的探针。
        ResponseEntity<String> r = http.getForEntity("/api/health", String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getBody()).contains("\"status\":\"up\"");
        // 查值，不是查键名：只查键名时 cache 的值改成 "" 或 "totally-bogus" 都能存活。
        // 质量审查轮实测过这两个变异体都能全绿——而 Task 11 的验收标准正是要求
        // 这个字段「如实报告」缓存状态，所以必须是值断言。
        assertThat(r.getBody()).contains("\"cache\":\"disabled(placeholder)\"");
    }

    @Test
    void port_reporter_prints_the_agreed_protocol_line() {
        // 这一行是外壳与后端的协议：外壳按 CHANGE_PORT= 前缀匹配 stdout。
        // 改动 PREFIX 或格式等于改协议，会让外壳找不到后端。
        assertThat(PortReporter.PREFIX).isEqualTo("CHANGE_PORT=");

        // 在真实上下文里跑一次 report()，捕获 stdout
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        try {
            System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            new PortReporter(env).report();
        } finally {
            System.setOut(original);
        }

        String line = captured.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
        assertThat(line).startsWith(PortReporter.PREFIX);
        // 这条是唯一能把「外壳拿到的是自己这个进程的端口」钉住的断言：
        // report() 读 local.server.port，必须与注入进来的 @LocalServerPort 逐字相等。
        // 只校验后缀是 \d+ 的话，打印出别的端口照样绿。
        assertThat(line).isEqualTo(PortReporter.PREFIX + port);
    }
}
