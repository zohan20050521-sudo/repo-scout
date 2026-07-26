package io.github.chada010.reposcout.controller.dto;

/**
 * 统一错误响应结构。错误码见 docs/api.md:
 * INVALID_PARAM(400)、LLM_UNAVAILABLE(502)、INTERNAL_ERROR(500)。
 */
public record ErrorResponse(
        String code,
        String message
) {
}
