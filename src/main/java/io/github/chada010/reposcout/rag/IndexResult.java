package io.github.chada010.reposcout.rag;

/**
 * 一次索引的结果:拉取文件数、生成块数、总耗时(毫秒)。
 */
public record IndexResult(int fileCount, int chunkCount, long costMs) {
}
