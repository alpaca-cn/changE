-- V6: change_log.origin 增加 'import'，并把 after_json 放宽成可空。
--
-- origin 的两处都要改，缺一不可：
--   1. 这里的 CHECK 约束（数据库侧兜底）
--   2. ChangeLogService.ALLOWED_ORIGINS（Java 侧先拦，给出能读懂的报错）
-- 只改一侧的失效形态是「Java 放行、DB 拒绝」，用户看到的是一句
-- [SQLITE_CONSTRAINT] CHECK constraint failed: change_log——所以两侧都有测试钉着
-- （ChangeLogJoinTableTest#origin_import_is_accepted 走行为，
-- SchemaTest#java_whitelist_matches_the_ddl 走静态比对）。
--
-- ⚠️ V4__auxiliary.sql 里 change_log.origin 那句注释与 CHECK 仍然写着 ('user','agent')，
-- 它**过时了**。但不能回头去改：已发布迁移的 checksum 被 Flyway 与
-- MigrationTest#published_migrations_are_byte_frozen 双双钉死，改动一个字节就会让
-- 所有跑过 V4 的库在下次启动时抛 FlywayValidateException。所以指向本文件的那句话
-- 只能写在这里——读到 V4 那行时，请以本文件与 SchemaTest 的 schema 快照为准。
--
-- SQLite 改不了 CHECK，只能重建表。⚠️ 重建的基线**不是**凭记忆重述的列清单，而是
-- V4__auxiliary.sql 里逐字的那份 DDL：10 列，列顺序 id / changed_at / origin / action /
-- target_table / target_id / before_json / after_json / chat_session_id / undone，
-- 索引 2 个（ix_change_log_target / ix_change_log_time），CHECK 1 条。
-- 本文件相对 V4 的改动只有四处，全部列出：
--   1. origin 的 CHECK 增加 'import'（本次目的之一）。
--   2. action 的 CHECK 增加 'delete'。**这一处计划里漏了**：V4 原文是
--      ('insert','update')，而同一任务的 recordDelete（Step 6）要写 action='delete'，
--      不放开这一处，delete 记录会在 DB 侧被直接拒绝。plan 重述的 DDL 里已经含
--      'delete'，说明这是它的本意，但真实 V4 里没有——属于「照记忆重述」丢掉的第三个差异。
--   3. after_json 由 NOT NULL 放宽成可空（删除没有「改动之后」的状态）。
--   4. 原来那条 CHECK (action <> 'update' OR before_json IS NOT NULL) 被下面三条
--      更严的 action 快照规则取代（对 update 是加强：before 与 after 都要求非空）。
-- 索引 2 个全部按原样重建，见文件末尾——漏一个不会有任何报错，只会让审计查询退化成
-- 全表扫描。
--
-- 列顺序保持与 V4 一致（chat_session_id 在 undone 之前）。顺序不影响语义，但它属于
-- SchemaTest#column_snapshot_is_exact_for_all_fifteen_tables 快照的一部分，乱一次就得
-- 解释一次；更重要的是**照记忆重述很容易把这两列写反**——本计划的重述里就是反的。
--
-- 关于 PRAGMA foreign_keys：不需要关。change_log 没有被任何表引用
-- （chat_session_id 是裸 INTEGER，没有 REFERENCES；V1–V4 里没有任何 REFERENCES change_log），
-- 所以 DROP TABLE 触发不了外键动作。而且该开关「在事务内是 no-op」，Flyway 又把迁移
-- 包在事务里——写了也不会生效，只会让人以为关掉了。
--
-- 索引名同样是**全库唯一**的：旧表上的 ix_change_log_target / ix_change_log_time 在
-- DROP TABLE 之前一直占着这两个名字，所以索引必须等旧表没了、新表改名之后再建。

CREATE TABLE change_log_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    changed_at      TEXT    NOT NULL,
    origin          TEXT    NOT NULL CHECK (origin IN ('user','agent','import')),
    action          TEXT    NOT NULL CHECK (action IN ('insert','update','delete')),
    target_table    TEXT    NOT NULL,
    target_id       INTEGER NOT NULL,
    before_json     TEXT,                  -- insert 时为 NULL
    after_json      TEXT,                  -- delete 时为 NULL（V6 之前是 NOT NULL）
    chat_session_id INTEGER,
    undone          INTEGER NOT NULL DEFAULT 0,
    -- 「一键回退」是第一阶段的核心安全机制，而它唯一的依据就是 before_json：
    -- action='update' 却没有 before 快照，等于这一行永远回不去，可 UI 只看 action
    -- 就会照常画出一个「撤销」按钮——按下去才发现无事可撤。
    --
    -- 下面三条把这条原则推广到三种 action，并且三种都**两侧钉死**。逐条的理由：
    --   insert：必须有 after，不然不知道插了什么；
    --   update：两边都要有（撤销靠 before，追溯靠 after）；
    --   delete：必须有 before（不然不知道删了什么），且 after **必须为空**——
    --           不是「允许为空」而是「必须为空」。V6 之前 after_json 是 NOT NULL，
    --           调用方为了让 INSERT 通过，只能拿 before 去充数，那写进库的是一句假话；
    --           将来任何读 after_json 的代码都会得出一份「删掉之后长这样」的错误结论。
    --           审计表里最不该有的就是「看起来合法但语义是错的」行。
    CHECK (action <> 'insert' OR after_json IS NOT NULL),
    CHECK (action <> 'update' OR (before_json IS NOT NULL AND after_json IS NOT NULL)),
    CHECK (action <> 'delete' OR (before_json IS NOT NULL AND after_json IS NULL))
);

-- 显式带上 id：审计行号在重建前后必须一致。已有数据一定满足上面三条新约束
-- （旧 schema 下 insert/update 的 after 恒非空、update 的 before 恒非空、action 只有
-- insert/update），所以这次拷贝不会因为 CHECK 失败——真失败了也应当让迁移整个失败，
-- 而不是跳过坏行。
INSERT INTO change_log_new (id, changed_at, origin, action, target_table, target_id,
                            before_json, after_json, chat_session_id, undone)
SELECT id, changed_at, origin, action, target_table, target_id,
       before_json, after_json, chat_session_id, undone
FROM change_log;

DROP TABLE change_log;
ALTER TABLE change_log_new RENAME TO change_log;

-- 两个索引按 V4 的原样重建。少了它们不会报错，只会让「查某个目标行的改动历史」
-- 与「按时间倒序列出最近改动」这两条最常走的审计查询变成全表扫描。
CREATE INDEX ix_change_log_target ON change_log(target_table, target_id);
CREATE INDEX ix_change_log_time ON change_log(changed_at);
