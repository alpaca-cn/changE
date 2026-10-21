# changE · 界面原型

> 10 页 PPT。7 张界面是 **HTML 一比一渲染**出来的（真实 SVG、真实排版、真实配色），
> 不是手绘示意图 —— 所以它可信到可以直接当「目标形态」来对。
>
> **视觉方向参考**：[LZheng0411/Lzheng-fitness](https://github.com/LZheng0411/Lzheng-fitness)
> 的 `skills/lzheng-fitness-workbench-builder/assets/workbench-template.html`。

| 文件 | 是什么 |
|---|---|
| `changE-原型图.pptx` | 交付物。16:9，10 页 |
| `changE-原型图.pdf` | 同上的 PDF |
| `renders/slide01..10.png` | 每页的渲染图，用于快速浏览与 review |
| `src/` | 生成源码（可复现） |

## 设计语言（从参考项目里抽出来的真实约定，不是猜的）

```css
--bg:#0b0c0b; --panel:#0e0f0e; --card:#131413; --raise:#1a1b19;
--ink:#f2f2ef; --muted:rgba(255,255,255,.46); --faint:rgba(255,255,255,.28);
--line:rgba(255,255,255,.10); --accent:#d7ff3f; --pill:#f1f1ee;
--r:16px; --r-sm:10px;
```

四条规则：

1. **近单色 + 一个柠檬绿**。层级靠 1px 发丝线与留白，不靠颜色。
2. **编号导航 + 小字母英文**：`01 今日 / TODAY`。数字等宽、英文 8px 字距 `.15em`。
   选中项是**反色浅底药丸**（`#f1f1ee` 上一个深字），不是加粗。
3. **字重 500，不是 700**。标题 650、数字 500 且 `tabular-nums`。粗体让界面显得吵。
4. **一个色只做一件事**：柠檬绿只表示「这是你的数」和「这里被选中」。

对比上一版（红色仪表盘）：

| | 上一版 | 这一版 |
|---|---|---|
| 底色 | 深黑 + 红 `#e0453a` | 近单色 + 柠檬绿 `#d7ff3f` |
| 层级 | 卡片 + 阴影 + 三级表面 | 1px 发丝线 + 大圆角，几乎无阴影 |
| 字重 | 粗体标题 | 500 / 650 |
| 导航 | 图标 + 中文 | 编号 `01..08` + 中文 + 8px 小字母 |
| 标题 | 大号无衬线 | 衬线（`Songti SC`）编辑感 |

## 怎么重新生成

```powershell
# 0. 必须先清裁图缓存，否则 deck 里用的还是旧图（见下）
Remove-Item "$env:TEMP\ppt-cropped" -Recurse -Force -ErrorAction SilentlyContinue

# 1. 生成 7 张界面的 HTML（会从 vendored 素材里抽真实 SVG path）
python docs/prototype/src/gen-mockups.py

# 2. 用 Edge headless 截图（每张 1280x820）
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
foreach ($f in Get-ChildItem <mockup 输出目录> -Filter *.html) {
  & $edge --headless=new --disable-gpu --hide-scrollbars --no-first-run `
    "--window-size=1280,820" "--screenshot=$($f.FullName -replace '\.html$','.png')" `
    "file:///$($f.FullName -replace '\\','/')"
}

# 3. 建 deck
python docs/prototype/src/build-deck.py

# 4. 渲染成 PNG 复核
powershell -File <skill>/scripts/render_pptx.ps1 -InFile changE-原型图.pptx -OutDir render
```

### ⚠️ 一个会让改动「看起来不生效」的陷阱

`pptx_designer` 的 `cover_image()` 会把裁好的图缓存到
`%TEMP%\ppt-cropped\<md5(路径+尺寸)>.png`，而且**只写一次、永不失效**：

```python
if not os.path.exists(cropped_path):
    cropped.save(cropped_path, "PNG")
```

所以**改了 mockup 的 PNG 之后重建，deck 里用的还是旧图**——不报错，只是图没变。
我撞过一次：清缓存之后 `image5` 从 64,209 B 变成 70,439 B，`slide06` 才真正更新。

## 页面清单

| 页 | 内容 |
|---|---|
| 1 | 封面（右侧是人体图实渲染） |
| 2 | 设计语言：10 个 token + 四条规则 |
| 3 | 今日（环形 72% + 17 格热力条 + 餐次） |
| 4 | 训练记录（含「看做法」抽屉） |
| 5 | 人体图（柠檬绿热力，真实 89 条 path） |
| 6 | 进步曲线（三张 sparkline） |
| 7 | 身体数据 |
| 8 | 饮食记录 |
| 9 | 智能体对话 |
| 10 | 落地：三步 + 每步怎么验 |

## 与现状的距离

七个界面里**五个已经能在这个 exe 里点到**。差别全在视觉层：发丝线、编号导航、
字重、柠檬绿、环形与 sparkline。

**智能体对话还没有**（计划 5）。那一页是目标形态，不是现状。

人体图的着色要**先录一次训练**才看得见——空库时 17 块肌群全是灰的，那是正确的。
