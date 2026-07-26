package io.github.chada010.reposcout.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * GitHub 访问相关装配。超时通过 RestClientCustomizer 统一配置:
 * 当前应用内 RestClient.Builder 仅被 GithubApiClient 消费,该 customizer
 * 事实上只作用于 GitHub 访问(LangChain4j 走自己的 HTTP 栈,不受影响)。
 */
@Configuration
@EnableConfigurationProperties({GithubProperties.class, ToolsProperties.class})
public class GithubConfig {

    @Bean
    public RestClientCustomizer githubTimeoutCustomizer(GithubProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.timeout())
                .withReadTimeout(properties.timeout());
        return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }
}
