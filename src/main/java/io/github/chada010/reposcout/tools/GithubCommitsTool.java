package io.github.chada010.reposcout.tools;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
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
 * 最近 commits 工具(FR-2.2):列出当前仓库近期提交,供 Agent 了解开发动向。
 * 非单例 bean,按仓库实例化,构造注入访问出口、裁剪配置与仓库标识。
 * limit 为空或非正数时取配置默认(即上限),给定值只能在上限内调小;
 * GitHub 异常统一降级为一行可读文本,不向上抛,保证工具失败不中断对话。
 */
public class GithubCommitsTool {

    private static final Logger log = LoggerFactory.getLogger(GithubCommitsTool.class);

    private static final int SHORT_SHA_LEN = 7;
    private static final String UNKNOWN_AUTHOR = "unknown";

    private static final String RATE_LIMIT_TEXT = "GitHub API 限流,请稍后重试";
    private static final String UNAVAILABLE_TEXT = "GitHub API 暂时不可用,请稍后重试";
    private static final String NOT_FOUND_TEXT = "未找到相关数据(仓库可能已变更)";
    private static final String EMPTY_TEXT = "没有提交记录";

    private final GithubApiClient client;
    private final ToolsProperties props;
    private final RepoRef repo;

    public GithubCommitsTool(GithubApiClient client, ToolsProperties props, RepoRef repo) {
        this.client = client;
        this.props = props;
        this.repo = repo;
    }

    @Tool("获取当前仓库最近的提交记录,了解近期开发动向。")
    public String recentCommits(@P("返回条数,可不填,默认与上限由服务端配置") Integer limit) {
        int perPage = effectiveLimit(limit);
        JsonNode array;
        try {
            array = client.getJson(
                    "/repos/" + repo.owner() + "/" + repo.name() + "/commits",
                    Map.of("per_page", perPage));
        } catch (GithubRateLimitException e) {
            log.warn("获取 commits 触发限流: repo={}/{}, msg={}",
                    repo.owner(), repo.name(), e.getMessage());
            return RATE_LIMIT_TEXT;
        } catch (GithubUnavailableException e) {
            log.warn("获取 commits 失败(GitHub 不可用): repo={}/{}, msg={}",
                    repo.owner(), repo.name(), e.getMessage());
            return UNAVAILABLE_TEXT;
        } catch (RepoNotFoundException e) {
            log.warn("获取 commits 失败(仓库不存在): repo={}/{}, msg={}",
                    repo.owner(), repo.name(), e.getMessage());
            return NOT_FOUND_TEXT;
        }
        return format(array);
    }

    /** 有效条数:limit 为 null 或 <1 取上限;否则 min(limit, 上限)。默认值即上限,只能调小。 */
    private int effectiveLimit(Integer limit) {
        int max = props.commitsMax();
        if (limit == null || limit < 1) {
            return max;
        }
        return Math.min(limit, max);
    }

    private String format(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return EMPTY_TEXT;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode node : array) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(formatLine(node));
        }
        return sb.length() == 0 ? EMPTY_TEXT : sb.toString();
    }

    /** 单行:{@code 短SHA 日期 作者: 提交消息首行}。 */
    private String formatLine(JsonNode node) {
        String sha = node.path("sha").asText();
        String shortSha = sha.length() >= SHORT_SHA_LEN ? sha.substring(0, SHORT_SHA_LEN) : sha;
        JsonNode author = node.path("commit").path("author");
        String date = datePart(author.path("date").asText());
        String name = author.path("name").asText();
        if (name.isEmpty()) {
            name = UNKNOWN_AUTHOR;
        }
        String firstLine = firstLine(node.path("commit").path("message").asText());
        return shortSha + " " + date + " " + name + ": " + firstLine;
    }

    /** 只取提交消息首行,兼容 CRLF。 */
    private String firstLine(String message) {
        if (message.isEmpty()) {
            return "";
        }
        int nl = message.indexOf('\n');
        String first = nl >= 0 ? message.substring(0, nl) : message;
        if (first.endsWith("\r")) {
            first = first.substring(0, first.length() - 1);
        }
        return first;
    }

    /** 取 ISO 时间串的日期部分(前 10 位 YYYY-MM-DD);不足 10 位则原样返回。 */
    private String datePart(String isoDateTime) {
        if (isoDateTime.length() < 10) {
            return isoDateTime;
        }
        return isoDateTime.substring(0, 10);
    }
}
