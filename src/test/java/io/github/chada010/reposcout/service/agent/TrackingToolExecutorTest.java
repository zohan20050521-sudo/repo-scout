package io.github.chada010.reposcout.service.agent;

import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TrackingToolExecutorTest {

    private static final String SESSION_ID = "0f14d0ab-9605-4a62-a9e4-5ed26688389b";

    @Mock
    private ToolExecutor delegate;

    private ToolExecutionRequest request() {
        return ToolExecutionRequest.builder().name("readme").arguments("{}").build();
    }

    @Test
    void withinLimitDelegatesAndReturnsRealResult() {
        given(delegate.execute(any(), any())).willReturn("真实结果");
        AtomicInteger counter = new AtomicInteger(0);
        TrackingToolExecutor executor = new TrackingToolExecutor(delegate, "readme", SESSION_ID, counter, 5);

        String result = executor.execute(request(), SESSION_ID);

        assertThat(result).isEqualTo("真实结果");
        verify(delegate).execute(any(), any());
    }

    @Test
    void beyondLimitReturnsReadableCapTextAndSkipsDelegate() {
        given(delegate.execute(any(), any())).willReturn("真实结果");
        AtomicInteger counter = new AtomicInteger(0);
        // 上限为 1:第 1 次真实执行,第 2 次触顶返回可读文本且不再调用底层工具
        TrackingToolExecutor executor = new TrackingToolExecutor(delegate, "readme", SESSION_ID, counter, 1);

        String first = executor.execute(request(), SESSION_ID);
        String second = executor.execute(request(), SESSION_ID);

        assertThat(first).isEqualTo("真实结果");
        assertThat(second).contains("工具调用轮数上限");
        // 底层工具只被调用一次(第二次被拦截),不死循环、不再消耗 GitHub 配额
        verify(delegate, times(1)).execute(any(), any());
    }

    @Test
    void sharedCounterCapsAcrossToolsInOneQuestion() {
        given(delegate.execute(any(), any())).willReturn("真实结果");
        AtomicInteger shared = new AtomicInteger(0);
        // 同一问答内四个工具共享计数器:上限 2,前两次任意工具真实执行,之后触顶
        TrackingToolExecutor toolA = new TrackingToolExecutor(delegate, "repoTree", SESSION_ID, shared, 2);
        TrackingToolExecutor toolB = new TrackingToolExecutor(delegate, "issues", SESSION_ID, shared, 2);

        assertThat(toolA.execute(request(), SESSION_ID)).isEqualTo("真实结果");
        assertThat(toolB.execute(request(), SESSION_ID)).isEqualTo("真实结果");
        assertThat(toolA.execute(request(), SESSION_ID)).contains("工具调用轮数上限");

        verify(delegate, times(2)).execute(any(), any());
    }
}
