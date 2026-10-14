-- 探针迁移：验证 Flyway 能否在 SQLite 上建表并记录历史
CREATE TABLE IF NOT EXISTS flyway_probe (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    note TEXT NOT NULL
);
INSERT INTO flyway_probe(note) VALUES ('flyway-works-on-sqlite');
