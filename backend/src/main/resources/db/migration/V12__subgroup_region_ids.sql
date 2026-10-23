-- V12: 把 2D 区域从「被拆的父组」下放到子部位。
--
-- **这个文件是生成的，不要手改。** 来源是
-- backend/src/main/resources/seed/region-inventory.json，由
-- scripts/body-map/gen-region-inventory.py 生成；测试 MuscleRegionMappingTest
-- 读同一份 JSON 来比对数据库。两边同源，改归属只需改那个脚本并重跑。
--
-- 为什么有的区域被两个子部位共用：素材是**按解剖画**的，比我们的分组粗——
-- 胸只有 upper/lower 两块（没有中胸），菱形肌没有独立区域（画在 traps-mid 里）。
-- 所以 chest-lower-* 同属中胸与下胸、traps-mid-* 同属中斜方与菱形肌；
-- 前端 BodyMap.vue 对「被 N 个肌群认领的区域」渲染 N 次，各自裁剪到一条横带。
--
-- 被拆的 5 个父组把 region_ids 清空：它们仍存在（体积极汇总到它们），
-- 但不再直接画 2D，否则就变成第三个认领者。

UPDATE muscle_group SET region_ids = 'abs-upper-left,obliques-left,abs-upper-right,abs-lower-right,abs-lower-left,obliques-right' WHERE name_en = 'abdominals';
UPDATE muscle_group SET region_ids = 'gluteus-medius-left,gluteus-medius-right' WHERE name_en = 'abductors';
UPDATE muscle_group SET region_ids = 'adductors-left,adductors-right' WHERE name_en = 'adductors';
UPDATE muscle_group SET region_ids = 'biceps-left,biceps-right' WHERE name_en = 'biceps';
UPDATE muscle_group SET region_ids = 'calves-gastroc-medial-left,calves-gastroc-lateral-left,calves-soleus-left,calves-gastroc-medial-right,calves-gastroc-lateral-right,calves-soleus-right' WHERE name_en = 'calves';
UPDATE muscle_group SET region_ids = 'chest-lower-left,chest-lower-right' WHERE name_en = 'chest-lower';
UPDATE muscle_group SET region_ids = 'chest-lower-left,chest-lower-right' WHERE name_en = 'chest-middle';
UPDATE muscle_group SET region_ids = 'chest-upper-left,chest-upper-right' WHERE name_en = 'chest-upper';
UPDATE muscle_group SET region_ids = 'forearm-left,forearm-right,forearm-flexors-left,forearm-extensors-left,forearm-flexors-right,forearm-extensors-right' WHERE name_en = 'forearms';
UPDATE muscle_group SET region_ids = 'gluteus-maximus-left,gluteus-maximus-right' WHERE name_en = 'glutes';
UPDATE muscle_group SET region_ids = 'hamstrings-medial-left,hamstrings-lateral-left,hamstrings-medial-right,hamstrings-lateral-right' WHERE name_en = 'hamstrings';
UPDATE muscle_group SET region_ids = 'lats-upper-left,lats-mid-left,lats-lower-left,lats-upper-right,lats-mid-right,lats-lower-right' WHERE name_en = 'lats';
UPDATE muscle_group SET region_ids = 'lower-back-erectors-left,lower-back-ql-left,lower-back-erectors-right,lower-back-ql-right' WHERE name_en = 'lower back-erector';
UPDATE muscle_group SET region_ids = 'traps-mid-left,traps-mid-right' WHERE name_en = 'middle back-rhomboids';
UPDATE muscle_group SET region_ids = 'neck-right,neck-left,nape' WHERE name_en = 'neck';
UPDATE muscle_group SET region_ids = 'quads-left,quads-right' WHERE name_en = 'quadriceps';
UPDATE muscle_group SET region_ids = 'shoulder-front-left,shoulder-front-right' WHERE name_en = 'shoulders-anterior';
UPDATE muscle_group SET region_ids = 'shoulder-side-left,shoulder-side-right' WHERE name_en = 'shoulders-lateral';
UPDATE muscle_group SET region_ids = 'deltoid-rear-left,deltoid-rear-right' WHERE name_en = 'shoulders-posterior';
UPDATE muscle_group SET region_ids = 'traps-mid-left,traps-lower-left,traps-mid-right,traps-lower-right' WHERE name_en = 'traps-middle';
UPDATE muscle_group SET region_ids = 'traps-upper-left,traps-upper-right' WHERE name_en = 'traps-upper';
UPDATE muscle_group SET region_ids = 'triceps-long-left,triceps-lateral-left,triceps-long-right,triceps-lateral-right' WHERE name_en = 'triceps';

UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'chest';
UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'lower back';
UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'middle back';
UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'shoulders';
UPDATE muscle_group SET region_ids = NULL WHERE name_en = 'traps';
