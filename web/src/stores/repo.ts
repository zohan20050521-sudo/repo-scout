import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { buildIndex, getIndexStatus, getRepo, listRepos } from '@/api/repos'
import { ApiError, toApiError } from '@/api/error'
import type {
  ApiErrorCode,
  ErrorCode,
  IndexJobResponse,
  IndexStatus,
  IndexTask,
  RepoSummary,
} from '@/types/api'

const POLL_INTERVAL_MS = 2_000
const MAX_POLL_ATTEMPTS = 300

/** 跨页面共享的当前仓库与索引状态；纯局部 UI 状态留在组件里。 */
export const useRepoStore = defineStore('repo', () => {
  const repos = ref<RepoSummary[]>([])
  const listLoading = ref(false)
  const listError = ref<ApiError | null>(null)

  const currentRepo = ref<RepoSummary | null>(null)
  const repoLoading = ref(false)
  const repoError = ref<ApiError | null>(null)

  const indexStatus = ref<IndexStatus | null>(null)
  const indexStatusLoading = ref(false)
  const indexStatusError = ref<ApiError | null>(null)

  const indexing = ref(false)
  const indexError = ref<ApiError | null>(null)
  const lastIndexResult = ref<IndexTask | null>(null)
  const activeJobId = ref<string | null>(null)

  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let pollRepoId: number | null = null
  let pollAttempts = 0
  let stateGeneration = 0

  const repoFullName = computed(() =>
    currentRepo.value ? `${currentRepo.value.owner}/${currentRepo.value.name}` : '',
  )

  async function fetchRepos(): Promise<void> {
    listLoading.value = true
    listError.value = null
    try {
      repos.value = await listRepos()
    } catch (error) {
      listError.value = toApiError(error)
    } finally {
      listLoading.value = false
    }
  }

  /** 路由刷新后按 repoId 重新拉取，不依赖内存里的导航对象。 */
  async function fetchRepo(id: number): Promise<void> {
    repoLoading.value = true
    repoError.value = null
    try {
      currentRepo.value = await getRepo(id)
    } catch (error) {
      currentRepo.value = null
      repoError.value = toApiError(error)
    } finally {
      repoLoading.value = false
    }
  }

  function clearPollTimer(): void {
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  /** 清掉定时器；不修改状态，供组件卸载时调用。 */
  function stopPolling(): void {
    clearPollTimer()
    pollRepoId = null
    pollAttempts = 0
  }

  /** 组件卸载时同时使尚未完成的状态请求失效，防止它重新启动轮询。 */
  function invalidatePolling(): void {
    stateGeneration += 1
    stopPolling()
  }

  function taskError(task: IndexTask): ApiError {
    const knownCodes: readonly ApiErrorCode[] = [
      'INVALID_PARAM',
      'LLM_UNAVAILABLE',
      'INTERNAL_ERROR',
      'REPO_NOT_FOUND',
      'GITHUB_UNAVAILABLE',
      'UNAUTHORIZED',
    ]
    const code: ErrorCode = task.errorCode && knownCodes.includes(task.errorCode as ApiErrorCode)
      ? (task.errorCode as ApiErrorCode)
      : 'INTERNAL_ERROR'
    return new ApiError(code, task.errorMessage?.trim() || '索引任务失败，请稍后重试', null, true)
  }

  function applyIndexStatus(status: IndexStatus, id: number, token: number, fromPoll: boolean): boolean {
    if (token !== stateGeneration) return false
    indexStatus.value = status
    const task = status.task ?? null
    if (task && (task.status === 'QUEUED' || task.status === 'RUNNING')) {
      indexing.value = true
      activeJobId.value = task.jobId
      if (!fromPoll) startPolling(id, token)
      return true
    }

    indexing.value = false
    activeJobId.value = null
    stopPolling()
    if (task?.status === 'SUCCEEDED') {
      lastIndexResult.value = task
      indexError.value = null
    } else if (task?.status === 'FAILED') {
      indexError.value = taskError(task)
    }
    return false
  }

  async function fetchIndexStatusInternal(id: number, token: number, fromPoll: boolean): Promise<boolean> {
    indexStatusLoading.value = true
    indexStatusError.value = null
    try {
      const status = await getIndexStatus(id)
      return applyIndexStatus(status, id, token, fromPoll)
    } catch (error) {
      if (token === stateGeneration) indexStatusError.value = toApiError(error)
      return token === stateGeneration && indexing.value
    } finally {
      if (token === stateGeneration) indexStatusLoading.value = false
    }
  }

  async function fetchIndexStatus(id: number): Promise<void> {
    if (pollRepoId !== null && pollRepoId !== id) {
      stateGeneration += 1
      stopPolling()
      indexing.value = false
      activeJobId.value = null
    }
    const token = stateGeneration
    await fetchIndexStatusInternal(id, token, false)
  }

  function schedulePoll(id: number, token: number): void {
    clearPollTimer()
    pollTimer = setTimeout(async () => {
      pollTimer = null
      if (token !== stateGeneration || pollRepoId !== id) return
      pollAttempts += 1
      if (pollAttempts > MAX_POLL_ATTEMPTS) {
        indexing.value = false
        activeJobId.value = null
        indexError.value = new ApiError('INTERNAL_ERROR', '索引状态轮询超时，请刷新后重试', null, false)
        stopPolling()
        return
      }
      const active = await fetchIndexStatusInternal(id, token, true)
      if (active && token === stateGeneration && pollRepoId === id) schedulePoll(id, token)
    }, POLL_INTERVAL_MS)
  }

  function startPolling(id: number, token = stateGeneration): void {
    stopPolling()
    pollRepoId = id
    pollAttempts = 0
    schedulePoll(id, token)
  }

  /** 提交短请求后立即开始从服务端恢复任务状态。 */
  async function runIndex(id: number): Promise<IndexJobResponse | null> {
    if (indexing.value) return null
    const token = stateGeneration
    indexing.value = true
    indexError.value = null
    indexStatusError.value = null
    try {
      const result = await buildIndex(id)
      if (token !== stateGeneration) return null
      activeJobId.value = result.jobId
      startPolling(id, token)
      await fetchIndexStatusInternal(id, token, false)
      return result
    } catch (error) {
      if (token === stateGeneration) {
        indexing.value = false
        activeJobId.value = null
        stopPolling()
        indexError.value = toApiError(error)
      }
      return null
    }
  }

  function upsertRepo(repo: RepoSummary): void {
    const index = repos.value.findIndex((item) => item.id === repo.id)
    if (index >= 0) repos.value.splice(index, 1, repo)
    else repos.value.unshift(repo)
  }

  /** 切换仓库时清掉上一个仓库的状态、错误与所有轮询请求。 */
  function resetIndexState(): void {
    stateGeneration += 1
    stopPolling()
    indexing.value = false
    activeJobId.value = null
    indexStatus.value = null
    indexStatusError.value = null
    indexError.value = null
    lastIndexResult.value = null
  }

  return {
    repos,
    listLoading,
    listError,
    currentRepo,
    repoLoading,
    repoError,
    indexStatus,
    indexStatusLoading,
    indexStatusError,
    indexing,
    indexError,
    lastIndexResult,
    activeJobId,
    repoFullName,
    fetchRepos,
    fetchRepo,
    fetchIndexStatus,
    runIndex,
    startPolling,
    stopPolling,
    invalidatePolling,
    upsertRepo,
    resetIndexState,
  }
})
