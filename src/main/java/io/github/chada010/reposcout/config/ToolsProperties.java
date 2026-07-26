package io.github.chada010.reposcout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub 工具集裁剪配置(FR-2.2,成本控制约束):工具返回内容注入提示词前
 * 按这些上限截断。本期只固化默认值,工具实现期只读不改。
 */
@ConfigurationProperties(prefix = "app.tools")
public record ToolsProperties(
        int treeMaxDepth,
        int treeMaxEntries,
        int readmeMaxChars,
        int issuesMax,
        int commitsMax
) {
}
