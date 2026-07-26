package io.github.chada010.reposcout.exception;

/**
 * 仓库不存在:GitHub 查无此公开仓库(私有仓库同样走此异常),
 * 或按 id 查询时记录未接入。由全局异常处理映射为 404 + REPO_NOT_FOUND。
 */
public class RepoNotFoundException extends RuntimeException {

    public RepoNotFoundException(String message) {
        super(message);
    }
}
