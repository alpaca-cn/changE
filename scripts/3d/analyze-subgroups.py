"""按策略 B（每个动作都落到一个子部位）生成分类表。

与上一版的区别：**没有「留在父级」这个出口**。每个动作都按解剖主导给一个子部位，
其中一部分是推断 —— 这份文档把「哪些是名字写明的、哪些是按主导推断的」分开标注，
因为两者的可信度不一样。
"""
import json
import pathlib
import re
from collections import Counter

SRC = pathlib.Path(r"E:\idea\program\change\backend\src\main\resources\seed\free-exercise-db\exercises.json")
OUT = pathlib.Path(r"E:\idea\program\change\docs\动作到子部位映射.md")

raw = json.loads(SRC.read_text(encoding="utf-8"))
items = raw if isinstance(raw, list) else raw.get("exercises", [])

# 子部位：(id, 中文, 父组)
SUB = {
    "chest upper": ("上胸", "chest"),
    "chest middle": ("中胸", "chest"),
    "chest lower": ("下胸", "chest"),
    "deltoid anterior": ("三角肌前束", "shoulders"),
    "deltoid lateral": ("三角肌中束", "shoulders"),
    "deltoid posterior": ("三角肌后束", "shoulders"),
    "traps upper": ("上斜方", "traps"),
    "traps middle": ("中斜方", "traps"),
    "rhomboids": ("菱形肌", "middle back"),
    "erector spinae": ("竖脊肌", "lower back"),
}

# (父组, 正则, [(子部位, 是否主部位)], 依据来源)
# "named" = 名字里写明了；"dominant" = 按解剖主导推断（策略 B）
RULES = [
    # ── 胸 ──
    ("chest", r"\bincline\b|\bupper chest\b",
     [("chest upper", True)], "named"),
    ("chest", r"\bdecline\b|\blower chest\b|\bchest dip\b|\bdip\b",
     [("chest lower", True)], "named"),
    ("chest", r".",
     [("chest middle", True)], "dominant"),

    # ── 肩 ──
    ("shoulders", r"\bfront raise\b|\bfront delt",
     [("deltoid anterior", True)], "named"),
    ("shoulders", r"\brear delt|\breverse fly|\bface pull|\bbent[- ]over .*raise",
     [("deltoid posterior", True)], "named"),
    ("shoulders", r"\blateral raise\b|\bside lateral|\blateral delt|\bdeltoid raise\b",
     [("deltoid lateral", True)], "named"),
    ("shoulders", r"\barnold\b",
     [("deltoid anterior", True), ("deltoid lateral", False)], "named"),
    # 推举：解剖上前束主导、中束辅助（策略 B 的典型推断）
    ("shoulders", r"\bpress\b|\bpush press\b|\bjerk\b",
     [("deltoid anterior", True), ("deltoid lateral", False)], "dominant"),
    # 其余肩部动作（举、平举变体）按中束
    ("shoulders", r".",
     [("deltoid lateral", True)], "dominant"),

    # ── 斜方 ──
    ("traps", r"\bshrug\b|\bupright row\b",
     [("traps upper", True)], "named"),
    ("traps", r"\bhigh pull\b|\bscapular\b|\brow\b",
     [("traps middle", True)], "named"),
    ("traps", r".",
     [("traps upper", True)], "dominant"),

    # ── 中背（菱形肌为这一桶的主导） ──
    ("middle back", r"\brhomboid|\bscapular retraction",
     [("rhomboids", True)], "named"),
    ("middle back", r".",
     [("rhomboids", True)], "dominant"),

    # ── 下背（竖脊肌主导） ──
    ("lower back", r"\bdeadlift\b|\bback extension\b|\bgood morning\b|\bextension\b",
     [("erector spinae", True)], "named"),
    ("lower back", r".",
     [("erector spinae", True)], "dominant"),
]

by_muscle = {}
for it in items:
    for m in it.get("primaryMuscles", []):
        by_muscle.setdefault(m, []).append(it)

rows = []          # (动作名, 父组, 子部位 id, is_primary, 依据)
counts = Counter()
evidence = Counter()

for parent in ("chest", "shoulders", "traps", "middle back", "lower back"):
    for it in by_muscle.get(parent, []):
        name = (it.get("name") or "").lower()
        for rule_parent, pat, subs, why in RULES:
            if rule_parent != parent:
                continue
            if re.search(pat, name):
                for sub, is_primary in subs:
                    rows.append((it.get("name"), parent, sub, is_primary, why))
                    counts[SUB[sub][0]] += 1
                evidence[why] += 1
                break

lines = [
    "# 动作 -> 子部位映射（策略 B，待实施）",
    "",
    "> 生成于 2026-10-23。用户选定 **B + 5 块背**：每个动作都落到一个子部位，",
    "> 不保留「未细分」的桶；背部分成 背阔肌 / 菱形肌 / 上斜方 / 中斜方 / 竖脊肌。",
    "> 生成脚本：`scripts/3d/analyze-subgroups.py`",
    "",
    "## 两类依据，可信度不同",
    "",
    "| 依据 | 动作数 | 含义 |",
    "|---|---|---|",
    f"| **名字写明** | {evidence['named']} | 动作名里直接带着部位线索（incline / decline / lateral raise / rear delt / shrug …）|",
    f"| **按主导推断** | {evidence['dominant']} | 名字没线索，按解剖上占主导的那一部分给（肩推→前束、划船→菱形肌、下背→竖脊）|",
    "",
    "「按主导推断」的那部分**是可以被推翻的**：如果你练的某个动作在解剖上更偏另一个部位，",
    "告诉我动作名，我单独改它的映射。",
    "",
    "## 子部位与命中数",
    "",
    "| 子部位 | 父组 | 动作数 |",
    "|---|---|---|",
]
for sub_id, (zh, parent) in SUB.items():
    lines.append(f"| {zh} | {parent} | {counts[zh]} |")

lines += [
    "",
    "## 背部的 5 个叶子",
    "",
    "| 叶子 | 来源 | 动作数 |",
    "|---|---|---|",
    f"| 背阔肌 lats | 不拆分，原样 | {len(by_muscle.get('lats', []))} |",
    f"| 菱形肌 rhomboids | 原 middle back 桶 | {counts['菱形肌']} |",
    f"| 上斜方 traps upper | 原 traps 桶 | {counts['上斜方']} |",
    f"| 中斜方 traps middle | 原 traps 桶 | {counts['中斜方']} |",
    f"| 竖脊肌 erector spinae | 原 lower back 桶 | {counts['竖脊肌']} |",
    "",
    "## 实施顺序（下一轮）",
    "",
    "1. `muscle_group` 加 `parent_en`（可空）；插入 10 个子部位行。",
    "2. **导入器**也要按这张表把 `exercise_muscle` 写到子部位 —— 见下面的坑。",
    "3. V11 迁移为已有库补做同样的重映射。",
    "4. 体积统计：有子部位时，父组 = 各子部位之和（总量不变，但能看出缺哪一块）。",
    "4. 3D 按子部位导出网格（`Clavicular/Sternocostal/Abdominal part`、三个 deltoid part 已确认存在）。",
    "5. 2D 人体图仍是父组粒度（细分要重画 SVG 路径）。",
    "",
    "## 一个必须避开的坑",
    "",
    "`exercise_muscle` 的映射是**导入器在每次导入时重新算的**（从 exercises.json 的",
    "`primaryMuscles` 直接写库）。所以：",
    "",
    "- 只写一条 V11 迁移去重映射，**新装用户拿不到** —— V11 在导入之前跑，那时表是空的；",
    "  导入随后又把粗组写回去了。",
    "- 所以规则表必须同时进**导入器**。V11 只是给已有库补齐。",
    "",
    "## 抽查：胸部",
    "",
    "| 动作 | 子部位 | 依据 |",
    "|---|---|---|",
]
for ex, parent, sub, is_primary, why in rows:
    if parent == "chest" and is_primary:
        lines.append(f"| {ex} | {SUB[sub][0]} | {'名字写明' if why == 'named' else '主导推断'} |")

OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"  已写出 {OUT}")
print(f"  名字写明 {evidence['named']} 个 / 按主导推断 {evidence['dominant']} 个")
for zh, n in counts.most_common():
    print(f"    {zh:<8} {n}")
