/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 开发期 Vite proxy 的后端目标地址，仅本地非敏感值 */
  readonly VITE_DEV_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
