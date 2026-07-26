package io.github.chada010.reposcout.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek 接入配置,默认值与环境变量映射见 application.yml 与 README。
 */
@ConfigurationProperties(prefix = "app.deepseek")
public record DeepSeekProperties(
        String apiKey,
        String baseUrl,
        String model,
        Duration timeout
) {
}
