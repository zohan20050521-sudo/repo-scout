import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { buildIndex, getIndexStatus, getRepo, listRepos } from '@/api/repos'
import type { ApiError} from '@/api/error';
import { toApiError } from '@/api/error'
import type { IndexResult, IndexStatus, RepoSummary } from '@/types/api'

/** 跨页面共享的当前仓库与索引状态；纯局部 UI 状态留在组件里 */
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
  const lastIndexResult = ref<IndexResult | null>(null)

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

  /** 路由刷新后按 repoId 重新拉取，不依赖内存里的导航对象 */
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

  async function fetchIndexStatus(id: number): Promise<void> {
    indexStatusLoading.value = true
    indexStatusError.value = null
    try {
      indexStatus.value = await getIndexStatus(id)
    } catch (error) {
      indexStatusError.value = toApiError(error)
    } finally {
      indexStatusLoading.value = false
    }
  }

  /** 同步长请求；成功后立即用 index-status 刷新真实状态 */
  async function runIndex(id: number): Promise<IndexResult | null> {
    if (indexing.value) return null
    indexing.value = true
    indexError.value = null
    try {
      const result = await buildIndex(id)
      lastIndexResult.value = result
      await fetchIndexStatus(id)
      return result
    } catch (error) {
      indexError.value = toApiError(error)
      return null
    } finally {
      indexing.value = false
    }
  }

  function upsertRepo(repo: RepoSummary): void {
    const index = repos.value.findIndex((item) => item.id === repo.id)
    if (index >= 0) repos.value.splice(index, 1, repo)
    else repos.value.unshift(repo)
  }

  /** 切换仓库时清掉上一个仓库的索引痕迹，避免串数据 */
  function resetIndexState(): void {
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
    repoFullName,
    fetchRepos,
    fetchRepo,
    fetchIndexStatus,
    runIndex,
    upsertRepo,
    resetIndexState,
  }
})
