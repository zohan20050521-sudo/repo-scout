package io.github.chada010.reposcout.tools;

import java.time.Duration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import io.github.chada010.reposcout.config.GithubProperties;
import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

/**
 * 手动验证:真实访问 GitHub API,对 spring-projects/spring-petclinic 依次调用两工具并打印结果。
 * 默认 @Disabled——CI 无外网,且匿名调用限流 60 次/小时/IP;本地手动去掉 @Disabled 跑一遍即可。
 * 不依赖 Spring:手工 new GithubApiClient(RestClient.builder(), ...) 构造真实客户端。
 * 若本机设置了 GITHUB_TOKEN 环境变量则带上以放宽限流,否则匿名调用。
 */
@Disabled("需真实网络访问 GitHub API,本地手动执行")
class GithubTreeReadmeManualVerificationTest {

    private static final ToolsProperties PROPS = new ToolsProperties(3, 200, 8000, 20, 20);

    private GithubApiClient realClient() {
        String token = System.getenv("GITHUB_TOKEN");
        GithubProperties props = new GithubProperties(
                "https://api.github.com", token == null ? "" : token, Duration.ofSeconds(10));
        return new GithubApiClient(RestClient.builder(), props);
    }

    @Test
    void printTreeAndReadme() {
        GithubApiClient client = realClient();
        RepoRef petclinic = new RepoRef("spring-projects", "spring-petclinic", "main");
        GithubTreeTool tree = new GithubTreeTool(client, PROPS, petclinic);
        GithubReadmeTool readme = new GithubReadmeTool(client, PROPS, petclinic);

        System.out.println("===== 1. repoTree(null) =====");
        System.out.println(tree.repoTree(null));

        System.out.println("===== 2. repoTree(1) =====");
        System.out.println(tree.repoTree(1));

        System.out.println("===== 3. readme() =====");
        System.out.println(readme.readme());

        RepoRef badBranch = new RepoRef("spring-projects", "spring-petclinic", "no-such-branch");
        System.out.println("===== 4. repoTree(null) on branch=no-such-branch =====");
        System.out.println(new GithubTreeTool(client, PROPS, badBranch).repoTree(null));
    }
}
