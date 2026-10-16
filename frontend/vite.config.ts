import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Tauri 在开发时把 devUrl 写死成 1420，所以这里必须 strictPort：
// 端口被占时宁可响亮失败，也不要静默换端口——那会让外壳连到一个不存在的地址。
export default defineConfig({
  plugins: [vue()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: '127.0.0.1',
    watch: {
      // src-tauri 由 cargo 自己监听，Vite 不用管
      ignored: ['**/src-tauri/**'],
    },
  },
})
