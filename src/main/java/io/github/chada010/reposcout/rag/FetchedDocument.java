package io.github.chada010.reposcout.rag;

/**
 * 从 GitHub 拉取到的一篇文档:相对仓库根的路径 + 原文内容(切分前)。
 */
public record FetchedDocument(String filePath, String content) {
}
