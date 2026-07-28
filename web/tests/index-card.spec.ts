import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import IndexStatusCard from '@/components/IndexStatusCard.vue'
import { useRepoStore } from '@/stores/repo'
import * as reposApi from '@/api/repos'
import type { IndexJobResponse, IndexStatus, IndexTask } from '@/types/api'

const queuedTask: IndexTask = {
  jobId: 'job-1',
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
const succeededTask: IndexTask = {
  ...queuedTask,
  jobId: 'job-2',
  status: 'SUCCEEDED',
  fileCount: 4,
  chunkCount: 63,
  costMs: 2450,
  startedAt: '2026-07-27T11:59:00',
  finishedAt: '2026-07-27T12:00:00',
}
const failedTask: IndexTask = {
  ...queuedTask,
  jobId: 'job-3',
  status: 'FAILED',
  errorCode: 'GITHUB_UNAVAILABLE',
  errorMessage: 'GitHub API 限流,请稍后重试',
  finishedAt: '2026-07-27T12:00:00',
}
const submitted: IndexJobResponse = { repoId: 1, jobId: 'job-2', status: 'QUEUED' }

const notIndexed: IndexStatus = {
  repoId: 1,
  indexed: false,
  fileCount: 0,
  chunkCount: 0,
  indexedAt: null,
  task: null,
}
const indexed: IndexStatus = {
  repoId: 1,
  indexed: true,
  fileCount: 4,
  chunkCount: 63,
  indexedAt: '2026-07-27T12:00:00',
  task: succeededTask,
}

function mountCard() {
  return mount(IndexStatusCard, { props: { repoId: 1 } })
}

describe('索引状态卡片', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('未索引时展示计数、说明与「建立文档索引」主行动', async () => {
    vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(notIndexed)
    const store = useRepoStore()
    const wrapper = mountCard()
    await store.fetchIndexStatus(1)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('未索引')
    expect(text).toContain('建立文档索引')
    expect(text).toContain('README')
  })

  it('已索引时展示文件数/块数/索引时间与「重建索引」', async () => {
    vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(indexed)
    const store = useRepoStore()
    const wrapper = mountCard()
    await store.fetchIndexStatus(1)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('已索引')
    expect(text).toContain('63')
    expect(text).toContain('2026-07-27 12:00')
    expect(text).toContain('重建索引')
    expect(text).toContain('幂等')
  })

  it('索引中展示排队文案且禁止重复提交', async () => {
    vi.spyOn(reposApi, 'getIndexStatus')
      .mockResolvedValueOnce(notIndexed)
      .mockResolvedValue({ ...notIndexed, task: queuedTask })
    const buildIndex = vi.spyOn(reposApi, 'buildIndex').mockResolvedValue(submitted)

    const store = useRepoStore()
    const wrapper = mountCard()
    await store.fetchIndexStatus(1)
    await flushPromises()

    const button = wrapper.findAll('button').find((b) => b.text().includes('建立文档索引'))
    await button?.trigger('click')
    await flushPromises()
    await button?.trigger('click')
    await flushPromises()

    expect(buildIndex).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('排队中')
    wrapper.unmount()
  })

  it('索引成功后展示本次结果并重新请求 index-status', async () => {
    const getIndexStatus = vi.spyOn(reposApi, 'getIndexStatus')
      .mockResolvedValueOnce(notIndexed)
      .mockResolvedValue(indexed)
    vi.spyOn(reposApi, 'buildIndex').mockResolvedValue(submitted)

    const store = useRepoStore()
    const wrapper = mountCard()
    await store.fetchIndexStatus(1)
    await flushPromises()

    await store.runIndex(1)
    await flushPromises()

    expect(getIndexStatus).toHaveBeenCalledTimes(2)
    const text = wrapper.text()
    expect(text).toContain('本次索引完成')
    expect(text).toContain('2.5 秒')
    expect(text).toContain('已索引')
  })

  it('后端任务失败保留重试入口并透出安全 message', async () => {
    vi.spyOn(reposApi, 'getIndexStatus')
      .mockResolvedValueOnce(notIndexed)
      .mockResolvedValue({ ...notIndexed, task: failedTask })
    vi.spyOn(reposApi, 'buildIndex').mockResolvedValue(submitted)

    const store = useRepoStore()
    const wrapper = mountCard()
    await store.fetchIndexStatus(1)
    await store.runIndex(1)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('索引没能完成')
    expect(text).toContain('GitHub API 限流,请稍后重试')
    expect(text).toContain('重新索引')
  })
})
