# 调研：Z-Anatomy 3D 人体图在 Tauri + Three.js 下的可行性

- 日期：2026-10-14
- 项目：change（个人自用健身记录客户端）
- 结论：**可行**。但必须换格式、必须合并几何、必须加 BVH，三者缺一不可。
- 证据来源：下载 Z-Anatomy 官方 web 构建产物，用 three r186 在 Node 中**真实解析 + 真实射线求交**（240 条射线均匀覆盖球面，240/240 命中）。可复跑脚本见 `za_bench/bench3.mjs`，实测模型见 `za_glb/*.glb`。

---

## 一、模型的真实数据（实测，非估算）

| 指标 | MuscularSystem | Regions of human body |
|---|---|---|
| 原始 FBX | 35.6 MB | 4.2 MB |
| **官方 GLB（Draco 压缩）** | **4.71 MB** | **0.74 MB** |
| 独立命名 mesh 节点 | **683** | 256 |
| 唯一三角形 | 128 万 | 13.2 万 |
| 每帧绘制三角形 | 232 万 | 13.5 万 |
| 顶点数 | 135 万 | — |
| 显存（位置+法线+索引） | ~32 MB | ~3.4 MB |
| 材质 / 贴图 | 1 / **0** | — |
| 模型高度 | 1.7（米制） | — |

**体积结论**：从 35.6 MB 的 FBX 压到 4.71 MB 的 GLB，**降到 1/7.5**。桌面客户端打包完全可接受。

**命名结论**：每块肌肉一个独立命名 mesh，名字是解剖学英文。面数中位数仅 1,472，最大的是 `Iliotibial tract`（48,612 面）和 `External intercostal muscles`（41,010 面）。

## 二、拾取：真正的瓶颈，已量化

| 方案 | 单次拾取耗时 | 相对 |
|---|---|---|
| three 原生 Raycaster | **14.96 ms** | 1×（≈一整个 60fps 帧，**完全不可用**） |
| + MeshBVH | **0.203 ms** | **74×** |
| 合并单几何 + BVH | **0.06 ms** | **250×** |

- BVH 一次性构建：683 节点 375 ms；合并单 mesh 1,119 ms（放加载阶段，可接受）
- faceIndex → 肌名二分查找：**0.02 µs/次**，实测精确命中（返回 `Serratus_posterior_superior_musclel`、`Hip_regionl`）
- Draco JS 解码耗时：334–366 ms

**结论：不需要 GPU picking。** 合并几何 + BVH + faceIndex 区间表即可。

## 三、性能边界

- **三角形数不是瓶颈**，128 万面对桌面 GPU 很轻松。
- **683 次 draw call 才是真实风险**，必须在渲染侧合并成单几何。
- 不使用骨骼动画，省掉一整套开销。

## 四、许可证（最大法律风险）

| 来源 | 许可 |
|---|---|
| BodyParts3D / DBCLS | **CC BY 4.0**（2025-02-27 起改为宽松许可） |
| Z-Anatomy 自身 | **CC BY-SA 4.0 — 带 ShareAlike 传染性** |
| 部分部件（内耳、肾脏） | CC-BY-NC-SA 4.0 / CC-BY-NC 4.0 — **不可商用** |

- **自用无碍**。
- **一旦分发**，Z-Anatomy 的 ShareAlike 生效，整个作品须以 CC BY-SA 分发；NC 部件还不能商用。
- **必须署名**（界面上可见）：
  - `BodyParts3D — The Database Center for Life Science — CC-BY-SA 2.1 Japan`
  - `Z-Anatomy — The open source atlas of anatomy — CC BY-SA 4.0`
- **发现一处许可不一致**：Zenodo 上的 Z-Anatomy 包是 130 MB 的 `.blend`，标注 **CC BY 4.0**；而 GitHub 仓库的 `License.txt` 写的是 **CC BY-SA 4.0**。两者冲突，上线前需以官方声明为准再核一次。

## 五、推荐落地管线

1. **FBX → GLB（Blender CLI 无头导出）** —— 必须用 Blender，它能保留 683 个 mesh 名；备选 `godotengine/FBX2glTF`（Facebook 原版已归档，Godot 分支在维护）
2. **Draco / meshopt 压缩到 ~5 MB**
3. **加载后合并为单几何**，同时建 faceIndex → 肌名区间表
4. **挂 MeshBVH**
5. **自绘 shader + uniform 查表**做肌群高亮与热力图着色
6. **Vue 组件封装**，对外只暴露 `volumeByMuscle` 与 `@muscle-click`

**不要做的事**：直接用 `FBXLoader` 加载 FBX；保留 683 个独立 mesh 渲染。

## 六、皮肤层的判断

Z-Anatomy 的 `Regions of human body` 是 **256 个"身体分区"**，语义上不是一层连续皮肤，而且 13.2 万面对做外壳偏重。

**建议**：让它承担「身体分区悬停」的语义；外观上的皮肤层**另配一个低模半透明外壳**。

## 七、工作量量级

**8–14 人日**：

| 环节 | 人日 |
|---|---|
| 模型处理管线（转换、压缩、验证） | 2–3 |
| 合并 + 拾取 + 名称映射 | 3–4 |
| 热力图着色 | 2 |
| Tauri 集成与性能调优 | 2–3 |

## 八、已有的 Web 先例

[caviri 的 Z-Anatomy 浏览器版](https://caviri.github.io/rete/anatomy.html) —— Three.js + GLTFLoader + DRACOLoader，模型正是 Z-Anatomy。证明这条路在浏览器里走得通。

## 九、两个最大的风险

1. **CC BY-SA 的 ShareAlike 传染性** —— 个人自用没问题，一想到分发就受限。
2. **683 draw call 若不合并，会吃掉整个帧预算。**
