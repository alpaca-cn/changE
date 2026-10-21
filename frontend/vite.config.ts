import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const pkg = JSON.parse(readFileSync(resolve(here, 'package.json'), 'utf-8')) as { version: string }

/**
 * 更新源地址。**留空 = 没有更新源**，界面会如实说「还没配置」。
 *
 * 为什么是构建期常量而不是设置页里的一项：它属于「这个发行版从哪更新」，
 * 不是用户偏好。做成可配置只会多一个用户看不懂、填错了还不知道的输入框。
 *
 * 想看更新检查长什么样，填一个返回 `{version, notes?, url?}` 的地址再重新构建。
 */
const UPDATE_MANIFEST = ''


// Tauri 在开发时把 devUrl 写死成 1420，所以这里必须 strictPort：
// 端口被占时宁可响亮失败，也不要静默换端口——那会让外壳连到一个不存在的地址。
export default defineConfig({
  plugins: [vue()],
  define: {
    // 版本与构建时间在构建期钉死：运行期去问 Tauri 需要额外插件，
    // 而这两个值本来就只在「这份产物」里有意义。
    __APP_VERSION__: JSON.stringify(pkg.version),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
    __UPDATE_MANIFEST__: JSON.stringify(UPDATE_MANIFEST),
  },
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
