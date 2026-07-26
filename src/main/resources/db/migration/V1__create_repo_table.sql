-- 仓库接入记录(FR-2.1)。
-- 不写 ENGINE/CHARSET 子句:同一脚本需在 H2(MODE=MySQL)测试环境执行,
-- MySQL 8 默认即 InnoDB/utf8mb4。
CREATE TABLE repo (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner          VARCHAR(100)  NOT NULL,
    name           VARCHAR(200)  NOT NULL,
    default_branch VARCHAR(100)  NOT NULL,
    description    VARCHAR(1000) NULL,
    html_url       VARCHAR(500)  NOT NULL,
    created_at     DATETIME      NOT NULL,
    updated_at     DATETIME      NOT NULL,
    CONSTRAINT uk_repo_owner_name UNIQUE (owner, name)
);
