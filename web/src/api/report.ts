import { request } from './client'
import type { ReportResult } from '@/types/api'

/** POST /api/repos/{id}/report —— 同步长请求，单次 LLM 生成五节报告 */
export function generateReport(id: number): Promise<ReportResult> {
  return request<ReportResult>({ url: `/repos/${id}/report`, method: 'POST' })
}
