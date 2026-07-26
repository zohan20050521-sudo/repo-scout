package io.github.chada010.reposcout.rag;

/**
 * 切分后、向量化前的文本块:保留来源 filePath 与文件内递增 chunkIndex,
 * 供入库时定位与幂等唯一键使用。向量在 IndexingService 批量算出后再组装成 DocChunk。
 */
public record Chunk(String filePath, int chunkIndex, String text) {
}
