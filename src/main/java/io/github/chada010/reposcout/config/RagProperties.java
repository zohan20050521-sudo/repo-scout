package io.github.chada010.reposcout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量化入库与检索配置(FR-3.1/FR-3.2,成本与规模控制):文档拉取与切分的上限、
 * 切分粒度、检索条数与相似度阈值。参照 {@link ToolsProperties} 写法,记录类只承载配置,
 * 默认值在 application.yml。文档拉取范围(README + docs/ + 扩展名白名单)硬编码,不进配置。
 * topK 为 chat 注入与 report 每个固定查询共用的检索条数;minScore 为余弦相似度过滤阈值。
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        int maxFiles,
        int maxFileBytes,
        int chunkSize,
        int chunkOverlap,
        int topK,
        double minScore
) {
}
