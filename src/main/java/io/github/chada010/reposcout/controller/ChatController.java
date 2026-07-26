package io.github.chada010.reposcout.controller;

import java.util.regex.Pattern;

import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.chada010.reposcout.config.ChatProperties;
import io.github.chada010.reposcout.controller.dto.ChatRequest;
import io.github.chada010.reposcout.controller.dto.ChatResponse;
import io.github.chada010.reposcout.exception.InvalidParamException;
import io.github.chada010.reposcout.service.ChatService;

/**
 * 对话接口。契约见 docs/api.md。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    /** 只接受标准 8-4-4-4-12 形式的 UUID,避免宽松解析放进异形键。 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ChatService chatService;
    private final ChatProperties chatProperties;

    public ChatController(ChatService chatService, ChatProperties chatProperties) {
        this.chatService = chatService;
        this.chatProperties = chatProperties;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        validate(request);
        ChatService.ChatResult result = chatService.chat(request.sessionId(), request.message());
        return new ChatResponse(result.sessionId(), result.answer());
    }

    private void validate(ChatRequest request) {
        int maxLength = chatProperties.messageMaxLength();
        if (request.message().length() > maxLength) {
            throw new InvalidParamException(
                    "message 超长:最多 " + maxLength + " 字符,当前 " + request.message().length() + " 字符");
        }
        if (StringUtils.hasText(request.sessionId())
                && !UUID_PATTERN.matcher(request.sessionId()).matches()) {
            throw new InvalidParamException("sessionId 必须是 UUID 格式,或留空由服务端生成");
        }
    }
}
