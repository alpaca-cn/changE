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
OUT = pathlib.Path(r"E:\devtools\cache\deck")
OUT.mkdir(parents=True, exist_ok=True)
PPTX = OUT / "changE-原型图.pptx"

BG = "#0b0e11"; SURFACE = "#11151a"; RAISED = "#171c22"; RAISED2 = "#1d242b"
LINE = "#222a32"; LINE_HI = "#2f3944"
TEXT = "#e9edf1"; DIM = "#7f8a95"; DIM2 = "#4d5762"
ACCENT = "#e0453a"; OK = "#3fb950"; WARN = "#d29922"
CJK = "Microsoft YaHei"; MONO = "Consolas"
W, H = 13.333, 7.5
TOTAL = 10

th = ThemeComposer().compose(style="dark-tech", seed=7)
th["colors"].update({
    "primary": ACCENT, "on-primary": "#ffffff", "secondary": DIM, "accent": ACCENT,
    "background": BG, "foreground": TEXT, "muted": SURFACE,
    "muted-foreground": DIM, "border": LINE, "destructive": ACCENT,
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
rect(s, 0, 0, W, 0.035, fill=ACCENT)
eyebrow(s, "CHANGE · 界面原型", 0.9, 0.78)
T(s, 0.9, 1.30, 5.2, 1.15, "changE", font_size=72, color=TEXT, bold=True, font_name=MONO)
T(s, 0.9, 2.52, 5.2, 0.5, "一个只属于你的训练记录本", font_size=21, color=TEXT, font_name=CJK)
rule(s, 0.9, 3.16)
multiline(s, 0.9, 3.44, 4.9, 1.5, [
    "本地优先 · 断网也能完整使用",
    "876 个动作 · 871 条中文分步做法",
    "17 块肌群 · 按本周训练量着色",
], font_size=12.5, color=DIM, font_name=CJK, line_spacing=1.95)
T(s, 0.9, 5.62, 4.9, 0.6,
  "这七张界面是 HTML 一比一渲染的，\n不是手绘示意图。", font_size=11, color=DIM2, font_name=CJK)

pic(s, MOCK / "03-bodymap.png", 6.05, 1.30, 6.85, 4.39)
T(s, 6.05, 5.80, 6.85, 0.3, "人体图 · 真实 SVG · 89 个区域 · 按本周组数着色",
  font_size=10, color=DIM2, font_name=CJK)
T(s, 6.05, 6.35, 6.85, 0.3, "v0.1 · 2026-10-20", font_size=10, color=DIM2, font_name=MONO)

# ═════════ S2 设计语言 ═════════
s = add_slide(prs)
bg(s)
eyebrow(s, "设计语言", 0.8, 0.55)
h1(s, "为什么现在显得「素」", 0.8, 0.86, 28)
body(s, "不是缺颜色，是缺层级：现在只有「底色 + 一条描边」，所有东西都在同一层。",
     0.8, 1.46, 11.5, size=12.5)
rule(s, 0.8, 1.86)

tokens = [
    ("--bg", BG, "页面底"),
    ("--surface", SURFACE, "卡片"),
    ("--raised", RAISED, "卡片里的块"),
    ("--line", LINE, "分隔线"),
    ("--line-hi", LINE_HI, "卡片顶边高光"),
    ("--accent", ACCENT, "数据与选中"),
    ("--ok", OK, "达标"),
    ("--warn", WARN, "提醒"),
]
T(s, 0.8, 2.10, 6, 0.28, "① 加三层表面 + 一条顶边高光，靠层级分层而不是靠描边",
  font_size=11.5, color=TEXT, bold=True, font_name=CJK)
for i, (nm, hexv, use) in enumerate(tokens):
    y = 2.50 + i * 0.545
    rrect(s, 0.82, y, 0.60, 0.36, fill=hexv, line=LINE_HI)
    T(s, 1.56, y + 0.00, 1.9, 0.22, nm, font_size=10, color=TEXT, font_name=MONO)
    T(s, 1.56, y + 0.19, 1.9, 0.18, hexv.upper(), font_size=8, color=DIM2, font_name=MONO)
    T(s, 3.38, y + 0.07, 2.6, 0.24, use, font_size=11.5, color=DIM, font_name=CJK)

rules = [
    ("01", "数字用等宽大字", "容量、组数、热量是这几页真正的主角。46pt 等宽数字 + 小号单位，眼睛先看到数，再看到字。"),
    ("02", "红色只给「数据」与「正在发生」", "不做装饰色。达标用绿、提醒用黄，红只出现在「这是你的数」和「这个被选中」。"),
    ("03", "每页至少一张微型图", "17 格热力条、环形进度、sparkline、分段条。图比文字更快回答「我这周怎么样」。"),
]
for i, (num, title, desc) in enumerate(rules):
    y = 2.10 + i * 1.60
    T(s, 6.95, y, 0.75, 0.4, num, font_size=21, color=ACCENT, bold=True, font_name=MONO)
    T(s, 7.70, y + 0.02, 4.9, 0.30, title, font_size=14.5, color=TEXT, bold=True, font_name=CJK)
    multiline(s, 7.70, y + 0.40, 4.8, 1.0, [desc], font_size=10.5, color=DIM,
              font_name=CJK, line_spacing=1.5)
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
