# frontend/ — changE 的 Tauri 外壳与 Vue 前端

Tauri 2 外壳（Rust，`src-tauri/`）+ Vue 3 SPA（TypeScript + Vite，`src/`）。
后端在平级的 `backend/`，两边是独立的构建。

完整规格见 `docs/superpowers/plans/2026-10-16-change-frontend-shell.md`。

---

## 前置

| 需要 | 版本 | 说明 |
|---|---|---|
| Node.js | 18+ | Vite 6 的要求 |
| **pnpm** | **11+** | 本目录只支持 pnpm。**不要用 npm / npx**——`npx` 在本机报 `EE certificate key too weak` |
| Java | 21+ | 只在跑后端 jar 时需要（Task 5 起） |
| Rust | 1.77+ | 只在 `pnpm tauri dev` / `pnpm tauri build` 时需要 |

pnpm 在 `E:\npm-global\pnpm.ps1`。不在 PATH 时用绝对路径：
`& 'E:\npm-global\pnpm.ps1' install`。

---

## 装依赖

```powershell
cd frontend
pnpm install
```

### 关于 `pnpm-workspace.yaml`（**不要删它**）

`pnpm-workspace.yaml` 里只有一段 `allowBuilds`，它**不是样板文件**：这是**本仓库唯一一处
「决定不执行第三方构建脚本」的地方**，每个包的 `false` 都有单独写下的判断依据（见文件内注释）。

背景：pnpm 11 默认不执行依赖的 `postinstall` 等构建脚本，并要求对每一个有构建脚本的包
显式表态。

- 未表态 → `pnpm install` 以 `ERR_PNPM_IGNORED_BUILDS` 退出 1，**而且整个 npm script 面
  跟着死掉**——`pnpm run` / `pnpm exec` 会先跑依赖检查再退出 1，连
  `pnpm exec tauri --version` 都进不去。这不是「某个包被跳过」，是整个 pnpm 用不了。
- `false` = 知道它要跑，**且决定不跑**；`true` = 知道，且允许。

**遇到 `ERR_PNPM_IGNORED_BUILDS` 时怎么判断**（按顺序问三个问题）：

1. **这个脚本到底干什么？** 去 `node_modules/<包>/package.json` 的 `scripts` 里看
   `postinstall` / `install` / `preinstall`。说不清就先按 `false`。
2. **不跑它的后果是什么？** 如果只是「下载一个平台二进制」，先去看那个二进制是不是通过
   `optionalDependencies` 装的——如果是，它**不经过** `postinstall`，`false` 没有影响。
   （`esbuild` 就属于这种：`@esbuild/win32-x64` 走 optionalDependencies。）
3. **跑它的收益是否存在？** 有些脚本在本项目的配置下是 no-op。例如 `vue-demi` 的
   `postinstall` 只在 Vue 2 / Vue 3 之间切模式，而本项目是 Vue 3、出厂默认就是
   `lib/index.vue3.mjs` 那条路径——跑了等于没跑。

三个问题都答完还**确实需要**它跑，才把那一行改成 `true` 并重跑 `pnpm install`。

> 默认立场是「不执行第三方 postinstall 代码」：装依赖不该顺带运行别人写的任意代码。
> 想要这个立场就必须填 `false` 而不是留空——**占位值既不是 true 也不是 false，会让 pnpm 直接拒绝工作。**

### 首次装完之后：生成图标

图标是**离线生成一次、然后入库**的产物，不是构建期步骤。所以必须在开发前把它生成出来：

```powershell
cd frontend
python scripts\make-icon.py                  # 产出 scripts/icon-source.png
pnpm tauri icon scripts/icon-source.png      # 铺开成各平台尺寸（写进 src-tauri/icons/）
```

`scripts/make-icon.py` 要 Pillow（`pip install Pillow`）。它是**当前唯一需要 Pillow 的地方**，
所以刻意**没有**把它写进 `package.json`——它不参与每次构建。

`pnpm tauri icon` 会顺带生成 `src-tauri/icons/android/` 与 `ios/`。**本项目是 desktop-only**
（`tauri.conf.json` 的 `targets: ["nsis"]`），这两个目录用不上、也不入库：

```powershell
Remove-Item -Recurse -Force src-tauri\icons\android, src-tauri\icons\ios
```

（`tauri.conf.json` 的 `bundle.icon` 只引用 `icons/icon.ico`，删掉不影响 `pnpm tauri dev`
与 `pnpm build`。）

### 重跑图标后可能看到的 `icon.icns` 差异（**不是你做错了**）

`scripts/icon-source.png` 是确定的：同一个 Python 解释器下跑两次 `make-icon.py`，
输出逐字节相同（实测 sha256 一致）。`tauri icon` 铺出来的 19 个文件里 **18 个也逐字节可复现**，
但 **`icon.icns` 不是**：

- 它的**像素是确定的**（1024×1024，每次完全一样）；
- 但 **ICNS 容器的字节每次运行都不同**——实测连续跑两次 `pnpm tauri icon`，
  两次的 `icon.icns` sha256 就不一样，120921 字节里有约 109000 字节不同。

所以重跑图标之后 `git status` 里冒出 `M icons/icon.icns` 是**预期现象**。想保持工作区干净：

```powershell
git checkout -- src-tauri/icons/icon.icns
```

**这个差异对本项目没有实际影响**：`.icns` 只在 macOS 上被用到，而本项目的打包目标是
Windows NSIS（`targets: ["nsis"]`），`bundle.icon` 里也没有引用它。

> 如果哪天真的要做 macOS 包，就需要给 `.icns` 找一个确定的生成方式（或接受
> 「每次重建都会产生一次无意义的二进制 diff」）。现在把它记在这里，免得下一个人
> 花半天去查一个不存在的错误。

---

## 跑测试

```powershell
cd frontend
pnpm test          # vitest run，跑一次
pnpm test:watch    # vitest，监听
```

> ⚠️ **现在 `pnpm test` 退出码是 1**，输出 `No test files found, exiting with code 1`。
> 这是 vitest 的既定行为，不是配置坏了——Task 1 阶段前端确实**一个测试文件都没有**。
> Task 2 建 `src/styles/theme.spec.ts` 之后它会变成正常的 0（全绿）。
> 在那之前，CI/脚本里**不要**把 `pnpm test` 的退出码当作可用信号。

---

## 开发

```powershell
cd frontend
pnpm tauri dev
```

Tauri 会自己执行 `pnpm dev` 起 Vite（`beforeDevCommand`），然后打开窗口。

### 端口 1420 被占时会**响亮失败**，这是有意的

`vite.config.ts` 里是 `strictPort: true`，所以端口被占时 Vite **直接退出**而不是静默换到
1421。原因是 `tauri.conf.json` 的 `devUrl` 写死了 `http://127.0.0.1:1420`——静默换端口
会让外壳连到一个不存在的地址，表现成「窗口一片空白」，比直接报错难查得多。

先找出占用者再决定怎么处理：

```powershell
Get-NetTCPConnection -LocalPort 1420 -State Listen |
  Select-Object LocalAddress, LocalPort, OwningProcess
```

---

## 构建前端

```powershell
cd frontend
pnpm build
```

`pnpm build` = `vue-tsc --noEmit && vite build`，产出 `frontend/dist/`。
这就是 `tauri.conf.json` 的 `beforeBuildCommand`——**第一次 `pnpm tauri build` 跑的就是它**，
所以它在提交前必须退出 0。不需要后端。

> 这里**不再**用 TypeScript 的 project references（原先的 `tsconfig.node.json` 已删除）：
> 被引用的 composite 项目不允许 `noEmit`，而本项目的 config 全部是 no-emit 的纯类型检查，
> 于是 `vue-tsc` 报 `TS6310` 直接让 `pnpm build` 退出 2。现在只有**一个** `tsconfig.json`，
> 同时包含 `src/` 与 `vite.config.ts` / `vitest.config.ts`。

## 打包（**Task 5 起才可用**）

```powershell
cd frontend
pnpm tauri build
```

外壳要拉起后端 jar，所以**先**把后端打出来：

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -q -DskipTests package     # → backend/target/change-backend-0.1.0-SNAPSHOT.jar
```

外壳按 `CHANGE_BACKEND_JAR` → 相对路径 `../../backend/target/...` 的顺序找 jar。
找不到时不会静默等待，而是把「找不到后端 jar。请先在 backend/ 下执行…」显示在状态栏里。

---

## 目录速览

```
frontend/
├── index.html            ← 含防白屏的内联底色
├── package.json
├── pnpm-workspace.yaml   ← ★ 唯一的「不执行第三方构建脚本」声明处
├── tsconfig.json         ← 唯一一份，no-emit 纯检查
├── vite.config.ts        ← strictPort: 1420
├── vitest.config.ts      ← Task 3 建
├── scripts/
│   ├── make-icon.py      ← ★ 图标的来源（可复现）
│   └── icon-source.png   ← ★ 1024×1024 源图，入库
├── src/                  ← Vue 3 SPA
└── src-tauri/            ← Rust 外壳
    └── icons/            ← 由上面两条命令生成，desktop 尺寸入库
```
