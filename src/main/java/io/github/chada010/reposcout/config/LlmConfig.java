package io.github.chada010.reposcout.config;

import java.util.function.Function;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import io.github.chada010.reposcout.memory.RedisChatMemoryStore;
import io.github.chada010.reposcout.memory.SessionRepoBinding;
import io.github.chada010.reposcout.service.Assistant;

/**
 * LLM 相关 Bean 的手工装配(不使用 langchain4j 的 spring-boot-starter,
 * 便于教学展示与精细控制)。DeepSeek 走 OpenAI 兼容协议接入。
 * v0.2 起 Assistant 为单例 AiServices:工具集由 {@code ToolProvider} 按会话绑定动态挂载,
 * 系统提示词由 {@code systemMessageProvider} 按绑定状态切换。
 */
@Configuration
@EnableConfigurationProperties({DeepSeekProperties.class, ChatProperties.class, AgentProperties.class})
public class LlmConfig {

    /**
     * 框架轮数上限作为防跑飞硬兜底,取值高于 {@link AgentProperties#maxToolRounds()}:
     * 正常轮数上限由 {@code TrackingToolExecutor} 优雅拦截(返回可读文本让模型收尾),
     * 框架参数只在模型无视上限持续调用的极端情况下终止循环(langchain4j 超限会抛异常)。
     */
    private static final int FRAMEWORK_BACKSTOP_MARGIN = 10;

    @Bean
    public ChatModel chatModel(DeepSeekProperties properties) {
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
    public Assistant assistant(ChatModel chatModel, ChatMemoryProvider chatMemoryProvider,
                               ToolProvider repoToolProvider, SessionRepoBinding sessionRepoBinding,
                               AgentProperties agentProperties) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .toolProvider(repoToolProvider)
                .systemMessageProvider(systemMessageProvider(sessionRepoBinding))
                .maxSequentialToolsInvocations(agentProperties.maxToolRounds() + FRAMEWORK_BACKSTOP_MARGIN)
                .build();
    }

    /** 系统提示词按会话绑定状态切换:已绑定用可调工具的提示,未绑定沿用纯对话提示。 */
    private Function<Object, String> systemMessageProvider(SessionRepoBinding sessionRepoBinding) {
        return memoryId -> sessionRepoBinding.get(String.valueOf(memoryId)).isPresent()
                ? Assistant.BOUND_SYSTEM_PROMPT
                : Assistant.UNBOUND_SYSTEM_PROMPT;
    }
}
