import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CitationCard from '@/components/CitationCard.vue'
import ChatMessageItem from '@/components/ChatMessageItem.vue'
import type { Citation } from '@/types/api'
import type { ChatMessage } from '@/types/chat'

const longExcerpt = '统一错误响应结构说明。'.repeat(30)

const citation: Citation = {
  filePath: 'docs/api.md',
  chunkIndex: 3,
  excerpt: longExcerpt,
  score: 0.806,
  url: 'https://github.com/zohan20050521-sudo/repo-scout/blob/main/docs/api.md',
}

function assistantMessage(citations: Citation[]): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: '回答正文',
    citations,
    sources: citations.map((item) => item.filePath),
    createdAt: Date.now(),
  }
}

describe('citation 卡片', () => {
  it('展示 filePath、chunkIndex、score 与摘录', () => {
    const wrapper = mount(CitationCard, { props: { citation } })
    const text = wrapper.text()
    expect(text).toContain('docs/api.md')
    expect(text).toContain('块 #3')
    expect(text).toContain('0.806')
    expect(text).toContain('强相关')
    expect(text).toContain('统一错误响应结构说明')
  })

  it('长摘录默认折叠，可展开为完整文本', async () => {
    const wrapper = mount(CitationCard, { props: { citation } })
    expect(wrapper.text()).toContain('…')
    expect(wrapper.text().length).toBeLessThan(longExcerpt.length)

    await wrapper.find('.rs-citation__toggle').trigger('click')
    expect(wrapper.find('.rs-citation__excerpt').text()).toBe(longExcerpt)
    expect(wrapper.text()).toContain('收起摘录')
  })

  it('短摘录不显示折叠按钮', () => {
    const wrapper = mount(CitationCard, {
      props: { citation: { ...citation, excerpt: '很短的摘录' } },
    })
    expect(wrapper.find('.rs-citation__toggle').exists()).toBe(false)
    expect(wrapper.text()).toContain('很短的摘录')
  })

  it('外链带安全属性并指向 citation.url', () => {
    const wrapper = mount(CitationCard, { props: { citation } })
    const link = wrapper.find('.rs-citation__link')
    expect(link.attributes('href')).toBe(citation.url)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
  })

  it('score 展示保留数值语义，不改写为百分比', () => {
    const wrapper = mount(CitationCard, { props: { citation: { ...citation, score: 0.52 } } })
    expect(wrapper.text()).toContain('0.520')
    expect(wrapper.text()).toContain('弱相关')
    expect(wrapper.text()).not.toContain('%')
  })
})

describe('assistant 消息里的 citations', () => {
  it('同一轮的引用挂在该轮消息下', () => {
    const wrapper = mount(ChatMessageItem, {
      props: { message: assistantMessage([citation]), retrying: false },
    })
    expect(wrapper.text()).toContain('本轮引用 1 处仓库文档')
    expect(wrapper.findAllComponents(CitationCard)).toHaveLength(1)
  })

  it('citations 为空数组时不渲染引用区，也不造「已引用」标签', () => {
    const wrapper = mount(ChatMessageItem, {
      props: { message: assistantMessage([]), retrying: false },
    })
    expect(wrapper.findAllComponents(CitationCard)).toHaveLength(0)
    expect(wrapper.text()).not.toContain('引用')
  })

  it('用户消息以纯文本渲染，不解析 Markdown/HTML', () => {
    const wrapper = mount(ChatMessageItem, {
      props: {
        message: {
          id: 'u-1',
          role: 'user',
          content: '<b>加粗</b> **也不解析**',
          createdAt: Date.now(),
        },
        retrying: false,
      },
    })
    expect(wrapper.find('.rs-msg__text').text()).toBe('<b>加粗</b> **也不解析**')
    expect(wrapper.html()).not.toContain('<b>加粗</b>')
  })

  it('失败消息提供重试按钮并抛出 retry 事件', async () => {
    const wrapper = mount(ChatMessageItem, {
      props: {
        message: {
          id: 'u-9',
          role: 'user',
          content: '问题',
          createdAt: Date.now(),
          failed: true,
          errorMessage: '模型服务超时',
        },
        retrying: false,
      },
    })
    expect(wrapper.text()).toContain('模型服务超时')
    await wrapper.find('.rs-msg__failed button').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([['u-9']])
  })
})
