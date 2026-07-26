package io.github.chada010.reposcout.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 文档向量块(FR-3.1)。schema 由 Flyway 管理
 * (db/migration/V2__create_doc_chunk_table.sql),ddl-auto=none,实体不负责建表。
 * embedding 为 float[] 的 JSON 数组文本;(repoId, filePath, chunkIndex) 唯一,
 * 支撑「先删后插」的幂等重建。
 */
@Entity
@Table(name = "doc_chunk")
public class DocChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long repoId;

    private String filePath;

    private Integer chunkIndex;

    private String content;

    private String embedding;

    private LocalDateTime createdAt;

    protected DocChunk() {
        // JPA 规范要求的无参构造
    }

    public DocChunk(Long repoId, String filePath, Integer chunkIndex, String content,
                    String embedding, LocalDateTime createdAt) {
        this.repoId = repoId;
        this.filePath = filePath;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embedding = embedding;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getRepoId() {
        return repoId;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public String getEmbedding() {
        return embedding;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
