import { request } from './client'
import type { ChatRequest, ChatResponse } from '@/types/api'

/**
 * POST /api/chat
 * 首轮携带 repoId 完成绑定；服务端返回 sessionId 后，后续只带 sessionId + message。
 */
export function sendChat(payload: ChatRequest): Promise<ChatResponse> {
  return request<ChatResponse>({ url: '/chat', method: 'POST', data: payload })
}
