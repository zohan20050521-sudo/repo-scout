package io.github.chada010.reposcout.controller.dto;

/** POST /api/chat 的结构化 RAG 引用详情。 */
public record CitationResponse(
        String filePath,
        int chunkIndex,
        String excerpt,
        double score,
        String url
) {
}
