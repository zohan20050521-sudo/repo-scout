package io.github.chada010.reposcout.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import io.github.chada010.reposcout.memory.RedisChatMemoryStore;
import io.github.chada010.reposcout.service.Assistant;

/**
 * LLM 相关 Bean 的手工装配(不使用 langchain4j 的 spring-boot-starter,
 * 便于教学展示与精细控制)。DeepSeek 走 OpenAI 兼容协议接入。
 */
@Configuration
@EnableConfigurationProperties({DeepSeekProperties.class, ChatProperties.class})
public class LlmConfig {

    @Bean
    public OpenAiChatModel chatModel(DeepSeekProperties properties) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("""
                    缺少 DeepSeek API Key,应用无法启动。
                    请设置环境变量 DEEPSEEK_API_KEY 后重试,例如:
                      export DEEPSEEK_API_KEY=<你的 DeepSeek API Key>
                    可选配置:DEEPSEEK_BASE_URL、DEEPSEEK_MODEL、DEEPSEEK_TIMEOUT,详见 README「环境变量」一节。""");
        }
        return OpenAiChatModel.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .modelName(properties.model())
                .timeout(properties.timeout())
                .build();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore store, ChatProperties chatProperties) {
        return sessionId -> MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(chatProperties.memory().maxMessages())
                .chatMemoryStore(store)
                .build();
    }

    @Bean
    public Assistant assistant(OpenAiChatModel chatModel, ChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
