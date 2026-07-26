package io.github.chada010.reposcout.tools;

import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * GithubIssuesTool 单测:Mockito mock GithubApiClient,fixture 用 ObjectMapper 构造,
 * 不打真实 GitHub、不起 Spring 上下文。覆盖格式化、PR 过滤、state 归一化、
 * 空结果文案、per_page 取值与三类异常降级。
 */
@ExtendWith(MockitoExtension.class)
class GithubIssuesToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolsProperties PROPS = new ToolsProperties(3, 200, 8000, 20, 20);
    private static final RepoRef REPO = new RepoRef("spring-projects", "spring-petclinic", "main");
    private static final String ISSUES_PATH = "/repos/spring-projects/spring-petclinic/issues";

    @Mock
    private GithubApiClient client;

    private GithubIssuesTool tool() {
        return new GithubIssuesTool(client, PROPS, REPO);
    }

    private JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    void formatsIssuesWithAndWithoutLabels() throws Exception {
        String raw = """
                [
                  {"number":42,"state":"open","title":"登录超时后会话未清理","updated_at":"2026-07-20T10:00:00Z",
                   "labels":[{"name":"bug"},{"name":"auth"}]},
                  {"number":38,"state":"open","title":"支持自定义端口","updated_at":"2026-07-18T08:30:00Z",
                   "labels":[]}
                ]
                """;
        given(client.getJson(anyString(), anyMap())).willReturn(json(raw));

        String out = tool().issues("open");

        assertThat(out).isEqualTo(
                "#42 [open] 登录超时后会话未清理(更新于 2026-07-20;标签 bug,auth)\n"
                        + "#38 [open] 支持自定义端口(更新于 2026-07-18)");
    }

    @Test
    void filtersOutPullRequestEntries() throws Exception {
        String raw = """
                [
                  {"number":100,"state":"open","title":"真实 issue","updated_at":"2026-07-20T00:00:00Z","labels":[]},
                  {"number":101,"state":"open","title":"这是一个 PR","updated_at":"2026-07-21T00:00:00Z",
                   "labels":[],"pull_request":{"url":"https://api.github.com/repos/x/y/pulls/101"}}
                ]
                """;
        given(client.getJson(anyString(), anyMap())).willReturn(json(raw));

        String out = tool().issues("all");

        assertThat(out).isEqualTo("#100 [open] 真实 issue(更新于 2026-07-20)");
        assertThat(out.lines()).hasSize(1);
        assertThat(out).doesNotContain("#101");
    }

    @Test
    void returnsEmptyTextWhenNoIssues() throws Exception {
        given(client.getJson(anyString(), anyMap())).willReturn(json("[]"));

        assertThat(tool().issues("open")).isEqualTo("无符合条件的 issue");
    }

    @Test
    void returnsEmptyTextWhenAllEntriesArePullRequests() throws Exception {
        String raw = """
                [{"number":1,"state":"open","title":"pr","updated_at":"2026-07-20T00:00:00Z",
                  "labels":[],"pull_request":{}}]
                """;
        given(client.getJson(anyString(), anyMap())).willReturn(json(raw));

        assertThat(tool().issues("open")).isEqualTo("无符合条件的 issue");
    }

    @ParameterizedTest
    @MethodSource("stateCases")
    void normalizesStateBeforeCall(String input, String expected) throws Exception {
        given(client.getJson(anyString(), anyMap())).willReturn(json("[]"));

        tool().issues(input);

        verify(client).getJson(eq(ISSUES_PATH),
                argThat((Map<String, ?> params) -> expected.equals(params.get("state"))));
    }

    private static Stream<Arguments> stateCases() {
        return Stream.of(
                arguments(null, "open"),
                arguments("   ", "open"),
                arguments("weird", "open"),
                arguments("CLOSED", "closed"),
                arguments("Open", "open"),
                arguments("all", "all"));
    }

    @Test
    void sendsConfiguredPerPage() throws Exception {
        given(client.getJson(anyString(), anyMap())).willReturn(json("[]"));

        tool().issues("open");

        verify(client).getJson(eq(ISSUES_PATH),
                argThat((Map<String, ?> params) -> Integer.valueOf(20).equals(params.get("per_page"))));
    }

    @Test
    void returnsRateLimitTextOnRateLimit() {
        given(client.getJson(anyString(), anyMap())).willThrow(new GithubRateLimitException("x"));

        assertThat(tool().issues("open")).isEqualTo("GitHub API 限流,请稍后重试");
    }

    @Test
    void returnsUnavailableTextOnUnavailable() {
        given(client.getJson(anyString(), anyMap())).willThrow(new GithubUnavailableException("x"));

        assertThat(tool().issues("open")).isEqualTo("GitHub API 暂时不可用,请稍后重试");
    }

    @Test
    void returnsNotFoundTextOnRepoNotFound() {
        given(client.getJson(anyString(), anyMap())).willThrow(new RepoNotFoundException("x"));

        assertThat(tool().issues("open")).isEqualTo("未找到相关数据(仓库可能已变更)");
    }
}
