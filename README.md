# changE

一个给自己用的健身记录客户端。单用户、离线优先、数据存在本机的一个 SQLite 文件里。

- **记录**：训练（组 / 组类型 / 主观状态）、饮食、身体数据
- **分析**：可点选的人体图（2D + 3D，按肌群与子部位上色）、进步曲线
- **智能体**：接自己的大模型 API，用自然语言记录、拍照识别饮食
- **数据**：一键备份、可撤销的写入日志

> 给朋友用也是同一个安装包——装完自带 Java 运行时，不需要他们准备任何环境。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 外壳 | **Tauri 2**（Rust）：窗口、单实例、进程管理、自动更新 |
| 前端 | Vue 3 + TypeScript + Vite + Pinia + Vue Router |
| 后端 | **Spring Boot 3**（Java）+ SQLite + Flyway |
| 3D | Three.js + Z-Anatomy 解剖模型（glTF） |

**前端不发 HTTP。** 所有后端调用都走外壳的一条 Tauri 命令（`api_request`），
它只允许 GET / POST。这样 WebView 的 CSP 可以收得很紧（`connect-src 'self'`），
而网络访问只发生在 Rust 与 Java 两层。

**后端是同一台机器上的子进程**，端口每次随机，启动后由外壳探测健康检查。

---

## 目录

```
backend/    Spring Boot：领域服务、Flyway 迁移、智能体工具
frontend/    Vue 应用 + src-tauri（Rust 外壳）
docs/       设计文档、原型、决策记录（含 3D 模型的许可决定）
scripts/    发版、3D 资产导出、人体图区域清单
keys/       更新签名**公钥**（私钥已 gitignore）
scratch/    早期探索的中间产物，不属于产品代码
```

---

## 开发

需要 JDK 21+、Node 20+、Rust、pnpm。

```powershell
# 后端
cd backend
mvn -B -q -DskipTests package && java -jar target/change-backend-*.jar

# 前端（另开一个终端）
cd frontend
pnpm install
pnpm dev            # 只跑 Web 部分
pnpm tauri dev      # 连同外壳一起跑
```

首次启动会导入动作库（876 个动作 / 871 条中文做法），约 10 秒。

### 测试

```powershell
cd backend  ; mvn -B clean test        # 434
cd frontend ; pnpm test                # 386
```

---

## 打包

```powershell
cd backend
mvn -B -q -DskipTests package
Copy-Item target/change-backend-*.jar ../frontend/src-tauri/resources/change-backend.jar -Force

cd ../frontend
pnpm tauri build
```

产物在 `frontend/src-tauri/target/release/bundle/nsis/`，约 107 MB：
其中 80 MB 是后端 fat jar，49 MB 是 jlink 出来的 Java 运行时。

自动更新与发版流程见 [`docs/发布与自动更新.md`](docs/发布与自动更新.md)。

---

## 数据与许可

这个项目用了第三方数据与模型，各自的许可不同，**不是整个仓库一个许可**：

| 资产 | 来源 | 许可 |
|---|---|---|
| 动作库（876 个动作、英文做法） | [free-exercise-db](https://github.com/yuhonas/free-exercise-db) | Unlicense |
| 动作中文做法（871 条） | 本项目机器翻译，**未经人工审校** | 同本项目 |
| 3D 肌肉模型 | [Z-Anatomy](https://github.com/Z-Anatomy/Models-of-human-anatomy) | **CC BY-SA 4.0** |
| 3D 皮肤 | [BodyParts3D](https://dbarchive.biosciencedbc.jp/en/bodyparts3d/) | **CC BY 4.0** |
| 2D 人体图 SVG | 见 `docs/` 里的署名记录 | 见该记录 |

**3D 资产是 CC BY-SA 4.0**：模型本体（以及我们对它的修改）继续按 CC BY-SA 分发，
应用代码不受影响。署名见应用内的「关于与致谢」页。
决策过程记录在 [`docs/2026-10-21-3D模型来源与许可.md`](docs/2026-10-21-3D模型来源与许可.md)。

---

## 它不做什么

- 不做账号体系、不做云同步。数据只在本机，备份是导出一个 `.db` 文件。
- 不提供训练建议。界面上的「本周容量参考区间」只是**提醒的比较基准**，
  不是医学建议，也不是处方。
- 不支持多用户。一个人一个数据目录。
