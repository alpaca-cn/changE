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
        ResponseEntity<String> r = http.getForEntity("/api/health", String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getBody()).contains("\"status\":\"up\"");
        assertThat(r.getBody()).contains("\"cache\"");
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
        // 前缀后面必须是纯数字端口——外壳要 parseInt
        assertThat(line.substring(PortReporter.PREFIX.length())).matches("\\d+");
    }
}
