package io.github.chada010.reposcout.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;
import io.github.chada010.reposcout.rag.RepoRetriever;
import io.github.chada010.reposcout.rag.ScoredChunk;
import io.github.chada010.reposcout.repository.DocChunkRepository;
import io.github.chada010.reposcout.repository.RepoRepository;
import io.github.chada010.reposcout.tools.GithubCommitsTool;
import io.github.chada010.reposcout.tools.GithubIssuesTool;
import io.github.chada010.reposcout.tools.GithubReadmeTool;
import io.github.chada010.reposcout.tools.GithubTreeTool;

/**
 * 仓库导读报告生成(FR-3.3):确定性取数 + 单次 LLM 调用,不经过 Assistant/会话/记忆
 * ——报告是一次性任务,不需要记忆;服务端确定性取数(不让模型规划)成本可控、可测试。
 * 取数复用四个 GitHub 工具(内置裁剪与失败降级文本,GitHub 故障不产生 502)与
 * {@link RepoRetriever} 固定查询集摘录;输出经五节结构校验,不合规追加纠正指令重试一次,
 * 仍不合规照常返回并记 WARN。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 摘录区的固定查询集:覆盖定位、上手与架构三类导读信息,服务端固定,不经请求参数。 */
    private static final List<String> EXCERPT_QUERIES = List.of(
            "这个项目是做什么的",
            "如何安装、配置和运行这个项目",
            "项目的架构与核心模块");

    static final String NOT_INDEXED_TEXT = "(该仓库尚未建立文档索引)";
    static final String NO_HIT_TEXT = "(未检索到相关文档摘录)";

    private final RepoRepository repoRepository;
    private final DocChunkRepository docChunkRepository;
    private final GithubApiClient githubApiClient;
    private final ToolsProperties toolsProperties;
    private final ChatModel chatModel;
    private final RepoRetriever repoRetriever;

    public ReportService(RepoRepository repoRepository, DocChunkRepository docChunkRepository,
                         GithubApiClient githubApiClient, ToolsProperties toolsProperties,
                         ChatModel chatModel, RepoRetriever repoRetriever) {
        this.repoRepository = repoRepository;
        this.docChunkRepository = docChunkRepository;
        this.githubApiClient = githubApiClient;
        this.toolsProperties = toolsProperties;
        this.chatModel = chatModel;
        this.repoRetriever = repoRetriever;
    }

    /**
     * 生成导读报告。仓库须已接入,否则抛 {@link RepoNotFoundException}(端点映射 404);
     * LLM 失败由 LangChain4jException 经既有 handler 映射 502。
     */
    public ReportResult generate(long repoId) {
        long start = System.currentTimeMillis();
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new RepoNotFoundException("仓库未接入或不存在:id=" + repoId));
        RepoRef ref = new RepoRef(repo.getOwner(), repo.getName(), repo.getDefaultBranch());

        // 确定性取数:四个工具按固定参数各调一次(null/缺省即配置默认值),摘录用固定查询集
        String tree = new GithubTreeTool(githubApiClient, toolsProperties, ref).repoTree(null);
        String readme = new GithubReadmeTool(githubApiClient, toolsProperties, ref).readme();
        String issues = new GithubIssuesTool(githubApiClient, toolsProperties, ref).issues("open");
        String commits = new GithubCommitsTool(githubApiClient, toolsProperties, ref).recentCommits(null);
        String excerpts = buildExcerpts(repoId);

        String prompt = ReportPrompts.buildPrompt(repo, tree, readme, issues, commits, excerpts);
        ChatResponse first = chatModel.chat(List.of(UserMessage.from(prompt)));
        String report = first.aiMessage().text();
        TokenUsage usage = first.tokenUsage();
        if (!ReportPrompts.isStructureValid(report)) {
            log.info("报告结构校验未通过,追加纠正指令重试一次: repoId={}", repoId);
            ChatResponse second = chatModel.chat(List.of(
                    UserMessage.from(prompt), first.aiMessage(),
                    UserMessage.from(ReportPrompts.CORRECTION_INSTRUCTION)));
            report = second.aiMessage().text();
            usage = addUsage(usage, second.tokenUsage());
            if (!ReportPrompts.isStructureValid(report)) {
                log.warn("报告结构校验重试后仍未通过,照常返回: repoId={}", repoId);
            }
        }

        long costMs = System.currentTimeMillis() - start;
        log.info("报告生成完成: repoId={}, costMs={}, inputTokens={}, outputTokens={}, totalTokens={}",
                repoId, costMs,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                usage == null ? null : usage.totalTokenCount());
        return new ReportResult(report, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS), costMs);
    }

    /**
     * 摘录区:未索引直接标注(不触碰检索与 Embedding 模型,D6 降级);已索引按固定查询集
     * 各检索一次,结果按 (filePath, chunkIndex) 去重合并,保持首次出现顺序。
     */
    private String buildExcerpts(long repoId) {
        if (!docChunkRepository.existsByRepoId(repoId)) {
            return NOT_INDEXED_TEXT;
        }
        Map<String, ScoredChunk> merged = new LinkedHashMap<>();
        for (String query : EXCERPT_QUERIES) {
            for (ScoredChunk hit : repoRetriever.retrieve(repoId, query)) {
                merged.putIfAbsent(hit.chunk().getFilePath() + "#" + hit.chunk().getChunkIndex(), hit);
            }
        }
        if (merged.isEmpty()) {
            return NO_HIT_TEXT;
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ScoredChunk hit : merged.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("【摘录 ").append(i++).append(" | 来源: ").append(hit.chunk().getFilePath())
                    .append("】\n").append(hit.chunk().getContent());
        }
        return sb.toString();
    }

    private static TokenUsage addUsage(TokenUsage first, TokenUsage second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first.add(second);
    }

    /** 报告结果:Markdown 全文、生成时间(截断到秒)与总耗时。 */
    public record ReportResult(String report, LocalDateTime generatedAt, long costMs) {
    }
}
