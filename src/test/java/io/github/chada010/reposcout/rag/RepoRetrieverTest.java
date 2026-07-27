package io.github.chada010.reposcout.rag;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.config.RagProperties;
import io.github.chada010.reposcout.entity.DocChunk;
import io.github.chada010.reposcout.repository.DocChunkRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * RepoRetriever 单测:mock 三依赖,验证检索顺序契约——未索引不触碰 EmbeddingModel、
 * 查询前缀逐字节精确、min-score 阈值边界(等于保留、低于剔除)、topK 透传。
 */
@ExtendWith(MockitoExtension.class)
class RepoRetrieverTest {

    private static final long REPO_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 12, 0, 0);

    @Mock
    private DocChunkRepository docChunkRepository;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private RepoVectorStore vectorStore;

    private RepoRetriever retriever(int topK, double minScore) {
        return new RepoRetriever(docChunkRepository, embeddingModel, vectorStore,
                new RagProperties(30, 100000, 400, 80, topK, minScore));
    }

    private static ScoredChunk hit(String filePath, double score) {
        return new ScoredChunk(new DocChunk(REPO_ID, filePath, 0, "content", "[1.0]", NOW), score);
    }

    private void stubEmbedding() {
        given(embeddingModel.embed(anyString()))
                .willReturn(Response.from(Embedding.from(new float[]{1f, 0f})));
    }

    @Test
    void unindexedRepoReturnsEmptyWithoutTouchingEmbeddingModel() {
        given(docChunkRepository.existsByRepoId(REPO_ID)).willReturn(false);

        List<ScoredChunk> result = retriever(4, 0.5).retrieve(REPO_ID, "任意问题");

        assertThat(result).isEmpty();
        verifyNoInteractions(embeddingModel, vectorStore);
    }

    @Test
    void embedInputIsQueryPrefixPlusQueryByteExact() {
        given(docChunkRepository.existsByRepoId(REPO_ID)).willReturn(true);
        stubEmbedding();
        given(vectorStore.search(anyLong(), any(float[].class), anyInt())).willReturn(List.of());

        retriever(4, 0.5).retrieve(REPO_ID, "这个项目怎么跑?");

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingModel).embed(textCaptor.capture());
        String expected = RepoRetriever.QUERY_PREFIX + "这个项目怎么跑?";
        assertThat(textCaptor.getValue()).isEqualTo(expected);
        assertThat(textCaptor.getValue().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void minScoreKeepsEqualAndDropsBelowThreshold() {
        given(docChunkRepository.existsByRepoId(REPO_ID)).willReturn(true);
        stubEmbedding();
        given(vectorStore.search(anyLong(), any(float[].class), anyInt())).willReturn(List.of(
                hit("a.md", 0.9),
                hit("b.md", 0.5),
                hit("c.md", 0.49999)));

        List<ScoredChunk> result = retriever(4, 0.5).retrieve(REPO_ID, "问题");

        // 等于阈值保留、低于剔除;顺序保持检索得分降序
        assertThat(result).extracting(h -> h.chunk().getFilePath()).containsExactly("a.md", "b.md");
    }

    @Test
    void topKFromPropertiesIsPassedToVectorStore() {
        given(docChunkRepository.existsByRepoId(REPO_ID)).willReturn(true);
        stubEmbedding();
        given(vectorStore.search(anyLong(), any(float[].class), anyInt())).willReturn(List.of());

        retriever(7, 0.5).retrieve(REPO_ID, "问题");

        verify(vectorStore).search(eq(REPO_ID), eq(new float[]{1f, 0f}), eq(7));
    }
}
