package io.github.chada010.reposcout.service;

import java.util.Optional;
import java.util.UUID;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.github.chada010.reposcout.exception.InvalidParamException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.memory.SessionRepoBinding;
import io.github.chada010.reposcout.repository.RepoRepository;

/**
 * 对话服务:负责会话 ID 生成、仓库绑定编排与模型调用,并记录 token 用量与耗时。
 * 绑定关系存 Redis({@link SessionRepoBinding}),工具是否挂载由 AiServices 的 ToolProvider
 * 按绑定关系决定,本服务只负责绑定的三态校验与 TTL 刷新。
 * 注意:用户消息全文只允许出现在 DEBUG 日志,INFO 级不落消息内容。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final Assistant assistant;
    private final SessionRepoBinding sessionRepoBinding;
    private final RepoRepository repoRepository;

    public ChatService(Assistant assistant, SessionRepoBinding sessionRepoBinding,
                       RepoRepository repoRepository) {
        this.assistant = assistant;
        this.sessionRepoBinding = sessionRepoBinding;
        this.repoRepository = repoRepository;
    }

    public ChatResult chat(String sessionId, String message, Long repoId) {
        String effectiveSessionId = StringUtils.hasText(sessionId)
                ? sessionId
                : UUID.randomUUID().toString();
        log.debug("chat request: sessionId={}, message={}, repoId={}", effectiveSessionId, message, repoId);

        resolveBinding(effectiveSessionId, repoId);

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

    /**
     * 绑定三态(FR-2.3):
     * <ul>
     *   <li>未绑定 + 携带 repoId → 校验该仓库已接入(否则 404),绑定;</li>
     *   <li>已绑定 + 携带不同 repoId → 冲突 400,提示新开会话切换;</li>
     *   <li>携带相同 repoId 或不携带 → 沿用原绑定。</li>
     * </ul>
     * 只要本会话已绑定(含本次新绑),即刷新绑定 TTL,与会话记忆保持同步过期。
     */
    private void resolveBinding(String sessionId, Long repoId) {
        Optional<Long> bound = sessionRepoBinding.get(sessionId);
        if (repoId != null) {
            if (bound.isEmpty()) {
                ensureRepoOnboarded(repoId);
                sessionRepoBinding.bind(sessionId, repoId);
                log.info("session bound to repo: sessionId={}, repoId={}", sessionId, repoId);
                bound = Optional.of(repoId);
            } else if (!bound.get().equals(repoId)) {
                throw new InvalidParamException(
                        "会话已绑定仓库 " + bound.get() + ",如需切换请新开会话");
            }
        }
        if (bound.isPresent()) {
            sessionRepoBinding.refreshTtl(sessionId);
        }
    }

    private void ensureRepoOnboarded(long repoId) {
        if (!repoRepository.existsById(repoId)) {
            throw new RepoNotFoundException("仓库未接入或不存在:id=" + repoId);
        }
    }

    /** 对话结果:会话 ID(可能为服务端新生成)与模型回答。 */
    public record ChatResult(String sessionId, String answer) {
    }
}
