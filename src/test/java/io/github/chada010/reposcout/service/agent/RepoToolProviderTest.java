package io.github.chada010.reposcout.service.agent;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.config.AgentProperties;
import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.memory.SessionRepoBinding;
import io.github.chada010.reposcout.repository.RepoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RepoToolProviderTest {

    private static final String SESSION_ID = "0f14d0ab-9605-4a62-a9e4-5ed26688389b";

    @Mock
    private GithubApiClient githubApiClient;

    @Mock
    private SessionRepoBinding sessionRepoBinding;

    @Mock
    private RepoRepository repoRepository;

    private RepoToolProvider provider;

    @BeforeEach
    void setUp() {
        ToolsProperties toolsProperties = new ToolsProperties(3, 200, 8000, 20, 20);
        AgentProperties agentProperties = new AgentProperties(5);
        provider = new RepoToolProvider(githubApiClient, toolsProperties, agentProperties,
                sessionRepoBinding, repoRepository);
    }

    private ToolProviderRequest request() {
        return new ToolProviderRequest(SESSION_ID, UserMessage.from("这个项目怎么跑"));
    }

    @Test
    void unboundSessionGetsNoTools() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.empty());

        assertThat(provider.provideTools(request())).isNull();
    }

    @Test
    void bindingToMissingRepoRecordGetsNoTools() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.of(1L));
        given(repoRepository.findById(1L)).willReturn(Optional.empty());

        assertThat(provider.provideTools(request())).isNull();
    }

    @Test
    void boundSessionGetsFourGithubTools() {
        LocalDateTime now = LocalDateTime.now();
        Repo repo = new Repo("octocat", "Hello-World", "master", "desc", "url", now, now);
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.of(1L));
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo));

        ToolProviderResult result = provider.provideTools(request());

        assertThat(result).isNotNull();
        Set<String> names = result.tools().keySet().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("repoTree", "readme", "issues", "recentCommits");
    }
}
