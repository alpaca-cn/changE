-- V11: 子部位。
--
-- 起因：用户反馈「胸只有胸部、肩只有肩部，太笼统了。胸部有上中下，肩有前中后，
-- 背有背阔菱形等」。3D 模型那边 Z-Anatomy 本来就按解剖分好了网格
-- （Clavicular/Sternocostal/Abdominal part、三个 deltoid part），
-- 缺的是**数据层的粒度**。
--
-- ## parent_en 这一列
--
-- 子部位的父组。NULL = 自己就是顶层（原 17 个）。
-- 体积极一律按「叶子」算，再由 WeeklyVolumeService 汇总到父组：
--   父组本周组数 = 自己的 + 所有子部位的
-- 这样总量不变，但界面上能看出「缺的是下胸」。
--
-- ## 为什么用 `父-子` 的英文名
--
-- 与 3D 模型里的对象名**逐字一致**（`chest-upper` / `shoulders-anterior` …）。
-- 前端拿对象名就能查到对应的肌群行，不需要第二张对照表。
--
-- ## 为什么 region_ids 是 NULL、weekly_sets 也是 NULL
--
-- region_ids：2D 人体图仍是父组粒度（细分要重画 SVG 路径）。
--   子部位带区域 id 会让 2D 上同一块区域被两个肌群争着上色。
-- weekly_sets：参考区间是给「一块肌肉」的，子部位没有独立区间；
--   给个假数字会让「本周容量」的提醒乱跳。NULL 表示不提醒
--   （VolumeCalculator 对未配置区间返回 NOT_CONFIGURED）。

ALTER TABLE muscle_group ADD COLUMN parent_en TEXT;

INSERT INTO muscle_group (name_zh, name_en, body_part, region_ids, weekly_sets_min, weekly_sets_max, parent_en) VALUES
    ('上胸',         'chest-upper',           '躯干前侧', NULL, NULL, NULL, 'chest'),
    ('中胸',         'chest-middle',          '躯干前侧', NULL, NULL, NULL, 'chest'),
    ('下胸',         'chest-lower',           '躯干前侧', NULL, NULL, NULL, 'chest'),
    ('三角肌前束',   'shoulders-anterior',    '上肢',     NULL, NULL, NULL, 'shoulders'),
    ('三角肌中束',   'shoulders-lateral',     '上肢',     NULL, NULL, NULL, 'shoulders'),
    ('三角肌后束',   'shoulders-posterior',   '上肢',     NULL, NULL, NULL, 'shoulders'),
    ('上斜方',       'traps-upper',           '躯干后侧', NULL, NULL, NULL, 'traps'),
    ('中斜方',       'traps-middle',          '躯干后侧', NULL, NULL, NULL, 'traps'),
    ('菱形肌',       'middle back-rhomboids', '躯干后侧', NULL, NULL, NULL, 'middle back'),
    ('竖脊肌',       'lower back-erector',    '躯干后侧', NULL, NULL, NULL, 'lower back');
