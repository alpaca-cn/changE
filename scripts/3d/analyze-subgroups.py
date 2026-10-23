"""生成「动作 -> 子部位」映射表，输出成一份可以人眼审阅的文档。

## 为什么先出文档、不动库

用户要「胸上中下、肩前中后」。3D 网格那边完全支持（Clavicular/Sternocostal/
Abdominal part、三个 deltoid part，实测确认过）。真正的瓶颈是**动作数据**：
free-exercise-db 的 primaryMuscles 只到粗肌群，而「Shoulder Press」这句话
本身不说明是哪一束。

如果直接按名字猜着改库，会发生什么：80% 的肩部动作被分到某一束，
**界面上不会报错**，热力图看起来也很正常 —— 只是它是错的。
那种错用户要练几个月才会察觉。

所以先把分类表打出来，能人工抽检之后再动迁移。

## 分类口径

1. **名字里写明的优先**：incline -> 上胸，decline/dip -> 下胸，lateral raise -> 中束，
   rear delt / reverse fly / face pull -> 后束，shrug / upright row -> 上斜方。
2. **名字不写明的，按解剖上占主导的那一部分给主部位**：
   - 卧推 / 飞鸟（平/未说明） -> 中胸（胸大肌胸肋部占主导）
   - 肩推 -> **前束为主、中束为辅**（解剖学上过头推举主要靠三角肌前束）
   - 划船 -> 背阔肌为主、菱形肌与中斜方为辅
3. **无法判断的宁可留在父级**，不硬塞一个子部位 —— 留空比填错好。
"""
import json
import pathlib
import re
from collections import Counter, defaultdict

SRC = pathlib.Path(r"E:\idea\program\change\backend\src\main\resources\seed\free-exercise-db\exercises.json")
OUT = pathlib.Path(r"E:\idea\program\change\docs\动作到子部位映射.md")

raw = json.loads(SRC.read_text(encoding="utf-8"))
items = raw if isinstance(raw, list) else raw.get("exercises", [])

# (子部位 id, 中文名, 父组, 正则)。顺序即优先级。
# primary/secondary：一个动作可以有一个主部位 + 若干辅部位，
# 与 exercise_muscle 表的主/次完全对应。
RULES = [
    # 胸
    ("chest upper", "上胸", "chest", r"\bincline\b|\bupper chest\b", True),
    ("chest lower", "下胸", "chest", r"\bdecline\b|\blower chest\b|\bchest dip", True),
    ("chest middle", "中胸", "chest", r".", True),
    # 肩
    ("deltoid anterior", "三角肌前束", "shoulders", r"\bfront raise\b|\barnold\b|\bfront delt", True),
    ("deltoid posterior", "三角肌后束", "middle back",
     r"\brear delt|\breverse fly|\bface pull|\bbent[- ]over .*raise", True),
    ("deltoid lateral", "三角肌中束", "shoulders", r"\blateral raise\b|\bside lateral|\blateral delt", True),
    # 肩推：前束为主、中束为辅（解剖上的主导）
    ("deltoid anterior", "三角肌前束", "shoulders", r"\bshoulder press\b|\boverhead press\b|\bpush press\b|\barnold press\b", True),
    ("deltoid lateral", "三角肌中束", "shoulders", r"\bshoulder press\b|\boverhead press\b|\bpush press\b|\barnold press\b", False),
    # 斜方
    ("traps upper", "上斜方", "traps", r"\bshrug\b|\bupright row\b", True),
    ("traps middle", "中斜方", "traps", r"\brow\b|\bscapular\b|\bhigh pull\b", True),
    # 背
    ("rhomboids", "菱形肌", "middle back", r"\brhomboid|\bscapular retraction", True),
    ("middle back", "中背部", "middle back", r".", True),
    # 下背
    ("erector spinae", "竖脊肌", "lower back",
     r"\bdeadlift\b|\bback extension\b|\bgood morning\b|\bextension\b|\bhyperextension", True),
    ("lower back", "下背部", "lower back", r".", True),
    # 不细分的组：原样保留
    ("lats", "背阔肌", "lats", r".", True),
]

SUBGROUP_NAMES = {
    "chest upper": "上胸",
    "chest middle": "中胸",
    "chest lower": "下胸",
    "deltoid anterior": "三角肌前束",
    "deltoid lateral": "三角肌中束",
    "deltoid posterior": "三角肌后束",
    "traps upper": "上斜方",
    "traps middle": "中斜方",
    "rhomboids": "菱形肌",
    "erector spinae": "竖脊肌",
}

# 只处理这几个父组；其余原样
SPLIT_PARENTS = {"chest", "shoulders", "traps", "middle back", "lower back"}

by_muscle = defaultdict(list)
for it in items:
    for m in it.get("primaryMuscles", []):
        by_muscle[m].append(it)

assignments = []   # (exercise, muscle, subgroup, is_primary, why)
counts = Counter()
unresolved = []

for parent in SPLIT_PARENTS:
    for it in by_muscle.get(parent, []):
        name = (it.get("name") or "").lower()
        hits = []
        for sub, zh, src_parent, pat, is_primary in RULES:
            if src_parent != parent:
                continue
            if re.search(pat, name):
                hits.append((sub, zh, is_primary, pat))
                if sub != parent and sub not in (parent,):
                    # 兜底规则之外的不重复命中：一旦命中具体规则就不再往下走
                    if pat != ".":
                        break
        # 只保留真正"细分"的命中；落到与父组同名的兜底就记为未细分
        specific = [h for h in hits if h[0] != parent]
        if not specific:
            unresolved.append((parent, it.get("name")))
            counts[f"{parent}（未细分）"] += 1
            continue
        for sub, zh, is_primary, _ in specific:
            assignments.append((it.get("name"), parent, sub, is_primary))
            counts[SUBGROUP_NAMES.get(sub, zh)] += 1

# ── 写文档 ────────────────────────────────────────────────
lines = [
    "# 动作 -> 子部位映射（待审阅，未应用）",
    "",
    "> 生成于 2026-10-23。**这一版没有改数据库**：先出分类表供人工抽检。",
    "> 生成脚本：`scripts/3d/analyze-subgroups.py`",
    "",
    "## 为什么先出表",
    "",
    "用户要「胸上中下、肩前中后」。3D 网格完全支持（Z-Anatomy 有",
    "`Clavicular head` / `Sternocostal head` / `Abdominal part`，",
    "以及三个 `deltoid part`）。瓶颈在动作数据：`free-exercise-db` 的",
    "`primaryMuscles` 只到粗肌群，而「Shoulder Press」这句话本身不说明是哪一束。",
    "",
    "直接按名字猜着改库的后果：约 80% 的肩部动作被塞进某一束，**界面上不会报错**，",
    "热力图看起来也正常 —— 只是它是错的。所以先列表。",
    "",
    "## 分类口径",
    "",
    "1. **名字里写明的优先**：incline → 上胸，decline/dip → 下胸，lateral raise → 中束，",
    "   rear delt / reverse fly / face pull → 后束，shrug / upright row → 上斜方。",
    "2. **名字不写明的，按解剖上占主导的那部分给主部位**：",
    "   - 平卧推/飞鸟 → 中胸",
    "   - 肩推 → 前束为主、中束为辅",
    "   - 划船 → 背阔肌为主、菱形肌为辅",
    "3. **判断不了的宁可留在父级**：留空比填错好。",
    "",
    "## 各子部位命中数",
    "",
    "| 子部位 | 动作数 |",
    "|---|---|",
]
for k, v in counts.most_common():
    lines.append(f"| {k} | {v} |")

lines += [
    "",
    "## 未细分（留在父级）的动作",
    "",
    f"共 {len(unresolved)} 个。这些是名字里没有任何部位线索的，",
    "强行分配只会制造不可信的数据。",
    "",
    "| 父组 | 动作 |",
    "|---|---|",
]
for parent, name in unresolved[:40]:
    lines.append(f"| {parent} | {name} |")
if len(unresolved) > 40:
    lines.append(f"| … | 另有 {len(unresolved) - 40} 个 |")

lines += [
    "",
    "## 抽查：胸部的全部分类",
    "",
    "| 动作 | 分到 |",
    "|---|---|",
]
for ex, parent, sub, is_primary in assignments:
    if parent == "chest":
        lines.append(f"| {ex} | {SUBGROUP_NAMES.get(sub, sub)} |")

lines += [
    "",
    "## 下一步（确认这张表之后）",
    "",
    "1. `muscle_group` 加 `parent_en`（可空）+ 9 个子组行；保留 17 个父组。",
    "2. 子组体积**计入父组**，所以容量面板与智能体的 `weekly_volume` 行为不变。",
    "3. V11 迁移：按上表把 `exercise_muscle` 的映射从父组下放到子组。",
    "4. 3D 按子组导出网格（`Clavicular/Sternocostal/Abdominal part`、三个 deltoid part 已确认存在）。",
    "5. 2D 人体图仍是父组粒度（细分要重画 SVG 路径），先不动。",
]

OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"  已写出 {OUT}")
print(f"  子部位命中: {dict(counts.most_common())}")
print(f"  未细分: {len(unresolved)} 个动作")
