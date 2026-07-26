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
 * GithubCommitsTool 单测:Mockito mock GithubApiClient,fixture 用 ObjectMapper 构造,
 * 不打真实 GitHub、不起 Spring 上下文。覆盖格式化(多行取首行、作者名兜底)、
 * limit 钳制、空结果文案与三类异常降级。
 */
@ExtendWith(MockitoExtension.class)
class GithubCommitsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolsProperties PROPS = new ToolsProperties(3, 200, 8000, 20, 20);
    private static final RepoRef REPO = new RepoRef("spring-projects", "spring-petclinic", "main");
    private static final String COMMITS_PATH = "/repos/spring-projects/spring-petclinic/commits";

    @Mock
    private GithubApiClient client;

    private GithubCommitsTool tool() {
        return new GithubCommitsTool(client, PROPS, REPO);
    }

    private JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    void formatsCommitsTakingFirstMessageLine() throws Exception {
        String raw = """
                [
                  {"sha":"a1b2c3d4e5f6","commit":{"author":{"name":"alice","date":"2026-07-25T12:00:00Z"},
                    "message":"fix: 修复会话过期时间未刷新\\n\\n更详细的正文第二段"}},
                  {"sha":"9e8f7a6b","commit":{"author":{"name":"bob","date":"2026-07-24T09:30:00Z"},
                    "message":"feat: 新增仓库接入接口"}}
                ]
                """;
        given(client.getJson(anyString(), anyMap())).willReturn(json(raw));

        String out = tool().recentCommits(5);

        assertThat(out).isEqualTo(
                "a1b2c3d 2026-07-25 alice: fix: 修复会话过期时间未刷新\n"
                        + "9e8f7a6 2026-07-24 bob: feat: 新增仓库接入接口");
    }

    @Test
    void fallsBackToUnknownWhenAuthorNameMissing() throws Exception {
        String raw = """
                [{"sha":"abcdef1234567","commit":{"author":{"date":"2026-07-25T00:00:00Z"},
                  "message":"chore: 清理依赖"}}]
                """;
        given(client.getJson(anyString(), anyMap())).willReturn(json(raw));

        assertThat(tool().recentCommits(1))
                .isEqualTo("abcdef1 2026-07-25 unknown: chore: 清理依赖");
    }

    @Test
    void returnsEmptyTextWhenNoCommits() throws Exception {
        given(client.getJson(anyString(), anyMap())).willReturn(json("[]"));

        assertThat(tool().recentCommits(5)).isEqualTo("没有提交记录");
    }

    @ParameterizedTest
    @MethodSource("limitCases")
    void clampsLimitToConfiguredMax(Integer input, int expectedPerPage) throws Exception {
        given(client.getJson(anyString(), anyMap())).willReturn(json("[]"));

        tool().recentCommits(input);

        verify(client).getJson(eq(COMMITS_PATH),
                argThat((Map<String, ?> params) -> Integer.valueOf(expectedPerPage).equals(params.get("per_page"))));
    }

    private static Stream<Arguments> limitCases() {
        return Stream.of(
                arguments(null, 20),   // 缺省取上限
                arguments(0, 20),      // <1 取上限
                arguments(-3, 20),     // <1 取上限
                arguments(5, 5),       // 上限内原样
                arguments(20, 20),     // 恰为上限
                arguments(100, 20));   // 超上限钳制
    }

    @Test
    void returnsRateLimitTextOnRateLimit() {
        given(client.getJson(anyString(), anyMap())).willThrow(new GithubRateLimitException("x"));

        assertThat(tool().recentCommits(5)).isEqualTo("GitHub API 限流,请稍后重试");
    }

    @Test
    void returnsUnavailableTextOnUnavailable() {
        given(client.getJson(anyString(), anyMap())).willThrow(new GithubUnavailableException("x"));

        assertThat(tool().recentCommits(5)).isEqualTo("GitHub API 暂时不可用,请稍后重试");
    }

    @Test
    void returnsNotFoundTextOnRepoNotFound() {
        given(client.getJson(anyString(), anyMap())).willThrow(new RepoNotFoundException("x"));

        assertThat(tool().recentCommits(5)).isEqualTo("未找到相关数据(仓库可能已变更)");
    }
}
