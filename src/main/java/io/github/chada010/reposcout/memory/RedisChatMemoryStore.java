package io.github.chada010.reposcout.memory;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.github.chada010.reposcout.config.ChatProperties;

/**
 * 基于 Redis 的会话记忆存储:每个会话一个 STRING 键,值为 LangChain4j
 * 序列化的消息列表 JSON。sessionId 已在入口校验为 UUID,键空间有界;
 * 每次写入刷新 TTL,会话过期自动清理。
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    /** 项目 Redis 键前缀规范:repo-scout:{模块}:{业务}:{id} */
    static final String KEY_PREFIX = "repo-scout:chat:memory:";

    private final StringRedisTemplate redisTemplate;
    private final ChatProperties chatProperties;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate, ChatProperties chatProperties) {
        this.redisTemplate = redisTemplate;
        this.chatProperties = chatProperties;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(key(memoryId));
        if (json == null) {
            return List.of();
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        redisTemplate.opsForValue().set(key(memoryId), json, chatProperties.memory().ttl());
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
