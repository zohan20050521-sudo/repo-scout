package io.github.chada010.reposcout.github;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.chada010.reposcout.config.GithubProperties;
import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GithubApiClient 单测:MockRestServiceServer 拦截请求,不打真实 GitHub。
 * 覆盖请求头、错误映射与「500ms 重试 1 次」策略。
 */
class GithubApiClientTest {

    private static final String BASE_URL = "https://api.github.test";
    private static final String REPO_PATH = "/repos/octocat/Hello-World";
    private static final String REPO_URL = BASE_URL + REPO_PATH;

    private MockRestServiceServer server;

    private GithubApiClient buildClient(String token) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new GithubApiClient(builder, new GithubProperties(BASE_URL, token, Duration.ofSeconds(2)));
    }

    @Test
    void getJsonSendsGithubHeadersAndBearerTokenWhenConfigured() {
        GithubApiClient client = buildClient("test-token");
        server.expect(once(), requestTo(REPO_URL))
                .andExpect(header(HttpHeaders.USER_AGENT, "repo-scout"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andRespond(withSuccess("{\"full_name\":\"octocat/Hello-World\"}", MediaType.APPLICATION_JSON));

        JsonNode json = client.getJson(REPO_PATH, Map.of());

        assertThat(json.path("full_name").asText()).isEqualTo("octocat/Hello-World");
        server.verify();
    }

    @Test
    void noAuthorizationHeaderWhenTokenEmpty() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(REPO_URL))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getJson(REPO_PATH, Map.of());

        server.verify();
    }

    @Test
    void getJsonAppendsQueryParams() {
        GithubApiClient client = buildClient("");
        // Map 遍历顺序不确定,只按参数逐个断言,不断言完整 query 串
        server.expect(once(), requestTo(startsWith(BASE_URL + REPO_PATH + "/issues?")))
                .andExpect(queryParam("state", "open"))
                .andExpect(queryParam("per_page", "20"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.getJson(REPO_PATH + "/issues", Map.of("state", "open", "per_page", 20));

        server.verify();
    }

    @Test
    void getRawSendsCustomAcceptHeaderAndReturnsBody() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(BASE_URL + REPO_PATH + "/readme"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json"))
                .andRespond(withSuccess("# Hello World", MediaType.TEXT_PLAIN));

        String raw = client.getRaw(REPO_PATH + "/readme", "application/vnd.github.raw+json");

        assertThat(raw).isEqualTo("# Hello World");
        server.verify();
    }

    @Test
    void notFoundThrowsRepoNotFoundWithoutRetry() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"message\":\"Not Found\"}"));

        assertThatThrownBy(() -> client.getJson(REPO_PATH, Map.of()))
                .isInstanceOf(RepoNotFoundException.class);
        server.verify();
    }

    @Test
    void forbiddenWithZeroRemainingHeaderThrowsRateLimitWithoutRetry() {
        GithubApiClient client = buildClient("");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "0");
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(headers).body("{}"));

        assertThatThrownBy(() -> client.getJson(REPO_PATH, Map.of()))
                .isInstanceOf(GithubRateLimitException.class)
                .hasMessageContaining("限流");
        server.verify();
    }

    @Test
    void tooManyRequestsWithRateLimitMessageThrowsRateLimitWithoutRetry() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"message\":\"API rate limit exceeded\"}"));

        assertThatThrownBy(() -> client.getJson(REPO_PATH, Map.of()))
                .isInstanceOf(GithubRateLimitException.class)
                .hasMessageContaining("限流");
        server.verify();
    }

    @Test
    void plainForbiddenWithoutRateLimitSignatureThrowsUnavailableWithoutRetry() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"message\":\"Forbidden\"}"));

        assertThatThrownBy(() -> client.getJson(REPO_PATH, Map.of()))
                .isInstanceOf(GithubUnavailableException.class)
                .isNotInstanceOf(GithubRateLimitException.class);
        server.verify();
    }

    @Test
    void serverErrorIsRetriedOnceAndSucceeds() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(REPO_URL)).andRespond(withServerError());
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withSuccess("{\"full_name\":\"octocat/Hello-World\"}", MediaType.APPLICATION_JSON));

        JsonNode json = client.getJson(REPO_PATH, Map.of());

        assertThat(json.path("full_name").asText()).isEqualTo("octocat/Hello-World");
        server.verify();
    }

    @Test
    void serverErrorIsRetriedOnceThenThrowsUnavailable() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(REPO_URL)).andRespond(withServerError());
        server.expect(once(), requestTo(REPO_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.getJson(REPO_PATH, Map.of()))
                .isInstanceOf(GithubUnavailableException.class);
        server.verify();
    }

    @Test
    void timeoutIsRetriedOnceThenThrowsUnavailable() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.getJson(REPO_PATH, Map.of()))
                .isInstanceOf(GithubUnavailableException.class);
        server.verify();
    }

    @Test
    void timeoutIsRetriedOnceAndSucceeds() {
        GithubApiClient client = buildClient("");
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(once(), requestTo(REPO_URL))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        JsonNode json = client.getJson(REPO_PATH, Map.of());

        assertThat(json.path("ok").asBoolean()).isTrue();
        server.verify();
    }
}
