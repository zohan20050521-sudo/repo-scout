package io.github.chada010.reposcout.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhq.BgeSmallZhQuantizedEmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * RAG 相关 Bean 装配(FR-3.1)。Embedding 走进程内量化 bge-small-zh(ONNX,
 * 自带权重、无外网),向量维度以模型实际输出为准(512),不硬编码进表结构。
 * bean 类型用 {@link EmbeddingModel} 抽象,便于测试用 mock 替换。
 *
 * <p>标注 {@code @Lazy}:24MB ONNX 模型只在首次真实索引时加载,避免全上下文
 * 测试({@code @SpringBootTest})在 CI 无意义地加载模型、拖慢构建。消费方
 * {@code IndexingService} 的注入点同样 {@code @Lazy},注入延迟初始化代理。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    @Bean
    @Lazy
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhQuantizedEmbeddingModel();
    }
}
