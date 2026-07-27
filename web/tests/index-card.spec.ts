import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError, AxiosHeaders } from 'axios'
import IndexStatusCard from '@/components/IndexStatusCard.vue'
import { useRepoStore } from '@/stores/repo'
import * as reposApi from '@/api/repos'
import type { IndexResult, IndexStatus } from '@/types/api'

const notIndexed: IndexStatus = {
  repoId: 1,
  indexed: false,
  fileCount: 0,
  chunkCount: 0,
  indexedAt: null,
}
const indexed: IndexStatus = {
  repoId: 1,
  indexed: true,
  fileCount: 4,
  chunkCount: 63,
  indexedAt: '2026-07-27T12:00:00',
}
const indexResult: IndexResult = { repoId: 1, fileCount: 4, chunkCount: 63, costMs: 2450 }

function githubUnavailable(): AxiosError {
  const config = { headers: new AxiosHeaders() }
  const error = new AxiosError('bad gateway', 'ERR_BAD_RESPONSE', config)
  error.response = {
    status: 502,
    statusText: '',
    data: { code: 'GITHUB_UNAVAILABLE', message: 'GitHub API 限流,请稍后重试' },
    headers: new AxiosHeaders(),
    config,
  }
  return error
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

  it('索引中展示真实等待文案且禁止重复提交', async () => {
    vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(notIndexed)
    let resolveIndex: ((value: IndexResult) => void) | undefined
    const buildIndex = vi
      .spyOn(reposApi, 'buildIndex')
      .mockReturnValue(new Promise<IndexResult>((r) => (resolveIndex = r)))

    const store = useRepoStore()
    const wrapper = mountCard()
    await store.fetchIndexStatus(1)
    await flushPromises()

    const button = wrapper.findAll('button').find((b) => b.text().includes('建立文档索引'))
    await button?.trigger('click')
    await button?.trigger('click')
    await flushPromises()

    expect(buildIndex).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('正在拉取文档并生成向量索引')

    resolveIndex?.(indexResult)
    await flushPromises()
  })

  it('索引成功后展示本次结果并重新请求 index-status', async () => {
    const getIndexStatus = vi
      .spyOn(reposApi, 'getIndexStatus')
      .mockResolvedValueOnce(notIndexed)
      .mockResolvedValue(indexed)
    vi.spyOn(reposApi, 'buildIndex').mockResolvedValue(indexResult)

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

  it('索引失败保留重试入口并透出后端 message', async () => {
    vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(notIndexed)
    vi.spyOn(reposApi, 'buildIndex').mockRejectedValue(githubUnavailable())

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
