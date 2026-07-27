package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.entity.DocChunk;
import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.memory.SessionRepoBinding;
import io.github.chada010.reposcout.repository.RepoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ChatContentRetriever 单测:未绑定会话返回空且不触碰 RepoRetriever(检索零成本);
 * 已绑定时委托检索,并把命中块的 file_path 放进 Content 的 TextSegment metadata。
 */
@ExtendWith(MockitoExtension.class)
class ChatContentRetrieverTest {

    private static final String SESSION_ID = "0f14d0ab-9605-4a62-a9e4-5ed26688389b";

    @Mock
    private SessionRepoBinding sessionRepoBinding;
    @Mock
    private RepoRetriever repoRetriever;
    @Mock
    private RepoRepository repoRepository;

    private ChatContentRetriever retriever() {
        return new ChatContentRetriever(sessionRepoBinding, repoRetriever, repoRepository);
    }

    private static Query query(String text) {
        return Query.from(text, Metadata.from(UserMessage.from(text), SESSION_ID, List.of()));
    }

    @Test
    void unboundSessionReturnsEmptyWithoutTouchingRepoRetriever() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.empty());

        List<Content> result = retriever().retrieve(query("这个项目怎么跑?"));

        assertThat(result).isEmpty();
        verifyNoInteractions(repoRetriever);
    }

    @Test
    void boundSessionMapsHitsToContentWithFilePathMetadata() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.of(5L));
        Repo repo = new Repo("owner name", "项目#1", "feature/中文 分支", null,
                "https://github.com/owner name/项目#1", LocalDateTime.now(), LocalDateTime.now());
        given(repoRepository.findById(5L)).willReturn(Optional.of(repo));
        DocChunk chunk = new DocChunk(5L, "docs/入门 #1.md", 2, "错误码表内容",
                "[1.0]", LocalDateTime.of(2026, 7, 26, 12, 0, 0));
        given(repoRetriever.retrieve(5L, "统一错误码有哪些?"))
                .willReturn(List.of(new ScoredChunk(chunk, 0.87)));

        List<Content> result = retriever().retrieve(query("统一错误码有哪些?"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).textSegment().text()).isEqualTo("错误码表内容");
        assertThat(result.get(0).textSegment().metadata().getString(ChatContentRetriever.FILE_PATH_KEY))
                .isEqualTo("docs/入门 #1.md");
        assertThat(result.get(0).textSegment().metadata().getInteger(ChatContentRetriever.CHUNK_INDEX_KEY))
                .isEqualTo(2);
        assertThat(result.get(0).textSegment().metadata().getDouble(ChatContentRetriever.SCORE_KEY))
                .isEqualTo(0.87);
        assertThat(result.get(0).textSegment().metadata().getString(ChatContentRetriever.SOURCE_URL_KEY))
                .isEqualTo("https://github.com/owner%20name/%E9%A1%B9%E7%9B%AE%231/blob/"
                        + "feature%2F%E4%B8%AD%E6%96%87%20%E5%88%86%E6%94%AF/docs/"
                        + "%E5%85%A5%E9%97%A8%20%231.md");
        verify(repoRetriever).retrieve(5L, "统一错误码有哪些?");
    }

    @Test
    void missingRepoRecordReturnsEmptyWithoutTouchingRepoRetriever() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.of(5L));
        given(repoRepository.findById(5L)).willReturn(Optional.empty());

        List<Content> result = retriever().retrieve(query("问题"));

        assertThat(result).isEmpty();
        verifyNoInteractions(repoRetriever);
    }
}
