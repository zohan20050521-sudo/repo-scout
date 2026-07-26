package io.github.chada010.reposcout.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/repos 请求体。repo 支持 owner/repo 或
 * https://github.com/owner/repo,详细格式约束在解析器中校验。
 */
public record RepoOnboardRequest(
        @NotBlank(message = "不能为空") String repo
) {
}
