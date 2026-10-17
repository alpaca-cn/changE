-- V5: 给 exercise_muscle 加代理主键，让它进得了审计网。
--
-- 起因：ChangeLogService.ALLOWED_TABLES 的准入条件是「单列整型 id」（回退靠
-- `WHERE id = ?` 定位目标行），而这张表原来是复合主键 (exercise_id, muscle_group_id)，
-- 定位不到，于是它被排除在审计之外。后果是：创建一个动作并挂 3 块肌肉，日志里只有
-- 1 行——那 3 行 join 记录从不进日志，所以「凭日志重建状态」做不到（undo 会级联删掉
-- 它们，日志里没有任何东西能让它们回来）。
--
-- 加一个代理 id 之后这张表就能被现有的通用回放机制原样覆盖，不需要在
-- ChangeLogService 里为它开特例。以后任何 join 表遇到同样问题都按这一条办：
-- 加代理 id，而不是把它排除在审计之外。
--
-- 业务唯一性从「主键」降级成「唯一索引」ux_exercise_muscle_pair：
-- 同一个动作对同一块肌肉只能标一次。这条降级是有风险的——索引漏建不会报任何错，
-- 只会让同一个动作能把同一块肌肉标两次，而「本周 20 组」会被静默算成 40 组。
-- 守卫有两条：SchemaTest 的索引快照（名字 + 唯一性）与
-- ChangeLogJoinTableTest#the_unique_index_on_the_pair_still_holds（真的插第二行会报错）。
--
-- ⚠️ 语句顺序不能按「建表 → 建索引 → 搬数据 → DROP → RENAME」来写：
-- SQLite 的索引名是**全库唯一**的，而旧表上已经有 ix_exercise_muscle_muscle_group，
-- 在新表上建同名索引会直接报 "index ... already exists"。所以必须先把旧表 DROP 掉
-- 腾出索引名，再在改名后的表上按原名重建索引——索引 DDL 因此与 V2 里的那一条逐字相同。
--
-- 关于 PRAGMA foreign_keys：本文件与 V6 都**不需要**关它。被重建的表没有被任何表引用
-- （已核对：V1–V4 里没有任何 REFERENCES exercise_muscle / REFERENCES change_log），
-- 所以 DROP TABLE 触发不了任何外键动作。另外这个开关「在事务内是 no-op」，而 Flyway
-- 把迁移包在事务里——写进脚本也不会生效，只会留下「以为关掉了」的错觉。

CREATE TABLE exercise_muscle_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,
    muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE,
    role            TEXT    NOT NULL CHECK (role IN ('primary', 'secondary'))
);

-- 显式带上旧主键的两列，而不是让 AUTOINCREMENT 重新发号：join 行的身份在重建前后应当
-- 一致，日志里已经记下的 target_id 才不会指向别处。旧主键 (exercise_id, muscle_group_id)
-- 本身就保证了这一组值无重复，所以唯一索引放在搬数据之后建也不会被脏数据挡住；
-- 反过来若真有重复，CREATE UNIQUE INDEX 会**报错失败**，而不是静默放过——
-- 这正是我们要的失败形态（迁移失败比静默破坏唯一性安全得多）。
INSERT INTO exercise_muscle_new (exercise_id, muscle_group_id, role)
SELECT exercise_id, muscle_group_id, role FROM exercise_muscle;

DROP TABLE exercise_muscle;
ALTER TABLE exercise_muscle_new RENAME TO exercise_muscle;

-- 两个索引必须一并重建（旧的两个随 DROP TABLE 一起没了）。
-- 漏掉 ix_exercise_muscle_muscle_group 不会有任何报错，只会让「按肌群查动作」
-- 退化成全表扫描——那是最难发现的一类退化。
CREATE UNIQUE INDEX ux_exercise_muscle_pair
    ON exercise_muscle(exercise_id, muscle_group_id);
CREATE INDEX ix_exercise_muscle_muscle_group ON exercise_muscle(muscle_group_id);
