"""把 Z-Anatomy 的网格名映射到我们 17 个肌群。

这是 3D 化的关键一步：Mapping is the whole product.
如果某个肌群找不到对应网格，那 3D 版就无法表达它 —— 必须在导模型之前就知道。
"""
import json
import pathlib
import re
from collections import defaultdict

d = json.loads(pathlib.Path(r"E:\devtools\cache\3d\blend-structure.json").read_text(encoding="utf-8"))
meshes = d["all_meshes"]

# 只看肌肉系统里的东西（别把骨骼、血管算进来）
muscle_meshes = [m for m in meshes if "Muscular system" in m["collection"] or "Muscles" in m["collection"]]
print("  肌肉系统里的网格: {} 个，{} 面".format(
    len(muscle_meshes), sum(m["polys"] for m in muscle_meshes)))
print()

# 每个肌群用一组关键词。宁可多匹配（后面能改），不要漏。
GROUPS = {
    "chest":        [r"pectoralis major", r"pectoralis minor", r"^pectoralis"],
    "abdominals":   [r"rectus abdominis", r"external abdominal oblique",
                     r"internal abdominal oblique", r"transversus abdominis"],
    "lats":         [r"latissimus dorsi"],
    "middle back":  [r"rhomboid"],
    "lower back":   [r"erector spinae", r"iliocostalis", r"longissimus", r"spinalis",
                     r"quadratus lumborum", r"multifidus"],
    "traps":        [r"trapezius", r"levator scapulae"],
    "shoulders":    [r"deltoid"],
    "biceps":       [r"biceps brachii", r"brachialis"],
    "triceps":      [r"triceps brachii"],
    "forearms":     [r"brachioradialis", r"flexor carpi", r"extensor carpi",
                     r"flexor digitorum", r"extensor digitorum", r"pronator", r"supinator"],
    "quadriceps":   [r"rectus femoris", r"vastus lateralis", r"vastus medialis",
                     r"vastus intermedius", r"quadriceps"],
    "hamstrings":   [r"biceps femoris", r"semitendinosus", r"semimembranosus", r"hamstring"],
    "glutes":       [r"gluteus maximus"],
    "calves":       [r"gastrocnemius", r"soleus"],
    "adductors":    [r"adductor", r"gracilis", r"pectineus"],
    "abductors":    [r"gluteus medius", r"gluteus minimus", r"tensor fasciae latae"],
    "neck":         [r"sternocleidomastoid", r"splenius", r"semispinalis capitis",
                     r"scalen", r"longus colli", r"longus capitis"],
}

print("  === 17 个肌群 -> Z-Anatomy 网格 ===")
mapping = {}
missing = []
for group, pats in GROUPS.items():
    hits = []
    for m in muscle_meshes:
        low = m["name"].lower()
        if any(re.search(p, low) for p in pats):
            hits.append(m)
    mapping[group] = [{"name": m["name"], "polys": m["polys"]} for m in hits]
    total = sum(m["polys"] for m in hits)
    flag = "" if hits else "   <<< 没有对应网格"
    print("    {:<14} {:>3} 个网格  {:>8} 面{}".format(group, len(hits), total, flag))
    if not hits:
        missing.append(group)
    for m in hits[:3]:
        print("        · {}".format(m["name"][:64]))

print()
if missing:
    print("  !! 缺失的肌群: {}".format(", ".join(missing)))
else:
    print("  17 个肌群全部有对应网格 ✓")

print()
print("  === 肌肉系统里未被映射的网格（前 25，看有没有漏掉重要的）===")
mapped_names = set()
for items in mapping.values():
    for it in items:
        mapped_names.add(it["name"])
unmapped = [m for m in muscle_meshes if m["name"] not in mapped_names]
unmapped.sort(key=lambda m: -m["polys"])
for m in unmapped[:25]:
    print("    {:<50} {:>7} 面".format(m["name"][:50], m["polys"]))
print("    （未映射共 {} 个，{} 面）".format(
    len(unmapped), sum(m["polys"] for m in unmapped)))

pathlib.Path(r"E:\devtools\cache\3d\mapping.json").write_text(
    json.dumps(mapping, ensure_ascii=False, indent=1), encoding="utf-8")
print()
print("  已写出 mapping.json")
