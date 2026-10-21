import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  /**
   * 与 vite.config.ts 的 `define` 保持一致。
   *
   * 测试用的是这份配置，而不是 vite.config.ts —— 所以构建期注入的那几个常量
   * 在测试里默认**不存在**，任何用到它们的模块一 import 就抛
   * `ReferenceError: __APP_VERSION__ is not defined`（2026-10-21 实测）。
   *
   * 这里给的是**固定的假值**，不是真实值：版本比较的用例要能断言具体结果
   * （0.1.0 vs 99.0.0），真实版本每发一版都变，断言就会跟着抖。
   */
  define: {
    __APP_VERSION__: JSON.stringify('0.1.0'),
    __BUILD_TIME__: JSON.stringify('2026-10-21T18:54:00.000Z'),
    __UPDATE_MANIFEST__: JSON.stringify(''),
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
  },
})
