package io.github.chada010.reposcout.rag;

import java.util.List;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatContentInjector 单测:空 contents 必须返回同一原消息实例(零改写,
 * v0.1/v0.2 行为零回归);非空时输出含原消息、来源路径与块内容,并保持摘录顺序。
 */
class ChatContentInjectorTest {

    private final ChatContentInjector injector = new ChatContentInjector();

    private static Content content(String filePath, String text) {
        return Content.from(TextSegment.from(text,
                Metadata.from(ChatContentRetriever.FILE_PATH_KEY, filePath)));
    }

    @Test
    void emptyContentsReturnsSameMessageInstanceUntouched() {
        UserMessage original = UserMessage.from("这个项目怎么跑?");

        assertThat(injector.inject(List.of(), original)).isSameAs(original);
        assertThat(injector.inject(null, original)).isSameAs(original);
    }

    @Test
    void nonEmptyContentsAppendExcerptsAfterOriginalMessage() {
        UserMessage original = UserMessage.from("统一错误码有哪些?");

        ChatMessage result = injector.inject(List.of(
                content("docs/api.md", "错误码表:INVALID_PARAM …"),
                content("README.md", "环境变量说明")), original);

        assertThat(result).isInstanceOf(UserMessage.class);
        String text = ((UserMessage) result).singleText();
        assertThat(text).startsWith("统一错误码有哪些?");
        assertThat(text).contains("【摘录 1 | 来源: docs/api.md】");
        assertThat(text).contains("错误码表:INVALID_PARAM …");
        assertThat(text).contains("【摘录 2 | 来源: README.md】");
        assertThat(text).contains("环境变量说明");
        assertThat(text).contains("回答时若引用了某条摘录,请在答案中注明其来源文件路径。");
        // 摘录按传入顺序(检索得分降序)排列
        assertThat(text.indexOf("docs/api.md")).isLessThan(text.indexOf("README.md"));
    }

    @Test
    void missingFilePathMetadataFallsBackToPlaceholder() {
        UserMessage original = UserMessage.from("问题");

        ChatMessage result = injector.inject(List.of(Content.from("无来源块")), original);

        assertThat(((UserMessage) result).singleText()).contains("来源: 未知");
    }
}
