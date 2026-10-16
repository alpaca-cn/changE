package com.change.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 把实际监听端口以固定格式打到标准输出，供 Tauri 外壳读取。
 *
 * 为什么要走标准输出而不是写文件：外壳是后台进程的父进程，
 * 直接读子进程的 stdout 最简单，也不需要约定文件路径与清理时机。
 *
 * 输出格式固定为一行，外壳按前缀匹配：
 *   CHANGE_PORT=12345
 */
@Component
public class PortReporter {

    private static final Logger log = LoggerFactory.getLogger(PortReporter.class);
    public static final String PREFIX = "CHANGE_PORT=";

    private final Environment env;

    public PortReporter(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        String port = env.getProperty("local.server.port");
        if (port == null) {
            log.warn("拿不到 local.server.port，外壳将无法定位后端");
            return;
        }
        // 这一行必须原样存在，外壳靠它定位；改动等于改协议
        System.out.println(PREFIX + port);
        System.out.flush();
    }
}
