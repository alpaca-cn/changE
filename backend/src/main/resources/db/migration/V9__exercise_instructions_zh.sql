-- 动作的分步做法（中文）。
--
-- exercise.instructions 是英文原文（计划 2 · Task 3 导入的，871 条非空）。
-- 这一列放中文，由 seed/instructions-zh.json 在启动时写入。
--
-- 为什么单独一列而不是覆盖原列：
--   1. 英文原文是**上游数据**，覆盖了就再也拿不回来（重新导入要网络）；
--   2. 中文是**派生**的，源头改了可以重生成；
--   3. 出问题（译文错、漏译）时，原文还能兜底显示。
ALTER TABLE exercise ADD COLUMN instructions_zh TEXT;
