package io.github.chada010.reposcout.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

/**
 * README 工具:取当前仓库 README 原文,超 readme-max-chars 按配置截断并加尾注。
 * 非单例 bean,编排期按仓库实例化(构造注入 GithubApiClient / ToolsProperties / RepoRef)。
 * 无 README(404,契约明确不算错误)返回可读文本;其余 GitHub 失败降级,不向上抛。
 */
public class GithubReadmeTool {

    private static final Logger log = LoggerFactory.getLogger(GithubReadmeTool.class);

    private static final String RAW_ACCEPT = "application/vnd.github.raw+json";
    private static final String RATE_LIMIT_TEXT = "GitHub API 限流,请稍后重试";
    private static final String UNAVAILABLE_TEXT = "GitHub API 暂时不可用,请稍后重试";
    private static final String NO_README_TEXT = "该仓库没有 README";

    private final GithubApiClient client;
    private final ToolsProperties props;
    private final RepoRef repo;

    public GithubReadmeTool(GithubApiClient client, ToolsProperties props, RepoRef repo) {
        this.client = client;
        this.props = props;
        this.repo = repo;
    }

    @Tool("获取当前仓库的 README 原文,了解项目定位与使用方法。")
    public String readme() {
        try {
            String raw = client.getRaw(readmePath(), RAW_ACCEPT);
            if (raw == null || raw.isEmpty()) {
                return NO_README_TEXT;
            }
            return truncate(raw);
        } catch (GithubRateLimitException e) {
            // 限流是 GithubUnavailableException 的子类,必须先于父类捕获
            log.warn("README 获取限流: repo={}/{}", repo.owner(), repo.name());
            return RATE_LIMIT_TEXT;
        } catch (GithubUnavailableException e) {
            log.warn("README 获取失败(GitHub 不可用): repo={}/{}, error={}",
                    repo.owner(), repo.name(), e.getMessage());
            return UNAVAILABLE_TEXT;
        } catch (RepoNotFoundException e) {
            log.warn("README 不存在: repo={}/{}", repo.owner(), repo.name());
            return NO_README_TEXT;
        }
    }

    private String readmePath() {
        return "/repos/" + repo.owner() + "/" + repo.name() + "/readme";
    }

    private String truncate(String raw) {
        int max = props.readmeMaxChars();
        if (raw.length() <= max) {
            return raw;
        }
        return raw.substring(0, max)
                + "\n(已截断:原文 " + raw.length() + " 字符,显示前 " + max + " 字符)";
    }
}
