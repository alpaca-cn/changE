-- V4: 辅助域。
--
-- 为什么有这份文件：照片、身体指标、对话记录与变更日志都不是训练或饮食本身的数据，
-- 但它们各自被前两个域引用（meal.photo_id、撤销一次训练改动时要回放 change_log），
-- 所以必须与业务表同库、同事务，而不是另一个存储。这五张表彼此的耦合只到这里为止，
-- 单独成一份迁移是为了让日后的改动不会牵动 V2/V3 的 checksum。

CREATE TABLE photo (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    taken_on   TEXT    NOT NULL,
    rel_path   TEXT    NOT NULL,       -- 只存相对路径，根目录由配置决定
    sha256     TEXT    NOT NULL,
    width      INTEGER,
    height     INTEGER,
    kind       TEXT    NOT NULL CHECK (kind IN ('food','body','other')),
    note       TEXT
);
CREATE UNIQUE INDEX ux_photo_sha ON photo(sha256);
CREATE INDEX ix_photo_kind_date ON photo(kind, taken_on);

CREATE TABLE body_metric (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    measured_on TEXT    NOT NULL,
    metric_type TEXT    NOT NULL,      -- weight / body_fat / waist / arm / ...
    value       REAL    NOT NULL,
    unit        TEXT    NOT NULL,
    note        TEXT
);
CREATE INDEX ix_metric_type_date ON body_metric(metric_type, measured_on);

CREATE TABLE chat_session (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT,
    created_at TEXT    NOT NULL
);

CREATE TABLE chat_message (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    INTEGER NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role          TEXT    NOT NULL CHECK (role IN ('user','assistant','tool')),
    content       TEXT,
    tool_calls    TEXT,                -- JSON 数组，记录本次调用了哪些工具
    created_at    TEXT    NOT NULL
);
CREATE INDEX ix_chat_msg_session ON chat_message(session_id);

CREATE TABLE change_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    changed_at  TEXT    NOT NULL,
    origin      TEXT    NOT NULL CHECK (origin IN ('user','agent')),
    action      TEXT    NOT NULL CHECK (action IN ('insert','update')),
    target_table TEXT   NOT NULL,
    target_id   INTEGER NOT NULL,
    before_json TEXT,                  -- insert 时为 NULL
    after_json  TEXT    NOT NULL,
    chat_session_id INTEGER,
    undone      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_changelog_target ON change_log(target_table, target_id);
CREATE INDEX ix_changelog_time ON change_log(changed_at);
