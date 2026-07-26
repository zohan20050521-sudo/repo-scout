package io.github.chada010.reposcout.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.chada010.reposcout.entity.DocChunk;

/**
 * doc_chunk 表访问接口。按 repo 维度整体读取/删除:文档块百级规模,
 * 检索时加载单仓库全部块在进程内算相似度(见 RepoVectorStore),幂等重建先删后插。
 */
public interface DocChunkRepository extends JpaRepository<DocChunk, Long> {

    List<DocChunk> findByRepoId(long repoId);

    /**
     * 批量删除某仓库全部块。用 JPQL 批量 DELETE(立即执行)而非派生删除:
     * 幂等重建在同一事务内先删后插,若走「加载实体再逐条删」会把删除排进 Hibernate
     * 动作队列,而队列默认 INSERT 先于 DELETE,导致重建时唯一键(repo_id,file_path,
     * chunk_index)冲突。批量 DELETE 立即落库,保证先删后插的真实顺序。
     */
    @Modifying
    @Query("delete from DocChunk d where d.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") long repoId);
}
