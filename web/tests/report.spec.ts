import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError, AxiosHeaders } from 'axios'
import ReportPanel from '@/components/ReportPanel.vue'
import { useReportStore } from '@/stores/report'
import * as reportApi from '@/api/report'
import { reportFileName, safeFileNamePart } from '@/composables/useDownload'
import type { RepoSummary, ReportResult } from '@/types/api'

const repo: RepoSummary = {
  id: 1,
  owner: 'zohan20050521-sudo',
  name: 'repo-scout',
  defaultBranch: 'main',
  description: 'GitHub 仓库导读 Agent',
  htmlUrl: 'https://github.com/zohan20050521-sudo/repo-scout',
  createdAt: '2026-07-26T12:00:00',
  updatedAt: '2026-07-27T09:00:00',
}

const REPORT_MD = [
  '## 项目定位',
  '一个仓库导读 Agent。',
  '## 技术栈',
  'Spring Boot 3。',
  '## 目录结构解读',
  'src/main/java 下分层。',
  '## 上手指引',
  '先跑 mvn verify。',
  '## 近期动向',
  '最近在做前端。',
].join('\n')

const result: ReportResult = {
  repoId: 1,
  generatedAt: '2026-07-27T12:00:00',
  costMs: 24500,
  report: REPORT_MD,
}

function llmUnavailable(): AxiosError {
  const config = { headers: new AxiosHeaders() }
  const error = new AxiosError('bad gateway', 'ERR_BAD_RESPONSE', config)
  error.response = {
    status: 502,
    statusText: '',
    data: { code: 'LLM_UNAVAILABLE', message: 'DeepSeek 调用超时' },
    headers: new AxiosHeaders(),
    config,
  }
  return error
}

function mountPanel(indexed = true) {
  return mount(ReportPanel, { props: { repo, indexed } })
}

describe('报告面板', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('未生成时提示空态，未索引时给出先索引建议但不阻止生成', () => {
    const wrapper = mountPanel(false)
    const text = wrapper.text()
    expect(text).toContain('还没有生成报告')
    expect(text).toContain('先索引通常能获得更完整的文档摘录')
    const button = wrapper.findAll('button').find((b) => b.text().includes('生成报告'))
    expect(button?.attributes('disabled')).toBeUndefined()
  })

  it('生成中展示 20–30 秒等待文案，并禁止重复提交', async () => {
    let resolve: ((value: ReportResult) => void) | undefined
    const generate = vi
      .spyOn(reportApi, 'generateReport')
      .mockReturnValue(new Promise<ReportResult>((r) => (resolve = r)))

    const wrapper = mountPanel()
    const button = wrapper.findAll('button').find((b) => b.text().includes('生成报告'))
    await button?.trigger('click')
    await button?.trigger('click')
    await flushPromises()

    expect(generate).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('20–30 秒')

    resolve?.(result)
    await flushPromises()
  })

  it('成功后展示 generatedAt、costMs、五节正文与本地目录', async () => {
    vi.spyOn(reportApi, 'generateReport').mockResolvedValue(result)
    const store = useReportStore()
    const wrapper = mountPanel()
    await store.generate(1)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('2026-07-27 12:00')
    expect(text).toContain('24.5 秒')
    for (const section of ['项目定位', '技术栈', '目录结构解读', '上手指引', '近期动向']) {
      expect(text).toContain(section)
    }
    expect(wrapper.findAll('.rs-report__toc-item')).toHaveLength(5)
    expect(wrapper.find('.rs-report__markdown').html()).toContain('<h2 id="rs-h-项目定位">')
  })

  it('生成失败时保留旧报告并给出重试入口', async () => {
    vi.spyOn(reportApi, 'generateReport')
      .mockResolvedValueOnce(result)
      .mockRejectedValueOnce(llmUnavailable())

    const store = useReportStore()
    const wrapper = mountPanel()
    await store.generate(1)
    await store.generate(1)
    await flushPromises()

    expect(store.report).toEqual(result)
    const text = wrapper.text()
    expect(text).toContain('报告生成失败')
    expect(text).toContain('DeepSeek 调用超时')
    expect(text).toContain('重新生成')
    expect(text).toContain('项目定位')
  })

  it('复制 Markdown 成功调用 Clipboard API', async () => {
    vi.spyOn(reportApi, 'generateReport').mockResolvedValue(result)
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })

    const store = useReportStore()
    const wrapper = mountPanel()
    await store.generate(1)
    await flushPromises()

    const button = wrapper.findAll('button').find((b) => b.text().includes('复制 Markdown'))
    await button?.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(REPORT_MD)
    expect(wrapper.find('.rs-report__copy-error').exists()).toBe(false)
  })

  it('Clipboard 失败时给出可见错误反馈', async () => {
    vi.spyOn(reportApi, 'generateReport').mockResolvedValue(result)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('权限被拒绝')) },
      configurable: true,
    })

    const store = useReportStore()
    const wrapper = mountPanel()
    await store.generate(1)
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('复制 Markdown'))
      ?.trigger('click')
    await flushPromises()

    expect(wrapper.find('.rs-report__copy-error').text()).toContain('权限被拒绝')
  })

  it('下载使用安全化的 owner-name-report.md 文件名', async () => {
    vi.spyOn(reportApi, 'generateReport').mockResolvedValue(result)
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const createObjectURL = vi.fn().mockReturnValue('blob:mock')
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })

    const store = useReportStore()
    const wrapper = mountPanel()
    await store.generate(1)
    await flushPromises()

    const anchors: string[] = []
    const appendSpy = vi
      .spyOn(document.body, 'appendChild')
      .mockImplementation(<T extends Node>(node: T): T => {
        if (node instanceof HTMLAnchorElement) anchors.push(node.download)
        return node
      })
    vi.spyOn(document.body, 'removeChild').mockImplementation(<T extends Node>(node: T): T => node)

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('下载 .md'))
      ?.trigger('click')

    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(anchors).toEqual(['zohan20050521-sudo-repo-scout-report.md'])
    appendSpy.mockRestore()
  })
})

describe('文件名安全化', () => {
  it('剔除路径分隔符与特殊字符', () => {
    expect(safeFileNamePart('../../etc/passwd')).toBe('etc-passwd')
    expect(safeFileNamePart('a b/c:d*e')).toBe('a-b-c-d-e')
    expect(safeFileNamePart('   ')).toBe('repo')
  })

  it('拼出 owner-name-report.md', () => {
    expect(reportFileName('octo cat', 'Hello/World')).toBe('octo-cat-Hello-World-report.md')
  })
})
