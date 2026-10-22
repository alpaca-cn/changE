"""导出 17 个肌群 + 一层皮肤（修正版）。

## 上一版为什么错

上一版把缩放写在**对象变换**上（`skin.scale = ...`），然后指望 glTF 导出把它
带出去。结果皮肤没缩，肌肉缩成一个小人 —— 屏幕上是一层巨大的皮包着一个小人。

这一版改成**把变换烘进网格数据**（`mesh.transform(matrix)`），并且：
  - 导入后立刻烘掉导入器给的旋转/平移，让网格数据就是世界坐标
  - 缩放与平移都直接作用在顶点上
  - 每一步之后重新量一次包围盒，**导出前把两个包围盒都打出来**

这样「对齐了没有」是日志里能读出来的数字，不是我祈祷它生效。
"""
import json
import os

import bpy
from mathutils import Matrix, Vector

MAPPING = json.load(open(os.environ["MAPPING_JSON"], encoding="utf-8"))
OUT = os.environ["GLB_OUT"]
SKIN_OBJ = os.environ.get("SKIN_OBJ", "")
TARGET_POLYS = int(os.environ.get("TARGET_POLYS", "3000"))
SKIN_POLYS = int(os.environ.get("SKIN_POLYS", "8000"))

for o in bpy.data.objects:
    o.select_set(False)


def world_bbox(objs):
    """世界坐标下的包围盒。用顶点算，不用 bound_box ——
    bound_box 是缓存的局部盒子，在各种变换之后极易读到旧值。"""
    xs, ys, zs = [], [], []
    for o in objs:
        m = o.matrix_world
        for v in o.data.vertices:
            w = m @ v.co
            xs.append(w.x)
            ys.append(w.y)
            zs.append(w.z)
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


def size_of(objs):
    lo, hi = world_bbox(objs)
    return Vector((hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2])), lo, hi


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
    objs = [bpy.data.objects.get(it["name"]) for it in items]
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
    decimate(target, TARGET_POLYS)
    target.name = group
    target.data.name = group
    exported.append(target)

muscle_size, muscle_lo, muscle_hi = size_of(exported)
print("  肌肉最终包围盒  height(Z)={:.4f}  width(X)={:.4f}  depth(Y)={:.4f}".format(
    muscle_size.z, muscle_size.x, muscle_size.y))

skin = None
if SKIN_OBJ and os.path.isfile(SKIN_OBJ):
    before = set(bpy.data.objects)
    bpy.ops.wm.obj_import(filepath=SKIN_OBJ)
    new = [o for o in bpy.data.objects if o not in before and o.type == 'MESH']
    if new:
        # 烘掉导入器给的变换：之后网格数据即世界坐标，后面只用矩阵直接改顶点
        for o in new:
            o.data.transform(o.matrix_world)
            o.matrix_world = Matrix.Identity(4)
            o.select_set(False)
        skin = new[0]
        for o in new[1:]:
            bpy.data.objects.remove(o, do_unlink=True)
        skin.name = "skin"
        skin.data.name = "skin"
        bpy.context.view_layer.update()

        raw_size, _, _ = size_of([skin])
        print("  皮肤导入后 X={:.2f} Y={:.2f} Z={:.2f}".format(
            raw_size.x, raw_size.y, raw_size.z))

        # **BodyParts3D 的 obj 是 Y-up。** 2026-10-22 实测：导入后三轴是
        # 292(X) / 667(Y) / 1719(Z) —— 最高的一维在 Y 上，也就是说「身高」被量成了
        # 「厚度」，于是缩放比算成了 0.0032，屏幕上是一层巨皮包着一个小人。
        #
        # 判据：**最长的那一维就是身高**。它不在 Z 上就先转过来。
        if raw_size.y > raw_size.z * 1.2:
            import math
            # 绕 X 轴 +90°：(x, y, z) -> (x, -z, y)，Y 变 Z
            skin.data.transform(Matrix.Rotation(math.radians(90), 4, 'X'))
            skin.data.update()
            raw_size, _, _ = size_of([skin])
            print("  已把 Y-up 转成 Z-up，现在是 X={:.2f} Y={:.2f} Z={:.2f}".format(
                raw_size.x, raw_size.y, raw_size.z))
        elif raw_size.x > raw_size.z * 1.2:
            import math
            skin.data.transform(Matrix.Rotation(math.radians(-90), 4, 'Y'))
            skin.data.update()
            raw_size, _, _ = size_of([skin])
            print("  已把 X-up 转成 Z-up，现在是 X={:.2f} Y={:.2f} Z={:.2f}".format(
                raw_size.x, raw_size.y, raw_size.z))

        # 按**身高**定缩放。姿态检查放在下面用宽高比核对：
        # 肌肉 宽/高 = 0.404，皮肤 667/1719 = 0.388 —— 差 4%，说明两者都是垂臂站姿。
        k = muscle_size.z / raw_size.z if raw_size.z else 1.0
        print("  缩放比 k={:.6f}（按身高）".format(k))

        skin.data.transform(Matrix.Scale(k, 4))
        skin.data.update()

        # 缩放后重新量，再把中心对上（不要在缩放前的旧值上算偏移）
        _, skin_lo, skin_hi = size_of([skin])
        centre_skin = [(skin_lo[i] + skin_hi[i]) / 2.0 for i in range(3)]
        centre_muscle = [(muscle_lo[i] + muscle_hi[i]) / 2.0 for i in range(3)]
        offset = Vector((centre_muscle[0] - centre_skin[0],
                         centre_muscle[1] - centre_skin[1],
                         centre_muscle[2] - centre_skin[2]))
        skin.data.transform(Matrix.Translation(offset))
        skin.data.update()

        decimate(skin, SKIN_POLYS)

        final_size, final_lo, final_hi = size_of([skin])
        print("  皮肤最终包围盒  height(Z)={:.4f}  width(X)={:.4f}  depth(Y)={:.4f}".format(
            final_size.z, final_size.x, final_size.y))
        print("  比例检查: 高 {:.3f} / 宽 {:.3f} （1.0 附近才算对齐）".format(
            final_size.z / muscle_size.z if muscle_size.z else 0,
            final_size.x / muscle_size.x if muscle_size.x else 0))
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
