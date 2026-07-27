package io.github.chada010.reposcout.controller.dto;

import java.util.List;

/**
 * POST /api/chat 成功响应:直接返回资源 JSON,无全局包装结构。
 * sources 为本轮实际注入的检索来源文件路径(FR-3.2),去重、按检索得分降序;
 * 未绑定/未索引/无命中为空数组,永不为 null。
 */
public record ChatResponse(
        String sessionId,
        String answer,
        List<String> sources,
        List<CitationResponse> citations
) {
}
