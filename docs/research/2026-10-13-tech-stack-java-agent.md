# 调研：Java 侧 agent 技术栈与拍照估热量可行性

- 日期：2026-10-13
- 项目：change（个人自用健身记录客户端）
- 用途：为「技术选型」「可行性分析」两份交付物提供依据
- 来源：由调研子代理检索产出，结论已尽量附原始 URL，但**未逐条人工复核**；版本号与定价会随时间变化，落地前需再确认一次

---

## 1. 框架选型

**结论：LangChain4j + Spring Boot 3.x（不要 Boot 4）**

| 方案 | 版本/状态 | 评价 |
|---|---|---|
| **LangChain4j** | 1.22.0（2026-10-07） | ✅ 推荐。纯 Java 库，不绑容器；tool calling、JSON Schema + `AiServices` 结构化输出、RAG/向量库覆盖最全、SSE 流式；OpenAI 兼容端点 / Ollama / DashScope / 智谱 / 千帆全支持 |
| Spring AI | 2.0.1 稳定，但建在 Spring Boot 4 / Framework 7 上；Boot 3.x 线用 1.1.5 | 可选。有官方 DeepSeek starter；生态正统，但版本线切换期略乱 |
| Google ADK for Java | 1.0.0（2026-03-30）→ 1.2.0 | 强项是多 agent 编排，偏 Gemini、RAG 弱。**建议作为第二阶段学习目标**，不做地基 |
| Quarkus LangChain4j | 与 LangChain4j 能力等同 | 能力没问题，但绑死 Quarkus 生态，与用户 Java 学习方向无关 |
| Semantic Kernel Java | **Java 分支已废弃** | 微软主线转 Agent Framework（仅 .NET/Python/Go）。**不要投入** |

文档：https://docs.langchain4j.dev/integrations/language-models/

## 2. 已知坑（必须提前设计规避）

- LangChain4j 发布节奏极快（1.11.11 → 1.22.0 仅约两个月）：**必须锁版本 + 用 BOM 统一管理**，否则升级即翻车。
  https://releasealert.dev/feeds/github/langchain4j/langchain4j
- Jackson / OkHttp 依赖冲突高发（Spring Boot 自带的版本与 LangChain4j 期望版本不一致）。
- 中文文档滞后一两个大版本，**以英文 docs + release notes 为准**。

## 3. 拍照 → 热量估算：可行性

**结论：走多模态大模型直出 JSON，放弃传统图像分类/检测。**

- 传统 CV 路线不成立的原因：中式混合菜、汤汁、勾芡、油盐用量无法从像素识别。
- 成本可忽略：DeepSeek 视觉模型单图最多 384 token，约 **0.001 元/图**（2026-08 定价：输入 1.5~3 元、输出 4.5~9 元每百万 token）。
- **准确度预期必须放低**：浙江同德医院实测同一张午餐照，三家 App 给出 1086 / 845 / 740 千卡；还会把红烧狮子头认成汉堡肉饼。
- 因此产品定位应为「**让我看见自己吃了什么**」，而不是「精确测量」。
- **硬性设计要求：UI 必须支持手工修正分量与条目**，否则错误估算会污染所有后续统计。

## 4. 境内 API vs 本地推理

- **主用境内 API**：DeepSeek 2026-05 永久降价（输入未命中缓存 3 元 / 输出 6 元每百万 token），自用月成本 < 1 元。
- **Ollama / llama.cpp 只做隐私兜底**：身材照这类敏感图片可切本地；但 7B 量化模型的中文与视觉能力明显弱于云端，不适合当主力。

## 5. 最小可行组合

- Spring Boot 3.x
- SQLite（单文件、免运维；H2 次选；PostgreSQL 对自用场景过度设计）
- 图片存本地文件系统 `/data/photos/{yyyy-MM}/`，数据库只存相对路径 + 哈希
- `java -jar` 单机部署；Docker 以后再加

---

## 关键决策点（三条，后续设计文档必须沿用）

1. **LangChain4j + Spring Boot 3.x** —— 明确避开 Spring Boot 4 生态。
2. **拍照热量只当估算，且必须可手工修正** —— 同图实测三家差 300+ 千卡。
3. **境内 API 为主 + Ollama 只做隐私兜底** —— 自用月成本不到 1 元。
