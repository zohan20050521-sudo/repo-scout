-- 文档向量块(FR-3.1)。沿用 V1 风格:不写 ENGINE/CHARSET,
-- 同一脚本需在 H2(MODE=MySQL)测试环境执行。
-- 不加外键约束(与 V1 一致),repo 存在性由触发索引的端点在应用层保证。
-- embedding 存 float[] 的 JSON 数组文本(Jackson 序列化),维度以模型实际输出为准,
-- 不硬编码进列类型,便于日后换模型;(repo_id, file_path, chunk_index) 唯一支撑幂等重建。
CREATE TABLE doc_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id     BIGINT       NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    embedding   MEDIUMTEXT   NOT NULL,
    created_at  DATETIME     NOT NULL,
    CONSTRAINT uk_doc_chunk UNIQUE (repo_id, file_path, chunk_index)
);
CREATE INDEX idx_doc_chunk_repo ON doc_chunk (repo_id);
