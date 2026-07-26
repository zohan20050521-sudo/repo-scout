package io.github.chada010.reposcout.controller.dto;

/**
 * POST /api/chat 成功响应:直接返回资源 JSON,无全局包装结构。
 */
public record ChatResponse(
        String sessionId,
        String answer
) {
}
