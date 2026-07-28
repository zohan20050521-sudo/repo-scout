package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;

/** Redis 中保存的索引任务快照，也是状态接口的内部数据源。 */
public record IndexJobState(
        String jobId,
        long repoId,
        IndexJobStatus status,
        LocalDateTime queuedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Integer fileCount,
        Integer chunkCount,
        Long costMs,
        String errorCode,
        String errorMessage
) {

    public boolean active() {
        return status == IndexJobStatus.QUEUED || status == IndexJobStatus.RUNNING;
    }

    public IndexJobState running(LocalDateTime started) {
        return new IndexJobState(jobId, repoId, IndexJobStatus.RUNNING, queuedAt, started,
                null, null, null, null, null, null);
    }

    public IndexJobState succeeded(IndexResult result, LocalDateTime finished) {
        return new IndexJobState(jobId, repoId, IndexJobStatus.SUCCEEDED, queuedAt, startedAt,
                finished, result.fileCount(), result.chunkCount(), result.costMs(), null, null);
    }

    public IndexJobState failed(String code, String message, LocalDateTime finished) {
        return new IndexJobState(jobId, repoId, IndexJobStatus.FAILED, queuedAt, startedAt,
                finished, null, null, null, code, message);
    }
}
