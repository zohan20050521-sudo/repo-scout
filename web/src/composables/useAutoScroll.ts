import { nextTick, onMounted, ref, type Ref } from 'vue'

/**
 * 消息区自动滚动：贴底时跟随新内容，用户主动上翻阅读时不抢滚动位置。
 */
export function useAutoScroll(container: Ref<HTMLElement | null>, threshold = 60) {
  const pinnedToBottom = ref(true)

  function isNearBottom(el: HTMLElement): boolean {
    return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold
  }

  function onScroll(): void {
    const el = container.value
    if (!el) return
    pinnedToBottom.value = isNearBottom(el)
  }

  async function scrollToBottom(force = false): Promise<void> {
    if (!force && !pinnedToBottom.value) return
    await nextTick()
    const el = container.value
    if (!el) return
    el.scrollTop = el.scrollHeight
    pinnedToBottom.value = true
  }

  onMounted(() => {
    const el = container.value
    if (el) el.addEventListener('scroll', onScroll, { passive: true })
  })

  return { pinnedToBottom, onScroll, scrollToBottom }
}
