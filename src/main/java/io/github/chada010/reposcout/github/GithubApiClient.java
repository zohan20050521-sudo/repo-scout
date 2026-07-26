package io.github.chada010.reposcout.github;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import io.github.chada010.reposcout.config.GithubProperties;
import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;

/**
 * GitHub REST API 通用客户端:只暴露与业务无关的 GET 方法,供仓库接入
 * 服务与 v0.2 各工具共用。统一错误映射与重试策略:
 * 404 与限流不重试;网络错误/超时/5xx 固定间隔 500ms 重试 1 次。
 * 失败记 WARN 日志(path、状态码),不记录 token。
 */
@Component
public class GithubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GithubApiClient.class);

    /** GitHub 建议所有请求显式声明的 API 版本。 */
    private static final String API_VERSION = "2022-11-28";
    private static final String JSON_ACCEPT = "application/vnd.github+json";
    private static final long RETRY_INTERVAL_MS = 500;

    private static final String UNAVAILABLE_MESSAGE = "GitHub 服务暂时不可用,请稍后重试";
    private static final String RATE_LIMIT_MESSAGE = "GitHub API 限流,请稍后重试";

    private final RestClient restClient;

    public GithubApiClient(RestClient.Builder builder, GithubProperties properties) {
        RestClient.Builder configured = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "repo-scout")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION);
        if (StringUtils.hasText(properties.token())) {
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token());
        }
        this.restClient = configured.build();
    }

    /** GET 并解析为 JSON。path 为 GitHub API 路径(如 /repos/{owner}/{name} 的展开形式)。 */
    public JsonNode getJson(String path, Map<String, ?> queryParams) {
        return executeWithRetry(path, () -> restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(path);
                    queryParams.forEach(uriBuilder::queryParam);
                    return uriBuilder.build();
                })
                .header(HttpHeaders.ACCEPT, JSON_ACCEPT)
                .retrieve()
                .body(JsonNode.class));
    }

    /** GET 原文(如 README 用 Accept: application/vnd.github.raw+json)。 */
    public String getRaw(String path, String acceptHeader) {
        return executeWithRetry(path, () -> restClient.get()
                .uri(path)
                .header(HttpHeaders.ACCEPT, acceptHeader)
                .retrieve()
                .body(String.class));
    }

    private <T> T executeWithRetry(String path, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            RuntimeException mapped = mapHttpError(path, e);
            if (!e.getStatusCode().is5xxServerError()) {
                throw mapped;
            }
        } catch (ResourceAccessException e) {
            log.warn("GitHub API 网络错误: path={}, error={}", path, e.getMessage());
        }
        sleepBeforeRetry();
        log.warn("GitHub API 重试 1 次: path={}", path);
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw mapHttpError(path, e);
        } catch (ResourceAccessException e) {
            log.warn("GitHub API 网络错误(重试后仍失败): path={}, error={}", path, e.getMessage());
            throw new GithubUnavailableException(UNAVAILABLE_MESSAGE);
        }
    }

    private RuntimeException mapHttpError(String path, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        log.warn("GitHub API 请求失败: path={}, status={}", path, status);
        if (status == HttpStatus.NOT_FOUND.value()) {
            return new RepoNotFoundException("GitHub 上未找到该资源");
        }
        if ((status == HttpStatus.FORBIDDEN.value() || status == HttpStatus.TOO_MANY_REQUESTS.value())
                && isRateLimited(e)) {
            return new GithubRateLimitException(RATE_LIMIT_MESSAGE);
        }
        return new GithubUnavailableException(UNAVAILABLE_MESSAGE);
    }

    private boolean isRateLimited(RestClientResponseException e) {
        HttpHeaders headers = e.getResponseHeaders();
        if (headers != null && "0".equals(headers.getFirst("X-RateLimit-Remaining"))) {
            return true;
        }
        return e.getResponseBodyAsString().toLowerCase(Locale.ROOT).contains("rate limit");
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GithubUnavailableException(UNAVAILABLE_MESSAGE);
        }
    }
}
