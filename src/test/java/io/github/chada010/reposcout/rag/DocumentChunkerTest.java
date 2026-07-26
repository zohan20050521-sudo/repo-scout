package io.github.chada010.reposcout.rag;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.chada010.reposcout.config.RagProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentChunker 单测:验证块数、文件内递增 chunk_index、file_path 保留与 overlap 生效。
 * 不涉及 GitHub 与真实模型,纯字符切分。
 */
class DocumentChunkerTest {

    private static RagProperties props(int chunkSize, int chunkOverlap) {
        return new RagProperties(30, 100000, chunkSize, chunkOverlap, 4, 0.5);
    }

    @Test
    void shortDocProducesSingleChunkAtIndexZero() {
        DocumentChunker chunker = new DocumentChunker(props(400, 80));

        List<Chunk> chunks = chunker.chunk(new FetchedDocument("README.md", "只有一小段内容,远短于切分上限。"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).filePath()).isEqualTo("README.md");
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(0).text()).contains("只有一小段内容");
    }

    @Test
    void longDocSplitsIntoSequentiallyIndexedChunksKeepingFilePath() {
        // 100 个不同的 word,chunkSize 偏小必然切成多块
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("word").append(i).append(' ');
        }
        DocumentChunker chunker = new DocumentChunker(props(40, 10));

        List<Chunk> chunks = chunker.chunk(new FetchedDocument("docs/guide.md", sb.toString()));

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).chunkIndex()).isEqualTo(i);
            assertThat(chunks.get(i).filePath()).isEqualTo("docs/guide.md");
            assertThat(chunks.get(i).text()).isNotBlank();
        }
    }

    @Test
    void overlapCausesAdjacentChunksToShareContent() {
        // 句子结构文本:递归切分器在句子粒度施加 overlap(纯词流不触发)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("Sentence").append(i).append(" has some words here. ");
        }
        String text = sb.toString();

        List<Chunk> withOverlap = new DocumentChunker(props(200, 80)).chunk(new FetchedDocument("docs/a.md", text));
        List<Chunk> noOverlap = new DocumentChunker(props(200, 0)).chunk(new FetchedDocument("docs/a.md", text));

        // 相邻块共享一整句:overlap 把前块尾部若干句携带进后块开头
        boolean shared = false;
        for (int i = 0; i + 1 < withOverlap.size(); i++) {
            for (String sentence : withOverlap.get(i).text().split("(?<=\\.)\\s+")) {
                String s = sentence.trim();
                if (s.length() > 5 && withOverlap.get(i + 1).text().contains(s)) {
                    shared = true;
                    break;
                }
            }
            if (shared) {
                break;
            }
        }
        assertThat(shared).as("相邻块应因 overlap 共享整句").isTrue();

        // overlap 导致内容重复,总字符数应多于无 overlap;块数不少于无 overlap
        int overlapChars = withOverlap.stream().mapToInt(c -> c.text().length()).sum();
        int plainChars = noOverlap.stream().mapToInt(c -> c.text().length()).sum();
        assertThat(overlapChars).isGreaterThan(plainChars);
        assertThat(withOverlap.size()).isGreaterThanOrEqualTo(noOverlap.size());
    }
}
