package io.github.chada010.reposcout.exception;

/**
 * GitHub API 限流(403/429 且带限流特征)。与父类同样映射为
 * 502 + GITHUB_UNAVAILABLE,单独成类便于工具层降级时区分提示文案。
 */
public class GithubRateLimitException extends GithubUnavailableException {

    public GithubRateLimitException(String message) {
        super(message);
    }
}
