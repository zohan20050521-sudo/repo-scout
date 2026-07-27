import { ref } from 'vue'

/**
 * 复制到剪贴板。Clipboard API 不可用或被拒时返回 false 并暴露原因，
 * 由调用方给出可见的错误反馈，不静默失败。
 */
export function useClipboard() {
  const copying = ref(false)
  const lastError = ref<string | null>(null)

  async function copy(text: string): Promise<boolean> {
    copying.value = true
    lastError.value = null
    try {
      const clipboard = navigator.clipboard
      if (!clipboard || typeof clipboard.writeText !== 'function') {
        lastError.value = '当前浏览器或非安全上下文不支持剪贴板写入，请手动选中复制'
        return false
      }
      await clipboard.writeText(text)
      return true
    } catch (error) {
      lastError.value =
        error instanceof Error && error.message
          ? `复制失败：${error.message}`
          : '复制失败，请手动选中复制'
      return false
    } finally {
      copying.value = false
    }
  }

  return { copy, copying, lastError }
}
