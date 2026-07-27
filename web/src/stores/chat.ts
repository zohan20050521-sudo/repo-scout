import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { sendChat } from '@/api/chat'
import type { ApiError} from '@/api/error';
import { toApiError } from '@/api/error'
import type { ChatMessage } from '@/types/chat'

let localIdSeed = 0
function nextLocalId(prefix: string): string {
  localIdSeed += 1
  return `${prefix}-${Date.now().toString(36)}-${localIdSeed}`
}

/**
 * 当前浏览器会话内的问答状态。
 * sessionId 由服务端下发：首轮带 repoId 绑定，后续只带 sessionId + message。
 */
export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const sessionId = ref<string | null>(null)
  const boundRepoId = ref<number | null>(null)
  const sending = ref(false)
  const lastError = ref<ApiError | null>(null)

  const hasConversation = computed(() => messages.value.length > 0)

  /** 只清空前端当前对话与 sessionId；后端没有删除会话 API */
  function resetConversation(): void {
    messages.value = []
    sessionId.value = null
    lastError.value = null
  }

  /** 切换仓库：整条会话失效，下一轮重新携带 repoId 绑定 */
  function bindRepo(repoId: number): void {
    if (boundRepoId.value !== repoId) {
      boundRepoId.value = repoId
      resetConversation()
    }
  }

  async function dispatch(userMessage: ChatMessage, repoId: number): Promise<boolean> {
    sending.value = true
    lastError.value = null
    userMessage.failed = false
    userMessage.errorMessage = undefined
    try {
      const response = await sendChat(
        sessionId.value
          ? { sessionId: sessionId.value, message: userMessage.content }
          : { message: userMessage.content, repoId },
      )
      sessionId.value = response.sessionId
      messages.value.push({
        id: nextLocalId('a'),
        role: 'assistant',
        content: response.answer,
        citations: response.citations ?? [],
        sources: response.sources ?? [],
        createdAt: Date.now(),
      })
      return true
    } catch (error) {
      const apiError = toApiError(error)
      lastError.value = apiError
      userMessage.failed = true
      userMessage.errorMessage = apiError.message
      return false
    } finally {
      sending.value = false
    }
  }

  /** 发送一条新用户消息 */
  async function send(text: string, repoId: number): Promise<boolean> {
    const content = text.trim()
    if (!content || sending.value) return false
    const userMessage: ChatMessage = {
      id: nextLocalId('u'),
      role: 'user',
      content,
      createdAt: Date.now(),
    }
    messages.value.push(userMessage)
    return dispatch(userMessage, repoId)
  }

  /** 重试失败的那条用户消息：复用原消息对象，不重复插入 */
  async function retry(messageId: string, repoId: number): Promise<boolean> {
    if (sending.value) return false
    const target = messages.value.find((item) => item.id === messageId && item.role === 'user')
    if (!target) return false
    return dispatch(target, repoId)
  }

  return {
    messages,
    sessionId,
    boundRepoId,
    sending,
    lastError,
    hasConversation,
    resetConversation,
    bindRepo,
    send,
    retry,
  }
})
