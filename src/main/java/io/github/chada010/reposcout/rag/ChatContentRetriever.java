package io.github.chada010.reposcout.rag;

import java.util.List;
import java.util.Optional;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Component;

import io.github.chada010.reposcout.memory.SessionRepoBinding;

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

    private final SessionRepoBinding sessionRepoBinding;
    private final RepoRetriever repoRetriever;

    public ChatContentRetriever(SessionRepoBinding sessionRepoBinding, RepoRetriever repoRetriever) {
        this.sessionRepoBinding = sessionRepoBinding;
        this.repoRetriever = repoRetriever;
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
        return repoRetriever.retrieve(repoId.get(), query.text()).stream()
                .map(ChatContentRetriever::toContent)
                .toList();
    }

    private static Content toContent(ScoredChunk hit) {
        TextSegment segment = TextSegment.from(hit.chunk().getContent(),
                Metadata.from(FILE_PATH_KEY, hit.chunk().getFilePath()));
        return Content.from(segment);
    }
}
