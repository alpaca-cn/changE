"""探明 Startup.blend 的对象结构。

只读不写：不解压纹理、不渲染，只统计「有哪些集合、哪些网格对象、各多少面」。
292 MB 的工程里对象可能上万，所以输出的是**汇总 + 取样**，不是全量清单。
"""
import bpy
import json
import os

out = os.environ.get("DUMP_OUT", r"E:\devtools\cache\3d\blend-structure.json")

meshes = []
for o in bpy.data.objects:
    if o.type != 'MESH':
        continue
    try:
        polys = len(o.data.polygons)
        verts = len(o.data.vertices)
    except Exception:
        polys = verts = -1
    meshes.append({
        "name": o.name,
        "verts": verts,
        "polys": polys,
        # 对象所在的第一个集合名：Z-Anatomy 很可能就是靠集合分组的
        "collection": o.users_collection[0].name if o.users_collection else "",
    })

meshes.sort(key=lambda m: -m["polys"])

# Collection 没有 users_collection（那是 Object 的属性）。
# 父子关系只能反向建：谁把自己列进 children，谁就是父。
parent_of = {}
for c in bpy.data.collections:
    for ch in c.children:
        parent_of[ch.name] = c.name

collections = []
for c in bpy.data.collections:
    collections.append({
        "name": c.name,
        "objects": len(c.objects),
        "children": len(c.children),
        "parent": parent_of.get(c.name, ""),
    })
collections.sort(key=lambda c: -c["objects"])

data = {
    "blend": bpy.data.filepath,
    "counts": {
        "objects": len(bpy.data.objects),
        "meshes": len(meshes),
        "collections": len(bpy.data.collections),
        "materials": len(bpy.data.materials),
    },
    "total_polys": sum(m["polys"] for m in meshes if m["polys"] > 0),
    "top_collections": collections[:60],
    "top_meshes": meshes[:80],
    # 全量名字：判断「某个肌群在不在」必须看全部，
    # 只看面数前 80 会得出错误的「0 条」结论（第一版就踩了）
    "all_meshes": meshes,
}

with open(out, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=1)

print("DUMP_OK", out, data["counts"], "total_polys=", data["total_polys"])
