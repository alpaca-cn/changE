# changE 第一阶段 · 实施计划总览

> **For agentic workers:** 每份计划都是独立可执行的。开工前先读本总览确认依赖顺序，再进入对应计划。

**目标**：把设计文档落成一个能天天用、也能发给朋友的桌面客户端。

**技术栈**：JDK 25 · Spring Boot 3.5.16 · LangChain4j 1.22.0 · SQLite + Flyway · Tauri 2 + Vue 3 · Redis（可选）

---

## 为什么拆成 7 份

第一阶段覆盖 7 个相对独立的子系统。塞进一份计划会有上百个任务，没人执行得动，也没法在中途交付可用成果。拆分后**每份计划都能独立跑出可验证的东西**。

## 计划清单与依赖

| # | 计划 | 产出（做完能验证什么） | 依赖 |
|---|---|---|---|
| 1 | **地基：数据层与核心计算** | 十五张表可迁移；热量 / 单位换算 / 训练容量三个核心计算有测试覆盖；后端能起并上报端口 | 无 |
| 2 | 动作库导入与肌群映射 | 876 个动作入库、带中文名；肌群映射表可用 | 计划 1 |
| 3 | 训练记录 | 能记一次完整训练；上次重量自动带出；周容量算得对 | 计划 1、2 |
| 4 | 饮食记录与拍照识别 | 能记一餐；拍照出草稿并可修正；热量实时算 | 计划 1、2 |
| 5 | 智能体 | 说一句话就能记账；改动可回退；工具白名单生效 | 计划 3、4 |
| 6 | 身体数据、进步曲线、人体图 2D | 体重曲线、1RM 曲线、2D 肌肉热力图 | 计划 3、4 |
| 7 | 打包与分发 | 产出可发给朋友的安装包（自带 JRE、首次启动向导） | 全部 |

**Redis 缓存层不单独成计划**，它作为基础设施放在计划 1 的第 11 个任务里——因为「可降级」必须是地基的一部分，不能事后补。

## 计划 1 与计划 2 之间：Tauri 外壳与 Vue 前端脚手架

> 完整计划：`docs/superpowers/plans/2026-10-16-change-frontend-shell.md`
> 状态：**已完成**（2026-10-16；其中 Task 8 是计划外补的，见下）

**为什么插这一件**：计划 1 的最终审查发现，7 份计划里**没有任何一份负责把外壳与前端建起来**——「外壳 / 前端 / Vue / Tauri」在设计文档里出现 34 次，但在 7 份计划的任何一行里都是 0 次。而计划 3 默认它们已经存在。

**做完之后有了什么**：

- `frontend/`（Vue 3 + TS + Vite + Pinia + Router）与 `frontend/src-tauri/`（Rust 外壳）
- 外壳拉起后端 jar、读 stdout 的 `CHANGE_PORT=`、把端口交给前端
- 深色主题 token 在 `theme.css` 一处定义，由测试钉住设计文档冻结的值
- 侧边栏四个分组 + 9 个占位页，布局与设计文档一致

**端到端实测**（2026-10-16，`pnpm tauri dev`，OpenJDK 25.0.2，本机屏幕缩放 125%）：

| 项 | 实测 |
|---|---|
| 窗口 | 逻辑客户区 **1280×820**（物理 1600×1025，DPI 120；含边框整窗 1294×858）；底色 `#0d0f12` |
| 有没有闪白 | **无**（详见下方「怎么测的」） |
| 从启动到「后端已连接」 | **7.6 秒**（外壳 spawn 出 java → 状态栏显示已连接）。若从 `pnpm tauri dev` 命令发出算起是 **12.9 秒** |
| 状态栏 | Task 7 当时：`后端已连接 · 端口 61998`；右侧是 `缓存状态取不到：TypeError: Failed to fetch`（**不是**「缓存：未启用（配置为关闭）」，见已知项 1）。**Task 8 修完并复测（同一台机器，端口 57638）：右侧变成 `缓存：未启用（配置为关闭）`** |
| 关窗后残留 java 进程 | **无**。窗口在收到 WM_CLOSE 后 0.11 秒内销毁；随后 1.5 秒的进程快照里 `change.exe` 与它的 java 子进程都已不在，`java.exe` 只剩一个 FinalShell 自己的（命令行 `-jar finalshell.jar`，与本项目无关）。四次启动、每次关窗后都一样 |
| jar 找不到时的提示 | `CHANGE_BACKEND_JAR 指向的文件不存在：E:\does\not\exist.jar`（红点 + 这条文案，界面一出现就在，没有停在「正在启动后端…」） |

**怎么测的**（起点不同数值差很多，所以写清起止）：

- **不闪白**：窗口一出现就按 ~80ms 一帧抓，连抓 3 秒、之后 250ms 一帧。抓帧用的是只针对**我们自己那个窗口 HWND** 的 `PrintWindow`（渲染到独立的内存 DC，不含压在它上面的任何窗口），**没有任何一次全屏截取**。窗口出现后约 20ms 的首帧：客户区均值亮度 **14.791**、整窗 22.539，而且客户区**逐像素 100% 都是 `#0d0f12`**（Tauri 的 `backgroundColor`）；全程 116 个有效帧里最亮的一帧 luma **25.017**，白帧（luma > 200）**0 个**。
- **加载提示**：同一串帧里，UI 渲染出来那一帧（启动后 5.9 秒）就是灰色圆点 + 「正在启动后端...」；圆点所在小方块的均值亮度在 **26.0–33.6** 之间来回摆（`opacity: 0.3 → 1` 的脉冲）。
- **7.6 秒的起点与终点**：起点 = 外壳 `spawn` 出 java 子进程的那一刻（20ms 轮询进程快照，取 `change.exe` 的第一个 `java.exe` 子进程，±25ms）；终点 = 抓到的第一帧里状态栏左侧文字变成「后端已连接 · 端口 …」的那一刻（用状态栏区域的像素统计定位，落在两帧之间 = ±140ms）。**不是**从 `pnpm tauri dev` 命令发出算起——那个数里含 cargo 编译（本次 target 是热的，只花 0.48 秒；冷启动会到 100 秒以上），不是用户感知的等待。
- 同一轮的后端日志可以交叉验证：`Started ChangeApplication in 6.475 seconds (process running for 7.18)`、`Tomcat started on port 61998`、`CHANGE_PORT=61998` 三行都出现在外壳 stdout 上，其中 `CHANGE_PORT=` 那一行比状态栏变文字早约 0.45 秒（前端 500ms 一次的轮询周期）。

**计划外补的 Task 8（健康检查改由外壳代发）**：Task 7 的端到端验收发现第 5 条不满足——
状态栏右侧是 `缓存状态取不到：TypeError: Failed to fetch`。根因是**前端自己用 `fetch`
打后端随机端口**，属于跨源，而响应没有 `Access-Control-Allow-Origin`；当初给 CSP 写的
`connect-src http://127.0.0.1:*` 只管「能不能发请求」，管不了「浏览器肯不肯把响应交给脚本」。
修法是**不给后端加 CORS**（那等于任何网页都能读本机服务的响应），改由 Rust 外壳代发这条请求，
CSP 随之收紧成 `connect-src 'self'`。复测后状态栏右侧是 `缓存：未启用（配置为关闭）`，
「完成标准」第 4 条随之勾上。完整记录见计划文档的 Task 8 一节。

**留下的已知项**：

1. ~~**缓存状态在真机上取不到**~~ —— **已由 Task 8 修复**（外壳代发健康检查，2026-10-16 复测通过）。
   原始记录：后端 `/api/health` 本身是好的（直接请求返回 `200` + `{"cache":"缓存：未启用（配置为关闭）","status":"up"}`），但它没有 `Access-Control-Allow-Origin` 头；而页面源（dev 是 `http://127.0.0.1:1420`，打包后是 `http://tauri.localhost`）与后端随机端口**不同源**，浏览器把响应拦掉了。对照实验钉死了根因：同一个假后端只切换有没有 ACAO 头——加了就显示「缓存：未启用（配置为关闭）」，不加就复现 `TypeError: Failed to fetch`。46 条前端测试**全部 mock 掉了 `fetch`**，所以这条路径之前从未真机跑过。修法（后端补 CORS，或外壳代发健康检查）留给下一个任务。
2. **Task 1 记的「窗口创建约 4 秒后被最小化」这次没有复现**。本次四次完整启动（正常启动 40 秒、jar 找不到 20 秒、两次只差一个 ACAO 头的假后端对照各 16 秒）全程按 20ms 轮询 `GetWindowPlacement` / `IsIconic` / `GetWindowLong(GWL_STYLE)`，只看到 `showCmd=1`（正常显示）与关窗时的 `showCmd=0`，**没有一次** `showCmd=2` / `IsIconic` / `WS_MINIMIZE`；抓帧序列里也没有任何一帧是空的或黑屏（那会是最小化的迹象）。本机在跑 NxDock、Rainmeter、Wallpaper Engine、VMware（含 `vmware-unity-helper`）等窗口管理类软件——**没能归因到具体哪一个**。
3. 后端启动实测 **7.18 秒**（JVM 进程起到 `CHANGE_PORT` 行），比上面「前置事实」里记的「约 3.8–6.8 秒」上限略长；本机同时跑着 VMware 虚拟机与视频播放。

## 计划 2：动作库导入与肌群映射

> 完整计划：`docs/superpowers/plans/2026-10-16-change-plan2-exercise-import.md`
> 状态：**Task 3–6 已完成**（2026-10-18，提交 `62864d3` / `51a4d03` / `c9b1050` / `119672d`）；Task 7（中文名）与 Task 8（端到端验收）见下方「未完成 / 待决」

**实测数字**（不是估计）：

| 项 | 值 |
|---|---|
| vendor 数据集 | `yuhonas/free-exercise-db` 的 `exercises.json`，**1,005,327 字节**，sha256 `5bb747e3…`，**876 条**，Unlicense（`LICENSE.md` / `schema.json` 一并 vendor，随包分发）。加载时校验 sha256 / 字节数 / 条数，不符即抛异常 |
| 导入产出 | **876** 个动作、**2,587** 条肌群标注（朴素相加 2,596；9 条主/次肌群重叠按「primary 赢」去重）、**17** 块肌群 |
| change_log | 一次导入写 **876 + 2,587 = 3,463** 条（`origin='import'`，动作行与 join 行都记） |
| 幂等 | 重跑一次：`inserted=0 / skipped=876`，行数不变（靠 `source_id` 部分唯一索引跳过，不用 `INSERT OR REPLACE`） |
| 9 条重叠 | 抽查 `Barbell Step Ups` 的 `quadriceps`：恰好 1 条、`role='primary'` |
| 分类 | **292** 条不是 `strength`（stretching 123 / plyometrics 61 / powerlifting 38 / olympic weightlifting 35 / strongman 21 / cardio 14）→ 加 `exercise.category` 供「记一组」的选择器过滤 |
| 其它分布（测试钉住） | mechanic=null 87 / equipment=null 77 / body only 111 / instructions 非空 871 / id≠name 844 |
| 迁移 | 新增 **V8**（`exercise.category` + `exercise.instructions`）。V8 的 checksum 从实跑后的库读出后钉入 `MigrationTest`（`-1311493510`）；V1–V7 的七个值一个未变 |
| 中文名 | **876 条动作全部有中文名**（`seed/name-zh.json`，44,684 字节，876 个互不重复），启动时由 `ExerciseNameZhRunner` 写入。**机器（智能体）生成、未经人工逐条复核**——见下方「未完成 / 待决」第 1 条 |
| 测试 | `mvn -B clean test` → **184 条、0 失败、1 skipped** |

**干净库端到端实跑**（2026-10-18，`java -jar`，`CHANGE_DATA_DIR` 指向临时目录）：应用正常启动；Flyway 依次应用 **V1–V8**（全部 `success=1`，V8 checksum `-1311493510` 与冻结值一致）；`ExerciseImportRunner` 在启动时导入，日志打出 `动作库导入：新增 876 个动作 / 跳过 0 个（已存在）/ 写入 2587 条肌群标注（源前缀 free-exercise-db:）`。库内实测：`exercise=876` / `exercise_muscle=2587` / `muscle_group=17` / `change_log(origin='import')=3463`（= 876 动作 + 2587 标注），`Barbell Step Ups` 的 quadriceps 恰好 1 条且 `role='primary'`。应用启动 3.6 秒，导入再花约 2.3 秒；`CHANGE_PORT`（外壳据此判定「后端已连接」）由 `ApplicationReadyEvent` 发出、**排在导入之后**，所以前端连上时数据一定已就位。再启动一次则全部跳过（幂等）。紧跟着 `ExerciseNameZhRunner` 写中文名，日志 `动作中文名：写入 876 / 已有跳过 0 / 重名跳过 0 / 找不到导入动作 0`，库内 876 条都有中文名且互不重复（抽查 `Plank→平板支撑`、`Clean and Jerk→挺举`）。

**`region_ids` 的口径**：词汇由**我们**定义（`com.change.seed.MuscleRegion` 的 27 个 id），**不是**直接用某个 SVG 文件的 path id——那是外部实现细节，换版本不该让库里 876 条动作的标注跟着漂。SVG path → region id 的映射放在前端（计划 6）。`muscle_group.region_ids` 存的是**逗号分隔的字符串**（不是数组）：多区域肌群写两个 id（如 `chest_left,chest_right`）。

**「撤销导入」是一个专用操作，不是日志回放**：`ExerciseImportService.unimport()` 按 `source_id LIKE 'free-exercise-db:%'` 前缀**一次删掉**全部导入动作（join 行由 `ON DELETE CASCADE` 带走），并写**一条**汇总 `delete` 日志作为可追溯证据。它**不**走那 3,463 条逐行日志的回放——那是「撤销 3,463 个东西」而不是「撤销这次导入」。`change_log` 一行不删：日志是历史。撤销可逆：再导入一次恢复 876 / 2,587。

**仍未完成 / 待决**：

1. **中文名（Task 7）已产出，但没有人工逐条复核**：876 条动作全部有中文名（`seed/name-zh.json`，44,684 字节）。计划原本指望「用 hasaneyldrm 的中文名交叉校验」，但**那个来源只有中文做法说明、根本没有名字**（见计划 2 · Task 7 的实施修正），所以 876 条**全部**是从英文名翻译得来、由**智能体产出**、**未经人工复核**。库里没有重复的中文名（测试与实跑都验过）。正式使用前建议抽查，尤其力量举术语（`Snatch`/`Clean`/`Jerk` 这类）。
2. **`unimport()` 的入口已定，而且撤销后重启不会复活**（2026-10-18）：入口是 HTTP 端点 `POST /api/exercises/unimport`（配套 `GET /api/exercises/status`、`POST /api/exercises/import`）。同时改了启动策略——动作库**只自动安装一次**：`ExerciseImportService.shouldInstallOnStartup()` 要求「库里没有导入的动作 **且** 从没撤销过」，后者读的是 `unimport()` 写下的那条汇总日志（**不需要新表**）。实测：导入 → `POST /unimport` → 重启 → 启动日志「动作库：已安装（或已被撤销），跳过启动导入」、`imported=0`；只有显式 `POST /import` 才装回来（并连中文名一起写）。注意 `unimport` 是**物理删除**导入的动作 + 留一条可追溯的汇总日志，和逐个动作的 `archived`（隐藏、但仍要能被旧记录解析）是两回事。
3. **Task 8 核对清单第 6 条的措辞过时**：写的是 `origin='IMPORT'`，实际存的是小写 `'import'`（`change_log.origin` 的 CHECK 是小写）。第 1–7 条现已可在真实应用上核对（见上面的端到端实跑）；第 8、9 条（触发 `unimport()`）见上一条。

## 计划 3：训练记录（后端）

> 完整计划：`docs/superpowers/plans/2026-10-18-change-plan3-training.md`
> 状态：**Task 1–7 全部完成**（2026-10-18 提交 `faf2e0b` / `e06876b` / `dc86e41` / `1e8229c` / `135bd3b` / `7e119f3`；Task 7 端到端验收 2026-10-19）

**三条读侧数字的口径**（记一次训练之外的三个数字都从这里来，都要「答得准」而不只是「算得出」）：

1. **「上次重量」里的「上次」= 按训练日期，不是按记录 id。** SQL 的排序键是 `workout_session.session_date DESC, s.id DESC, se.order_index ASC`（`LastPerformanceService`）——取「**包含这个动作的、最近的那次训练**」的那个动作，再把它的组按 `set_index` 升序给出。从没做过 → **204**（不是 404：「没做过」不是错误）。日期在 `workout_session`、动作在 `session_exercise`，跨表排序派生方法名表达不了，所以这一条走 SQL，组本身仍走派生查询。
2. **周容量 = 主肌群 +1 / 次肌群 +0.5，自然周（周一起算），只算 `completed = 1`。** 没打勾的组是「还没做/没记」，不是训练量。返回**固定 17 块**肌群（含 0 组的）——「腹/核心本周 0 组」这类提醒必须以 0 出现，不能从列表里悄悄消失。`neck` 的区间是 null → `NOT_CONFIGURED`，与「练了 0 组」的 `UNTRAINED` 是**两个事实**。
3. **动作选择器默认排除 `stretching`。** `category` 给定时精确匹配；不给定时**只**滤掉拉伸——其余分类（`olympic weightlifting` 的抓举/挺举等）都保留。`limit` 默认 50、封顶 200。文本搜索复用 `ExerciseRepo` 既有的两个派生查询（计划 2 刚修过 `archived` 过滤，这里是它们的第一个真实调用方）。

**干净库端到端实跑**（2026-10-19，`java -jar` + Python 直接打 HTTP 与只读 SQLite；`CHANGE_DATA_DIR` 指向临时目录；本次的「今天」= 2026-10-19，**周一**）：

启动日志：Flyway 依次应用 **V1–V8**（`now at version v8`）→ `动作库导入：新增 876 个动作 / 跳过 0 个 / 写入 2587 条肌群标注` → `动作中文名：写入 876 …` → `CHANGE_PORT=53100`。应用启动 **4.642 秒**。

| # | 请求 | 实测结果 |
|---|---|---|
| 1 | `POST /api/sessions`（动作 = id 16 `交替地板卧推`；3 组：2 组「60×8 已完成」+ 1 组未完成） | **200** `{"sessionId":1,"exercises":1,"sets":3}` |
| 2 | `GET /api/sessions/recent` | **200** `[{"id":1,"sessionDate":"2026-10-19","title":"胸 + 三头","perceivedState":"睡得好"}]` |
| 3 | `GET /api/sessions/1` | **200**，`exercises[0].sets` 长度 3、`setIndex` 为 0/1/2（升序）；`startedAt` = `"2026-10-19T19:30:00"`。`GET /api/sessions/999999` → **404** |
| 4 | `GET /api/exercises/16/last-performance` | **200** `{"sessionDate":"2026-10-19","sessionExerciseId":1,"sets":[{"weight":60.0,"repetitions":8},…,{"weight":null,"repetitions":null}]}` |
| 5 | `GET /api/volume/weekly?date=2026-10-19` | **200** `weekStart=2026-10-19`、`weekEnd=2026-10-25`、`muscles` 长度 **17**；`chest` = **2.0**（主 +1×2）、`triceps` = **1.0**（次 +0.5×2），两者 status 均 `BELOW`（区间 10–20 / 6–14）。**3 组里只有 2 组完成 → 容量是 2.0 而不是 3.0，「只算 completed」在真实数据上成立** |
| 6 | `SELECT COUNT(*) FROM change_log WHERE origin='user'` | **5**（`workout_session` 1 + `session_exercise` 1 + `workout_set` 3）。`after_json` 里的日期/时间戳全是字符串：`{"session_date":"2026-10-19","started_at":"2026-10-19T19:30",…}` |
| 7 | **再启动一次**（库已存在） | 日志 `动作库：已安装（或已被撤销），跳过启动导入` / `动作中文名：都已写好，跳过`；`GET /api/sessions/recent` 仍有那次（新端口 53118）。**没有重现导入** |
| 8 | `GET /api/volume/weekly?date=2026-08-20`（别的周） | **200**，17 块全部 0 组、状态集合 `{NOT_CONFIGURED, UNTRAINED}`（**不是 500**）。`?date=2026/10/14` → **400** |

**顺带核实的两个风险点**（都在风险表里点名过）：
- `workout_session.started_at` 落库是 `'2026-10-19T19:30'` —— 可读的 ISO 串，**不是**纪元毫秒，`@JdbcTypeCode` 映射生效（`started_at` 列没有 CHECK，写错会是**静默**的，所以这条只能靠真库读出）。
- `change_log` 快照里的日期/时间戳全是字符串（`ChangeLogService` 的 `ObjectMapper` 没注册 `JavaTimeModule`，放 `java.time` 对象会在**记日志**那一步抛异常，把整次记录弄失败）。

**测试**：`mvn -B clean test` → **213 条、0 失败、1 skipped**（本计划开始时是 190 条）。**无新迁移**：迁移仍是 V1–V8、连续无缺口。

**仍未做**：前端「今日 / 训练记录」两页——另起一份计划（`TodayView.vue` / `TrainingView.vue` 仍是占位页）。后端**没有**训练的删除/修改端点（设计的安全线是「只增改不删」）。

## 计划 4a：饮食记录（完全本地的那一半）

> 完整计划：`docs/superpowers/plans/2026-10-19-change-plan4a-nutrition-local.md`
> 状态：**计划已写，尚未实现**（2026-10-19）

上表里的**计划 4 是「饮食记录与拍照识别」**，它含两条体量差很大的路径，所以拆开：

| 半份 | 内容 | 依赖 |
|---|---|---|
| **4a（本节）** | 搜食物库、按份量或克数加条目、热量与三大营养素**实时算**、写入并逐行审计、今日页显示当天摄入 | 计划 1、2 |
| 4b | 拍照 / 外卖截图 / 直接说话 → AI 出草稿 → 用户确认 | 多模态模型 + API key（首启向导），且 4a 的 `MealItem` 结构先就位 |

**为什么先做 4a**：它**完全不依赖网络**（设计文档的核心原则：模型不可用时本地记录必须照常可用），能独立验收；而 4b 要先定多模态模型、key 存哪、降级与免责提示，是三件都需要拍板的事。

**已核对的前置事实**（写计划时逐条查过磁盘，不是复述）：四张表 `food`/`food_portion`/`meal`/`meal_item` 在 **V3/V4** 已建好，**本计划不需要新迁移**；`ChangeLogService.ALLOWED_TABLES` **已含这四张表**，不用改；`com.change.calc` 的 `Nutrition` / `NutritionCalculator`（`forGrams` / `sum`）/ `PortionConverter`（`toGrams` / `Unit`）**已存在且有测试**，本计划只接线、不重写计算。

**三条口径（写在计划里、实现时要守住）**：

1. **克数由后端算**——`POST /api/meals` 的契约里**没有 `grams` 字段**；前端只在预览时按同一公式估，入库的换成后端用 `PortionConverter.toGrams` 算的值。
2. **热量永远实时算**——`meal_item` 上**没有** kcal 列，任何地方都不回存合计（设计文档决定 3 的铁律）。
3. **grams 存未定标的值**——`calc/package-info` 约定二：定标（两位小数）属于显示层，先舍再累加会引入有方向性的偏差。

**一条要在实现时保持诚实的事**：种子食物库用 **30 条常见中式食物**的**近似**每百克数值（**中国食物成分表的转录版有版权、不可分发**，设计文档明确要求分发版排除它）。数值由智能体填写、**未经逐条核对**——这一点要同时写在 seed 文件头、Runner 的类注释与总览里，任何地方都不许把它说成「权威值」。

## 前端计划：今日与训练记录页

> **命名**：前端的工作按**主题**命名、不占计划编号（同「计划 1 与计划 2 之间：Tauri 外壳与 Vue 前端脚手架」那一节）。上表里的**计划 4（饮食记录与拍照识别）与本节无关**——它的本地半份（4a）见上一节，已写好计划、尚未实现。

> 完整计划：`docs/superpowers/plans/2026-10-19-change-frontend-training-pages.md`
> 状态：**Task 1–5 已完成**（2026-10-19 提交 `4efdde6` / `43a2b98` / `08eb227` / `bd58e4c` / `523cbf6`）；Task 6 见下「真机验收」与「仍未做」

**跨源怎么定的（这份计划的第一节，必须先定）**：沿用计划 1 Task 8 的结论——**前端不发任何 HTTP**。把外壳那条「只会 GET /api/health」的代发扩展成**通用代理命令** `api_request(method, path, body?)`（`src-tauri/src/http.rs` 的 `build_request` / `validate_path` / `parse_api_response`），前端只 `invoke`。**后端零改动、CSP 仍是 `connect-src 'self'`、capabilities 未动。**

三条实现上的取舍（都有测试钉住）：

| 取舍 | 理由 |
|---|---|
| 任何状态码都是 `Ok`，只有传输失败才是 `Err` | 前端要按状态码分流：400 显示服务端那句中文校验文案、404 显示「找不到」、204 显示「这个动作没做过」。压成一句错误消息，这三条就再也分不开 |
| `parse_api_response` **不做**「正文是空的」检查 | 与 `parse_health_response` 的契约**不同**：204 与 5xx 都可能没有正文，而「没做过这个动作」就是 `204 + 空正文`，判成错误会把一个正常状态变成吓人的失败 |
| `API_TIMEOUT = 10s`（不是健康检查的 2s） | 写一次训练要落库，2 秒太紧 |
| `path` 拦掉 `\r` `\n` 空格 | 请求行/头注入的守门点（`validate_path`） |

**容量口径在界面上的落地**：17 块肌群**全部渲染**（含 0 组的）——`VolumePanel` 的变异实测：给列表加 `.filter(x => x.sets > 0)`，恰好 3 条变红。状态用 `--ok/--warn/--danger/--muted-strong` 四个新语义色，且**颜色之外还有文字**（「偏少」「本周 0 组」「未设目标」），色盲用户也读得出。

**测试数字（2026-10-19 实测）**：

| 命令 | 这份计划之前 | 现在 |
|---|---|---|
| `cd frontend && pnpm test` | 50 passed（8 files） | **125 passed（13 files）** |
| `cd frontend && pnpm build` | 退 0 | 退 0（`built in 725ms`） |
| `cd frontend/src-tauri && cargo test` | 76 passed + 4 integration，2 ignored | **93 passed + 4 integration，3 ignored**（新增的 `api_request` 纯函数测试 + 1 条真机 e2e） |

变异实测（新增的守卫逐个改坏再还原）：通用代理 3 处 → 恰好 5 条红；容量面板隐藏 0 组 → 3 条红；`toNumber('')` 返回 `0` 与「预填就打勾」→ 恰好 6 条红。全部还原后全绿。

**真机链路验收（headless，2026-10-19）**：`cargo test end_to_end_proxy -- --ignored --nocapture` 真的拉起后端 jar，用**外壳自己的传输代码**（`http::api_request`）走一遍。真实输出：

- `spawn → 就绪用了 5.63 秒`
- `GET /api/volume/weekly` → **200**，耗时 **223 ms**，`muscles` 17 条、`muscles[0].nameZh == "胸部"`
- `GET /api/exercises?q=%E5%8D%A7%E6%8E%A8&limit=1` → **200**，命中 `JM Press`（**查询串与百分号编码原样穿过代理**）
- `GET /api/exercises/999999/last-performance` → **204**，正文是空串（不是 `'0'`、不是 `'[]'`）
- `POST /api/sessions`（非法体）→ **400** `{"error":"一次训练至少要有 1 个动作"}`（**body 与 `Content-Length` 真的被后端读并解析了**）
- `GET /api/sessions/999999` → **404**，而且**是 `Ok` 不是 `Err`**

**真机发现的缺口 → 已修（2026-10-19）**：404 的正文原来是 Spring 的默认错误体——
`{"timestamp":…,"status":404,"error":"Not Found","path":"/api/sessions/999999"}`。
`TrainingController` 抛的 `ResponseStatusException(404, "找不到这次训练: n")` 里**那句中文原因根本没有出现**（Spring 默认 `server.error.include-message=never`），所以前端界面上只有英文的 `Not Found`。

**修法**：**没有**选 `server.error.include-message=always`——那会让 404 的正文长成 Spring 自己的字段布局，与 400 的 `{error}` 不一致，前端还得同时认两种形状。改成给 `ApiErrors` 补第三个 handler，把 `ResponseStatusException` 收进**同一套** `{error}` 形状、并**保留原状态码**（404 还得是 404）。**前端读 `error` 的代码一行都没改。**

改完真机复测（同一条代理 e2e）：`400 正文 = {"error":"一次训练至少要有 1 个动作"}`、`404 正文 = {"error":"找不到这次训练: 999999"}`。后端测试 213 → **214**（新增一条同时断言「正文是我们那句话 + 状态码仍是 404 + Spring 默认体的 `timestamp`/`path` 不该出现」）。

> 这一条**违反了这份计划自己写的「不动后端」**。理由：它是真机跑出来的、用户可见的缺陷，而修法只有一处，且让错误契约变得**更一致**（400 与 404 同形状、前端零改动）。已记进计划文档的「实施修正」。

**仍未做 / 需要人工**：

1. **真机 GUI 逐条核对（Task 6 Step 2 的 8 条）没做**——本会话没有可视窗口，而前端访问后端全靠 `invoke`，纯浏览器里跑不起来（没有 Tauri 运行时）。**能自动化的那一半已经做了**（上面那条 headless 代理 e2e，覆盖的正是「代理接错了就整条链路瘫掉」的那部分）；剩下的跳路由 / 渲染 / 点按钮属于 jsdom 组件测试的范围，而**「打开一个真窗口、把界面上的字读出来」仍然待人工执行**：`cd backend; mvn -B -q -DskipTests package`，然后 `cd frontend; pnpm tauri dev`。

## 已完成的前置验证

`spike/` 目录已实测通过（2026-10-14），本计划不再重复：

- JDK 25 跑 Spring Boot 3.5.16 ✅
- SQLite + Hibernate 社区方言 ✅
- LangChain4j 1.22.0 加载 ✅
- **Redis 不可达时应用照常启动** ✅

两个实测数字会影响实现，已在计划中体现：

- **启动 3.769 秒** → 外壳必须有加载提示
- **fat jar 79.1 MB** → 打包计划要为此留出体积预算

## 执行顺序建议

计划 1 → 2 → 3 → 4 → 5 → 6 → 7。

其中**计划 3 与 4 可以并行**（都只依赖 1、2，彼此不依赖）。计划 5 必须等 3、4 完成，因为它的工具要操作这两块的数据。

## 计划 2 之前的一个独立任务：MyBatis-Plus 读侧

> 任务卡：`MyBatis-Plus 读侧：动作库查询 + 补 ExerciseRepo 的验证`（DSH 任务板，id `1092ea20`）
> 排期：**计划 1 完成之后、计划 2 开工之前**

### 为什么插这一件

计划 1 的最终整体审查发现：`ExerciseRepo` 的两个查询方法（`findByArchivedFalse`、`findByNameZhContainingOrNameEnContainingIgnoreCase`）**零测试覆盖**——审查的原话是「第一次真实使用就是它的第一次执行」。而**计划 2 会立刻大量使用它们**，包括中英混合的 `Containing` + `IgnoreCase` 搜索。

所以这个任务同时拿到三样东西：MyBatis-Plus 的学习收益、补上一个真实缺口、给计划 2 去风险。

### 关键设计动作（这是它值得单独成任务的原因）

计划 1 用 JPA，其中 `ddl-auto: validate` 不是装饰——它在 Task 4 真的拦下过「community 方言把 `Long` 映射成 `bigint`，而 schema 里写的是 `INTEGER`」，那次是**上下文根本起不来**（实测 17 个错误）。

**MyBatis-Plus 没有等价物。** 把表搬到 MP 就会丢掉这道启动期守卫。补偿方式是把守卫**下沉成测试**：反射 MP 实体的字段→列名映射，与 `pragma_table_info('<表>')` 逐列比对。

这条比 `validate` **更强**——`validate` 只覆盖有 JPA 实体的 4 张表，而这条可以覆盖**全部十五张表**（含那 7 张还没有实体的，就是最终审查点名的那批「字符串列名是唯一契约」的表）。

### 未知项：先证实再动手

MyBatis-Plus 对 **SQLite** 的支持程度、以及与 **Spring Boot 3.5.16 / JDK 25** 的版本兼容性，**本项目尚未验证过**。参照 `spike/` 的做法先做最小验证（能起、能查、`java.time` 映射正确——注意 xerial 默认把 `LocalDate`/`LocalDateTime` 写成纪元毫秒，JPA 那边是用 `@JdbcTypeCode` 解决的，MP 那边需要等价的 `TypeHandler`）。

**若发现硬伤就停下来报告**，不要硬上。

### 与「换掉整个数据层」的区别

本任务是**读侧的增量替换**，不是把 JPA 换掉：写入路径（`ChangeLogService` 那套动态标识符 + 白名单 + 快照回退）与四个纯计算类**不动**。

`exercise` 建议**整表搬到 MP**（不要让同一张表有两套映射——那是「同一份真相两个副本」的经典错误），代价是它不再受 `validate` 保护，由上面那条反射测试补上。

### 关于「要不要全面改用 MyBatis-Plus」

**不建议**。理由不是技术不相容（4 个实体全是平铺字段映射、零关联、零 JPQL，换起来并不难），而是**价值错配**：

| 这个项目真正的难点 | MyBatis-Plus 能帮上吗 |
|---|---|
| `ChangeLogService`：表名来自白名单、列名来自快照键，**标识符拼进 SQL** | 不能，仍要手写 + 自己防注入 |
| 十五张表**手写 DDL**（Flyway），schema 由五层快照钉死 | 不能，也不需要 |
| 一键回退靠 before/after 快照 | 不能，这是业务约束 |

MP 卖的是「少写 CRUD」，而本项目缺的不是 CRUD——4 个仓储、一共 4 个查询方法，这部分本来就不值钱。**最痛的三个地方它一个都碰不到，却会让你丢掉 `validate`。**

因此：**读侧增量用 MP 学，写侧继续 JPA。**

## 全局约定

以下约定在所有计划中一致，不重复说明：

| 项 | 约定 |
|---|---|
| 后端根目录 | `backend/` |
| 包名 | `com.change` |
| 迁移脚本 | `backend/src/main/resources/db/migration/V<n>__<desc>.sql` |
| 测试 | JUnit 5；单元测试不依赖 Spring 上下文，集成测试用 `@SpringBootTest` |
| 提交粒度 | 每个任务一次提交，前缀 `feat:` / `fix:` / `test:` / `chore:` |
| 提交信息语言 | 中文，说清「做了什么 + 为什么」 |
| 时区 | 全部使用 `LocalDate` / `LocalDateTime`，不做时区转换（单机自用） |
| 主键 | `INTEGER PRIMARY KEY AUTOINCREMENT`（SQLite 的 rowid 别名） |
| 时间戳 | `TEXT` 存 ISO-8601，SQLite 无原生日期类型 |
| 布尔 | `INTEGER` 0/1 |
| 金额与营养 | 一律 `REAL`；只有整数语义的用 `INTEGER` |

## 明确的非目标（第一阶段不做）

计划模板 · 多用户 · 云同步 · 手机 App · 社交排行 · 3D 人体图 · 从 FitNotes/Strong 导入 · 渐进规则引擎

这些在总览与设计文档里都有记录，实施时不要顺手加。
