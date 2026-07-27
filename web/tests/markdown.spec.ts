import { describe, expect, it } from 'vitest'
import { extractHeadings, renderMarkdown, renderMarkdownWithAnchors } from '@/composables/useMarkdown'

describe('安全 Markdown 渲染', () => {
  it('原始 HTML 被转义，不进入 DOM', () => {
    const html = renderMarkdown('正常文本 <img src=x onerror="alert(1)"> 结束')
    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img')
    expect(html).not.toContain('onerror="')
  })

  it('script 标签不会被透传', () => {
    const html = renderMarkdown('<script>window.__pwned = true</script>')
    expect(html).not.toMatch(/<script/i)
    expect(html).toContain('&lt;script&gt;')
  })

  it('javascript: 链接不会生成可点击的 <a>', () => {
    const html = renderMarkdown('[点我](javascript:alert(1))')
    // markdown-it 的 validateLink 直接拒绝该协议，连 link token 都不产生
    expect(html).not.toMatch(/<a\s/)
    expect(html).not.toContain('href')
  })

  it('data: 与 vbscript: 协议同样不会生成链接', () => {
    for (const source of ['[x](data:text/html;base64,PHNjcmlwdD4=)', '[x](vbscript:msgbox(1))']) {
      const html = renderMarkdown(source)
      expect(html).not.toMatch(/<a\s/)
    }
  })

  it('markdown-it 放行但不在白名单内的协议被降级为不可点击', () => {
    // ftp: 能通过 markdown-it 的 validateLink，由本项目的 href 白名单兜底拦下
    const html = renderMarkdown('[x](ftp://a/b)')
    expect(html).not.toContain('ftp://a/b')
    expect(html).toContain('data-rs-blocked-link="true"')
    expect(html).toContain('href="#"')
  })

  it('站内相对链接与锚点保持可用', () => {
    const html = renderMarkdown('[y](/local/path)')
    expect(html).toContain('href="/local/path"')
    expect(html).not.toContain('data-rs-blocked-link')
  })

  it('正常链接带安全属性', () => {
    const html = renderMarkdown('[api](https://github.com/a/b/blob/main/docs/api.md)')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer nofollow"')
    expect(html).toContain('href="https://github.com/a/b/blob/main/docs/api.md"')
  })

  it('常见 Markdown 结构都能渲染', () => {
    const source = [
      '# 标题',
      '',
      '- 列表项一',
      '- 列表项二',
      '',
      '1. 有序一',
      '',
      '> 引用内容',
      '',
      '行内 `code` 片段',
      '',
      '| 列 A | 列 B |',
      '| --- | --- |',
      '| 1 | 2 |',
      '',
      '```java',
      'class Foo {}',
      '```',
    ].join('\n')
    const html = renderMarkdown(source)
    expect(html).toContain('<h1>标题</h1>')
    expect(html).toContain('<ul>')
    expect(html).toContain('<ol>')
    expect(html).toContain('<blockquote>')
    expect(html).toContain('<code>code</code>')
    expect(html).toContain('rs-table-scroll')
    expect(html).toContain('<table>')
    expect(html).toContain('class="hljs language-java"')
    expect(html).toContain('hljs-keyword')
  })

  it('未知语言的代码块仅转义不高亮', () => {
    const html = renderMarkdown('```notalang\n<b>x</b>\n```')
    expect(html).toContain('&lt;b&gt;x&lt;/b&gt;')
    expect(html).not.toContain('<b>x</b>')
  })

  it('空输入返回空字符串', () => {
    expect(renderMarkdown('')).toBe('')
  })
})

describe('报告本地锚点目录', () => {
  const report = [
    '## 项目定位',
    '内容',
    '## 技术栈',
    '```',
    '## 这是代码块里的伪标题',
    '```',
    '## 目录结构解读',
    '## 上手指引',
    '## 近期动向',
  ].join('\n')

  it('只抽取真实二级标题，跳过代码块内文本', () => {
    const headings = extractHeadings(report)
    expect(headings.map((item) => item.text)).toEqual([
      '项目定位',
      '技术栈',
      '目录结构解读',
      '上手指引',
      '近期动向',
    ])
  })

  it('渲染结果里的标题带上与目录一致的 id', () => {
    const headings = extractHeadings(report)
    const html = renderMarkdownWithAnchors(report)
    for (const heading of headings) {
      expect(html).toContain(`<h2 id="${heading.anchor}">`)
    }
  })

  it('重复标题生成唯一锚点', () => {
    const headings = extractHeadings('## 同名\n## 同名')
    expect(headings[0].anchor).not.toBe(headings[1].anchor)
  })
})
