package io.github.chada010.reposcout.rag;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.memory.SessionRepoBinding;
import io.github.chada010.reposcout.repository.RepoRepository;

/**
 * 对话链路的检索器(FR-3.2):挂在 {@code DefaultRetrievalAugmentor} 上,按会话绑定
 * 状态决定是否检索——从 query metadata 取 chatMemoryId(即 sessionId)查绑定,
 * 未绑定直接返回空且<b>不触碰 RepoRetriever/EmbeddingModel</b>(v0.1/v0.2 行为零回归);
 * 已绑定则委托 {@link RepoRetriever},命中块的 {@code file_path} 进 TextSegment metadata,
 * 供注入器标注来源与 {@code Result.sources()} 提取引用。
 */
@Component
public class ChatContentRetriever implements ContentRetriever {

    /** 命中块来源文件路径的 metadata 键,注入器与 sources 提取共用。 */
    public static final String FILE_PATH_KEY = "file_path";
    public static final String CHUNK_INDEX_KEY = "chunk_index";
    public static final String SCORE_KEY = "score";
    public static final String SOURCE_URL_KEY = "source_url";

    private static final Logger log = LoggerFactory.getLogger(ChatContentRetriever.class);

    private final SessionRepoBinding sessionRepoBinding;
    private final RepoRetriever repoRetriever;
    private final RepoRepository repoRepository;

    public ChatContentRetriever(SessionRepoBinding sessionRepoBinding, RepoRetriever repoRetriever,
                                RepoRepository repoRepository) {
        this.sessionRepoBinding = sessionRepoBinding;
        this.repoRetriever = repoRetriever;
        this.repoRepository = repoRepository;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query.metadata() == null || query.metadata().chatMemoryId() == null) {
            return List.of();
        }
        String sessionId = String.valueOf(query.metadata().chatMemoryId());
        Optional<Long> repoId = sessionRepoBinding.get(sessionId);
        if (repoId.isEmpty()) {
            return List.of();
        }
        Repo repo = repoRepository.findById(repoId.get()).orElse(null);
        if (repo == null) {
            log.warn("会话绑定仓库记录不存在,跳过检索: repoId={}", repoId.get());
            return List.of();
        }
        return repoRetriever.retrieve(repoId.get(), query.text()).stream()
                .map(hit -> toContent(hit, repo))
                .toList();
    }

    private static Content toContent(ScoredChunk hit, Repo repo) {
        Metadata metadata = new Metadata()
                .put(FILE_PATH_KEY, hit.chunk().getFilePath())
                .put(CHUNK_INDEX_KEY, hit.chunk().getChunkIndex())
                .put(SCORE_KEY, hit.score())
                .put(SOURCE_URL_KEY, sourceUrl(repo, hit.chunk().getFilePath()));
        return Content.from(TextSegment.from(hit.chunk().getContent(), metadata));
    }

    private static String sourceUrl(Repo repo, String filePath) {
        return "https://github.com/" + encodeSegment(repo.getOwner()) + "/"
                + encodeSegment(repo.getName()) + "/blob/" + encodeSegment(repo.getDefaultBranch())
                + "/" + Arrays.stream(filePath.split("/", -1))
                        .map(ChatContentRetriever::encodeSegment)
                        .collect(Collectors.joining("/"));
    }

    private static String encodeSegment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
