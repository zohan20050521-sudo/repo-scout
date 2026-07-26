package io.github.chada010.reposcout.memory;

import java.time.Duration;
import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.github.chada010.reposcout.config.ChatProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisChatMemoryStoreTest {

    private static final String SESSION_ID = "0f14d0ab-9605-4a62-a9e4-5ed26688389b";
    private static final String EXPECTED_KEY = "repo-scout:chat:memory:" + SESSION_ID;
    private static final Duration TTL = Duration.ofHours(24);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisChatMemoryStore store;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties(4000, new ChatProperties.Memory(20, TTL));
        store = new RedisChatMemoryStore(redisTemplate, properties);
    }

    @Test
    void updateMessagesWritesToPrefixedKeyWithConfiguredTtl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        store.updateMessages(SESSION_ID, List.of(UserMessage.from("你好")));

        verify(valueOperations).set(eq(EXPECTED_KEY), anyString(), eq(TTL));
    }

    @Test
    void everyWriteRefreshesTtl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        store.updateMessages(SESSION_ID, List.of(UserMessage.from("第一轮")));
        store.updateMessages(SESSION_ID, List.of(UserMessage.from("第一轮"), AiMessage.from("回答")));

        verify(valueOperations, times(2)).set(eq(EXPECTED_KEY), anyString(), eq(TTL));
    }

    @Test
    void messagesSurviveSerializationRoundTrip() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        List<ChatMessage> messages = List.of(
                UserMessage.from("项目怎么跑起来?"),
                AiMessage.from("使用 mvn spring-boot:run。"));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        store.updateMessages(SESSION_ID, messages);
        verify(valueOperations).set(eq(EXPECTED_KEY), jsonCaptor.capture(), eq(TTL));

        given(valueOperations.get(EXPECTED_KEY)).willReturn(jsonCaptor.getValue());
        assertThat(store.getMessages(SESSION_ID)).isEqualTo(messages);
    }

    @Test
    void getMessagesReturnsEmptyListWhenKeyAbsent() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(EXPECTED_KEY)).willReturn(null);

        assertThat(store.getMessages(SESSION_ID)).isEmpty();
    }

    @Test
    void deleteMessagesRemovesPrefixedKey() {
        store.deleteMessages(SESSION_ID);

        verify(redisTemplate).delete(EXPECTED_KEY);
    }
}
