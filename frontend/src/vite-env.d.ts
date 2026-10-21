

// 由 vite.config.ts 的 define 在构建期替换（见那里的注释）
declare const __APP_VERSION__: string
declare const __BUILD_TIME__: string
/** 空串表示「这个发行版没有配置更新源」。 */
declare const __UPDATE_MANIFEST__: string
