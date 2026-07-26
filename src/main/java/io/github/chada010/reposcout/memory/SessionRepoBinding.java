package io.github.chada010.reposcout.memory;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.github.chada010.reposcout.config.ChatProperties;

/**
 * 会话与仓库的绑定关系存储(FR-2.3):每个会话一个 STRING 键,值为已接入仓库的
 * repoId 字符串。键名 {@code repo-scout:chat:repo:{sessionId}}(项目键前缀规范
 * {@code repo-scout:{模块}:{业务}:{id}}),与会话记忆同 TTL(复用
 * {@link ChatProperties.Memory#ttl()}),每轮对话刷新,过期后需重新携带 repoId 绑定。
 *
 * <p>独立于 {@link RedisChatMemoryStore}:记忆存消息列表,绑定存 repoId,职责分离。
 * sessionId 已在入口校验为 UUID,键空间有界。
 */
@Component
public class SessionRepoBinding {

    /** 项目 Redis 键前缀规范:repo-scout:{模块}:{业务}:{id} */
    static final String KEY_PREFIX = "repo-scout:chat:repo:";

    private final StringRedisTemplate redisTemplate;
    private final ChatProperties chatProperties;

    public SessionRepoBinding(StringRedisTemplate redisTemplate, ChatProperties chatProperties) {
        this.redisTemplate = redisTemplate;
        this.chatProperties = chatProperties;
    }

    /** 读取会话当前绑定的 repoId;未绑定返回空。值异常(非数字)按未绑定处理,不抛。 */
    public Optional<Long> get(String sessionId) {
        String value = redisTemplate.opsForValue().get(key(sessionId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 绑定会话到指定仓库,写入值并设置 TTL(与记忆同 TTL)。 */
    public void bind(String sessionId, long repoId) {
        redisTemplate.opsForValue().set(key(sessionId), Long.toString(repoId), chatProperties.memory().ttl());
    }

    /** 刷新绑定 TTL(与本轮记忆写入同步);键不存在时 Redis 忽略,无副作用。 */
    public void refreshTtl(String sessionId) {
        redisTemplate.expire(key(sessionId), chatProperties.memory().ttl());
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
