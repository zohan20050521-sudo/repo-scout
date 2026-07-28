package io.github.chada010.reposcout.controller.dto;

import java.time.LocalDateTime;

/** GET /api/repos/{id}/index-status 成功响应。 */
public record IndexStatusResponse(
        long repoId,
        boolean indexed,
        long fileCount,
        long chunkCount,
        LocalDateTime indexedAt,
        IndexTaskResponse task
) {

    public IndexStatusResponse(long repoId, boolean indexed, long fileCount,
                               long chunkCount, LocalDateTime indexedAt) {
        this(repoId, indexed, fileCount, chunkCount, indexedAt, null);
    }
}
