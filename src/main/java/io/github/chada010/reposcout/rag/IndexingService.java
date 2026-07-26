package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.github.chada010.reposcout.entity.DocChunk;
import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.RepoRef;
import io.github.chada010.reposcout.repository.RepoRepository;

/**
 * 向量化入库编排(FR-3.1):拉取 → 切分 → 批量向量化 → 幂等重建入库。
 * 同步执行(受拉取上限约束,耗时可接受);向量化耗时、每文件块数记 INFO。
 *
 * <p>EmbeddingModel 注入点标 {@code @Lazy}:真实 bge 模型只在首次索引时加载,
 * 不拖慢无关的全上下文测试(见 {@code RagConfig})。
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final RepoRepository repoRepository;
    private final DocumentFetcher fetcher;
    private final DocumentChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final RepoVectorStore vectorStore;
    private final EmbeddingCodec embeddingCodec;

    public IndexingService(RepoRepository repoRepository, DocumentFetcher fetcher,
                           DocumentChunker chunker, @Lazy EmbeddingModel embeddingModel,
                           RepoVectorStore vectorStore, EmbeddingCodec embeddingCodec) {
        this.repoRepository = repoRepository;
        this.fetcher = fetcher;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.embeddingCodec = embeddingCodec;
    }

    /**
     * 索引指定仓库。仓库须已接入,否则抛 {@link RepoNotFoundException}(端点映射 404)。
     * GitHub 目录列表拉取失败由 {@link DocumentFetcher} 抛出(端点映射 502)。
     */
    public IndexResult index(long repoId) {
        long start = System.currentTimeMillis();
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new RepoNotFoundException("仓库未接入或不存在:id=" + repoId));
        RepoRef ref = new RepoRef(repo.getOwner(), repo.getName(), repo.getDefaultBranch());

        List<FetchedDocument> docs = fetcher.fetch(ref);
        List<Chunk> chunks = new ArrayList<>();
        for (FetchedDocument doc : docs) {
            List<Chunk> docChunks = chunker.chunk(doc);
            log.info("切分完成: repo={}/{}, file={}, chunks={}",
                    ref.owner(), ref.name(), doc.filePath(), docChunks.size());
            chunks.addAll(docChunks);
        }

        List<DocChunk> stored = embedAndBuild(repoId, chunks, ref);
        vectorStore.replaceRepoChunks(repoId, stored);

        long cost = System.currentTimeMillis() - start;
        log.info("索引完成: repo={}/{}, files={}, chunks={}, costMs={}",
                ref.owner(), ref.name(), docs.size(), stored.size(), cost);
        return new IndexResult(docs.size(), stored.size(), cost);
    }

    /** 批量向量化并组装 DocChunk;无块时直接返回空(不空跑模型),仍走重建以清空旧索引。 */
    private List<DocChunk> embedAndBuild(long repoId, List<Chunk> chunks, RepoRef ref) {
        if (chunks.isEmpty()) {
            log.info("无可索引内容: repo={}/{}", ref.owner(), ref.name());
            return List.of();
        }
        List<TextSegment> segments = chunks.stream().map(c -> TextSegment.from(c.text())).toList();

        long embedStart = System.currentTimeMillis();
        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = response.content();
        log.info("向量化完成: repo={}/{}, segments={}, dim={}, costMs={}",
                ref.owner(), ref.name(), segments.size(),
                embeddings.isEmpty() ? 0 : embeddings.get(0).dimension(),
                System.currentTimeMillis() - embedStart);

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        List<DocChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String embeddingJson = embeddingCodec.toJson(embeddings.get(i).vector());
            result.add(new DocChunk(repoId, chunk.filePath(), chunk.chunkIndex(),
                    chunk.text(), embeddingJson, now));
        }
        return result;
    }
}
