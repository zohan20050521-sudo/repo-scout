package io.github.chada010.reposcout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量化入库配置(FR-3.1,成本与规模控制):文档拉取与切分的上限、切分粒度。
 * 参照 {@link ToolsProperties} 写法,记录类只承载配置,默认值在 application.yml。
 * 文档拉取范围(README + docs/ + 扩展名白名单)硬编码,不进配置。
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        int maxFiles,
        int maxFileBytes,
        int chunkSize,
        int chunkOverlap
) {
}
