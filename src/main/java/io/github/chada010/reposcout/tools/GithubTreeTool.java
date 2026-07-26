package io.github.chada010.reposcout.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriUtils;

import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

/**
 * 目录树工具:按仓库默认分支取递归目录树,渲染为面向模型的缩进树形文本。
 * 非单例 bean,编排期按仓库实例化(构造注入 GithubApiClient / ToolsProperties / RepoRef)。
 * GitHub 访问失败降级为一行可读文本,不向上抛,保证工具失败不中断对话;详情记 WARN 日志。
 */
public class GithubTreeTool {

    private static final Logger log = LoggerFactory.getLogger(GithubTreeTool.class);

    private static final String RATE_LIMIT_TEXT = "GitHub API 限流,请稍后重试";
    private static final String UNAVAILABLE_TEXT = "GitHub API 暂时不可用,请稍后重试";
    private static final String NOT_FOUND_TEXT = "未找到目录树(仓库或默认分支可能已变更)";
    private static final String EMPTY_TEXT = "(目录树为空)";

    private final GithubApiClient client;
    private final ToolsProperties props;
    private final RepoRef repo;

    public GithubTreeTool(GithubApiClient client, ToolsProperties props, RepoRef repo) {
        this.client = client;
        this.props = props;
        this.repo = repo;
    }

    @Tool("获取当前仓库的目录树,了解项目结构与代码组织。可指定最大深度。")
    public String repoTree(@P("目录树最大深度,可不填,默认与上限由服务端配置") Integer maxDepth) {
        int depth = effectiveDepth(maxDepth);
        try {
            JsonNode root = client.getJson(treePath(), Map.of("recursive", "1"));
            return render(root, depth);
        } catch (GithubRateLimitException e) {
            // 限流是 GithubUnavailableException 的子类,必须先于父类捕获
            log.warn("目录树获取限流: repo={}/{}, branch={}", repo.owner(), repo.name(), repo.defaultBranch());
            return RATE_LIMIT_TEXT;
        } catch (GithubUnavailableException e) {
            log.warn("目录树获取失败(GitHub 不可用): repo={}/{}, branch={}, error={}",
                    repo.owner(), repo.name(), repo.defaultBranch(), e.getMessage());
            return UNAVAILABLE_TEXT;
        } catch (RepoNotFoundException e) {
            log.warn("目录树未找到: repo={}/{}, branch={}", repo.owner(), repo.name(), repo.defaultBranch());
            return NOT_FOUND_TEXT;
        }
    }

    /** 有效深度:maxDepth 为 null 或 <1 时取配置上限;否则不超过上限(默认值即上限,只能调小)。 */
    private int effectiveDepth(Integer maxDepth) {
        int max = props.treeMaxDepth();
        if (maxDepth == null || maxDepth < 1) {
            return max;
        }
        return Math.min(maxDepth, max);
    }

    /**
     * 分支段必须 URL 编码:path 走 GithubApiClient 的 URI 模板通道,
     * 分支名中的 '/' 等特殊字符若不编码会被当作路径分隔符,破坏请求。
     */
    private String treePath() {
        String branch = UriUtils.encodePathSegment(repo.defaultBranch(), StandardCharsets.UTF_8);
        return "/repos/" + repo.owner() + "/" + repo.name() + "/git/trees/" + branch;
    }

    private String render(JsonNode root, int depth) {
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode node : root.path("tree")) {
            if (segmentCount(node.path("path").asText()) <= depth) {
                entries.add(node);
            }
        }
        if (entries.isEmpty()) {
            return EMPTY_TEXT;
        }
        // 按路径逐段字典序排序:同层兄弟按段名字典序,子项始终紧跟父目录,输出稳定。
        // 不能用纯 path 字符串字典序——'-'(45) < '/'(47),会把 docker/ 的子项拆到
        // docker-compose.yml 之后,子项视觉上挂错父目录、误导模型。
        entries.sort(Comparator.comparing(node -> node.path("path").asText(),
                GithubTreeTool::compareBySegments));

        int total = entries.size();
        int limit = props.treeMaxEntries();
        int shown = Math.min(total, limit);

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            lines.add(formatLine(entries.get(i)));
        }
        if (total > limit) {
            lines.add("(已截断:共 " + total + " 项,显示前 " + limit + " 项)");
        }
        if (root.path("truncated").asBoolean(false)) {
            lines.add("(GitHub 返回结果不完整)");
        }
        return String.join("\n", lines);
    }

    /** 渲染一行:缩进 =(层级-1)×2 空格,只打当前段名,目录(type=tree)加 '/' 后缀。 */
    private String formatLine(JsonNode node) {
        String path = node.path("path").asText();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        String line = "  ".repeat(segmentCount(path) - 1) + name;
        return "tree".equals(node.path("type").asText()) ? line + "/" : line;
    }

    private int segmentCount(String path) {
        if (path.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    /** 逐段比较路径:同层按段名字典序;前缀(祖先)路径排在其子孙之前,保证子项紧跟父目录。 */
    private static int compareBySegments(String a, String b) {
        String[] as = a.split("/");
        String[] bs = b.split("/");
        int n = Math.min(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int cmp = as[i].compareTo(bs[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(as.length, bs.length);
    }
}
