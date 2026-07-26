package io.github.chada010.reposcout.controller.dto;

import io.github.chada010.reposcout.rag.IndexResult;

/**
 * 触发向量化索引的响应(FR-3.1):直接返回资源 JSON,无全局包装结构。
 * POST /api/repos/{id}/index 使用。
 */
public record IndexResponse(
        long repoId,
        int fileCount,
        int chunkCount,
        long costMs
) {

    public static IndexResponse of(long repoId, IndexResult result) {
        return new IndexResponse(repoId, result.fileCount(), result.chunkCount(), result.costMs());
    }
}
