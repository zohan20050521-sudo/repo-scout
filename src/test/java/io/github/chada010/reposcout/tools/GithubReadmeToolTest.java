package io.github.chada010.reposcout.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * GithubReadmeTool 单测:Mockito mock GithubApiClient,不打真实 GitHub、不起 Spring 上下文。
 * 覆盖正常原文、超长截断、无 README(404)与限流/不可用降级,以及请求路径与 Accept 头。
 */
@ExtendWith(MockitoExtension.class)
class GithubReadmeToolTest {

    @Mock
    private GithubApiClient client;

    private static ToolsProperties props(int readmeMaxChars) {
        return new ToolsProperties(3, 200, readmeMaxChars, 20, 20);
    }

    private static RepoRef repo() {
        return new RepoRef("spring-projects", "spring-petclinic", "main");
    }

    private GithubReadmeTool tool(ToolsProperties props) {
        return new GithubReadmeTool(client, props, repo());
    }

    @Test
    void returnsRawReadmeWhenWithinLimit() {
        given(client.getRaw(anyString(), anyString())).willReturn("# Hello\n项目介绍");

        assertThat(tool(props(8000)).readme()).isEqualTo("# Hello\n项目介绍");
    }

    @Test
    void truncatesLongReadmeWithFootnote() {
        given(client.getRaw(anyString(), anyString())).willReturn("0123456789ABCDEFGHIJ");

        assertThat(tool(props(10)).readme())
                .isEqualTo("0123456789\n(已截断:原文 20 字符,显示前 10 字符)");
    }

    @Test
    void notFoundReturnsNoReadmeText() {
        given(client.getRaw(anyString(), anyString())).willThrow(new RepoNotFoundException("nf"));

        assertThat(tool(props(8000)).readme()).isEqualTo("该仓库没有 README");
    }

    @Test
    void emptyBodyReturnsNoReadmeText() {
        given(client.getRaw(anyString(), anyString())).willReturn("");

        assertThat(tool(props(8000)).readme()).isEqualTo("该仓库没有 README");
    }

    @Test
    void rateLimitReturnsFriendlyText() {
        given(client.getRaw(anyString(), anyString())).willThrow(new GithubRateLimitException("rl"));

        assertThat(tool(props(8000)).readme()).isEqualTo("GitHub API 限流,请稍后重试");
    }

    @Test
    void unavailableReturnsFriendlyText() {
        given(client.getRaw(anyString(), anyString())).willThrow(new GithubUnavailableException("down"));

        assertThat(tool(props(8000)).readme()).isEqualTo("GitHub API 暂时不可用,请稍后重试");
    }

    @Test
    void usesReadmePathAndRawAcceptHeader() {
        given(client.getRaw(anyString(), anyString())).willReturn("# ok");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> accept = ArgumentCaptor.forClass(String.class);

        tool(props(8000)).readme();

        verify(client).getRaw(path.capture(), accept.capture());
        assertThat(path.getValue()).isEqualTo("/repos/spring-projects/spring-petclinic/readme");
        assertThat(accept.getValue()).isEqualTo("application/vnd.github.raw+json");
    }
}
