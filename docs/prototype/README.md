# changE · 界面原型

> 10 页 PPT。7 张界面是 **HTML 一比一渲染**出来的（真实 SVG、真实排版、真实配色），
> 不是手绘示意图 —— 所以它可信到可以直接当「目标形态」来对。

| 文件 | 是什么 |
|---|---|
| `changE-原型图.pptx` | 交付物。16:9，10 页 |
| `changE-原型图.pdf` | 同上的 PDF（给不想开 PowerPoint 的场合） |
| `renders/slide01..10.png` | 每页的渲染图，用于快速浏览与 review |
| `src/` | 生成源码（可复现） |

## 一眼看结论

现在的界面「素」，不是缺颜色，是**缺层级**：

- 只有 `--bg` + 一条 1px 描边，所有东西都在同一层
- 数字与正文同字号，眼睛不知道先看哪里
- 红色只出现在选中态，没被用来表达数据

原型加了四样东西：**三级表面**（bg / surface / raised + 顶部高光）、**等宽大字号数字**
（46pt tabular）、**红色只给数据**、**每页至少一张微型图**（17 格热力条 / 环形 / sparkline / 分段条）。

## 怎么重新生成

```powershell
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

所以**改了 mockup 的 PNG 之后重建，deck 里用的还是旧图**——而且不报错，只是图没变。
我在这一轮就撞了：清了缓存之后 `image5` 从 64,209 B 变成 70,439 B，`slide06.png` 才真正更新。

**重建前的第一件事**：

```powershell
Remove-Item "$env:TEMP\ppt-cropped" -Recurse -Force -ErrorAction SilentlyContinue
```

## 页面清单

| 页 | 内容 |
|---|---|
| 1 | 封面 |
| 2 | 设计语言：为什么素 + 8 个 token + 三条规则 |
| 3 | 今日 |
| 4 | 训练记录（含「看做法」抽屉） |
| 5 | 人体图 |
| 6 | 进步曲线 |
| 7 | 身体数据 |
| 8 | 饮食记录 |
| 9 | 智能体对话 |
| 10 | 落地：三步 + 每步怎么验 |

## 与现状的距离

七个界面里**五个已经能在这个 exe 里点到**：今日 / 训练记录 / 人体图 / 进步曲线 / 身体数据 /
饮食记录（含已实现的部分）。差别在「表面层级、大字号数字、微型图」这三样视觉层。

**智能体对话还没有**（计划 5）。那一页是目标形态，不是现状。

人体图的着色要**先录一次训练**才看得见——空库时 17 块肌群全是灰的，那是正确的。
