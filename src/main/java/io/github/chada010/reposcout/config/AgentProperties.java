package io.github.chada010.reposcout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 编排配置(FR-2.3,成本控制约束)。默认值与环境变量映射见 application.yml 与 README。
 *
 * @param maxToolRounds 单次问答的工具调用轮数上限(默认 5,环境变量 AGENT_MAX_TOOL_ROUNDS)。
 *                      超限后由 {@code TrackingToolExecutor} 返回可读文本让模型收尾,防止循环调用。
 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(
        int maxToolRounds
) {
}
