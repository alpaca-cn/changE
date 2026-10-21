"""构建「changE · 界面原型」PPT（修订版）。

上一版的问题：
- 封面的三张预告图被裁掉了 0.5 英寸（超出页面）
- 设计语言页的第一个色板 --bg 与页底同色，看不见；且色板没有描边
- 七个界面页的右栏只写了 3 条说明，下半截空着
"""
import pathlib

from pptx_designer import Presentation, validate_resolved_theme
from pptx_designer.renderer.theme import ThemeComposer
from pptx_designer.tools.layout import add_slide, page_number
from pptx_designer.tools.shapes import rect, rrect
from pptx_designer.tools.text import text as T, multiline
from pptx_designer.tools.images import cover_image

MOCK = pathlib.Path(r"E:\devtools\cache\ui_mock")
ASSETS = pathlib.Path(r"E:\idea\program\change\docs\prototype\assets")
OUT = pathlib.Path(r"E:\devtools\cache\deck")
OUT.mkdir(parents=True, exist_ok=True)
PPTX = OUT / "changE-原型图.pptx"

BG = "#e8e3d7"; SURFACE = "#ffffff"; RAISED = "#f8f6f0"; RAISED2 = "#efece3"
LINE = "#e3dfd5"; LINE_HI = "#cfc9bb"
TEXT = "#1a1a1a"; DIM = "#6b6559"; DIM2 = "#9c968a"
ACCENT = "#b5893a"; OK = "#6c8a4a"; WARN = "#a8792c"
PILL = "#1a1a1a"; PILL_INK = "#f6f4ef"
INK = "#161412"
CJK = "Microsoft YaHei"; MONO = "Consolas"
W, H = 13.333, 7.5
TOTAL = 10

th = ThemeComposer().compose(style="dark-tech", seed=7)
th["colors"].update({
    "primary": ACCENT, "on-primary": "#ffffff", "secondary": DIM, "accent": ACCENT,
    "background": BG, "foreground": TEXT, "muted": SURFACE,
    "muted-foreground": DIM, "border": LINE, "destructive": ACCENT,
})
th["semantic_roles"].update({
    "background": BG, "surface": SURFACE, "ink": TEXT, "muted": DIM,
    "accent": ACCENT, "accent-secondary": DIM, "success": OK, "warning": WARN,
    "danger": ACCENT, "border": LINE,
    "data-series-1": ACCENT, "data-series-2": DIM,
})
validate_resolved_theme(th)
prs = Presentation(theme=th, strict_theme=True)


def bg(slide):
    rect(slide, 0, 0, W, H, fill=BG)


def pic(slide, path, left, top, width, height):
    rrect(slide, left - 0.035, top - 0.035, width + 0.07, height + 0.07, fill=LINE_HI)
    cover_image(slide, left, top, width, height, str(path))


def eyebrow(slide, s, left, top):
    T(slide, left, top, 9, 0.26, s, font_size=9.5, color=DIM2, font_name=CJK)


def h1(slide, s, left, top, size=30):
    T(slide, left, top, W - left - 0.6, 0.55, s, font_size=size, color=TEXT,
      bold=True, font_name=CJK)


def body(slide, s, left, top, width, size=12, color=DIM):
    T(slide, left, top, width, 0.5, s, font_size=size, color=color, font_name=CJK)


def rule(slide, left, top, width=1.4, color=ACCENT):
    rect(slide, left, top, width, 0.035, fill=color)


# ═════════ S1 封面（不裁切：左边字，右边一张大图）═════════
s = add_slide(prs)
bg(s)
rect(s, 0, 0, 7.94, 0.035, fill=ACCENT)
eyebrow(s, "CHANGE · 界面原型", 0.9, 0.78)
T(s, 0.9, 1.30, 5.2, 1.15, "changE", font_size=72, color=TEXT, bold=True, font_name=MONO)
T(s, 0.9, 2.52, 5.2, 0.5, "一个只属于你的训练记录本", font_size=21, color=TEXT, font_name=CJK)
rule(s, 0.9, 3.16)
multiline(s, 0.9, 3.44, 4.9, 1.5, [
    "本地优先 · 断网也能完整使用",
    "876 个动作 · 871 条中文分步做法",
    "17 块肌群 · 按本周训练量着色",
], font_size=12.5, color=DIM, font_name=CJK, line_spacing=1.95)
T(s, 0.9, 4.55, 4.9, 0.6,
  "风格参考 LZheng0411/Lzheng-fitness 的 workbench-template。",
  font_size=10, color=DIM2, font_name=CJK)

cover_image(s, 7.94, 0, 5.39, 7.5, str(ASSETS / "oil-painting.jpg"))
T(s, 0.9, 5.30, 4.9, 0.9,
  "背景不再用纯黑 —— 改成暖米白，深色只留在一块 hero 里。\n人物图与油画来自你自己的素材。",
  font_size=10.5, color=DIM2, font_name=CJK)
T(s, 0.9, 6.55, 4.9, 0.3, "v0.2 · 2026-10-21", font_size=10, color=DIM2, font_name=MONO)

# ═════════ S2 设计语言 ═════════
s = add_slide(prs)
bg(s)
eyebrow(s, "DESIGN LANGUAGE", 0.8, 0.55)
h1(s, "从压抑到稳重", 0.8, 0.86, 28)
body(s, "上一版全黑，看着压抑。这一版换成暖米白：深色只留一块 hero，其余靠发丝线与留白分层。",
     0.8, 1.46, 11.5, size=12.5)
rule(s, 0.8, 1.86)

tokens = [
    ("--bg", "#f4f2ed", "页面底（暖米白）"),
    ("--panel", "#efece3", "侧栏"),
    ("--card", SURFACE, "卡片"),
    ("--raise", RAISED, "卡内块"),
    ("--line", LINE, "1px 发丝线"),
    ("--ink", TEXT, "正文"),
    ("--muted", DIM, "次要文字"),
    ("--faint", DIM2, "标注"),
    ("--accent", ACCENT, "黄铜：数据与选中"),
    ("--pill", INK, "导航选中（反色深底）"),
    ("--hero", INK, "深色 hero 块"),
]
T(s, 0.8, 2.02, 6, 0.26, "① 暖米白打底，深色只留一块 hero，墨色分层",
  font_size=11.5, color=TEXT, bold=True, font_name=CJK)
for i, (nm, hexv, use) in enumerate(tokens):
    y = 2.32 + i * 0.435
    rrect(s, 0.82, y, 0.54, 0.30, fill=hexv, line=LINE_HI)
    T(s, 1.50, y - 0.01, 1.9, 0.19, nm, font_size=9.5, color=TEXT, font_name=MONO)
    T(s, 1.50, y + 0.15, 1.9, 0.15, hexv.upper(), font_size=7.5, color=DIM2, font_name=MONO)
    T(s, 3.26, y + 0.04, 2.7, 0.21, use, font_size=10.5, color=DIM, font_name=CJK)

rules = [
    ("01", "先解决「压抑」", "纯黑 + 高饱和红会让界面像监控室。换成暖米白，文字用墨色，深色只保留一块 hero 用来压住画面。"),
    ("02", "编号导航 + 小字母英文", "01 今日 / TODAY。选中项是反色深底药丸，不是加粗；左边一条黄铜竖条。"),
    ("03", "一个色只做一件事", "黄铜只表示「这是你的数」和「这里被选中」。其余用墨色与暖灰分级。"),
]
for i, (num, title, desc) in enumerate(rules):
    y = 2.02 + i * 1.58
    T(s, 6.95, y, 0.75, 0.4, num, font_size=20, color=ACCENT, bold=True, font_name=MONO)
    T(s, 7.70, y + 0.01, 4.9, 0.30, title, font_size=14, color=TEXT, bold=True, font_name=CJK)
    multiline(s, 7.70, y + 0.38, 4.8, 1.0, [desc], font_size=10.5, color=DIM,
              font_name=CJK, line_spacing=1.55)
page_number(s, 2, TOTAL)

# ═════════ S3–S9 七个界面 ═════════
SCREENS = [
    ("01", "今日", "一张卡回答「这周练够了吗」，另一张回答「今天吃够了吗」。", "01-today",
     ["17 格热力条 = 17 块肌群，红得越深本周练得越多",
      "容量同时给「16 组」与「折合 42.5 组」两个口径",
      "最近训练与本周小结共用一个卡，不留空白"],
     "首页现在只有一张 VolumePanel（17 行文字），热力条与小结条都还没有。"),
    ("02", "训练记录", "选肌群 → 选动作 → 填组。点「看做法」开抽屉，不离开这一页。", "02-training",
     ["抽屉里的分步做法用序号圆点，段数就是步骤数",
      "已填的组在抽屉打开时原样保留",
      "两个搜索链接可复制，不在应用里播视频"],
     "抽屉已经实现了。缺的是「上次成绩」提示与按使用频率排序。"),
    ("03", "人体图", "训练均衡度仪表盘：哪块练多了、哪块漏了，一眼看出来。", "03-bodymap",
     ["正面 + 背面，89 个区域真实 SVG",
      "点一块 → 右侧给该肌群的容量与动作",
      "三种口径：本周组数 / 恢复中 / 长期未练"],
     "已经实现了。缺的是把 17 格热力条也用上（首页还没用），以及「恢复中」的加权口径。"),
    ("04", "进步曲线", "同一个动作三条曲线分开画 —— 量纲不同，叠在一起会互相压平。", "04-progress",
     ["估算 1RM / 最大重量 / 总吨位，各一张小图",
      "每张带大数字与六周变化",
      "只算 12 次以内的组，算不出的日子折线断开"],
     "三条曲线已经实现了。缺的是大数字与「六周变化」那句话。"),
    ("05", "身体数据", "九项指标，每周记一两次就够。没记过显示「—」，不是 0。", "05-body",
     ["九张当前值卡 + 时间线，一屏看完",
      "变化用 ▲▼ 与颜色，持平用「—」",
      "录入只管「值 + 日期 + 备注」，单位按类型自动"],
     "当前值卡与时间线已经实现了。缺的是与「上一次」比较算出来的 ▲▼。"),
    ("06", "饮食记录", "记个大概就行 —— 精度不是目标，可修正是。", "06-diet",
     ["今日总热量 + 三段宏量条",
      "按餐次分组，没记的餐留一行「还没记」",
      "数值是手工近似，界面里不假装权威"],
     "总量与餐次已经实现了。缺的是三段宏量条，以及「没记的那一餐」也要占一行。"),
    ("07", "智能体对话", "说一句就记下来。写操作一律先给摘要，确认了才落库。", "07-agent",
     ["工具调用单独一张卡：准备写入 / 已写入",
      "确认前能看到每一行将写入什么",
      "拍照与语音留了入口（下一阶段接）"],
     "这一页还没有（计划 5）。它是目标形态，不是现状。"),
]

for idx, (num, name, sub, img, notes, gap) in enumerate(SCREENS):
    s = add_slide(prs)
    bg(s)
    eyebrow(s, f"界面原型 · {num} / 07", 0.42, 0.28)
    h1(s, name, 0.42, 0.52, 27)
    body(s, sub, 0.42, 1.04, 9.4, size=11)
    pic(s, MOCK / f"{img}.png", 0.42, 1.40, 9.32, 5.97)

    T(s, 10.15, 1.46, 2.8, 0.24, "这一页在解决什么", font_size=9.5, color=DIM2, font_name=CJK)
    rule(s, 10.15, 1.76, 0.9, LINE_HI)
    for j, n in enumerate(notes):
        y = 1.96 + j * 1.12
        T(s, 10.15, y, 0.36, 0.24, f"{j+1}", font_size=12, color=ACCENT, bold=True, font_name=MONO)
        multiline(s, 10.15, y + 0.28, 2.75, 0.80, [n], font_size=10, color=DIM,
                  font_name=CJK, line_spacing=1.45)

    gy = 5.42
    rule(s, 10.15, gy, 2.75, LINE)
    T(s, 10.15, gy + 0.14, 2.8, 0.24, "离这个原型还差什么", font_size=9.5, color=DIM2,
      font_name=CJK)
    multiline(s, 10.15, gy + 0.46, 2.75, 1.3, [gap], font_size=10, color=TEXT,
              font_name=CJK, line_spacing=1.5)
    page_number(s, idx + 3, TOTAL)

# ═════════ S10 收尾 ═════════
s = add_slide(prs)
bg(s)
eyebrow(s, "落地", 0.8, 0.55)
h1(s, "这套语言怎么落地", 0.8, 0.86, 28)
body(s, "不需要重写界面。三步，每步都能单独验、单独回退。", 0.8, 1.46, 8.6, size=12.5)
rule(s, 0.8, 1.86)

steps = [
    ("第一步 · 加层级", ACCENT,
     ["theme.css 补 --surface / --raised / --line-hi",
      "所有卡片换成三级表面 + 顶部 1px 高光",
      "不动布局，只换表面"]),
    ("第二步 · 让数字变大", OK,
     ["抽一个 <Stat>：46pt 等宽数字 + 15pt 单位",
      "首页的容量、饮食的热量先换",
      "改变「一眼先看到什么」"]),
    ("第三步 · 加微型图", WARN,
     ["17 格热力条（数据已在 /api/volume/weekly）",
      "三段宏量条、sparkline",
      "每加一张图都要有对应空态"]),
]
for i, (title, color, items) in enumerate(steps):
    x = 0.8 + i * 4.05
    rrect(s, x, 2.10, 3.78, 2.05, fill=SURFACE)
    rect(s, x, 2.10, 3.78, 0.045, fill=color)
    T(s, x + 0.30, 2.34, 3.2, 0.30, title, font_size=14, color=TEXT, bold=True, font_name=CJK)
    multiline(s, x + 0.30, 2.78, 3.18, 1.2, ["· " + it for it in items], font_size=10.5,
              color=DIM, font_name=CJK, line_spacing=1.85)

# 每步怎么验
T(s, 0.8, 4.42, 6, 0.26, "每步怎么验", font_size=9.5, color=DIM2, font_name=CJK)
rule(s, 0.8, 4.72, 11.7, LINE)
checks = [
    ("打开今日页，卡片之间能看出层级差；改动里 grep 不到新的 #hex 字面量。", ACCENT),
    ("首页第一眼看到的是数字而不是标题；远看仍读得出「16 组」。", OK),
    ("录一次训练后热力条有颜色；0 组时是灰的，而不是从图上消失。", WARN),
]
for i, (txt, color) in enumerate(checks):
    x = 0.8 + i * 4.05
    T(s, x, 4.90, 3.5, 0.05, "", font_size=9, color=color, font_name=CJK)
    multiline(s, x, 4.90, 3.60, 0.9, [txt], font_size=10.5, color=TEXT,
              font_name=CJK, line_spacing=1.6)

T(s, 0.8, 6.08, 11.7, 0.3,
  "七张界面里五张已经能在这个 exe 里点到；人体图的着色要先录一次训练才看得见。",
  font_size=11, color=TEXT, font_name=CJK)
multiline(s, 0.8, 6.52, 11.7, 0.6, [
    "本 deck 的 7 张界面是 HTML 一比一渲染（真实 SVG、真实排版），不是手绘示意图；幻灯片自身的文字与色板是 PPT 原生对象。"
], font_size=9, color=DIM2, font_name=CJK, line_spacing=1.5)
page_number(s, TOTAL, TOTAL)

prs.save(str(PPTX))
print(f"已生成: {PPTX}  页数 {len(prs.slides)}")
