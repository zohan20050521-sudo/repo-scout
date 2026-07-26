package io.github.chada010.reposcout.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.chada010.reposcout.entity.DocChunk;
import io.github.chada010.reposcout.repository.DocChunkRepository;

/**
 * 基于 doc_chunk 表的向量存储实现。检索为进程内暴力扫描:加载单仓库全部块
 * (百级规模),逐块算余弦相似度,降序取 topK。余弦用标准点积/模长,
 * 不假设向量已归一化;零模长(空向量)得 0 分。
 */
@Component
public class JpaRepoVectorStore implements RepoVectorStore {

    private final DocChunkRepository docChunkRepository;
    private final EmbeddingCodec embeddingCodec;

    public JpaRepoVectorStore(DocChunkRepository docChunkRepository, EmbeddingCodec embeddingCodec) {
        this.docChunkRepository = docChunkRepository;
        this.embeddingCodec = embeddingCodec;
    }

    @Override
    @Transactional
    public void replaceRepoChunks(long repoId, List<DocChunk> chunks) {
        // 先删后插保证幂等:重复索引同一仓库,总数不因重复调用增长(唯一键兜底)。
        // deleteByRepoId 与 saveAll 在同一事务,避免删后插失败留下空索引。
        docChunkRepository.deleteByRepoId(repoId);
        docChunkRepository.saveAll(chunks);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScoredChunk> search(long repoId, float[] queryVector, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        List<DocChunk> chunks = docChunkRepository.findByRepoId(repoId);
        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (DocChunk chunk : chunks) {
            float[] vector = embeddingCodec.fromJson(chunk.getEmbedding());
            scored.add(new ScoredChunk(chunk, cosine(queryVector, vector)));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    /** 标准余弦相似度:点积除以两向量模长之积;任一模长为 0 返回 0。 */
    private static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
