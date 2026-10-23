"""生成 2D 区域归属的**单一事实源**，并据此写出 V12 迁移。

## 为什么要有这份清单

2D 细分改了三次都没落地，原因不是代码难，而是「89 个区域分别归谁」这张账
散在三处：V10 的 SQL、`MuscleRegionMappingTest` 里写死的 65/24 计数、
以及前端素材里的 id。改一处，另两处就打脸。

所以这里把它收成一份 JSON：`backend/src/main/resources/seed/region-inventory.json`。
V12 由它生成；测试读它来比对数据库。两边同源，就不可能再互相打脸。

## 归属规则

1. 以 V10 的归属为基线（65 个区域已分给 12 个没被拆的肌群 + 5 个父组）
2. 把那 5 个父组的区域**下放**给子部位：
     chest-upper-*        -> chest-upper
     chest-lower-*        -> chest-middle + chest-lower   （素材只有上下两块，中/下胸共用，前端切横带）
     shoulder-front-*     -> shoulders-anterior
     shoulder-side-*      -> shoulders-lateral
     deltoid-rear-*       -> shoulders-posterior
     traps-upper-*        -> traps-upper
     traps-mid-*          -> traps-middle + middle back-rhomboids （菱形肌在中斜方深层，共用）
     traps-lower-*        -> traps-middle
     lower-back-erectors-* / lower-back-ql-* -> lower back-erector
3. 其余 24 个区域**故意不归属**：头、脸、手、脚、膝、肘、脊柱、
   前锯肌、髂腰肌、胫骨前肌 —— 它们不在 17 个肌群里（见 V10 的注释）。
"""
import json
import pathlib
import re

ROOT = pathlib.Path(r"E:\idea\program\change")
FRONT = ROOT / "frontend/src/assets/body-map/data/muscles.front.ts"
BACK = ROOT / "frontend/src/assets/body-map/data/muscles.back.ts"
V10 = ROOT / "backend/src/main/resources/db/migration/V10__muscle_region_ids.sql"
INVENTORY = ROOT / "backend/src/main/resources/seed/region-inventory.json"
V12 = ROOT / "backend/src/main/resources/db/migration/V12__subgroup_region_ids.sql"

# ── 1) 全部区域 id，按素材顺序 ────────────────────────────
all_ids = []
for f in (FRONT, BACK):
    t = f.read_text(encoding="utf-8")
    all_ids += re.findall(r"id:\s*['\"]([a-z0-9-]+)", t)
all_ids = list(dict.fromkeys(all_ids))

# ── 2) V10 基线 ───────────────────────────────────────────
v10 = V10.read_text(encoding="utf-8")
base = {}
for m in re.finditer(r"region_ids = '([^']+)' WHERE name_en = '([^']+)'", v10):
    for rid in m.group(1).split(","):
        base[rid.strip()] = m.group(2)

# ── 3) 下放规则：区域前缀 -> 子部位（第一个是主归属）───────
SPLIT = [
    ("chest-upper-", ["chest-upper"]),
    ("chest-lower-", ["chest-middle", "chest-lower"]),
    ("shoulder-front-", ["shoulders-anterior"]),
    ("shoulder-side-", ["shoulders-lateral"]),
    ("deltoid-rear-", ["shoulders-posterior"]),
    ("traps-upper-", ["traps-upper"]),
    ("traps-mid-", ["traps-middle", "middle back-rhomboids"]),
    ("traps-lower-", ["traps-middle"]),
    ("lower-back-erectors-", ["lower back-erector"]),
    ("lower-back-ql-", ["lower back-erector"]),
]
SPLIT_PARENTS = {"chest", "shoulders", "traps", "middle back", "lower back"}

groups_by_region = {}
for rid in all_ids:
    owners = None
    for prefix, subs in SPLIT:
        if rid.startswith(prefix):
            owners = subs
            break
    if owners is None:
        parent = base.get(rid)
        if parent in SPLIT_PARENTS:
            raise SystemExit(f"区域 {rid} 仍挂在被拆父组 {parent} 上，规则没覆盖")
        owners = [parent] if parent else []
    groups_by_region[rid] = owners

# ── 4) 生成清单 ──────────────────────────────────────────
inventory = {
    "_comment": "2D 区域归属的单一事实源。V12 与 MuscleRegionMappingTest 都读这里。",
    "_generated_by": "scripts/body-map/gen-region-inventory.py",
    "regions": [
        {"id": rid, "groups": groups_by_region[rid]}
        for rid in all_ids
    ],
}
INVENTORY.parent.mkdir(parents=True, exist_ok=True)
INVENTORY.write_text(json.dumps(inventory, ensure_ascii=False, indent=1), encoding="utf-8")

mapped = sum(1 for v in groups_by_region.values() if v)
claims = sum(len(v) for v in groups_by_region.values())
unmapped = [k for k, v in groups_by_region.items() if not v]
print(f"  区域清单: {len(all_ids)} 个，已归属 {mapped}，未归属 {len(unmapped)}")
print(f"  归属条目（共享区域计多次）: {claims}")

# ── 5) 生成 V12 ──────────────────────────────────────────
by_group = {}
for rid, owners in groups_by_region.items():
    for g in owners:
        by_group.setdefault(g, []).append(rid)

lines = [
    "-- V12: 把 2D 区域从「被拆的父组」下放到子部位。",
    "--",
    "-- **这个文件是生成的，不要手改。** 来源是",
    "-- backend/src/main/resources/seed/region-inventory.json，由",
    "-- scripts/body-map/gen-region-inventory.py 生成；测试 MuscleRegionMappingTest",
    "-- 读同一份 JSON 来比对数据库。两边同源，改归属只需改那个脚本并重跑。",
    "--",
    "-- 为什么有的区域被两个子部位共用：素材是**按解剖画**的，比我们的分组粗——",
    "-- 胸只有 upper/lower 两块（没有中胸），菱形肌没有独立区域（画在 traps-mid 里）。",
    "-- 所以 chest-lower-* 同属中胸与下胸、traps-mid-* 同属中斜方与菱形肌；",
    "-- 前端 BodyMap.vue 对「被 N 个肌群认领的区域」渲染 N 次，各自裁剪到一条横带。",
    "--",
    "-- 被拆的 5 个父组把 region_ids 清空：它们仍存在（体积极汇总到它们），",
    "-- 但不再直接画 2D，否则就变成第三个认领者。",
    "",
]

for g in sorted(by_group):
    if g in SPLIT_PARENTS:
        continue
    ids = ",".join(by_group[g])
    lines.append(f"UPDATE muscle_group SET region_ids = '{ids}' WHERE name_en = '{g}';")

lines.append("")
for g in sorted(SPLIT_PARENTS):
    lines.append(f"UPDATE muscle_group SET region_ids = NULL WHERE name_en = '{g}';")

V12.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"  已写出 {V12.name}（{sum(1 for l in lines if l.startswith('UPDATE'))} 条 UPDATE）")
