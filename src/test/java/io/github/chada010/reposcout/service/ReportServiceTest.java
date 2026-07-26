package io.github.chada010.reposcout.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.entity.DocChunk;
import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.rag.RepoRetriever;
import io.github.chada010.reposcout.rag.ScoredChunk;
import io.github.chada010.reposcout.repository.DocChunkRepository;
import io.github.chada010.reposcout.repository.RepoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ReportService 单测:mock GithubApiClient(fixture 用 ObjectMapper 构造,参照 tools 测试)
 * + mock ChatModel + mock 检索。验证确定性取数进提示词、五节校验通过时 LLM 只调 1 次、
 * 缺节重试一次(共 2 次)后照常返回、未接入 404、未索引时提示词带标注。
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolsProperties PROPS = new ToolsProperties(3, 200, 8000, 20, 20);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 12, 0, 0);

    private static final String VALID_REPORT = """
            ## 项目定位
            GitHub 仓库导读 Agent。
            ## 技术栈
            Spring Boot 3 + LangChain4j。
            ## 目录结构解读
            标准 Maven 布局。
            ## 上手指引
            见 README.md。
            ## 近期动向
            正在开发 v0.3。""";

    private static final String INVALID_REPORT = "## 项目定位\n只有一节,缺其余四节。";

    @Mock
    private RepoRepository repoRepository;
    @Mock
    private DocChunkRepository docChunkRepository;
    @Mock
    private GithubApiClient githubApiClient;
    @Mock
    private ChatModel chatModel;
    @Mock
    private RepoRetriever repoRetriever;

    private ReportService service() {
        return new ReportService(repoRepository, docChunkRepository, githubApiClient,
                PROPS, chatModel, repoRetriever);
    }

    private static Repo repo() {
        return new Repo("octocat", "Hello-World", "main", "示例仓库",
                "https://github.com/octocat/Hello-World", NOW, NOW);
    }

    private static ChatResponse llmResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .tokenUsage(new TokenUsage(100, 50))
                .build();
    }

    /** GitHub 取数 fixture:目录树/issues/commits 走 getJson,README 走 getRaw。 */
    private void stubGithubData() throws Exception {
        given(githubApiClient.getJson(anyString(), anyMap())).willAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (path.contains("/git/trees/")) {
                return MAPPER.readTree("""
                        {"tree":[{"path":"src","type":"tree"},{"path":"pom.xml","type":"blob"}]}""");
            }
            if (path.endsWith("/issues")) {
                return MAPPER.readTree("""
                        [{"number":24,"state":"open","title":"RAG 检索接入","updated_at":"2026-07-25T00:00:00Z","labels":[]}]""");
            }
            return MAPPER.readTree("""
                    [{"sha":"1949d03abcdef","commit":{"author":{"name":"chada010","date":"2026-07-25T00:00:00Z"},"message":"feat: 向量化入库管道"}}]""");
        });
        given(githubApiClient.getRaw(anyString(), anyString())).willReturn("# Hello-World\n如何运行……");
    }

    private String firstUserMessageOfCall(List<List<ChatMessage>> allCalls, int callIndex) {
        return ((UserMessage) allCalls.get(callIndex).get(0)).singleText();
    }

    @Test
    void validReportGeneratedWithSingleLlmCallAndDeterministicData() throws Exception {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(docChunkRepository.existsByRepoId(1L)).willReturn(true);
        given(repoRetriever.retrieve(anyLong(), anyString())).willReturn(List.of(
                new ScoredChunk(new DocChunk(1L, "docs/api.md", 0, "错误码表内容", "[1.0]", NOW), 0.9)));
        stubGithubData();
        given(chatModel.chat(anyList())).willReturn(llmResponse(VALID_REPORT));

        ReportService.ReportResult result = service().generate(1L);

        assertThat(result.report()).isEqualTo(VALID_REPORT);
        assertThat(result.generatedAt().getNano()).isZero();
        assertThat(result.costMs()).isNotNegative();

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, times(1)).chat(captor.capture());
        String prompt = firstUserMessageOfCall(captor.getAllValues(), 0);
        // 四块工具数据 + 摘录都进了提示词
        assertThat(prompt).contains("pom.xml");
        assertThat(prompt).contains("# Hello-World");
        assertThat(prompt).contains("RAG 检索接入");
        assertThat(prompt).contains("feat: 向量化入库管道");
        assertThat(prompt).contains("docs/api.md").contains("错误码表内容");
    }

    @Test
    void excerptsAreDedupedByFilePathAndChunkIndexAcrossQueries() throws Exception {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(docChunkRepository.existsByRepoId(1L)).willReturn(true);
        // 三个固定查询命中同一个块:提示词中只出现一次
        DocChunk same = new DocChunk(1L, "docs/api.md", 0, "重复块内容", "[1.0]", NOW);
        given(repoRetriever.retrieve(anyLong(), anyString()))
                .willReturn(List.of(new ScoredChunk(same, 0.8)));
        stubGithubData();
        given(chatModel.chat(anyList())).willReturn(llmResponse(VALID_REPORT));

        service().generate(1L);

        verify(repoRetriever, times(3)).retrieve(anyLong(), anyString());
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        String prompt = firstUserMessageOfCall(captor.getAllValues(), 0);
        assertThat(prompt.split("重复块内容", -1)).hasSize(2); // 只出现 1 次
    }

    @Test
    void invalidStructureRetriesOnceWithCorrectionAndReturnsSecondAnswer() throws Exception {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(docChunkRepository.existsByRepoId(1L)).willReturn(false);
        stubGithubData();
        given(chatModel.chat(anyList()))
                .willReturn(llmResponse(INVALID_REPORT))
                .willReturn(llmResponse(VALID_REPORT));

        ReportService.ReportResult result = service().generate(1L);

        assertThat(result.report()).isEqualTo(VALID_REPORT);
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, times(2)).chat(captor.capture());
        // 重试消息:原提示 + 上次输出 + 纠正指令
        List<ChatMessage> retryMessages = captor.getAllValues().get(1);
        assertThat(retryMessages).hasSize(3);
        assertThat(((AiMessage) retryMessages.get(1)).text()).isEqualTo(INVALID_REPORT);
        assertThat(((UserMessage) retryMessages.get(2)).singleText()).contains("不符合结构要求");
    }

    @Test
    void stillInvalidAfterRetryReturnsAsIsWithoutThirdCall() throws Exception {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(docChunkRepository.existsByRepoId(1L)).willReturn(false);
        stubGithubData();
        given(chatModel.chat(anyList())).willReturn(llmResponse(INVALID_REPORT));

        ReportService.ReportResult result = service().generate(1L);

        assertThat(result.report()).isEqualTo(INVALID_REPORT);
        verify(chatModel, times(2)).chat(anyList());
    }

    @Test
    void missingRepoThrowsRepoNotFoundAndSkipsEverything() {
        given(repoRepository.findById(999999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().generate(999999L))
                .isInstanceOf(RepoNotFoundException.class)
                .hasMessage("仓库未接入或不存在:id=999999");

        verifyNoInteractions(githubApiClient, chatModel, repoRetriever);
    }

    @Test
    void unindexedRepoPromptContainsMarkerAndSkipsRetrieval() throws Exception {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(docChunkRepository.existsByRepoId(1L)).willReturn(false);
        stubGithubData();
        given(chatModel.chat(anyList())).willReturn(llmResponse(VALID_REPORT));

        service().generate(1L);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        assertThat(firstUserMessageOfCall(captor.getAllValues(), 0))
                .contains("(该仓库尚未建立文档索引)");
        verifyNoInteractions(repoRetriever);
    }
}
