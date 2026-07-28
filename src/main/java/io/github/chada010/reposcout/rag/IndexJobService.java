package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import io.github.chada010.reposcout.exception.GithubRateLimitException;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.repository.RepoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/** 索引任务提交、去重、后台执行与安全错误映射。 */
@Service
public class IndexJobService {

    private static final Logger log = LoggerFactory.getLogger(IndexJobService.class);
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String REPO_NOT_FOUND = "REPO_NOT_FOUND";
    private static final String GITHUB_UNAVAILABLE = "GITHUB_UNAVAILABLE";
    private static final String REPO_NOT_FOUND_MESSAGE = "仓库不存在，索引任务无法完成";
    private static final String GITHUB_MESSAGE = "GitHub 服务暂时不可用，请稍后重试";
    private static final String RATE_LIMIT_MESSAGE = "GitHub API 限流，请稍后重试";
    private static final String INTERNAL_MESSAGE = "索引任务失败，请稍后重试";
    private static final String QUEUE_FULL_MESSAGE = "索引队列繁忙，请稍后重试";
    private static final int MAX_SUBMIT_ATTEMPTS = 5;

    private final RepoRepository repoRepository;
    private final IndexingService indexingService;
    private final IndexJobStore store;
    private final TaskExecutor executor;

    public IndexJobService(RepoRepository repoRepository, IndexingService indexingService,
                           IndexJobStore store,
                           @Qualifier("indexTaskExecutor") TaskExecutor executor) {
        this.repoRepository = repoRepository;
        this.indexingService = indexingService;
        this.store = store;
        this.executor = executor;
    }

    /** 校验仓库后提交；同仓库 active 任务直接复用，不创建第二个 embedding。 */
    public IndexJobState submit(long repoId) {
        repoRepository.findById(repoId)
                .orElseThrow(() -> new RepoNotFoundException("仓库未接入或不存在:id=" + repoId));

        for (int attempt = 0; attempt < MAX_SUBMIT_ATTEMPTS; attempt++) {
            IndexJobState queued = queued(repoId);
            IndexJobStore.CreateResult result = store.createIfAvailable(queued);
            if (result == IndexJobStore.CreateResult.CREATED) {
                return enqueue(queued);
            }

            Optional<IndexJobState> current = store.find(repoId);
            if (current.isPresent() && current.get().active()) {
                return current.get();
            }

            // 旧版本可能留下 lock-without-state；只按 owner 原子清理后重试。
            String owner = store.lockOwner(repoId);
            if (owner != null) {
                if (current.isPresent()) {
                    store.releaseIfOwner(repoId, owner);
                } else {
                    store.releaseOrphanIfOwner(repoId, owner);
                }
                continue;
            }

            // active marker 与 state 不一致时不返回 500，清理后让下一次原子创建修复。
            String activeOwner = store.activeOwner(repoId);
            if (activeOwner != null) {
                store.releaseIfOwner(repoId, activeOwner);
            }
        }
        throw new IllegalStateException("索引任务提交冲突，请稍后重试");
    }

    private IndexJobState queued(long repoId) {
        return new IndexJobState(UUID.randomUUID().toString(), repoId, IndexJobStatus.QUEUED,
                now(), null, null, null, null, null, null, null);
    }

    private IndexJobState enqueue(IndexJobState queued) {
        try {
            executor.execute(() -> run(queued));
            return queued;
        } catch (RejectedExecutionException e) {
            markFailedQuietly(queued, INTERNAL_ERROR, QUEUE_FULL_MESSAGE);
            store.releaseIfOwner(queued.repoId(), queued.jobId());
            throw e;
        } catch (RuntimeException e) {
            markFailedQuietly(queued, INTERNAL_ERROR, INTERNAL_MESSAGE);
            store.releaseIfOwner(queued.repoId(), queued.jobId());
            throw e;
        }
    }

    /** TaskExecutor 只调用此方法；所有异常均转换为可展示的 FAILED 状态。 */
    void run(IndexJobState queued) {
        try {
            IndexJobState current = store.find(queued.repoId()).orElse(queued);
            if (!queued.jobId().equals(current.jobId())) {
                return;
            }
            IndexJobState running = current.running(now());
            if (!store.saveIfOwner(running)) {
                return;
            }
            IndexResult result = indexingService.index(queued.repoId());
            store.saveIfOwner(running.succeeded(result, now()));
        } catch (RepoNotFoundException e) {
            markFailedQuietly(queued, REPO_NOT_FOUND, REPO_NOT_FOUND_MESSAGE);
        } catch (GithubRateLimitException e) {
            markFailedQuietly(queued, GITHUB_UNAVAILABLE, RATE_LIMIT_MESSAGE);
        } catch (GithubUnavailableException e) {
            markFailedQuietly(queued, GITHUB_UNAVAILABLE, GITHUB_MESSAGE);
        } catch (Exception e) {
            log.error("索引任务失败: repoId={}, jobId={}, exception={}", queued.repoId(),
                    queued.jobId(), e.getClass().getSimpleName());
            markFailedQuietly(queued, INTERNAL_ERROR, INTERNAL_MESSAGE);
        } finally {
            try {
                store.releaseIfOwner(queued.repoId(), queued.jobId());
            } catch (RuntimeException e) {
                log.warn("索引任务锁释放失败: repoId={}, jobId={}", queued.repoId(), queued.jobId());
            }
        }
    }

    private void markFailedQuietly(IndexJobState job, String code, String message) {
        try {
            IndexJobState current = store.find(job.repoId()).orElse(job);
            if (job.jobId().equals(current.jobId())) {
                store.saveIfOwner(current.failed(code, message, now()));
            }
        } catch (RuntimeException e) {
            log.warn("索引任务失败状态写入失败: repoId={}, jobId={}", job.repoId(), job.jobId());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
