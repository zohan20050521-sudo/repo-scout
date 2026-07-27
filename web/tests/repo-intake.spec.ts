import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { AxiosError, AxiosHeaders } from 'axios'
import RepoIntakeForm from '@/components/RepoIntakeForm.vue'
import * as reposApi from '@/api/repos'
import type { RepoSummary } from '@/types/api'

const repo: RepoSummary = {
  id: 3,
  owner: 'vuejs',
  name: 'core',
  defaultBranch: 'main',
  description: 'Vue.js core',
  htmlUrl: 'https://github.com/vuejs/core',
  createdAt: '2026-07-26T12:00:00',
  updatedAt: '2026-07-27T09:00:00',
}

function invalidParamError(): AxiosError {
  const config = { headers: new AxiosHeaders() }
  const error = new AxiosError('bad request', 'ERR_BAD_REQUEST', config)
  error.response = {
    status: 400,
    statusText: '',
    data: { code: 'INVALID_PARAM', message: 'repo 格式不合法:仅支持 owner/repo' },
    headers: new AxiosHeaders(),
    config,
  }
  return error
}

describe('仓库接入表单', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('空输入时提交按钮禁用，不发请求', async () => {
    const createRepo = vi.spyOn(reposApi, 'createRepo')
    const wrapper = mount(RepoIntakeForm)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(createRepo).not.toHaveBeenCalled()
  })

  it('合法输入提交后抛出 connected 事件并透传服务端仓库对象', async () => {
    const createRepo = vi.spyOn(reposApi, 'createRepo').mockResolvedValue(repo)
    const wrapper = mount(RepoIntakeForm)
    await wrapper.find('input').setValue('  vuejs/core  ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createRepo).toHaveBeenCalledWith('vuejs/core')
    expect(wrapper.emitted('connected')).toEqual([[repo]])
  })

  it('请求进行中禁止重复提交', async () => {
    let resolve: ((value: RepoSummary) => void) | undefined
    const createRepo = vi
      .spyOn(reposApi, 'createRepo')
      .mockReturnValue(new Promise<RepoSummary>((r) => (resolve = r)))

    const wrapper = mount(RepoIntakeForm)
    await wrapper.find('input').setValue('vuejs/core')
    await wrapper.find('form').trigger('submit')
    await wrapper.find('form').trigger('submit')
    await wrapper.find('form').trigger('submit')
    expect(createRepo).toHaveBeenCalledTimes(1)

    resolve?.(repo)
    await flushPromises()
    expect(wrapper.emitted('connected')).toHaveLength(1)
  })

  it('服务端错误按 message 展示，并保留重试入口', async () => {
    vi.spyOn(reposApi, 'createRepo').mockRejectedValue(invalidParamError())
    const wrapper = mount(RepoIntakeForm)
    await wrapper.find('input').setValue('https://gitlab.com/a/b')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('仓库接入失败')
    expect(text).toContain('repo 格式不合法:仅支持 owner/repo')
    expect(text).toContain('INVALID_PARAM')
    expect(text).toContain('重新接入')
    expect(wrapper.emitted('connected')).toBeUndefined()
  })
})
