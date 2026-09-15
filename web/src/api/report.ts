import { request } from './client'
import type { ReportResult } from '@/types/api'

/** 报告包含 GitHub 取数与 LLM 生成，允许覆盖后端与代理的长请求窗口。 */
const REPORT_TIMEOUT_MS = 180_000

/** POST /api/repos/{id}/report —— 同步长请求，单次 LLM 生成五节报告 */
export function generateReport(id: number): Promise<ReportResult> {
  return request<ReportResult>({
    url: `/repos/${id}/report`,
    method: 'POST',
    timeout: REPORT_TIMEOUT_MS,
  })
}
