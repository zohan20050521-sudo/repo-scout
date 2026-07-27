import { request } from './client'
import type { CreateRepoRequest, IndexResult, IndexStatus, RepoSummary } from '@/types/api'

/** GET /api/repos —— 已接入仓库列表，按 id 倒序，一期不分页 */
export function listRepos(): Promise<RepoSummary[]> {
  return request<RepoSummary[]>({ url: '/repos', method: 'GET' })
}

/** POST /api/repos —— 接入仓库，重复接入幂等返回同 id */
export function createRepo(repo: string): Promise<RepoSummary> {
  const body: CreateRepoRequest = { repo }
  return request<RepoSummary>({ url: '/repos', method: 'POST', data: body })
}

/** GET /api/repos/{id} */
export function getRepo(id: number): Promise<RepoSummary> {
  return request<RepoSummary>({ url: `/repos/${id}`, method: 'GET' })
}

/** GET /api/repos/{id}/index-status */
export function getIndexStatus(id: number): Promise<IndexStatus> {
  return request<IndexStatus>({ url: `/repos/${id}/index-status`, method: 'GET' })
}

/** POST /api/repos/{id}/index —— 同步长请求，幂等重建 */
export function buildIndex(id: number): Promise<IndexResult> {
  return request<IndexResult>({ url: `/repos/${id}/index`, method: 'POST' })
}
