package io.github.chada010.reposcout.rag;

import java.util.List;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import io.github.chada010.reposcout.config.RagProperties;
import io.github.chada010.reposcout.repository.DocChunkRepository;

/**
 * 仓库文档检索核心(FR-3.2),供对话注入与导读报告共用:查询向量化 → 余弦检索 →
 * 按相似度阈值过滤。检索顺序固定:先查该仓库是否已建索引,未索引直接返回空且
 * <b>不触碰 EmbeddingModel</b>——这是 CI 不加载 ONNX 模型与未索引仓库优雅降级的关键。
 *
 * <p>EmbeddingModel 注入点标 {@code @Lazy}(与 {@link IndexingService} 同款):
 * 真实 bge 模型只在首次检索时加载。
 */
@Component
public class RepoRetriever {

    /**
     * bge 官方查询指令前缀:查询侧 embed 前拼接以对齐「查询→文档」的表示空间。
     * 仅查询侧加;文档入库侧(FR-3.1)未加前缀,保持不动。
     */
    public static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章:";

    private static final Logger log = LoggerFactory.getLogger(RepoRetriever.class);

    private final DocChunkRepository docChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final RepoVectorStore vectorStore;
    private final RagProperties ragProperties;

    public RepoRetriever(DocChunkRepository docChunkRepository, @Lazy EmbeddingModel embeddingModel,
                         RepoVectorStore vectorStore, RagProperties ragProperties) {
        this.docChunkRepository = docChunkRepository;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    /**
     * 检索该仓库与 query 最相关的文档块,按相似度降序,已按 {@code min-score} 过滤。
     * 未建索引返回空列表。查询全文不落日志(与 v0.2 日志纪律一致),只记长度。
     */
    public List<ScoredChunk> retrieve(long repoId, String query) {
        if (!docChunkRepository.existsByRepoId(repoId)) {
            return List.of();
        }
        long start = System.currentTimeMillis();
        float[] queryVector = embeddingModel.embed(QUERY_PREFIX + query).content().vector();
        List<ScoredChunk> hits = vectorStore.search(repoId, queryVector, ragProperties.topK());
        List<ScoredChunk> filtered = hits.stream()
                .filter(hit -> hit.score() >= ragProperties.minScore())
                .toList();
        // topScore 取过滤前最高分:命中被阈值全滤掉时仍可见实际分数,供调优 RAG_MIN_SCORE
        log.info("检索完成: repoId={}, queryLen={}, hits={}, topScore={}, costMs={}",
                repoId, query.length(), filtered.size(),
                hits.isEmpty() ? null : String.format("%.3f", hits.get(0).score()),
                System.currentTimeMillis() - start);
        return filtered;
    }
}
