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
import io.github.chada010.reposcout.memory.SessionRepoBinding;

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

    private ChatContentRetriever retriever() {
        return new ChatContentRetriever(sessionRepoBinding, repoRetriever);
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
        DocChunk chunk = new DocChunk(5L, "docs/api.md", 2, "错误码表内容",
                "[1.0]", LocalDateTime.of(2026, 7, 26, 12, 0, 0));
        given(repoRetriever.retrieve(5L, "统一错误码有哪些?"))
                .willReturn(List.of(new ScoredChunk(chunk, 0.87)));

        List<Content> result = retriever().retrieve(query("统一错误码有哪些?"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).textSegment().text()).isEqualTo("错误码表内容");
        assertThat(result.get(0).textSegment().metadata().getString(ChatContentRetriever.FILE_PATH_KEY))
                .isEqualTo("docs/api.md");
        verify(repoRetriever).retrieve(5L, "统一错误码有哪些?");
    }
}
