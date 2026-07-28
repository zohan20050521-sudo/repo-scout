import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useRepoStore } from '@/stores/repo'
import * as reposApi from '@/api/repos'
import type { IndexJobResponse, IndexStatus, IndexTask } from '@/types/api'

const queued: IndexTask = {
  jobId: 'job-queued',
  repoId: 1,
  status: 'QUEUED',
  errorCode: null,
  errorMessage: null,
  fileCount: null,
  chunkCount: null,
  costMs: null,
  startedAt: null,
  finishedAt: null,
}
const running: IndexTask = { ...queued, jobId: 'job-running', status: 'RUNNING' }
const succeeded: IndexTask = {
  ...running,
  status: 'SUCCEEDED',
  fileCount: 2,
  chunkCount: 8,
  costMs: 300,
  finishedAt: '2026-07-28T12:01:00',
}
const failed: IndexTask = {
  ...running,
  status: 'FAILED',
  errorCode: 'GITHUB_UNAVAILABLE',
  errorMessage: 'GitHub 服务暂时不可用，请稍后重试',
  finishedAt: '2026-07-28T12:01:00',
}

function status(task: IndexTask | null): IndexStatus {
  return { repoId: 1, indexed: task?.status === 'SUCCEEDED', fileCount: 0, chunkCount: 0, indexedAt: null, task }
}

describe('repo store 索引任务轮询', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    useRepoStore().stopPolling()
    vi.useRealTimers()
  })

  it('刷新工作区遇到 RUNNING 会恢复轮询并在成功终态停止', async () => {
    const getStatus = vi.spyOn(reposApi, 'getIndexStatus')
      .mockResolvedValueOnce(status(running))
      .mockResolvedValue(status(succeeded))
    const store = useRepoStore()

    await store.fetchIndexStatus(1)
    expect(store.indexing).toBe(true)
    await vi.advanceTimersByTimeAsync(2_000)
    await flushPromises()

    expect(getStatus).toHaveBeenCalledTimes(2)
    expect(store.indexing).toBe(false)
    expect(store.lastIndexResult?.chunkCount).toBe(8)
  })

  it('服务端 FAILED 终态停止轮询并保留错误文案', async () => {
    vi.spyOn(reposApi, 'getIndexStatus')
      .mockResolvedValueOnce(status(queued))
      .mockResolvedValue(status(failed))
    const store = useRepoStore()

    await store.fetchIndexStatus(1)
    await vi.advanceTimersByTimeAsync(2_000)
    await flushPromises()

    expect(store.indexing).toBe(false)
    expect(store.indexError?.code).toBe('GITHUB_UNAVAILABLE')
    expect(store.indexError?.message).toContain('GitHub 服务暂时不可用')
  })

  it('切换仓库会使旧 generation 的 timer 失效', async () => {
    const getStatus = vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(status(running))
    const store = useRepoStore()

    await store.fetchIndexStatus(1)
    store.resetIndexState()
    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()

    expect(getStatus).toHaveBeenCalledTimes(1)
    expect(store.indexing).toBe(false)
  })

  it('轮询超过十分钟后停止并报告超时', async () => {
    vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(status(queued))
    const store = useRepoStore()
    await store.fetchIndexStatus(1)

    for (let i = 0; i < 301; i += 1) {
      await vi.advanceTimersByTimeAsync(2_000)
      await flushPromises()
    }

    expect(store.indexing).toBe(false)
    expect(store.indexError?.code).toBe('INTERNAL_ERROR')
    expect(store.indexError?.message).toContain('轮询超时')
  })

  it('提交接口返回 job 资源而非同步结果', async () => {
    const response: IndexJobResponse = { repoId: 1, jobId: 'job-new', status: 'QUEUED' }
    vi.spyOn(reposApi, 'buildIndex').mockResolvedValue(response)
    vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(status(queued))
    const store = useRepoStore()

    await expect(store.runIndex(1)).resolves.toEqual(response)
    expect(store.activeJobId).toBe('job-queued')
    store.stopPolling()
  })
})
