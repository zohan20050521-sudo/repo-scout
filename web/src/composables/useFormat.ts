/** 后端时间为 LocalDateTime（无时区），按本地时间直接读，不做时区换算 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const normalized = value.replace(' ', 'T')
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 相对时间：列表里比绝对时间更好读，超过 30 天回退到日期 */
export function formatRelative(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value.replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return value
  const diffMs = Date.now() - date.getTime()
  if (diffMs < 0) return formatDateTime(value)
  const minutes = Math.floor(diffMs / 60_000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days <= 30) return `${days} 天前`
  return formatDateTime(value)
}

/** 耗时毫秒转易读文本 */
export function formatCost(costMs: number): string {
  if (!Number.isFinite(costMs) || costMs < 0) return '—'
  if (costMs < 1000) return `${Math.round(costMs)} 毫秒`
  const seconds = costMs / 1000
  // 60 秒内保留一位小数：索引/报告耗时的量级差异对用户是有意义的信息
  if (seconds < 60) return `${seconds.toFixed(1).replace(/\.0$/, '')} 秒`
  const minutes = Math.floor(seconds / 60)
  return `${minutes} 分 ${Math.round(seconds % 60)} 秒`
}

/**
 * 检索得分展示：保留数值语义（三位小数）并给一个可读的相关度档位。
 * 不把原始 double 改写成百分比，避免暗示后端返回的是概率。
 */
export function formatScore(score: number): string {
  if (!Number.isFinite(score)) return '—'
  return score.toFixed(3)
}

export function scoreLevel(score: number): 'high' | 'medium' | 'low' {
  if (score >= 0.75) return 'high'
  if (score >= 0.6) return 'medium'
  return 'low'
}

export function scoreLevelLabel(score: number): string {
  const level = scoreLevel(score)
  if (level === 'high') return '强相关'
  if (level === 'medium') return '相关'
  return '弱相关'
}
