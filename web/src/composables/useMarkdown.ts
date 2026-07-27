import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'

/**
 * 安全 Markdown 渲染。
 * - html: false —— 模型输出里的原始 HTML 一律按文本转义，不进入 DOM，避免 XSS
 * - linkify 出来的链接强制 target=_blank + rel=noopener noreferrer nofollow
 * - 代码块用 highlight.js 高亮后再交给 markdown-it，转义由本函数负责
 */
const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  typographer: false,
  highlight(code: string, lang: string): string {
    const language = lang && hljs.getLanguage(lang) ? lang : ''
    if (language) {
      try {
        const highlighted = hljs.highlight(code, { language, ignoreIllegals: true }).value
        return `<pre class="rs-code"><code class="hljs language-${md.utils.escapeHtml(language)}">${highlighted}</code></pre>`
      } catch {
        // 高亮失败退回纯转义，不影响正文可读
      }
    }
    return `<pre class="rs-code"><code class="hljs">${md.utils.escapeHtml(code)}</code></pre>`
  },
})

/** 仅允许安全协议的链接，其余（javascript:、data: 等）直接剥成纯文本 */
const SAFE_LINK_PATTERN = /^(https?:|mailto:|#|\/)/i

const defaultLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const href = token.attrGet('href') ?? ''
  if (!SAFE_LINK_PATTERN.test(href.trim())) {
    token.attrSet('href', '#')
    token.attrSet('data-rs-blocked-link', 'true')
  }
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer nofollow')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

/** 表格加一层容器，窄屏可横向滚动而不撑破布局 */
md.renderer.rules.table_open = () => '<div class="rs-table-scroll"><table>'
md.renderer.rules.table_close = () => '</table></div>'

/** 渲染 Markdown 为可安全注入的 HTML 字符串 */
export function renderMarkdown(source: string): string {
  if (!source) return ''
  return md.render(source)
}

export interface MarkdownHeading {
  level: number
  text: string
  anchor: string
}

/** 从 Markdown 源码解析二级标题，用于报告本地锚点目录（不伪造后端目录数据） */
export function extractHeadings(source: string, levels: number[] = [2]): MarkdownHeading[] {
  const headings: MarkdownHeading[] = []
  const seen = new Map<string, number>()
  let inFence = false

  for (const rawLine of source.split('\n')) {
    const line = rawLine.trimEnd()
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence
      continue
    }
    if (inFence) continue

    const match = /^(#{1,6})\s+(.+?)\s*#*$/.exec(line)
    if (!match) continue
    const level = match[1].length
    if (!levels.includes(level)) continue

    const text = match[2].replace(/[*`_]/g, '').trim()
    if (!text) continue
    const base = `rs-h-${text.replace(/\s+/g, '-').toLowerCase()}`
    const count = seen.get(base) ?? 0
    seen.set(base, count + 1)
    headings.push({ level, text, anchor: count === 0 ? base : `${base}-${count}` })
  }

  return headings
}

/** 给渲染结果里的标题补上与 extractHeadings 一致的 id，实现本地锚点跳转 */
export function renderMarkdownWithAnchors(source: string, levels: number[] = [2]): string {
  const headings = extractHeadings(source, levels)
  let html = renderMarkdown(source)
  let cursor = 0

  for (const heading of headings) {
    const openTag = `<h${heading.level}>`
    const index = html.indexOf(openTag, cursor)
    if (index === -1) continue
    const replacement = `<h${heading.level} id="${heading.anchor}">`
    html = html.slice(0, index) + replacement + html.slice(index + openTag.length)
    cursor = index + replacement.length
  }

  return html
}
