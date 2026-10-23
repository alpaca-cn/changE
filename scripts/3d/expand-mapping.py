"""把 mapping.json 扩展成「按子部位分组」的版本，供 3D 用。

产出 22 个对象：
  12 个不拆分的父组（原样）
  10 个子部位（chest-upper / chest-middle / cheft-lower / shoulders-anterior / ...）

## 为什么子部位名用 `父-子` 的形式

前端的体积极是按**父组**算的（数据层还没改）。名字写成 `chest-upper`，
前端就能用 `split('-')[0]` 拿到父组，点选后照样能打开右侧面板；
而 `-upper` 这一段用来告诉用户「你点的是上胸」，不再是一句「胸部」。

这比现在就改数据层更诚实：**它不声称有分块体积**，只是让人点得更准。
真实的分块体积要动 schema + 导入器 + 汇总 + UI 五处，一次做完才算数。
"""
import json
import pathlib
import re

SRC = pathlib.Path(r"E:\idea\program\change\scripts\3d\mapping.json")
OUT = pathlib.Path(r"E:\idea\program\change\scripts\3d\mapping-parts.json")

mapping = json.loads(SRC.read_text(encoding="utf-8"))

# 子部位：父组 -> [(子部位后缀, 正则)]
SPLIT = {
    "chest": [
        ("upper", r"clavicular head"),
        ("lower", r"abdominal part"),
        ("middle", r"."),          # 胸肋部 + 胸小肌 + 合并体
    ],
    "shoulders": [
        ("anterior", r"clavicular part"),
        ("posterior", r"scapular spinal part"),
        ("lateral", r"acromial part"),
        ("lateral", r"."),         # 筋膜 / 滑囊 / 合并体归中束
    ],
    "traps": [
        ("upper", r"descending part|levator scapulae"),
        ("middle", r"transverse part"),
        ("middle", r"ascending part"),  # 用户选的是 5 块背，下斜方并入中斜方
        ("upper", r"."),
    ],
    "middle back": [
        ("rhomboids", r"."),
    ],
    "lower back": [
        ("erector", r"."),
    ],
}

out = {}
for group, items in mapping.items():
    if group not in SPLIT:
        out[group] = items
        continue
    buckets = {suffix: [] for suffix, _ in SPLIT[group]}
    for it in items:
        low = it["name"].lower()
        for suffix, pat in SPLIT[group]:
            if re.search(pat, low):
                buckets[suffix].append(it)
                break
    for suffix, lst in buckets.items():
        if lst:
            out[f"{group}-{suffix}"] = lst

# 报告
print(f"  原 {len(mapping)} 组 -> 扩成 {len(out)} 个对象")
for k in out:
    if k in mapping:
        continue
    print(f"    {k:<22} {len(out[k])} 个网格  {sum(i['polys'] for i in out[k]):>7} 面")

OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"  已写出 {OUT}")
