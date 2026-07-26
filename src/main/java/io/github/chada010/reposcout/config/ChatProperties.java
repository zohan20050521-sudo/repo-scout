package io.github.chada010.reposcout.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话与会话记忆配置,默认值与环境变量映射见 application.yml 与 README。
 */
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(
        int messageMaxLength,
        Memory memory
) {

    public record Memory(
            int maxMessages,
            Duration ttl
    ) {
    }
}
