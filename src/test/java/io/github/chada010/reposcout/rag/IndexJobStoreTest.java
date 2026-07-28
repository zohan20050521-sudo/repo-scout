package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IndexJobStoreTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private IndexJobStore store;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(values);
        store = new IndexJobStore(redisTemplate, objectMapper);
    }

    @Test
    void firstAcquireUsesJobIdAndOneHourTtl() {
        given(values.setIfAbsent(eq("repo-scout:index:lock:7"), eq("job-7"),
                eq(IndexJobStore.LOCK_TTL))).willReturn(true);

        assertThat(store.tryAcquire(7L, "job-7")).isTrue();
        verify(values).setIfAbsent("repo-scout:index:lock:7", "job-7", IndexJobStore.LOCK_TTL);
    }

    @Test
    void stateRoundTripKeepsTimesAndNullableResult() {
        IndexJobState state = new IndexJobState("job-1", 1L, IndexJobStatus.SUCCEEDED,
                LocalDateTime.of(2026, 7, 28, 12, 0), LocalDateTime.of(2026, 7, 28, 12, 1),
                LocalDateTime.of(2026, 7, 28, 12, 2), 3, 12, 456L, null, null);
        store.save(state);

        // Capture the serialized value and feed it back to verify a complete round trip.
        org.mockito.ArgumentCaptor<String> captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("repo-scout:index:job:1"), captured.capture(), eq(IndexJobStore.STATE_TTL));
        given(values.get("repo-scout:index:job:1")).willReturn(captured.getValue());

        assertThat(store.find(1L)).contains(state);
    }

    @Test
    void releaseUsesCompareAndDeleteScriptSoOldJobCannotDeleteNewLock() {
        given(redisTemplate.execute(any(), eq(List.of("repo-scout:index:lock:3")), eq("old-job")))
                .willReturn(0L);

        assertThat(store.releaseIfOwner(3L, "old-job")).isFalse();
        verify(redisTemplate).execute(any(), eq(List.of("repo-scout:index:lock:3")), eq("old-job"));
    }

    @Test
    void malformedStateFailsWithSafeMessage() {
        given(values.get("repo-scout:index:job:9")).willReturn("not-json");

        assertThatThrownBy(() -> store.find(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("索引任务状态不可用");
    }

    @Test
    void nullOrWrongRepoStateCannotBeUsedForDeduplication() {
        given(values.get("repo-scout:index:job:9")).willReturn("null");

        assertThatThrownBy(() -> store.find(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("索引任务状态不可用");
    }
}
