import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { AxiosError, AxiosHeaders } from 'axios'
import RepoWorkspaceView from '@/views/RepoWorkspaceView.vue'
import HomeView from '@/views/HomeView.vue'
import * as reposApi from '@/api/repos'
import type { IndexStatus, RepoSummary } from '@/types/api'

const repo: RepoSummary = {
  id: 12,
  owner: 'vuejs',
  name: 'core',
  defaultBranch: 'main',
  description: 'Vue.js core',
  htmlUrl: 'https://github.com/vuejs/core',
  createdAt: '2026-07-26T12:00:00',
  updatedAt: '2026-07-27T09:00:00',
}

const otherRepo: RepoSummary = { ...repo, id: 13, name: 'router' }

const status: IndexStatus = {
  repoId: 12,
  indexed: true,
  fileCount: 4,
  chunkCount: 63,
  indexedAt: '2026-07-27T12:00:00',
}

function repoNotFound(): AxiosError {
  const config = { headers: new AxiosHeaders() }
  const error = new AxiosError('not found', 'ERR_BAD_REQUEST', config)
  error.response = {
    status: 404,
    statusText: '',
    data: { code: 'REPO_NOT_FOUND', message: '仓库未接入或不存在:id=12' },
    headers: new AxiosHeaders(),
    config,
  }
  return error
}

function makeRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: HomeView },
      { path: '/repos/:repoId(\\d+)', name: 'repo-workspace', component: RepoWorkspaceView },
    ],
  })
}

async function mountWorkspace(path: string) {
  const router = makeRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(RepoWorkspaceView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('工作区按路由 repoId 拉取数据', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('直接访问 /repos/12（模拟刷新）会重新请求详情与索引状态', async () => {
    const getRepo = vi.spyOn(reposApi, 'getRepo').mockResolvedValue(repo)
    const getIndexStatus = vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(status)

    const wrapper = await mountWorkspace('/repos/12')

    expect(getRepo).toHaveBeenCalledWith(12)
    expect(getIndexStatus).toHaveBeenCalledWith(12)
    expect(wrapper.text()).toContain('vuejs/core')
    expect(wrapper.text()).toContain('已索引')
  })

  it('详情 404 时展示页面级错误与返回入口，且不请求索引状态', async () => {
    vi.spyOn(reposApi, 'getRepo').mockRejectedValue(repoNotFound())
    const getIndexStatus = vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(status)

    const wrapper = await mountWorkspace('/repos/12')

    expect(getIndexStatus).not.toHaveBeenCalled()
    const text = wrapper.text()
    expect(text).toContain('仓库信息没能加载')
    expect(text).toContain('仓库未接入或不存在:id=12')
    expect(text).toContain('REPO_NOT_FOUND')
    expect(text).toContain('返回接入页选择其他仓库')
  })

  it('路由 repoId 变化时重新拉取新仓库', async () => {
    const getRepo = vi
      .spyOn(reposApi, 'getRepo')
      .mockResolvedValueOnce(repo)
      .mockResolvedValueOnce(otherRepo)
    vi.spyOn(reposApi, 'getIndexStatus').mockResolvedValue(status)

    const router = makeRouter()
    await router.push('/repos/12')
    await router.isReady()
    const wrapper = mount(RepoWorkspaceView, { global: { plugins: [router] } })
    await flushPromises()

    await router.push('/repos/13')
    await flushPromises()

    expect(getRepo).toHaveBeenNthCalledWith(1, 12)
    expect(getRepo).toHaveBeenNthCalledWith(2, 13)
    expect(wrapper.text()).toContain('vuejs/router')
  })
})
