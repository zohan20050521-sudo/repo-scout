package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.repository.DocChunkRepository;
import io.github.chada010.reposcout.repository.RepoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class IndexStatusServiceTest {

    @Mock
    private RepoRepository repoRepository;
    @Mock
    private DocChunkRepository docChunkRepository;
    @Mock
    private IndexJobStore indexJobStore;

    private IndexStatusService service() {
        return new IndexStatusService(repoRepository, docChunkRepository);
    }

    @Test
    void indexedRepoReturnsAggregatedCountsAndLatestTime() {
        LocalDateTime indexedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
        DocChunkRepository.IndexStatistics statistics = statistics(63, 4, indexedAt);
        io.github.chada010.reposcout.entity.Repo repo = org.mockito.Mockito.mock(
                io.github.chada010.reposcout.entity.Repo.class);
        given(repoRepository.findById(1L)).willReturn(java.util.Optional.of(repo));
        given(docChunkRepository.aggregateIndexStatistics(1L)).willReturn(statistics);

        IndexStatusService.IndexStatus result = service().getStatus(1L);

        assertThat(result.indexed()).isTrue();
        assertThat(result.fileCount()).isEqualTo(4);
        assertThat(result.chunkCount()).isEqualTo(63);
        assertThat(result.indexedAt()).isEqualTo(indexedAt);
    }

    @Test
    void unindexedRepoReturnsZeroesAndNullTime() {
        DocChunkRepository.IndexStatistics statistics = statistics(0, 0, null);
        given(repoRepository.findById(1L)).willReturn(java.util.Optional.of(org.mockito.Mockito.mock(
                io.github.chada010.reposcout.entity.Repo.class)));
        given(docChunkRepository.aggregateIndexStatistics(1L)).willReturn(statistics);

        IndexStatusService.IndexStatus result = service().getStatus(1L);

        assertThat(result.indexed()).isFalse();
        assertThat(result.fileCount()).isZero();
        assertThat(result.chunkCount()).isZero();
        assertThat(result.indexedAt()).isNull();
    }

    @Test
    void missingRepoThrowsExistingMessage() {
        given(repoRepository.findById(9L)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service().getStatus(9L))
                .isInstanceOf(RepoNotFoundException.class)
                .hasMessage("仓库未接入或不存在:id=9");
    }

    @Test
    void indexedStatusIncludesLatestRedisTask() {
        LocalDateTime queuedAt = LocalDateTime.of(2026, 7, 28, 12, 0);
        IndexJobState task = new IndexJobState("job-1", 1L, IndexJobStatus.RUNNING,
                queuedAt, queuedAt, null, null, null, null, null, null);
        io.github.chada010.reposcout.entity.Repo repo = org.mockito.Mockito.mock(
                io.github.chada010.reposcout.entity.Repo.class);
        given(repoRepository.findById(1L)).willReturn(java.util.Optional.of(repo));
        DocChunkRepository.IndexStatistics emptyStatistics = statistics(0, 0, null);
        given(docChunkRepository.aggregateIndexStatistics(1L)).willReturn(emptyStatistics);
        given(indexJobStore.find(1L)).willReturn(java.util.Optional.of(task));

        IndexStatusService.IndexStatus result = new IndexStatusService(
                repoRepository, docChunkRepository, indexJobStore).getStatus(1L);

        assertThat(result.task()).isEqualTo(task);
    }

    private DocChunkRepository.IndexStatistics statistics(long chunks, long files,
                                                           LocalDateTime indexedAt) {
        DocChunkRepository.IndexStatistics statistics = org.mockito.Mockito.mock(
                DocChunkRepository.IndexStatistics.class);
        given(statistics.getChunkCount()).willReturn(chunks);
        given(statistics.getFileCount()).willReturn(files);
        given(statistics.getIndexedAt()).willReturn(indexedAt);
        return statistics;
    }
}
