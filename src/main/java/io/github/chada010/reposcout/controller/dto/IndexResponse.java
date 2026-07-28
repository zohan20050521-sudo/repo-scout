package io.github.chada010.reposcout.controller.dto;

import io.github.chada010.reposcout.rag.IndexJobState;
import io.github.chada010.reposcout.rag.IndexJobStatus;

/**
 * 触发向量化索引的响应(FR-3.1):直接返回资源 JSON,无全局包装结构。
 * POST /api/repos/{id}/index 使用。
 */
public record IndexResponse(
        long repoId,
        String jobId,
        IndexJobStatus status
) {

    public static IndexResponse of(IndexJobState state) {
        return new IndexResponse(state.repoId(), state.jobId(), state.status());
    }
}
