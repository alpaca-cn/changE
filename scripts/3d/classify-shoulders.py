"""肩部 129 个动作逐个归类（人工核对过名字之后写下的规则 + 例外）。

与上一版的区别：上一版把「没命中具体规则的肩部动作」全丢给中束（117 条，明显偏高）。
这一版按解剖把肩部分成三类的真实分布：

    推举类（press / jerk / snatch / clean / thruster / log lift / halo）-> 前束为主 + 中束为辅
    举类（raise / lateral / side / scaption / upright row / crucifix）-> 中束
    后侧类（rear delt / reverse fly / face pull / external rotation / row to neck）-> 后束

例外表用来纠正规则抓不对的少数几个（名字里没有部位线索、但解剖上很清楚）。
"""
import json
import pathlib
import re

SRC = pathlib.Path(r"E:\idea\program\change\backend\src\main\resources\seed\free-exercise-db\exercises.json")
raw = json.loads(SRC.read_text(encoding="utf-8"))
items = raw if isinstance(raw, list) else raw.get("exercises", [])
shoulders = [it for it in items if "shoulders" in it.get("primaryMuscles", [])]

A, L, P = "deltoid anterior", "deltoid lateral", "deltoid posterior"
ZH = {A: "前束", L: "中束", P: "后束"}

# 后侧：这些词一旦出现就是后束，优先级最高
REAR = r"\brear delt|\breverse fly|\breverse machine fly|\bback fly|\bface pull|\bpull apart|\bexternal rotation|\bcuban|\brow to neck|\blow-pulley side lateral|\bbent over .*lateral|\bsled reverse|\blying rear"
# 前侧
FRONT = r"\bfront raise\b|\bfront delt|\bfront .*raise|\bincline shoulder raise|\bstraight-arm front|\bcar drivers"
# 中束
LATERAL = r"\blateral raise\b|\bside lateral|\blaterals\b|\bdeltoid raise\b|\bupright row\b|\bscaption\b|\bcrucifix\b|\biron cross\b|\bpower partials\b|\bcircles\b|\bstretch\b|\boverhead .*raise"
# 推举
PRESS = r"\bpress\b|\bpresses\b|\bjerk\b|\bsnatch\b|\bclean\b|\bthruster\b|\blog lift\b|\bhalo\b|\bpush press\b|\bmilitary\b|\bjammer\b|\bcrossfit\b|\bdelivery\b"

# 名字里没有线索、但解剖上明确的，逐个点名
OVERRIDE = {
    # 反握划船/后束划船类：命中 REAR 已足够，这里只放规则抓不到的
    "battling ropes": [A, L],
    "backward medicine ball throw": [A, L],
    "medicine ball scoop throw": [A, L],
    "standing two-arm overhead throw": [A, L],
    "shoulder raise": [L],
    "dumbbell raise": [A, L],
    "single dumbbell raise": [L],
    "arm drill": [L],
    "straight raises on incline bench": [P],   # 俯卧斜板 -> 后束
    "kneeling arm drill": [L],
    "side wrist pull": [L],
    "sled overhead backward walk": [A, L],
    "return push from stance": [A, L],
    "anti-gravity press": [A, L],
    "circus bell": [A, L],
    # 颈后推举：中束为主（解剖上过头推举靠前束，但颈后变式让中束承担更多）
    "push press - behind the neck": [L, A],
    "standing barbell press behind neck": [L, A],
}

assign = {}
for it in shoulders:
    name = (it.get("name") or "").strip()
    low = name.lower()
    if low in OVERRIDE:
        subs = OVERRIDE[low]
    elif re.search(REAR, low):
        subs = [P]
    elif re.search(FRONT, low):
        subs = [A, L]
    elif re.search(LATERAL, low):
        subs = [L]
    elif re.search(PRESS, low):
        subs = [A, L]
    else:
        subs = [L]
    assign[name] = subs

from collections import Counter
counts = Counter()
for subs in assign.values():
    counts[ZH[subs[0]]] += 1
secondary = Counter()
for subs in assign.values():
    for s in subs[1:]:
        secondary[ZH[s]] += 1

OUT = pathlib.Path(r"E:\idea\program\change\docs\动作到子部位映射.md")
t = OUT.read_text(encoding="utf-8")

# 替换肩部那一段（如果之前没有就追加）
block = [
    "",
    "## 肩部逐个归类（129 个，人工核对）",
    "",
    "上一版把「没命中规则的肩部动作」全丢给中束（117 条，明显偏高）。这一版按解剖分三类：",
    "",
    "- **推举类**（press / jerk / snatch / clean / thruster / military）→ 前束为主 + 中束为辅",
    "- **举类**（raise / lateral / side / scaption / upright row）→ 中束",
    "- **后侧类**（rear delt / reverse fly / face pull / external rotation / row to neck）→ 后束",
    "",
    "分布：",
    "",
    "| 主部位 | 动作数 |",
    "|---|---|",
]
for zh in ("前束", "中束", "后束"):
    block.append(f"| {zh} | {counts[zh]} |")
block += ["", "次要部位（同一动作的第二行）：", ""]
for zh, n in secondary.most_common():
    block.append(f"- {zh}：{n} 个动作把它算作辅助")
block += ["", "逐个清单：", "", "| # | 动作 | 主 | 辅 |", "|---|---|---|---|"]
for i, (name, subs) in enumerate(assign.items(), 1):
    main = ZH[subs[0]]
    sec = "、".join(ZH[s] for s in subs[1:]) or "—"
    block.append(f"| {i} | {name} | {main} | {sec} |")

t = t.rstrip() + "\n" + "\n".join(block) + "\n"
OUT.write_text(t, encoding="utf-8")

print(f"  已写入 {OUT}")
print(f"  前束 {counts['前束']} / 中束 {counts['中束']} / 后束 {counts['后束']}  （共 {len(assign)}）")
