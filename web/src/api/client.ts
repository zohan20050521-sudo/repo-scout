import axios, { type AxiosRequestConfig } from 'axios'
import { toApiError } from './error'

/**
 * 浏览器永远只请求同源 /api。
 * 内部共享密钥（X-Repo-Scout-Internal-Key）由同源服务端代理注入，
 * 前端代码、Vite 变量与 bundle 都不持有，因此此处不设任何鉴权头。
 */
export const http = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
  // 索引已异步化；普通接口短超时足以覆盖提交任务与状态轮询。
  timeout: 30_000,
})

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => Promise.reject(toApiError(error)),
)

/** 统一出口：直接返回后端资源 JSON，不做 { data } 包装兼容 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await http.request<T>(config)
    return response.data
  } catch (error) {
    throw toApiError(error)
  }
}
