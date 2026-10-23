# 这是什么

早期探索阶段的中间产物，**不属于产品代码**，也不参与构建。

保留它们只是为了留个痕迹：某一步为什么那样试、试出来什么。

| 目录 | 是什么 |
|---|---|
| `spike/` | 最初的 Spring Boot 探针工程：验证「Tauri 外壳能不能拉起一个 Java 子进程并通过端口通信」 |
| `za_bench/` | 3D 模型降面与 Draco 压缩的基准脚本 |
| `za_glb/` | 从 Z-Anatomy 导出的中间 glb（产品里用的是 `frontend/src/assets/anatomy/muscles.glb`） |
| `ppt_tasks/` | 早期一版设计稿与渲染（产品里的原型在 `docs/prototype/`） |

`spike/` 里那个 79 MB 的 `spike-0.0.1-SNAPSHOT.jar` **不在仓库里**（`.gitignore` 排除）。
