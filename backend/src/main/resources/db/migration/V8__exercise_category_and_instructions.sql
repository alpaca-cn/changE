-- V8: exercise 加 category 与 instructions 两列。
--
-- 数据集（free-exercise-db，876 条）里有四处信息在现有 exercise 表上没有位置：
-- category(7 值) / level(3 值) / force(3 值) / instructions(多段文本)。
--
-- 只加 category 与 instructions，理由：
--   category 是**功能性的**——876 条里 292 条不是 strength（123 条 stretching、
--   61 plyometrics、14 cardio…）。「记一组」的选择器必须能把它们滤掉，
--   否则拉伸会混进训练记录。
--   instructions 是「这个动作怎么做」，缺了动作库就只能当名字列表用。
-- level / force 第一阶段用不上（YAGNI），而且 dataset 是 vendor 的，
-- 需要时凭 source_id 能反查回来，不会丢。
--
-- 两列都可空：instructions 在 876 条里有 5 条是空数组（写 NULL）；
-- category 对导入前已存在的手工动作不存在，不该被强行编一个值。
ALTER TABLE exercise ADD COLUMN category     TEXT;
ALTER TABLE exercise ADD COLUMN instructions TEXT;
