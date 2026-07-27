package io.github.chada010.reposcout.controller.dto;

import java.time.LocalDateTime;

import io.github.chada010.reposcout.service.ReportService;

/**
 * 生成仓库导读报告的响应(FR-3.3):直接返回资源 JSON,无全局包装结构。
 * POST /api/repos/{id}/report 使用;report 为 Markdown 全文(五个固定小节)。
 */
public record ReportResponse(
        long repoId,
        LocalDateTime generatedAt,
        long costMs,
        String report
) {

    public static ReportResponse of(long repoId, ReportService.ReportResult result) {
        return new ReportResponse(repoId, result.generatedAt(), result.costMs(), result.report());
    }
}
