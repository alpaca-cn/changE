"""从 Startup.blend 导出 changE 用的肌肉 glb。

产出：一个 .glb，里面是 17 个对象，每个对象名 = 我们的 muscleGroupId
（chest / abdominals / lats / ...），面数按组压到目标值。

为什么「按组 join 再减面」而不是逐块导出：
  浏览器的 raycaster 只认 object，一个臂群如果拆成 9 个对象，
  点选和高亮都要写 9 次。join 成一个对象、名字就是组名，前端逻辑就只有一层。

为什么减面：
  原始合计约 100 万面（仅被映射的部分）。屏幕上一个人体占不到 800px 高，
  每组留到 3000 面肉眼已经分不出差别，而体积能压到几 MB。
"""
import json
import os

import bmesh
import bpy

MAPPING = json.load(open(os.environ["MAPPING_JSON"], encoding="utf-8"))
OUT = os.environ["GLB_OUT"]
TARGET_POLYS = int(os.environ.get("TARGET_POLYS", "3000"))

print("=== 开始导出 ===")
print("组数:", len(MAPPING), "每组目标面数:", TARGET_POLYS)

# 先全部取消选择，最后只导出选中的
for o in bpy.data.objects:
    o.select_set(False)

exported = []


def joined_object(group, names):
    """把一组的网格 join 成一个对象，减面，改名。"""
    objs = [bpy.data.objects.get(n) for n in names]
    objs = [o for o in objs if o is not None and o.type == 'MESH']
    if not objs:
        print("  !! {} 没有可用网格".format(group))
        return None

    # 以第一个为容器，其余 join 进去
    target = objs[0]
    if len(objs) > 1:
        for o in bpy.data.objects:
            o.select_set(False)
        for o in objs:
            o.select_set(True)
        bpy.context.view_layer.objects.active = target
        with bpy.context.temp_override(
                active_object=target, selected_editable_objects=objs, object=target):
            bpy.ops.object.join()

    before = len(target.data.polygons)
    if before > TARGET_POLYS:
        mod = target.modifiers.new(name="dec", type='DECIMATE')
        mod.ratio = TARGET_POLYS / float(before)
        with bpy.context.temp_override(
                active_object=target, object=target, selected_editable_objects=[target]):
            bpy.ops.object.modifier_apply(modifier=mod.name)

    target.name = group
    target.data.name = group
    after = len(target.data.polygons)
    print("  {:<14} {:>7} -> {:>5} 面  ({} 个源网格)".format(group, before, after, len(objs)))
    return target


for group, items in MAPPING.items():
    obj = joined_object(group, [it["name"] for it in items])
    if obj is not None:
        exported.append(obj)

# 只导出这 17 个
for o in bpy.data.objects:
    o.select_set(False)
for o in exported:
    o.select_set(True)
bpy.context.view_layer.objects.active = exported[0]

total = sum(len(o.data.polygons) for o in exported)
print("=== 合计 {} 个对象 / {} 面 ===".format(len(exported), total))

bpy.ops.export_scene.gltf(
    filepath=OUT,
    export_format='GLB',
    use_selection=True,
    export_apply=True,
    export_materials='EXPORT',
    export_yup=True,
    export_normals=True,
    export_texcoords=False,
    export_cameras=False,
    export_lights=False,
)
print("EXPORT_OK", OUT, os.path.getsize(OUT))
