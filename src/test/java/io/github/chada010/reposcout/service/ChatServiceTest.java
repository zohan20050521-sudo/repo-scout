package io.github.chada010.reposcout.service;

import java.util.UUID;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private Assistant assistant;

    private Result<String> resultOf(String answer) {
        return Result.<String>builder()
                .content(answer)
                .tokenUsage(new TokenUsage(10, 5))
                .build();
    }

    @Test
    void blankSessionIdGeneratesUuidAndReturnsIt() {
        given(assistant.chat(anyString(), eq("你好"))).willReturn(resultOf("回答"));
        ChatService service = new ChatService(assistant);

        ChatService.ChatResult result = service.chat(null, "你好");

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
        ChatService service = new ChatService(assistant);

        ChatService.ChatResult result = service.chat("  ", "你好");

        assertThat(UUID.fromString(result.sessionId())).isNotNull();
    }

    @Test
    void providedSessionIdIsPassedThroughUnchanged() {
        String sessionId = "0f14d0ab-9605-4a62-a9e4-5ed26688389b";
        given(assistant.chat(eq(sessionId), eq("继续"))).willReturn(resultOf("好的"));
        ChatService service = new ChatService(assistant);

        ChatService.ChatResult result = service.chat(sessionId, "继续");

        assertThat(result.sessionId()).isEqualTo(sessionId);
        verify(assistant).chat(eq(sessionId), eq("继续"));
    }
}
