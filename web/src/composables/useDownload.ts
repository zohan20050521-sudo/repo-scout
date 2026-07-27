/** 把任意字符串安全化为文件名片段：只留字母数字与 - _ . */
export function safeFileNamePart(input: string): string {
  const normalized = input
    .trim()
    .replace(/[^A-Za-z0-9._-]+/g, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^[-.]+|[-.]+$/g, '')
  return normalized || 'repo'
}

/** 报告下载文件名：owner-name-report.md */
export function reportFileName(owner: string, name: string): string {
  return `${safeFileNamePart(owner)}-${safeFileNamePart(name)}-report.md`
}

/** 触发一次本地文本文件下载 */
export function downloadTextFile(fileName: string, content: string, mime = 'text/markdown'): void {
  const blob = new Blob([content], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  // 交回内存：立即 revoke 在部分浏览器会中断下载，延后一拍
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
