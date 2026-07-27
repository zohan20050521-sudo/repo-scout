import { config } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { vi } from 'vitest'

config.global.plugins = [ElementPlus]

// jsdom 未实现的浏览器 API：组件挂载时会用到
if (!window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia
}

if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = vi.fn()
}
