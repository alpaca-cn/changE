-- V12: 把 2D 区域从「被拆的父组」下放到子部位。
--
-- 背景：V11 加了 10 个子部位，但区域 id 仍挂在父组上，所以 2D 人体图只能按
-- 「胸部」整块上色，看不出是上胸还是下胸练得多。
--
-- ## 为什么有的区域被两个子部位共用
--
-- 人体图素材是**按解剖画**的，比我们的分组粗：
--   - 胸只画了 `chest-upper-*` 与 `chest-lower-*` 两块，**没有中胸**
--   - 菱形肌没有独立区域（它画在 `traps-mid-*` 那一块里，深于中斜方）
--
-- 用户选的处理方式（2026-10-23）：中胸与下胸**共用 chest-lower**，前端把这块
-- 按高度切成上下两半（中点在上者=中胸，下者=下胸）；菱形肌与中斜方共用
-- `traps-mid-*`，前端同样切成上下两半。
--
-- 所以下面有两条 UPDATE 写进同一个区域 id 集合 —— 那是**故意的**。
-- 前端 `BodyMap.vue` 对「被 N 个肌群认领的区域」渲染 N 次，各自裁剪到一条横带。
--
-- ## 为什么把父组的 region_ids 清空
--
-- 父组仍然存在（体积极汇总到它），但它不再直接画 2D：
-- 如果父组也认领同一批区域，它就变成第三个认领者，切成三份反而更乱。
-- 父组的信息由「子部位之和」体现，2D 上由子部位上色。
--
-- lats 没有拆分，保持 V10 的值不动。

-- ── 胸 ──────────────────────────────────────────────────
UPDATE muscle_group SET region_ids = NULL WHERE name_en IN ('chest');
UPDATE muscle_group SET region_ids = 'chest-upper-left,chest-upper-right'
    WHERE name_en = 'chest-upper';
UPDATE muscle_group SET region_ids = 'chest-lower-left,chest-lower-right'
    WHERE name_en = 'chest-middle';
UPDATE muscle_group SET region_ids = 'chest-lower-left,chest-lower-right'
    WHERE name_en = 'chest-lower';

-- ── 肩 ──────────────────────────────────────────────────
UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'shoulders';
UPDATE muscle_group SET region_ids = 'shoulder-front-left,shoulder-front-right'
    WHERE name_en = 'shoulders-anterior';
UPDATE muscle_group SET region_ids = 'shoulder-side-left,shoulder-side-right'
    WHERE name_en = 'shoulders-lateral';
UPDATE muscle_group SET region_ids = 'deltoid-rear-left,deltoid-rear-right'
    WHERE name_en = 'shoulders-posterior';

-- ── 斜方 ────────────────────────────────────────────────
UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'traps';
UPDATE muscle_group SET region_ids = 'traps-upper-left,traps-upper-right'
    WHERE name_en = 'traps-upper';
-- 中斜方 + 下斜方（用户选的是「背 5 块」，下斜方并入中斜方）
UPDATE muscle_group SET region_ids = 'traps-mid-left,traps-mid-right,traps-lower-left,traps-lower-right'
    WHERE name_en = 'traps-middle';

-- ── 中背 / 菱形肌 ────────────────────────────────────────
UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'middle back';
-- 与 traps-middle 共用 traps-mid-*（菱形肌在解剖上位于中斜方深层）；
-- 多出来的 traps-lower-* 给中斜方，菱形肌只要 mid。
UPDATE muscle_group SET region_ids = 'traps-mid-left,traps-mid-right'
    WHERE name_en = 'middle back-rhomboids';
UPDATE muscle_group SET region_ids = 'traps-lower-left,traps-lower-right'
    WHERE name_en = 'traps-middle';

-- ── 下背 / 竖脊肌 ────────────────────────────────────────
UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'lower back';
UPDATE muscle_group SET region_ids = 'lower-back-erectors-left,lower-back-erectors-right,lower-back-ql-left,lower-back-ql-right'
    WHERE name_en = 'lower back-erector';
