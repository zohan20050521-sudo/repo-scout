package io.github.chada010.reposcout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 公网内部 API 共享密钥配置;空白时门禁关闭。 */
@ConfigurationProperties("app.security")
public record SecurityProperties(String internalApiKey) {
}
