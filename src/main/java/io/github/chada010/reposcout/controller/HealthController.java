package io.github.chada010.reposcout.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点。v0.1 只报告服务在线,不探测 MySQL/Redis 可用性,
 * 保证无外部依赖时也能启动并通过检查。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final String applicationName;

    public HealthController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "application", applicationName
        );
    }
}
