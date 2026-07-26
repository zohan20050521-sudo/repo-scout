package io.github.chada010.reposcout.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/chat 请求体。sessionId 可空(空则服务端生成);
 * message 长度上限可配置,在 Controller 中按配置校验。
 * repoId 可选(FR-2.3):未绑定会话首次携带即绑定该已接入仓库,之后本会话挂载工具作答;
 * 已绑定会话再传不同 repoId 视为冲突(400)。非数字类型的 repoId 由 JSON 解析阶段拦为 400。
 */
public record ChatRequest(
        String sessionId,
        @NotBlank(message = "不能为空") String message,
        Long repoId
) {
}
