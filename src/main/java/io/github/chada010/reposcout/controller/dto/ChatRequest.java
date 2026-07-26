package io.github.chada010.reposcout.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/chat 请求体。sessionId 可空(空则服务端生成);
 * message 长度上限可配置,在 Controller 中按配置校验。
 */
public record ChatRequest(
        String sessionId,
        @NotBlank(message = "不能为空") String message
) {
}
