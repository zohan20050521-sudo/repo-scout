package io.github.chada010.reposcout.memory;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.github.chada010.reposcout.config.ChatProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SessionRepoBindingTest {

    private static final String SESSION_ID = "0f14d0ab-9605-4a62-a9e4-5ed26688389b";
    private static final String EXPECTED_KEY = "repo-scout:chat:repo:" + SESSION_ID;
    private static final Duration TTL = Duration.ofHours(24);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SessionRepoBinding binding;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties(4000, new ChatProperties.Memory(20, TTL));
        binding = new SessionRepoBinding(redisTemplate, properties);
    }

    @Test
    void bindWritesRepoIdToPrefixedKeyWithConfiguredTtl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        binding.bind(SESSION_ID, 42L);

        verify(valueOperations).set(eq(EXPECTED_KEY), eq("42"), eq(TTL));
    }

    @Test
    void refreshTtlExpiresPrefixedKeyWithConfiguredTtl() {
        binding.refreshTtl(SESSION_ID);

        verify(redisTemplate).expire(EXPECTED_KEY, TTL);
    }

    @Test
    void getReturnsBoundRepoId() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(EXPECTED_KEY)).willReturn("42");

        assertThat(binding.get(SESSION_ID)).contains(42L);
    }

    @Test
    void getReturnsEmptyWhenKeyAbsent() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(EXPECTED_KEY)).willReturn(null);

        assertThat(binding.get(SESSION_ID)).isEmpty();
    }

    @Test
    void getTreatsNonNumericValueAsUnbound() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(EXPECTED_KEY)).willReturn("not-a-number");

        assertThat(binding.get(SESSION_ID)).isEmpty();
    }
}
