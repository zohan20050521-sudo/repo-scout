package io.github.chada010.reposcout.tools;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import io.github.chada010.reposcout.config.GithubProperties;
import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

/**
 * 手动验证:对真实公开仓库 spring-projects/spring-petclinic 各调一次工具并打印结果。
 * 匿名调用 GitHub(60 次/小时/IP),本类默认 {@link Disabled} 不进 CI;本地临时去掉
 * 注解执行一遍、把原始输出贴入 PR 后再恢复注解。不起 Spring,手工构造真实客户端。
 */
@Disabled("需真实网络访问 GitHub API,本地手动执行")
class GithubIssuesCommitsManualVerificationTest {

    private static final ToolsProperties PROPS = new ToolsProperties(3, 200, 8000, 20, 20);
    private static final RepoRef REPO = new RepoRef("spring-projects", "spring-petclinic", "main");

    /** 匿名客户端(token 空);RestClient.builder() 直接构造,不经 Spring。 */
    private GithubApiClient realClient() {
        return new GithubApiClient(
                RestClient.builder(),
                new GithubProperties("https://api.github.com", "", Duration.ofSeconds(10)));
    }

    @Test
    void printIssues() {
        GithubApiClient client = realClient();
        GithubIssuesTool tool = new GithubIssuesTool(client, PROPS, REPO);

        // 直接取一次原始返回,对比过滤前后条数,证明 pull_request 过滤生效
        JsonNode rawOpen = client.getJson(
                "/repos/spring-projects/spring-petclinic/issues",
                Map.of("state", "open", "per_page", PROPS.issuesMax()));
        System.out.println("原始 open 返回条数(含 PR)= " + rawOpen.size());

        System.out.println("=== issues(\"open\") ===");
        System.out.println(tool.issues("open"));
        System.out.println("=== issues(\"closed\") ===");
        System.out.println(tool.issues("closed"));
    }

    @Test
    void printCommits() {
        GithubCommitsTool tool = new GithubCommitsTool(realClient(), PROPS, REPO);

        System.out.println("=== recentCommits(5) ===");
        String five = tool.recentCommits(5);
        System.out.println(five);
        System.out.println("recentCommits(5) 行数 = " + five.lines().count());

        System.out.println("=== recentCommits(null) ===");
        String all = tool.recentCommits(null);
        System.out.println(all);
        System.out.println("recentCommits(null) 行数 = " + all.lines().count());
    }
}
