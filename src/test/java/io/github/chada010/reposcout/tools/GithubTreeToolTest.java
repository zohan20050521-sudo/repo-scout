package io.github.chada010.reposcout.tools;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * GithubTreeTool 单测:Mockito mock GithubApiClient,不打真实 GitHub、不起 Spring 上下文。
 * 覆盖渲染/排序/缩进、深度剪枝、maxDepth 钳制、条目截断、truncated 注记、分支编码与错误降级。
 */
@ExtendWith(MockitoExtension.class)
class GithubTreeToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 顶层含文件与目录、故意乱序,用于验证按 path 字典序排序。 */
    private static final String MIXED_TREE = """
            {
              "truncated": false,
              "tree": [
                {"path": "src/main/App.java", "type": "blob"},
                {"path": "src", "type": "tree"},
                {"path": "README.md", "type": "blob"},
                {"path": "src/main", "type": "tree"},
                {"path": "pom.xml", "type": "blob"}
              ]
            }
            """;

    @Mock
    private GithubApiClient client;

    private static ToolsProperties props(int treeMaxDepth, int treeMaxEntries) {
        return new ToolsProperties(treeMaxDepth, treeMaxEntries, 8000, 20, 20);
    }

    private static RepoRef repo() {
        return new RepoRef("spring-projects", "spring-petclinic", "main");
    }

    private GithubTreeTool tool(ToolsProperties props, RepoRef repo) {
        return new GithubTreeTool(client, props, repo);
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rendersMixedTreeSortedByPathWithIndentAndSlash() {
        given(client.getJson(anyString(), anyMap())).willReturn(json(MIXED_TREE));

        // 字典序:'R'(82) < 'p'(112) < 's'(115),故 README.md 在 src 之前
        // ——与 design 示意模板的目录优先顺序不同,以任务书「按 path 字典序」为准。
        assertThat(tool(props(3, 200), repo()).repoTree(null)).isEqualTo("""
                README.md
                pom.xml
                src/
                  main/
                    App.java""");
    }

    @Test
    void keepsChildrenAttachedToParentWhenSiblingSortsBeforeSlash() {
        // 回归:'-'(45) < '/'(47),纯 path 字符串字典序会把 docker/ 的子项拆到
        // docker-compose.yml 之后;逐段比较保证子项紧跟父目录,不引入目录优先。
        String fixture = """
                {"tree": [
                  {"path": "docker-compose.yml", "type": "blob"},
                  {"path": "docker", "type": "tree"},
                  {"path": "docker/Dockerfile", "type": "blob"}
                ]}
                """;
        given(client.getJson(anyString(), anyMap())).willReturn(json(fixture));

        assertThat(tool(props(3, 200), repo()).repoTree(null)).isEqualTo("""
                docker/
                  Dockerfile
                docker-compose.yml""");
    }

    @Test
    void prunesEntriesDeeperThanEffectiveDepth() {
        given(client.getJson(anyString(), anyMap())).willReturn(json(MIXED_TREE));

        assertThat(tool(props(3, 200), repo()).repoTree(2)).isEqualTo("""
                README.md
                pom.xml
                src/
                  main/""");
    }

    @Test
    void maxDepthOneShowsTopLevelOnly() {
        given(client.getJson(anyString(), anyMap())).willReturn(json(MIXED_TREE));

        assertThat(tool(props(3, 200), repo()).repoTree(1)).isEqualTo("""
                README.md
                pom.xml
                src/""");
    }

    @Test
    void nullMaxDepthUsesConfiguredDefault() {
        given(client.getJson(anyString(), anyMap())).willReturn(json(MIXED_TREE));

        assertThat(tool(props(3, 200), repo()).repoTree(null)).contains("    App.java");
    }

    @Test
    void nonPositiveMaxDepthUsesConfiguredDefault() {
        given(client.getJson(anyString(), anyMap())).willReturn(json(MIXED_TREE));

        // maxDepth=0 视为未指定,取配置上限 3,能看到最深的 App.java
        assertThat(tool(props(3, 200), repo()).repoTree(0)).contains("    App.java");
    }

    @Test
    void maxDepthAboveUpperBoundIsClampedToDefault() {
        given(client.getJson(anyString(), anyMap())).willReturn(json(MIXED_TREE));

        // 上限即默认 3,传 10 只能钳到 3(默认值即上限,只能调小)
        assertThat(tool(props(3, 200), repo()).repoTree(10)).isEqualTo("""
                README.md
                pom.xml
                src/
                  main/
                    App.java""");
    }

    @Test
    void truncatesEntriesBeyondLimitWithFootnote() {
        String flat = """
                {"tree": [
                  {"path": "c.txt", "type": "blob"},
                  {"path": "a.txt", "type": "blob"},
                  {"path": "b.txt", "type": "blob"}
                ]}
                """;
        given(client.getJson(anyString(), anyMap())).willReturn(json(flat));

        // treeMaxEntries=2:排序后取前 2(a、b),尾注给出剪枝后总数与显示数
        assertThat(tool(props(3, 2), repo()).repoTree(null)).isEqualTo("""
                a.txt
                b.txt
                (已截断:共 3 项,显示前 2 项)""");
    }

    @Test
    void notesGithubTruncatedResponse() {
        String truncated = """
                {"truncated": true, "tree": [
                  {"path": "README.md", "type": "blob"}
                ]}
                """;
        given(client.getJson(anyString(), anyMap())).willReturn(json(truncated));

        assertThat(tool(props(3, 200), repo()).repoTree(null)).isEqualTo("""
                README.md
                (GitHub 返回结果不完整)""");
    }

    @Test
    void emptyTreeReturnsPlaceholder() {
        given(client.getJson(anyString(), anyMap())).willReturn(json("{\"tree\": []}"));

        assertThat(tool(props(3, 200), repo()).repoTree(null)).isEqualTo("(目录树为空)");
    }

    @Test
    void encodesBranchSegmentInRequestPath() {
        given(client.getJson(anyString(), anyMap())).willReturn(json("{\"tree\": []}"));
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, ?>> query = ArgumentCaptor.forClass(Map.class);

        tool(props(3, 200), new RepoRef("o", "n", "release/1.0")).repoTree(null);

        verify(client).getJson(path.capture(), query.capture());
        // 分支段中的 '/' 必须编码为 %2F,不能泄漏成路径分隔符
        assertThat(path.getValue()).isEqualTo("/repos/o/n/git/trees/release%2F1.0");
        assertThat(query.getValue().get("recursive")).isEqualTo("1");
    }

    @Test
    void rateLimitReturnsFriendlyText() {
        given(client.getJson(anyString(), anyMap())).willThrow(new GithubRateLimitException("rl"));

        assertThat(tool(props(3, 200), repo()).repoTree(null)).isEqualTo("GitHub API 限流,请稍后重试");
    }

    @Test
    void unavailableReturnsFriendlyText() {
        given(client.getJson(anyString(), anyMap())).willThrow(new GithubUnavailableException("down"));

        assertThat(tool(props(3, 200), repo()).repoTree(null)).isEqualTo("GitHub API 暂时不可用,请稍后重试");
    }

    @Test
    void repoNotFoundReturnsFriendlyText() {
        given(client.getJson(anyString(), anyMap())).willThrow(new RepoNotFoundException("nf"));

        assertThat(tool(props(3, 200), repo()).repoTree(null))
                .isEqualTo("未找到目录树(仓库或默认分支可能已变更)");
    }
}
