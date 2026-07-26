package io.github.chada010.reposcout.controller;

import dev.langchain4j.exception.LangChain4jException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.chada010.reposcout.config.ChatProperties;
import io.github.chada010.reposcout.service.ChatService;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@EnableConfigurationProperties(ChatProperties.class)
class ChatControllerTest {

    private static final String SESSION_ID = "0f14d0ab-9605-4a62-a9e4-5ed26688389b";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void blankMessageReturns400WithInvalidParam() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"))
                .andExpect(jsonPath("$.message").value(containsString("message")));
    }

    @Test
    void missingMessageReturns400WithInvalidParam() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void tooLongMessageReturns400WithInvalidParam() throws Exception {
        String tooLong = "字".repeat(4001);
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"))
                .andExpect(jsonPath("$.message").value(containsString("4000")));
    }

    @Test
    void malformedSessionIdReturns400WithInvalidParam() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"not-a-uuid\", \"message\": \"你好\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"))
                .andExpect(jsonPath("$.message").value(containsString("UUID")));
    }

    @Test
    void validRequestReturns200WithSessionIdAndAnswer() throws Exception {
        given(chatService.chat(isNull(), anyString(), isNull()))
                .willReturn(new ChatService.ChatResult(SESSION_ID, "你好,我是 repo-scout。"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.answer").value("你好,我是 repo-scout。"));
    }

    @Test
    void repoIdIsPassedThroughToService() throws Exception {
        given(chatService.chat(eq(SESSION_ID), anyString(), eq(7L)))
                .willReturn(new ChatService.ChatResult(SESSION_ID, "已绑定回答"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"" + SESSION_ID + "\", \"message\": \"这个项目怎么跑\", \"repoId\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("已绑定回答"));
        verify(chatService).chat(eq(SESSION_ID), anyString(), eq(7L));
    }

    @Test
    void nonNumericRepoIdReturns400WithInvalidParam() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"你好\", \"repoId\": \"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        verifyNoInteractions(chatService);
    }

    @Test
    void llmFailureReturns502WithUnifiedErrorAndNoStackTrace() throws Exception {
        given(chatService.chat(any(), anyString(), any()))
                .willThrow(new LangChain4jException("upstream timeout"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"" + SESSION_ID + "\", \"message\": \"你好\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("at "))));
    }

    @Test
    void unexpectedFailureReturns500WithUnifiedError() throws Exception {
        given(chatService.chat(any(), anyString(), any()))
                .willThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"你好\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("boom"))));
    }
}
