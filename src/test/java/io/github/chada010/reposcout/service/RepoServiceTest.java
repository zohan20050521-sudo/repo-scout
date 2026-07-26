package io.github.chada010.reposcout.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.exception.InvalidParamException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.repository.RepoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RepoServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDateTime OLD_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Mock
    private GithubApiClient githubApiClient;

    @Mock
    private RepoRepository repoRepository;

    private RepoService service() {
        return new RepoService(githubApiClient, repoRepository);
    }

    private ObjectNode githubRepoJson(String fullName, String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("full_name", fullName);
        node.put("default_branch", "master");
        if (description == null) {
            node.putNull("description");
        } else {
            node.put("description", description);
        }
        node.put("html_url", "https://github.com/" + fullName);
        node.put("private", false);
        return node;
    }

    private Repo existingRepo(long id) {
        Repo repo = new Repo("octocat", "Hello-World", "old-branch", "old desc",
                "https://github.com/octocat/Hello-World", OLD_TIME, OLD_TIME);
        ReflectionTestUtils.setField(repo, "id", id);
        return repo;
    }

    @Test
    void onboardNewRepoStoresCanonicalCaseFromGithubFullName() {
        // 用户输入大小写随意,入库以 GitHub full_name 为准
        given(githubApiClient.getJson(eq("/repos/OCTOCAT/hello-world"), anyMap()))
                .willReturn(githubRepoJson("octocat/Hello-World", "My first repo"));
        given(repoRepository.findByOwnerAndName("octocat", "Hello-World")).willReturn(Optional.empty());
        given(repoRepository.save(any(Repo.class))).willAnswer(inv -> inv.getArgument(0));

        Repo saved = service().onboard("OCTOCAT/hello-world");

        assertThat(saved.getOwner()).isEqualTo("octocat");
        assertThat(saved.getName()).isEqualTo("Hello-World");
        assertThat(saved.getDefaultBranch()).isEqualTo("master");
        assertThat(saved.getDescription()).isEqualTo("My first repo");
        assertThat(saved.getHtmlUrl()).isEqualTo("https://github.com/octocat/Hello-World");
        assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void onboardExistingRepoRefreshesMetadataAndKeepsId() {
        given(githubApiClient.getJson(eq("/repos/octocat/Hello-World"), anyMap()))
                .willReturn(githubRepoJson("octocat/Hello-World", "new desc"));
        given(repoRepository.findByOwnerAndName("octocat", "Hello-World"))
                .willReturn(Optional.of(existingRepo(7L)));
        given(repoRepository.save(any(Repo.class))).willAnswer(inv -> inv.getArgument(0));

        Repo updated = service().onboard("https://github.com/octocat/Hello-World.git");

        assertThat(updated.getId()).isEqualTo(7L);
        assertThat(updated.getDefaultBranch()).isEqualTo("master");
        assertThat(updated.getDescription()).isEqualTo("new desc");
        assertThat(updated.getCreatedAt()).isEqualTo(OLD_TIME);
        assertThat(updated.getUpdatedAt()).isAfter(OLD_TIME);
    }

    @Test
    void concurrentUniqueConflictFallsBackToRequeryAndUpdate() {
        given(githubApiClient.getJson(eq("/repos/octocat/Hello-World"), anyMap()))
                .willReturn(githubRepoJson("octocat/Hello-World", "desc"));
        // 首查为空 → 插入撞唯一键 → 重查命中并发写入的记录
        given(repoRepository.findByOwnerAndName("octocat", "Hello-World"))
                .willReturn(Optional.empty(), Optional.of(existingRepo(3L)));
        given(repoRepository.save(any(Repo.class)))
                .willThrow(new DataIntegrityViolationException("uk_repo_owner_name"))
                .willAnswer(inv -> inv.getArgument(0));

        Repo result = service().onboard("octocat/Hello-World");

        assertThat(result.getId()).isEqualTo(3L);
        assertThat(result.getDescription()).isEqualTo("desc");
    }

    @Test
    void privateRepoIsRejectedAsNotFound() {
        ObjectNode json = githubRepoJson("octocat/secret", null);
        json.put("private", true);
        given(githubApiClient.getJson(eq("/repos/octocat/secret"), anyMap())).willReturn(json);

        assertThatThrownBy(() -> service().onboard("octocat/secret"))
                .isInstanceOf(RepoNotFoundException.class)
                .hasMessageContaining("私有仓库暂不支持");
        verify(repoRepository, never()).save(any());
    }

    @Test
    void githubNotFoundIsRethrownWithReadableMessage() {
        given(githubApiClient.getJson(eq("/repos/octocat/definitely-not-exist-xyz"), anyMap()))
                .willThrow(new RepoNotFoundException("GitHub 上未找到该资源"));

        assertThatThrownBy(() -> service().onboard("octocat/definitely-not-exist-xyz"))
                .isInstanceOf(RepoNotFoundException.class)
                .hasMessageContaining("octocat/definitely-not-exist-xyz")
                .hasMessageContaining("私有仓库暂不支持");
    }

    @Test
    void overlongDescriptionIsTruncatedToColumnWidth() {
        given(githubApiClient.getJson(eq("/repos/octocat/Hello-World"), anyMap()))
                .willReturn(githubRepoJson("octocat/Hello-World", "很".repeat(1500)));
        given(repoRepository.findByOwnerAndName("octocat", "Hello-World")).willReturn(Optional.empty());
        given(repoRepository.save(any(Repo.class))).willAnswer(inv -> inv.getArgument(0));

        Repo saved = service().onboard("octocat/Hello-World");

        assertThat(saved.getDescription()).hasSize(1000);
    }

    @Test
    void invalidAddressFailsBeforeCallingGithub() {
        assertThatThrownBy(() -> service().onboard("https://gitlab.com/a/b"))
                .isInstanceOf(InvalidParamException.class);
        verify(githubApiClient, never()).getJson(any(), anyMap());
    }

    @Test
    void getRepoMissingIdThrowsNotFound() {
        given(repoRepository.findById(999999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().getRepo(999999L))
                .isInstanceOf(RepoNotFoundException.class)
                .hasMessageContaining("仓库未接入或不存在");
    }

    @Test
    void listReposDelegatesToIdDescQuery() {
        service().listRepos();

        verify(repoRepository).findAllByOrderByIdDesc();
    }
}
