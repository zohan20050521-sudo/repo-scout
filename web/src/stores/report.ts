import { defineStore } from 'pinia'
import { ref } from 'vue'
import { generateReport } from '@/api/report'
import type { ApiError} from '@/api/error';
import { toApiError } from '@/api/error'
import type { ReportResult } from '@/types/api'

/** 报告只保留当前前端会话，后端无报告历史 API */
export const useReportStore = defineStore('report', () => {
  const report = ref<ReportResult | null>(null)
  const reportRepoId = ref<number | null>(null)
  const generating = ref(false)
  const lastError = ref<ApiError | null>(null)

  /** 失败时保留旧报告，避免一次失败清空用户已有内容 */
  async function generate(repoId: number): Promise<ReportResult | null> {
    if (generating.value) return null
    generating.value = true
    lastError.value = null
    try {
      const result = await generateReport(repoId)
      report.value = result
      reportRepoId.value = repoId
      return result
    } catch (error) {
      lastError.value = toApiError(error)
      return null
    } finally {
      generating.value = false
    }
  }

  /** 切换仓库时丢弃上一个仓库的报告，避免张冠李戴 */
  function resetForRepo(repoId: number): void {
    if (reportRepoId.value !== repoId) {
      report.value = null
      reportRepoId.value = null
      lastError.value = null
    }
  }

  return { report, reportRepoId, generating, lastError, generate, resetForRepo }
})
