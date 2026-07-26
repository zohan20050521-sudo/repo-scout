package io.github.chada010.reposcout.tools;

import java.util.Locale;
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
 * issues 工具(FR-2.2):列出当前仓库的 issue,供 Agent 了解已知问题与需求动向。
 * 非单例 bean,按仓库实例化,构造注入访问出口、裁剪配置与仓库标识。
 * GitHub 该端点混含 PR,输出前过滤掉含 {@code pull_request} 字段的条目;
 * GitHub 异常统一降级为一行可读文本,不向上抛,保证工具失败不中断对话。
 */
public class GithubIssuesTool {

    private static final Logger log = LoggerFactory.getLogger(GithubIssuesTool.class);

    /** 合法的 state 取值;其余(含 null/空白)一律按 open 处理。 */
    private static final String DEFAULT_STATE = "open";
    private static final String STATE_CLOSED = "closed";
    private static final String STATE_ALL = "all";

    private static final String RATE_LIMIT_TEXT = "GitHub API 限流,请稍后重试";
    private static final String UNAVAILABLE_TEXT = "GitHub API 暂时不可用,请稍后重试";
    private static final String NOT_FOUND_TEXT = "未找到相关数据(仓库可能已变更)";
    private static final String EMPTY_TEXT = "无符合条件的 issue";

    private final GithubApiClient client;
    private final ToolsProperties props;
    private final RepoRef repo;

    public GithubIssuesTool(GithubApiClient client, ToolsProperties props, RepoRef repo) {
        this.client = client;
        this.props = props;
        this.repo = repo;
    }

    @Tool("获取当前仓库的 issue 列表,了解已知问题与需求动向。可按状态过滤。")
    public String issues(@P("issue 状态过滤:open、closed 或 all,可不填,默认 open") String state) {
        String normalized = normalizeState(state);
        JsonNode array;
        try {
            array = client.getJson(
                    "/repos/" + repo.owner() + "/" + repo.name() + "/issues",
                    Map.of("state", normalized, "per_page", props.issuesMax()));
        } catch (GithubRateLimitException e) {
            log.warn("获取 issues 触发限流: repo={}/{}, state={}, msg={}",
                    repo.owner(), repo.name(), normalized, e.getMessage());
            return RATE_LIMIT_TEXT;
        } catch (GithubUnavailableException e) {
            log.warn("获取 issues 失败(GitHub 不可用): repo={}/{}, state={}, msg={}",
                    repo.owner(), repo.name(), normalized, e.getMessage());
            return UNAVAILABLE_TEXT;
        } catch (RepoNotFoundException e) {
            log.warn("获取 issues 失败(仓库不存在): repo={}/{}, state={}, msg={}",
                    repo.owner(), repo.name(), normalized, e.getMessage());
            return NOT_FOUND_TEXT;
        }
        return format(array);
    }

    /** trim + 小写后须 ∈ open|closed|all,否则一律按 open。 */
    private String normalizeState(String state) {
        if (state == null) {
            return DEFAULT_STATE;
        }
        String s = state.trim().toLowerCase(Locale.ROOT);
        if (DEFAULT_STATE.equals(s) || STATE_CLOSED.equals(s) || STATE_ALL.equals(s)) {
            return s;
        }
        return DEFAULT_STATE;
    }

    /** 过滤 PR 条目后逐行格式化;全部被过滤或本就为空时返回空结果文案。 */
    private String format(JsonNode array) {
        if (array == null || !array.isArray()) {
            return EMPTY_TEXT;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode node : array) {
            // GitHub /issues 端点混含 PR,含 pull_request 字段的条目一律剔除
            if (node.has("pull_request")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(formatLine(node));
        }
        return sb.length() == 0 ? EMPTY_TEXT : sb.toString();
    }

    /** 单行:{@code #编号 [状态] 标题(更新于 日期;标签 a,b)},无标签则省略标签段。 */
    private String formatLine(JsonNode node) {
        StringBuilder line = new StringBuilder();
        line.append('#').append(node.path("number").asInt())
                .append(" [").append(node.path("state").asText()).append("] ")
                .append(node.path("title").asText())
                .append("(更新于 ").append(datePart(node.path("updated_at").asText()));
        String labels = joinLabels(node.path("labels"));
        if (!labels.isEmpty()) {
            line.append(";标签 ").append(labels);
        }
        return line.append(')').toString();
    }

    /** 标签名以逗号连接;跳过空名与非数组。 */
    private String joinLabels(JsonNode labels) {
        if (labels == null || !labels.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode label : labels) {
            String name = label.path("name").asText();
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /** 取 ISO 时间串的日期部分(前 10 位 YYYY-MM-DD);不足 10 位则原样返回。 */
    private String datePart(String isoDateTime) {
        if (isoDateTime.length() < 10) {
            return isoDateTime;
        }
        return isoDateTime.substring(0, 10);
    }
}
