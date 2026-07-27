import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError, AxiosHeaders } from 'axios'
import { useChatStore } from '@/stores/chat'
import * as chatApi from '@/api/chat'
import type { ChatResponse } from '@/types/api'

const SESSION = '0f14d0ab-9605-4a62-a9e4-5ed26688389b'

function response(overrides: Partial<ChatResponse> = {}): ChatResponse {
  return {
    sessionId: SESSION,
    answer: '这是回答',
    sources: [],
    citations: [],
    ...overrides,
  }
}

function llmUnavailable(): AxiosError {
  const config = { headers: new AxiosHeaders() }
  const error = new AxiosError('bad gateway', 'ERR_BAD_RESPONSE', config)
  error.response = {
    status: 502,
    statusText: '',
    data: { code: 'LLM_UNAVAILABLE', message: '模型服务超时' },
    headers: new AxiosHeaders(),
    config,
  }
  return error
}

describe('对话请求语义', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('首轮携带 repoId 且不带 sessionId', async () => {
    const send = vi.spyOn(chatApi, 'sendChat').mockResolvedValue(response())
    const store = useChatStore()
    await store.send('怎么跑起来', 7)

    expect(send).toHaveBeenCalledWith({ message: '怎么跑起来', repoId: 7 })
    expect(store.sessionId).toBe(SESSION)
    expect(store.messages).toHaveLength(2)
    expect(store.messages[0].role).toBe('user')
    expect(store.messages[1].role).toBe('assistant')
  })

  it('后续轮只带 sessionId + message，不再带 repoId', async () => {
    const send = vi.spyOn(chatApi, 'sendChat').mockResolvedValue(response())
    const store = useChatStore()
    await store.send('第一问', 7)
    await store.send('第二问', 7)

    expect(send).toHaveBeenLastCalledWith({ sessionId: SESSION, message: '第二问' })
  })

  it('新对话只清空前端会话，下一轮重新携带 repoId', async () => {
    const send = vi.spyOn(chatApi, 'sendChat').mockResolvedValue(response())
    const store = useChatStore()
    await store.send('第一问', 7)
    store.resetConversation()

    expect(store.messages).toHaveLength(0)
    expect(store.sessionId).toBeNull()

    await store.send('新一轮', 7)
    expect(send).toHaveBeenLastCalledWith({ message: '新一轮', repoId: 7 })
  })

  it('切换仓库时丢弃旧会话，避免绑定冲突', async () => {
    vi.spyOn(chatApi, 'sendChat').mockResolvedValue(response())
    const store = useChatStore()
    store.bindRepo(7)
    await store.send('第一问', 7)
    store.bindRepo(8)

    expect(store.messages).toHaveLength(0)
    expect(store.sessionId).toBeNull()
  })

  it('发送中不重复发起请求', async () => {
    let resolve: ((value: ChatResponse) => void) | undefined
    const send = vi
      .spyOn(chatApi, 'sendChat')
      .mockReturnValue(new Promise<ChatResponse>((r) => (resolve = r)))
    const store = useChatStore()
    const first = store.send('问题', 7)
    await store.send('又一次', 7)
    expect(send).toHaveBeenCalledTimes(1)
    resolve?.(response())
    await first
  })

  it('失败时标记该条用户消息，重试成功不重复插入用户消息', async () => {
    const send = vi
      .spyOn(chatApi, 'sendChat')
      .mockRejectedValueOnce(llmUnavailable())
      .mockResolvedValue(response())

    const store = useChatStore()
    await store.send('怎么跑起来', 7)

    expect(store.messages).toHaveLength(1)
    expect(store.messages[0].failed).toBe(true)
    expect(store.messages[0].errorMessage).toBe('模型服务超时')
    expect(store.lastError?.code).toBe('LLM_UNAVAILABLE')

    const ok = await store.retry(store.messages[0].id, 7)
    expect(ok).toBe(true)
    expect(send).toHaveBeenCalledTimes(2)
    expect(store.messages.filter((m) => m.role === 'user')).toHaveLength(1)
    expect(store.messages[0].failed).toBe(false)
    expect(store.messages[1].role).toBe('assistant')
  })

  it('重试失败的首轮仍按首轮语义携带 repoId', async () => {
    const send = vi
      .spyOn(chatApi, 'sendChat')
      .mockRejectedValueOnce(llmUnavailable())
      .mockResolvedValue(response())
    const store = useChatStore()
    await store.send('怎么跑起来', 7)
    await store.retry(store.messages[0].id, 7)

    expect(send).toHaveBeenLastCalledWith({ message: '怎么跑起来', repoId: 7 })
  })

  it('空白消息不发送', async () => {
    const send = vi.spyOn(chatApi, 'sendChat').mockResolvedValue(response())
    const store = useChatStore()
    expect(await store.send('   ', 7)).toBe(false)
    expect(send).not.toHaveBeenCalled()
  })

  it('citations 与 sources 按轮保存，不跨轮混合', async () => {
    vi.spyOn(chatApi, 'sendChat')
      .mockResolvedValueOnce(
        response({
          sources: ['docs/api.md'],
          citations: [
            {
              filePath: 'docs/api.md',
              chunkIndex: 3,
              excerpt: '统一错误响应结构',
              score: 0.806,
              url: 'https://github.com/o/r/blob/main/docs/api.md',
            },
          ],
        }),
      )
      .mockResolvedValueOnce(response())

    const store = useChatStore()
    await store.send('第一问', 7)
    await store.send('第二问', 7)

    const assistants = store.messages.filter((m) => m.role === 'assistant')
    expect(assistants[0].citations).toHaveLength(1)
    expect(assistants[0].sources).toEqual(['docs/api.md'])
    expect(assistants[1].citations).toEqual([])
  })
})
