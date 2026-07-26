package io.github.chada010.reposcout.service;

import java.util.UUID;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 对话服务:负责会话 ID 生成与模型调用编排,并记录 token 用量与耗时。
 * 注意:用户消息全文只允许出现在 DEBUG 日志,INFO 级不落消息内容。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final Assistant assistant;

    public ChatService(Assistant assistant) {
        this.assistant = assistant;
    }

    public ChatResult chat(String sessionId, String message) {
        String effectiveSessionId = StringUtils.hasText(sessionId)
                ? sessionId
                : UUID.randomUUID().toString();
        log.debug("chat request: sessionId={}, message={}", effectiveSessionId, message);

        long start = System.currentTimeMillis();
        Result<String> result = assistant.chat(effectiveSessionId, message);
        long costMs = System.currentTimeMillis() - start;

        TokenUsage usage = result.tokenUsage();
        log.info("chat done: sessionId={}, inputTokens={}, outputTokens={}, totalTokens={}, costMs={}",
                effectiveSessionId,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                usage == null ? null : usage.totalTokenCount(),
                costMs);
        return new ChatResult(effectiveSessionId, result.content());
    }

    /** 对话结果:会话 ID(可能为服务端新生成)与模型回答。 */
    public record ChatResult(String sessionId, String answer) {
    }
}
