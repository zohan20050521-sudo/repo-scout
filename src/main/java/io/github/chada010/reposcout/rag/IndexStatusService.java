package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.repository.DocChunkRepository;
import io.github.chada010.reposcout.repository.RepoRepository;

/** 查询已接入仓库的文档索引聚合状态。 */
@Service
public class IndexStatusService {

    private final RepoRepository repoRepository;
    private final DocChunkRepository docChunkRepository;

    public IndexStatusService(RepoRepository repoRepository, DocChunkRepository docChunkRepository) {
        this.repoRepository = repoRepository;
        this.docChunkRepository = docChunkRepository;
    }

    public IndexStatus getStatus(long repoId) {
        if (repoRepository.findById(repoId).isEmpty()) {
            throw new RepoNotFoundException("仓库未接入或不存在:id=" + repoId);
        }
        DocChunkRepository.IndexStatistics statistics = docChunkRepository.aggregateIndexStatistics(repoId);
        long chunkCount = statistics.getChunkCount();
        return new IndexStatus(repoId, chunkCount > 0, statistics.getFileCount(),
                chunkCount, statistics.getIndexedAt());
    }

    public record IndexStatus(long repoId, boolean indexed, long fileCount,
                              long chunkCount, LocalDateTime indexedAt) {
    }
}
