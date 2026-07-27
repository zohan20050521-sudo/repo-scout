import type { Citation } from './api'

export type ChatMessageRole = 'user' | 'assistant'

/** 前端会话内的一条消息；只存在于当前浏览器会话，后端无会话列表 API */
export interface ChatMessage {
  /** 前端本地 id，仅用于列表 key 与重试定位 */
  id: string
  role: ChatMessageRole
  content: string
  /** assistant 消息：本轮实际注入的结构化引用，按轮独立 */
  citations?: Citation[]
  /** assistant 消息：本轮来源路径（兼容字段，UI 以 citations 为主） */
  sources?: string[]
  createdAt: number
  /** 本条 user 消息发送失败，可原地重试 */
  failed?: boolean
  /** 失败原因，展示在消息下方 */
  errorMessage?: string
}
