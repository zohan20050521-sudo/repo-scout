package io.github.chada010.reposcout.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub API 访问配置。token 默认空(匿名调用,限流阈值较低),
 * 由环境变量 GITHUB_TOKEN 注入后带 Authorization: Bearer 头;
 * timeout 同时作用于连接与读超时。
 */
@ConfigurationProperties(prefix = "app.github")
public record GithubProperties(
        String baseUrl,
        String token,
        Duration timeout
) {
}
