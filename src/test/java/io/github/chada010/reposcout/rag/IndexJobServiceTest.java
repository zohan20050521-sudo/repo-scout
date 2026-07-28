package io.github.chada010.reposcout.rag;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.repository.RepoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class IndexJobServiceTest {

    @Mock
    private RepoRepository repoRepository;
    @Mock
    private IndexingService indexingService;
    @Mock
    private IndexJobStore store;
    @Mock
    private org.springframework.core.task.TaskExecutor executor;

    private IndexJobService service() {
        return new IndexJobService(repoRepository, indexingService, store, executor);
    }

    @Test
    void firstSubmitPersistsQueuedStateAndSchedulesExactlyOnce() {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(store.createIfAvailable(org.mockito.ArgumentMatchers.any(IndexJobState.class)))
                .willReturn(IndexJobStore.CreateResult.CREATED);

        IndexJobState result = service().submit(1L);

        assertThat(result.repoId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(IndexJobStatus.QUEUED);
        verify(store).createIfAvailable(result);
        verify(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void activeTaskIsReusedWithoutSchedulingAnotherEmbedding() {
        IndexJobState running = state(IndexJobStatus.RUNNING);
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(store.createIfAvailable(org.mockito.ArgumentMatchers.any(IndexJobState.class)))
                .willReturn(IndexJobStore.CreateResult.ACTIVE);
        given(store.find(1L)).willReturn(Optional.of(running));

        assertThat(service().submit(1L)).isEqualTo(running);
        verify(executor, never()).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void terminalTaskReleasesStaleLockAndCreatesNewJob() {
        IndexJobState succeeded = state(IndexJobStatus.SUCCEEDED);
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(store.createIfAvailable(org.mockito.ArgumentMatchers.any(IndexJobState.class)))
                .willReturn(IndexJobStore.CreateResult.LOCKED, IndexJobStore.CreateResult.CREATED);
        given(store.find(1L)).willReturn(Optional.of(succeeded));
        given(store.lockOwner(1L)).willReturn("old-job");

        IndexJobState result = service().submit(1L);

        assertThat(result.jobId()).isNotEqualTo(succeeded.jobId());
        verify(store).releaseIfOwner(1L, "old-job");
        verify(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void workerSuccessStoresResultAndReleasesOwnLock() {
        IndexJobState queued = state(IndexJobStatus.QUEUED);
        given(store.find(1L)).willReturn(Optional.of(queued));
        given(store.saveIfOwner(org.mockito.ArgumentMatchers.any(IndexJobState.class))).willReturn(true);
        given(indexingService.index(1L)).willReturn(new IndexResult(3, 12, 456L));

        service().run(queued);

        ArgumentCaptor<IndexJobState> captured = ArgumentCaptor.forClass(IndexJobState.class);
        verify(store, org.mockito.Mockito.times(2)).saveIfOwner(captured.capture());
        assertThat(captured.getAllValues().get(0).status()).isEqualTo(IndexJobStatus.RUNNING);
        assertThat(captured.getAllValues().get(1).status()).isEqualTo(IndexJobStatus.SUCCEEDED);
        assertThat(captured.getAllValues().get(1).chunkCount()).isEqualTo(12);
        verify(store).releaseIfOwner(1L, queued.jobId());
    }

    @Test
    void workerGithubFailureStoresSafeErrorAndDoesNotEscapeThread() {
        IndexJobState queued = state(IndexJobStatus.QUEUED);
        given(store.find(1L)).willReturn(Optional.of(queued));
        given(store.saveIfOwner(org.mockito.ArgumentMatchers.any(IndexJobState.class))).willReturn(true);
        given(indexingService.index(1L)).willThrow(new GithubUnavailableException("secret upstream detail"));

        service().run(queued);

        ArgumentCaptor<IndexJobState> captured = ArgumentCaptor.forClass(IndexJobState.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).saveIfOwner(captured.capture());
        IndexJobState failed = captured.getAllValues().get(captured.getAllValues().size() - 1);
        assertThat(failed.status()).isEqualTo(IndexJobStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("GITHUB_UNAVAILABLE");
        assertThat(failed.errorMessage()).doesNotContain("secret");
        verify(store).releaseIfOwner(1L, queued.jobId());
    }

    @Test
    void missingRepoIsRejectedBeforeRedisOrExecutor() {
        given(repoRepository.findById(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().submit(9L))
                .isInstanceOf(RepoNotFoundException.class);
        verify(store, never()).createIfAvailable(org.mockito.ArgumentMatchers.any(IndexJobState.class));
        verify(executor, never()).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void fullQueueMarksTaskFailedAndPropagatesRejection() {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(store.createIfAvailable(org.mockito.ArgumentMatchers.any(IndexJobState.class)))
                .willReturn(IndexJobStore.CreateResult.CREATED);
        doThrow(new RejectedExecutionException("queue full"))
                .when(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));

        assertThatThrownBy(() -> service().submit(1L))
                .isInstanceOf(RejectedExecutionException.class);
        verify(store).releaseIfOwner(eq(1L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void orphanLockWithoutStateIsRecoveredAndDoesNotReturnInternalError() {
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(store.createIfAvailable(org.mockito.ArgumentMatchers.any(IndexJobState.class)))
                .willReturn(IndexJobStore.CreateResult.LOCKED, IndexJobStore.CreateResult.CREATED);
        given(store.find(1L)).willReturn(Optional.empty());
        given(store.lockOwner(1L)).willReturn("orphan-job");

        IndexJobState result = service().submit(1L);

        assertThat(result.status()).isEqualTo(IndexJobStatus.QUEUED);
        verify(store).releaseOrphanIfOwner(1L, "orphan-job");
        verify(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void activeStateIsReusedAfterLockLeaseExpires() {
        IndexJobState running = state(IndexJobStatus.RUNNING);
        given(repoRepository.findById(1L)).willReturn(Optional.of(repo()));
        given(store.createIfAvailable(org.mockito.ArgumentMatchers.any(IndexJobState.class)))
                .willReturn(IndexJobStore.CreateResult.ACTIVE);
        given(store.find(1L)).willReturn(Optional.of(running));

        assertThat(service().submit(1L)).isEqualTo(running);
        verify(executor, never()).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void workerStopsBeforeEmbeddingWhenActiveOwnerWasReplaced() {
        IndexJobState queued = state(IndexJobStatus.QUEUED);
        given(store.find(1L)).willReturn(Optional.of(queued));
        given(store.saveIfOwner(org.mockito.ArgumentMatchers.any(IndexJobState.class))).willReturn(false);

        service().run(queued);

        verify(indexingService, never()).index(1L);
        verify(store).releaseIfOwner(1L, queued.jobId());
    }

    @Test
    void workerDoesNotWriteTerminalStateAfterActiveOwnerWasReplaced() {
        IndexJobState queued = state(IndexJobStatus.QUEUED);
        given(store.find(1L)).willReturn(Optional.of(queued));
        given(store.saveIfOwner(org.mockito.ArgumentMatchers.any(IndexJobState.class)))
                .willReturn(true, false);
        given(indexingService.index(1L)).willReturn(new IndexResult(3, 12, 456L));

        service().run(queued);

        verify(store, org.mockito.Mockito.times(2))
                .saveIfOwner(org.mockito.ArgumentMatchers.any(IndexJobState.class));
        verify(store).releaseIfOwner(1L, queued.jobId());
    }

    private Repo repo() {
        return new Repo("owner", "repo", "main", null,
                "https://github.com/owner/repo", LocalDateTime.now(), LocalDateTime.now());
    }

    private IndexJobState state(IndexJobStatus status) {
        return new IndexJobState("job-1", 1L, status,
                LocalDateTime.of(2026, 7, 28, 12, 0),
                status == IndexJobStatus.QUEUED ? null : LocalDateTime.of(2026, 7, 28, 12, 1),
                status == IndexJobStatus.SUCCEEDED ? LocalDateTime.of(2026, 7, 28, 12, 2) : null,
                null, null, null, null, null);
    }
}
