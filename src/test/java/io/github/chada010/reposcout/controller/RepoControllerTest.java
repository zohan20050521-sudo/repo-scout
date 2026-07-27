package io.github.chada010.reposcout.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.InvalidParamException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.rag.IndexResult;
import io.github.chada010.reposcout.rag.IndexStatusService;
import io.github.chada010.reposcout.rag.IndexingService;
import io.github.chada010.reposcout.service.RepoService;
import io.github.chada010.reposcout.service.ReportService;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RepoController.class)
class RepoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepoService repoService;

    @MockitoBean
    private IndexingService indexingService;

    @MockitoBean
    private IndexStatusService indexStatusService;

    @MockitoBean
    private ReportService reportService;

    private Repo repo(long id, String owner, String name) {
        Repo repo = new Repo(owner, name, "master", "My first repo",
                "https://github.com/" + owner + "/" + name,
                LocalDateTime.of(2026, 7, 26, 12, 0, 0),
                LocalDateTime.of(2026, 7, 26, 12, 0, 0));
        ReflectionTestUtils.setField(repo, "id", id);
        return repo;
    }

    @Test
    void missingRepoFieldReturns400WithInvalidParam() throws Exception {
        mockMvc.perform(post("/api/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void blankRepoFieldReturns400WithInvalidParam() throws Exception {
        mockMvc.perform(post("/api/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void invalidRepoFormatReturns400WithReadableMessage() throws Exception {
        given(repoService.onboard(anyString()))
                .willThrow(new InvalidParamException("repo 格式不合法:仅支持 owner/repo 或 https://github.com/owner/repo"));

        mockMvc.perform(post("/api/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\": \"https://gitlab.com/a/b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"))
                .andExpect(jsonPath("$.message").value(containsString("格式不合法")));
    }

    @Test
    void onboardSuccessReturnsRepoJson() throws Exception {
        given(repoService.onboard("octocat/Hello-World")).willReturn(repo(1L, "octocat", "Hello-World"));

        mockMvc.perform(post("/api/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\": \"octocat/Hello-World\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.owner").value("octocat"))
                .andExpect(jsonPath("$.name").value("Hello-World"))
                .andExpect(jsonPath("$.defaultBranch").value("master"))
                .andExpect(jsonPath("$.htmlUrl").value("https://github.com/octocat/Hello-World"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void githubRepoMissingReturns404WithRepoNotFound() throws Exception {
        given(repoService.onboard(anyString()))
                .willThrow(new RepoNotFoundException("GitHub 查无此公开仓库:octocat/nope(私有仓库暂不支持)"));

        mockMvc.perform(post("/api/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\": \"octocat/nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPO_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("私有仓库暂不支持")));
    }

    @Test
    void githubRateLimitReturns502WithExplicitMessage() throws Exception {
        given(repoService.onboard(anyString()))
                .willThrow(new GithubRateLimitException("GitHub API 限流,请稍后重试"));

        mockMvc.perform(post("/api/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\": \"octocat/Hello-World\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(containsString("限流")));
    }

    @Test
    void githubDownReturns502WithoutInternalDetails() throws Exception {
        given(repoService.onboard(anyString()))
                .willThrow(new GithubUnavailableException("GitHub 服务暂时不可用,请稍后重试"));

        mockMvc.perform(post("/api/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\": \"octocat/Hello-World\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void listReturnsArrayOfRepos() throws Exception {
        given(repoService.listRepos()).willReturn(List.of(repo(2L, "b", "b-repo"), repo(1L, "a", "a-repo")));

        mockMvc.perform(get("/api/repos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[1].id").value(1));
    }

    @Test
    void getByIdReturnsRepoJson() throws Exception {
        given(repoService.getRepo(1L)).willReturn(repo(1L, "octocat", "Hello-World"));

        mockMvc.perform(get("/api/repos/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.owner").value("octocat"));
    }

    @Test
    void getByMissingIdReturns404WithRepoNotFound() throws Exception {
        given(repoService.getRepo(999999L))
                .willThrow(new RepoNotFoundException("仓库未接入或不存在:id=999999"));

        mockMvc.perform(get("/api/repos/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPO_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("仓库未接入或不存在")));
    }

    @Test
    void getByNonNumericIdReturns400NotInternalError() throws Exception {
        mockMvc.perform(get("/api/repos/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"))
                .andExpect(jsonPath("$.message").value(containsString("id")));
    }

    @Test
    void indexStatusSuccessReturnsResourceJson() throws Exception {
        given(indexStatusService.getStatus(1L)).willReturn(new IndexStatusService.IndexStatus(
                1L, true, 4, 63, LocalDateTime.of(2026, 7, 27, 12, 0)));

        mockMvc.perform(get("/api/repos/1/index-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value(1))
                .andExpect(jsonPath("$.indexed").value(true))
                .andExpect(jsonPath("$.fileCount").value(4))
                .andExpect(jsonPath("$.chunkCount").value(63))
                .andExpect(jsonPath("$.indexedAt").value("2026-07-27T12:00:00"));
    }

    @Test
    void indexStatusOnMissingRepoReturns404() throws Exception {
        given(indexStatusService.getStatus(9L))
                .willThrow(new RepoNotFoundException("仓库未接入或不存在:id=9"));

        mockMvc.perform(get("/api/repos/9/index-status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPO_NOT_FOUND"));
    }

    @Test
    void indexStatusWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/api/repos/abc/index-status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void indexSuccessReturnsCounts() throws Exception {
        given(indexingService.index(1L)).willReturn(new IndexResult(3, 12, 456L));

        mockMvc.perform(post("/api/repos/1/index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value(1))
                .andExpect(jsonPath("$.fileCount").value(3))
                .andExpect(jsonPath("$.chunkCount").value(12))
                .andExpect(jsonPath("$.costMs").value(456));
    }

    @Test
    void indexOnMissingRepoReturns404WithRepoNotFound() throws Exception {
        given(indexingService.index(999999L))
                .willThrow(new RepoNotFoundException("仓库未接入或不存在:id=999999"));

        mockMvc.perform(post("/api/repos/999999/index"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPO_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("仓库未接入或不存在")));
    }

    @Test
    void indexWhenGithubUnavailableReturns502() throws Exception {
        given(indexingService.index(1L))
                .willThrow(new GithubUnavailableException("GitHub 服务暂时不可用,请稍后重试"));

        mockMvc.perform(post("/api/repos/1/index"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }

    @Test
    void reportSuccessReturnsRepoIdGeneratedAtCostMsAndReport() throws Exception {
        given(reportService.generate(1L)).willReturn(new ReportService.ReportResult(
                "## 项目定位\n导读 Agent。\n## 技术栈\nSpring Boot。\n## 目录结构解读\n标准布局。\n"
                        + "## 上手指引\n见 README。\n## 近期动向\n开发中。",
                LocalDateTime.of(2026, 7, 26, 12, 0, 0), 8000L));

        mockMvc.perform(post("/api/repos/1/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value(1))
                .andExpect(jsonPath("$.generatedAt").value("2026-07-26T12:00:00"))
                .andExpect(jsonPath("$.costMs").value(8000))
                .andExpect(jsonPath("$.report").value(containsString("## 项目定位")));
    }

    @Test
    void reportOnMissingRepoReturns404WithRepoNotFound() throws Exception {
        given(reportService.generate(999999L))
                .willThrow(new RepoNotFoundException("仓库未接入或不存在:id=999999"));

        mockMvc.perform(post("/api/repos/999999/report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPO_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("仓库未接入或不存在")));
    }

    @Test
    void reportWithNonNumericIdReturns400NotInternalError() throws Exception {
        mockMvc.perform(post("/api/repos/abc/report"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"))
                .andExpect(jsonPath("$.message").value(containsString("id")));
    }
}
