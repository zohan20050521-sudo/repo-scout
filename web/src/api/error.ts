import axios from 'axios'
import type { ApiErrorBody, ErrorCode } from '@/types/api'

/**
 * 请求层统一错误：把 HTTP 状态、后端 code/message 收敛成可判别对象，
 * 组件不再各自解析 AxiosError。
 */
export class ApiError extends Error {
  readonly code: ErrorCode
  /** 无 HTTP 响应（网络错误、取消）时为 null */
  readonly status: number | null
  /** 后端是否返回了结构化 { code, message } */
  readonly fromServer: boolean

  constructor(code: ErrorCode, message: string, status: number | null, fromServer: boolean) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.fromServer = fromServer
  }
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) return false
  const body = value as Record<string, unknown>
  return typeof body.code === 'string' && typeof body.message === 'string'
}

/** 401 的文案由前端决定：不引导访客输入内部 key */
const UNAUTHORIZED_MESSAGE = '演示服务访问配置异常，请联系维护者或稍后重试'

const FALLBACK_MESSAGE: Record<ErrorCode, string> = {
  INVALID_PARAM: '请求参数不合法，请检查输入后重试',
  LLM_UNAVAILABLE: '模型服务暂时不可用，请稍后重试',
  INTERNAL_ERROR: '服务内部错误，请稍后重试',
  REPO_NOT_FOUND: '仓库未接入或不存在',
  GITHUB_UNAVAILABLE: 'GitHub 接口暂时不可用，请稍后重试',
  UNAUTHORIZED: UNAUTHORIZED_MESSAGE,
  NETWORK_ERROR: '网络连接失败，请检查网络后重试',
  REQUEST_CANCELED: '请求已取消',
  UNKNOWN_ERROR: '请求失败，请稍后重试',
}

function codeFromStatus(status: number): ErrorCode {
  if (status === 400) return 'INVALID_PARAM'
  if (status === 401) return 'UNAUTHORIZED'
  if (status === 404) return 'REPO_NOT_FOUND'
  if (status === 502) return 'GITHUB_UNAVAILABLE'
  if (status >= 500) return 'INTERNAL_ERROR'
  return 'UNKNOWN_ERROR'
}

/** 把任意抛出物规范化为 ApiError */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  if (axios.isCancel(error)) {
    return new ApiError('REQUEST_CANCELED', FALLBACK_MESSAGE.REQUEST_CANCELED, null, false)
  }

  if (axios.isAxiosError(error)) {
    const response = error.response
    if (!response) {
      return new ApiError('NETWORK_ERROR', FALLBACK_MESSAGE.NETWORK_ERROR, null, false)
    }
    if (isApiErrorBody(response.data)) {
      const code = response.data.code as ErrorCode
      // 401 永不透出后端 message，避免暗示访客补 key
      const message =
        code === 'UNAUTHORIZED'
          ? UNAUTHORIZED_MESSAGE
          : response.data.message.trim() || FALLBACK_MESSAGE[code] || FALLBACK_MESSAGE.UNKNOWN_ERROR
      return new ApiError(code, message, response.status, true)
    }
    const code = codeFromStatus(response.status)
    return new ApiError(code, FALLBACK_MESSAGE[code], response.status, false)
  }

  const message = error instanceof Error && error.message ? error.message : FALLBACK_MESSAGE.UNKNOWN_ERROR
  return new ApiError('UNKNOWN_ERROR', message, null, false)
}

/** 按错误码给出适度上下文补充，用于页面级错误块的第二行说明 */
export function errorHint(error: ApiError): string {
  switch (error.code) {
    case 'UNAUTHORIZED':
      return '后端已开启内部访问门禁，需由同源服务端代理注入密钥；请勿在页面填写任何密钥。'
    case 'GITHUB_UNAVAILABLE':
      return 'GitHub 接口可能限流或网络不通，稍后重试通常可恢复。'
    case 'LLM_UNAVAILABLE':
      return '上游模型超时、限流或鉴权失败，可稍后重试。'
    case 'REPO_NOT_FOUND':
      return '请确认仓库为公开仓库且地址正确；私有仓库暂不支持。'
    case 'INVALID_PARAM':
      return '请按 owner/repo 或完整 GitHub 地址填写。'
    case 'NETWORK_ERROR':
      return '未收到服务端响应，请确认后端服务与网络可用。'
    case 'INTERNAL_ERROR':
      return '服务端处理异常，重试仍失败请联系维护者。'
    default:
      return ''
  }
}
