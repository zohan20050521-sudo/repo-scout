import { describe, expect, it } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { ApiError, errorHint, toApiError } from '@/api/error'

function axiosErrorWithResponse(status: number, data: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() }
  const error = new AxiosError('request failed', 'ERR_BAD_RESPONSE', config)
  error.response = {
    status,
    statusText: '',
    data,
    headers: new AxiosHeaders(),
    config,
  }
  return error
}

describe('请求层错误映射', () => {
  it('把后端 { code, message } 原样收敛为可判别错误', () => {
    const error = toApiError(
      axiosErrorWithResponse(404, { code: 'REPO_NOT_FOUND', message: '仓库未接入或不存在:id=9' }),
    )
    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('REPO_NOT_FOUND')
    expect(error.status).toBe(404)
    expect(error.fromServer).toBe(true)
    expect(error.message).toBe('仓库未接入或不存在:id=9')
  })

  it('INVALID_PARAM 400 保留后端可行动文案', () => {
    const error = toApiError(
      axiosErrorWithResponse(400, { code: 'INVALID_PARAM', message: 'repo 格式不合法' }),
    )
    expect(error.code).toBe('INVALID_PARAM')
    expect(error.message).toBe('repo 格式不合法')
  })

  it('502 GITHUB_UNAVAILABLE 与 LLM_UNAVAILABLE 不被吞掉', () => {
    const github = toApiError(
      axiosErrorWithResponse(502, { code: 'GITHUB_UNAVAILABLE', message: 'GitHub API 限流' }),
    )
    const llm = toApiError(
      axiosErrorWithResponse(502, { code: 'LLM_UNAVAILABLE', message: '模型超时' }),
    )
    expect(github.code).toBe('GITHUB_UNAVAILABLE')
    expect(github.status).toBe(502)
    expect(llm.code).toBe('LLM_UNAVAILABLE')
  })

  it('401 用演示服务配置异常文案，且不引导输入任何 key', () => {
    const error = toApiError(
      axiosErrorWithResponse(401, { code: 'UNAUTHORIZED', message: '无权访问该接口' }),
    )
    expect(error.code).toBe('UNAUTHORIZED')
    expect(error.message).toBe('演示服务访问配置异常，请联系维护者或稍后重试')
    const text = `${error.message}${errorHint(error)}`
    // 不泄露密钥名/请求头名，也不要求访客补 key；只明确劝阻在页面填写密钥
    expect(text).not.toContain('INTERNAL_API_KEY')
    expect(text).not.toContain('X-Repo-Scout-Internal-Key')
    expect(text).not.toMatch(/请(输入|填写|提供)/)
    expect(errorHint(error)).toContain('请勿在页面填写任何密钥')
  })

  it('无响应体时按 HTTP 状态兜底', () => {
    const error = toApiError(axiosErrorWithResponse(500, '<html>oops</html>'))
    expect(error.code).toBe('INTERNAL_ERROR')
    expect(error.fromServer).toBe(false)
    expect(error.message).toBe('服务内部错误，请稍后重试')
  })

  it('网络断开映射为 NETWORK_ERROR', () => {
    const error = toApiError(new AxiosError('Network Error', 'ERR_NETWORK'))
    expect(error.code).toBe('NETWORK_ERROR')
    expect(error.status).toBeNull()
  })

  it('未知抛出物映射为 UNKNOWN_ERROR', () => {
    expect(toApiError('boom').code).toBe('UNKNOWN_ERROR')
  })
})
