import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/api/client'
import { listRepos, createRepo, getIndexStatus, buildIndex } from '@/api/repos'
import { sendChat } from '@/api/chat'
import { generateReport } from '@/api/report'
import type { RepoSummary } from '@/types/api'

const repo: RepoSummary = {
  id: 1,
  owner: 'octocat',
  name: 'Hello-World',
  defaultBranch: 'master',
  description: null,
  htmlUrl: 'https://github.com/octocat/Hello-World',
  createdAt: '2026-07-26T12:00:00',
  updatedAt: '2026-07-26T12:00:00',
}

describe('API 请求层', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('baseURL 固定为同源 /api，且不带任何内部密钥请求头', () => {
    expect(http.defaults.baseURL).toBe('/api')
    const headers = JSON.stringify(http.defaults.headers)
    expect(headers.toLowerCase()).not.toContain('internal')
  })

  it('直接返回后端资源 JSON，不做 { data } 包装兼容', async () => {
    const request = vi.spyOn(http, 'request').mockResolvedValue({ data: [repo] })
    await expect(listRepos()).resolves.toEqual([repo])
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/repos', method: 'GET' }))
  })

  it('各接口的方法与路径与 docs/api.md 一致', async () => {
    const request = vi.spyOn(http, 'request').mockResolvedValue({ data: repo })

    await createRepo('octocat/Hello-World')
    expect(request).toHaveBeenLastCalledWith({
      url: '/repos',
      method: 'POST',
      data: { repo: 'octocat/Hello-World' },
    })

    await getIndexStatus(7)
    expect(request).toHaveBeenLastCalledWith({ url: '/repos/7/index-status', method: 'GET' })

    await buildIndex(7)
    expect(request).toHaveBeenLastCalledWith({ url: '/repos/7/index', method: 'POST' })

    await generateReport(7)
    expect(request).toHaveBeenLastCalledWith({ url: '/repos/7/report', method: 'POST' })

    await sendChat({ message: '你好', repoId: 7 })
    expect(request).toHaveBeenLastCalledWith({
      url: '/chat',
      method: 'POST',
      data: { message: '你好', repoId: 7 },
    })
  })
})
