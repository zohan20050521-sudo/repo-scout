import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 浏览器只请求同源 /api：开发期由 Vite proxy 转发到本地后端，
// 生产由同源服务端代理承担（并在那里注入内部密钥，前端永不持有）。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const target = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      chunkSizeWarningLimit: 900,
    },
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['tests/**/*.spec.ts'],
      setupFiles: ['tests/setup.ts'],
      restoreMocks: true,
    },
  }
})
