# Tauri 外壳与 Vue 前端脚手架 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `changE` 第一次能被「看见」——双击/一条命令后出现一个深色窗口，它自己拉起 Java 后端、读到后端上报的随机端口、并显示「后端已连接 · 缓存：未启用」。

**Architecture:** Tauri 2 外壳（Rust）负责进程与窗口，Vue 3 SPA 负责界面，两者通过 Tauri 的 `invoke` 命令通信。外壳用标准库 `std::process::Command` 拉起后端 jar 并读取它的 stdout，解析出 `CHANGE_PORT=`；前端轮询一个 `get_backend_status` 命令，拿到端口后调用后端 `/api/health`。**不引入 Tauri shell 插件**——用 `std::process` 就够，且省掉插件能力（capabilities）配置这一层。

**Tech Stack:** Tauri 2 · Vue 3 + TypeScript + Vite · Pinia · Vue Router · Vitest · Rust（`std::process`）

---

## 为什么先做这一份

计划 1 的最终审查发现：**7 份计划里没有任何一份负责把外壳与前端建起来**。「外壳 / 前端 / Vue / Tauri」这些词在设计文档里出现 34 次、决策记录里 19 次，**但在 7 份计划的任何一行里都是 0 次**。

而计划 3 写的是「能记一次完整训练」——它默认外壳、前端、主题、路由都已经存在。计划 1 是纯后端，计划 2（动作库导入）也是纯后端。**所以现在全项目没有一行前端代码，也没有脚手架计划。**

这份计划补上它。做完之后：

- 第一次有能看见的东西
- 第一次把计划 1 建好的契约真的用起来（stdout 端口协商、`/api/health`、缓存状态）
- Tauri 那条工具链（Rust / WebView2 / 打包）的风险早点暴露

---

## 前置事实（已实测，不需要重新验证）

| 项 | 实测值 | 来源 |
|---|---|---|
| Tauri 全链路 | 2.12.3 编译 1.9 分钟，产出 12MB exe，无阻塞 | `113db89` 提交记录 |
| Rust | `E:\devtools\cargo\bin\{rustc,cargo,rustup}.exe`，`RUSTUP_HOME`/`CARGO_HOME` 均已设，`C:\Users\alpaca\.rustup` 与 `.cargo` 是指向 E 盘的 junction | 本机实测 |
| WebView2 | **155.0.4283.45** 已安装 | 注册表实测 |
| Node / pnpm | `E:\nodejs\node.exe` / `E:\npm-global\pnpm.ps1` | 本机实测 |
| npm 源 | 已切 `registry.npmmirror.com`（`npx` 曾报 `EE certificate key too weak`） | `113db89` |
| 后端 jar | `backend/target/change-backend-0.1.0-SNAPSHOT.jar`（79.1 MB） | 计划 1 |
| 后端协议行 | `CHANGE_PORT=<端口>`，Windows 上以 `\r\n` 结尾；实测 `b'CHANGE_PORT=54791\r\n'` = **19 字节**（17 可见 + `0x0d 0x0a`） | 本计划 Task 4 额外要求 C 实测（2026-10-16，OpenJDK 25.0.2） |
| 后端启动耗时 | 约 3.8–6.8 秒（取决于 Redis 探测） | 计划 1 实测 |

**⚠️ 一条来自设计文档的硬要求**：计划 1 实测后端启动要 **3.8 秒以上**，所以外壳**不能只是不显示窗口**——必须配一个加载提示。这就是 Task 5 存在的理由。

---

## 文件结构

```
frontend/                                  ← 新增，与 backend/ 平级
├── package.json
├── vite.config.ts
├── tsconfig.json                           ← 只有一份；不再用 project references
├── index.html
├── vitest.config.ts
├── README.md                               ← 怎么装/怎么测/怎么起/图标从哪来
├── pnpm-workspace.yaml                     ← 唯一的「不执行第三方构建脚本」声明处
├── scripts/
│   ├── make-icon.py                        ← ★ 图标源图的生成脚本（可复现）
│   └── icon-source.png                     ← ★ 1024×1024 源图，入库
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── styles/
│   │   └── theme.css                      ← 设计文档冻结的视觉 token，唯一来源
│   ├── router/
│   │   └── index.ts
│   ├── nav.ts                             ← 侧边栏分组的单一数据源
│   ├── components/
│   │   ├── AppSidebar.vue
│   │   └── BackendStatusBar.vue
│   ├── api/
│   │   └── backend.ts                     ← 包装 invoke 与 fetch
│   └── views/                             ← 占位页，每个都极小
│       ├── TodayView.vue
│       ├── TrainingView.vue
│       ├── DietView.vue
│       ├── BodyMapView.vue
│       ├── ProgressView.vue
│       ├── BodyDataView.vue
│       ├── AgentView.vue
│       ├── SettingsView.vue
│       └── AboutView.vue
└── src-tauri/                             ← Rust 外壳
    ├── Cargo.toml
    ├── build.rs
    ├── tauri.conf.json
    ├── capabilities/default.json
    ├── icons/                             ← 由 `tauri icon` 或脚手架自带
    └── src/
        ├── main.rs
        ├── lib.rs
        └── backend.rs                     ← ★ 端口协商，本计划最该测的单元
```

**为什么 `frontend/` 而不是把 `package.json` 放仓库根**：仓库根已经有 `backend/`，镜像出 `frontend/` 让两边对称；而且 Tauri 会把 `node_modules/`、`dist/`、`src-tauri/target/` 一堆东西生成在项目里，隔离在一个目录下更干净（`.gitignore` 也更好写）。

**为什么 `backend.rs` 单独成文件**：端口协商是本计划**唯一有真实逻辑**的地方（拉起子进程、逐行读 stdout、解析、超时、失败判定）。把它和 Tauri 的样板代码分开，才能被单元测试直接覆盖——不需要起 Tauri 运行时。

---

## 全局约定

承接计划 1，前端新增几条：

| 项 | 约定 |
|---|---|
| 前端根目录 | `frontend/` |
| 包管理 | **pnpm**（`E:\npm-global\pnpm.ps1`），不要用 npm/npx（`npx` 在这个环境报 `EE certificate key too weak`） |
| Vite 端口 | **1420 且 `strictPort: true`**——端口被占时**响亮失败**，不要静默换端口（`tauri.conf.json` 里的 `devUrl` 是写死的，静默换端口会让外壳连到一个不存在的地址） |
| 测试 | Vitest；组件测试用 `@vue/test-utils`；Rust 侧 `cargo test` |
| 提交粒度 | 每个任务一次提交，前缀 `feat:` / `fix:` / `test:` / `chore:` |
| 提交信息语言 | 中文，说清「做了什么 + 为什么」 |
| 主题 | **只做深色**。视觉 token 只在 `theme.css` 定义一次，组件里**不许写死颜色** |
| 端口与地址 | 前端**永远不硬编码端口**——端口只能来自 `invoke('get_backend_status')` |

---

## Task 1: 最小可跑的 Tauri 2 + Vue 3 骨架

**这是本计划的风险验证**：Tauri 冒烟测试（`113db89`）验的是**裸 Tauri**，而这里要的是 **Tauri + Vite + Vue + pnpm** 的组合。先把它跑通，再往上加东西。

> **本任务上一轮的缺陷已修**：`pnpm build` 因为 project references + `noEmit` 报
> `TS6310`、退出 2（详见 Step 3 与 Step 13b）。`tauri dev` 不跑 `vue-tsc`，所以它躲过了
> 当时的验收——Task 1 的验收里**因此补了 Step 13b**，并去掉了 `tsconfig.node.json`。
> 同时补了图标的可复现来源（Step 12b）。

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/index.html`
- Create: `frontend/pnpm-workspace.yaml`（由 `pnpm install` 生成，随后补上理由注释）
- Create: `frontend/README.md`
- Create: `frontend/scripts/make-icon.py`
- Create: `frontend/scripts/icon-source.png`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/styles/theme.css`（空占位，Task 2 覆盖）
- Create: `frontend/src-tauri/Cargo.toml`
- Create: `frontend/src-tauri/build.rs`
- Create: `frontend/src-tauri/tauri.conf.json`
- Create: `frontend/src-tauri/capabilities/default.json`
- Create: `frontend/src-tauri/src/main.rs`
- Create: `frontend/src-tauri/src/lib.rs`
- Modify: `.gitignore`

- [x] **Step 1: 建 `frontend/package.json`**

```json
{
  "name": "change-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest",
    "tauri": "tauri"
  },
  "dependencies": {
    "@tauri-apps/api": "^2",
    "pinia": "^2.2.6",
    "vue": "^3.5.13",
    "vue-router": "^4.5.0"
  },
  "devDependencies": {
    "@tauri-apps/cli": "^2",
    "@vitejs/plugin-vue": "^5.2.1",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.1",
    "typescript": "^5.7.2",
    "vite": "^6.0.5",
    "vitest": "^3",
    "vue-tsc": "^2.2.0"
  }
}
```

> **版本号说明**：这里用的是各包的**大版本范围**（`^2` / `^3.5`），不是钉死的小版本。`pnpm install` 会解析到当时的最新兼容版本。**如果安装后 `pnpm tauri dev` 报版本不兼容，先报告实际报错再改**，不要盲目升降级。
>
> **`vitest` 必须是 `^3`，不要降回 2.x**：`vitest@2` 把 `vite` 写成硬依赖 `^5.0.0`，
> 会让 `node_modules` 里出现两份 vite，并让 `vitest.config.ts` 必然报 `TS2769`
> （与 `exclude` 的关系见 Task 2 末尾的「两份 `vite`」一节）。

- [x] **Step 2: 建 `frontend/vite.config.ts`**

```ts
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
```

- [x] **Step 3: 建 `frontend/tsconfig.json`（只有一份）**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "jsx": "preserve",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "esModuleInterop": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["vite/client", "node"]
  },
  "include": ["src/**/*.ts", "src/**/*.vue", "vite.config.ts", "vitest.config.ts"]
}
```

> ⚠️ **这里刻意没有 `tsconfig.node.json`，也没有 `references`。** 首版计划分了两个 config 并用
> project references 串起来，那是**坏的**：被引用的 composite 项目不允许 `noEmit`
> （`tsconfig.node.json` 同时写了 `"composite": true` 和 `"noEmit": true`），于是
> `vue-tsc --noEmit` 直接报
>
> ```
> tsconfig.json(21,18): error TS6310: Referenced project '.../tsconfig.node.json' may not disable emit.
> ```
>
> 并让 `pnpm build` 退出 2。**`pnpm tauri dev` 不跑 `vue-tsc`，所以这个缺陷躲过了 Task 1 的验收**，
> 而 `tauri.conf.json` 的 `beforeBuildCommand` 正是 `pnpm build`——第一次 `tauri build` 必然撞上。
> 修法是**删掉第二个 config，改成单一 tsconfig**：本项目只有两个 config 文件、没有需要增量构建的
> 规模，references 带来的复杂度不值得。`vite.config.ts` 与 `vitest.config.ts` 直接并进 `include`。
>
> 另外 `types` 里加了 `"node"`，因为 `src/styles/theme.spec.ts`（Task 2）要用 `node:fs` /
> `node:url`。**因此 `@types/node` 是必需的 devDependency**，否则 `vue-tsc` 报找不到类型。
> 它**不在 Step 1 的 fence 里**（那份 fence 是「首次创建 `package.json`」的原样口径），
> 由 Step 12 安装后手动加进 `devDependencies`：`"@types/node": "^22"`。

- [x] **Step 4: 建 `frontend/index.html`**

**注意 `background-color` 那个内联样式**——它是防白屏闪的第一道（WebView 在 Vue 挂载前会先渲染这个 HTML）：

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>changE</title>
    <!-- 防白屏：WebView 在 Vue 挂载之前会先渲染这个 HTML，
         底色必须在这里就定成主题底色，否则会闪一下白 -->
    <style>
      html, body { margin: 0; background-color: #0d0f12; color: #d8dee6; }
    </style>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [x] **Step 5: 建 `frontend/src/main.ts` 与 `frontend/src/App.vue`**

`main.ts`：

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './styles/theme.css'

createApp(App).use(createPinia()).mount('#app')
```

> `./styles/theme.css` 在 Task 2 才创建。**这里先建一个空文件占位**，否则本步骤的 `pnpm dev` 会因找不到模块而失败：
>
> ```powershell
> New-Item -ItemType File -Force -Path frontend\src\styles\theme.css
> ```
>
> （`-Force` 让父目录不存在时也一并建出来。）

`App.vue`：

```vue
<script setup lang="ts">
</script>

<template>
  <div class="shell">
    <h1>changE</h1>
    <p>骨架已启动。后端接线在后面的任务里加。</p>
  </div>
</template>

<style scoped>
.shell {
  padding: 32px;
  font-family: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
}
h1 { font-weight: 800; letter-spacing: -0.015em; margin: 0 0 8px; }
p  { color: #6b7480; margin: 0; }
</style>
```

- [x] **Step 6: 建 `frontend/src-tauri/Cargo.toml`**

```toml
[package]
name = "change"
version = "0.1.0"
description = "changE —— 健身记录客户端"
edition = "2021"
rust-version = "1.77"

[lib]
name = "change_lib"
crate-type = ["staticlib", "cdylib", "rlib"]

[build-dependencies]
tauri-build = { version = "2", features = [] }

[dependencies]
tauri = { version = "2", features = [] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
```

> **注意这里没有 `tauri-plugin-shell`。** 本计划用标准库 `std::process` 拉后台进程——省掉插件注册与 capabilities 授权两层配置，而且端口解析逻辑可以脱离 Tauri 运行时被单元测试覆盖（Task 4）。

- [x] **Step 7: 建 `frontend/src-tauri/build.rs`**

```rust
fn main() {
    tauri_build::build()
}
```

- [x] **Step 8: 建 `frontend/src-tauri/tauri.conf.json`**

```json
{
  "$schema": "https://schema.tauri.app/config/2",
  "productName": "changE",
  "version": "0.1.0",
  "identifier": "com.change.desktop",
  "build": {
    "beforeDevCommand": "pnpm dev",
    "devUrl": "http://127.0.0.1:1420",
    "beforeBuildCommand": "pnpm build",
    "frontendDist": "../dist"
  },
  "app": {
    "windows": [
      {
        "title": "changE",
        "width": 1280,
        "height": 820,
        "minWidth": 960,
        "minHeight": 640,
        "resizable": true,
        "backgroundColor": "#0d0f12"
      }
    ],
    "security": {
      "csp": "default-src 'self'; connect-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:"
    }
  },
  "bundle": {
    "active": true,
    "targets": ["nsis"],
    "icon": ["icons/icon.ico"]
  }
}
```

**两处刻意的选择，都有理由：**

1. **`backgroundColor: "#0d0f12"`** —— Tauri 在 WebView 加载出内容之前会先画窗口底色。不设它就会闪一下白（设计文档要求避免白屏，而窗口自身那一层闪烁是 HTML 里的样式管不到的）。
2. **CSP 的 `connect-src` 收紧成 `'self'`**（Task 8 改的）—— 首版写的是
   `connect-src 'self' http://127.0.0.1:*`，理由是「端口是运行时才知道的，CSP 是静态的，
   所以只能通配 `*`」。那个理由只对了一半：**前端现在根本不发这个请求**（健康检查改由
   外壳代发，见 Task 8 一节），所以通配没有存在的必要。**不要图省事写 `"csp": null`**：
   那等于关掉整个 CSP，而这是一个要分发给朋友的客户端。
   `style-src 'unsafe-inline'` 留着：Vue 的 `<style scoped>` 会注入内联样式，
   去掉它界面直接没有样式。

- [x] **Step 9: 建 `frontend/src-tauri/capabilities/default.json`**

```json
{
  "$schema": "../gen/schemas/desktop-schema.json",
  "identifier": "default",
  "description": "changE 主窗口的能力",
  "windows": ["main"],
  "permissions": ["core:default"]
}
```

> 本计划只用 `invoke` 命令（`core:default` 已覆盖），没有文件系统、没有 shell 插件，所以权限清单保持最小。

- [x] **Step 10: 建 `frontend/src-tauri/src/lib.rs` 与 `main.rs`**

`lib.rs`：

```rust
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("Tauri 应用启动失败");
}
```

`main.rs`：

```rust
// Windows 发布版不弹控制台窗口；调试版保留，方便看后端子进程的输出。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    change_lib::run()
}
```

- [x] **Step 11: 更新 `.gitignore`**

在现有 `.gitignore` 末尾追加：

```gitignore
# 前端
frontend/node_modules/
frontend/dist/
frontend/src-tauri/target/
frontend/src-tauri/gen/
```

- [x] **Step 12: 安装依赖**

Run:
```powershell
cd E:\idea\program\change\frontend
pnpm install
```

Expected: 装完无 error。**如果 `pnpm` 不在 PATH**，用绝对路径 `& 'E:\npm-global\pnpm.ps1' install`。

装完之后把 `@types/node` 加进 `devDependencies`（版本 `"^22"`）再跑一次 `pnpm install`——
理由见 Step 3 的注（`theme.spec.ts` 要用 `node:fs` / `node:url`）。

> ⚠️ **首次 `pnpm install` 很可能在 `ERR_PNPM_IGNORED_BUILDS` 上退出 1**，看起来像：
>
> ```
> [ERR_PNPM_IGNORED_BUILDS] Ignored build scripts: esbuild@0.19.3, esbuild@0.25.0, vue-demi@0.14.10
> Run "pnpm approve-builds" to pick which dependencies should be allowed to run scripts.
> ```
>
> **这不是「某个包被跳过」，是整个 npm script 面死掉。** 同一个原因会让
> `pnpm run` / `pnpm exec` 全部以 1 退出——连 `pnpm exec tauri --version` 都进不去。
> 所以这一步**必须解决掉**，不能带着它往下走。
>
> pnpm 11 会自动生成 `frontend/pnpm-workspace.yaml`，里面是一份**未填写的模板**
> （`esbuild: set this to true or false`）。**占位值既不是 true 也不是 false，
> 于是 pnpm 拒绝工作**。值只有两种含义：
>
> - `false` = 知道这个包有构建脚本，**且决定不执行**
> - `true` = 知道，且允许它执行
>
> **本项目全部填 `false`**——即「不执行任何第三方 postinstall 代码」。这不是图省事，
> 每个包都单独核过：
>
> | 包 | 为什么 `false` 是安全的 |
> |---|---|
> | `esbuild` | 它的 postinstall 只是「确认平台二进制在不在」。真正的 `@esbuild/win32-x64` 是通过 **`optionalDependencies`** 装的，**不经过** postinstall。实测 `pnpm build` 正常 |
> | `vue-demi` | 它的 postinstall 只在 Vue 2 / Vue 3 之间切模式。本项目是 Vue 3，而出厂默认就是 `lib/index.vue3.mjs` 那条路径——脚本本身是 no-op |
>
> **不要用 `pnpm approve-builds` 交互式点选**（结果不可复现），也不要写别的占位值。
> 直接手填 `frontend/pnpm-workspace.yaml`，**并把上面的判断依据作为注释写进文件里**——
> 那个文件里一个字的解释都没有的话，下一个人会以为它是样板而删掉它。
>
> 将来某个包**确实**需要跑构建脚本时（症状：包能装上但功能报错，且报错指向它自己的
> postinstall 产物缺失），把那一行改成 `true` 并重跑 `pnpm install`。

- [x] **Step 12b: 生成图标（`tauri icon` 的输入是可复现的脚本）**

图标是**离线生成一次、然后入库**的产物，不是构建期步骤。首版计划漏了这一步：当时的源图是
临时造的、放在 `%TEMP%`、没有入库，于是「重新生成一次图标」这件事不可复现。现在源图是代码。

先建 `frontend/scripts/make-icon.py`（Pillow 画一张 1024×1024 的占位图：主题底色 `#0d0f12`
+ 强调色 `#e0453a` 的简单几何），然后：

```powershell
cd E:\idea\program\change\frontend
python scripts\make-icon.py                  # 产出 scripts/icon-source.png
pnpm tauri icon scripts/icon-source.png      # 铺开成各平台尺寸
```

Expected: 两条都退出 0；`src-tauri/icons/` 下出现 `icon.ico` / `icon.png` / 各尺寸 PNG。

**把 `scripts/icon-source.png` 一起入库**（1024×1024，几十 KB 量级）——它是可复现链条的输入，
不入库的话这个链条就断了。`make-icon.py` 要 Pillow，但**不写进 `package.json`**：
它不参与任何构建。

> `pnpm tauri icon` 会顺带生成 `src-tauri/icons/android/` 与 `ios/` 共约 40 个移动端文件。
> **本项目是 desktop-only**（`tauri.conf.json` 的 `targets: ["nsis"]`，`bundle.icon` 只引用
> `icons/icon.ico`），所以这两个目录**删掉且不入库**：
>
> ```powershell
> Remove-Item -Recurse -Force src-tauri\icons\android, src-tauri\icons\ios
> ```
>
> 实测删掉后 `cargo check --all-targets`、`pnpm tauri dev`、`pnpm build` 都不受影响。

> ⚠️ **重跑图标后 `icons/icon.icns` 会出现二进制差异，这不是错误。** 实测连续跑两次
> `pnpm tauri icon`，两次的 `icon.icns` sha256 就不同（120921 字节里约 109000 字节不同），
> 但它的**像素是确定的**（1024×1024，每次一样）——不确定的是 ICNS 容器本身。
> `.icns` 只在 macOS 上用，而本项目打包目标是 Windows NSIS 且 `bundle.icon` 没引用它，
> 所以对本项目无实际影响。**验收时不要把它当成差异**；想保持工作区干净就
> `git checkout -- src-tauri/icons/icon.icns`。这件事在 `frontend/README.md` 里也记了一份。

- [x] **Step 12c: 给 `pnpm-workspace.yaml` 补上理由注释**

`pnpm install` 生成的那个文件只有干巴巴的 `esbuild: false` / `vue-demi: false`（见 Step 12 的
表），**一个字的解释都没有**——下一个人会以为它是样板而删掉它。就在文件里把 Step 12 那张表的
依据写成注释：为什么是 `false`（不是「随便填的占位值」）、两个包各自的判断依据、以及将来某个包
**确实**需要跑构建脚本时该怎么做（把那一行改成 `true` 并重跑 `pnpm install`）。

> 这里**不给这个文件的逐字 fence**：它的价值全在注释措辞上，而注释会随讨论变化，
> 钉成 fence 只会制造无意义的同步负担。判断依据本身在 Step 12 的表里，那份是规格。

同时建 `frontend/README.md`，把「怎么装依赖 / 怎么跑测试 / 怎么 `pnpm tauri dev` /
端口 1420 被占为什么会响亮失败 / 图标从哪来 / pnpm 版本要求（11+）/ 后端 jar 要先
`mvn package`（Task 5 起需要）」写清楚。**它是「图标从哪来」这个问题的答案**，
所以 Step 12b 的两条命令要原样写进去。

- [x] **Step 13: 验证「能起来」——这是本任务真正的验收**

Run:
```powershell
cd E:\idea\program\change\frontend
pnpm tauri dev
```

Expected:
1. Vite 在 1420 启动，终端出现 `VITE v... ready in ... ms`
2. cargo 开始编译（**首次约 2 分钟**，`113db89` 实测 1.9 分钟；cargo 缓存已有 321MB，不会重下）
3. **弹出一个 1280×820 的深色窗口，写着 `changE` 与「骨架已启动」**
4. 窗口**不闪白**（若闪白，回去检查 Step 4 的 HTML 内联样式与 Step 8 的 `backgroundColor`）

按 `Ctrl+C` 停掉。

**如果第 3 步起不来**：停下来报告实际报错。这一步是两个未知（Tauri+Vite 集成、pnpm 依赖解析）的交汇点，**不要靠猜去改配置**。

- [x] **Step 13b: `pnpm build` 必须退出 0 —— 本缺陷的回归守卫**

Run:
```powershell
cd E:\idea\program\change\frontend
pnpm build
```

Expected: **退出码 0**，并产出 `frontend/dist/`（`dist/index.html` + `dist/assets/*`）。
不需要后端——`vite build` 只读 `index.html` 与 `src/`。

> **这一步是首版计划漏掉的那道守卫，它就是 TS6310 漏到下一轮的原因。**
> `pnpm tauri dev` 走的是 `beforeDevCommand`（只起 Vite），**不跑 `vue-tsc`**，
> 所以两个 config 的 `TS6310` 能一路躲过 Step 13 的验收。而
> `tauri.conf.json` 的 `beforeBuildCommand` 是 `pnpm build`，`pnpm build` 的第一步就是
> `vue-tsc --noEmit`——**第一次 `pnpm tauri build`（计划 7）必然撞上它**。
>
> 也就是说：`tauri dev` 绿 ≠ 前端能构建。凡是 `beforeBuildCommand` 会跑的，
> 都要在 Task 1 就被跑过一次。

- [x] **Step 14: 提交**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml frontend/vite.config.ts \
        frontend/tsconfig.json frontend/index.html frontend/README.md \
        frontend/pnpm-workspace.yaml frontend/scripts \
        frontend/src frontend/src-tauri .gitignore
git commit -m "feat(frontend): Tauri 2 + Vue 3 + Vite 骨架跑通，深色窗口不闪白

- 首次把前端引入仓库（此前 7 份计划里没有一份负责建它）
- 用 strictPort:true 让 1420 被占时响亮失败，而不是静默换端口
- 两处防白屏：HTML 内联底色 + Tauri 窗口 backgroundColor
- 不用 tauri-plugin-shell，改用 std::process（省掉插件能力配置，
  且端口解析逻辑可脱离 Tauri 运行时被单元测试覆盖）
- 单一 tsconfig（不用 project references）：被引用的 composite 项目
  不允许 noEmit，那会让 pnpm build 报 TS6310、退出 2
- 图标可复现：scripts/make-icon.py 画源图，pnpm tauri icon 铺开
- pnpm-workspace.yaml 的 allowBuilds 全填 false，即不执行第三方
  postinstall；每个包的判断依据写在文件注释里"
```

---

## Task 2: 深色主题 token

把设计文档冻结的视觉方向变成**一处代码**。之后所有组件都从这里取值，不许写死颜色。

**Files:**
- Create: `frontend/vitest.config.ts`（前端第一个测试文件要能跑起来，测试环境从这里定下来）
- Create: `frontend/src/styles/theme.css`（覆盖 Task 1 的空占位）
- Test: `frontend/src/styles/theme.spec.ts`

- [x] **Step 1: 建 `frontend/vitest.config.ts`**

```ts
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
  },
})
```

> **为什么这份配置在 Task 2 就建，而不是留到 Task 3**：Task 2 是前端第一个有测试文件的
> 任务，`pnpm test` 从这一步起才是有效信号；测试环境（jsdom）与 `include` 的约定，也该在
> 有测试的那一刻就钉下来。**Task 3 只复用，不重建。**
>
> ⚠️ `plugins: [vue()]` 这一行曾经**必然**报 `TS2769`——根因是 `vitest@2` 把 `vite` 写成
> 硬依赖 `^5.0.0`，`node_modules` 里因此存在两份 vite。完整记录见本任务末尾的
> 「两份 vite」一节，**不要**再用 `tsconfig.json` 的 `exclude` 去绕。

- [x] **Step 2: 写失败的测试**

```ts
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

// 这条测试的意义：视觉方向是设计文档冻结的决定，而 CSS 是最容易悄悄漂移的地方
// （改一个色值不会有任何东西报警）。这里把「设计文档里写的那几个值」变成断言，
// 让漂移变成红的。这与后端 SchemaTest 用 DDL 快照钉住 schema 是同一个思路。
const here = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(resolve(here, 'theme.css'), 'utf-8')

/** 取 `--name: value;` 里的 value（去空白）。 */
function token(name: string): string | undefined {
  const m = css.match(new RegExp(`--${name}\\s*:\\s*([^;]+);`))
  return m?.[1].trim()
}

describe('主题 token', () => {
  it('底色、面板、分隔线与设计文档一致', () => {
    expect(token('bg')).toBe('#0d0f12')
    expect(token('panel')).toBe('#14171b')
    expect(token('line')).toBe('#262b31')
  })

  it('强调色只有一个暖红', () => {
    expect(token('accent')).toBe('#e0453a')
  })

  it('小标签的灰与字距与设计文档一致', () => {
    expect(token('eyebrow')).toBe('#6b7480')
    expect(token('eyebrow-tracking')).toBe('0.16em')
  })

  it('标题字距是 -0.015em，字重 800', () => {
    expect(token('title-tracking')).toBe('-0.015em')
    expect(token('title-weight')).toBe('800')
  })

  it('圆角在 4–6px 之间（设计文档规定不允许更大）', () => {
    const r = Number(token('radius')?.replace('px', ''))
    expect(r).toBeGreaterThanOrEqual(4)
    expect(r).toBeLessThanOrEqual(6)
  })

  it('不定义阴影（设计文档明确说不用阴影分层）', () => {
    expect(css).not.toMatch(/--shadow\s*:/)
  })

  it('只有深色主题：不定义浅色覆盖（设计文档明确说不做浅色）', () => {
    // 用 prefers-color-scheme 或 [data-theme] 开浅色分支就等于破掉了这条封板决定
    expect(css).not.toMatch(/prefers-color-scheme\s*:\s*light/)
    expect(css).not.toMatch(/\[data-theme\s*=\s*["']?light/)
  })

  it('强调色只在 --accent 定义一次，组件不许各写各的红', () => {
    // 设计文档说「唯一的暖红」。全文件里 #e0453a 只该出现一次（就是 --accent 那行）。
    const hits = css.match(/#e0453a/gi) ?? []
    expect(hits).toHaveLength(1)
  })
})
```

- [x] **Step 3: 跑测试，确认它失败**

Run: `cd frontend && pnpm test src/styles/theme.spec.ts`

Expected: FAIL —— 8 条全红，因为 `theme.css` 现在是空文件，所有 `token(...)` 返回 `undefined`。

- [x] **Step 4: 写实现**

```css
/*
 * changE 的视觉 token —— 唯一来源。
 *
 * 值来自设计文档「九、界面与视觉方向」。任何组件都不许写死颜色，
 * 一律 var(--xxx)。theme.spec.ts 会把下面这几个值钉住，防止悄悄漂移。
 *
 * 只做深色主题：厚重感一半来自深色底与高对比，再维护一套浅色主题
 * 会稀释这个方向，且测试成本翻倍（设计文档已封板）。
 *
 * **已知还没定义的 token 类别，现在刻意不加**：正文 400 字重、语义色
 * （--danger / --warning）、肌肉色阶、比 --gap-xl 更大的一档留白、
 * 把 --line 拆成 divider / border 两个。
 *
 * 加它们的时机是**消费它们的界面出现时**——那时才知道要几档、该叫什么名字。
 * 提前定义就是一堆没人用的死值，而且每一个都要被 theme.spec.ts 钉住，
 * 成本不是零。等真正需要的那一页写到那里再加。
 */
:root {
  /* 背景三层 */
  --bg: #0d0f12;
  --panel: #14171b;
  --line: #262b31;

  /* 唯一强调色：肌肉高亮、主按钮、进度条都用它 */
  --accent: #e0453a;

  /* 文字 */
  --text: #d8dee6;
  /* --text-dim 与 --eyebrow 今天同值（#6b7480），但语义不同、不要合并：
     --text-dim 是正文里的次要文字，--eyebrow 是全大写小标签。两者将来完全
     可能分叉（比如小标签要更暗一点），合成一个会把这个可能性堵死，
     而保留两个的成本是零。 */
  --text-dim: #6b7480;
  --eyebrow: #6b7480;

  /* 排版 */
  --title-weight: 800;
  --title-tracking: -0.015em;
  --eyebrow-tracking: 0.16em;

  /* 形状：靠描边与间距分层，不用阴影 */
  --radius: 5px;
  --line-width: 1px;

  /* 间距：留白放宽，用空白而不是卡片边框分组 */
  --gap-xs: 4px;
  --gap-sm: 8px;
  --gap-md: 16px;
  --gap-lg: 28px;
  --gap-xl: 44px;

  --font: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
}

* { box-sizing: border-box; }

html, body, #app {
  height: 100%;
  margin: 0;
}

body {
  background: var(--bg);
  color: var(--text);
  font-family: var(--font);
  font-size: 14px;
  line-height: 1.5;
  -webkit-font-smoothing: antialiased;
}

h1, h2, h3 {
  font-weight: var(--title-weight);
  letter-spacing: var(--title-tracking);
  margin: 0;
}

/* 小标签：全大写 + 宽字距 + 灰 */
.eyebrow {
  text-transform: uppercase;
  letter-spacing: var(--eyebrow-tracking);
  color: var(--eyebrow);
  font-size: 11px;
  font-weight: 600;
}

button {
  font: inherit;
  color: inherit;
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  padding: 6px 12px;
  cursor: pointer;
}

button:hover { border-color: var(--accent); }

a { color: var(--accent); }
```

- [x] **Step 5: 跑测试，确认通过**

Run: `cd frontend && pnpm test src/styles/theme.spec.ts`

Expected: `Tests  8 passed`

> **为什么是 8 条而不是 6 条**：fence 里最后两条是**否定式**断言（不定义浅色分支、
> `#e0453a` 全文件只出现一次）。空文件天然满足它们，所以它们在写实现之前也是绿的——
> 只有在**后人**加了 `prefers-color-scheme: light` 分支、或让第二个地方出现同一个色号时
> 才会变红。它们是替将来守门的，不是替现在的实现提要求的：**不要**因为「空文件也能过」
> 就把它们删掉，也**不要**因此以为实现没做完。

- [x] **Step 6: 提交**

```bash
git add frontend/vitest.config.ts frontend/src/styles/theme.css frontend/src/styles/theme.spec.ts
git commit -m "feat(frontend): 深色主题 token，并用测试钉住设计文档冻结的值

视觉方向是最容易悄悄漂移的地方——改一个色值不会有任何东西报警。
这里把设计文档里的值变成断言（与后端用 DDL 快照钉 schema 同一思路）。
另加两条结构性断言：圆角必须落在 4–6px、不许定义阴影。
同时建起 vitest.config.ts——前端第一批测试从这里开始。"
```

### 两份 `vite`：`vitest` 2 的依赖冲突（给后来人）

**现象**：`pnpm build`（= `vue-tsc --noEmit && vite build`）会在 `vitest.config.ts` 上以
`TS2769` 退出 2，报错位置落在 `plugins: [vue()]` 那一行——**和主题 token 没有任何关系**。
上一轮实测过：把 `vue()` 的结果往两个方向 `as`（vite@5 的类型 / vite@6 的类型）都不管用，
因为两份 vite 的类型是**双向**不兼容的，`as` 只是把同一个错误换个写法。

**根因**：`vitest@2.1.8` 把 `vite` 写成**硬依赖 `^5.0.0`**（不是 `peerDependency`），
而项目自己用 `vite@^6`。于是 `node_modules` 里同时存在两份 vite：

```
vite@5.0.0   ← vitest / vite-node / @vitest/mocker 用
vite@6.4.4   ← 项目与 @vitejs/plugin-vue 用
```

`vue-tsc` 从 `vitest/config` 拿到的 `defineConfig` 是按 **vite@5 的 `PluginOption`** 校验的，
而 `vue()` 返回的是 **vite@6 的插件实例**，两套类型在同一个 config 对象里互斥——所以这是
必然报错，不是写法问题。

**这不是「pnpm 的正常行为」，而是依赖缺陷**：装两遍 vite、两套插件类型、插件实例跨版本
混用，将来极难查。看到两份 vite 就该当成 bug，而不是忍下去。

**处置**：把 `vitest` 升到 `^3`。`vitest@3.2.7` 对 vite 的依赖是**范围**
`"^5.0.0 || ^6.0.0 || ^7.0.0-0"`，所以 pnpm 会直接复用项目那份 `vite@6.4.4`，
`node_modules` 里只剩一份 vite，类型冲突从根上消失（升级后 `pnpm why vite` 输出
`Found 1 version of vite`）。

`jsdom` 与 `@vue/test-utils` **不用跟着动**：`vitest@3` 的 `peerDependencies` 里
`jsdom` 是 `*`（当前 `^25.0.1` 就满足），而 `@vue/test-utils` 根本不在它的 peer 列表里。
**不要凭记忆升降级，去读它的 `peerDependencies`。**

**为什么不用 `tsconfig.json` 的 `exclude` 绕过**：曾经有一版就是这么修的——把
`vitest.config.ts` 从 `include` 里排除，`pnpm build` 立刻变绿。那是**用看不见换不报错**：
文件退出类型检查之后就再没有东西守着它的写法，而 vitest 自己加载配置时只会给运行期错误
（`port: 'not-a-number'` 这类在运行期往往不疼不痒）。根因在依赖上就该在依赖上解决。
本轮已把那个 `exclude` 拆掉，`tsconfig.json` 回到 Task 1 Step 3 fence 的样子，
所以**不必**在 Files 清单里再列它。

**回归守卫**——让「它确实在类型检查里」这件事可验证：往 `vitest.config.ts` 里塞一个错的
选项，`pnpm build` 必须**变红**：

```
vitest.config.ts(6,3): error TS2769: No overload matches this call.
  Object literal may only specify known properties, and 'port' does not exist in type 'UserConfigExport'.
[ELIFECYCLE] Command failed with exit code 2.
```

**如果它还是绿的**，说明这个文件又不在类型检查里了——那时该去查 `include` / `exclude`，
而不是怀疑 vitest 的版本。

> 也可以直接用 `pnpm exec vue-tsc --noEmit --listFiles` 看它到底检查了哪些文件，
> 输出里应当有 `frontend/vitest.config.ts`。

---

## Task 3: 侧边栏骨架与路由

界面布局是设计文档冻结的：左侧分组导航 + 顶部工具条 + 右侧工作区。

**Files:**
- Create: `frontend/src/nav.ts`
- Create: `frontend/src/router/index.ts`
- Create: `frontend/src/components/AppSidebar.vue`
- Create: `frontend/src/components/AppToolbar.vue`
- Create: `frontend/src/views/` 下 9 个占位页
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/App.vue`
- Test: `frontend/src/nav.spec.ts`
- Test: `frontend/src/components/AppSidebar.spec.ts`
- Test: `frontend/src/components/AppToolbar.spec.ts`
- 复用（不重建）: `frontend/vitest.config.ts`——Task 2 Step 1 已建

- [x] **Step 1: 确认 `frontend/vitest.config.ts` 已存在（不要重建）**

它由 **Task 2 Step 1** 建立（`environment: 'jsdom'` + `include: ['src/**/*.spec.ts']`），
本任务的组件测试用的就是它。**不要在这里再写一份**——两份配置迟早会漂移，而它们的行为
差异只在跑测试时才显形。

> 这个文件已经被 Task 1 Step 3 的 `tsconfig.json` 收进 `include` 了——原先它归
> `tsconfig.node.json` 管，而那个 config 已随 project references 一起删除。**这里不需要再动
> tsconfig。**

- [x] **Step 2: 写失败的测试（导航数据）**

```ts
import { describe, it, expect } from 'vitest'
import { NAV_GROUPS, allRoutes } from './nav'

describe('导航分组', () => {
  it('四个分组，顺序与设计文档一致', () => {
    expect(NAV_GROUPS.map((g) => g.label)).toEqual(['记录', '分析', '助手', '我的'])
  })

  it('「我的」钉在底部且与功能区分隔', () => {
    const mine = NAV_GROUPS.find((g) => g.label === '我的')!
    expect(mine.pinnedBottom).toBe(true)
    // 其余三组不钉底
    expect(NAV_GROUPS.filter((g) => !g.pinnedBottom).map((g) => g.label))
      .toEqual(['记录', '分析', '助手'])
  })

  it('人体图那一项带「预留」标记（设计文档决定 5：界面位第一阶段就冻结）', () => {
    const item = allRoutes().find((r) => r.path === '/body-map')!
    expect(item.label).toBe('人体图')
    expect(item.marker).toBe('预留')
  })

  it('每个路由的 path 唯一，且都有非空 label', () => {
    const routes = allRoutes()
    expect(new Set(routes.map((r) => r.path)).size).toBe(routes.length)
    for (const r of routes) expect(r.label.length).toBeGreaterThan(0)
  })

  it('覆盖设计文档列出的全部 9 项', () => {
    expect(allRoutes().map((r) => r.label)).toEqual([
      '今日', '训练记录', '饮食记录',
      '人体图', '进步曲线', '身体数据',
      '智能体对话',
      '设置', '关于与致谢',
    ])
  })
})
```

- [x] **Step 3: 跑测试，确认失败**

Run: `cd frontend && pnpm test src/nav.spec.ts`

Expected: FAIL —— `Failed to resolve import "./nav"`

- [x] **Step 4: 写 `frontend/src/nav.ts`**

```ts
/**
 * 侧边栏与路由的单一数据源。
 *
 * 布局是设计文档冻结的决定：左侧分组导航 + 顶部工具条 + 右侧工作区。
 * 「设置」与「关于与致谢」收进底部「我的」分组，用分隔线与功能区隔开——
 * 系统级入口不该和日常功能混在一起。
 *
 * 这里刻意用数据而不是手写 9 个 <RouterLink>：分组、顺序、是否钉底、
 * 有没有「预留」标记全部在一处，改布局不用翻模板。
 */

export interface NavItem {
  path: string
  label: string
  /** 路由名，供 router.push({ name }) 用。 */
  name: string
  /** 角标文案。目前只有「人体图」用它（设计文档决定 5）。 */
  marker?: string
  /** 图标的占位字符。第一阶段不引入图标库，先用单字。 */
  glyph: string
}

export interface NavGroup {
  label: string
  /** 钉在侧边栏底部（设计文档：「我的」用分隔线隔开）。 */
  pinnedBottom: boolean
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    label: '记录',
    pinnedBottom: false,
    items: [
      { path: '/today',    label: '今日',     name: 'today',    glyph: '今' },
      { path: '/training', label: '训练记录', name: 'training', glyph: '训' },
      { path: '/diet',     label: '饮食记录', name: 'diet',     glyph: '食' },
    ],
  },
  {
    label: '分析',
    pinnedBottom: false,
    items: [
      // 设计文档决定 5：3D 人体图排在第二阶段，但界面位第一阶段就冻结，
      // 免得将来加它时要重排整个导航。
      { path: '/body-map', label: '人体图',   name: 'bodyMap',  glyph: '体', marker: '预留' },
      { path: '/progress', label: '进步曲线', name: 'progress', glyph: '曲' },
      { path: '/body',     label: '身体数据', name: 'body',     glyph: '数' },
    ],
  },
  {
    label: '助手',
    pinnedBottom: false,
    items: [
      { path: '/agent', label: '智能体对话', name: 'agent', glyph: '智' },
    ],
  },
  {
    label: '我的',
    pinnedBottom: true,
    items: [
      { path: '/settings', label: '设置',       name: 'settings', glyph: '设' },
      { path: '/about',    label: '关于与致谢', name: 'about',    glyph: '谢' },
    ],
  },
]

/** 拍平成一个列表，路由与测试都用它。 */
export function allRoutes(): NavItem[] {
  return NAV_GROUPS.flatMap((g) => g.items)
}
```

- [x] **Step 5: 跑测试，确认通过**

Run: `cd frontend && pnpm test src/nav.spec.ts`

Expected: `Tests  5 passed`

- [x] **Step 6: 建 9 个占位页**

每个都长这样（以 `TodayView.vue` 为例，其余 8 个把标题与说明换掉即可）：

`frontend/src/views/TodayView.vue`：

```vue
<script setup lang="ts">
</script>

<template>
  <section class="page">
    <p class="eyebrow">记录</p>
    <h2>今日</h2>
    <p class="pending">这一页还没有实现。计划 3（训练记录）会在这里放「本周容量」——设计文档要求它放在最显眼的位置。</p>
  </section>
</template>

<style scoped>
.page { padding: var(--gap-xl); max-width: 900px; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }
.pending { color: var(--text-dim); }
</style>
```

其余 8 个文件按同样结构建，**只改三处**（`eyebrow` 文本、`h2` 标题、`pending` 说明）：

| 文件 | eyebrow | h2 | pending 说明 |
|---|---|---|---|
| `TrainingView.vue` | 记录 | 训练记录 | 计划 3 实现：记一次完整训练、上次重量自动带出。 |
| `DietView.vue` | 记录 | 饮食记录 | 计划 4 实现：条目化记录 + 拍照识别草稿。 |
| `BodyMapView.vue` | 分析 | 人体图 | 设计文档决定 5：3D 人体图排第二阶段，界面位已冻结在这里。 |
| `ProgressView.vue` | 分析 | 进步曲线 | 计划 6 实现：体重曲线与 1RM 曲线。 |
| `BodyDataView.vue` | 分析 | 身体数据 | 计划 6 实现：围度与体重，用「类型 + 数值」存（不逐项开列）。 |
| `AgentView.vue` | 助手 | 智能体对话 | 计划 5 实现：说一句话就能记账，改动可回退。 |
| `SettingsView.vue` | 我的 | 设置 | 后面接：数据目录、Redis 连接状态、模型接口。 |
| `AboutView.vue` | 我的 | 关于与致谢 | 后面接：数据源许可证署名（设计文档「七、数据源与许可证」要求）。 |

- [x] **Step 7: 建 `frontend/src/router/index.ts`**

```ts
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { allRoutes } from '../nav'

import TodayView from '../views/TodayView.vue'
import TrainingView from '../views/TrainingView.vue'
import DietView from '../views/DietView.vue'
import BodyMapView from '../views/BodyMapView.vue'
import ProgressView from '../views/ProgressView.vue'
import BodyDataView from '../views/BodyDataView.vue'
import AgentView from '../views/AgentView.vue'
import SettingsView from '../views/SettingsView.vue'
import AboutView from '../views/AboutView.vue'

const componentByPath: Record<string, unknown> = {
  '/today': TodayView,
  '/training': TrainingView,
  '/diet': DietView,
  '/body-map': BodyMapView,
  '/progress': ProgressView,
  '/body': BodyDataView,
  '/agent': AgentView,
  '/settings': SettingsView,
  '/about': AboutView,
}

// 路由表由 nav.ts 派生：布局改了这里不用动。
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/today' },
  ...allRoutes().map((item) => ({
    path: item.path,
    name: item.name,
    component: componentByPath[item.path],
  })),
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
```

- [x] **Step 8: 写失败的测试（侧边栏）**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import AppSidebar from './AppSidebar.vue'
import { allRoutes } from '../nav'

function mountWithRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: allRoutes().map((r) => ({ path: r.path, name: r.name, component: { template: '<div/>' } })),
  })
  return mount(AppSidebar, { global: { plugins: [router] } })
}

describe('侧边栏', () => {
  it('渲染四个分组标题', () => {
    const w = mountWithRouter()
    const labels = w.findAll('.group-label').map((n) => n.text())
    expect(labels).toEqual(['记录', '分析', '助手', '我的'])
  })

  it('渲染全部 9 个入口', () => {
    const w = mountWithRouter()
    expect(w.findAll('.nav-item')).toHaveLength(9)
  })

  it('「我的」分组带 pinned 类（由它决定分隔线与贴底）', () => {
    const w = mountWithRouter()
    const pinnedGroups = w.findAll('.nav-group.pinned')
    expect(pinnedGroups).toHaveLength(1)
    expect(pinnedGroups[0].find('.group-label').text()).toBe('我的')
  })

  it('人体图那项显示「预留」角标', () => {
    const w = mountWithRouter()
    const marker = w.findAll('.marker').map((n) => n.text())
    expect(marker).toEqual(['预留'])
  })
})
```

- [x] **Step 9: 跑测试，确认失败**

Run: `cd frontend && pnpm test src/components/AppSidebar.spec.ts`

Expected: FAIL —— `Failed to resolve import "./AppSidebar.vue"`

- [x] **Step 10: 写 `frontend/src/components/AppSidebar.vue`**

```vue
<script setup lang="ts">
import { NAV_GROUPS } from '../nav'

const top = NAV_GROUPS.filter((g) => !g.pinnedBottom)
const bottom = NAV_GROUPS.filter((g) => g.pinnedBottom)
</script>

<template>
  <nav class="sidebar">
    <div class="brand">changE</div>

    <div class="scroll">
      <div v-for="group in top" :key="group.label" class="nav-group">
        <p class="group-label eyebrow">{{ group.label }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="nav-item"
        >
          <span class="glyph">{{ item.glyph }}</span>
          <span class="label">{{ item.label }}</span>
          <span v-if="item.marker" class="marker">{{ item.marker }}</span>
        </RouterLink>
      </div>
    </div>

    <!-- 「我的」钉底部，用一条分隔线与功能区隔开 -->
    <div v-for="group in bottom" :key="group.label" class="nav-group pinned">
      <div class="divider" />
      <p class="group-label eyebrow">{{ group.label }}</p>
      <RouterLink
        v-for="item in group.items"
        :key="item.path"
        :to="item.path"
        class="nav-item"
      >
        <span class="glyph">{{ item.glyph }}</span>
        <span class="label">{{ item.label }}</span>
      </RouterLink>
    </div>
  </nav>
</template>

<style scoped>
.sidebar {
  width: 208px;
  flex: 0 0 208px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-right: var(--line-width) solid var(--line);
}

.brand {
  padding: var(--gap-lg) var(--gap-md) var(--gap-md);
  font-size: 17px;
  font-weight: var(--title-weight);
  letter-spacing: var(--title-tracking);
}

.scroll { flex: 1 1 auto; overflow-y: auto; }

.nav-group { padding: var(--gap-md) var(--gap-sm) 0; }

.group-label { margin: 0 0 var(--gap-sm) var(--gap-sm); }

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--gap-sm);
  padding: 7px var(--gap-sm);
  margin-bottom: 2px;
  border-radius: var(--radius);
  color: var(--text);
  text-decoration: none;
}

.nav-item:hover { background: var(--bg); }

/* 选中态用强调色，但不填充整块——设计文档要求靠描边与间距分层 */
.nav-item.router-link-active {
  color: var(--accent);
  background: var(--bg);
}

.glyph {
  width: 20px;
  text-align: center;
  color: var(--text-dim);
  font-size: 12px;
}

.nav-item.router-link-active .glyph { color: var(--accent); }

.label { flex: 1 1 auto; }

.marker {
  font-size: 10px;
  letter-spacing: var(--eyebrow-tracking);
  color: var(--eyebrow);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  padding: 1px 5px;
}

.divider {
  height: var(--line-width);
  background: var(--line);
  margin: var(--gap-sm) var(--gap-sm) var(--gap-md);
}

.nav-group.pinned { padding-bottom: var(--gap-md); }
</style>
```

- [x] **Step 10b: 写 `frontend/src/components/AppToolbar.vue`**

布局的另一半。设计文档写的是「左侧分组导航 + **顶部工具条** + 右侧工作区」，工具条显示当前页名：

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { allRoutes } from '../nav'

const route = useRoute()

// 页名从 nav.ts 查，不在模板里手写——布局改了这里不用动。
const title = computed(
  () => allRoutes().find((r) => r.path === route.path)?.label ?? 'changE',
)
</script>

<template>
  <header class="toolbar">
    <h1>{{ title }}</h1>
  </header>
</template>

<style scoped>
.toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  height: 46px;
  padding: 0 var(--gap-lg);
  border-bottom: var(--line-width) solid var(--line);
}

h1 { font-size: 15px; }
</style>
```

配套测试（`frontend/src/components/AppToolbar.spec.ts`）：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { nextTick } from 'vue'
import AppToolbar from './AppToolbar.vue'
import { allRoutes } from '../nav'

async function mountAt(path: string) {
  const router = createRouter({
    history: createWebHistory(),
    routes: allRoutes().map((r) => ({
      path: r.path,
      name: r.name,
      component: { template: '<div/>' },
    })),
  })
  router.push(path)
  await router.isReady()
  const w = mount(AppToolbar, { global: { plugins: [router] } })
  await nextTick()
  return w
}

describe('顶部工具条', () => {
  it('显示当前页名', async () => {
    expect((await mountAt('/training')).find('h1').text()).toBe('训练记录')
  })

  it('切页之后跟着变', async () => {
    // 页名来自 nav.ts 的查表，不是模板里写死的字符串——
    // 这条测试守住「它确实在跟着路由动」。
    const w = await mountAt('/diet')
    expect(w.find('h1').text()).toBe('饮食记录')
  })

  it('认识的路径才会显示页名，不认识的回退到产品名', async () => {
    const w = await mountAt('/today')
    expect(w.find('h1').text()).toBe('今日')
  })
})
```

- [x] **Step 11: 跑测试，确认通过**

Run: `cd frontend && pnpm test src/components/AppSidebar.spec.ts src/components/AppToolbar.spec.ts`

Expected: `Tests  9 passed`（侧边栏 6 + 工具条 3）

> **计数校正（2026-10-16，Task 7 复核实测）**：这里原先写的是 `Tests  7 passed`
> （侧边栏 4 + 工具条 3）。侧边栏实际产出 **6 条**——实现时多了「分隔线在「我的」
> 分组内部」与「「我的」在 DOM 里排在所有功能区之后」两条；`pnpm test` 的 per-file
> 行实测 `AppSidebar.spec.ts` = 6 条、`AppToolbar.spec.ts` = 3 条，合计 9。

- [x] **Step 12: 改 `main.ts` 与 `App.vue` 用上路由、侧边栏与工具条**

`main.ts`：

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import './styles/theme.css'

createApp(App).use(createPinia()).use(router).mount('#app')
```

`App.vue`：

```vue
<script setup lang="ts">
import AppSidebar from './components/AppSidebar.vue'
import AppToolbar from './components/AppToolbar.vue'
</script>

<template>
  <div class="shell">
    <AppSidebar />
    <div class="main">
      <AppToolbar />
      <main class="workspace">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell { display: flex; height: 100%; }
.main { flex: 1 1 auto; display: flex; flex-direction: column; min-width: 0; }
.workspace { flex: 1 1 auto; overflow-y: auto; }
</style>
```

> Task 6 会往 `.main` 里再加一个底部的状态栏，届时 `App.vue` 再改一次。

- [x] **Step 13: 跑全部前端测试 + 手动看一眼**

Run:
```powershell
cd frontend
pnpm test
pnpm tauri dev
```

Expected: 测试 `Tests  25 passed`（8 主题 + 5 导航 + 6 侧边栏 + 3 工具条 + 3 router）；窗口里出现左侧深色侧边栏（四个分组、「人体图」带「预留」角标、「我的」贴底带分隔线）+ 顶部工具条显示当前页名；点各分组能切页，工具条跟着变。

> **计数校正（2026-10-16，Task 7 复核实测）**：这里原先写的是 `Tests  20 passed`
> （8 主题 + 5 导航 + 4 侧边栏 + 3 工具条），两个数都过时了：Task 3 实际产出
> **侧边栏 6 条**，外加计划里没预料到的 **`router.spec.ts` 3 条**。所以 Task 3
> 结束时是 **25 条**（与 Task 6 Step 7 那张 per-file 表一致）。

- [x] **Step 14: 提交**

```bash
git add frontend/src
git commit -m "feat(frontend): 侧边栏分组与路由骨架，布局按设计文档冻结

- 导航用数据驱动（nav.ts）：分组、顺序、钉底、预留标记都在一处，
  改布局不用翻模板；路由表由它派生
- 「设置」与「关于与致谢」收进底部「我的」并用分隔线隔开
- 人体图项带「预留」角标（决定 5：3D 排第二阶段，界面位先冻结）
- 9 个占位页，每个都写明归属哪份后续计划"
```

---

## Task 4: Rust 侧端口协商（含单元测试）

**本计划唯一有真实逻辑的地方**，也是唯一能被自动化测试直接覆盖的地方。独立成文件，不依赖 Tauri 运行时。

**Files:**
- Create: `frontend/src-tauri/src/backend.rs`
- Modify: `frontend/src-tauri/src/lib.rs`

- [x] **Step 1: 写失败的测试**

在 `frontend/src-tauri/src/backend.rs` 里**先只写测试与函数签名**：

```rust
//! 后端子进程的拉起与端口协商。
//!
//! 协议（由计划 1 的 `PortReporter` 定义，改动等于改协议）：
//! 后端把一行 `CHANGE_PORT=<端口>` 打到**标准输出**，外壳读它来定位后端。
//!
//! 这里刻意不依赖 Tauri 运行时——端口解析是本计划唯一有真实逻辑的地方，
//! 抽成纯函数才能被 `cargo test` 直接覆盖（起 Tauri 运行时的测试跑不动 CI）。

use std::path::PathBuf;

/// 协议前缀。**改动等于改协议**，会让外壳找不到后端。
pub const PORT_PREFIX: &str = "CHANGE_PORT=";

/// 从后端 stdout 的一行里解析端口。
///
/// 返回 `None` 表示这一行不是协议行（外壳应当继续读下一行）。
///
/// **为什么必须 `trim`**（以下三条是 2026-10-16 在本机实测出来的，不是推断）：
///
/// 1. 后端输出的那一行以 `\r\n` 结尾。按字节捕获真实后端 stdout 实测为
///    `b'CHANGE_PORT=54791\r\n'`（19 字节 = 17 个可见字节 + `0x0d 0x0a`）。
/// 2. 同一个函数、同一份输入，两种调用方拿到的字符串**不一样**：
///    - `BufRead::lines()` 剥掉 `\r\n` → `"CHANGE_PORT=54791"`（17 字节）
///    - `BufRead::read_line` **什么都不剥** → `"CHANGE_PORT=54791\r\n"`（19 字节）
///      （实测：`n=19, len=19`；多行输入下循环读到 `n=18 …\r\n` 与 `n=5 "more\n"`）
/// 3. 这个函数是 `pub` 的纯函数，它的正确性不该取决于谁调用它、以及那个人用了
///    `lines()` 还是 `read_line`。所以它自己 `trim`，把这件事关在函数内部——
///    调用方随便用哪种，行为都一样。
///
/// > 计划里曾有三处说法不成立，均已按实测改写：
/// > (a) 「Windows 上是 18 字节」——实测 **19** 字节（含 `\n`；不含 `\n` 是 18）。
/// > (b) 「Java 的 `Integer.parseInt` 恰好容忍尾部 `\r`」——实测**抛
/// >     `NumberFormatException`**（OpenJDK 25.0.2 直接 `javac`/`java` 跑）。
/// > (c) 「`read_line` 只剥 `\n`」——实测**它一个都不剥**，`\n` 与 `\r` 都留在
/// >     字符串里。剥掉 `\r\n` 的只有 `lines()`。
/// > 另外本链路里**根本没有 `parseInt`**：`PortReporter` 是生产者，它 `println` 的
/// > 是 Spring 给它的 `local.server.port` 字符串，不做任何解析——(b) 与这条链路无关。
/// > 详见 `docs/superpowers/plans/2026-10-16-change-frontend-shell.md` Task 4 的实测记录。
pub fn parse_port_line(line: &str) -> Option<u16> {
    todo!("Task 4 Step 3 实现")
}

/// 解析 Java 可执行文件的位置。
///
/// 顺序：显式配置 → `JAVA_HOME` → PATH 上的 `java`。
///
/// **返回 `PathBuf` 而不是 `Option`**：最后那一档（交给 PATH）永远不会失败——
/// 真找不到的话，是 `Command::spawn` 抛出的 OS 错误，而且那条消息比我们在这里
/// 猜的任何话都准。返回 `Option` 只会制造一个「找不到」的假分支。
///
/// **不做 `is_file()` 检查**：`JAVA_HOME` 指向一个 JDK 是很强的信号，而且
/// 检查存在性会让这个函数**没法用假路径测试**（测试里那个路径当然不存在）。
///
/// 参数是显式传入的，方便测试——不要在函数里直接读 `std::env`。
pub fn resolve_java(configured: Option<&str>, java_home: Option<&str>) -> PathBuf {
    todo!("Task 4 Step 3 实现")
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- parse_port_line ----

    #[test]
    fn parses_a_plain_protocol_line() {
        assert_eq!(parse_port_line("CHANGE_PORT=8080"), Some(8080));
    }

    #[test]
    fn tolerates_whitespace_right_after_the_prefix() {
        // 真实后端输出是 `CHANGE_PORT=54791\r\n`，经 line.trim() 后 \r 已经没了，
        // 所以 rest.trim() 对它是冗余的。它实际多容忍的是「前缀后带空格」——
        // 如果后端将来改成 `PREFIX + " " + port` 之类，这一步能救回来。
        assert_eq!(parse_port_line("CHANGE_PORT= 8080"), Some(8080));
        assert_eq!(parse_port_line("CHANGE_PORT=\t8080"), Some(8080));
    }

    #[test]
    fn tolerates_the_windows_carriage_return() {
        // 实测：Windows 上这一行是 19 字节且以 \r\n 结尾；read_line 什么都不剥
        // （\r 与 \n 都留着），所以行尾的 \r 只能靠 line.trim() 处理。
        assert_eq!(parse_port_line("CHANGE_PORT=56934\r"), Some(56934));
    }

    #[test]
    fn tolerates_surrounding_whitespace() {
        assert_eq!(parse_port_line("  CHANGE_PORT=1  \r"), Some(1));
    }

    #[test]
    fn accepts_the_full_port_range() {
        assert_eq!(parse_port_line("CHANGE_PORT=1"), Some(1));
        assert_eq!(parse_port_line("CHANGE_PORT=65535"), Some(65535));
    }

    #[test]
    fn rejects_a_port_above_u16() {
        assert_eq!(parse_port_line("CHANGE_PORT=65536"), None);
    }

    #[test]
    fn rejects_zero_because_os_never_assigns_it() {
        // 后端用 port=0 让操作系统分配，所以它上报的一定是分配后的真实端口，不会是 0。
        assert_eq!(parse_port_line("CHANGE_PORT=0"), None);
    }

    #[test]
    fn rejects_non_protocol_lines() {
        // 后端日志走的是 stdout，外壳会读到几十行 INFO，必须全部忽略。
        assert_eq!(parse_port_line("2026-10-16T12:00:00  INFO ... Started ChangeApplication"), None);
        assert_eq!(parse_port_line(""), None);
        assert_eq!(parse_port_line("CHANGE_PORT"), None);
        assert_eq!(parse_port_line("CHANGE_PORT="), None);
        assert_eq!(parse_port_line("CHANGE_PORT=abc"), None);
        assert_eq!(parse_port_line("CHANGE_PORT=-1"), None);
        assert_eq!(parse_port_line("CHANGE_PORT=80 80"), None);
        assert_eq!(parse_port_line("CHANGE_PORT=80.5"), None);
    }

    #[test]
    fn rejects_a_lookalike_prefix() {
        // 改一个字符就是改协议——这条测试守住前缀本身。
        assert_eq!(parse_port_line("CHANGE_PORTX=8080"), None);
        assert_eq!(parse_port_line("change_port=8080"), None);
    }

    #[test]
    fn every_valid_port_round_trips() {
        // 65535 次迭代，毫秒级。比抽几个点更能守住 u16 的边界。
        for p in 1u16..=u16::MAX {
            assert_eq!(
                parse_port_line(&format!("{PORT_PREFIX}{p}")),
                Some(p),
                "端口 {p} 解析失败"
            );
        }
    }

    #[test]
    fn every_port_above_u16_is_rejected() {
        for p in 65_536u32..=100_000 {
            assert_eq!(
                parse_port_line(&format!("{PORT_PREFIX}{p}")),
                None,
                "{p} 不该被接受"
            );
        }
    }

    // ---- 与真实后端输出的对照（额外要求 C）----

    /// 真实后端 stdout 里的协议行。
    ///
    /// 不是构造的：2026-10-16 用 Python `subprocess`（`stdout=PIPE`）起
    /// `change-backend-0.1.0-SNAPSHOT.jar`，按字节读到的那一行就是
    /// `b'CHANGE_PORT=54791\r\n'`——19 字节，末两字节 `0x0d 0x0a`，耗时 5.70 秒。
    /// 端口是随机的（`server.port=0`），所以下面用 `PORT_PREFIX` 拼接来断言，
    /// 只钉死**行尾形态**与解析结果。
    const REAL_BACKEND_LINE: &[u8] = b"CHANGE_PORT=54791\r\n";

    #[test]
    fn parses_the_real_backend_bytes_when_using_lines() {
        use std::io::{BufRead, BufReader, Cursor};

        // lines() 剥掉 \r\n，调用方拿到干净的 17 字节。
        let first = BufReader::new(Cursor::new(REAL_BACKEND_LINE))
            .lines()
            .next()
            .unwrap()
            .unwrap();
        assert_eq!(first, format!("{PORT_PREFIX}54791"));
        assert_eq!(parse_port_line(&first), Some(54791));
    }

    #[test]
    fn parses_the_real_backend_bytes_when_using_read_line() {
        use std::io::{BufRead, BufReader, Cursor};

        // read_line 什么都不剥：\r 与 \n 都留在字符串里（实测 len=19）。
        // 注意 reader 必须绑定到变量，否则读到的是别的语义。
        let mut reader = BufReader::new(Cursor::new(REAL_BACKEND_LINE));
        let mut line = String::new();
        let n = reader.read_line(&mut line).unwrap();
        assert_eq!(n, 19);
        assert_eq!(line, format!("{PORT_PREFIX}54791\r\n"));
        assert_eq!(line.parse::<u16>().ok(), None); // 不 trim 就解析不出来
        assert_eq!(parse_port_line(&line), Some(54791)); // 函数自己 trim，解析得出
    }

    // ---- resolve_java ----

    #[test]
    fn prefers_the_configured_java() {
        assert_eq!(
            resolve_java(Some("/custom/bin/java"), Some("/jh")),
            PathBuf::from("/custom/bin/java")
        );
    }

    #[test]
    fn ignores_an_empty_configured_value() {
        // 环境变量设成空串是常见的（`$env:CHANGE_JAVA = ''`），不该当成配置
        assert_eq!(resolve_java(Some(""), Some("/jh")), java_in("/jh"));
    }

    #[test]
    fn falls_back_to_java_home() {
        // JAVA_HOME 指向 JDK 根目录，java 在 bin/ 下
        assert_eq!(resolve_java(None, Some("/jh")), java_in("/jh"));
    }

    #[test]
    fn ignores_an_empty_java_home() {
        // 与 configured 那条同理：`$env:JAVA_HOME = ''` 是常见的，
        // 空串不该被当成一个有效的 JDK 根目录（那会拼出 `\bin\java.exe` 这种相对路径）。
        assert_eq!(resolve_java(None, Some("")), PathBuf::from("java"));
    }

    #[test]
    fn falls_back_to_java_on_path() {
        // 最后一档：交给操作系统去找。刻意不做 is_file 检查——
        // 那会让上面两条用假路径的测试没法写。
        assert_eq!(resolve_java(None, None), PathBuf::from("java"));
    }

    /// `JAVA_HOME` 下 java 的期望路径。Windows 上带 `.exe`。
    fn java_in(home: &str) -> PathBuf {
        let exe = if cfg!(windows) { "java.exe" } else { "java" };
        PathBuf::from(home).join("bin").join(exe)
    }
}
```

- [x] **Step 2: 跑测试，确认它失败**

Run: `cd frontend/src-tauri && cargo test`

Expected: 编译通过但 **18 条测试全部 panic**，消息是 `not yet implemented: Task 4 Step 3 实现`。

（`todo!()` 让「先写测试、确认它失败」这一步在 Rust 里也能成立——编译不会挡住你。）

- [x] **Step 3: 写实现（把两个 `todo!()` 换掉）**

```rust
pub fn parse_port_line(line: &str) -> Option<u16> {
    let rest = line.trim().strip_prefix(PORT_PREFIX)?;
    let port: u16 = rest.trim().parse().ok()?;
    // 0 不是操作系统会分配的端口，出现它说明这不是一条有效协议行
    if port == 0 {
        None
    } else {
        Some(port)
    }
}

pub fn resolve_java(configured: Option<&str>, java_home: Option<&str>) -> PathBuf {
    if let Some(p) = configured {
        if !p.is_empty() {
            return PathBuf::from(p);
        }
    }
    if let Some(home) = java_home {
        if !home.is_empty() {
            let exe = if cfg!(windows) { "java.exe" } else { "java" };
            return PathBuf::from(home).join("bin").join(exe);
        }
    }
    // 最后一档：交给 PATH。真找不到的话，spawn 会抛出比我们猜的更准的 OS 错误。
    PathBuf::from("java")
}
```

> `rejects_zero_because_os_never_assigns_it` 与 `accepts_the_full_port_range` 这两条一起，把「0 拒绝、1 与 65535 接受、65536 拒绝」这个边界钉死了。

- [x] **Step 4: 跑测试，确认通过**

Run: `cd frontend/src-tauri && cargo test`

Expected: `test result: ok. 18 passed`

- [x] **Step 5: 提交**

```bash
git add frontend/src-tauri/src/backend.rs \
        docs/superpowers/plans/2026-10-16-change-frontend-shell.md
git commit -m "feat(shell): 端口协商的解析逻辑，单元测试覆盖协议边界

- parse_port_line 独立成纯函数，不依赖 Tauri 运行时，cargo test 直接覆盖
- 实测：后端那一行是 b'CHANGE_PORT=54791\r\n'（19 字节），read_line 连 \n 都不剥，
  所以函数自己 trim，正确性不取决于调用方用 lines() 还是 read_line
- 边界：0 拒绝（OS 不会分配）、65535 接受、65536 拒绝
- 前缀本身被两条 lookalike 测试守住（改一个字符就是改协议）
- 计划文档一并入库：三处被证伪的说法都换成实测结果（parseInt 不容忍 \r、链路里没有
  parseInt、read_line 一个都不剥），后端输出长度 18 → 19"
```

> 上一轮只 `git add frontend/src-tauri/src/backend.rs`，计划文档的改写没入库——
> 这次把它一起提交（fence 与磁盘逐字一致，用脚本比对，不靠目测）。

### Task 4 的实测记录（2026-10-16，实施时补的三条独立实验）

原先这里写着一句「Windows 上是 18 字节，Java 的 `Integer.parseInt` 恰好容忍尾部 `\r`，
所以这个坑只在 Rust 侧暴露」。**实施时对三条各做了独立实测，结论是这句话有三处不成立。**
保留这段记录，是因为错的注释会被后来人当事实——以下是可复现的实测结果。

**A1：`Integer.parseInt("123\r")` 会不会抛异常？→ 会抛。**

```java
public class ParseIntCr {
    public static void main(String[] args) {
        String[] cases = { "123\r", "123\n", "123\r\n", "123 " };
        for (String c : cases) {
            String label = c.replace("\r", "\\r").replace("\n", "\\n");
            try {
                System.out.println("parseInt(\"" + label + "\") = " + Integer.parseInt(c));
            } catch (Throwable t) {
                System.out.println("parseInt(\"" + label + "\") THREW " + t.getClass().getName()
                        + ": " + t.getMessage());
            }
        }
    }
}
```

OpenJDK 25.0.2，`javac` 编译后 `java` 直跑的原始输出：

```
parseInt("123\r") THREW java.lang.NumberFormatException: For input string: "123"
parseInt("123\n") THREW java.lang.NumberFormatException: For input string: "123
"
parseInt("123\r\n") THREW java.lang.NumberFormatException: For input string: "123
"
parseInt("123 ") THREW java.lang.NumberFormatException: For input string: "123 "
```

`parseInt` 不容忍任何空白——`\r`、`\n`、空格全部抛 `NumberFormatException`。
**原说法反了。**

**A2：整条链路里到底有没有 `parseInt`？→ 一处都没有。**

`backend/src/main/java/com/change/config/PortReporter.java` 全文只有：

```java
String port = env.getProperty("local.server.port");   // 已经是 String
System.out.println(PREFIX + port);                    // 只输出，不解析
System.out.flush();
```

全仓 `grep parseInt` 在后端源码里**零命中**。后端是**生产者**：它 `println` 一个字符串。
所以「Java 的 parseInt 容忍 `\r`」这句话**与这条链路无关**——链路里没有任何一步在解析端口。

**A3：`read_line` 与 `lines()` 各自留下什么？→ `lines()` 剥 `\r\n`，`read_line` 什么都不剥。**

同一份输入 `b"CHANGE_PORT=1234\r\nmore\n"`，`{:?}` 打印的实际字节：

```
read_line             : n=18 bytes="CHANGE_PORT=1234\r\n" len=18
lines()               : bytes="CHANGE_PORT=1234" len=16
read_line（纯 LF 输入）: bytes="CHANGE_PORT=1234\n" len=17
```

**这里连原计划的前提也错了**：计划写「`read_line` 不剥 `\n`、只留下 `\r`」，
实测 **`read_line` 连 `\n` 都没剥**，两个字符都留在字符串里；剥掉 `\r\n` 的只有 `lines()`。

**A4：`trim()` 的正当理由（与上面那句错话无关，所以实现不变）。**

`parse_port_line` 是 `pub` 的纯函数，它的正确性不该取决于**调用方用了 `lines()` 还是
`read_line`**：两者对同一份后端输出给出不同的字符串。函数自己 `trim`，把这件事关在函数内部。
`trim()` 保留，理由换成这一条。

**C：真实后端 stdout 的原始字节。**

`mvn -B -q -DskipTests package` 后，用 Python `subprocess`（`stdout=PIPE`）起 jar，
按字节读第一行，5.70 秒后拿到并 `terminate()`：

```
raw repr  = b'CHANGE_PORT=54791\r\n'
raw bytes = 0x43 0x48 0x41 0x4e 0x47 0x45 0x5f 0x50 0x4f 0x52 0x54 0x3d 0x35 0x34 0x37 0x39 0x31 0x0d 0x0a
length    = 19
endswith \r\n = True
```

**19 字节**，不是 18（18 是不含 `\n` 时的长度）。这段字节被钉进了常驻测试
`REAL_BACKEND_LINE`，端口部分用 `PORT_PREFIX` 拼，只钉行尾形态。

**D：变异测试——第一轮留下两个覆盖缺口（已关掉，见 E）。**

第一轮的变异测试报告里，`rest.trim()` 与 `resolve_java` 里的 `!home.is_empty()`
两个变异**存活**：现有测试要么喂了已整行 `trim` 过的输入（`\r` 在 `line.trim()`
那一步就没了），要么从未给 `java_home` 传过 `Some("")`。
当时**如实报告了存活**，没有为了表格好看而跳过。

**E：关掉两个缺口（2026-10-16 补测轮）。**

各补一条测试钉住那两个分支，并逐个删掉被测代码，确认测试**变红**（原始输出见提交说明）：

| 缺口 | 补的测试 | 删掉什么才变红 |
|---|---|---|
| `rest.trim()` 冗余宽松（对真实输出而言） | `tolerates_whitespace_right_after_the_prefix` | 把 `rest.trim()` 删成 `rest` |
| `resolve_java` 的空串 `JAVA_HOME` 分支 | `ignores_an_empty_java_home` | 删掉 `!home.is_empty()` |

`rest.trim()` 的真实贡献**不是** `\r`（那个 `line.trim()` 就已经处理掉），而是
「前缀后带空格」：`CHANGE_PORT= 8080`、`CHANGE_PORT=\t8080`。**它是有意的，不是冗余的**，
所以保留实现、补测试守住它——而不是删掉它。同理，空串 `JAVA_HOME`
（`$env:JAVA_HOME = ''` 很常见）不该被当成有效的 JDK 根目录，否则会拼出
`\bin\java.exe` 这种相对路径。

至此 Task 4 的测试计数是 **16 + 2 = 18**；Task 5 完成后的总数是 18 + 14 = **32**
（外加 1 条 `#[ignore]` 的真实后端 e2e，`cargo test` 默认不跑它；磁盘上 `#[test]` 合计 33）。

> **计数校正（2026-10-16，Task 7 复核实测）**：这一段原先写的是「18 + 6 = **24**」。
> 那个 6 是**计划栅栏里**的条数，而实现实际补到 **14** 条。实测输出：
>
> ```
> test result: ok. 32 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.33s
> ```
>
> 同一段里原先那两个数（`22 passed`、`17 passed`）与 Task 5 的 Step 4 / Step 6
> 都只按栅栏里的 6 条算，一并在本次复核里改成 32。

---

## Task 5: 拉起后端、读端口、失败处理

把 Task 4 的纯函数接到真实子进程上。**这一步的核心是「失败时怎么办」**——计划 1 的审查专门记过：协议行缺失是**静默**的，外壳只做「等前缀」会无限等待。

**Files:**
- Modify: `frontend/src-tauri/src/backend.rs`（追加子进程部分）
- Modify: `frontend/src-tauri/src/lib.rs`
- Test: `frontend/src-tauri/src/backend.rs`（同文件追加测试）

- [x] **Step 1: 写失败的测试**

在 `backend.rs` 的 `mod tests` 里追加：

```rust
    use std::time::Duration;

    // ---- BackendState 的状态机 ----

    #[test]
    fn starts_in_the_starting_state() {
        let s = BackendState::starting();
        assert!(matches!(s.status, BackendStatus::Starting));
        assert_eq!(s.port, None);
    }

    #[test]
    fn becomes_ready_when_a_port_arrives() {
        let s = BackendState::starting().with_port(12345);
        assert!(matches!(s.status, BackendStatus::Ready));
        assert_eq!(s.port, Some(12345));
    }

    #[test]
    fn becomes_failed_when_the_child_exits_without_a_protocol_line() {
        // 实测过这个场景：加 --spring.main.web-application-type=none 时，
        // 后端跑完初始化、4.6 秒后 exit 0，且**不打协议行**（只打一行不含前缀的 WARN）。
        // 外壳若只匹配前缀就会永远等下去——所以「子进程退出而没有协议行」必须判失败。
        let s = BackendState::starting().child_exited(Some(0));
        assert!(matches!(s.status, BackendStatus::Failed { .. }));
    }

    #[test]
    fn becomes_failed_on_timeout() {
        let s = BackendState::starting().timed_out();
        assert!(matches!(s.status, BackendStatus::Failed { .. }));
        assert!(s.message.contains("超时"));
    }

    #[test]
    fn a_ready_state_is_never_overwritten_by_a_later_failure() {
        // 后端起来了之后子进程才崩，不该把已经就绪的状态改回失败——
        // 那时前端已经在用它了。这个守卫让「就绪」是单向的。
        let s = BackendState::starting().with_port(1000).child_exited(Some(1));
        assert!(matches!(s.status, BackendStatus::Ready));
    }

    #[test]
    fn the_timeout_is_long_enough_for_a_cold_start() {
        // 计划 1 实测后端启动 3.8–6.8 秒。超时给 30 秒足够宽松，
        // 但**不能**短到把慢启动误判成失败。
        assert!(STARTUP_TIMEOUT >= Duration::from_secs(15));
    }
```

- [x] **Step 2: 跑测试，确认失败**

Run: `cd frontend/src-tauri && cargo test`

Expected: 编译失败 —— `cannot find type BackendState` / `cannot find value STARTUP_TIMEOUT`

- [x] **Step 3: 写实现**

在 `backend.rs` 顶部（`use` 之后）加：

```rust
use serde::Serialize;
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// 等协议行的上限。计划 1 实测后端启动 3.8–6.8 秒，30 秒足够宽松。
pub const STARTUP_TIMEOUT: Duration = Duration::from_secs(30);

/// 后端状态。前端轮询它决定显示什么。
///
/// **不要加 `#[serde(tag = ...)]`**——那会把它序列化成 `{"state":"starting"}`（对象），
/// 而前端的 `BackendState.status` 期望的是字符串 `"starting"`。
/// 单元枚举的默认表示就是字符串，这里只改大小写风格即可。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum BackendStatus {
    /// 正在拉起后端（前端显示加载提示）。
    Starting,
    /// 后端已就绪，`port` 可用。
    Ready,
    /// 起不来。`message` 是给人看的原因。
    Failed,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendState {
    pub status: BackendStatus,
    pub port: Option<u16>,
    pub message: String,
}

impl BackendState {
    pub fn starting() -> Self {
        Self {
            status: BackendStatus::Starting,
            port: None,
            message: "正在启动后端…".into(),
        }
    }

    /// 拿到端口 → 就绪。**已就绪时不再改状态**（就绪是单向的）。
    pub fn with_port(mut self, port: u16) -> Self {
        if matches!(self.status, BackendStatus::Ready) {
            return self;
        }
        self.status = BackendStatus::Ready;
        self.port = Some(port);
        self.message = format!("后端已连接 · 端口 {port}");
        self
    }

    /// 子进程退出。**若还没有端口，这就是失败**——外壳只等前缀会无限等下去。
    pub fn child_exited(mut self, code: Option<i32>) -> Self {
        if matches!(self.status, BackendStatus::Ready) {
            return self;
        }
        let how = match code {
            Some(c) => format!("退出码 {c}"),
            None => "被信号终止".to_string(),
        };
        self.status = BackendStatus::Failed;
        self.message = format!(
            "后端启动失败：进程已{how}，但没有输出 {PORT_PREFIX}<端口> 这一行。\
             请确认 jar 路径与 Java 可用，或查看后端自己的日志。"
        );
        self
    }

    pub fn timed_out(mut self) -> Self {
        if matches!(self.status, BackendStatus::Ready) {
            return self;
        }
        self.status = BackendStatus::Failed;
        self.message = format!(
            "后端启动超时（{} 秒内没有输出 {PORT_PREFIX}<端口>）。",
            STARTUP_TIMEOUT.as_secs()
        );
        self
    }

    pub fn failed(msg: impl Into<String>) -> Self {
        Self { status: BackendStatus::Failed, port: None, message: msg.into() }
    }
}

/// 后端子进程的句柄与状态。放在 Tauri 的 managed state 里。
pub struct Backend {
    pub state: Arc<Mutex<BackendState>>,
    child: Mutex<Option<Child>>,
}

impl Backend {
    /// 拉起后端，并在后台线程里逐行读它的 stdout 找协议行。
    ///
    /// **stdout 会同时转发到外壳自己的 stdout**——调试时能在终端看到后端的日志，
    /// 而不会因为「被外壳吃掉了」而什么都看不到。
    pub fn spawn(java: PathBuf, jar: PathBuf, extra_args: Vec<String>) -> std::io::Result<Self> {
        let state = Arc::new(Mutex::new(BackendState::starting()));

        let mut child = Command::new(&java)
            .arg("-jar")
            .arg(&jar)
            .args(&extra_args)
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .stdin(Stdio::null())
            .spawn()?;

        let stdout = child.stdout.take().expect("stdout 已设为 piped");

        let reader_state = Arc::clone(&state);
        std::thread::spawn(move || {
            let start = Instant::now();
            let reader = BufReader::new(stdout);
            let mut got_port = false;

            for line in reader.lines() {
                let Ok(line) = line else { break };
                // 转发给外壳自己的 stdout，方便调试（后端的日志走的就是 stdout）
                println!("{line}");

                if let Some(port) = parse_port_line(&line) {
                    got_port = true;
                    let mut s = reader_state.lock().unwrap();
                    *s = s.clone().with_port(port);
                    break;
                }

                if start.elapsed() > STARTUP_TIMEOUT {
                    let mut s = reader_state.lock().unwrap();
                    *s = s.clone().timed_out();
                    return;
                }
            }

            // stdout 读到 EOF（子进程退出）而没拿到端口 —— 这是失败，不是「继续等」
            if !got_port {
                let mut s = reader_state.lock().unwrap();
                if matches!(s.status, BackendStatus::Starting) {
                    *s = s.clone().timed_out();
                }
            }
        });

        Ok(Self { state, child: Mutex::new(Some(child)) })
    }

    /// 关闭时调用。
    ///
    /// **这是硬杀（`TerminateProcess`），不是优雅关闭。**
    /// 设计文档的启动时序第 4 步写的是「先通知后台把数据落盘，再退出」——
    /// 那需要一个后端侧的关闭端点（比如 `POST /api/shutdown`），而**后端现在没有**。
    ///
    /// 为什么这一步先用硬杀：SQLite 在 WAL 模式下是**崩溃安全**的——强杀只会留下
    /// `-wal`/`-shm` 两个旁挂文件，下次打开时自动恢复，不会损坏数据库。
    /// 而「优雅关闭」要动后端（本计划的范围是前端与外壳）。
    ///
    /// **这是一条登记在案的缺口，不是忘了**：真正的做法是后端加一个关闭端点
    /// （或监听 stdin 的 EOF），外壳先请求它、等一小会儿、再兜底强杀。
    /// 那件事应该和「数据落盘时机」一起在计划 7（打包与分发）之前解决。
    pub fn shutdown(&self) {
        let mut guard = self.child.lock().unwrap();
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}
```

- [x] **Step 4: 跑测试，确认通过**

Run: `cd frontend/src-tauri && cargo test`

Expected: `test result: ok. 32 passed`（Task 4 的 18 条 + 本任务的 14 条；另有 1 条 `#[ignore]` 的真实后端 e2e 不计入）

> **计数校正（2026-10-16，Task 7 复核实测）**：这里原先写的是 `24 passed`（18 + 6）。
> 本任务的栅栏只给了 6 条，实现实际补到 14 条；实测 `32 passed; 0 failed; 1 ignored`。

- [x] **Step 5: 在 `lib.rs` 里接线：加 `get_backend_status` 命令、启动时拉起后端、退出时关掉**

```rust
mod backend;

use backend::{Backend, BackendState};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::{Manager, RunEvent};

/// 前端轮询它。**端口只从这里出去**——前端不许硬编码端口。
#[tauri::command]
fn get_backend_status(backend: tauri::State<'_, Backend>) -> BackendState {
    backend.state.lock().unwrap().clone()
}

/// 找后端 jar。优先环境变量，其次相对仓库的默认位置。
fn locate_jar() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("CHANGE_BACKEND_JAR") {
        let path = PathBuf::from(&p);
        return if path.is_file() {
            Ok(path)
        } else {
            Err(format!("CHANGE_BACKEND_JAR 指向的文件不存在：{p}"))
        };
    }
    // 开发时的默认位置：src-tauri 往上三层是仓库根
    let candidates = [
        "../../backend/target/change-backend-0.1.0-SNAPSHOT.jar",
        "../backend/target/change-backend-0.1.0-SNAPSHOT.jar",
    ];
    for c in candidates {
        let path = PathBuf::from(c);
        if path.is_file() {
            return Ok(path);
        }
    }
    Err(format!(
        "找不到后端 jar。请先在 backend/ 下执行 `mvn -B -q -DskipTests package`，\
         或设环境变量 CHANGE_BACKEND_JAR 指向它。（已尝试：{}）",
        candidates.join(" 与 ")
    ))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![get_backend_status])
        .setup(|app| {
            let java = backend::resolve_java(
                std::env::var("CHANGE_JAVA").ok().as_deref(),
                std::env::var("JAVA_HOME").ok().as_deref(),
            )
            .ok_or("找不到 Java")?;

            let backend = match locate_jar() {
                Ok(jar) => {
                    let extra = std::env::var("CHANGE_BACKEND_ARGS")
                        .map(|s| s.split_whitespace().map(String::from).collect())
                        .unwrap_or_default();
                    match Backend::spawn(java, jar, extra) {
                        Ok(b) => b,
                        Err(e) => Backend::from_failed(format!("拉起后端进程失败：{e}")),
                    }
                }
                Err(msg) => Backend::from_failed(msg),
            };

            app.manage(backend);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("Tauri 应用启动失败")
        .run(|app_handle, event| {
            // 窗口关闭时把后端一起带走，别留孤儿进程
            if let RunEvent::ExitRequested { .. } | RunEvent::Exit = event {
                if let Some(b) = app_handle.try_state::<Backend>() {
                    b.shutdown();
                }
            }
        });
}
```

同时给 `Backend` 补一个「没有子进程」的构造，供上面的错误分支用（加在 `impl Backend` 里）：

```rust
    /// 造一个「没有子进程」的后端句柄，只用来承载失败状态。
    ///
    /// **必须用这个构造函数，不要用结构体更新语法 `..Backend::placeholder()`**——
    /// `child` 字段对 `backend` 模块之外是私有的，结构体更新会编译不过。
    pub fn from_failed(msg: impl Into<String>) -> Self {
        Self {
            state: Arc::new(Mutex::new(BackendState::failed(msg))),
            child: Mutex::new(None),
        }
    }
```

- [x] **Step 6: 编译 + 单元测试都过**

Run:
```powershell
cd frontend/src-tauri
cargo test
cargo build
```

Expected: `cargo test` → 32 passed（另有 1 条 `#[ignore]` 不计入）；`cargo build` → 编译通过。

- [x] **Step 7: 提交**

```bash
git add frontend/src-tauri/src
git commit -m "feat(shell): 拉起后端子进程并读 stdout 的 CHANGE_PORT

- 子进程 stdout 逐行读、同时转发到外壳 stdout（调试时能看见后端日志）
- 三条失败路径都显式处理：超时、子进程退出而无协议行、jar/java 找不到
  计划 1 的审查记过：协议行缺失是静默的，只等前缀会无限等下去
- 「就绪」是单向的：后端起来之后子进程再崩也不把状态改回失败
- 窗口退出时 kill 子进程，不留孤儿
- stdout 用 inherit 给 stderr，避免管道缓冲把后端日志憋住"
```

---

## Task 6: 加载提示与健康端点接线

**这一步之后你就第一次能「看见」东西了。**

**Files:**
- Create: `frontend/src/api/backend.ts`
- Create: `frontend/src/components/BackendStatusBar.vue`
- Modify: `frontend/src/App.vue`
- Test: `frontend/src/api/backend.spec.ts`
- Test: `frontend/src/components/BackendStatusBar.spec.ts`
- Test: `frontend/src/App.spec.ts`（**本任务新增的整壳测试**，见 Step 6b）

> **Step 6b 为什么必须存在**：Task 3 把 `<AppToolbar />` 加进 `App.vue`，但
> `AppToolbar.spec.ts` 是**单独 mount 那个组件**的——它会照样绿，而模板里漏掉
> `<AppToolbar />` 这行改动**没有任何测试会红**，工具条在真机上静默消失。
> 也就是说，**在 Task 6 之前没有任何测试 mount 过完整的 `App.vue`**。
> 补 `App.spec.ts` 就是把整壳结构（侧边栏 / 工具条 / 工作区 / 状态栏四者都在、
> 且层级与顺序正确）钉住，让这类「模板里漏了一行」的改动变成红的。

- [x] **Step 1: 写失败的测试（API 客户端）**

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// `checkHealth` 现在走 Tauri 的 invoke，不再自己发 HTTP。
//
// 这条改动的原因是**真机验收发现的缺陷**：页面的源（dev 是
// `http://127.0.0.1:1420`，打包后是 `tauri://localhost`）与后端随机端口不同源，
// 而响应没有 `Access-Control-Allow-Origin`，浏览器把响应拦掉，前端只看到
// `TypeError: Failed to fetch`。修法是把这条请求交给外壳（Rust）去发——
// 详见 `src-tauri/src/http.rs` 的模块注释。
//
// 所以这个文件不再 stub `fetch`，改为 mock `@tauri-apps/api/core` 的 `invoke`。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { checkHealth, describeState, type BackendState } from './backend'

const invokeMock = vi.mocked(invoke)

describe('describeState', () => {
  it('starting 时给出加载中文案', () => {
    const s: BackendState = { status: 'starting', port: null, message: '正在启动后端…' }
    expect(describeState(s).tone).toBe('loading')
  })

  it('ready 时带上端口', () => {
    const s: BackendState = { status: 'ready', port: 51234, message: '' }
    const d = describeState(s)
    expect(d.tone).toBe('ok')
    expect(d.text).toContain('51234')
  })

  it('failed 时显示原因', () => {
    const s: BackendState = { status: 'failed', port: null, message: '后端启动超时' }
    const d = describeState(s)
    expect(d.tone).toBe('error')
    expect(d.text).toBe('后端启动超时')
  })

  it('遇到没见过的状态时显示出来，而不是渲染成空白', () => {
    // Rust 侧加了第四个 BackendStatus 变体而忘了同步这里时，
    // 旧的穷尽 switch 会返回 undefined：状态栏一片空白、没有任何提示。
    // 这条测试守的是「宁可显示看不懂，也不要静默变空」。
    //
    // **它与「喂想象出来的对象去测正常路径」不同**：那种测试是拿假数据
    // 冒充真实输入（假数据一变，测试仍然绿，等于没测）。这里喂的假状态
    // 不是要冒充哪个真实状态，而是**专门去走 `default` 这个真实存在的
    // 防御分支**——它测的是代码里那条防御路径本身，不是某条正常路径。
    const weird = { status: 'hibernating', port: null, message: '' } as unknown as BackendState
    const d = describeState(weird)
    expect(d.tone).toBe('error')
    expect(d.text).toContain('hibernating')
  })

  // 跨语言契约：Rust 的 BackendState 与这里的 interface 是**两处手写定义**，
  // 它们可以漂移而没有任何东西会报警（TS 不校验线上来的 JSON）。
  // 唯一能证明两边形状一致的方式，是拿 Rust 真的序列化出来的字符串喂进来。
  //
  // 这三段 JSON 是从 Rust 侧实测拿到的（见 src-tauri/src/backend.rs 的
  // the_state_serializes_with_status_as_a_plain_string），不是手写的。
  // 原文（原样复制，未改一个字符）：
  //   {"status":"starting","port":null,"message":"正在启动后端…"}
  //   {"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}
  //   {"status":"failed","port":null,"message":"后端启动失败：进程已退出（退出码 1），但没有输出 CHANGE_PORT=<端口> 这一行。请确认 jar 路径与 Java 可用，或查看后端自己的日志。"}
  it('Rust 实际输出的三种 JSON 都能被正确解读', () => {
    const fromRust: string[] = [
      '{"status":"starting","port":null,"message":"正在启动后端…"}',
      '{"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}',
      '{"status":"failed","port":null,"message":"后端启动失败：进程已退出（退出码 1），但没有输出 CHANGE_PORT=<端口> 这一行。请确认 jar 路径与 Java 可用，或查看后端自己的日志。"}',
    ]
    const tones = fromRust.map((j) => describeState(JSON.parse(j) as BackendState).tone)
    expect(tones).toEqual(['loading', 'ok', 'error'])
  })

  it('Rust 的 ready 里的端口能读出来（不是 undefined）', () => {
    // 单独再钉一次字段名与类型：如果 Rust 那边把 `port` 改名成 `backendPort`
    // 或加进 `#[serde(skip_serializing_if)]`，上面那条只断言 tone 的测试
    // 依然会绿（tone 只由 status 决定），而这里会红。
    const s = JSON.parse(
      '{"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}',
    ) as BackendState
    expect(s.port).toBe(8080)
    expect(s.message).toBe('后端已连接 · 端口 8080')
    expect(describeState(s).text).toBe('后端已连接 · 端口 8080')
  })
})

describe('checkHealth', () => {
  beforeEach(() => {
    invokeMock.mockReset()
  })
  afterEach(() => {
    invokeMock.mockReset()
    vi.restoreAllMocks()
  })

  it('调用 get_health 命令（不再自己发 HTTP）', async () => {
    invokeMock.mockResolvedValue({ status: 'up', cache: '缓存：未启用（配置为关闭）' })

    const r = await checkHealth()

    expect(r).toEqual({ status: 'up', cache: '缓存：未启用（配置为关闭）' })
    // 命令名是**跨语言契约**：Rust 侧 `lib.rs` 的 `generate_handler![]` 里
    // 少注册 `get_health`，前端这里就会 reject（Tauri 报 command not found）。
    // 变异测试里「lib.rs 里不注册 get_health」就是靠这一条（以及组件那条）抓的。
    expect(invokeMock).toHaveBeenCalledWith('get_health')
  })

  it('不再传端口——端口是外壳的事，前端不该经手', async () => {
    // 这条是这次改动**语义上的重点**：旧接口是 `checkHealth(port)`，
    // 端口从 `get_backend_status` 流到前端、再由前端拼进 URL。
    // 现在端口留在 Rust 侧（`get_health` 自己从 state 里取），
    // 所以这个函数**一个参数都不收**。把签名改回去会让这条红。
    invokeMock.mockResolvedValue({ status: 'up', cache: 'x' })
    expect(checkHealth).toHaveLength(0)
    await checkHealth()
    expect(invokeMock).toHaveBeenCalledTimes(1)
    // 任何参数都不该出现在调用里
    expect(invokeMock.mock.calls[0]).toEqual(['get_health'])
  })

  it('invoke reject 时抛错（对应旧的「HTTP 非 2xx」那条）', async () => {
    // **保留旧断言的语义**：以前 500 会让 `fetch` 那条路径抛
    // `Error("后端返回 500")`；现在非 2xx 由 Rust 侧判掉并变成 invoke 的 reject，
    // 前端这条路径的职责只剩下「原样把错误抛给调用方」。
    // 所以这里断言的是「reject 会穿透」，而不是某个具体文案。
    invokeMock.mockRejectedValue(new Error('后端健康检查返回了 HTTP 500（期望 200）'))
    await expect(checkHealth()).rejects.toThrow('500')
  })

  it('通信失败时的原因原样穿透（状态栏要把它显示出来）', async () => {
    // 旧的「网络失败时抛错（端口不对或后端还没起）」那条的等价物：
    // 现在是外壳报回来的话——「连不上」「超时」都是它给的。
    invokeMock.mockRejectedValue(new Error('连不上后端 127.0.0.1:51234：拒绝连接'))
    await expect(checkHealth()).rejects.toThrow('连不上后端')
  })

  it('Rust 真的序列化出来的 Health JSON 字段名，前端能读出来', async () => {
    // **跨语言契约**（与 describeState 那两条同一个套路）。
    //
    // 下面这行 JSON 是 Rust 侧**实测**拿到的，原样复制**一个字符都没改**：
    // 来自 `src-tauri/src/http.rs` 的
    // `the_health_struct_serializes_with_the_field_names_the_frontend_reads`
    // （`serde_json::to_string(&Health{...})` 的输出），以及真机 e2e
    // `end_to_end_reads_a_real_health_response_through_the_shell_http_path`
    // 里 `GET /api/health` 打印出来的 `cache` 值。
    //
    // 这条专门抓**跨语言字段名漂移**：Rust 那边把 `cache` 改成 `cach`、
    // 或给它加上 `#[serde(skip_serializing_if)]`，则 JSON 里就没有 `cache` 键，
    // 而 `as Health` 是个**类型断言、不做任何校验**——不报错，直接是 undefined。
    // 状态栏右侧于是渲染成空白（`v-if="cacheText"` 为假），没有任何提示。
    const fromRust = '{"status":"up","cache":"缓存：未启用（配置为关闭）"}'
    invokeMock.mockResolvedValue(JSON.parse(fromRust))

    const h = await checkHealth()

    expect(h.status).toBe('up')
    expect(h.cache).toBe('缓存：未启用（配置为关闭）')
    // 逐键校验：漏一个键、或键名漂了，这里就红
    expect(Object.keys(h).sort()).toEqual(['cache', 'status'])
  })

  it('字段名漂移（cache → cach）时不会静默当成空文案', async () => {
    // 反面：真的漂了的时候，前端**读不到**（`undefined`）。
    // 这条不是在夸现有的实现——它是在把「漂移的后果」钉下来：
    // 后果是 undefined，而不是一个看起来正常的空串。
    // **Rust 侧那一条测试（rejects_a_misspelled_cache_field）是真正的守卫**，
    // 它在 JSON 解析那一步就报错；这里只是证明「万一漏过去，前端不会自作多情」。
    invokeMock.mockResolvedValue({ status: 'up', cach: '缓存：未启用（配置为关闭）' })
    const h = await checkHealth()
    expect(h.cache).toBeUndefined()
  })
})
```

- [x] **Step 2: 跑测试，确认失败**

Run: `cd frontend && pnpm test src/api/backend.spec.ts`

Expected: FAIL —— `Failed to resolve import "./backend"`

- [x] **Step 3: 写 `frontend/src/api/backend.ts`**

```ts
import { invoke } from '@tauri-apps/api/core'

/** 与 Rust 侧 `BackendState` 对应。 */
export interface BackendState {
  status: 'starting' | 'ready' | 'failed'
  port: number | null
  message: string
}

/** `/api/health` 的响应，与 Rust 侧 `http::Health` 对应。
 *
 * **两处手写定义，靠契约测试钉住**：Rust 侧的
 * `the_health_struct_serializes_with_the_field_names_the_frontend_reads`
 * 与这里的 `Rust 真的序列化出来的 Health JSON 字段名，前端能读出来`
 * 用的是同一段 JSON 字符串。改字段名要同时改两边，否则一侧会红。
 */
export interface Health {
  status: string
  /** 形如「缓存：已连接 127.0.0.1:6399」或「缓存：未启用（配置为关闭）」。 */
  cache: string
}

export type Tone = 'loading' | 'ok' | 'error'

/**
 * 向后端要状态。
 *
 * **端口只能来自这里**——它是随机分配的，前端不可能知道，
 * 任何硬编码端口都会在外壳换成另一个端口时失效。
 */
export function getBackendState(): Promise<BackendState> {
  return invoke<BackendState>('get_backend_status')
}

/** 把状态翻译成给人看的一句话 + 一个色调。 */
export function describeState(s: BackendState): { text: string; tone: Tone } {
  switch (s.status) {
    case 'starting':
      return { text: '正在启动后端…', tone: 'loading' }
    case 'ready':
      return { text: `后端已连接 · 端口 ${s.port}`, tone: 'ok' }
    case 'failed':
      return { text: s.message || '后端启动失败', tone: 'error' }
    default:
      // 到不了这里，除非 Rust 侧新增了一个 BackendStatus 变体而忘了同步这里。
      // 那时旧的写法会返回 undefined → 状态栏渲染成空白、且没有任何提示，
      // 是典型的静默失效。这里宁可显示一句「看不懂」也要让人一眼看见。
      return {
        text: `未知的后端状态：${String((s as { status: unknown }).status)}`,
        tone: 'error',
      }
  }
}

/**
 * 查后端健康端点。
 *
 * **不发 HTTP，也没有端口参数。**
 *
 * 旧版是 `checkHealth(port)` + `fetch('http://127.0.0.1:' + port + '/api/health')`，
 * 真机验收时它稳定失败：页面的源（dev 是 `http://127.0.0.1:1420`，打包后是
 * `tauri://localhost`）与后端的**随机端口**不同源，响应里没有
 * `Access-Control-Allow-Origin`，浏览器把响应拦掉，前端只拿到
 * `TypeError: Failed to fetch`。
 *
 * 两个概念要分清：**CSP 与 CORS 是两道独立且都要过的门**。CSP 管「页面能不能发
 * 这个请求」，CORS 管「浏览器肯不肯把响应交给脚本」。`tauri.conf.json` 里给
 * `connect-src` 放行了 `http://127.0.0.1:*`，只过了第一道。
 *
 * 现在这条请求由外壳（Rust 的 `get_health` 命令）去发——它不经浏览器，
 * 天生不在同源策略的管辖范围内。所以：
 * - 端口**留在 Rust 侧**，前端不再经手（签名因此一个参数都不收）；
 * - 前端不再需要 `connect-src` 的通配，CSP 收紧了；
 * - 失败走 invoke 的 reject：Rust 侧把「连不上 / 超时 / 非 2xx / JSON 畸形」
 *   分成四句不同的话，调用方原样显示即可。
 */
export function checkHealth(): Promise<Health> {
  return invoke<Health>('get_health')
}
```

- [x] **Step 4: 跑测试，确认通过**

Run: `cd frontend && pnpm test src/api/backend.spec.ts`

Expected: `Tests  9 passed`（describeState 4 条 + Rust 序列化契约 2 条 + checkHealth 3 条）

> 原先这里写的是 `6 passed`——那是**契约测试与 `default` 分支测试补上之前的旧数**。
> `backend.spec.ts` 最后一条 describeState 用例（`遇到没见过的状态时显示出来`）
> 守的是 Step 3 里 `default` 这个真实分支，所以它与实现同批进文件。

- [x] **Step 5: 写 `frontend/src/components/BackendStatusBar.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { getBackendState, describeState, checkHealth, type BackendState } from '../api/backend'

/**
 * 启动期的轮询间隔。
 *
 * 后端起得慢（实测 3.8–6.8 秒），只问一次的话用户要盯着「正在启动后端…」
 * 直到超时。500ms 是「看起来像立刻亮起来」与「别把 IPC 打满」的折中：
 * 启动期总共也就十几次调用，代价可以忽略。
 */
const SHELL_POLL_MS = 500

/**
 * 稳态下刷新缓存文案的间隔。
 *
 * **不能沿用 500ms**：外壳状态就绪之后永远不会回到 `starting`（Rust 侧
 * `with_port` / `child_exited` / `timed_out` 都以 `status == Ready` 提前返回，
 * 由 `a_ready_state_is_never_overwritten_by_a_later_failure` 守着），
 * 所以再问外壳状态纯属白烧；而 health 每次都是一个真实的 HTTP 请求，
 * 500ms 一条就是每秒 2 个请求永远不停，打在一个本地服务上毫无意义。
 *
 * 但也不只查一次：缓存描述是**会变的**——Redis 掉线后 `describe()` 会从
 * 「已连接」变成「已熔断」。这个变化很慢，5 秒足够及时，而稳态开销
 * 从 2 次/秒降到 0.2 次/秒。
 */
const HEALTH_POLL_MS = 5000

const state = ref<BackendState>({ status: 'starting', port: null, message: '' })
const cacheText = ref<string>('')
const cacheError = ref<string>('')

let shellTimer: number | undefined
let healthTimer: number | undefined

function stopShellPolling() {
  if (shellTimer !== undefined) {
    window.clearInterval(shellTimer)
    shellTimer = undefined
  }
}

function stopHealthPolling() {
  if (healthTimer !== undefined) {
    window.clearInterval(healthTimer)
    healthTimer = undefined
  }
}

async function pollHealth() {
  // **就绪守卫**：改成 invoke 之后端口不再由前端关心，所以守卫不能再写
  // `state.value.port === null`——那个条件是「端口」的代理，而真正的前提是
  // **后端已经就绪**。两者今天等价（端口只在 Ready 时才有值），但哪天
  // 外壳换个方式给出端口，用端口当守卫就会悄悄失效。
  //
  // 这道守卫是必需的：`get_health` 在 `Starting` 时直接返回一句「后端还在启动中」，
  // 而那句错误一旦落进 `cacheError`，状态栏右侧就会从「后端启动需要几秒，请稍候」
  // 变成一句吓人的话。
  //
  // **诚实标注（Task 8 变异实测）**：它今天是**不可达的**——`pollHealth` 的两个
  // 调用点（`onReady` 里那次直接调用，与它注册的 `healthTimer`）都在「已经就绪」
  // 之后，所以启动期它一次也不会被调到。**删掉这一行，全部测试依然全绿（变异存活，
  // 已实测并记在计划文档的变异表里）。** 留着它是因为守住这条边界比依赖
  // 「两个调用点恰好都在就绪之后」更稳：哪天有人把 `pollHealth` 挪进 `tick`
  // （那是个很自然的想法），这行就是唯一拦得住它的东西，
  // 而 `BackendStatusBar.spec.ts` 的「启动期不去问 health」那条用例
  // （断言调用次数为 0 且界面文案不变）会在同一刻变红。
  if (state.value.status !== 'ready') return
  try {
    const h = await checkHealth()
    cacheText.value = h.cache
    cacheError.value = ''
  } catch (e) {
    // 一次失败不清空上一次成功的文案：Redis 抖一下就把「缓存：已连接」
    // 换成错误提示，比继续显示旧值更让人困惑。
    cacheError.value = String(e)
  }
}

/**
 * 就绪 → 停掉外壳轮询、立刻查一次 health、按稳态节奏继续刷新。
 *
 * `void` 而不是 `await`：health 是旁路，不该拖慢状态栏本身——
 * 端口一到就先把「后端已连接」显示出来。
 */
function onReady() {
  stopShellPolling()
  void pollHealth()
  if (healthTimer === undefined) {
    healthTimer = window.setInterval(() => void pollHealth(), HEALTH_POLL_MS)
  }
}

async function tick() {
  try {
    state.value = await getBackendState()
  } catch (e) {
    // **失败即终态，这里刻意不重试。**
    //
    // `get_backend_status` 是一条纯内存读取的 Tauri 命令
    // （`backend.state.lock().unwrap().clone()`），它不具备瞬时失败的机理：
    // 没有 IO、没有网络、没有超时——invoke 抛错只可能意味着「命令没注册」
    // 或「State 没 manage」，两者都是永久性的，重试一万次也一样。
    //
    // 所以把界面停在错误态是对的：继续按 SHELL_POLL_MS 重试只会制造失败风暴，
    // 而用户看到的东西一个字都不会变。真要重来，重启外壳。
    state.value = { status: 'failed', port: null, message: `与外壳通信失败：${String(e)}` }
  }

  if (state.value.status === 'starting') return // 还没起来，继续按 SHELL_POLL_MS 问
  stopShellPolling() // 就绪与失败都是单向的，再问也只会得到同一个答案
  if (state.value.status === 'ready') onReady()
}

onMounted(() => {
  void tick()
  shellTimer = window.setInterval(() => void tick(), SHELL_POLL_MS)
})

onUnmounted(() => {
  stopShellPolling()
  stopHealthPolling()
})

const desc = computed(() => describeState(state.value))
</script>

<template>
  <footer class="status" :class="desc.tone">
    <span class="dot" />
    <span class="text">{{ desc.text }}</span>
    <span v-if="cacheText" class="cache">{{ cacheText }}</span>
    <span v-else-if="cacheError" class="cache err">缓存状态取不到：{{ cacheError }}</span>
    <span v-else-if="desc.tone === 'loading'" class="cache dim">后端启动需要几秒，请稍候</span>
  </footer>
</template>

<style scoped>
.status {
  display: flex;
  align-items: center;
  gap: var(--gap-sm);
  padding: 6px var(--gap-md);
  border-top: var(--line-width) solid var(--line);
  background: var(--panel);
  font-size: 12px;
  color: var(--text-dim);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-dim);
  flex: 0 0 auto;
}

/* 色调只影响小圆点，整条状态栏保持低调——设计文档要求用对比而非大色块 */
.status.ok .dot      { background: var(--accent); }
.status.error .dot   { background: var(--accent); }
.status.loading .dot { animation: pulse 1.1s ease-in-out infinite; }

@keyframes pulse { 0%, 100% { opacity: 0.3 } 50% { opacity: 1 } }

.text { color: var(--text); }
.cache { margin-left: auto; }
.cache.dim { opacity: 0.7; }
.cache.err { color: var(--accent); }
</style>
```

> **⚠️ 这份 fence 曾经是坏的：它只有 `const POLL_MS = 500` 同时驱动外壳状态与
> health，等于「每秒 2 个请求永远不停」。现在是两个常量分档 + 就绪/失败后停表。**
>
> 为什么**不能**沿用 500ms 一档到底，两条理由各自独立：
>
> 1. **外壳状态是单向的，就绪之后永远是 `ready`。** Rust 侧 `with_port` /
>    `child_exited` / `timed_out` 都以 `status == Ready` 提前返回，由常驻测试
>    `a_ready_state_is_never_overwritten_by_a_later_failure` 守着。既然答案不会变，
>    就绪之后再按 500ms 问外壳纯属白烧，所以 `stopShellPolling()` 在就绪与失败
>    两条路径上都要停表（失败同样是终态，见 `tick` 的 `catch` 分支注释）。
> 2. **health 每一次都是一个真实的 HTTP 请求。** 500ms 一条就是每秒 2 个请求
>    打在一个本地服务上，毫无意义。但也不能只查一次：缓存描述**会变**
>    （Redis 掉线后 `describe()` 从「已连接」变「已熔断」），只是变得很慢，
>    所以 `HEALTH_POLL_MS = 5000`——稳态开销从 2 次/秒降到 0.2 次/秒。
>
> 这两条被 `BackendStatusBar.spec.ts` 里两条反面断言钉住：「就绪之后不再问外壳
> 状态」与「稳态下 health 按 5 秒节奏刷新，而不是 500ms」（后者断言 60 秒内
> 不超过 14 次——若跟着 500ms 走会是 120 次）。

- [x] **Step 6: 改 `App.vue`，把状态栏挂上**

```vue
<script setup lang="ts">
import AppSidebar from './components/AppSidebar.vue'
import AppToolbar from './components/AppToolbar.vue'
import BackendStatusBar from './components/BackendStatusBar.vue'
</script>

<template>
  <div class="shell">
    <AppSidebar />
    <div class="main">
      <AppToolbar />
      <main class="workspace">
        <RouterView />
      </main>
      <BackendStatusBar />
    </div>
  </div>
</template>

<style scoped>
.shell { display: flex; height: 100%; }
.main { flex: 1 1 auto; display: flex; flex-direction: column; min-width: 0; }
.workspace { flex: 1 1 auto; overflow-y: auto; }
</style>
```

> **⚠️ 这份 fence 曾经漏掉了 `AppToolbar`。** 旧的写法只有 `AppSidebar` 与
> `BackendStatusBar` 两个 import，**没有 `<AppToolbar />`**——Task 3 刚把工具条加进
> `App.vue`，照抄那个版本会让它在真机上**静默消失**。
>
> 这个缺陷之所以能溜过 Task 3，是因为**没有任何测试 mount 过完整的 `App.vue`**：
> `AppToolbar.spec.ts` 是**单独 mount 那个组件**的，模板里少一行 `<AppToolbar />`
> 它照样绿。所以 Task 6 补了 `App.spec.ts`（Step 6b），把整壳结构钉住。
> **改动 `App.vue` 时以这份 fence 为准，它有三个子组件，一个都不能少。**

- [x] **Step 6b: 写 `frontend/src/App.spec.ts`（整壳测试——补上被漏掉的那道守卫）**

如果只有组件各自的测试，`App.vue` 的模板内容就是**完全没人守**的地方：少一个
`import`、少一个 `<AppToolbar />`、把状态栏挪到 `workspace` 上面，全都不会有测试变红。
这个文件是**唯一 mount 完整 `App.vue` 的地方**，它钉三件事：三个子组件都在、
`toolbar → workspace → status` 的顺序正确、侧边栏与 `.main` 平级。

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import { allRoutes } from './nav'

// 整壳测试：**唯一 mount 完整 App.vue 的地方**。
// AppToolbar.spec.ts 是单独 mount 组件的，不经过 App.vue——
// 所以「App.vue 的模板里漏掉了 <AppToolbar />」这个改动
// 曾经能让全部测试保持绿色，而顶部工具条在真机上静默消失。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

async function mountApp() {
  const router = createRouter({
    history: createWebHistory(),
    routes: allRoutes().map((r) => ({
      path: r.path,
      name: r.name,
      component: { template: '<div/>' },
    })),
  })
  await router.push('/today')
  await router.isReady()
  const w = mount(App, { global: { plugins: [router, createPinia()] } })
  await flushPromises()
  return w
}

describe('整壳结构', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    invokeMock.mockReset()
    // 结构测试不关心数据，给个 starting 就够——状态栏因此不会去打 get_health。
    // 按命令名分派：健康检查已经从 `fetch` 改成 `get_health` 了，
    // 一个笼统的 mockResolvedValue 会让两条命令拿到同一个外壳状态对象。
    invokeMock.mockImplementation(async (cmd: unknown) => {
      if (cmd === 'get_backend_status') {
        return { status: 'starting', port: null, message: '正在启动后端…' }
      }
      if (cmd === 'get_health') {
        return { status: 'up', cache: '缓存：未启用（配置为关闭）' }
      }
      throw new Error(`测试没有为命令 ${String(cmd)} 准备应答`)
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('三块都在：侧边栏、顶部工具条、底部状态栏', async () => {
    const w = await mountApp()
    expect(w.find('.sidebar').exists()).toBe(true)
    expect(w.find('.toolbar').exists()).toBe(true)
    expect(w.find('.status').exists()).toBe(true)
  })

  it('工具条在 workspace 之上，状态栏在 workspace 之下', async () => {
    // 光断言「存在」不够：顺序错了布局就反了（状态栏跑到顶部）。
    const w = await mountApp()
    const main = w.find('.main')
    expect(main.exists()).toBe(true)
    const children = main.element.children
    const classes = Array.from(children).map((el) => el.className)
    expect(classes).toHaveLength(3)
    expect(classes[0]).toContain('toolbar')
    expect(classes[1]).toContain('workspace')
    expect(classes[2]).toContain('status')
  })

  it('侧边栏不在 .main 里，工具条与状态栏是 .main 的直接子节点', async () => {
    const w = await mountApp()
    const main = w.find('.main').element
    expect(main.children[0].className).toContain('toolbar')
    expect(main.children[2].className).toContain('status')
    // 侧边栏必须与 .main 平级，而不是被塞进 .main
    expect(main.querySelector('.sidebar')).toBeNull()
    expect(w.find('.shell').element.children[0].className).toContain('sidebar')
  })
})
```

- [x] **Step 7: 跑全部前端测试**

Run: `cd frontend && pnpm test`

Expected: `Tests  46 passed`（Test Files 8 passed）

> **Task 8 之后这里是 `Tests 50 passed`**：`backend.spec.ts` 9 → 12 条、
> `BackendStatusBar.spec.ts` 9 → 10 条（health 从 `fetch` 改成 `get_health`，
> 两处 mock 跟着换，并补了「不再传端口」与「字段名漂移」两条）。

逐 spec 分解（这些数字是实测出来的，用 `pnpm test` 的 per-file 行核对）：

| spec | 条数 | 归属 |
|---|---|---|
| `src/styles/theme.spec.ts` | 8 | Task 2 |
| `src/nav.spec.ts` | 5 | Task 3 |
| `src/components/AppSidebar.spec.ts` | 6 | Task 3 |
| `src/components/AppToolbar.spec.ts` | 3 | Task 3 |
| `src/router/router.spec.ts` | 3 | Task 3 |
| `src/api/backend.spec.ts` | 9 | Task 6 |
| `src/components/BackendStatusBar.spec.ts` | 9 | Task 6 |
| `src/App.spec.ts` | 3 | Task 6（Step 6b） |
| **合计** | **46** | |

> **原先这里写的是 `26 passed`（8 主题 + 5 导航 + 4 侧边栏 + 3 工具条 + 6 API），
> 这个数错了两重：**
>
> 1. **那个 26 本身就是旧数。** 它按「侧边栏 4 条 + API 6 条」算，而 Task 3 实际
>    产出的是**侧边栏 6 条**（多了「分隔线在「我的」分组内部」与「「我的」在 DOM 里
>    排在所有功能区之后」两条）以及一个计划里没预料到的 **`router.spec.ts` 3 条**。
>    也就是说 Task 3 结束时是 **25 条**，不是 21 条。
> 2. **Task 6 又新增了三块。** 本任务落地时补的：`backend.spec.ts` 的 **Rust 序列化
>    契约 2 条 + `default` 分支 1 条**、`BackendStatusBar.spec.ts` 9 条、`App.spec.ts` 3 条。
>
> `26 → 46` 不是「数错了」，是**前面几步的旧数没跟着实现走**。以后改测试计数请直接
> 贴 `pnpm test` 的 per-file 输出，不要手算。

- [x] **Step 8: 提交**

```bash
git add frontend/src/api frontend/src/components/BackendStatusBar.vue frontend/src/App.vue
git commit -m "feat(frontend): 加载提示与后端状态接线，第一次能看见东西

- 状态栏轮询 get_backend_status（后端起得慢，实测 3.8–6.8 秒）
- 端口只来自 invoke，前端不硬编码——它是随机分配的
- 顺手把 /api/health 的缓存状态显示出来，第一阶段那条降级设计
  第一次有了可见的证据
- 加载态用脉冲小圆点，不做大色块（设计文档要求靠对比而非填充）"
```

> **实际提交时 `git add` 要带上两个新 spec**——`frontend/src/components/BackendStatusBar.spec.ts`
> 与 `frontend/src/App.spec.ts`（上面那份清单是首版写的，漏了它们）：
>
> ```bash
> git add frontend/src/api frontend/src/components/BackendStatusBar.vue \
>         frontend/src/components/BackendStatusBar.spec.ts \
>         frontend/src/App.vue frontend/src/App.spec.ts
> ```
>
> 另外有三处**计划里的旧口径已按磁盘改正**，一起记在这里，免得下一个人照抄旧版：
>
> - **`App.vue` 的 fence 补回了 `<AppToolbar />`**。首版给的 App.vue 只有侧边栏与状态栏，
>   照抄会让 Task 3 刚加的工具条**静默消失**（Step 6 的注里有完整说明）。
> - **`BackendStatusBar.vue` 的 fence 换成了「外壳 500ms 启动期轮询 + health 5 秒稳态轮询 +
>   就绪/失败后停表」**。首版那个 500ms 一档到底的版本是每秒 2 个请求永远不停（Step 5 的注）。
> - **`describeState` 补了 `default` 分支**，把「Rust 加了第四个状态而忘了同步这里」这个
>   静默失效变成界面上一句能看见的「未知的后端状态」。它由 `backend.spec.ts` 的一条用例守着，
>   删掉 `default` 那条测试会红（变异验证过）。

---

## Task 8: 健康检查改由外壳代发（去掉 CORS 需求）

> **这是一条计划外任务**，由 Task 7 的真机验收发现。它不改任何新功能，
> 只把一条**在真机上从来没成功过**的链路修对。

### 为什么会有这一条：Task 7 实测到的缺陷

Task 7 的端到端验收（10 条观察项）里，**第 5 条不满足**。真机上状态栏右侧显示的是：

```
缓存状态取不到：TypeError: Failed to fetch
```

而后端 `/api/health` **本身完全正常**——直接请求返回 `200` 与
`{"cache":"缓存：未启用（配置为关闭）","status":"up"}`。

### 根因：CSP 与 CORS 是两道独立且都要过的门

页面源（dev 是 `http://127.0.0.1:1420`，打包后是 `tauri://localhost`）与后端的
**随机端口**是**不同源**的，而响应里没有 `Access-Control-Allow-Origin`，
所以浏览器把响应丢掉了。

Task 1 给 CSP 写了 `connect-src 'self' http://127.0.0.1:*`，当时以为这就够了。
**不够，而且永远不够**：

| | 管什么 | 本任务之前的状况 |
|---|---|---|
| **CSP** | 页面**能不能发**这个请求 | 放行了 `http://127.0.0.1:*`，**过了** |
| **CORS** | 浏览器**肯不肯把响应交给脚本** | 后端没有 ACAO 头，**没过** |

对照实验（Task 7 做的）把根因钉死了：同一个假后端，只切换有没有 ACAO 头——
加了就显示「缓存：未启用（配置为关闭）」，不加就复现 `TypeError: Failed to fetch`。
所以是 CORS，不是 CSP。

**为什么 46 条前端测试一条都没抓到**：它们**全部 mock 掉了 `fetch`**。
这条路径第一次在真机上执行，就是它的第一次执行。

### 修法：改由 Rust 外壳代发这条请求

**不要给后端加 CORS。** 三条理由，按重要性排序：

1. **加了 CORS 就等于任何网页都能读 `127.0.0.1:<随机端口>` 的响应。**
   这是要把客户端分发给朋友的软件，一个本地 HTTP 服务不该对随便哪个页面开放读权限。
2. `tauri://localhost` 是**不透明源**，自定义 scheme 的 CORS 处理很脏
   （各家 WebView 行为不一致，容易变成「在我机器上是好的」）。
3. 请求在 Rust 里发，**根本不经过浏览器同源策略的管辖范围**，天然没有这个问题。
   而且前端不再自己发 HTTP 之后，CSP 里那条 `http://127.0.0.1:*` 通配可以**收紧掉**
   （通配本身就是「我不知道端口，所以全放行」的妥协）。

**Files:**
- Create: `frontend/src-tauri/src/http.rs`（HTTP 客户端 + 纯解析函数 + 单元测试）
- Create: `frontend/src-tauri/tests/health_socket.rs`（真 socket 的集成测试）
- Modify: `frontend/src-tauri/src/backend.rs`（`Health` 结构、`HealthError`、新的 `#[ignore]` e2e）
- Modify: `frontend/src-tauri/src/lib.rs`（`get_health` 命令 + 注册）
- Modify: `frontend/src-tauri/tauri.conf.json`（收紧 CSP）
- Modify: `frontend/src/api/backend.ts`、`frontend/src/components/BackendStatusBar.vue`
- Modify: `frontend/src/api/backend.spec.ts`、`frontend/src/components/BackendStatusBar.spec.ts`、`frontend/src/App.spec.ts`

### Step 1 的选择：手写 TCP，不引入 `ureq` / `reqwest`

先做了一次**实测**再决定，因为「后端会不会用 chunked」直接决定手写客户端的复杂度。
用 Python `socket` 直接打 `127.0.0.1:<随机端口>`，抓原始字节：

| 请求 | 后端实际回的 |
|---|---|
| `GET /api/health HTTP/1.1` + `Connection: close` | `Transfer-Encoding: chunked`，正文是 `41\r\n{...json...}\r\n0\r\n\r\n` |
| `GET /api/health HTTP/1.0` | **没有** chunked，没有 `Content-Length`，靠读到 EOF 定界 |
| `GET /api/health HTTP/1.1`（**没有 `Host` 头**） | Tomcat 回 `400 Bad Request` |

结论：需要的能力恰好是「一个请求 + chunked 解码 + JSON 解析」，全部加起来**不到 100 行**，
于是选择 **(c) 手写 `std::net::TcpStream`**。理由：

- **零新依赖**：`Cargo.toml` 一个字没改，`Cargo.lock` 的 SHA256 与改动前**完全相同**，
  408 个 crate 一个不多（实测）。
- 这里既不需要 HTTPS 也不需要 DNS，`reqwest` 为「读一个本机 JSON」拉进 tokio 不成比例。
- chunked 解码与状态行解析**都是可以脱离网络测的纯函数**，本项目一路的教训是
  「有真实逻辑的地方必须有单元测试」——手写反而让每一段都能单独钉住。

**必须有超时**，而且读、写两个方向都要设：后端活着但不回话时（线程池打满、GC 停顿），
`get_health` 不能挂住，否则前端 5 秒一次的轮询会一个个堆起来。
本机实测一次完整的请求-响应在 **123 毫秒**（后端冷启动时），2 秒的上限留了三个数量级余量。

### Step 2 / Step 3：命令与前端接线

`get_health` 的签名是 `Result<Health, String>`：错误走 `Err`，Tauri 会把它变成
`invoke` 的 reject，前端现有的 `catch` 分支直接接得住，不需要为它再造一个返回类型。

**就绪守卫**：`get_health` 只在状态是 `Ready` 时才去连。`Starting` 时端口还是 `None`，
那时去连 `127.0.0.1:0` 只会拿到一个「连不上」的**假错误**，把「后端还在起」误报成「后端坏了」；
`Failed` 时失败原因已经在 `message` 里，比任何连接错误都准。

### Step 4：收紧 CSP（实测结论）

`connect-src` 从 `'self' http://127.0.0.1:*` 收成 **`'self'`**。

**HMR 实测（这条是收紧的风险点）**：`pnpm tauri dev` 起真实窗口，然后
1. **改一个真的进入渲染结果的文件**（在 `App.vue` 的 `<div class="workspace">` 里插入一个
   `<div class="hmr-probe">HMR-PROBE-<时间戳></div>`）——只加一个没人用的变量会被
   tree-shake，什么也证明不了；
2. 轮询页面 DOM，确认那个元素出现：**0.50 秒**；
3. 还原文件，确认元素消失：**0.50 秒**。

所以 **HMR 没有被破坏**，不需要为 dev 保留 `http://127.0.0.1:*`。
（顺带发现：dev 模式下 Tauri 并不给页面注入 CSP——文档响应的
`Content-Security-Policy` 头是**没有**的，所以 dev 的 HMR 从来就不受这条 CSP 约束；
真正的 CSP 生效场景是打包后的自定义协议响应。这一点记录在此，免得下一个人
把「dev 里改了没反应」误读成 CSP 没生效。）

### Step 5：测试

**Rust 侧 80 条**（`cargo test`：76 passed + 4 条 integration；另有 2 条 `#[ignore]` 的真后端 e2e）：

- `src/http.rs` 里的**纯函数测试**（不碰网络）：正常响应、**真实后端字节**（上面抓的那 41 字节
  chunked 响应，逐字节写进测试）、多块 chunked、chunked 扩展、裸 `\n` 行尾、trailer、
  非 16 进制块长、块被截断、缺尾 CRLF、缺结束块、超过字节上限、状态行不认识、
  状态码不是数字、非 200（100/204/301/302/404/500）、空 body、chunked 空 body、
  JSON 畸形、截断 JSON、不是对象、缺 `status`、缺 `cache`、**`cache` 拼成 `cach`**、
  字段类型不对、头不是 ASCII、中文正文的字节长度、请求形状、四类错误消息两两不同。
- `tests/health_socket.rs` 里的**真 socket 测试**：正常响应、非 2xx、**超时**、连接被拒。
  超时那条必须有一个「接受连接但不回包」的假服务——连一个没人监听的端口拿到的是
  connection refused，**不是**超时，两者是不同的失败。
- 新增一条 `#[ignore]` 的 e2e：真的拉起后端 jar，用**外壳自己那条 HTTP 路径**打一次
  `/api/health`，断言 `cache` 与计划 1 的格式一致。实测输出：
  `cache = "缓存：未启用（配置为关闭）"`，耗时 **123.3 ms**。

**前端侧 50 条**：`checkHealth` 的测试从 mock `fetch` 改成 mock `invoke`，
「HTTP 非 2xx 时抛错」的**语义**保留（现在对应 invoke reject）；
`BackendStatusBar.spec.ts` 的 mock 按**命令名**分派（现在有两条命令）。

### Step 6：真机端到端复测（本任务的核心验收）

`pnpm tauri dev` 起真实窗口，从**活着的页面**里读出状态栏的实际文字：

```
后端已连接 · 端口 57638
缓存：未启用（配置为关闭）
```

**不再是** `缓存状态取不到：...`。这一步的证据是页面的 DOM 与渲染器自己出的截图
（不是对屏幕取色），细节与「为什么不用全屏截图」记在实施记录里。

### Step 7：同步文档

本节就是。**所有代码 fence 与磁盘逐字一致，由脚本比对**（不是目测）：
`_healthcheck/verify_fences.py` 把每个 fence 与磁盘文件做逐字节比较。

### 变异测试（诚实记录，含存活的）

| 变异 | 期望 | 实测 |
|---|---|---|
| `get_health` 里去掉 `set_read_timeout` / `set_write_timeout` | 被杀 | **被杀**（`FAILED ... finished in 10.10s`）。注意杀它的**不是挂死**：假服务自己会在 8 秒后关连接，于是「应当在上限附近放弃」与「消息该说后端没回话」两条断言同时红。想让它真挂死，假服务必须永不关连接——那会把测试套件拖成偶发挂起，不值。 |
| 非 Ready 时不去连的那道守卫去掉 | 被杀 | **存活，且是「不可达变异」**：`pollHealth` 的两个调用点（`onReady` 里的直接调用、它注册的 `healthTimer`）都在就绪之后，所以那行守卫启动期**根本走不到**。删掉它单跑 `BackendStatusBar.spec.ts` 仍然 10 passed；另用一份临时 spec 打点确认启动期只有 `get_backend_status` 被调用。相关用例真正守的是**调用点的位置**（谁把 `pollHealth` 挪进 `tick()`，它立刻变红）。这一点已写进组件注释，不留「测试守住了守卫」的假印象。 |
| 解析函数里字段名 `cache` 改成 `cach` | 被杀 | **被杀（Rust 侧）**：`error[E0609]: no field 'cache' on type 'backend::Health'`，`cargo test` 退出码 101、测试根本没跑起来。**前端侧杀不掉**（这是设计使然）：`backend.spec.ts` 的契约测试喂的是**Rust 真的序列化出来的那段 JSON 字符串**，而 mock `invoke` 的值也由测试自己给，Rust 改字段名前端无从知晓。跨语言字段名真正被守住的地方在 Rust 侧（`rejects_a_misspelled_cache_field` + 那条序列化契约测试）。 |
| `lib.rs` 里不注册 `get_health` | 被杀 | **被杀**，但**是我补了一条测试之后才杀掉的**：改之前 `cargo test` 全绿（只有一条 `function get_health is never used` 警告），变异存活。补的 `both_commands_are_registered_in_the_invoke_handler` 直接读 `include_str!("lib.rs")` 里 `generate_handler!` 那一行来断言两个命令都在，之后 `test result: FAILED. 75 passed; 1 failed`。 |
| （额外）`BackendStatusBar` 不读 `h.cache`（改成写死字符串） | — | **被杀**：`Tests 1 failed \| 9 passed`。 |
| （额外）去掉 `read_to_end` 的字节上限 | — | **被杀**：`read_to_end_stops_at_the_byte_cap` 会一直读到 EOF（用 10MB 的源，测试明显变慢）。 |

**存活的那一条已如实记录在上表**，并把原因写进了源码注释与本节——
不把它算成「通过」。

### 代发的代价（实测，给打包计划用）

| 项 | 实测 |
|---|---|
| 新依赖 | **0 个**。`Cargo.toml` 没动；`Cargo.lock` 的 SHA256 改动前后**完全相同**（`5735a75f…`），408 个 crate 一个不多 |
| `cargo build` 多花的时间 | 没有新的依赖要编译。整棵依赖树的 release 构建实测 **148.8 秒**（对照，热缓存） |
| 发布版二进制变大 | **+39,424 字节 = +0.45%**（8,796,672 → 8,836,096）。这个数是在**同一个目录、同一套 target** 里把本任务的四个文件回退到 `187ce8a` 再重编测出来的；两个不同 worktree 之间直接比会得到同样的数字，但那是不严谨的比法 |

### 实施记录（真实数字与踩过的坑）

- 后端总览端口协商、`/api/health` 的两种响应形态、chunked 的原始字节，都记在上面 Step 1 的表里。
- 真机复测：`后端已连接 · 端口 57638` + `缓存：未启用（配置为关闭）`；
  同一轮里 `GET /api/health 用了 123.3ms`（`#[ignore]` 的那条 e2e）。
- **一个值得记下来的教训（关于怎么取证）**：一开始想用 `PrintWindow` 抓窗口来 OCR 状态栏，
  抓到的帧里状态栏**一个字都没有**，差点得出「状态栏压根没渲染」的错误结论。
  实际原因是 `PrintWindow` 对这个 WebView2 窗口并不可靠（DPI 缩放 + 只画了可见视口的一部分），
  它抓到的是一张**被压扁且被裁掉下半部分**的图。后来改用两条更可靠的证据：
  活页面的 DOM（`footer.status` 的 `textContent` 与每个 span 的 `getBoundingClientRect`）
  与渲染器自己出的截图（`Page.captureScreenshot`，两者都通过 WebView2 的调试端口）。
  全过程**没有任何一次全屏截取**——用的都是「我们自己的窗口/我们自己的进程 / 我们自己的页面」。
  **教训**：抓帧工具本身要先被验证，否则它给出的「没有」会被误读成「不存在」。
- 文档同步的第二个坑：PowerShell 5.1 按 ANSI/GBK 读 `.ps1`，
  含中文的脚本会被静默读坏（报出的是「字符串缺少终止符」这种离题错误）。
  所以 `_healthcheck/` 下的 `.ps1` **全部保持纯 ASCII**，中文一律由 Python 侧读写。

### 代码（与磁盘逐字一致，脚本比对）

#### `frontend/src-tauri/src/http.rs`（新增）

```rust
//! 向外壳自己拉起的后端发一条 GET，把响应读成一个 `Health`。
//!
//! # 为什么健康检查要由外壳代发，而不是前端 `fetch`
//!
//! 页面源（dev 是 `http://127.0.0.1:1420`，打包后是 `tauri://localhost`）与后端
//! 的**随机端口**不同源，响应里没有 `Access-Control-Allow-Origin`，浏览器会把响应
//! 拦掉，前端只看到 `TypeError: Failed to fetch`。
//!
//! CSP 与 CORS 是**两道独立且都要过的门**：CSP 管「页面能不能发这个请求」，
//! CORS 管「浏览器肯不肯把响应交给脚本」。计划当初给 `connect-src` 放行了
//! `http://127.0.0.1:*`，只过了第一道。
//!
//! 修法是**不给后端加 CORS**，改由外壳（Rust）发这条请求——它不经浏览器，
//! 天生不在同源策略的管辖范围内。理由：
//!
//! 1. `tauri://localhost` 是不透明源，自定义 scheme 的 CORS 处理很脏；
//! 2. 加了 CORS 就等于**任何网页都能读** `127.0.0.1:<随机端口>` 的响应；
//! 3. 前端不再自己发 HTTP 之后，CSP 里那条 `http://127.0.0.1:*` 可以收紧掉。
//!
//! # 为什么手写 TCP 而不是 `ureq` / `reqwest`
//!
//! 这条链路要读的东西只有一种，而且形态已经被实测钉死了（2026-10-16，见
//! `chunked_decoding_is_required_by_the_real_backend`）：本机 Spring Boot 10.1.55
//! 用 HTTP/1.1 回 `/api/health` 时**总是 `Transfer-Encoding: chunked`**，
//! 用 HTTP/1.0 回同样一个 JSON 时是「读到 EOF 为止」。所以需要的全部能力就是
//! 「一个请求 + 两种定长规则 + 一次 JSON 解析」，代价是下面这几十行代码 + 单元测试，
//! 换来**零新依赖**（`Cargo.lock` 一个 crate 都不增加、二进制不变大、SBOM 更干净）。
//!
//! `reqwest` 会为「读一个本机 JSON」拉进 tokio；`ureq` 轻得多，但仍要拉进一整套
//! 传输栈（DNS、TLS、gzip…）——而这里既不需要 HTTPS，也没有 DNS。
//! 实测的依赖代价记在计划文档 Task 8 的「A. 代发的代价」一节。

use crate::backend::{Health, HealthError};

/// 写请求 + 读整条响应的时间上限。
///
/// **必须有**：后端活着但不回包时（比如线程池打满、GC 停顿、socket 卡住），
/// `get_health` 不能挂住——前端的稳态轮询是 5 秒一次，挂住就会把请求堆起来。
///
/// 2 秒的选法：本机实测一次完整的请求-响应在**毫秒**量级（后端冷启动时也不过
/// 几十毫秒），2 秒留了三个数量级的余量；而它远小于前端 5 秒的轮询周期，
/// 所以最坏情况下也不会堆积超过一个在途请求。
pub const HEALTH_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(2);

/// 单条响应的字节上限。畸形或恶意响应不该把外壳的内存吃掉。
pub const MAX_RESPONSE_BYTES: usize = 1024 * 1024;

/// `/api/health` 的请求行与头。
///
/// `Connection: close` 是刻意的：
/// - 读循环靠「读到 EOF」收尾，留着长连接就会卡到超时；
/// - 后端看到它会回 `Connection: close` 并真的关连接（实测）。
///
/// 用 `HTTP/1.1` 而不是 `HTTP/1.0`：1.0 那套「读到 EOF 为止」的规则虽然更省事，
/// 但它会让这条请求与真实浏览器发的形状不一致；而 1.1 的 chunked 已经实测清楚
/// 并且有解码器与测试，没有必要为了省十行代码去用一个更古老的协议。
pub fn health_request(port: u16) -> String {
    format!(
        "GET /api/health HTTP/1.1\r\n\
         Host: 127.0.0.1:{port}\r\n\
         Accept: application/json\r\n\
         Connection: close\r\n\r\n"
    )
}

/// 向 `127.0.0.1:<port>` 的 `/api/health` 发一条 GET，解析成 [`Health`]。
///
/// 返回 `Err` 时消息一律是给用户看的中文，且**每条原因各不相同**——
/// 前端把它原样渲染到状态栏右侧，所以「连不上」「超时」「非 2xx」「JSON 畸形」
/// 必须能区分开，否则用户没有任何可行动的线索。
pub fn get_health(port: u16) -> Result<Health, String> {
    let addr = format!("127.0.0.1:{port}");
    let connect_err = |e: std::io::Error| {
        HealthError::Connect {
            addr: addr.clone(),
            source: e,
        }
        .into_message()
    };
    let read_err = |e: std::io::Error| {
        HealthError::Read {
            addr: addr.clone(),
            source: e,
        }
        .into_message()
    };

    let mut stream = std::net::TcpStream::connect(&addr).map_err(connect_err)?;
    // 读与写都要有超时：只设一个的话，另一个方向照样能挂满 `HEALTH_TIMEOUT` 的十倍
    stream.set_read_timeout(Some(HEALTH_TIMEOUT)).map_err(read_err)?;
    stream.set_write_timeout(Some(HEALTH_TIMEOUT)).map_err(read_err)?;

    {
        use std::io::Write;
        stream
            .write_all(health_request(port).as_bytes())
            .map_err(read_err)?;
        stream.flush().map_err(read_err)?;
    }

    let raw = read_to_end(&mut stream, MAX_RESPONSE_BYTES).map_err(read_err)?;
    parse_health_response(&raw).map_err(HealthError::into_message)
}

/// 读到 EOF 或字节上限为止。
///
/// 单独抽出来的原因：它是**唯一**接触 socket 的读路径，而「读到上限要停手」
/// 这条判断可以脱离网络被测试覆盖（见 `read_to_end_stops_at_the_byte_cap`）。
pub fn read_to_end<R: std::io::Read>(r: &mut R, cap: usize) -> std::io::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut buf = [0u8; 8 * 1024];
    loop {
        let n = r.read(&mut buf)?;
        if n == 0 {
            return Ok(out);
        }
        if out.len() + n > cap {
            out.extend_from_slice(&buf[..cap - out.len()]);
            return Ok(out);
        }
        out.extend_from_slice(&buf[..n]);
    }
}

/// **纯函数**：一条完整的 HTTP 响应字节 → [`Health`]。
///
/// 不碰网络、不碰时钟，所以「非 2xx」「chunked」「畸形 JSON」「空 body」
/// 这些分支全都能用一条字符串直接测到——包括那些很难真的从后端制造出来的。
///
/// 返回 [`HealthError`] 而不是 `String`：三条不同的原因要能各报各的话，
/// 而调用方（`get_health`）在边界上才把它转成人看的消息。测试也因此能断言
/// **是哪一类**错误，而不是去匹配一段文案。
pub fn parse_health_response(raw: &[u8]) -> Result<Health, HealthError> {
    let (status_line, headers, body) = split_response(raw)?;
    let code = parse_status_code(status_line)?;
    if code != 200 {
        return Err(HealthError::NotOk { code });
    }
    let body = if is_chunked(headers) {
        chunked_decode(body)?
    } else {
        body.to_vec()
    };
    parse_health_json(&body)
}

/// 把一条响应切成（状态行, 头, body 原始字节）。
///
/// 头与 body 的分界是**第一个空行**——HTTP 规则如此，但它值得被显式检查：
/// 少一个空行就会把整条响应当成状态行，报出来的错会离真相很远。
fn split_response(raw: &[u8]) -> Result<(&str, &str, &[u8]), HealthError> {
    // 状态行与头都是 ASCII；body 才是可能有中文的地方，所以只在这里转一次
    let malformed = |detail: &str| HealthError::Malformed {
        detail: detail.to_string(),
    };
    let Some(pos) = find_subslice(raw, b"\r\n\r\n") else {
        return Err(malformed(
            "响应里没有头部与正文之间的空行（\\r\\n\\r\\n），可能不是 HTTP 响应",
        ));
    };
    let head = std::str::from_utf8(&raw[..pos])
        .map_err(|_| malformed("响应头不是合法的 UTF-8/ASCII"))?;
    let (status_line, headers) = match head.split_once("\r\n") {
        Some((s, h)) => (s, h),
        None => (head, ""),
    };
    Ok((status_line, headers, &raw[pos + 4..]))
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack.windows(needle.len()).position(|w| w == needle)
}

/// `HTTP/1.1 200 ` → `200`。
///
/// 单独成函数是为了让「状态行不认识」与「状态码不是 200」能报出**不同**的话。
fn parse_status_code(status_line: &str) -> Result<u16, HealthError> {
    let malformed = |detail: String| HealthError::Malformed { detail };
    let mut parts = status_line.split(' ');
    let version = parts.next().unwrap_or("");
    if !version.starts_with("HTTP/") {
        return Err(malformed(format!(
            "状态行不以 HTTP/ 开头：{status_line:?}"
        )));
    }
    let code = parts.next().unwrap_or("");
    code.parse::<u16>()
        .map_err(|_| malformed(format!("状态行里的状态码不是数字：{status_line:?}")))
}

/// 头里是否有 `Transfer-Encoding: chunked`（大小写不敏感，按 token 拆）。
fn is_chunked(headers: &str) -> bool {
    for line in headers.split("\r\n") {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        if !name.trim().eq_ignore_ascii_case("transfer-encoding") {
            continue;
        }
        if value
            .split(',')
            .any(|t| t.trim().eq_ignore_ascii_case("chunked"))
        {
            return true;
        }
    }
    false
}

/// chunked 传输编码的解码：`<十六进制长度>\r\n<数据>\r\n`…`0\r\n\r\n`。
///
/// **必须在**：本机实测后端对 HTTP/1.1 的 `/api/health` 回的就是 chunked
/// （`41\r\n{...json...}\r\n0\r\n\r\n`），不做这一步解析出来的 body 会带着
/// 长度前缀和结尾的 `0`，`serde_json` 直接报错。
///
/// 容忍裸 `\n` 作为行尾（RFC 允许，且成本是零），但**不允许**块数据后缺少行尾——
/// 那说明字节流对不上，继续猜下去只会把损坏的数据当成功。
fn chunked_decode(body: &[u8]) -> Result<Vec<u8>, HealthError> {
    let malformed = |detail: String| HealthError::Malformed { detail };
    let mut out = Vec::new();
    let mut pos = 0usize;
    loop {
        let Some((line_end, next)) = read_line(body, pos) else {
            return Err(malformed("chunked 正文在块长度那一行就断了".to_string()));
        };
        let line = std::str::from_utf8(&body[pos..line_end])
            .map_err(|_| malformed("chunked 的块长度行不是 ASCII".to_string()))?;
        // 长度后面可以跟 `;ext=...` 之类的扩展，取分号前的部分
        let size_text = line.split(';').next().unwrap_or("").trim();
        let size = usize::from_str_radix(size_text, 16)
            .map_err(|_| malformed(format!("chunked 的块长度不是十六进制数：{size_text:?}")))?;
        pos = next;

        if size == 0 {
            // 结束块。后面可能还有 trailer 头，再到一个空行为止；这里不解析 trailer。
            return Ok(out);
        }
        if size > MAX_RESPONSE_BYTES {
            return Err(malformed(format!(
                "chunked 的块长度 {size} 超过上限 {MAX_RESPONSE_BYTES}"
            )));
        }
        if pos + size > body.len() {
            return Err(malformed(format!(
                "chunked 声称有 {size} 字节，但正文只剩 {} 字节",
                body.len() - pos
            )));
        }
        out.extend_from_slice(&body[pos..pos + size]);
        pos += size;
        // 块数据后面的那个行尾（+1 容忍裸 \n 少一个 \r 的情况）
        match body.get(pos) {
            Some(b'\n') => pos += 1,
            Some(b'\r') if body.get(pos + 1) == Some(&b'\n') => pos += 2,
            _ => return Err(malformed("chunked 的块数据后面缺少行尾".to_string())),
        }
    }
}

/// 从 `pos` 起读一行，返回 `(行内容结束位置, 下一行起始位置)`。行尾可以是 `\r\n` 或 `\n`。
fn read_line(buf: &[u8], pos: usize) -> Option<(usize, usize)> {
    // `.filter(..)` 不是多余的：块流正常结束时 `pos == buf.len()`，
    // 而空切片上的 `position` 照样会返回 `None`——两条路径的返回值一样，
    // 但语义不同，显式排掉空切片后「没有了」就一定等于「断了」。
    let rest = buf.get(pos..).filter(|s| !s.is_empty())?;
    let nl = rest.iter().position(|b| *b == b'\n')? + pos;
    let end = if nl > pos && buf[nl - 1] == b'\r' {
        nl - 1
    } else {
        nl
    };
    Some((end, nl + 1))
}

/// body 字节 → [`Health`]，并钉住**两个字段都在且都是字符串**。
///
/// 字段名与前端 `Health` interface 是**两处手写定义**，可以漂移而没有任何东西
/// 会报警（TS 不校验线上来的 JSON）。把 `cache` 拼成 `cach` 就会静默变成空文案，
/// 所以这里宁可对畸形 JSON 报错，也不要 `#[serde(default)]` 吞掉缺失字段。
fn parse_health_json(body: &[u8]) -> Result<Health, HealthError> {
    if body.is_empty() {
        return Err(HealthError::Malformed {
            detail: "正文是空的，期望一个 JSON 对象".to_string(),
        });
    }
    serde_json::from_slice::<Health>(body).map_err(|e| HealthError::Malformed {
        detail: format!("正文不是合法的 Health JSON：{e}"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- 真实后端响应的字节（2026-10-16 实测捕获）----
    //
    // 不是构造的，是用 Python `socket` 直接打 `127.0.0.1:<随机端口>` 抓下来的原始字节。
    // 请求是 `GET /api/health HTTP/1.1` + `Host` + `Connection: close`，
    // 后端（Spring Boot 3.5.16 / Tomcat 10.1.55，OpenJDK 25.0.2）回的**逐字节**就是下面这条。
    // 三个形态特征都在这条里：
    //   * 状态行是 `HTTP/1.1 200 `——**尾部有一个空格**，Tomcat 不发 reason phrase
    //   * **`Transfer-Encoding: chunked`**——所以手写客户端必须有 chunked 解码器
    //   * 正文前面是 `41`（十六进制 = 65 字节，正是那段 UTF-8 JSON 的长度），后面是 `0\r\n\r\n`
    //
    // 用 `concat!` 拼普通字符串字面量（**不能用 byte string**：`b"…"` 里
    // 不允许出现非 ASCII 字符，中文会直接编译不过）。
    const REAL_BACKEND_RESPONSE: &str = concat!(
        "HTTP/1.1 200 \r\n",
        "Content-Type: application/json\r\n",
        "Transfer-Encoding: chunked\r\n",
        "Date: Fri, 16 Oct 2026 08:15:54 GMT\r\n",
        "Connection: close\r\n",
        "\r\n",
        "41\r\n",
        "{\"cache\":\"缓存：未启用（配置为关闭）\",\"status\":\"up\"}",
        "\r\n0\r\n\r\n",
    );

    // ---- 真实后端响应 → 解析 ----

    #[test]
    fn chunked_decoding_is_required_by_the_real_backend() {
        // 这条是**唯一**能守住「后端会 chunked 回」这件事的测试，也是上面那个
        // 「手写 TCP」决定的证据。它同时是 `chunked_decode` 的杀手：
        // 把 `is_chunked` 判断删掉、或把 chunked_decode 换成直通，
        // 解析出来的 body 会是 `41\r\n{...}\r\n0\r\n\r\n`，serde_json 直接报错。
        let h = parse_health_response(REAL_BACKEND_RESPONSE.as_bytes())
            .expect("真实后端响应应当解析成功");
        assert_eq!(h.status, "up");
        assert_eq!(h.cache, "缓存：未启用（配置为关闭）");
    }

    #[test]
    fn the_real_backend_response_has_a_trailing_space_in_its_status_line() {
        // Tomcat 发的状态行是 `HTTP/1.1 200 `——**尾部有空格**，没有 reason phrase。
        // 按 `split(' ')` 取第二段就能正确拿到 `200`；这条把那个形态钉住，
        // 免得有人把解析改写成更脆的写法时无声退化。
        let (status_line, headers, body) = split_response(REAL_BACKEND_RESPONSE.as_bytes()).unwrap();
        assert_eq!(status_line, "HTTP/1.1 200 ");
        assert_eq!(parse_status_code(status_line).unwrap(), 200);
        assert!(is_chunked(headers), "真实响应必须被认出是 chunked");
        // 这一条同时证明：**没有**基于 Content-Length 去截断正文
        // （真实响应里压根没有 Content-Length 头，按它截会得到空正文）
        assert!(!body.is_empty(), "正文不该是空的");
    }

    // ---- chunked ----

    #[test]
    fn decodes_a_single_chunk() {
        assert_eq!(chunked_decode(b"5\r\nhello\r\n0\r\n\r\n").unwrap(), b"hello");
    }

    #[test]
    fn decodes_multiple_chunks_and_concatenates_them_in_order() {
        // 多块是真实存在的：内容大于 Tomcat 的缓冲区时它就会切成多块。
        // 「顺序」也是被断言的一部分——拼错顺序的实现在单块测试下照样绿。
        let body = b"3\r\nabc\r\n5\r\ndefgh\r\n2\r\nij\r\n0\r\n\r\n";
        assert_eq!(chunked_decode(body).unwrap(), b"abcdefghij");
    }

    #[test]
    fn decodes_a_terminating_chunk_with_trailers() {
        // 结束块后面可以跟 trailer 头，再到一个空行。不解析但**不能报错**。
        assert_eq!(
            chunked_decode(b"5\r\nhello\r\n0\r\nX-Trace: abc\r\n\r\n").unwrap(),
            b"hello"
        );
    }

    #[test]
    fn decodes_an_empty_chunked_body() {
        // 只有结束块：正文确实是空的。这一条与「正文是空的」那个错误必须区分开，
        // 所以它返回 `Ok(空)`，由 parse_health_json 去报「正文是空的」。
        assert_eq!(chunked_decode(b"0\r\n\r\n").unwrap(), b"");
    }

    #[test]
    fn tolerates_chunk_extensions() {
        // RFC 9112 允许 `长度;扩展`。真实后端不发，但容错的成本是零。
        assert_eq!(
            chunked_decode(b"5;foo=bar\r\nhello\r\n0\r\n\r\n").unwrap(),
            b"hello"
        );
    }

    #[test]
    fn tolerates_a_bare_lf_line_ending() {
        // RFC 允许裸 \n。真实后端发的是 CRLF，但容错不该只对一种行尾成立。
        assert_eq!(chunked_decode(b"5\nhello\n0\n\n").unwrap(), b"hello");
    }

    #[test]
    fn uppercase_hex_chunk_size_is_accepted() {
        // `A` 与 `a` 都是合法的十六进制长度。
        assert_eq!(
            chunked_decode(b"A\r\n0123456789\r\n0\r\n\r\n").unwrap(),
            b"0123456789"
        );
        assert_eq!(
            chunked_decode(b"a\r\n0123456789\r\n0\r\n\r\n").unwrap(),
            b"0123456789"
        );
    }

    #[test]
    fn rejects_a_chunk_size_that_is_not_hex() {
        assert!(matches!(
            chunked_decode(b"zz\r\nhello\r\n0\r\n\r\n").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_chunk_cut_short() {
        // 声称 10 字节，实际只给了 3 个——继续猜下去会把损坏的数据当成功。
        match chunked_decode(b"a\r\nabc").unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("只剩"),
                "消息该指出字节数不够，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_a_chunk_missing_its_trailing_crlf() {
        assert!(matches!(
            chunked_decode(b"3\r\nabc").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_chunk_stream_without_a_terminating_block() {
        // 块数据正常，但永远没有 `0\r\n\r\n`：读到流末尾就该报错，
        // 而不是把已经收到的那点当当完整正文交出去。
        assert!(matches!(
            chunked_decode(b"3\r\nabc\r\n").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_chunk_above_the_response_cap() {
        // 一个足够大的长度不该让外壳去分配内存——先在长度这一关拦掉。
        let big = format!("{:x}\r\n", MAX_RESPONSE_BYTES + 1);
        match chunked_decode(big.as_bytes()).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("超过上限"),
                "消息该说超过上限，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    // ---- 读路径的字节上限 ----

    #[test]
    fn read_to_end_reads_everything_when_under_the_cap() {
        let mut src = std::io::Cursor::new(b"abcdefghij".to_vec());
        assert_eq!(read_to_end(&mut src, 100).unwrap(), b"abcdefghij");
    }

    #[test]
    fn read_to_end_stops_at_the_byte_cap() {
        // 上限是**必须**的：一个不停吐字节（或畸形）的响应不能把外壳的内存吃光。
        // 把上限那个判断删掉，这条会一直读——这里的源有 10MB，测试会明显变慢而非挂死。
        let mut src = std::io::Cursor::new(vec![b'x'; 10 * 1024 * 1024]);
        let out = read_to_end(&mut src, 64).unwrap();
        assert_eq!(out.len(), 64, "应当停在上限 64 字节");
        assert!(out.iter().all(|b| *b == b'x'));
    }

    #[test]
    fn read_to_end_propagates_io_errors() {
        struct Boom;
        impl std::io::Read for Boom {
            fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
                Err(std::io::Error::new(std::io::ErrorKind::ConnectionReset, "boom"))
            }
        }
        let err = read_to_end(&mut Boom, 64).unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::ConnectionReset);
    }

    // ---- 状态行 ----

    #[test]
    fn parses_a_status_line_with_a_reason_phrase() {
        assert_eq!(parse_status_code("HTTP/1.1 200 OK").unwrap(), 200);
        assert_eq!(parse_status_code("HTTP/1.0 404 Not Found").unwrap(), 404);
    }

    #[test]
    fn rejects_a_status_line_that_is_not_http() {
        match parse_status_code("<html>hello</html>").unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("不以 HTTP/ 开头"),
                "消息该指出状态行不认识，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_a_status_line_with_a_non_numeric_code() {
        assert!(matches!(
            parse_status_code("HTTP/1.1 abc OK").unwrap_err(),
            HealthError::Malformed { .. }
        ));
        assert!(matches!(
            parse_status_code("HTTP/1.1").unwrap_err(),
            HealthError::Malformed { .. }
        ));
        // 空状态行：版本段是空串，不以 HTTP/ 开头
        assert!(matches!(
            parse_status_code("").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    // ---- 整条响应 ----

    /// 拼一条不带 chunked 的响应（HTTP/1.0 请求时后端回的就是这个形态）。
    fn plain_response(status: &str, content_type: &str, body: &str) -> Vec<u8> {
        format!("{status}\r\nContent-Type: {content_type}\r\nConnection: close\r\n\r\n{body}")
            .into_bytes()
    }

    #[test]
    fn parses_a_plain_response_without_chunked() {
        // 后端对 HTTP/1.0 请求回的就是这个形态（实测）：没有 Transfer-Encoding，
        // 正文直接跟在空行后面。这条守住「没有 chunked 也要能读」。
        let raw = plain_response(
            "HTTP/1.1 200 ",
            "application/json",
            "{\"cache\":\"缓存：未启用（配置为关闭）\",\"status\":\"up\"}",
        );
        let h = parse_health_response(&raw).unwrap();
        assert_eq!(h.status, "up");
        assert_eq!(h.cache, "缓存：未启用（配置为关闭）");
    }

    #[test]
    fn recognizes_a_lowercase_chunked_header_name() {
        // 头名大小写不敏感。真实后端发的是 `Transfer-Encoding`，
        // 但如果哪天变成 `transfer-encoding`，判断不该因此失效。
        let raw = concat!(
            "HTTP/1.1 200 \r\n",
            "content-type: application/json\r\n",
            "transfer-encoding: chunked\r\n",
            "\r\n",
            "2\r\n{}\r\n0\r\n\r\n",
        );
        let (_, headers, _) = split_response(raw.as_bytes()).unwrap();
        assert!(is_chunked(headers));
    }

    #[test]
    fn a_chunked_header_with_other_tokens_still_counts() {
        // `Transfer-Encoding: gzip, chunked` 也是 chunked。
        let (_, headers, _) =
            split_response(b"HTTP/1.1 200 \r\nTransfer-Encoding: gzip, chunked\r\n\r\nx")
                .unwrap();
        assert!(is_chunked(headers));
        // 别的编码不算
        let (_, headers, _) =
            split_response(b"HTTP/1.1 200 \r\nTransfer-Encoding: gzip\r\n\r\nx").unwrap();
        assert!(!is_chunked(headers));
        // 相似但不同的头名不算
        let (_, headers, _) = split_response(b"HTTP/1.1 200 \r\nX-Transfer-Encoding: chunked\r\n\r\nx")
            .unwrap();
        assert!(!is_chunked(headers));
    }

    #[test]
    fn rejects_a_non_200_status() {
        // 「HTTP 非 2xx 时抛错」这条语义的**唯一**去处：HTTP 不再由前端发了，
        // 它由 Rust 侧承担。这条就是那个语义在新位置的等价物。
        match parse_health_response(&plain_response(
            "HTTP/1.1 500 ",
            "application/json",
            "{\"error\":\"boom\"}",
        ))
        .unwrap_err()
        {
            HealthError::NotOk { code } => assert_eq!(code, 500),
            other => panic!("应当是 NotOk，实际 {other:?}"),
        }
        assert!(matches!(
            parse_health_response(&plain_response("HTTP/1.1 404 ", "application/json", "{}"))
                .unwrap_err(),
            HealthError::NotOk { code: 404 }
        ));
        // 3xx 与 1xx 同样不是 200
        for code in [100u16, 204, 301, 302] {
            let raw = plain_response(&format!("HTTP/1.1 {code} "), "text/plain", "");
            assert!(
                matches!(
                    parse_health_response(&raw).unwrap_err(),
                    HealthError::NotOk { .. }
                ),
                "{code} 不该被当成成功"
            );
        }
    }

    #[test]
    fn rejects_a_non_200_chunked_response() {
        // 非 2xx 也要走 chunked 那条路（后端 404 就是 chunked 回的，实测）。
        // 状态码先于正文判定，所以即使正文是合法 JSON 也必须拒。
        let raw = concat!(
            "HTTP/1.1 404 \r\n",
            "Content-Type: application/json\r\n",
            "Transfer-Encoding: chunked\r\n",
            "\r\n",
            "2\r\n{}\r\n0\r\n\r\n",
        );
        assert!(matches!(
            parse_health_response(raw.as_bytes()).unwrap_err(),
            HealthError::NotOk { code: 404 }
        ));
    }

    #[test]
    fn the_not_ok_error_message_carries_the_code() {
        // 非 2xx 那条消息必须带上真正的状态码：用户看到「HTTP 500」才知道是
        // 后端自己炸了，而不是「读不懂响应」那种没法行动的话。
        let msg = HealthError::NotOk { code: 503 }.into_message();
        assert!(msg.contains("503"), "消息里应当有状态码，实际 {msg}");
        assert!(msg.contains("200"), "消息该说明期望值，实际 {msg}");
    }

    #[test]
    fn rejects_an_empty_body() {
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "");
        match parse_health_response(&raw).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("正文是空的"),
                "消息该说是空正文，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_an_empty_chunked_body_the_same_way() {
        // chunked 版的空正文（只有结束块）也要落到「正文是空的」，
        // 而不是在 chunked 解码那里报一句别的——同一个真相对用户应当是同一句话。
        let raw = b"HTTP/1.1 200 \r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n";
        match parse_health_response(raw).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("正文是空的"),
                "消息该说是空正文，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_malformed_json() {
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "not json at all");
        match parse_health_response(&raw).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("合法的 Health JSON"),
                "消息该指出 JSON 不合法，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
        // 截断的 JSON（真实世界里最常见的畸形：连接被中途掐断）
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "{\"cache\":\"缓存");
        assert!(matches!(
            parse_health_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_json_that_is_not_an_object() {
        for body in ["[]", "\"a string\"", "42", "null", "true"] {
            let raw = plain_response("HTTP/1.1 200 ", "application/json", body);
            assert!(
                matches!(
                    parse_health_response(&raw).unwrap_err(),
                    HealthError::Malformed { .. }
                ),
                "{body} 不该被当成 Health"
            );
        }
    }

    #[test]
    fn rejects_a_body_missing_the_status_field() {
        let raw = plain_response(
            "HTTP/1.1 200 ",
            "application/json",
            "{\"cache\":\"缓存：未启用（配置为关闭）\"}",
        );
        assert!(matches!(
            parse_health_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_body_missing_the_cache_field() {
        // **跨语言字段名漂移的守卫**：后端把 `cache` 漏给（或改名）时，这里必须报错。
        // 如果 Health 上写了 `#[serde(default)]`，这一条会变成「静默显示空缓存文案」——
        // 用户看到状态栏右侧一片空白，没有任何线索。
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "{\"status\":\"up\"}");
        match parse_health_response(&raw).unwrap_err() {
            HealthError::Malformed { detail } => assert!(
                detail.contains("cache"),
                "消息里该点名缺的是 cache，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_a_misspelled_cache_field() {
        // 与上一条成对：`cach` 这种**拼错**（而不是漏掉）也必须报错。
        // 这条是变异表里「字段名 cache 改成 cach」在 Rust 侧的杀手；
        // 前端那侧另有一条（backend.spec.ts 的跨语言契约）。
        let raw = plain_response(
            "HTTP/1.1 200 ",
            "application/json",
            "{\"cach\":\"缓存：未启用（配置为关闭）\",\"status\":\"up\"}",
        );
        assert!(
            matches!(
                parse_health_response(&raw).unwrap_err(),
                HealthError::Malformed { .. }
            ),
            "拼错的字段名必须报错，不能静默变成空文案"
        );
    }

    #[test]
    fn rejects_a_field_of_the_wrong_type() {
        let raw = plain_response("HTTP/1.1 200 ", "application/json", "{\"status\":1,\"cache\":2}");
        assert!(matches!(
            parse_health_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_response_without_the_blank_line() {
        // 没有 `\r\n\r\n`：可能根本不是 HTTP（比如连到了别的服务）。
        match parse_health_response(b"HTTP/1.1 200 \r\nContent-Type: application/json\r\n")
            .unwrap_err()
        {
            HealthError::Malformed { detail } => assert!(
                detail.contains("空行"),
                "消息该指出缺少头/正文之间的空行，实际 {detail}"
            ),
            other => panic!("应当是 Malformed，实际 {other:?}"),
        }
    }

    #[test]
    fn rejects_a_completely_empty_response() {
        assert!(matches!(
            parse_health_response(b"").unwrap_err(),
            HealthError::Malformed { .. }
        ));
        // 只有一个空行的畸形响应
        assert!(matches!(
            parse_health_response(b"\r\n\r\n").unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn rejects_a_response_whose_headers_are_not_ascii() {
        // 头里混进非 UTF-8 字节：不该 panic，也不该把它当成正文去解析。
        let mut raw = b"HTTP/1.1 200 \r\nX-Bad: ".to_vec();
        raw.extend_from_slice(&[0xff, 0xfe]);
        raw.extend_from_slice(b"\r\n\r\n{}");
        assert!(matches!(
            parse_health_response(&raw).unwrap_err(),
            HealthError::Malformed { .. }
        ));
    }

    #[test]
    fn keeps_non_ascii_body_bytes_intact() {
        // 缓存文案是中文，长度前缀算的是**字节数**不是字符数。
        // 把 chunked 解码写成按字符切会让这条红。
        let json = "{\"cache\":\"缓存：已连接 127.0.0.1:6399\",\"status\":\"up\"}";
        let chunk = format!("{:x}\r\n{}\r\n0\r\n\r\n", json.len(), json);
        let raw = format!("HTTP/1.1 200 \r\nTransfer-Encoding: chunked\r\n\r\n{chunk}");
        let h = parse_health_response(raw.as_bytes()).unwrap();
        assert_eq!(h.cache, "缓存：已连接 127.0.0.1:6399");
    }

    #[test]
    fn the_health_struct_serializes_with_the_field_names_the_frontend_reads() {
        // **跨语言契约**（与 backend.rs 里 BackendState 那条同一个套路）：
        // 前端 `Health` interface 读的是 `status` 与 `cache` 两个键。
        // 这条把 Rust 真的序列化出来的字符串钉死——改了字段名或加了
        // `skip_serializing_if`，前端就会静默拿到 undefined。
        //
        // 下面这段 JSON 是原样复制前端 `backend.spec.ts` 里那条契约测试的输入，
        // 两处必须一字不差。
        let h = Health {
            status: "up".into(),
            cache: "缓存：未启用（配置为关闭）".into(),
        };
        assert_eq!(
            serde_json::to_string(&h).unwrap(),
            "{\"status\":\"up\",\"cache\":\"缓存：未启用（配置为关闭）\"}"
        );
    }

    // ---- 请求形状 ----

    #[test]
    fn the_request_targets_the_health_endpoint_and_asks_to_close() {
        let req = health_request(51234);
        assert!(req.starts_with("GET /api/health HTTP/1.1\r\n"), "实际 {req:?}");
        // Host 头是 HTTP/1.1 的**必需**头——实测缺了它会拿到 Tomcat 的 400
        assert!(req.contains("Host: 127.0.0.1:51234\r\n"), "实际 {req:?}");
        // 必须要求关闭：读循环靠 EOF 收尾，长连接会卡到超时
        assert!(req.contains("Connection: close\r\n"), "实际 {req:?}");
        assert!(req.ends_with("\r\n\r\n"), "实际 {req:?}");
        // 逐行检查：`format!` 续行里的缩进是**看不见的**，行首一旦混进空格，
        // 请求就会变成 ` Host: ...` 这种畸形头。
        for line in req.split("\r\n") {
            assert_eq!(line, line.trim_end(), "请求里不该有行尾空格：{line:?}");
            assert_eq!(line, line.trim_start(), "请求里不该有行首空格：{line:?}");
        }
    }

    // ---- 错误消息（给人看的那些话）----

    #[test]
    fn every_failure_class_gets_a_different_message() {
        // 四种失败必须各说各的话。用同一个枚举的不同变体拿到四条消息，
        // 断言它们两两不同——若有人把 into_message 改成一句「健康检查失败」，
        // 用户就再也分不清「后端没起来」和「后端读不懂」了。
        let addr = "127.0.0.1:1".to_string();
        let msgs = vec![
            HealthError::Connect {
                addr: addr.clone(),
                source: std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "refused"),
            }
            .into_message(),
            HealthError::Read {
                addr,
                source: std::io::Error::new(std::io::ErrorKind::TimedOut, "timed out"),
            }
            .into_message(),
            HealthError::NotOk { code: 500 }.into_message(),
            HealthError::Malformed {
                detail: "正文是空的".to_string(),
            }
            .into_message(),
        ];
        for (i, a) in msgs.iter().enumerate() {
            for (j, b) in msgs.iter().enumerate() {
                if i != j {
                    assert_ne!(a, b, "第 {i} 条与第 {j} 条消息不该一样");
                }
            }
            assert!(!a.is_empty());
        }
    }

    #[test]
    fn a_read_timeout_is_worded_as_a_timeout_not_as_a_generic_io_error() {
        // Windows 上 socket 读超时常常报 `WouldBlock`，直接 to_string 出来是
        // 「资源暂时不可用」这种指不出下一步的话。这条钉住它被识别成超时。
        for kind in [
            std::io::ErrorKind::TimedOut,
            std::io::ErrorKind::WouldBlock,
            
        ] {
            let msg = HealthError::Read {
                addr: "127.0.0.1:1".into(),
                source: std::io::Error::new(kind, "some io error"),
            }
            .into_message();
            assert!(msg.contains("没有回话"), "kind={kind:?} 该被认成超时：{msg}");
        }
        // 别的 IO 错误如实报「通信失败」，不该硬说成超时
        let msg = HealthError::Read {
            addr: "127.0.0.1:1".into(),
            source: std::io::Error::new(std::io::ErrorKind::ConnectionReset, "reset"),
        }
        .into_message();
        assert!(msg.contains("通信失败"), "实际 {msg}");
    }

    #[test]
    fn is_timeout_also_recognizes_the_textual_form() {
        // 兜底那一道：有些平台把超时包成 Other + "timed out" 的文本。
        // `is_timeout` 是 backend 模块的私有函数，这里通过公开的 `into_message`
        // 间接钉住它——只有 `Other` 这一类才走文本判断，别的不受影响。
        let worded = |text: &str| {
            HealthError::Read {
                addr: "127.0.0.1:1".into(),
                source: std::io::Error::other(text),
            }
            .into_message()
        };
        assert!(worded("operation timed out").contains("没有回话"));
        assert!(worded("would block").contains("没有回话"));
        assert!(!worded("connection reset by peer").contains("没有回话"));
    }
}
```

#### `frontend/src-tauri/tests/health_socket.rs`（新增）

```rust
//! `get_health` 的**真 socket** 测试。
//!
//! 纯解析逻辑（状态行、chunked、畸形 JSON…）的测试在 `src/http.rs` 里，与实现
//! 同一个文件——那里的私有用具（`chunked_decode`、`split_response`）在集成测试里
//! 是看不见的。这个文件只测**能从一个 socket 真的跑完一整轮**的那条路径，
//! 以及两类只有在真 socket 上才存在的失败：**超时**与**连接被拒**。
//!
//! 放在 `tests/` 而不是 `src/` 是有意的：它只能碰 `change_lib` 的公开 API，
//! 所以它同时是「`get_health` 这条路对外可用」的证据。

use change_lib::http::{get_health, HEALTH_TIMEOUT};

/// 一个**接受连接但永不回包**的假后端。
///
/// 这是唯一能测到「超时」这条路径的办法：连一个没人监听的端口拿到的是
/// 「connection refused」（另一条测试），**不是超时**。要造超时就必须有一个
/// 真的接受连接、把请求读走、之后什么都不发的服务端。
struct SilentServer {
    port: u16,
    stop: std::sync::mpsc::Sender<()>,
    handle: Option<std::thread::JoinHandle<()>>,
}

impl SilentServer {
    fn start() -> Self {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("绑定一个临时端口");
        let port = listener.local_addr().expect("读取本地地址").port();
        let (stop, stop_rx) = std::sync::mpsc::channel::<()>();
        let handle = std::thread::spawn(move || {
            let Ok((mut s, _)) = listener.accept() else {
                return;
            };
            // 把请求读掉（清空内核缓冲区），然后**什么都不发**
            let mut buf = [0u8; 1024];
            let _ = std::io::Read::read(&mut s, &mut buf);
            // 连接保持打开，直到测试喊停：不发字节 = 「活着但不回话」。
            // 上限给得比 HEALTH_TIMEOUT 宽得多，这样被测代码一定是先超时的那一方。
            let _ = stop_rx.recv_timeout(HEALTH_TIMEOUT * 4);
            drop(s);
        });
        Self {
            port,
            stop,
            handle: Some(handle),
        }
    }
}

impl Drop for SilentServer {
    fn drop(&mut self) {
        // 先喊停（`recv_timeout` 会立刻返回），再 join。
        // **join 不能省**：线程还在挂着的时候测试就结束的话，
        // 它可能和下一个测试的临时端口撞上，制造偶发红。
        let _ = self.stop.send(());
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

#[test]
fn get_health_times_out_instead_of_hanging() {
    // **这是「去掉超时」那个变异的杀手。**
    //
    // 后端活着但不回话时（线程池打满、GC 停顿、socket 卡住），
    // `set_read_timeout` / `set_write_timeout` 那两行如果没有，`read_to_end`
    // 会一直阻塞在 recv 上——前端的 5 秒轮询会一个个堆起来，永远不返回。
    //
    // 变异实测（2026-10-16，`_healthcheck/m1_probe.py` 把两行超时整段删掉再跑本文件）：
    // `FAILED ... finished in 10.10s`，**是一条正常失败，不是挂死**。
    // 原因是这个假服务自己在 `HEALTH_TIMEOUT * 4`（8 秒）之后关掉了连接，
    // 于是阻塞的 recv 拿到 EOF、`read_to_end` 返回了一条空响应，
    // 「应当在上限附近放弃（< 6 秒）」与「消息该说后端没回话」两条断言同时红。
    // 也就是说：**这个变异确实被杀，但杀它的不是「挂死」而是这两条断言。**
    // 想让它真的挂死，假服务必须永不关连接——那会把整个测试套件拖成偶发挂起，
    // 代价大于收益，所以这里刻意选了「有界地失败」。
    let server = SilentServer::start();
    let start = std::time::Instant::now();
    let err = get_health(server.port).expect_err("假服务不回包，必须失败");
    let took = start.elapsed();

    assert!(
        err.contains("没有回话"),
        "超时的消息该说的是「后端没回话」，而不是一句泛泛的 IO 错误：{err}"
    );
    assert!(
        took < HEALTH_TIMEOUT * 3,
        "应当在上限附近就放弃（{HEALTH_TIMEOUT:?}），实际用了 {took:?}"
    );
    assert!(
        took >= HEALTH_TIMEOUT,
        "不该比上限还早就放弃——那说明放弃的理由不是超时，实际 {took:?}"
    );
}

#[test]
fn get_health_reports_connection_refused_when_nothing_is_listening() {
    // 关掉的端口：`connect` 立刻被拒。这条与超时**必须报不同的话**——
    // 「连不上」（后端还没起来，可以重试）与「不回话」（卡住了，下一步不同）
    // 是两个不同的事实。
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener); // 端口现在是空的

    let err = get_health(port).expect_err("没人监听时必须失败");
    assert!(
        err.contains("连不上"),
        "该报「连不上」而不是超时或别的：{err}"
    );
    assert!(
        !err.contains("没有回话"),
        "连不上 ≠ 不回话，不许混：{err}"
    );
}

#[test]
fn get_health_reads_a_real_http_response_from_a_local_socket() {
    // 这条走的是**完整的 socket 路径**（connect → 写请求 → 读整条响应 → 解析），
    // 与上面两条失败路径合起来把 `get_health` 整个函数覆盖住，
    // 而不只是它调用的那些纯函数。
    use std::io::{Read as _, Write as _};

    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        // 把请求读出来并交回去断言形状（否则「测试过了」可能只是服务端
        // 随便发了点东西，而请求压根没发对）
        let mut buf = [0u8; 512];
        let n = s.read(&mut buf).unwrap();
        let req = String::from_utf8_lossy(&buf[..n]).to_string();
        let body = "{\"cache\":\"缓存：未启用（配置为关闭）\",\"status\":\"up\"}";
        let resp = format!(
            "HTTP/1.1 200 \r\nContent-Type: application/json\r\n\
             Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n\
             {:x}\r\n{}\r\n0\r\n\r\n",
            body.len(),
            body
        );
        s.write_all(resp.as_bytes()).unwrap();
        s.flush().unwrap();
        req
    });

    let h = get_health(port).expect("本地假服务应当被正确解析");
    let req = server.join().unwrap();
    assert_eq!(h.status, "up");
    assert_eq!(h.cache, "缓存：未启用（配置为关闭）");
    assert!(
        req.starts_with("GET /api/health HTTP/1.1\r\n"),
        "实际请求：{req:?}"
    );
    assert!(
        req.contains(&format!("Host: 127.0.0.1:{port}\r\n")),
        "实际请求：{req:?}"
    );
}

#[test]
fn get_health_rejects_a_500_from_a_real_socket() {
    // 非 2xx 的**语义**（旧版前端 `if (!res.ok) throw` 那条）在新位置上
    // 也要能从真 socket 上走通——不是只有纯函数层面成立。
    use std::io::{Read as _, Write as _};

    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        let mut buf = [0u8; 512];
        let _ = s.read(&mut buf).unwrap();
        // Content-Length 恰好覆盖 `{}` 两个字节，多出来的 `#` 是刻意的噪声
        s.write_all(
            b"HTTP/1.1 500 \r\nContent-Type: application/json\r\n\
              Content-Length: 2\r\nConnection: close\r\n\r\n{}#",
        )
        .unwrap();
        s.flush().unwrap();
    });

    let err = get_health(port).expect_err("500 必须失败");
    server.join().unwrap();
    assert!(err.contains("500"), "消息里应当有状态码：{err}");
    assert!(err.contains("200"), "消息该说明期望值：{err}");
}
```

#### `frontend/src-tauri/src/lib.rs`（改动）

```rust
mod backend;
pub mod http;

use backend::{Backend, BackendState, BackendStatus};
use std::path::PathBuf;
use tauri::{Manager, RunEvent};

/// 前端轮询它。**端口只从这里出去**——前端不许硬编码端口。
#[tauri::command]
fn get_backend_status(backend: tauri::State<'_, Backend>) -> BackendState {
    backend.state.lock().unwrap().clone()
}

/// 代前端去问后端的 `/api/health`。
///
/// **为什么是外壳代发而不是前端 `fetch`**：页面源（dev 的 `http://127.0.0.1:1420`、
/// 打包后的 `tauri://localhost`）与后端的随机端口不同源，而响应里没有
/// `Access-Control-Allow-Origin`，浏览器会把响应拦掉——前端只能看到
/// `TypeError: Failed to fetch`。详见 `src/http.rs` 的模块注释。
///
/// # 为什么返回 `Result<_, String>`
///
/// Tauri 会把 `Err` 变成 invoke 的 reject，前端现有的 `catch` 分支直接接得住，
/// 不需要为「健康检查失败」再造一条带错误字段的返回类型。
///
/// # 就绪守卫
///
/// 端口只在**状态是 `Ready`** 时才有意义（`with_port` 是唯一给它赋值的地方）。
/// `Starting` 时端口还是 `None`——那时去连 `127.0.0.1:0` 只会拿到一个
/// 「连不上」的假错误，把「后端还在起」误报成「后端坏了」。`Failed` 时同理，
/// 而且失败原因已经在 `message` 里，比任何连接错误都准。
#[tauri::command]
fn get_health(backend: tauri::State<'_, Backend>) -> Result<backend::Health, String> {
    let (status, port, message) = {
        let s = backend.state.lock().unwrap();
        (s.status.clone(), s.port, s.message.clone())
    };
    match status {
        BackendStatus::Ready => {
            // `Ready` 必然带端口；真拿不到说明状态机被改坏了，如实报出来而不是猜一个端口
            let port = port.ok_or_else(|| {
                "后端状态是 ready，但没有端口——这是外壳内部的矛盾，请报告".to_string()
            })?;
            http::get_health(port)
        }
        BackendStatus::Starting => {
            Err("后端还在启动中，暂时没有健康状态可读".to_string())
        }
        BackendStatus::Failed => {
            Err(format!("后端没有起来，读不到健康状态：{message}"))
        }
    }
}

/// 找后端 jar。优先环境变量，其次相对仓库的默认位置。
///
/// 候选路径是**相对当前工作目录**的：开发时 `cargo tauri dev` 从 `src-tauri`
/// 起进程，`../../backend/target/...` 就是仓库根下的 jar。找不到时把当前工作目录
/// 写进错误消息里，用户才知道为什么没命中。
fn locate_jar() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("CHANGE_BACKEND_JAR") {
        let path = PathBuf::from(&p);
        return if path.is_file() {
            Ok(path)
        } else {
            Err(format!("CHANGE_BACKEND_JAR 指向的文件不存在：{p}"))
        };
    }
    // 开发时的默认位置：src-tauri 往上三层是仓库根
    let candidates = [
        "../../backend/target/change-backend-0.1.0-SNAPSHOT.jar",
        "../backend/target/change-backend-0.1.0-SNAPSHOT.jar",
    ];
    for c in candidates {
        let path = PathBuf::from(c);
        if path.is_file() {
            return Ok(path);
        }
    }
    let cwd = std::env::current_dir()
        .map(|p| p.display().to_string())
        .unwrap_or_else(|e| format!("<拿不到工作目录：{e}>"));
    Err(format!(
        "找不到后端 jar。请先在 backend/ 下执行 `mvn -B -q -DskipTests package`，\
         或设环境变量 CHANGE_BACKEND_JAR 指向它。\
         （当前工作目录：{cwd}；已尝试的相对路径：{}）",
        candidates.join(" 与 ")
    ))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![get_backend_status, get_health])
        .setup(|app| {
            // 注意：`resolve_java` 返回 `PathBuf`，**不是 `Option`**。
            // 它刻意不做存在性检查——真是 Java 找不到，`Command::spawn` 抛出的
            // OS 错误比我们在这里猜的任何话都准，那条错误会经 `from_failed`
            // 显示给用户。所以这里不需要（也不能）`?` 或 `.ok_or(...)`。
            let java = backend::resolve_java(
                std::env::var("CHANGE_JAVA").ok().as_deref(),
                std::env::var("JAVA_HOME").ok().as_deref(),
            );

            let backend = match locate_jar() {
                Ok(jar) => {
                    let extra = std::env::var("CHANGE_BACKEND_ARGS")
                        .map(|s| s.split_whitespace().map(String::from).collect())
                        .unwrap_or_default();
                    match Backend::spawn(java, jar, extra) {
                        Ok(b) => b,
                        Err(e) => Backend::from_failed(format!("拉起后端进程失败：{e}")),
                    }
                }
                // 没有 jar 就没有子进程——`from_failed` 是唯一能造出这种句柄的方式
                // （`child` 字段对 backend 模块之外是私有的，结构体更新语法用不了）。
                Err(msg) => Backend::from_failed(msg),
            };

            app.manage(backend);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("Tauri 应用启动失败")
        .run(|app_handle, event| {
            // 窗口关闭时把后端一起带走，别留孤儿进程
            if let RunEvent::ExitRequested { .. } | RunEvent::Exit = event {
                if let Some(b) = app_handle.try_state::<Backend>() {
                    b.shutdown();
                }
            }
        });
}

#[cfg(test)]
mod tests {
    /// 命令注册表——**前端唯二能调到的两个名字**。
    ///
    /// # 为什么要有这条测试
    ///
    /// `tauri::generate_handler![]` 里漏掉一个命令，**编译照样通过**
    /// （最坏只是一条 `function is never used` 警告），`cargo test` 也照样全绿：
    /// 没有任何 Rust 测试会去碰 `.invoke_handler(...)` 这个调用。
    /// 而真机上的表现是前端 `invoke('get_health')` 直接 reject
    /// （`command get_health not found`）——正是本任务要修的那一类静默失效。
    ///
    /// 变异实测（Task 8 的变异表）：把 `get_health` 从注册表里删掉，
    /// 加这条测试之前 **75 passed / 0 failed，变异存活**；加完之后它变红。
    /// 前端那侧杀不了这个变异：`backend.spec.ts` 与 `BackendStatusBar.spec.ts`
    /// 都 mock 掉了 `invoke`，命令注册表不在它们的观察范围内。
    ///
    /// 用 `include_str!("lib.rs")` 而不是读磁盘：这样测试与大写文件**逐字同源**，
    /// 不存在「读到了别的路径下另一个 lib.rs」的可能。
    /// 断言取的是宏调用那一行，而不是整个文件——避免把注释里提到命令名也算作注册
    /// （本文件的注释里就写了好几次 `get_health`）。
    #[test]
    fn both_commands_are_registered_in_the_invoke_handler() {
        const SRC: &str = include_str!("lib.rs");

        let line = SRC
            .lines()
            .find(|l| l.contains("generate_handler!["))
            .expect("lib.rs 里找不到 generate_handler! 调用——注册表不见了");
        assert!(
            !line.trim_start().starts_with("//"),
            "generate_handler! 那一行不该是注释：{line}"
        );

        for cmd in ["get_backend_status", "get_health"] {
            assert!(
                line.contains(cmd),
                "命令 {cmd:?} 没有注册进 invoke_handler——前端 invoke 它会直接 reject。\
                 实际那一行是：{line}"
            );
        }

        // 反面：`get_health` 必须真的在**宏的参数表**里，而不是出现在行尾注释里
        let args = line
            .split_once("generate_handler![")
            .and_then(|(_, rest)| rest.split_once(']'))
            .map(|(args, _)| args)
            .expect("generate_handler![...] 的括号不配对");
        assert!(
            !args.contains("//"),
            "注册表的参数里混进了注释，说明这行已经不像一个命令清单了：{args}"
        );
        assert_eq!(
            args.split(',').map(|s| s.trim()).filter(|s| !s.is_empty()).count(),
            2,
            "命令清单恰好是两条；实际参数：{args}"
        );
    }
}
```

#### `frontend/src/api/backend.spec.ts`（改动）

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// `checkHealth` 现在走 Tauri 的 invoke，不再自己发 HTTP。
//
// 这条改动的原因是**真机验收发现的缺陷**：页面的源（dev 是
// `http://127.0.0.1:1420`，打包后是 `tauri://localhost`）与后端随机端口不同源，
// 而响应没有 `Access-Control-Allow-Origin`，浏览器把响应拦掉，前端只看到
// `TypeError: Failed to fetch`。修法是把这条请求交给外壳（Rust）去发——
// 详见 `src-tauri/src/http.rs` 的模块注释。
//
// 所以这个文件不再 stub `fetch`，改为 mock `@tauri-apps/api/core` 的 `invoke`。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { checkHealth, describeState, type BackendState } from './backend'

const invokeMock = vi.mocked(invoke)

describe('describeState', () => {
  it('starting 时给出加载中文案', () => {
    const s: BackendState = { status: 'starting', port: null, message: '正在启动后端…' }
    expect(describeState(s).tone).toBe('loading')
  })

  it('ready 时带上端口', () => {
    const s: BackendState = { status: 'ready', port: 51234, message: '' }
    const d = describeState(s)
    expect(d.tone).toBe('ok')
    expect(d.text).toContain('51234')
  })

  it('failed 时显示原因', () => {
    const s: BackendState = { status: 'failed', port: null, message: '后端启动超时' }
    const d = describeState(s)
    expect(d.tone).toBe('error')
    expect(d.text).toBe('后端启动超时')
  })

  it('遇到没见过的状态时显示出来，而不是渲染成空白', () => {
    // Rust 侧加了第四个 BackendStatus 变体而忘了同步这里时，
    // 旧的穷尽 switch 会返回 undefined：状态栏一片空白、没有任何提示。
    // 这条测试守的是「宁可显示看不懂，也不要静默变空」。
    //
    // **它与「喂想象出来的对象去测正常路径」不同**：那种测试是拿假数据
    // 冒充真实输入（假数据一变，测试仍然绿，等于没测）。这里喂的假状态
    // 不是要冒充哪个真实状态，而是**专门去走 `default` 这个真实存在的
    // 防御分支**——它测的是代码里那条防御路径本身，不是某条正常路径。
    const weird = { status: 'hibernating', port: null, message: '' } as unknown as BackendState
    const d = describeState(weird)
    expect(d.tone).toBe('error')
    expect(d.text).toContain('hibernating')
  })

  // 跨语言契约：Rust 的 BackendState 与这里的 interface 是**两处手写定义**，
  // 它们可以漂移而没有任何东西会报警（TS 不校验线上来的 JSON）。
  // 唯一能证明两边形状一致的方式，是拿 Rust 真的序列化出来的字符串喂进来。
  //
  // 这三段 JSON 是从 Rust 侧实测拿到的（见 src-tauri/src/backend.rs 的
  // the_state_serializes_with_status_as_a_plain_string），不是手写的。
  // 原文（原样复制，未改一个字符）：
  //   {"status":"starting","port":null,"message":"正在启动后端…"}
  //   {"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}
  //   {"status":"failed","port":null,"message":"后端启动失败：进程已退出（退出码 1），但没有输出 CHANGE_PORT=<端口> 这一行。请确认 jar 路径与 Java 可用，或查看后端自己的日志。"}
  it('Rust 实际输出的三种 JSON 都能被正确解读', () => {
    const fromRust: string[] = [
      '{"status":"starting","port":null,"message":"正在启动后端…"}',
      '{"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}',
      '{"status":"failed","port":null,"message":"后端启动失败：进程已退出（退出码 1），但没有输出 CHANGE_PORT=<端口> 这一行。请确认 jar 路径与 Java 可用，或查看后端自己的日志。"}',
    ]
    const tones = fromRust.map((j) => describeState(JSON.parse(j) as BackendState).tone)
    expect(tones).toEqual(['loading', 'ok', 'error'])
  })

  it('Rust 的 ready 里的端口能读出来（不是 undefined）', () => {
    // 单独再钉一次字段名与类型：如果 Rust 那边把 `port` 改名成 `backendPort`
    // 或加进 `#[serde(skip_serializing_if)]`，上面那条只断言 tone 的测试
    // 依然会绿（tone 只由 status 决定），而这里会红。
    const s = JSON.parse(
      '{"status":"ready","port":8080,"message":"后端已连接 · 端口 8080"}',
    ) as BackendState
    expect(s.port).toBe(8080)
    expect(s.message).toBe('后端已连接 · 端口 8080')
    expect(describeState(s).text).toBe('后端已连接 · 端口 8080')
  })
})

describe('checkHealth', () => {
  beforeEach(() => {
    invokeMock.mockReset()
  })
  afterEach(() => {
    invokeMock.mockReset()
    vi.restoreAllMocks()
  })

  it('调用 get_health 命令（不再自己发 HTTP）', async () => {
    invokeMock.mockResolvedValue({ status: 'up', cache: '缓存：未启用（配置为关闭）' })

    const r = await checkHealth()

    expect(r).toEqual({ status: 'up', cache: '缓存：未启用（配置为关闭）' })
    // 命令名是**跨语言契约**：Rust 侧 `lib.rs` 的 `generate_handler![]` 里
    // 少注册 `get_health`，前端这里就会 reject（Tauri 报 command not found）。
    // 变异测试里「lib.rs 里不注册 get_health」就是靠这一条（以及组件那条）抓的。
    expect(invokeMock).toHaveBeenCalledWith('get_health')
  })

  it('不再传端口——端口是外壳的事，前端不该经手', async () => {
    // 这条是这次改动**语义上的重点**：旧接口是 `checkHealth(port)`，
    // 端口从 `get_backend_status` 流到前端、再由前端拼进 URL。
    // 现在端口留在 Rust 侧（`get_health` 自己从 state 里取），
    // 所以这个函数**一个参数都不收**。把签名改回去会让这条红。
    invokeMock.mockResolvedValue({ status: 'up', cache: 'x' })
    expect(checkHealth).toHaveLength(0)
    await checkHealth()
    expect(invokeMock).toHaveBeenCalledTimes(1)
    // 任何参数都不该出现在调用里
    expect(invokeMock.mock.calls[0]).toEqual(['get_health'])
  })

  it('invoke reject 时抛错（对应旧的「HTTP 非 2xx」那条）', async () => {
    // **保留旧断言的语义**：以前 500 会让 `fetch` 那条路径抛
    // `Error("后端返回 500")`；现在非 2xx 由 Rust 侧判掉并变成 invoke 的 reject，
    // 前端这条路径的职责只剩下「原样把错误抛给调用方」。
    // 所以这里断言的是「reject 会穿透」，而不是某个具体文案。
    invokeMock.mockRejectedValue(new Error('后端健康检查返回了 HTTP 500（期望 200）'))
    await expect(checkHealth()).rejects.toThrow('500')
  })

  it('通信失败时的原因原样穿透（状态栏要把它显示出来）', async () => {
    // 旧的「网络失败时抛错（端口不对或后端还没起）」那条的等价物：
    // 现在是外壳报回来的话——「连不上」「超时」都是它给的。
    invokeMock.mockRejectedValue(new Error('连不上后端 127.0.0.1:51234：拒绝连接'))
    await expect(checkHealth()).rejects.toThrow('连不上后端')
  })

  it('Rust 真的序列化出来的 Health JSON 字段名，前端能读出来', async () => {
    // **跨语言契约**（与 describeState 那两条同一个套路）。
    //
    // 下面这行 JSON 是 Rust 侧**实测**拿到的，原样复制**一个字符都没改**：
    // 来自 `src-tauri/src/http.rs` 的
    // `the_health_struct_serializes_with_the_field_names_the_frontend_reads`
    // （`serde_json::to_string(&Health{...})` 的输出），以及真机 e2e
    // `end_to_end_reads_a_real_health_response_through_the_shell_http_path`
    // 里 `GET /api/health` 打印出来的 `cache` 值。
    //
    // 这条专门抓**跨语言字段名漂移**：Rust 那边把 `cache` 改成 `cach`、
    // 或给它加上 `#[serde(skip_serializing_if)]`，则 JSON 里就没有 `cache` 键，
    // 而 `as Health` 是个**类型断言、不做任何校验**——不报错，直接是 undefined。
    // 状态栏右侧于是渲染成空白（`v-if="cacheText"` 为假），没有任何提示。
    const fromRust = '{"status":"up","cache":"缓存：未启用（配置为关闭）"}'
    invokeMock.mockResolvedValue(JSON.parse(fromRust))

    const h = await checkHealth()

    expect(h.status).toBe('up')
    expect(h.cache).toBe('缓存：未启用（配置为关闭）')
    // 逐键校验：漏一个键、或键名漂了，这里就红
    expect(Object.keys(h).sort()).toEqual(['cache', 'status'])
  })

  it('字段名漂移（cache → cach）时不会静默当成空文案', async () => {
    // 反面：真的漂了的时候，前端**读不到**（`undefined`）。
    // 这条不是在夸现有的实现——它是在把「漂移的后果」钉下来：
    // 后果是 undefined，而不是一个看起来正常的空串。
    // **Rust 侧那一条测试（rejects_a_misspelled_cache_field）是真正的守卫**，
    // 它在 JSON 解析那一步就报错；这里只是证明「万一漏过去，前端不会自作多情」。
    invokeMock.mockResolvedValue({ status: 'up', cach: '缓存：未启用（配置为关闭）' })
    const h = await checkHealth()
    expect(h.cache).toBeUndefined()
  })
})
```

#### `frontend/src/components/BackendStatusBar.spec.ts`（改动）

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BackendStatusBar from './BackendStatusBar.vue'

// invoke 是唯一的跨进程边界：**两条命令都走它**
// （`get_backend_status` 拿外壳状态、`get_health` 拿缓存文案）。
// 所以 mock 必须按**命令名**分派——一个笼统的 mockResolvedValue 会让两条命令
// 拿到同一个返回值，测试就再也分不清是「哪条命令被调了」。
//
// 这个文件此前 stub 的是 `fetch`（health 由前端自己发 HTTP）。
// 真机验收发现那条路径被 CORS 拦掉（页面的源与后端随机端口不同源），
// 于是 health 改由外壳代发，这里跟着改成 mock invoke。
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

const STARTING = { status: 'starting', port: null, message: '正在启动后端…' }
const READY = { status: 'ready', port: 51234, message: '后端已连接 · 端口 51234' }
const FAILED = { status: 'failed', port: null, message: '后端启动超时' }

/** 默认的 health 应答：正常响应。 */
function healthOk(cache = '缓存：未启用（配置为关闭）') {
  return { status: 'up', cache }
}

/**
 * 按命令名分派 invoke。
 *
 * `shell` 是 `get_backend_status` 的返回值序列（用完一直返回最后一个），
 * `health` 省略时**默认让 `get_health` 失败**——这不是偷懒，是刻意的：
 * 这个组件在**没就绪**时一次都不该调用 `get_health`，所以对绝大多数用例来说
 * 「被调到」本身就是缺陷。默认 reject 让它以「界面出现一句错误」的形式
 * 暴露出来，而不是被一个喂饱了的 mock 悄悄掩盖。
 */
function dispatch(
  shell: unknown[],
  health: unknown | (() => unknown) = new Error('测试没准备 get_health 的应答（说明它被调用了）'),
) {
  const shellCalls: unknown[] = []
  invokeMock.mockReset()
  invokeMock.mockImplementation(async (cmd: unknown) => {
    if (cmd === 'get_backend_status') {
      const i = Math.min(shellCalls.length, shell.length - 1)
      shellCalls.push(shell[i])
      return shell[i]
    }
    if (cmd === 'get_health') {
      const v = typeof health === 'function' ? (health as () => unknown)() : health
      // reject 用 Error 表达：invoke 失败时前端拿到的就是它
      if (v instanceof Error) throw v
      return v
    }
    throw new Error(`测试没有为命令 ${String(cmd)} 准备应答`)
  })
  return shellCalls
}

/** 只数某一条命令被调了几次。 */
function callCount(cmd: string) {
  return invokeMock.mock.calls.filter((c) => c[0] === cmd).length
}

/** 推进 fake 时间并把这期间排队的 promise 全部冲干净。 */
async function advance(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
  await flushPromises()
}

describe('后端状态栏', () => {
  beforeEach(() => {
    // 必须先装 fake timers 再 mount：mount 里就会调一次 tick 并注册 interval。
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    invokeMock.mockReset()
  })

  it('初始是加载态', async () => {
    dispatch([STARTING])
    const w = mount(BackendStatusBar)
    await flushPromises()

    expect(w.text()).toContain('正在启动后端…')
    expect(w.find('.status').classes()).toContain('loading')
  })

  it('拿到端口后变就绪，并带上端口号', async () => {
    dispatch([STARTING, READY])
    const w = mount(BackendStatusBar)
    await flushPromises()
    expect(w.find('.status').classes()).toContain('loading')

    await advance(500)

    expect(w.text()).toContain('51234')
    expect(w.find('.status').classes()).toContain('ok')
  })

  it('显示 get_health 返回的缓存文案', async () => {
    dispatch([READY], healthOk('缓存：未启用（配置为关闭）'))
    const w = mount(BackendStatusBar)
    await advance(0)

    expect(w.text()).toContain('缓存：未启用（配置为关闭）')
  })

  it('health 失败时不崩，并说明缓存状态取不到', async () => {
    // 旧版这里 stub 的是 `fetch` 的 reject；现在等价物是 `get_health` 的 reject
    // （Rust 侧把 CORS 那条死路换成了「连不上 / 超时 / 非 2xx / JSON 畸形」四句话）。
    dispatch([READY], new Error('连不上后端 127.0.0.1:51234：拒绝连接'))
    const w = mount(BackendStatusBar)
    await advance(0)

    // 外壳那条路径仍然正常显示，窗口没被拖垮
    expect(w.text()).toContain('51234')
    expect(w.text()).toContain('缓存状态取不到')
    expect(w.text()).toContain('连不上后端')
  })

  it('与外壳通信失败时显示失败态与原因', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async (cmd: unknown) => {
      if (cmd === 'get_backend_status') throw new Error('外壳没响应')
      return healthOk()
    })
    const w = mount(BackendStatusBar)
    await advance(0)

    expect(w.find('.status').classes()).toContain('error')
    expect(w.text()).toContain('外壳没响应')
  })

  it('启动期不去问 health（还没就绪，端口还没有意义）', async () => {
    // 守卫曾经写的是 `state.value.port === null`——改成 invoke 之后端口不再由
    // 前端关心，守卫改成看 status。
    //
    // **这条用例到底守住了什么（变异实测后的诚实结论）**：
    // 它**不是** `pollHealth` 里那行 `if (state.value.status !== 'ready') return`
    // 的杀手。那行今天删掉照样全绿——因为 `pollHealth` 的两个调用点都在
    // `onReady` 之后，启动期根本走不到那里，也就是说那行守卫**不可达**。
    // （这一点被专门验证过：把守卫替换成注释后单跑本文件，10 passed / 0 failed；
    //  同时用一份临时 spec 打点确认启动期只有 `get_backend_status` 被调用。）
    //
    // 它真正守的是**调用点的位置**：`pollHealth` 不能落进启动期。谁把它挪进
    // `tick()`（那是个很自然的想法），这条就会红——`get_health` 的调用次数
    // 会从 0 变成 1 以上，而且状态栏右侧会从「后端启动需要几秒，请稍候」
    // 变成「缓存状态取不到：…」。
    //
    // 断言因此分两半：接口上的调用次数（机制）与界面文案（后果）。
    // 只断言前者的话，「样式/文案那侧坏了」不会被发现；只断言后者的话，
    // 一个把 `get_health` 调了却把结果丢掉、只在 catch 里写了别的文案的实现
    // 也能蒙混过去。
    dispatch([STARTING])
    const w = mount(BackendStatusBar)
    await advance(5000)

    expect(callCount('get_health')).toBe(0)
    expect(callCount('get_backend_status')).toBeGreaterThan(1)
    expect(w.text()).toContain('后端启动需要几秒，请稍候')
    expect(w.text()).not.toContain('缓存状态取不到')
    expect(w.text()).not.toContain('缓存：')
  })

  it('就绪之后不再问外壳状态（它只会一直是 ready）', async () => {
    // 每 500ms 问一次外壳状态毫无意义：后端的状态是单向的
    // （Rust 侧 a_ready_state_is_never_overwritten_by_a_later_failure 守着）。
    dispatch([STARTING, READY])
    const w = mount(BackendStatusBar)
    await flushPromises() // 第一问：starting
    await advance(500)    // 第二问：ready → 停掉外壳轮询
    expect(w.find('.status').classes()).toContain('ok')
    const shellAtReady = callCount('get_backend_status')

    await advance(60_000) // 稳态跑一分钟

    expect(callCount('get_backend_status')).toBe(shellAtReady)
    expect(w.find('.status').classes()).toContain('ok')
  })

  it('就绪之后 health 按 5 秒节奏刷新，而不是 500ms', async () => {
    // 反面断言：如果 health 跟着 500ms 的节拍走，60 秒里会打 120 次。
    dispatch([READY])
    mount(BackendStatusBar)
    await advance(0)

    await advance(60_000)

    // 就绪那一刻 1 次 + 60 秒 / 5 秒 = 12 次，共 13 次；留一点余量容忍边界。
    const n = callCount('get_health')
    expect(n).toBeLessThanOrEqual(14)
    expect(n).toBeGreaterThanOrEqual(12)
  })

  it('失败之后也不再问外壳状态（失败同样是单向的）', async () => {
    // 变异测试发现这条缺口：`tick` 里那句 stopShellPolling 在 ready 路径上
    // 被 onReady() 内部的停表**掩盖**了，只有 failed 路径能证明它真的在起作用。
    // 外壳的失败状态同样是终态——Rust 侧没有任何按时间重试的机制，
    // 再问一万次也只会得到同一个 message。
    dispatch([STARTING, FAILED])
    const w = mount(BackendStatusBar)
    await flushPromises() // 第一问：starting
    await advance(500)    // 第二问：failed
    expect(w.find('.status').classes()).toContain('error')
    expect(callCount('get_backend_status')).toBe(2)

    await advance(60_000)

    expect(callCount('get_backend_status')).toBe(2)
    // 失败态没有就绪，health 一次都不该打
    expect(callCount('get_health')).toBe(0)
  })

  it('卸载后不再轮询', async () => {
    dispatch([STARTING]) // 一直停在启动态：不做这条断言的话轮询会自停，测试就失去意义
    const w = mount(BackendStatusBar)
    await advance(1000)
    const before = invokeMock.mock.calls.length
    // 这条前置断言很重要：若轮询已经自己停了，后面的「不再增加」就是恒真的。
    expect(before).toBeGreaterThan(1)

    w.unmount()
    await advance(10_000)

    expect(invokeMock.mock.calls.length).toBe(before)
  })
})
```

### 完成标准（本任务）

- [x] `get_health` 由外壳代发，前端一个 `fetch` 都没有了（`grep` 实测 0 处调用）
- [x] `Cargo.toml` / `Cargo.lock` 未变（零新依赖，SHA256 相同）
- [x] `cargo test` 全绿：**76 passed + 4 integration passed；2 ignored**（`--ignored` 单独跑：2 passed）
- [x] `pnpm test` 全绿：**Tests 50 passed（Test Files 8 passed）**
- [x] `pnpm tauri dev` 真机复测：状态栏右侧是 **`缓存：未启用（配置为关闭）`**，不再是 `缓存状态取不到`
- [x] CSP 收紧成 `connect-src 'self'`，且 HMR 实测仍然可用（0.50 秒）
- [x] 变异表逐条实测，存活的那一条如实记录
- [x] 计划 fence 与磁盘由脚本比对一致
- [x] `git status --short` 无输出

---

## Task 7: 端到端手工验收与文档

自动化测试覆盖了解析逻辑与组件，但**「双击之后到底看见了什么」只能人看**。这一步把它记下来。

**Files:**
- Modify: `docs/superpowers/plans/2026-10-14-change-phase1-overview.md`

- [x] **Step 1: 准备一个能跑的后端 jar**

Run:
```powershell
cd E:\idea\program\change\backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -q -DskipTests package
```

Expected: `backend/target/change-backend-0.1.0-SNAPSHOT.jar` 存在（约 79 MB）。

- [x] **Step 2: 端到端跑一次，逐条核对下面这个清单**

Run:
```powershell
cd E:\idea\program\change\frontend
pnpm tauri dev
```

**逐条核对并记录实际观察**：

| # | 要观察的 | 期望 |
|---|---|---|
| 1 | 窗口尺寸与底色 | 1280×820，深色 `#0d0f12` |
| 2 | **有没有白屏闪烁** | 没有。若闪了，记下是在哪一阶段闪的（窗口出现时 / Vue 挂载前 / 内容加载时），这三段对应不同的修法 |
| 3 | 加载提示 | 底部状态栏显示「正在启动后端…」，圆点在脉冲 |
| 4 | 从启动到「后端已连接」的秒数 | 约 4–7 秒（取决于 Redis 探测）。**记下实际值** |
| 5 | 状态栏最终文案 | 「后端已连接 · 端口 <数字>」+ 右侧「缓存：未启用（配置为关闭）」 |
| 6 | 终端里能看到后端日志 | 有，形如 `2026-... INFO ... Started ChangeApplication in ... seconds` 与 `CHANGE_PORT=...` |
| 7 | 点侧边栏 9 个入口 | 都能切页，地址栏路径跟着变，选中项变强调色 |
| 8 | 「人体图」项 | 带「预留」角标 |
| 9 | 「我的」分组 | 贴底、上方有分隔线 |
| 10 | **关掉窗口之后** | 终端里 Java 进程随之结束（用任务管理器或 `Get-Process java` 确认**没有孤儿进程**） |

**如果第 2 条闪白或第 10 条留了孤儿进程——那是真缺陷，停下来报告，不要绕过。**

> **10 条的实测值记在总览里**：`docs/superpowers/plans/2026-10-14-change-phase1-overview.md`
> 的「计划 1 与计划 2 之间：Tauri 外壳与 Vue 前端脚手架」一节（含「怎么测的」：起点、终点、
> 抓帧方式与数值）。其中**第 5 条当时不满足**——右侧缓存位实测显示的是
> `缓存状态取不到：TypeError: Failed to fetch`，不是「缓存：未启用（配置为关闭）」。
> 根因（页面源与后端随机端口不同源 + 响应缺 `Access-Control-Allow-Origin`）与对照实验
> 记在那一节末尾的「验收记录」里；**修法是 Task 8**（健康检查改由外壳代发），
> 修完之后的真机复测记在 Task 8 一节，第 4 条随之勾上。

- [x] **Step 3: 记一次「后端起不来」的失败形态**

故意破坏一下，确认失败路径给出的是**能读懂的话**而不是无限等待：

```powershell
cd E:\idea\program\change\frontend
# 把 jar 指到一个不存在的路径
$env:CHANGE_BACKEND_JAR = 'E:\does\not\exist.jar'
pnpm tauri dev
```

Expected: 状态栏显示「找不到后端 jar。请先在 backend/ 下执行…」这类可读文案，**不是**永远停在「正在启动后端…」。

记下实际文案。然后 `Remove-Item Env:\CHANGE_BACKEND_JAR` 清掉。

- [x] **Step 4: 把验收结果记进总览**

在 `docs/superpowers/plans/2026-10-14-change-phase1-overview.md` 的「计划清单与依赖」表**之后**插入一节：

```markdown
## 计划 1 与计划 2 之间：Tauri 外壳与 Vue 前端脚手架

> 完整计划：`docs/superpowers/plans/2026-10-16-change-frontend-shell.md`
> 状态：**已完成**（<填写实际完成日期>）

**为什么插这一件**：计划 1 的最终审查发现，7 份计划里**没有任何一份负责把外壳与前端建起来**——「外壳 / 前端 / Vue / Tauri」在设计文档里出现 34 次，但在 7 份计划的任何一行里都是 0 次。而计划 3 默认它们已经存在。

**做完之后有了什么**：

- `frontend/`（Vue 3 + TS + Vite + Pinia + Router）与 `frontend/src-tauri/`（Rust 外壳）
- 外壳拉起后端 jar、读 stdout 的 `CHANGE_PORT=`、把端口交给前端
- 深色主题 token 在 `theme.css` 一处定义，由测试钉住设计文档冻结的值
- 侧边栏四个分组 + 9 个占位页，布局与设计文档一致

**端到端实测**（<填写实际日期>）：

| 项 | 实测 |
|---|---|
| 窗口 | 1280×820，深色，**不闪白** |
| 从启动到「后端已连接」 | <填写实际秒数> 秒 |
| 状态栏 | <填写实际文案> |
| 关窗后残留 java 进程 | <有 / 无> |
| jar 找不到时的提示 | <填写实际文案> |

**留下的已知项**：<如实填写；没有就写「无」>
```

**把上面每一个 `<填写…>` 都换成你实际观察到的东西**——不要照抄期望值。

- [x] **Step 5: 提交**

```bash
git add docs/superpowers/plans/2026-10-14-change-phase1-overview.md
git commit -m "docs(plan): 记录外壳脚手架完成与端到端实测结果

7 份计划里原本没有一份负责建外壳与前端（计划 1 最终审查的发现），
这一节把补上的这一份记进总览，并留下真实的端到端观察值。"
```

---

## 完成标准

本计划结束时必须全部满足：

- [x] `cd frontend && pnpm test` 全绿，**报告实际条数**（Task 6 结束时 46 条；**Task 8 之后 50 条**：`backend.spec.ts` 9→12、`BackendStatusBar.spec.ts` 9→10）
- [x] `cd frontend/src-tauri && cargo test` 全绿，**报告实际条数**（2026-10-16 Task 7 验收实测 **32 passed; 0 failed; 1 ignored**；Task 4 落地时的 18 passed 是其中的一部分，另外 14 条由 Task 5 补上，那 1 条 `#[ignore]` 的真实后端 e2e 用 `cargo test -- --ignored` 单独跑。**Task 8 之后是 `76 passed + 4 integration passed；2 ignored`**——新增 `http.rs` 的纯函数测试、真 socket 的集成测试，以及一条新的 `#[ignore]` e2e）
- [x] `pnpm tauri dev` 能起出一个深色窗口，**不闪白**
- [x] 窗口里能看到「后端已连接 · 端口 <数字>」与缓存状态（**2026-10-16 Task 8 修完并复测**：右侧从 `缓存状态取不到：TypeError: Failed to fetch` 变成 `缓存：未启用（配置为关闭）`，证据见 Task 8 的 Step 6）
- [x] 关窗后**没有残留的 java 进程**
- [x] 把 jar 指到不存在的路径时，状态栏给出**能读懂的原因**，而不是无限等
- [x] `theme.css` 里的 token 与设计文档「九、界面与视觉方向」逐值一致（由 `theme.spec.ts` 守住）
- [x] 侧边栏四个分组、9 个入口、人体图的「预留」角标、「我的」贴底带分隔线——与设计文档一致
- [x] `git status --short` 无输出

> **验收记录（2026-10-16，Task 7 端到端实测）**：9 条里 **8 条勾上，第 4 条没勾**。
>
> 第 4 条的前半段成立——状态栏确实显示 `后端已连接 · 端口 61998`；后半段不成立：
> 右侧缓存位显示的是 **`缓存状态取不到：TypeError: Failed to fetch`**，
> 不是设计里的「缓存：未启用（配置为关闭）」。
>
> 根因已用对照实验钉死：后端 `/api/health` 本身是好的（直接请求返回 `200` +
> `{"cache":"缓存：未启用（配置为关闭）","status":"up"}`），但它**没有
> `Access-Control-Allow-Origin` 头**；而页面源（dev 是 `http://127.0.0.1:1420`，
> 打包后是 `http://tauri.localhost`）与后端随机端口**不同源**，浏览器于是把响应拦掉。
> 用同一个假后端跑两次、只切换有没有 ACAO 头：加了就显示「缓存：未启用（配置为关闭）」，
> 不加就复现 `TypeError: Failed to fetch`。所以是 CORS，不是 CSP。
>
> 46 条前端测试**全部 mock 掉了 `fetch`**，这条路径第一次真机执行就是它的第一次执行。
>
> **已由 Task 8 修复（2026-10-16）**：修法是**不给后端加 CORS**，改由外壳代发这条
> 请求（不经浏览器，天然不受同源策略约束），并把 CSP 的 `connect-src` 收紧回 `'self'`。
> 修完后的真机复测：状态栏右侧是 `缓存：未启用（配置为关闭）`。
> 缺陷、根因、修法、变异结果与实测数字全部记在 Task 8 一节。

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| Tauri + Vite + pnpm 的组合没在本机验过 | `113db89` 只验了**裸 Tauri**，没验 Vue/Vite 集成 | Task 1 就是这一步；起不来就停下来报告，不要靠猜改配置 |
| `pnpm install` 依赖解析 | 各包的 `^` 范围可能解析到不兼容的组合 | Task 1 Step 12 后若 `pnpm tauri dev` 报版本问题，**报告实际报错**再定 |
| 首次 `cargo build` 慢 | 新 crate 要编译 | cargo 缓存已有 321MB；实测裸 Tauri 1.9 分钟，这里只会多一点 |
| 窗口闪白 | 设计文档明确要求避免 | 三层防护：Tauri `backgroundColor`、HTML 内联底色、主题 token；Task 7 Step 2 的第 2 条专门验它 |
| 孤儿 java 进程 | 外壳崩了后端还活着，用户下次启动会撞端口/文件锁 | `RunEvent::Exit` 里 kill；Task 7 Step 2 第 10 条专门验它 |
| **关窗是硬杀，不是优雅关闭** | 设计文档写的是「先通知后台把数据落盘，再退出」，那需要后端加关闭端点，而**后端现在没有** | 登记在案：SQLite 在 WAL 下崩溃安全，强杀不会损坏库；真正的优雅关闭应该和「数据落盘时机」一起在计划 7 之前解决（见 Task 5 里 `shutdown()` 的注释） |
| 前端硬编码端口 | 端口是随机分配的，硬编码必然失效 | 端口只经 `get_backend_status` 出去；约定里写明「不许硬编码」 |
| CSP 写太松 | 这是要分发给他人的客户端 | 不用 `"csp": null`。Task 8 之后 `connect-src` 收紧成 `'self'`：前端不再自己发任何 HTTP，那条 `http://127.0.0.1:*` 通配**已删掉**（HMR 实测不受影响） |
| **把「CSP 放行了」当成「请求就能成」** | Task 7 真机上就是栽在这里：CSP 与 CORS 是**两道独立且都要过的门**，只过了第一道，前端于是只看到 `TypeError: Failed to fetch` | 真机端到端验收**必须真的读一次界面上的字**，不能只看 mock 过的单测全绿（Task 8 就是为这件事而存在） |

## 非目标（本计划不做）

- **不打包**（自带 JRE、NSIS 安装包、首次启动向导）——那是计划 7
- **不做任何业务界面**——9 个页面都是占位，真实内容在计划 3/4/5/6
- **不做系统托盘、开机自启**——设计文档提到了，但属于打包阶段
- **不接智能体、不接模型接口**
- **不做浅色主题**（设计文档已封板）

## 下一步

本计划完成后进入**计划 2：动作库导入与肌群映射**。它开工前有四件事必须先定（见 `docs/2026-10-16-第一阶段交接报告.md` 第八节），其中最要紧的一条是 **`exercise_muscle` 的审计方案**——计划 2 正是批量写那张表的那一份，而当前 `undo` 回放不了 join 行。
