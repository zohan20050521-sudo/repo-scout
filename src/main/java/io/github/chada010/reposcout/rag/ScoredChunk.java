package io.github.chada010.reposcout.rag;

import io.github.chada010.reposcout.entity.DocChunk;

/**
 * 检索命中:文档块 + 与查询向量的余弦相似度(降序排序用)。
 */
public record ScoredChunk(DocChunk chunk, double score) {
}
