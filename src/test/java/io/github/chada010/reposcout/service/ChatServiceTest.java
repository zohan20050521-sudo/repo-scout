package io.github.chada010.reposcout.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.exception.InvalidParamException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.memory.SessionRepoBinding;
import io.github.chada010.reposcout.repository.RepoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String SESSION_ID = "0f14d0ab-9605-4a62-a9e4-5ed26688389b";

    @Mock
    private Assistant assistant;

    @Mock
    private SessionRepoBinding sessionRepoBinding;

    @Mock
    private RepoRepository repoRepository;

    private ChatService service() {
        return new ChatService(assistant, sessionRepoBinding, repoRepository);
    }

    private Result<String> resultOf(String answer) {
        return Result.<String>builder()
                .content(answer)
                .tokenUsage(new TokenUsage(10, 5))
                .build();
    }

    private static Content sourceContent(String filePath) {
        return Content.from(TextSegment.from("块内容", Metadata.from("file_path", filePath)));
    }

    @Test
    void blankSessionIdGeneratesUuidAndReturnsIt() {
        given(assistant.chat(anyString(), eq("你好"))).willReturn(resultOf("回答"));

        ChatService.ChatResult result = service().chat(null, "你好", null);

        // 返回的 sessionId 必须是合法 UUID,且与传给 assistant 的一致
        assertThat(UUID.fromString(result.sessionId())).isNotNull();
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(assistant).chat(sessionCaptor.capture(), eq("你好"));
        assertThat(sessionCaptor.getValue()).isEqualTo(result.sessionId());
        assertThat(result.answer()).isEqualTo("回答");
    }

    @Test
    void emptySessionIdAlsoGeneratesUuid() {
        given(assistant.chat(anyString(), eq("你好"))).willReturn(resultOf("回答"));

        ChatService.ChatResult result = service().chat("  ", "你好", null);

        assertThat(UUID.fromString(result.sessionId())).isNotNull();
    }

    @Test
    void providedSessionIdIsPassedThroughUnchanged() {
        given(assistant.chat(eq(SESSION_ID), eq("继续"))).willReturn(resultOf("好的"));

        ChatService.ChatResult result = service().chat(SESSION_ID, "继续", null);

        assertThat(result.sessionId()).isEqualTo(SESSION_ID);
        verify(assistant).chat(eq(SESSION_ID), eq("继续"));
    }

    @Test
    void unboundSessionWithoutRepoIdNeverBindsOrRefreshes() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.empty());
        given(assistant.chat(eq(SESSION_ID), anyString())).willReturn(resultOf("纯对话"));

        service().chat(SESSION_ID, "闲聊", null);

        verify(sessionRepoBinding, never()).bind(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(sessionRepoBinding, never()).refreshTtl(anyString());
    }

    @Test
    void firstRepoIdBindsAfterExistenceCheckAndRefreshesTtl() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.empty());
        given(repoRepository.existsById(5L)).willReturn(true);
        given(assistant.chat(eq(SESSION_ID), anyString())).willReturn(resultOf("已绑定"));

        service().chat(SESSION_ID, "这个项目怎么跑", 5L);

        verify(repoRepository).existsById(5L);
        verify(sessionRepoBinding).bind(SESSION_ID, 5L);
        verify(sessionRepoBinding).refreshTtl(SESSION_ID);
    }

    @Test
    void bindingNonOnboardedRepoThrowsRepoNotFound() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.empty());
        given(repoRepository.existsById(999999L)).willReturn(false);

        assertThatThrownBy(() -> service().chat(SESSION_ID, "你好", 999999L))
                .isInstanceOf(RepoNotFoundException.class);

        verify(sessionRepoBinding, never()).bind(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(assistant, never()).chat(anyString(), anyString());
    }

    @Test
    void differentRepoIdOnBoundSessionConflictsWith400() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.of(3L));

        assertThatThrownBy(() -> service().chat(SESSION_ID, "你好", 9L))
                .isInstanceOf(InvalidParamException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("新开会话");

        verify(assistant, never()).chat(anyString(), anyString());
    }

    @Test
    void sameRepoIdReusesBindingAndRefreshesTtl() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.of(5L));
        given(assistant.chat(eq(SESSION_ID), anyString())).willReturn(resultOf("沿用"));

        service().chat(SESSION_ID, "继续问", 5L);

        verify(sessionRepoBinding, never()).bind(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(repoRepository, never()).existsById(org.mockito.ArgumentMatchers.anyLong());
        verify(sessionRepoBinding).refreshTtl(SESSION_ID);
    }

    @Test
    void sourcesAreExtractedDedupedAndKeptInFirstSeenOrder() {
        given(assistant.chat(eq(SESSION_ID), anyString())).willReturn(Result.<String>builder()
                .content("回答")
                .tokenUsage(new TokenUsage(10, 5))
                .sources(List.of(
                        sourceContent("docs/api.md"),
                        sourceContent("README.md"),
                        sourceContent("docs/api.md")))
                .build());

        ChatService.ChatResult result = service().chat(SESSION_ID, "错误码有哪些", null);

        // 去重且保持检索得分降序的首次出现顺序
        assertThat(result.sources()).containsExactly("docs/api.md", "README.md");
    }

    @Test
    void nullSourcesYieldEmptyListNeverNull() {
        given(assistant.chat(eq(SESSION_ID), anyString()))
                .willReturn(new Result<>("回答", new TokenUsage(10, 5), null, null, null));

        ChatService.ChatResult result = service().chat(SESSION_ID, "你好", null);

        assertThat(result.sources()).isNotNull().isEmpty();
    }

    @Test
    void sourceContentWithoutFilePathMetadataIsSkipped() {
        given(assistant.chat(eq(SESSION_ID), anyString())).willReturn(Result.<String>builder()
                .content("回答")
                .tokenUsage(new TokenUsage(10, 5))
                .sources(List.of(Content.from("无 metadata 的块")))
                .build());

        ChatService.ChatResult result = service().chat(SESSION_ID, "你好", null);

        assertThat(result.sources()).isEmpty();
    }

    @Test
    void boundSessionWithoutRepoIdReusesBindingAndRefreshesTtl() {
        given(sessionRepoBinding.get(SESSION_ID)).willReturn(Optional.of(5L));
        given(assistant.chat(eq(SESSION_ID), anyString())).willReturn(resultOf("沿用"));

        service().chat(SESSION_ID, "刚才那个入口类在哪", null);

        verify(sessionRepoBinding).refreshTtl(SESSION_ID);
        verify(sessionRepoBinding, never()).bind(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
