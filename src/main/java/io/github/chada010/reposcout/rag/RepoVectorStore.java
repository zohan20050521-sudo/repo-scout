package io.github.chada010.reposcout.rag;

import java.util.List;

import io.github.chada010.reposcout.entity.DocChunk;

/**
 * 仓库向量存储抽象(自实现,不套 langchain4j EmbeddingStore,更可控)。
 * 文档块百级规模,检索在进程内算余弦相似度即可;抽象为接口便于日后换专用向量库。
 */
public interface RepoVectorStore {

    /**
     * 用新块整体替换某仓库的向量索引:先删该 repo 旧块再批量插(事务内),
     * 天然幂等——重复索引同一仓库不产生重复数据。
     */
    void replaceRepoChunks(long repoId, List<DocChunk> chunks);

    /**
     * 按余弦相似度检索该仓库最相关的 topK 个块,降序返回。
     * 本任务提供但不接入对话(供下一张任务书消费),正确性由集成测试保证。
     */
    List<ScoredChunk> search(long repoId, float[] queryVector, int topK);
}
