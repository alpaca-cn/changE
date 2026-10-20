# 计划 7 · 打包与分发

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 产出一个能发给朋友的 Windows 安装包。双击安装 → 启动 → 能记训练、能看人体图。**朋友的电脑上不需要装 JDK**。

**Architecture:** 三层打包：`jlink` 出一个只含必需模块的精简 JRE（实测 48.8 MB）→ 与 Spring Boot fat jar（79.7 MB）一起作为 **Tauri resources** 打进 NSIS 安装包 → 外壳启动时**优先用随包资源**，找不到才回退到开发路径。窗口**立刻显示**并给加载提示（首次启动要 12 秒，因为要导入 876 条动作）。

**Tech Stack:** Tauri 2 · Rust · `jlink`（JDK 25）· NSIS

---

## 这一份要解决什么

设计文档 `### 分发形态：单用户，但可分发` 点出了四个「会在打包那一步集中爆发」的问题。**挨个核实过，其中一个已经解决、一个现在没有内容**：

| 设计文档的要求 | 现状 |
|---|---|
| **必须自带 Java 运行时** | ❌ **要做**。现在 `resolve_java` 靠 `CHANGE_JAVA` / `JAVA_HOME` / `PATH`——朋友的机器上没有 |
| **不得把模型 API Key 打进安装包** | ⏸ **现在没有可打的东西**。计划 5（智能体）还没做，代码里**没有任何** LLM / API key 配置 |
| **数据目录不能写死** | ✅ **已经解决**。`application.yml` 的 `change.data-dir` 默认就是 `${APPDATA}/changE`，`DataDirectoryInitializer` 还有防「落到盘根」的守卫 |
| **WebView2 运行时** | ⚠️ 用 Tauri 默认的 `downloadBootstrapper`（装的时候下，~1.8 MB）。Win10/11 基本都自带 Edge |

**所以本计划真正要做的是：JRE + jar 随包、外壳优先用随包资源、加载提示、NSIS 安装包、体积核对。**

### 一个已经做过的决定性实验

**48.8 MB 的精简 JRE 真的跑起来了**（2026-10-20 实测，不是推测）：

```
jlink --add-modules java.base,java.sql,java.naming,java.desktop,java.management,
       java.instrument,java.security.jgss,java.xml,jdk.unsupported,jdk.crypto.ec,
       java.net.http,jdk.management,java.logging,java.transaction.xa,java.compiler
       --strip-debug --no-header-files --no-man-pages --compress=zip-6
→ 48.8 MB / 5.6 秒

用那个 JRE 跑 change-backend 的 fat jar（清掉 JAVA_HOME，确保真在用精简 JRE）：
→ Started ChangeApplication in 6.832 seconds
→ 动作库导入 876 / 中文名 876 / 中文做法 871 / 食物 35
→ CHANGE_PORT=55317
→ GET /api/health → 200 {"status":"up",...}
→ 库建出来 3.26 MB / 17 张表 / 876 条动作
```

**这一条是本计划风险的绝大部分**：体积对了不代表能跑，少一个模块就是启动时 `NoClassDefFoundError`——而那会在朋友的机器上发生。

**体积账（未压缩）**：48.8（JRE）+ 79.7（jar）= **128.5 MB**。与设计文档的「100–130 MB」吻合。NSIS 压缩后会更小。

---

## 前置事实（全部已实测）

| 项 | 值 |
|---|---|
| 外壳现在怎么找 Java | `backend::resolve_java(CHANGE_JAVA, JAVA_HOME)` → `PathBuf`，**刻意不做存在性检查**（真找不到时 `Command::spawn` 的 OS 错误更准） |
| 外壳现在怎么找 jar | `locate_jar()`：先 `CHANGE_BACKEND_JAR`，再 `../../backend/target/...` 与 `../backend/target/...` |
| 启动耗时 | **首次 12.2 秒**（到 `CHANGE_PORT`；其中 `Started` 6.8 秒，之后导入 876 条动作约 5 秒）；**第二次约 5 秒**（导入跳过） |
| 现有 `tauri.conf.json` | 776 B，`bundle.targets: ["nsis"]`，`icon: ["icons/icon.ico"]`，CSP 已设，窗口 1280×820 |
| 有没有图标 | `frontend/src-tauri/icons/icon.ico`（`make-icon.py` 生成过） |
| 外壳测试 | 93 passed + 4（`every_command_is_registered_in_the_invoke_handler` 等） |
| 后端 / 前端测试 | 360 / 278 |
| JDK | `C:\Users\alpaca\.jdks\openjdk-25.0.2`（`jlink` / `jdeps` 都在） |
| MSVC | `E:\devtools\vctools`（Tauri 首次编译实测约 1.9 分钟） |

---

## 全局约定

| 项 | 约定 |
|---|---|
| **不许把 API key 打进包** | 代码里现在没有 key。本计划**也不新增**任何 key 配置——那是计划 5 的事，届时配套「使用者填自己的」 |
| **不许把 `E:\` 或任何开发机路径写进产物** | 随包资源用 Tauri 的 `resource_dir()` 解析；开发路径只作为**回退** |
| 依赖 | 外壳**不加** Tauri 插件（除非 WebView2 真的需要，先不加） |
| 体积 | 最终安装包要**报告实际大小**，不是估计 |
| 交付物 | `frontend/src-tauri/target/release/bundle/nsis/*.exe` |

---

## 文件结构

```
backend/
└── scripts/jlink-runtime.ps1                       ← 新：可复现的精简 JRE 构建

frontend/src-tauri/
├── tauri.conf.json                                 ← 改：bundle.resources + webviewInstallMode
├── src/
│   ├── backend.rs                                  ← 改：resolve_java / locate_jar 优先随包
│   └── lib.rs                                      ← 改：窗口立刻可见 + 加载态
└── resources/                                      ← 新（构建产物，见下）
    ├── change-backend.jar                          ← 由 mvn 产出复制而来
    └── runtime/                                    ← 由 jlink 产出
```

> **`resources/` 要不要进 git？** 不要。它是构建产物（128 MB），应该由脚本生成，并且加进 `.gitignore`。**真正的交付物是安装包，不是这些中间文件。**

---

## Task 1: 可复现的精简 JRE 构建脚本

**Files:**
- Create: `backend/scripts/jlink-runtime.ps1`
- Modify: `.gitignore`（忽略 `frontend/src-tauri/resources/`）

- [x] **Step 1: 写脚本**

脚本要做：

1. 从 `JAVA_HOME` 找 `jlink.exe`（找不到就报错并说明怎么设）
2. 用**上面实测过的那套模块清单**（写死在脚本里，附注释说明这是一次实测的结果、不是猜的）
3. 输出到 `frontend/src-tauri/resources/runtime`
4. **打印实际体积**，并在超过某个阈值（比如 60 MB）时**警告**——体积悄悄长大是这类脚本最常见的腐化

`--compress=zip-6` 与 `--strip-debug --no-header-files --no-man-pages` 照实测的那套。

- [x] **Step 2: 跑一次，报告体积**

Expected: **≈ 48.8 MB**，`bin/java.exe` 存在，`java -version` 输出 25.0.2。

- [x] **Step 3: 用产物跑一次后端**（证明脚本产出的不是个坏 JRE）

```powershell
$env:CHANGE_DATA_DIR = "$env:TEMP\jlink-verify"
$env:CHANGE_REDIS_ENABLED = "false"
& frontend/src-tauri/resources/runtime/bin/java.exe -jar backend/target/change-backend-0.1.0-SNAPSHOT.jar
```

Expected: 看到 `CHANGE_PORT=`。

- [x] **Step 4: 提交**

---

## Task 2: 外壳优先用随包资源

**这是本计划最容易写错的一步**：改的是「找 Java」「找 jar」这两条路径，而**它们错了的表现是「在开发机上一切正常，装到别人机器上才炸」**。

**Files:**
- Modify: `frontend/src-tauri/src/backend.rs`（`resolve_java`）
- Modify: `frontend/src-tauri/src/lib.rs`（`locate_jar`）
- Modify: `frontend/src-tauri/tauri.conf.json`（`bundle.resources`）
- Test: `frontend/src-tauri/src/backend.rs` 的 `#[cfg(test)]` 模块

- [x] **Step 1: 先写测试（TDD：这两个函数现在**没有**任何测试）**

要求可测：把「候选路径列表」抽成**纯函数**（不带 IO 判定的版本）或**接受一个「文件存在」谓词**，这样测试能注入假的存在性。

至少覆盖：

1. **随包资源优先**：随包路径存在时，用它，**不看** `CHANGE_JAVA` / `JAVA_HOME`
2. **`CHANGE_JAVA` 仍然有效**：随包不存在、`CHANGE_JAVA` 指向一个存在的文件 → 用它
3. **回退到 JAVA_HOME**
4. **都没有时给出可读的错误**（包含试过哪些路径）——这是「朋友的机器上出问题」时唯一能救命的信息
5. **jar 的候选顺序**：随包 > `CHANGE_BACKEND_JAR` > 开发路径

> ⚠️ **随包路径怎么拿**：Tauri 2 的 `app.path().resource_dir()`。但它需要 `AppHandle`，而 `resolve_java` / `locate_jar` 现在没有。**这正是要处理的设计问题**：把「随包根目录」作为参数传进去，而不是在函数里偷偷拿全局。这样测试不需要 Tauri 运行时。

- [x] **Step 2: 改 `tauri.conf.json`**

```json
"bundle": {
  "active": true,
  "targets": ["nsis"],
  "icon": ["icons/icon.ico"],
  "resources": {
    "resources/change-backend.jar": "change-backend.jar",
    "resources/runtime/": "runtime/"
  },
  "windows": {
    "webviewInstallMode": { "type": "downloadBootstrapper" }
  }
}
```

**`resources` 的映射写法要核实**：Tauri 2 的 `bundle.resources` 支持「源 → 目标」的对象形式；跑一次 `tauri build` 后**进安装目录确认那两个东西真的在里面**，不要只看配置。

- [x] **Step 3: 加一个「构建前检查」**

`tauri build` 之前，如果 `resources/change-backend.jar` 或 `resources/runtime/bin/java.exe` 不存在，**直接失败并告诉人先跑 Task 1 与 `mvn package`**。

否则 `tauri build` 会**成功**产出一个缺资源的安装包——那个包装到朋友机器上才炸。

**做法**：写一个 `beforeBuildCommand`（Tauri 支持）或一个 npm script `build:release`，里面先检查再调 `pnpm build`。

- [x] **Step 4: 跑外壳测试 + `cargo clippy`**

- [x] **Step 5: 提交**

---

## Task 3: 窗口立刻显示 + 加载提示

**为什么必须做**：首次启动到 `CHANGE_PORT` 要 **12.2 秒**。现在的设计是「外壳先不显示窗口避免白屏」（设计文档提到过），但那意味着用户双击后**近 12 秒没有任何反馈**——他会以为没启动、再双击一次、然后两个实例抢同一个库。

**Files:**
- Modify: `frontend/src-tauri/tauri.conf.json`（`visible: false`）
- Modify: `frontend/src-tauri/src/lib.rs`（setup 里开始拉后端，就绪后显示窗口）
- Modify: `frontend/src/views/…` 或新增一个启动态组件
- Test: 前端组件测试 + 外壳测试

- [x] **Step 1: 窗口配置成 `visible: false`**

- [x] **Step 2: 启动态**

外壳在后端就绪后调 `window.show()`。前端在 `BackendStatusBar`（已有）之外，**在应用根部**显示一个全屏的启动态：

- 「正在准备服务…」
- 分阶段文案（可选）：`正在启动` → `首次启动需要导入动作库，约 10 秒`
- **失败时**必须显示错误（后端起不来是真实的可能：端口被占、jar 损坏、缺模块）——**不能停在一个永远转圈的加载页**

- [x] **Step 3: 测试**

至少覆盖：
1. 启动态在「后端未就绪」时显示
2. 就绪后消失
3. **失败时显示错误文案**（不是空白、不是永远转圈）

- [x] **Step 4: 提交**

---

## Task 4: 打出安装包并核对体积

- [x] **Step 1: 准备资源**

```powershell
cd backend
mvn -B -q clean package -DskipTests
cd ..\frontend\src-tauri
New-Item -ItemType Directory -Force -Path resources | Out-Null
Copy-Item ..\..\backend\target\change-backend-0.1.0-SNAPSHOT.jar resources\change-backend.jar
# 再跑 Task 1 的脚本产出 resources\runtime
```

- [x] **Step 2: `pnpm tauri build`**

- [x] **Step 3: 报告实际产物**

- 安装包路径与**实际字节数**（不是估计）
- `resources` 在安装后落在哪里（**解压/安装后进目录看**，不要只看配置）
- 后端 jar 与 runtime 是否真的在里面

- [x] **Step 4: 提交**（只提交源码与配置，**不提交 `resources/` 与 `target/`**）

---

## Task 5: 端到端——真的装一次

**这一步是本计划唯一能证明「朋友的机器上能用」的**。

- [x] **Step 1: 静默安装到一个干净的目录**

NSIS 支持 `/S /D=<path>`。装到 `$env:TEMP\change-install-test`。

- [x] **Step 2: 从那个目录启动（关键：不能带任何开发环境的变量）**

```powershell
# 清掉所有可能让外壳偷偷用上系统 Java 的变量
Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
Remove-Item Env:CHANGE_JAVA -ErrorAction SilentlyContinue
Remove-Item Env:CHANGE_BACKEND_JAR -ErrorAction SilentlyContinue
Remove-Item Env:CHANGE_DATA_DIR -ErrorAction SilentlyContinue
```

然后启动装出来的 exe。

**逐条核对**：

| # | 期望 |
|---|---|
| 1 | 进程起来了，**没有立刻退出** |
| 2 | 数据目录落在 `%APPDATA%\changE`（**不是** `E:\`、不是安装目录） |
| 3 | 库建起来，876 条动作、871 条中文做法、35 条食物 |
| 4 | `/api/health` 返回 200（从日志里读端口） |
| 5 | **关掉窗口后没有残留 java 进程** |
| 6 | 卸载后：数据目录**还在**（用户的训练记录不能被卸载带走） |

- [x] **Step 3: 报告实际结果**

- [x] **Step 4: 提交**

---

## 完成标准

- [x] `jlink` 脚本可复现，产出 ≈ 48.8 MB，且**用产物能真的启动后端**
- [x] 外壳**优先用随包资源**，且有测试证明顺序（随包 > CHANGE_JAVA > JAVA_HOME）
- [x] 找不到资源时的错误信息**列出试过的路径**
- [x] `tauri build` 之前缺资源就**直接失败**（而不是产出缺资源的包）
- [x] 启动时窗口**立刻可见**，加载态有文案；**失败时有错误而不是永远转圈**
- [x] 安装包实际大小已报告；装完后 `resources` 确实在安装目录里
- [x] **在清掉所有开发环境变量的条件下**装一次、启动一次、记一条数据、关掉、确认无残留进程
- [x] 卸载后数据目录仍在
- [x] `cargo test` / `cargo clippy` 全绿；前端 `pnpm test` + `pnpm build` 全绿
- [x] **`resources/` 与 `target/` 没有进 git**
- [x] 代码里**没有任何 API key**

## 实施记录（落地时真撞上的事）

### 1. NSIS 下载会超时——必须手动准备（**下一个人的第一道墙**）

`pnpm tauri build` 第一次跑到打包阶段时失败：

```
Info Verifying NSIS package
Downloading https://github.com/tauri-apps/binary-releases/releases/download/nsis-3.13/nsis-3.13.zip
failed to bundle project: `timeout: global`
```

**不是网络不通**——同一个 URL 用 PowerShell `Invoke-WebRequest` 11.8 秒就下来（2.3 MB）。是 Tauri 自己那个 HTTP client 的超时太短。

**手动准备（两个文件，放到 `%LOCALAPPDATA%\tauri\NSIS\`）：**

```powershell
# 1) NSIS 本体：解压后把 nsis-3.13/* 直接铺到 NSIS 根（makensis.exe 必须在根上）
$target = "$env:LOCALAPPDATA\tauri\NSIS"
Invoke-WebRequest 'https://github.com/tauri-apps/binary-releases/releases/download/nsis-3.13/nsis-3.13.zip' -OutFile "$env:TEMP\nsis.zip" -UseBasicParsing
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory("$env:TEMP\nsis.zip", "$env:TEMP\nsis")
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item "$env:TEMP\nsis\nsis-3.13\*" -Destination $target -Recurse -Force

# 2) tauri 的 NSIS 插件 DLL（少它会在 makensis 阶段失败）
$dllDir = "$target\Plugins\x86-unicode\additional"
New-Item -ItemType Directory -Force -Path $dllDir | Out-Null
Invoke-WebRequest 'https://github.com/tauri-apps/nsis-tauri-utils/releases/download/nsis_tauri_utils-v0.5.3/nsis_tauri_utils.dll' -OutFile "$dllDir\nsis_tauri_utils.dll" -UseBasicParsing
```

然后 `pnpm tauri build` 就过了。

**这两个 URL 与目标路径是从 tauri CLI 的二进制里搜出来的**（`cli.win32-x64-msvc.node` 里的字符串 `nsis-3.13` 与 `Plugins/x86-unicode/additional/nsis_tauri_utils.dll`），不是猜的。

### 2. `bundle.resources` 让「资源存在」成了所有 cargo 构建的前提

加了 `bundle.resources` 之后，**`cargo test` 也会在 build script 阶段检查并复制资源**，缺文件直接失败：

```
resource path 'resources\change-backend.jar' doesn't exist
```

所以新克隆 / CI 的顺序必须是：`mvn package` → `jlink-runtime.ps1` → 才能 `cargo test` / `tauri build`。

`pnpm check:release-resources` 的价值是**更早、更可读的失败**，不是唯一的失败点。

### 3. `TerminateProcess` 硬杀外壳会留下孤儿 java

验收时发现：`Popen.terminate()`（Windows 上是 `TerminateProcess`）杀掉 `change.exe` 后，**那个 JVM 还活着**——因为外壳的 `RunEvent::ExitRequested | Exit` 处理器**根本没机会跑**。

**正常关窗（点 X / `CloseMainWindow()`）不会**：实测优雅关闭后残留 java **0 个**。

这不是缺陷（`TerminateProcess` 不给任何进程机会），但它意味着：**崩溃或被强杀之后，下一次启动可能会碰到一个还占着数据库的旧 JVM。** 记在这里，下一轮做「单实例锁」时一起处理。

### 4. 验收脚本自己抢跑过一次

第一次验收里「库完整」那条 FAIL：`change.db` 只有 4,096 B、0 条动作。**不是产品问题**——`/api/health` 返回 200 只代表 Tomcat 起来了，而 876 条动作的导入是在 `Started` **之后**才跑的。补验等到导入完成：**12 秒整**，876 / 871 / 35 全对。

**教训**：「health 200」与「应用可用」是两件事。判「可用」要看**数据**，不看端口。

---

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| **精简 JRE 缺模块** | 体积对了不代表能跑；错在朋友机器上发生 | **已实测跑通**（Task 0 的前置实验）。但改模块清单后必须重跑一次真实启动 |
| **`bundle.resources` 映射写错** | 配置看着对、包也打出来了，但资源没进去 | Task 4 Step 3 要求**进安装目录实际看**，不看配置 |
| **外壳在开发机上「看起来正常」** | 因为开发机有 `JAVA_HOME`，回退路径把问题盖住了 | Task 5 要求**清掉所有相关变量**再测 |
| **12 秒白屏** | 用户以为没启动，双击第二次 → 两个实例抢同一个 SQLite | Task 3 的加载态；另外**单实例锁**值得考虑（见非目标） |
| **NSIS 需要下载 NSIS 本体** | 首次 `tauri build` 会下载工具链，可能失败 | 先跑一次，失败就报告实际错误 |
| 安装包体积 130 MB+ | 用户可能介意 | 已实测 128.5 MB（未压缩）。**照实报告，不掩饰** |

## 非目标（本计划不做）

- **不做首次启动向导**。设计文档的向导要问两样：数据目录与模型 key。前者**已经自动落到 `%APPDATA%\changE`**（`application.yml` 的默认 + `DataDirectoryInitializer` 的守卫），再问一遍是给用户加一道没必要的坎；后者**现在没有消费者**（计划 5 还没做，代码里没有任何 LLM 配置）。**等计划 5 真的有了模型层，再一起做 key 的填写与存储。**
- **不做自动更新**（Tauri updater）——那需要签名密钥与一个更新服务器，是另一件事。
- **不做 macOS / Linux 包**——当前只在 Windows 上验过；`jlink` 与 NSIS 都是平台绑定的。
- **不做单实例锁**——值得做，但它属于「运行期行为」而不是「打包」，而且加了它就得处理「第二个实例应该激活第一个窗口」这类交互。**记在这里，留给下一轮。**
- **不内嵌 WebView2 离线安装器**（那会让包大 ~130 MB）。用默认的 `downloadBootstrapper`：装的时候下 ~1.8 MB。
- **不改 `volume` / `body-map` 等业务代码**
