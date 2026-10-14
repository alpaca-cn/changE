# -*- coding: utf-8 -*-
"""changE 设计定稿演示 —— 18 页 Build Mode 生成脚本。

Theme Lock v1：深色厚重（近黑 #0D0F12 + 唯一暖红 #E0453A + 1px 细线 + 无阴影）
字体：Microsoft YaHei（本机已确认 msyh.ttc / msyhbd.ttc 存在）
"""
import os
from pptx_designer import Presentation, validate_resolved_theme
from pptx_designer.renderer.theme import ThemeComposer
from pptx_designer.tools.layout import add_slide, clean_save
from pptx_designer.tools.shapes import rect, rrect, arrow
from pptx_designer.tools.text import text as _text

# ── 设计令牌 ──────────────────────────────────────────────────
C = {
    "bg": "#0D0F12", "panel": "#14171B", "panel2": "#1A1E23",
    "line": "#262B31", "accent": "#E0453A", "accent_dim": "#8F2C1E",
    "fg": "#ECEEF1", "muted": "#8B939D", "faint": "#6B7480",
}
FONT = "Microsoft YaHei"
SW, SH = 13.333, 7.5
ML, MR = 0.75, 0.75
CW = SW - ML - MR          # 11.833
T_TITLE, T_SUB, T_RULE = 0.60, 1.20, 1.76
T_BODY, T_FOOT_RULE, T_FOOT = 2.05, 6.34, 6.48

theme = ThemeComposer().compose(style="dark-tech", seed=17)
theme["colors"].update({
    "primary": C["accent"], "on-primary": "#FFFFFF",
    "secondary": C["muted"], "accent": C["accent"],
    "background": C["bg"], "foreground": C["fg"],
    "muted": C["panel"], "muted-foreground": C["muted"],
    "border": C["line"], "destructive": C["accent_dim"],
})
theme["typography"] = {"heading": FONT, "body": FONT}
validate_resolved_theme(theme)
prs = Presentation(theme=theme, strict_theme=True)

TOTAL = 18
_n = [0]


# ── 基础工具 ──────────────────────────────────────────────────
def put(slide, l, t, w, h, txt, size=15, color=None, bold=False,
        align="left", anchor="top"):
    return _text(slide, l, t, w, h, txt, font_size=size,
                 color=color or C["fg"], bold=bold, align=align,
                 font_name=FONT, anchor=anchor)


def blank():
    s = add_slide(prs)
    rect(s, 0, 0, SW, SH, C["bg"])
    return s


def page(title, sub="", num=True):
    """标准内容页骨架。返回 slide。"""
    _n[0] += 1
    s = blank()
    rect(s, 0, 0, SW, 0.055, C["accent"])
    if title:
        put(s, ML, T_TITLE, CW, 0.5, title, size=28, bold=True)
    if sub:
        put(s, ML, T_SUB, CW, 0.36, sub, size=13, color=C["muted"])
    rect(s, ML, T_RULE, CW, 0.011, C["line"])
    if num:
        rect(s, ML, T_FOOT_RULE, CW, 0.011, C["line"])
        put(s, SW - MR - 1.3, T_FOOT, 1.3, 0.3, f"{_n[0]:02d} / {TOTAL}",
            size=10, color=C["faint"], align="right")
    return s


def rule(slide, y, x=None, w=None, color=None):
    rect(slide, x or ML, y, w or CW, 0.009, color or C["line"])


def eyebrow(slide, l, t, txt, w=6.0, color=None):
    put(slide, l, t, w, 0.26, txt.upper(), size=10,
        color=color or C["faint"])


def stat(slide, l, t, w, number, label, note="", num_size=40):
    put(slide, l, t, w, 0.62, number, size=num_size, bold=True, color=C["accent"])
    put(slide, l, t + 0.66, w, 0.3, label, size=13, color=C["fg"])
    if note:
        put(slide, l, t + 1.0, w, 0.5, note, size=11, color=C["muted"])


def chain(slide, steps, y, h=0.62, size=12, gap=0.30, highlight=()):
    """横向流程链：步骤之间用 ▸ 连接。steps 为字符串列表。"""
    n = len(steps)
    aw = gap
    bw = (CW - aw * (n - 1)) / n
    x = ML
    for i, st in enumerate(steps):
        fill = C["panel2"] if i in highlight else C["panel"]
        line = C["accent"] if i in highlight else C["line"]
        rrect(slide, x, y, bw, h, fill, line)
        put(slide, x + 0.12, y, bw - 0.24, h, st, size=size,
            align="center", anchor="middle",
            color=C["fg"] if i in highlight else C["muted"])
        if i < n - 1:
            put(slide, x + bw, y, aw, h, "▸", size=12,
                color=C["faint"], align="center", anchor="middle")
        x += bw + aw


# ══ 01 封面 ═══════════════════════════════════════════════════
s = blank()
_n[0] = 1
rect(s, 0, 0, SW, 0.055, C["accent"])
put(s, ML, 2.05, 11.0, 1.5, "changE", size=78, bold=True)
rect(s, ML + 0.04, 3.72, 1.45, 0.045, C["accent"])
put(s, ML, 4.02, 10.5, 0.6, "一个只服务一个人的健身记录客户端", size=21, color=C["muted"])

# 右侧三条关键数字，给封面一个支点
bx = 8.55
for i, (num, lab) in enumerate([("12 MB", "Tauri 打包产物"),
                                ("0.06 ms", "3D 拾取耗时"),
                                ("15", "张表的数据模型")]):
    yy = 2.35 + i * 1.15
    put(s, bx, yy, 4.0, 0.5, num, size=26, bold=True, color=C["accent"])
    put(s, bx, yy + 0.46, 4.0, 0.3, lab, size=12, color=C["muted"])
rect(s, bx - 0.28, 2.28, 0.011, 3.32, C["line"])

rect(s, ML, 6.34, CW, 0.011, C["line"])
put(s, ML, 6.48, 9.0, 0.3, "设计定稿  ·  2026-10-14  ·  六条决定  ·  四条主流程  ·  三份实测",
    size=11, color=C["faint"])
put(s, SW - MR - 1.3, 6.48, 1.3, 0.3, "01 / 18", size=10,
    color=C["faint"], align="right")

# ══ 02 问题 ═══════════════════════════════════════════════════
s = page("商业健身 App 的槽点，根源只有一个",
         "不是功能缺失，是「要同时服务几百万人」这件事本身带来的")
items = [
    ("免费版限计划数", "Strong 免费版只给 3 个 routine"),
    ("广告与会员墙", "Keep、训记的抱怨集中在会员墙"),
    ("数据导出要收费", "MyFitnessPal 导出仅 Premium"),
    ("食物库偏西式", "中式家常菜与份量换算基本不可用"),
    ("计划是静态模板", "不会按你的历史递进"),
]
y = T_BODY + 0.06
for name, note in items:
    put(s, ML, y, 3.1, 0.34, name, size=15, bold=True)
    put(s, ML + 3.25, y, 4.6, 0.34, note, size=13, color=C["muted"])
    y += 0.62
    rule(s, y - 0.17, ML, 7.9)

# 右侧归因
rx = 8.9
rect(s, rx - 0.42, T_BODY, 0.011, 3.4, C["line"])
eyebrow(s, rx, T_BODY + 0.02, "归因")
put(s, rx, T_BODY + 0.42, 3.75, 2.6,
    "订阅制要求功能可被切碎出售，\n所以分析、导出、批量录入都进了付费墙。\n\n"
    "多租户要求照顾所有人体型，\n所以食物库只能往「大而泛」做。\n\n"
    "两条都不是技术限制，\n而是商业模式限制。",
    size=13, color=C["muted"])
# 左下补一条结论，平衡版面
rect(s, ML, 5.28, 7.9, 0.011, C["line"])
put(s, ML, 5.50, 7.9, 0.9,
    "结论：这些槽点无法靠「做得更好」解决，因为它们是商业模式的必然产物。\n"
    "change 的立论正是从这里出发——把「多租户」这个前提整个去掉。",
    size=12, color=C["faint"])

# ══ 03 立论 ═══════════════════════════════════════════════════
s = page("只有一个用户，所以可以这样设计", "本项目所有取舍都服从这一条")
put(s, ML, T_BODY + 0.15, 11.8, 1.0,
    "不需要账号体系，不需要权限模型，不需要计费，\n不需要为「照顾所有人体型」而牺牲精度。",
    size=25, bold=True)
rule(s, T_BODY + 1.55)
cards = [
    ("不受商业模式约束", "分析、导出、批量录入都可以直接给，\n没有「要不要放进付费墙」这个问题。"),
    ("数据完全属于自己", "SQLite 单文件 + 本地图片目录，\n备份就是把一个文件夹复制走。"),
    ("智能体可以直接改数据", "商业 App 不敢让 AI 写用户数据，\n因为它要对几百万人的数据负责。"),
    ("精度可以为一个人调", "食物库只补你常吃的那 20–50 条，\n不用管别人吃不吃。"),
]
cx = ML
cw2 = (CW - 3 * 0.30) / 4
for i, (t, d) in enumerate(cards):
    put(s, cx, T_BODY + 1.95, cw2, 0.4, f"0{i+1}", size=13, bold=True, color=C["accent"])
    put(s, cx, T_BODY + 2.38, cw2, 0.4, t, size=14, bold=True)
    put(s, cx, T_BODY + 2.85, cw2, 1.2, d, size=11.5, color=C["muted"])
    if i < 3:
        rect(s, cx + cw2 + 0.15, T_BODY + 1.9, 0.009, 2.05, C["line"])
    cx += cw2 + 0.30

# ══ 04 六个决定总览 ═══════════════════════════════════════════
s = page("六个核心决定", "后面逐条展开，每条都给依据")
rows = [
    ("01", "产品形态", "Tauri 2 桌面客户端", "安装包 12 MB，与参考项目同构"),
    ("02", "「打卡」", "训练日志，不是出勤打勾", "目标值与实际值在同一条组记录上"),
    ("03", "饮食记录", "营养按每 100 克存", "热量永远实时算，绝不存绝对热量"),
    ("04", "智能体", "可读也能写（tool calling）", "只增改不删除，每次改动可回退"),
    ("05", "人体图", "3D，排第二阶段", "拾取实测 14.96 ms → 0.06 ms"),
    ("06", "动作库", "三家各取所长", "876 条，Unlicense 公有领域"),
]
y = T_BODY + 0.12
for num, label, title, note in rows:
    put(s, ML, y, 0.6, 0.34, num, size=13, color=C["accent"], bold=True)
    put(s, ML + 0.68, y, 1.5, 0.34, label, size=13, color=C["muted"])
    put(s, ML + 2.42, y, 3.7, 0.34, title, size=15, bold=True)
    put(s, ML + 6.25, y, 5.5, 0.34, note, size=12.5, color=C["muted"])
    y += 0.63
    if num != "06":
        rule(s, y - 0.17)

# ══ 05 决定 1 · 形态 ══════════════════════════════════════════
s = page("决定 1 · 产品形态 = 桌面客户端",
         "参考物是「B站工具箱」：把其中的 Python 后台换成 Java，其余层照搬")
# 左：窗口示意
wx, wy, ww, wh = ML, T_BODY + 0.15, 5.5, 3.55
rrect(s, wx, wy, ww, wh, C["panel"], C["line"])
rect(s, wx, wy, ww, 0.26, C["panel2"])
for i, c in enumerate(("#FF5F57", "#FEBC2E", "#28C840")):
    rrect(s, wx + 0.14 + i * 0.17, wy + 0.085, 0.10, 0.09, c)
put(s, wx + 0.72, wy + 0.02, 3.0, 0.22, "changE — 训练打卡", size=8, color=C["faint"])
# 侧栏
rect(s, wx, wy + 0.26, 1.15, wh - 0.26, C["panel2"])
for i, lab in enumerate(("今日", "训练", "饮食", "人体图", "智能体")):
    yy = wy + 0.44 + i * 0.30
    if i == 0:
        rrect(s, wx + 0.09, yy - 0.045, 0.97, 0.24, C["accent_dim"])
    put(s, wx + 0.17, yy - 0.03, 0.9, 0.22, lab, size=8,
        color=C["fg"] if i == 0 else C["faint"])
# 内容区
put(s, wx + 1.32, wy + 0.44, 3.9, 0.3, "今天", size=14, bold=True)
for i in range(3):
    rrect(s, wx + 1.32 + i * 1.30, wy + 0.86, 1.18, 1.05, C["panel2"], C["line"])
    put(s, wx + 1.42 + i * 1.30, wy + 0.96, 1.0, 0.22,
        ("今日训练", "今日饮食", "本周容量")[i], size=8, color=C["faint"])
rrect(s, wx + 1.32, wy + 2.06, 3.86, 0.42, C["accent"])
put(s, wx + 1.32, wy + 2.06, 3.86, 0.42, "开始训练", size=11,
    align="center", anchor="middle", color="#FFFFFF")
rrect(s, wx + 1.32, wy + 2.60, 3.86, 0.42, C["panel2"], C["line"])
put(s, wx + 1.32, wy + 2.60, 3.86, 0.42, "从人体图挑肌群", size=10,
    align="center", anchor="middle", color=C["muted"])

# 右：映射表
rx = 6.85
eyebrow(s, rx, T_BODY + 0.02, "参考项目 → change")
mapping = [
    ("界面", "Vue 3 + TypeScript", "照搬"),
    ("前端配套", "Vite / Pinia / Router", "照搬"),
    ("桌面壳", "Tauri 2 + Rust", "照搬"),
    ("业务后台", "Python → Java", "换掉"),
    ("前后端通信", "stdio JSON → 本地 HTTP", "换掉"),
    ("本地存储", "SQLite 单文件", "照搬"),
    ("导出", "openpyxl → Apache POI", "换掉"),
]
yy = T_BODY + 0.42
for layer, tech, tag in mapping:
    put(s, rx, yy, 1.35, 0.3, layer, size=12, color=C["muted"])
    put(s, rx + 1.42, yy, 3.2, 0.3, tech, size=12.5, bold=(tag == "换掉"))
    put(s, rx + 4.66, yy, 0.9, 0.3, tag, size=11,
        color=C["accent"] if tag == "换掉" else C["faint"])
    yy += 0.44
    if layer != "导出":
        rule(s, yy - 0.10, rx, 5.55)

# ══ 06 决定 2 · 训练日志 ══════════════════════════════════════
s = page("决定 2 · 「打卡」= 训练日志", "三层嵌套，目标值与实际值放在同一条记录上")
# 三层嵌套示意
lx, ly, lw = ML, T_BODY + 0.12, 6.6
rrect(s, lx, ly, lw, 0.52, C["panel2"], C["accent"])
put(s, lx + 0.2, ly, lw - 0.4, 0.52, "WorkoutSession   一次训练 · 10月14日 · 胸 + 三头",
    size=13, bold=True, anchor="middle")
rrect(s, lx + 0.45, ly + 0.70, lw - 0.45, 0.52, C["panel"], C["line"])
put(s, lx + 0.65, ly + 0.70, lw - 0.85, 0.52, "SessionExercise   训练中的动作 · 杠铃卧推",
    size=12.5, anchor="middle", color=C["fg"])
rrect(s, lx + 0.90, ly + 1.40, lw - 0.90, 0.52, C["panel"], C["line"])
put(s, lx + 1.10, ly + 1.40, lw - 1.30, 0.52,
    "WorkoutSet   一组 · 60 kg × 8 · 目标 60 × 8", size=12, anchor="middle",
    color=C["muted"])
eyebrow(s, lx, ly + 2.20, "一个动作行下面挂多条组行，几组就是几行")
# 补一条「为什么不做最轻的那种」，填满下半页并给出否定依据
rect(s, lx, ly + 2.62, 6.6, 0.011, C["line"])
put(s, lx, ly + 2.82, 6.6, 1.0,
    "为什么不做最轻的「今天练了」打卡？\n"
    "因为那样记不了重量与次数，看不到自己有没有变强；\n"
    "三个月后回头看，只有一堆日期，没有信息。",
    size=11.5, color=C["faint"])

# 两条要点
rx = 7.75
for i, (t, d) in enumerate([
    ("目标值与实际值同表",
     "「今天该做 60×8、实际做了 62.5×6」\n是同一行数据，不需要两张表对账。"),
    ("用组类型，不用组序号",
     "热身组 / 递减组 / 力竭组的语义完全不同，\n只记「第几组」会把这些信息丢掉。"),
]):
    yy = T_BODY + 0.15 + i * 1.72
    put(s, rx, yy, 0.5, 0.3, f"0{i+1}", size=13, bold=True, color=C["accent"])
    put(s, rx, yy + 0.36, 4.8, 0.34, t, size=15, bold=True)
    put(s, rx, yy + 0.78, 4.8, 0.8, d, size=12, color=C["muted"])
rect(s, rx - 0.32, T_BODY + 0.15, 0.011, 3.2, C["line"])

# ══ 07 ★ 决定 3 · 饮食铁律 ════════════════════════════════════
s = page("决定 3 · 一条铁律：不存绝对热量",
         "营养值一律按每 100 克存，份量单独存，热量永远实时算")
half = (CW - 0.55) / 2
# 左：反例（刻意用中性描边——「错的那个」不该抢眼）
rrect(s, ML, T_BODY + 0.10, half, 3.45, C["panel"], C["line"])
put(s, ML + 0.28, T_BODY + 0.30, half - 0.56, 0.4, "✗   反例", size=17,
    bold=True, color=C["muted"])
put(s, ML + 0.28, T_BODY + 0.82, half - 0.56, 0.5,
    "MealItem 里直接存 174 kcal", size=14, bold=True)
put(s, ML + 0.28, T_BODY + 1.38, half - 0.56, 1.8,
    "用户把份量从「1 碗」改成「1.5 碗」时，\n"
    "你必须记得回去把这一行重算一遍。\n\n"
    "漏一次，这一餐的账就永远对不上；\n"
    "而且你事后无法判断哪一行是漏算的。",
    size=12.5, color=C["muted"])
# 右：正例
rrect(s, ML + half + 0.55, T_BODY + 0.10, half, 3.45, C["panel"], C["accent"])
put(s, ML + half + 0.83, T_BODY + 0.30, half - 0.56, 0.4, "✓   正例", size=17,
    bold=True, color=C["accent"])
put(s, ML + half + 0.83, T_BODY + 0.82, half - 0.56, 0.5,
    "存「1 碗 → 150 克」", size=14, bold=True)
put(s, ML + half + 0.83, T_BODY + 1.38, half - 0.56, 1.8,
    "热量 = 150 ÷ 100 × 每百克值，当场算出来。\n\n"
    "改份量、换食物、换单位，全都自动正确。\n"
    "而且「改了之后对不对」不需要靠人记得。",
    size=12.5, color=C["muted"])
put(s, ML, T_BODY + 3.72, CW, 0.4,
    "同一张午餐照片，三家 App 实测估出 1086 / 845 / 740 千卡——差 300 多。"
    "所以识别结果只是草稿，而「可修正」正是这条铁律换来的。",
    size=12.5, color=C["faint"])

# ══ 08 决定 4 · 智能体 ════════════════════════════════════════
s = page("决定 4 · 智能体可读也能写", "说话就是录入，而工具集是白名单")
# 左：对话流
lx, ly, lw = ML, T_BODY + 0.12, 7.0
msgs = [
    ("user", "中午吃了两个包子一碗粥"),
    ("ai", "已记入午餐：包子 2 个（200g）、白粥 1 碗（300g），合计约 560 kcal。"),
    ("tool", "⚙  调用了 create_meal_item ×2 · 已记入变更日志"),
    ("user", "那个鸡腿其实是炸的"),
    ("ai", "已把「红烧鸡腿」改为「炸鸡腿」，240 → 328 kcal。"),
    ("tool", "⚙  调用了 update_meal_item · 可一键撤销"),
]
yy = ly
for kind, m in msgs:
    if kind == "tool":
        put(s, lx + 0.5, yy, lw - 0.5, 0.28, m, size=10, color=C["faint"])
        yy += 0.34
    else:
        w = lw * 0.74
        x = lx + (lw - w) if kind == "user" else lx
        rrect(s, x, yy, w, 0.42, C["accent"] if kind == "user" else C["panel2"],
              None if kind == "user" else C["line"])
        put(s, x + 0.2, yy, w - 0.4, 0.42, m, size=11.5,
            color="#FFFFFF" if kind == "user" else C["fg"], anchor="middle")
        yy += 0.54
# 右：安全线
rx = 8.25
rect(s, rx - 0.42, T_BODY + 0.12, 0.011, 3.5, C["line"])
eyebrow(s, rx, T_BODY + 0.12, "三条安全线")
for i, (t, d) in enumerate([
    ("工具集是白名单", "不是让模型自由写 SQL。\n它能做的事被明确枚举。"),
    ("只增改，不删除", "删除无法撤销，所以干脆不给。"),
    ("每次改动留痕可回退", "ChangeLog 记录改动前后的 JSON 快照。"),
]):
    yy = T_BODY + 0.55 + i * 1.06
    put(s, rx, yy, 4.3, 0.3, t, size=13.5, bold=True)
    put(s, rx, yy + 0.34, 4.3, 0.6, d, size=11.5, color=C["muted"])

# ══ 09 ★ 决定 5 · 3D 人体图 ═══════════════════════════════════
s = page("决定 5 · 3D 人体图，但不是现在", "可行性已被实测证明；排期仍放第二阶段")
# 拾取基准对比
lx, ly = ML, T_BODY + 0.15
eyebrow(s, lx, ly, "拾取耗时实测（240 条射线，全部命中）")
bars = [("three 原生 Raycaster", 14.96, "14.96 ms", 1.00, C["accent_dim"]),
        ("＋ MeshBVH", 0.203, "0.203 ms", 0.12, C["accent"]),
        ("合并单几何 ＋ BVH", 0.06, "0.06 ms", 0.05, C["accent"])]
yy = ly + 0.42
for name, val, lab, frac, col in bars:
    put(s, lx, yy, 2.3, 0.3, name, size=12, color=C["muted"])
    rrect(s, lx + 2.40, yy + 0.055, 2.55 * frac, 0.2, col)
    put(s, lx + 5.05, yy, 1.0, 0.3, lab, size=12.5, bold=True)
    yy += 0.5
put(s, lx, yy + 0.05, 6.6, 0.6,
    "瓶颈是 683 次 draw call，不是 128 万个三角形。\n这决定了实现方式：必须合并几何。",
    size=11.5, color=C["faint"])

# 右侧：界面位冻结
rx = 7.55
rect(s, rx - 0.42, T_BODY + 0.15, 0.011, 3.5, C["line"])
eyebrow(s, rx, T_BODY + 0.15, "界面位第一阶段就冻结")
put(s, rx, T_BODY + 0.55, 5.1, 0.4, "第二阶段只替换渲染层", size=14, bold=True)
frozen = ["侧边导航「人体图」项 + 路由",
          "最终布局：画布区 + 右侧面板",
          "后端 GET /api/body-map/volume",
          "肌群映射表（683 对象名 ↔ 70 块肌肉）",
          "组件契约 volumeByMuscle / @muscle-click"]
yy = T_BODY + 1.08
for f in frozen:
    put(s, rx, yy, 0.22, 0.28, "✓", size=11, color=C["accent"])
    put(s, rx + 0.26, yy, 4.8, 0.28, f, size=11.5, color=C["muted"])
    yy += 0.38
put(s, rx, yy + 0.06, 5.1, 0.5,
    "第一阶段先渲染 2D 静态热力图——与 3D 共用同一份数据与同一张映射表。",
    size=11, color=C["faint"])

# ══ 10 决定 6 · 数据源 ════════════════════════════════════════
s = page("决定 6 · 动作库，三家各取所长", "中文动作名三个数据集都没有，必须自己产出")
srcs = [
    ("动作库主体", "yuhonas/free-exercise-db", "876 条 · 1.0 MB",
     "primaryMuscles 是 17 值固定枚举，可直接绑人体图", "Unlicense 公有领域"),
    ("中文说明补充", "hasaneyldrm/exercises-dataset", "1,324 条",
     "instructions.zh 全覆盖，用于交叉校验术语", "MIT（仅文本）"),
    ("2D 区域路径", "vulovix/body-muscles", "约 24 KB",
     "两个数据文件 vendor 进项目，零依赖风险", "Apache-2.0"),
]
y = T_BODY + 0.10
for name, repo, size, why, lic in srcs:
    put(s, ML, y, 1.7, 0.3, name, size=13, bold=True)
    put(s, ML + 1.85, y, 3.4, 0.3, repo, size=12, color=C["muted"])
    put(s, ML + 5.35, y, 1.5, 0.3, size, size=12, color=C["muted"])
    put(s, ML + 7.0, y, 2.6, 0.3, why, size=11, color=C["faint"])
    put(s, ML + 9.85, y, 2.0, 0.3, lic, size=11.5,
        color=C["accent"] if "Unlicense" in lic else C["fg"])
    y += 0.55
    rule(s, y - 0.16)
put(s, ML, y + 0.12, 11.8, 0.9,
    "明确不能用：hasaneyldrm 的 images/ 与 videos/ 版权属 Gym visual，"
    "仓库原文写明「克隆本仓库不等于获得媒体授权」，且限定 180×180、必须署名。\n"
    "人体图模型（第二阶段）是 CC BY-SA 4.0，带 ShareAlike 传染性——自用无碍，分发受限。",
    size=11.5, color=C["faint"])

# 补一条排除理由，填满下半页并给出依据
rect(s, ML, y + 1.10, CW, 0.011, C["line"])
put(s, ML, y + 1.30, CW, 1.0,
    "为什么不用 hasaneyldrm 做主体：它的 muscle_group 是 29 值自由文本，"
    "traps / trapezius、lats / latissimus dorsi 这类同义词并存，"
    "甚至同一条记录里 hip flexors 重复出现两次。\n"
    "脏字段正好卡在「按肌群筛动作」这个核心功能上——而那正是人体图的命脉。",
    size=11.5, color=C["faint"])

# ══ 11 技术选型 ═══════════════════════════════════════════════
s = page("技术选型", "八层，每一层都写清了为什么是它")
tech = [
    ("桌面外壳", "Tauri 2 + Rust", "安装包 12 MB（实测产物）"),
    ("界面", "Vue 3 + TS + Vite + Pinia", "与参考项目同构"),
    ("业务后台", "JDK 25 + Spring Boot 3.x", "你的学习方向"),
    ("智能体", "LangChain4j + DeepSeek", "纯 Java 库，tool calling 齐全"),
    ("主库", "SQLite 单文件", "零运维；另提供 MySQL profile 练手"),
    ("缓存", "Redis（可选，可降级）", "虚拟机常关，无缓存是常态路径"),
    ("通信", "本地 HTTP 127.0.0.1", "比 stdio JSON 管道简单"),
    ("导出", "Apache POI", "Java 版，对应 openpyxl"),
]
y = T_BODY + 0.10
for layer, tech_, why in tech:
    put(s, ML, y, 1.5, 0.3, layer, size=12.5, color=C["muted"])
    put(s, ML + 1.62, y, 4.3, 0.3, tech_, size=13, bold=True)
    put(s, ML + 6.1, y, 5.7, 0.3, why, size=11.5, color=C["faint"])
    y += 0.44
    rule(s, y - 0.13)
eyebrow(s, ML, y + 0.05, "明确排除")
put(s, ML, y + 0.30, CW, 0.5,
    "Semantic Kernel Java（Java 分支已废弃）· Spring Boot 4（与 3.x 线分叉）· "
    "Google ADK（偏 Gemini、RAG 弱）· JavaFX 客户端（界面工作量数倍）· "
    "直接加载 FBX（必须转 GLB）",
    size=10.5, color=C["faint"])

# ══ 12 系统架构 ═══════════════════════════════════════════════
s = page("系统架构", "四层，全部跑在你自己这台机器上；唯一出网点可关闭")
layers = [
    ("change.exe", "Tauri 2 外壳 · Rust", "窗口管理 · 启动与关闭后台 · 系统托盘", C["panel2"], C["accent"]),
    ("界面", "Vue 3 + TypeScript（WebView2 渲染）", "训练打卡 · 饮食记录 · 照片 · 进步曲线 · 智能体 · 人体图", C["panel"], C["line"]),
    ("Java 后台", "JDK 25 + Spring Boot 3.x", "训练模块 · 饮食模块 · 智能体（LangChain4j）", C["panel"], C["line"]),
]
y = T_BODY + 0.05
for name, sub, detail, fill, line in layers:
    h = 0.78
    rrect(s, ML, y, 7.85, h, fill, line)
    put(s, ML + 0.25, y + 0.08, 2.6, 0.3, name, size=13.5, bold=True)
    put(s, ML + 2.55, y + 0.11, 4.0, 0.26, sub, size=11, color=C["muted"])
    put(s, ML + 0.25, y + 0.44, 7.35, 0.26, detail, size=10.5, color=C["faint"])
    y += h
    if name != "Java 后台":
        put(s, ML + 3.7, y, 0.5, 0.22, "▼", size=9, color=C["faint"], align="center")
        y += 0.22
# 最底层四格
for i, (t, d) in enumerate([("SQLite", "单文件数据库"), ("图片目录", "本地文件系统"),
                            ("食物营养库", "随程序打包"), ("模型接口", "唯一出网点 · 可关闭")]):
    x = ML + i * (1.8575 + 0.14)
    last = i == 3
    rrect(s, x, y + 0.05, 1.8575, 0.78, C["panel"],
          C["accent_dim"] if last else C["line"])
    put(s, x + 0.16, y + 0.14, 1.54, 0.28, t, size=12, bold=True,
        color=C["accent"] if last else C["fg"])
    put(s, x + 0.16, y + 0.46, 1.54, 0.28, d, size=9.5, color=C["faint"])
# 右侧时序
rx = 9.15
rect(s, rx - 0.42, T_BODY + 0.05, 0.011, 4.0, C["line"])
eyebrow(s, rx, T_BODY + 0.05, "启动时序")
for i, (t, d) in enumerate([
    ("双击 change.exe", "外壳先不显示窗口，避免白屏"),
    ("拉起 Java 子进程", "绑定端口 0，由系统分配随机端口"),
    ("读端口后开窗", "加载 http://127.0.0.1:<port>"),
    ("关窗优雅退出", "先让后台落盘，再结束进程"),
]):
    yy = T_BODY + 0.42 + i * 0.88
    put(s, rx, yy, 0.3, 0.28, str(i + 1), size=12, bold=True, color=C["accent"])
    put(s, rx + 0.34, yy, 3.3, 0.28, t, size=12.5, bold=True)
    put(s, rx + 0.34, yy + 0.30, 3.3, 0.44, d, size=10.5, color=C["muted"])

# ══ 13 数据模型 ═══════════════════════════════════════════════
s = page("数据模型：三个域，十五张表", "训练 6 张 · 饮食 4 张 · 辅助 5 张")
doms = [
    ("训练域", ["Exercise  动作库", "WorkoutSession  一次训练",
                "SessionExercise  动作", "WorkoutSet  一组",
                "MuscleGroup  肌群", "ExerciseMuscle  动作↔肌群"]),
    ("饮食域", ["Food  食物库（按每 100 克）", "FoodPortion  份量换算",
                "Meal  一餐", "MealItem  餐中条目"]),
    ("辅助域", ["Photo  照片", "BodyMetric  身体数据",
                "ChatSession / ChatMessage", "ChangeLog  可回退日志",
                "（+ 智能体会话共 5 张）"]),
]
cx = ML
cw3 = (CW - 2 * 0.42) / 3
for name, tables in doms:
    rrect(s, cx, T_BODY + 0.05, cw3, 2.55, C["panel"], C["line"])
    put(s, cx + 0.24, T_BODY + 0.20, cw3 - 0.48, 0.3, name, size=14, bold=True)
    yy = T_BODY + 0.62
    for t in tables:
        put(s, cx + 0.24, yy, cw3 - 0.48, 0.26, t, size=10.5, color=C["muted"])
        yy += 0.31
    cx += cw3 + 0.42
# 两个建模难点
put(s, ML, T_BODY + 2.78, CW, 0.3, "两个建模难点", size=13.5, bold=True, color=C["accent"])
put(s, ML, T_BODY + 3.16, 5.6, 0.9,
    "一次训练多动作多组\n三层嵌套。目标值与实际值同表；组类型用枚举。",
    size=11.5, color=C["muted"])
put(s, ML + 6.2, T_BODY + 3.16, 5.6, 0.9,
    "一餐多条目 + 单位换算\n营养按每 100 克存，份量单独存，热量实时算。",
    size=11.5, color=C["muted"])

# ══ 14 四条主流程 ═════════════════════════════════════════════
s = page("四条主流程", "前三条是每天的日常，第四条是人体图这个入口")
flows = [
    ("① 记录一次训练", ["今天还没练", "开始训练", "选肌群出动作", "填 60 × 8", "打勾", "结束并写入"], (3,)),
    ("② 记录一餐", ["点记一餐", "拍照 / 搜 / 外卖截图", "AI 出草稿", "确认或改份量", "写入并算热量"], (2,)),
    ("③ 智能体改数据", ["你说一句话", "解析意图", "调用工具", "写库 + 记日志", "可一键撤销"], (2,)),
    ("④ 从肌群找动作", ["打开人体图", "肌肉按组数着色", "悬停看容量", "点击", "加入今天训练"], (1, 4)),
]
y = T_BODY + 0.10
for name, steps, hl in flows:
    put(s, ML, y + 0.11, 2.55, 0.32, name, size=12.5, bold=True)
    n = len(steps)
    gap = 0.24
    avail = CW - 2.75 - gap * (n - 1)
    bw = avail / n
    x = ML + 2.75
    for i, st in enumerate(steps):
        fill = C["accent_dim"] if i in hl else C["panel"]
        line = C["accent"] if i in hl else C["line"]
        rrect(s, x, y, bw, 0.56, fill, line)
        put(s, x + 0.08, y, bw - 0.16, 0.56, st, size=10.5,
            align="center", anchor="middle",
            color=C["fg"] if i in hl else C["muted"])
        if i < n - 1:
            put(s, x + bw, y, gap, 0.56, "▸", size=10,
                color=C["faint"], align="center", anchor="middle")
        x += bw + gap
    y += 0.76
    if name != "④ 从肌群找动作":
        rule(s, y - 0.14)
put(s, ML, y + 0.06, CW, 0.6,
    "② 的四个入口（拍照 / 搜索 / 外卖截图 / 粘贴订单文本）殊途同归，"
    "最后都落到同一张条目表、走同一套「草稿 → 确认 → 入库」。",
    size=11, color=C["faint"])

# ══ 15 界面与视觉 ═════════════════════════════════════════════
s = page("界面与视觉：只做深色", "厚重感来自对比与留白，不是装饰")
rules6 = [
    ("底色", "#0D0F12 近黑 · 面板 #14171B", "只用 1px 细线分层"),
    ("强调色", "#E0453A 唯一暖红", "肌肉高亮、主按钮、进度条共用"),
    ("字重", "标题 800 并压字距", "重量感来自对比，不是整体加粗"),
    ("小标签", "全大写 + 0.16em 字距", "承担技术文档的质感"),
    ("圆角与阴影", "圆角 4–6px，不用阴影", "硬朗的关键就在这一条"),
    ("留白", "大幅放宽", "用空白而不是卡片边框分组"),
]
y = T_BODY + 0.12
for k, v, note in rules6:
    put(s, ML, y, 1.5, 0.3, k, size=12.5, color=C["muted"])
    put(s, ML + 1.62, y, 4.6, 0.3, v, size=13, bold=True)
    put(s, ML + 6.4, y, 5.4, 0.3, note, size=11.5, color=C["faint"])
    y += 0.52
    rule(s, y - 0.15)
put(s, ML, y + 0.10, CW, 0.7,
    "不做浅色主题：厚重感一半来自深色底与高对比，再维护一套浅色主题会稀释这个方向，"
    "测试成本也翻倍。",
    size=11.5, color=C["faint"])

# ══ 16 可行性实测 ═════════════════════════════════════════════
s = page("可行性：不是推测，是真跑过的数字",
         "环境、编译、拾取三组数据都是本机实测，不是估算")
xs = ML
cw4 = (CW - 2 * 0.45) / 3
blocks = [
    ("环境搭建", "全部落在 E 盘",
     [("Rust 工具链", "1.99.0"), ("MSVC 工具集", "14.44"), ("cargo 包缓存", "321 MB")],
     "国内源实测：USTC 3.4 MB/s；官方源仅 19 KB/s"),
    ("Tauri 冒烟测试", "全链路通过",
     [("首次编译", "1.9 分钟"), ("产出 exe", "12 MB"), ("Electron 对比", "150 MB")],
     "唯一仍需验证：JDK 25 与 Spring Boot 3.x 的版本搭配"),
    ("3D 拾取基准", "240 条射线全命中",
     [("原生 Raycaster", "14.96 ms"), ("＋ MeshBVH", "0.203 ms"), ("合并 + BVH", "0.06 ms")],
     "模型：683 个独立命名 mesh，128 万三角形"),
]
for i, (title, sub, rows_, note) in enumerate(blocks):
    x = ML + i * (cw4 + 0.45)
    rrect(s, x, T_BODY + 0.10, cw4, 3.05, C["panel"], C["line"])
    put(s, x + 0.26, T_BODY + 0.30, cw4 - 0.52, 0.34, title, size=15, bold=True)
    put(s, x + 0.26, T_BODY + 0.70, cw4 - 0.52, 0.28, sub, size=11, color=C["accent"])
    yy = T_BODY + 1.18
    for k, v in rows_:
        put(s, x + 0.26, yy, cw4 * 0.55, 0.28, k, size=11, color=C["muted"])
        put(s, x + 0.26 + cw4 * 0.55, yy, cw4 * 0.40, 0.28, v, size=12.5,
            bold=True, align="right")
        yy += 0.38
    rect(s, x + 0.26, yy - 0.02, cw4 - 0.52, 0.009, C["line"])
    put(s, x + 0.26, yy + 0.12, cw4 - 0.52, 0.9, note, size=10.5, color=C["faint"])

# 底部结论：把「唯一未验证项」单独点出来
rect(s, ML, T_BODY + 3.35, CW, 0.011, C["line"])
put(s, ML, T_BODY + 3.56, CW, 0.8,
    "三组数据全部来自本机实测，没有一个是估算。"
    "目前唯一还没验证的是 JDK 25 与 Spring Boot 3.x 的版本搭配——"
    "它也是唯一可能推翻现有选型的点，所以排在实施第一步。",
    size=12, color=C["faint"])

# ══ 17 风险与排期 ════════════════════════════════════════════
s = page("风险与排期", "七个风险里只有一个需要立刻处理")
risks = [
    ("JDK 25 与 Spring Boot 3.x 搭配未验证", "高", "动工第一步就跑最小骨架验证"),
    ("Java 生态没有成熟可抄的健身项目", "中", "照 wger 思路用 Java 重写，正是学习价值"),
    ("LangChain4j 迭代极快", "中", "锁版本 + BOM；以英文文档为准"),
    ("拍照估热量不够准", "中", "定位为估算 + 一句话修正"),
    ("Redis 在虚拟机上，常关", "中", "无缓存是常态路径；超时压到 300 ms"),
    ("CC BY-SA 的 ShareAlike 传染性", "中", "自用无碍；分发受限；界面必须署名"),
]
y = T_BODY + 0.05
for r, level, fix in risks:
    put(s, ML, y, 5.2, 0.28, r, size=11.5)
    put(s, ML + 5.35, y, 0.7, 0.28, level, size=11.5,
        bold=True, color=C["accent"] if level == "高" else C["muted"])
    put(s, ML + 6.2, y, 5.6, 0.28, fix, size=11, color=C["faint"])
    y += 0.42
    rule(s, y - 0.13)
# 两阶段
eyebrow(s, ML, y + 0.10, "两阶段排期")
ph = [("第一阶段 · 10 步", "最小骨架 → 数据层 → 动作库 → 训练 → 饮食 → 智能体 → 身体数据 → 人体图 2D → Redis 缓存 → 打包",
       "做完就有个能天天用的东西", C["accent"]),
      ("第二阶段 · 8–14 人日", "模型管线 → 合并几何 + BVH 拾取 → 热力图着色 → Tauri 集成调优",
       "只替换渲染层，其余一律不动", C["muted"])]
yy = y + 0.44
for name, steps, note, col in ph:
    rrect(s, ML, yy, CW, 0.62, C["panel"], C["line"])
    rect(s, ML, yy, 0.05, 0.62, col)
    put(s, ML + 0.24, yy + 0.06, 3.0, 0.26, name, size=12, bold=True, color=col)
    put(s, ML + 0.24, yy + 0.32, 11.0, 0.26, steps, size=9.5, color=C["muted"])
    put(s, ML + 9.6, yy + 0.06, 2.1, 0.5, note, size=9.5,
        color=C["faint"], align="right", anchor="middle")
    yy += 0.72

# ══ 18 收尾 ═══════════════════════════════════════════════════
s = blank()
_n[0] = 18
rect(s, 0, 0, SW, 0.055, C["accent"])
put(s, ML, 1.55, 11.8, 1.0, "下一步", size=40, bold=True)
rule(s, 2.80)
steps3 = [
    ("01", "写实施计划", "把第一阶段十步拆成可执行的计划，每步带验收标准"),
    ("02", "先验证版本搭配", "JDK 25 + Spring Boot 3.x + LangChain4j 最小骨架跑通"),
    ("03", "再动数据层", "十五张表 + Flyway 迁移，然后动作库导入与中文翻译"),
]
y = 3.28
for num, t, d in steps3:
    put(s, ML, y, 0.6, 0.34, num, size=13, bold=True, color=C["accent"])
    put(s, ML + 0.7, y, 3.4, 0.34, t, size=16, bold=True)
    put(s, ML + 4.35, y + 0.02, 7.4, 0.34, d, size=12, color=C["muted"])
    y += 0.78
    if num != "03":
        rule(s, y - 0.22)
rect(s, ML, 6.34, CW, 0.011, C["line"])
put(s, ML, 6.48, 11.0, 0.3,
    "全部设计细节见 docs/superpowers/specs/2026-10-14-change-design.md",
    size=11, color=C["faint"])
put(s, SW - MR - 1.3, 6.48, 1.3, 0.3, "18 / 18", size=10,
    color=C["faint"], align="right")

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "change-design-deck.pptx")
clean_save(prs, out)
print(f"[ok] slides={_n[0]}  saved={out}  {os.path.getsize(out):,} bytes")
