package io.github.chada010.reposcout.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhq.BgeSmallZhQuantizedEmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import io.github.chada010.reposcout.rag.ChatContentInjector;
import io.github.chada010.reposcout.rag.ChatContentRetriever;

/**
 * RAG 相关 Bean 装配(FR-3.1/FR-3.2)。Embedding 走进程内量化 bge-small-zh(ONNX,
 * 自带权重、无外网),向量维度以模型实际输出为准(512),不硬编码进表结构。
 * bean 类型用 {@link EmbeddingModel} 抽象,便于测试用 mock 替换。
 *
 * <p>标注 {@code @Lazy}:24MB ONNX 模型只在首次真实索引/检索时加载,避免全上下文
 * 测试({@code @SpringBootTest})在 CI 无意义地加载模型、拖慢构建。消费方
 * {@code IndexingService}/{@code RepoRetriever} 的注入点同样 {@code @Lazy},注入延迟初始化代理。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    @Bean
    @Lazy
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhQuantizedEmbeddingModel();
    }

    /**
     * 对话链路的检索注入组装(FR-3.2):自定义按会话绑定检索的 retriever 与
     * 空命中零改写的 injector,挂到单例 AiServices(见 {@code LlmConfig#assistant})。
     * 与 v0.2「ToolProvider 动态挂载」同一套框架机制,注入按会话动态生效。
     */
    @Bean
    public RetrievalAugmentor retrievalAugmentor(ChatContentRetriever chatContentRetriever,
                                                 ChatContentInjector chatContentInjector) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(chatContentRetriever)
                .contentInjector(chatContentInjector)
                .build();
    }
}
