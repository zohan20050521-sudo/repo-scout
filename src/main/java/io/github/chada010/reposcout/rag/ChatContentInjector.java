package io.github.chada010.reposcout.rag;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import org.springframework.stereotype.Component;

/**
 * 对话链路的注入器(FR-3.2):把检索命中的文档摘录追加到原用户消息之后。
 * contents 为空(未绑定/未索引/无命中)时<b>原样返回原消息,不做任何改写</b>,
 * 保证 v0.1/v0.2 行为零回归;非空时按相关度降序列出摘录并标注来源文件路径,
 * 提示模型引用时注明出处(与系统提示词 D3 要素呼应)。
 */
@Component
public class ChatContentInjector implements ContentInjector {

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        if (contents == null || contents.isEmpty()) {
            return chatMessage;
        }
        if (!(chatMessage instanceof UserMessage userMessage) || !userMessage.hasSingleText()) {
            // 防御:非单段文本用户消息(当前链路不会出现)不改写,宁可不注入也不破坏消息结构
            return chatMessage;
        }
        String augmented = augment(userMessage.singleText(), contents);
        return userMessage.name() == null
                ? UserMessage.from(augmented)
                : UserMessage.from(userMessage.name(), augmented);
    }

    private String augment(String originalText, List<Content> contents) {
        StringBuilder sb = new StringBuilder(originalText);
        sb.append("\n\n---\n以下是从该仓库文档中检索到的相关摘录(按相关度降序,可能与问题无关,无关请忽略):");
        for (int i = 0; i < contents.size(); i++) {
            TextSegmentView view = TextSegmentView.of(contents.get(i));
            sb.append("\n【摘录 ").append(i + 1).append(" | 来源: ").append(view.filePath()).append("】\n")
                    .append(view.text());
        }
        sb.append("\n回答时若引用了某条摘录,请在答案中注明其来源文件路径。");
        return sb.toString();
    }

    /** 摘录展示要素:来源路径缺失时兜底为可读占位,不让 null 进提示词。 */
    private record TextSegmentView(String filePath, String text) {

        static TextSegmentView of(Content content) {
            String filePath = content.textSegment().metadata().getString(ChatContentRetriever.FILE_PATH_KEY);
            return new TextSegmentView(filePath == null ? "未知" : filePath, content.textSegment().text());
        }
    }
}
