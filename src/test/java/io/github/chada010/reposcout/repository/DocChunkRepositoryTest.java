package io.github.chada010.reposcout.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import io.github.chada010.reposcout.entity.DocChunk;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DocChunkRepositoryTest {

    @Autowired
    private DocChunkRepository repository;

    @Test
    void aggregateCountsDistinctFilesAndUsesLatestCreatedAt() {
        LocalDateTime early = LocalDateTime.of(2026, 7, 27, 11, 0);
        LocalDateTime latest = LocalDateTime.of(2026, 7, 27, 12, 0);
        repository.saveAllAndFlush(List.of(
                chunk(1L, "docs/api.md", 0, early),
                chunk(1L, "docs/api.md", 1, latest),
                chunk(1L, "README.md", 0, early),
                chunk(2L, "other.md", 0, latest)));

        DocChunkRepository.IndexStatistics result = repository.aggregateIndexStatistics(1L);

        assertThat(result.getChunkCount()).isEqualTo(3);
        assertThat(result.getFileCount()).isEqualTo(2);
        assertThat(result.getIndexedAt()).isEqualTo(latest);
    }

    @Test
    void aggregateForMissingChunksReturnsZeroesAndNull() {
        DocChunkRepository.IndexStatistics result = repository.aggregateIndexStatistics(999L);

        assertThat(result.getChunkCount()).isZero();
        assertThat(result.getFileCount()).isZero();
        assertThat(result.getIndexedAt()).isNull();
    }

    private DocChunk chunk(long repoId, String path, int index, LocalDateTime createdAt) {
        return new DocChunk(repoId, path, index, "content", "[1.0]", createdAt);
    }
}
