package io.github.chada010.reposcout.controller.dto;

import java.time.LocalDateTime;

import io.github.chada010.reposcout.entity.Repo;

/**
 * 仓库记录响应:直接返回资源 JSON,无全局包装结构。
 * POST /api/repos 与 GET /api/repos(/{id}) 共用。
 */
public record RepoResponse(
        Long id,
        String owner,
        String name,
        String defaultBranch,
        String description,
        String htmlUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static RepoResponse from(Repo repo) {
        return new RepoResponse(
                repo.getId(),
                repo.getOwner(),
                repo.getName(),
                repo.getDefaultBranch(),
                repo.getDescription(),
                repo.getHtmlUrl(),
                repo.getCreatedAt(),
                repo.getUpdatedAt());
    }
}
