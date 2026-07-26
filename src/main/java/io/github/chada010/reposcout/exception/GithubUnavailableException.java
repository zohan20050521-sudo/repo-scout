package io.github.chada010.reposcout.exception;

/**
 * GitHub 访问失败:网络错误、超时、GitHub 5xx 或限流(见子类)。
 * 由全局异常处理映射为 502 + GITHUB_UNAVAILABLE,message 对用户可读。
 */
public class GithubUnavailableException extends RuntimeException {

    public GithubUnavailableException(String message) {
        super(message);
    }
}
