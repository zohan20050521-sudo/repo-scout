package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.entity.DocChunk;
import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.RepoRef;
import io.github.chada010.reposcout.repository.RepoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * IndexingService 单测:mock 拉取/切分/EmbeddingModel/存储(CI 不跑真实模型),
 * 验证拉取→切分→批量向量化→幂等重建的编排、DocChunk 组装,以及未接入仓库抛 404。
 */
@ExtendWith(MockitoExtension.class)
class IndexingServiceTest {

    @Mock
    private RepoRepository repoRepository;
    @Mock
    private DocumentFetcher fetcher;
    @Mock
    private DocumentChunker chunker;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private RepoVectorStore vectorStore;

    private final EmbeddingCodec codec = new EmbeddingCodec(new ObjectMapper());

    private IndexingService service() {
        return new IndexingService(repoRepository, fetcher, chunker, embeddingModel, vectorStore, codec);
    }

    private static Repo repo() {
        return new Repo("o", "n", "main", "desc", "https://github.com/o/n",
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void indexFetchesChunksEmbedsAndRebuilds() {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        FetchedDocument doc = new FetchedDocument("README.md", "原文");
        given(fetcher.fetch(any(RepoRef.class))).willReturn(List.of(doc));
        given(chunker.chunk(doc)).willReturn(List.of(
                new Chunk("README.md", 0, "seg0"),
                new Chunk("README.md", 1, "seg1")));
        given(embeddingModel.embedAll(anyList())).willReturn(Response.from(List.of(
                Embedding.from(new float[]{1f, 0f}),
                Embedding.from(new float[]{0f, 1f}))));

        IndexResult result = service().index(1L);

        assertThat(result.fileCount()).isEqualTo(1);
        assertThat(result.chunkCount()).isEqualTo(2);

        // 校验向量化输入是切分文本
        ArgumentCaptor<List<TextSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embedAll(segCaptor.capture());
        assertThat(segCaptor.getValue()).extracting(TextSegment::text).containsExactly("seg0", "seg1");

        // 校验入库的 DocChunk 组装
        ArgumentCaptor<List<DocChunk>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).replaceRepoChunks(org.mockito.ArgumentMatchers.eq(1L), chunkCaptor.capture());
        List<DocChunk> stored = chunkCaptor.getValue();
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getRepoId()).isEqualTo(1L);
        assertThat(stored.get(0).getFilePath()).isEqualTo("README.md");
        assertThat(stored.get(0).getChunkIndex()).isZero();
        assertThat(stored.get(0).getContent()).isEqualTo("seg0");
        assertThat(codec.fromJson(stored.get(0).getEmbedding())).containsExactly(1f, 0f);
        assertThat(stored.get(1).getChunkIndex()).isEqualTo(1);
        assertThat(codec.fromJson(stored.get(1).getEmbedding())).containsExactly(0f, 1f);
    }

    @Test
    void missingRepoThrowsRepoNotFoundAndSkipsPipeline() {
        given(repoRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().index(999L))
                .isInstanceOf(RepoNotFoundException.class)
                .hasMessageContaining("999");

        verifyNoInteractions(fetcher, chunker, embeddingModel, vectorStore);
    }

    @Test
    void emptyDocsStillRebuildsWithoutInvokingModel() {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(fetcher.fetch(any(RepoRef.class))).willReturn(List.of());

        IndexResult result = service().index(1L);

        assertThat(result.fileCount()).isZero();
        assertThat(result.chunkCount()).isZero();
        // 无块不空跑模型,但仍重建以清空旧索引
        verify(embeddingModel, never()).embedAll(anyList());
        verify(vectorStore).replaceRepoChunks(1L, List.of());
    }
}
