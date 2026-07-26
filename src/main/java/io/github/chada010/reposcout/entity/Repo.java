package io.github.chada010.reposcout.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 仓库接入记录(FR-2.1)。schema 由 Flyway 管理
 * (db/migration/V1__create_repo_table.sql),ddl-auto=none,
 * 实体不负责建表;owner/name 存 GitHub 规范大小写,(owner, name) 唯一。
 */
@Entity
@Table(name = "repo")
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String owner;

    private String name;

    private String defaultBranch;

    private String description;

    private String htmlUrl;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    protected Repo() {
        // JPA 规范要求的无参构造
    }

    public Repo(String owner, String name, String defaultBranch, String description,
                String htmlUrl, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.owner = owner;
        this.name = name;
        this.defaultBranch = defaultBranch;
        this.description = description;
        this.htmlUrl = htmlUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 重复接入时刷新可变元信息与 updatedAt(幂等语义,FR-2.1)。 */
    public void refreshFrom(String defaultBranch, String description, String htmlUrl, LocalDateTime now) {
        this.defaultBranch = defaultBranch;
        this.description = description;
        this.htmlUrl = htmlUrl;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public String getDescription() {
        return description;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
