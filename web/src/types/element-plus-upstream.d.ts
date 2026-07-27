/**
 * element-plus 2.13.0 的上游 d.ts 缺陷补丁，与业务代码无关。
 * 这里按 Vue 的真实语义补声明，而不是用 skipLibCheck 全局放行类型检查。
 *
 * 1. table-v2 的类型引用 `JSX.IntrinsicAttributes['class']`，但 Vue 的全局 JSX
 *    声明里 IntrinsicAttributes 只有 key/ref 等保留属性，没有 class。
 * 2. `element-plus/es/utils/vue/icon.d.ts` 从 `element-plus/es/icons-vue` 导入
 *    Loading，而该子路径在发布产物里并不存在（图标实际来自 @element-plus/icons-vue）。
 *
 * TODO: element-plus 修复上述类型后删除本文件（跟踪 table-v2 / icons-vue 子路径导出）。
 * 本文件保持为 ambient 声明（无顶层 import/export），否则 declare module 会退化成模块增强。
 */

declare namespace JSX {
  interface IntrinsicAttributes {
    class?: unknown
  }
}

declare module 'element-plus/es/icons-vue' {
  // eslint-disable-next-line @typescript-eslint/consistent-type-imports -- ambient 文件不能有顶层 import，只能用 import() 内联（上游缺陷修补）
  export const Loading: import('vue').DefineComponent<Record<string, never>>
}
