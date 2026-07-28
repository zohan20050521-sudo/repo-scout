package io.github.chada010.reposcout.rag;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis 中的索引任务状态与 repo 级互斥锁。 */
@Component
public class IndexJobStore {

    static final String STATE_KEY_PREFIX = "repo-scout:index:job:";
    static final String LOCK_KEY_PREFIX = "repo-scout:index:lock:";
    static final Duration STATE_TTL = Duration.ofHours(24);
    static final Duration LOCK_TTL = Duration.ofHours(1);

    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IndexJobStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<IndexJobState> find(long repoId) {
        String json = redisTemplate.opsForValue().get(stateKey(repoId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            IndexJobState state = objectMapper.readValue(json, IndexJobState.class);
            if (state == null || state.jobId() == null || state.status() == null
                    || state.repoId() != repoId) {
                throw new IllegalStateException("索引任务状态不可用");
            }
            return Optional.of(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("索引任务状态不可用", e);
        }
    }

    public void save(IndexJobState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(stateKey(state.repoId()), json, STATE_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("索引任务状态不可用", e);
        }
    }

    /** 以 jobId 作为锁值，只有首次请求才能取得该仓库锁。 */
    public boolean tryAcquire(long repoId, String jobId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey(repoId), jobId, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public String lockOwner(long repoId) {
        return redisTemplate.opsForValue().get(lockKey(repoId));
    }

    /** Lua compare-and-delete，避免旧任务释放新任务的锁。 */
    public boolean releaseIfOwner(long repoId, String jobId) {
        Long deleted = redisTemplate.execute(RELEASE_IF_OWNER,
                List.of(lockKey(repoId)), jobId);
        return Long.valueOf(1L).equals(deleted);
    }

    String stateKey(long repoId) {
        return STATE_KEY_PREFIX + repoId;
    }

    String lockKey(long repoId) {
        return LOCK_KEY_PREFIX + repoId;
    }
}
