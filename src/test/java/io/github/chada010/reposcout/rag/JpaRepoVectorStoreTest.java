package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import io.github.chada010.reposcout.entity.DocChunk;
import io.github.chada010.reposcout.repository.DocChunkRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JpaRepoVectorStore 集成测试:H2(MODE=MySQL)+ Flyway(顺带验证 V2 迁移脚本可执行)。
 * 覆盖幂等重建(先删后插,重复不增长)与余弦检索排序(已知向量验证 topK 顺序)。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class JpaRepoVectorStoreTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 12, 0, 0);
    private static final long REPO = 1L;

    @Autowired
    private DocChunkRepository repository;

    @Autowired
    private TestEntityManager em;

    private JpaRepoVectorStore store;
    private EmbeddingCodec codec;

    @BeforeEach
    void setUp() {
        codec = new EmbeddingCodec(new ObjectMapper());
        store = new JpaRepoVectorStore(repository, codec);
    }

    private DocChunk chunk(long repoId, String path, int index, float... vector) {
        return new DocChunk(repoId, path, index, "content-" + path + "-" + index,
                codec.toJson(vector), NOW);
    }

    /** 模拟独立事务:落盘并清空持久化上下文,避免同事务缓存掩盖删/插行为。 */
    private void flushClear() {
        em.flush();
        em.clear();
    }

    @Test
    void replaceRepoChunksIsIdempotentAndDoesNotDuplicate() {
        store.replaceRepoChunks(REPO, List.of(
                chunk(REPO, "README.md", 0, 1f, 0f),
                chunk(REPO, "docs/a.md", 0, 0f, 1f)));
        flushClear();
        assertThat(repository.findByRepoId(REPO)).hasSize(2);

        // 重复重建:先删后插,总数不增长
        store.replaceRepoChunks(REPO, List.of(
                chunk(REPO, "README.md", 0, 1f, 0f),
                chunk(REPO, "docs/a.md", 0, 0f, 1f)));
        flushClear();
        assertThat(repository.findByRepoId(REPO)).hasSize(2);
    }

    @Test
    void replaceOnlyAffectsTargetRepo() {
        store.replaceRepoChunks(REPO, List.of(chunk(REPO, "README.md", 0, 1f, 0f)));
        store.replaceRepoChunks(2L, List.of(chunk(2L, "README.md", 0, 1f, 0f)));
        flushClear();

        // 重建 repo 1 不影响 repo 2
        store.replaceRepoChunks(REPO, List.of(chunk(REPO, "docs/a.md", 0, 0f, 1f)));
        flushClear();

        assertThat(repository.findByRepoId(REPO)).hasSize(1);
        assertThat(repository.findByRepoId(2L)).hasSize(1);
    }

    @Test
    void searchRanksByCosineSimilarityDescending() {
        store.replaceRepoChunks(REPO, List.of(
                chunk(REPO, "a.md", 0, 1f, 0f),   // 与查询 [1,0] 完全同向 → 1.0
                chunk(REPO, "b.md", 0, 0f, 1f),   // 正交 → 0
                chunk(REPO, "c.md", 0, 1f, 1f)));  // 45 度 → ~0.707
        flushClear();

        List<ScoredChunk> top2 = store.search(REPO, new float[]{1f, 0f}, 2);

        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).chunk().getFilePath()).isEqualTo("a.md");
        assertThat(top2.get(1).chunk().getFilePath()).isEqualTo("c.md");
        assertThat(top2.get(0).score()).isGreaterThan(top2.get(1).score());
        assertThat(top2.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void searchIsScopedToRepoAndClampsTopK() {
        store.replaceRepoChunks(REPO, List.of(chunk(REPO, "a.md", 0, 1f, 0f)));
        store.replaceRepoChunks(2L, List.of(chunk(2L, "other.md", 0, 1f, 0f)));
        flushClear();

        // topK 超过库中数量 → 返回全部(仅本 repo);topK<=0 → 空
        assertThat(store.search(REPO, new float[]{1f, 0f}, 10)).hasSize(1);
        assertThat(store.search(REPO, new float[]{1f, 0f}, 10).get(0).chunk().getFilePath())
                .isEqualTo("a.md");
        assertThat(store.search(REPO, new float[]{1f, 0f}, 0)).isEmpty();
    }
}
