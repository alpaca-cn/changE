"""导出 17 个肌群 + 一层皮肤。

皮肤来自 BodyParts3D（CC BY 4.0）的 FJ2810.obj —— 它的部件清单里
`FMA7163  skin  FJ2810`。Z-Anatomy 自己没有皮肤网格。

## 对齐怎么做的（以及它的风险）

两个模型来自不同的数据集，比例与原点都不同。这里只按**包围盒**对齐：
把皮肤的 Z 高度缩放到与肌肉一致，再把包围盒中心对齐。

这是个粗糙的对齐：如果两者的姿态不同（比如手臂张开的程度不一样），
皮肤就会偏。**所以第一次导出必须靠人眼检查**，脚本能保证的只有
「两者高度一致、中心重合」。
"""
import json
import os

import bpy

MAPPING = json.load(open(os.environ["MAPPING_JSON"], encoding="utf-8"))
OUT = os.environ["GLB_OUT"]
SKIN_OBJ = os.environ.get("SKIN_OBJ", "")
TARGET_POLYS = int(os.environ.get("TARGET_POLYS", "3000"))
SKIN_POLYS = int(os.environ.get("SKIN_POLYS", "8000"))

for o in bpy.data.objects:
    o.select_set(False)


def bbox_of(objs):
    xs, ys, zs = [], [], []
    for o in objs:
        for corner in o.bound_box:
            world = o.matrix_world @ __import__("mathutils").Vector(corner)
            xs.append(world.x)
            ys.append(world.y)
            zs.append(world.z)
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


def decimate(obj, target):
    before = len(obj.data.polygons)
    if before > target:
        mod = obj.modifiers.new(name="dec", type='DECIMATE')
        mod.ratio = target / float(before)
        with bpy.context.temp_override(active_object=obj, object=obj,
                                       selected_editable_objects=[obj]):
            bpy.ops.object.modifier_apply(modifier=mod.name)
    return len(obj.data.polygons)


exported = []
for group, items in MAPPING.items():
    names = [it["name"] for it in items]
    objs = [bpy.data.objects.get(n) for n in names]
    objs = [o for o in objs if o is not None and o.type == 'MESH']
    if not objs:
        print("  !! {} 没有网格".format(group))
        continue
    target = objs[0]
    for o in bpy.data.objects:
        o.select_set(False)
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = target
    if len(objs) > 1:
        with bpy.context.temp_override(active_object=target, selected_editable_objects=objs,
                                       object=target):
            bpy.ops.object.join()
    after = decimate(target, TARGET_POLYS)
    target.name = group
    target.data.name = group
    exported.append(target)
    print("  {:<14} -> {:>5} 面".format(group, after))

muscle_min, muscle_max = bbox_of(exported)
muscle_h = muscle_max[2] - muscle_min[2]
print("  肌肉包围盒 Z 高度: {:.3f}".format(muscle_h))

skin = None
if SKIN_OBJ and os.path.isfile(SKIN_OBJ):
    before = set(bpy.data.objects)
    # BodyParts3D 的 obj 是 Y-up、单位 mm；Blender 的导入器会做 up 轴转换。
    bpy.ops.wm.obj_import(filepath=SKIN_OBJ)
    new = [o for o in bpy.data.objects if o not in before and o.type == 'MESH']
    if new:
        skin = new[0]
        for o in new[1:]:
            bpy.data.objects.remove(o, do_unlink=True)
        skin.name = "skin"
        skin.data.name = "skin"

        skin_min, skin_max = bbox_of([skin])
        skin_h = skin_max[2] - skin_min[2]
        print("  皮肤包围盒 Z 高度: {:.3f}  (比例 {:.4f})".format(skin_h, muscle_h / skin_h))

        if skin_h > 0:
            k = muscle_h / skin_h
            skin.scale = (skin.scale[0] * k, skin.scale[1] * k, skin.scale[2] * k)
            bpy.context.view_layer.update()

            skin_min, skin_max = bbox_of([skin])
            centre_skin = [(skin_min[i] + skin_max[i]) / 2 for i in range(3)]
            centre_muscle = [(muscle_min[i] + muscle_max[i]) / 2 for i in range(3)]
            skin.location = (
                skin.location[0] + (centre_muscle[0] - centre_skin[0]),
                skin.location[1] + (centre_muscle[1] - centre_skin[1]),
                skin.location[2] + (centre_muscle[2] - centre_skin[2]),
            )
            bpy.context.view_layer.update()

        after = decimate(skin, SKIN_POLYS)
        print("  皮肤 -> {} 面".format(after))
        exported.append(skin)
else:
    print("  !! 没有提供皮肤 obj，只导出肌肉")

for o in bpy.data.objects:
    o.select_set(False)
for o in exported:
    o.select_set(True)
bpy.context.view_layer.objects.active = exported[0]

total = sum(len(o.data.polygons) for o in exported)
print("=== {} 个对象 / {} 面 ===".format(len(exported), total))

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
