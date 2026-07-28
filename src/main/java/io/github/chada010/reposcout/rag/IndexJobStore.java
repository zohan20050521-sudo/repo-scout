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
    static final String ACTIVE_KEY_PREFIX = "repo-scout:index:active:";
    static final Duration STATE_TTL = Duration.ofHours(24);
    static final Duration LOCK_TTL = Duration.ofHours(1);
    /** active marker 覆盖排队与运行时长，避免 lock TTL 到期后重复提交。 */
    static final Duration ACTIVE_TTL = STATE_TTL;

    private static final DefaultRedisScript<Long> CREATE_IF_AVAILABLE = new DefaultRedisScript<>(
            "local active = redis.call('get', KEYS[3]) "
                    + "if active then return 0 end "
                    + "local state = redis.call('get', KEYS[2]) "
                    + "if state and (string.find(state, '\"status\"%s*:%s*\"QUEUED\"') "
                    + "or string.find(state, '\"status\"%s*:%s*\"RUNNING\"')) then return 0 end "
                    + "if redis.call('exists', KEYS[1]) == 1 then return -1 end "
                    + "redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[3]) "
                    + "redis.call('set', KEYS[2], ARGV[2], 'PX', ARGV[4]) "
                    + "redis.call('set', KEYS[3], ARGV[1], 'PX', ARGV[5]) "
                    + "return 1", Long.class);

    private static final DefaultRedisScript<Long> SAVE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[2]) ~= ARGV[1] then return 0 end "
                    + "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) "
                    + "if ARGV[4] == '1' then "
                    + "redis.call('del', KEYS[2]) "
                    + "else "
                    + "redis.call('set', KEYS[2], ARGV[1], 'PX', ARGV[4]) "
                    + "end "
                    + "return 1", Long.class);

    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "local deleted = 0 "
                    + "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('del', KEYS[1]); deleted = 1 end "
                    + "if redis.call('get', KEYS[2]) == ARGV[1] then "
                    + "redis.call('del', KEYS[2]); deleted = 1 end "
                    + "return deleted", Long.class);

    private static final DefaultRedisScript<Long> RELEASE_ORPHAN_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "and redis.call('exists', KEYS[2]) == 0 then "
                    + "redis.call('del', KEYS[1]) "
                    + "if redis.call('get', KEYS[3]) == ARGV[1] then redis.call('del', KEYS[3]) end "
                    + "return 1 else return 0 end", Long.class);

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

    /** 仅用于非 worker 的兼容性读写；worker 必须使用 {@link #saveIfOwner(IndexJobState)}。 */
    void save(IndexJobState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(stateKey(state.repoId()), json, STATE_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("索引任务状态不可用", e);
        }
    }

    /** 原子创建 lock、QUEUED state 与 active owner marker。 */
    public CreateResult createIfAvailable(IndexJobState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            Long result = redisTemplate.execute(CREATE_IF_AVAILABLE,
                    List.of(lockKey(state.repoId()), stateKey(state.repoId()),
                            activeKey(state.repoId())),
                    state.jobId(), json, Long.toString(LOCK_TTL.toMillis()),
                    Long.toString(STATE_TTL.toMillis()), Long.toString(ACTIVE_TTL.toMillis()));
            if (result == null) {
                throw new IllegalStateException("索引任务状态不可用");
            }
            return switch (result.intValue()) {
                case 1 -> CreateResult.CREATED;
                case 0 -> CreateResult.ACTIVE;
                default -> CreateResult.LOCKED;
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("索引任务状态不可用", e);
        }
    }

    /** 只允许 active owner 更新状态；终态写入同时删除 active marker。 */
    public boolean saveIfOwner(IndexJobState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            Long result = redisTemplate.execute(SAVE_IF_OWNER,
                    List.of(stateKey(state.repoId()), activeKey(state.repoId())),
                    state.jobId(), json, Long.toString(STATE_TTL.toMillis()),
                    state.active() ? Long.toString(ACTIVE_TTL.toMillis()) : "1");
            return Long.valueOf(1L).equals(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("索引任务状态不可用", e);
        }
    }

    public String lockOwner(long repoId) {
        return redisTemplate.opsForValue().get(lockKey(repoId));
    }

    public String activeOwner(long repoId) {
        return redisTemplate.opsForValue().get(activeKey(repoId));
    }

    /** Lua compare-and-delete，避免旧任务释放新任务的 lock/active marker。 */
    public boolean releaseIfOwner(long repoId, String jobId) {
        Long deleted = redisTemplate.execute(RELEASE_IF_OWNER,
                List.of(lockKey(repoId), activeKey(repoId)), jobId);
        return Long.valueOf(1L).equals(deleted);
    }

    /** 清理旧版本留下的 lock-without-state，不影响其他 owner。 */
    public boolean releaseOrphanIfOwner(long repoId, String jobId) {
        Long deleted = redisTemplate.execute(RELEASE_ORPHAN_IF_OWNER,
                List.of(lockKey(repoId), stateKey(repoId), activeKey(repoId)), jobId);
        return Long.valueOf(1L).equals(deleted);
    }

    String stateKey(long repoId) {
        return STATE_KEY_PREFIX + repoId;
    }

    String lockKey(long repoId) {
        return LOCK_KEY_PREFIX + repoId;
    }

    String activeKey(long repoId) {
        return ACTIVE_KEY_PREFIX + repoId;
    }

    public enum CreateResult {
        CREATED,
        ACTIVE,
        LOCKED
    }
}
