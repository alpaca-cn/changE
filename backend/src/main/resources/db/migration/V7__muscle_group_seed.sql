-- V7: 17 块肌群的种子数据。
--
-- ⚠️ 这个文件是**生成的**，不要手改。它由 MuscleGroupSeedTest 从
-- com.change.seed.MuscleGroupSeed.ALL 生成；改种子请改那个类，然后重新生成：
--   cd backend
--   mvn -B clean test -Dtest=MuscleGroupSeedTest#regenerate_the_migration \
--       -Dchange.seed.write=true
--
-- 为什么要生成而不是手写：同一份 17 行数据会同时存在于 Java 常量与这份 SQL 里，
-- 手抄两份中文名与数值必然漂移；而「Java 改了、SQL 没改」没有任何东西会报警，
-- 库里的 17 块肌肉就会和代码里的不一致。生成之后两者仍可能被各自改动，所以
-- MuscleGroupSeedTest#the_seed_migration_matches_the_java_constants 先逐行解析
-- 下面的 6 个字段与 Java 常量比对，再逐字节比对本文件与生成器输出。
--
-- region_ids 是**逗号分隔的字符串**（不是数组）：多区域肌群写两个 id。
-- 词汇表见 com.change.seed.MuscleRegion。
--
-- weekly_sets_min/max 是界面上「本周容量」提醒的比较基准，不是训练建议；
-- neck 写 NULL：颈部训练不该被「本周 0 组」追着（见 MuscleGroupSeed 的类注释）。

INSERT INTO muscle_group (name_zh, name_en, body_part, region_ids, weekly_sets_min, weekly_sets_max) VALUES
    ('胸部', 'chest', '躯干前侧', 'chest_left,chest_right', 10, 20),
    ('腹直肌', 'abdominals', '躯干前侧', 'abdominals', 8, 16),
    ('背阔肌', 'lats', '躯干后侧', 'lats_left,lats_right', 10, 20),
    ('中背部', 'middle back', '躯干后侧', 'middle_back', 8, 16),
    ('下背部', 'lower back', '躯干后侧', 'lower_back', 4, 10),
    ('斜方肌', 'traps', '躯干后侧', 'traps', 6, 14),
    ('肩部', 'shoulders', '上肢', 'shoulders_left,shoulders_right', 8, 18),
    ('肱二头肌', 'biceps', '上肢', 'biceps_left,biceps_right', 6, 14),
    ('肱三头肌', 'triceps', '上肢', 'triceps_left,triceps_right', 6, 14),
    ('前臂', 'forearms', '上肢', 'forearms', 4, 10),
    ('股四头肌', 'quadriceps', '下肢', 'quadriceps_left,quadriceps_right', 10, 20),
    ('腘绳肌', 'hamstrings', '下肢', 'hamstrings_left,hamstrings_right', 8, 16),
    ('臀肌', 'glutes', '下肢', 'glutes_left,glutes_right', 8, 16),
    ('小腿', 'calves', '下肢', 'calves_left,calves_right', 6, 14),
    ('内收肌', 'adductors', '下肢', 'adductors', 4, 10),
    ('外展肌', 'abductors', '下肢', 'abductors', 4, 10),
    ('颈部', 'neck', '颈', 'neck', NULL, NULL);
