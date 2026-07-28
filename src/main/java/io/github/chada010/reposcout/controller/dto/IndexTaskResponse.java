package io.github.chada010.reposcout.controller.dto;

import java.time.LocalDateTime;

import io.github.chada010.reposcout.rag.IndexJobState;
import io.github.chada010.reposcout.rag.IndexJobStatus;

/** GET index-status 中的可选索引任务对象。 */
public record IndexTaskResponse(
        String jobId,
        long repoId,
        IndexJobStatus status,
        String errorCode,
        String errorMessage,
        Integer fileCount,
        Integer chunkCount,
        Long costMs,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    public static IndexTaskResponse from(IndexJobState state) {
        return new IndexTaskResponse(state.jobId(), state.repoId(), state.status(),
                state.errorCode(), state.errorMessage(), state.fileCount(), state.chunkCount(),
                state.costMs(), state.startedAt(), state.finishedAt());
    }
}
