/**
 * 与 docs/api.md（v0.6.0）一一对应的接口类型。
 * 成功响应直接是资源 JSON，不存在 { code, data, msg } 包装。
 */

/** GET /api/repos/{id}、POST /api/repos、GET /api/repos 的元素结构 */
export interface RepoSummary {
  id: number
  owner: string
  name: string
  defaultBranch: string
  /** 仓库无描述时为 null */
  description: string | null
  htmlUrl: string
  createdAt: string
  updatedAt: string
}

/** POST /api/repos 请求体 */
export interface CreateRepoRequest {
  /** owner/repo 或 https://github.com/owner/repo（允许尾部 / 或 .git） */
  repo: string
}

/** GET /api/repos/{id}/index-status */
export interface IndexStatus {
  repoId: number
  /** indexed = chunkCount > 0 */
  indexed: boolean
  fileCount: number
  chunkCount: number
  /** 未索引为 null */
  indexedAt: string | null
  /** 当前或最近一次索引任务；从未提交或状态过期时为 null */
  task?: IndexTask | null
}

export type IndexJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** GET /api/repos/{id}/index-status 中的任务对象 */
export interface IndexTask {
  jobId: string
  repoId: number
  status: IndexJobStatus
  errorCode: ApiErrorCode | null
  errorMessage: string | null
  fileCount: number | null
  chunkCount: number | null
  costMs: number | null
  startedAt: string | null
  finishedAt: string | null
}

/** POST /api/repos/{id}/index：短请求只返回任务资源 */
export interface IndexJobResponse {
  repoId: number
  jobId: string
  status: IndexJobStatus
}

/** 旧调用方名称保留为类型别名，字段已切换为异步任务响应。 */
export type IndexResult = IndexJobResponse

/** POST /api/chat 请求体 */
export interface ChatRequest {
  /** 标准 UUID；缺省时服务端生成新会话 */
  sessionId?: string
  /** 非空，长度 ≤ CHAT_MESSAGE_MAX_LENGTH（默认 4000） */
  message: string
  /** 首次绑定仓库时携带；同一会话后续可省略 */
  repoId?: number
}

/** POST /api/chat 响应中的结构化引用 */
export interface Citation {
  filePath: string
  /** 文件内从 0 开始的块序号 */
  chunkIndex: number
  /** 注入模型的完整 chunk 文本 */
  excerpt: string
  /** 检索相似度原始 double 数值 */
  score: number
  /** GitHub blob 链接 */
  url: string
}

/** POST /api/chat 响应 */
export interface ChatResponse {
  sessionId: string
  answer: string
  /** 旧调用方兼容字段：来源文件路径，永不为 null */
  sources: string[]
  /** 面向引用卡片的结构化引用，永不为 null */
  citations: Citation[]
}

/** POST /api/repos/{id}/report */
export interface ReportResult {
  repoId: number
  generatedAt: string
  costMs: number
  /** 五个固定二级小节的 Markdown 全文 */
  report: string
}

/** 统一错误响应体 */
export interface ApiErrorBody {
  code: string
  message: string
}

/** docs/api.md 错误码表 */
export const API_ERROR_CODES = [
  'INVALID_PARAM',
  'LLM_UNAVAILABLE',
  'INTERNAL_ERROR',
  'REPO_NOT_FOUND',
  'GITHUB_UNAVAILABLE',
  'UNAUTHORIZED',
] as const

export type ApiErrorCode = (typeof API_ERROR_CODES)[number]

/** 请求层为「无法解析后端结构化错误」的场景补充的本地判别码 */
export type LocalErrorCode = 'NETWORK_ERROR' | 'REQUEST_CANCELED' | 'UNKNOWN_ERROR'

export type ErrorCode = ApiErrorCode | LocalErrorCode
