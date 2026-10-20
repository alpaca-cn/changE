-- V10: 把 muscle_group.region_ids 改成 vendored 人体图素材里的真实区域 id。
--
-- 背景：V7 播种时还没有人体图素材，region_ids 填的是 `chest_left,chest_right`
-- 这种**下划线占位名**（临时词汇表见 com.change.seed.MuscleRegion）。计划 6b 的
-- Task 1 vendor 了 vulovix/body-muscles（Apache-2.0）的
-- `frontend/src/assets/body-map/data/muscles.{front,back}.ts`；它的区域 id 是
-- 连字符 + 子分组风格：`chest-upper-left`、`lats-mid-right`。本迁移把 17 行的
-- region_ids 全部换成素材里的真实 id。
--
-- 为什么必须换：前端拿 muscle_group.region_ids 去 SVG 里找同 id 的 <path> 着色。
-- **id 对不上不会报错**——那块肌肉只是永远不着色。整张图看起来很正常，只是
-- 那一块一直是灰的；你不会注意到「背阔肌这里应该是红的，为什么没亮」。
-- 所以除了 MigrationTest 的 checksum 冻结之外，还必须有一条交叉校验测试
-- （MuscleRegionMappingTest）把库里的 region_ids 与素材原文里的 id 对起来：
-- 只改迁移、只改前端，都可能让两边重新漂开。
--
-- 映射结果：17 块肌群共映射 65 个区域；素材的另外 24 个区域**刻意不映射**——
-- head / face / head-back 不是训练目标，spine 是标志线不是肌肉，
-- serratus-anterior-* / tibialis-anterior-* / hip-flexor-* 我们 17 块里没有，
-- elbow-* / knee-* / knee-back-* / hand-* / hand-back-* / foot-* / foot-back-*
-- 是关节与四肢末端。未映射数（24）也被测试钉死：素材升级后多出一个区域时，
-- 测试会红，提醒我们显式决定它归哪块肌群、还是继续不映射。
--
-- 两条解剖学判断（写给未来的自己：不要在「优化」映射时把它们改回去）：
--
-- 1. `abductors`（外展肌）映射到 `gluteus-medius-*`：解剖上髋外展肌就是
--    臀中肌 / 臀小肌。素材没有单独的 `abductors` 区域，`gluteus-medius`
--    是唯一正确的落点。
-- 2. `glutes`（臀肌）**只**映射 `gluteus-maximus-*`，把 `gluteus-medius-*`
--    留给 `abductors`。否则两块肌群共用同一片区域，着色时互相覆盖——
--    练完臀推，图上亮起来的可能是「外展肌」，两边的容量统计也会互相污染。
--
-- region_ids 仍是逗号分隔的字符串（不是数组），逗号两侧不留空格；
-- 每行按 name_en 定位，顺序沿用计划里的映射表，便于人工核对。
UPDATE muscle_group SET region_ids = 'chest-upper-left,chest-lower-left,chest-upper-right,chest-lower-right' WHERE name_en = 'chest';
UPDATE muscle_group SET region_ids = 'abs-upper-left,abs-lower-left,abs-upper-right,abs-lower-right,obliques-left,obliques-right' WHERE name_en = 'abdominals';
UPDATE muscle_group SET region_ids = 'lats-upper-left,lats-mid-left,lats-lower-left,lats-upper-right,lats-mid-right,lats-lower-right' WHERE name_en = 'lats';
UPDATE muscle_group SET region_ids = 'traps-mid-left,traps-mid-right' WHERE name_en = 'middle back';
UPDATE muscle_group SET region_ids = 'lower-back-erectors-left,lower-back-ql-left,lower-back-erectors-right,lower-back-ql-right' WHERE name_en = 'lower back';
UPDATE muscle_group SET region_ids = 'traps-upper-left,traps-lower-left,traps-upper-right,traps-lower-right' WHERE name_en = 'traps';
UPDATE muscle_group SET region_ids = 'shoulder-front-left,shoulder-side-left,deltoid-rear-left,shoulder-front-right,shoulder-side-right,deltoid-rear-right' WHERE name_en = 'shoulders';
UPDATE muscle_group SET region_ids = 'biceps-left,biceps-right' WHERE name_en = 'biceps';
UPDATE muscle_group SET region_ids = 'triceps-long-left,triceps-lateral-left,triceps-long-right,triceps-lateral-right' WHERE name_en = 'triceps';
UPDATE muscle_group SET region_ids = 'forearm-left,forearm-flexors-left,forearm-extensors-left,forearm-right,forearm-flexors-right,forearm-extensors-right' WHERE name_en = 'forearms';
UPDATE muscle_group SET region_ids = 'quads-left,quads-right' WHERE name_en = 'quadriceps';
UPDATE muscle_group SET region_ids = 'hamstrings-medial-left,hamstrings-lateral-left,hamstrings-medial-right,hamstrings-lateral-right' WHERE name_en = 'hamstrings';
UPDATE muscle_group SET region_ids = 'gluteus-maximus-left,gluteus-maximus-right' WHERE name_en = 'glutes';
UPDATE muscle_group SET region_ids = 'calves-gastroc-medial-left,calves-gastroc-lateral-left,calves-soleus-left,calves-gastroc-medial-right,calves-gastroc-lateral-right,calves-soleus-right' WHERE name_en = 'calves';
UPDATE muscle_group SET region_ids = 'adductors-left,adductors-right' WHERE name_en = 'adductors';
UPDATE muscle_group SET region_ids = 'gluteus-medius-left,gluteus-medius-right' WHERE name_en = 'abductors';
UPDATE muscle_group SET region_ids = 'neck-left,neck-right,nape' WHERE name_en = 'neck';
