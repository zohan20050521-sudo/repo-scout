package io.github.chada010.reposcout.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import io.github.chada010.reposcout.entity.Repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2(MODE=MySQL)+ Flyway 建表的持久层测试:
 * 表结构与唯一键由 V1__create_repo_table.sql 创建,测试同时验证迁移脚本可执行。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RepoRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 12, 0, 0);

    @Autowired
    private RepoRepository repository;

    private Repo repo(String owner, String name) {
        return new Repo(owner, name, "main", "desc",
                "https://github.com/" + owner + "/" + name, NOW, NOW);
    }

    @Test
    void savedRepoCanBeFoundByOwnerAndName() {
        Repo saved = repository.saveAndFlush(repo("octocat", "Hello-World"));

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findByOwnerAndName("octocat", "Hello-World"))
                .hasValueSatisfying(found -> {
                    assertThat(found.getId()).isEqualTo(saved.getId());
                    assertThat(found.getDefaultBranch()).isEqualTo("main");
                });
        assertThat(repository.findByOwnerAndName("octocat", "no-such-repo")).isEmpty();
    }

    @Test
    void duplicateOwnerAndNameViolatesUniqueKey() {
        repository.saveAndFlush(repo("octocat", "Hello-World"));

        assertThatThrownBy(() -> repository.saveAndFlush(repo("octocat", "Hello-World")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nullDescriptionIsAllowedBySchema() {
        Repo saved = repository.saveAndFlush(new Repo("octocat", "no-desc", "main", null,
                "https://github.com/octocat/no-desc", NOW, NOW));

        assertThat(repository.findById(saved.getId()))
                .hasValueSatisfying(found -> assertThat(found.getDescription()).isNull());
    }

    @Test
    void findAllOrdersByIdDescending() {
        Repo first = repository.saveAndFlush(repo("a", "first"));
        Repo second = repository.saveAndFlush(repo("b", "second"));

        List<Repo> repos = repository.findAllByOrderByIdDesc();

        assertThat(repos).extracting(Repo::getId)
                .containsSubsequence(second.getId(), first.getId());
        assertThat(repos.get(0).getId()).isEqualTo(second.getId());
    }
}
