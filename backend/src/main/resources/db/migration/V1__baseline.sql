-- V1: 基线。只建一张探针表，用来验证 Flyway 真的跑起来了。
CREATE TABLE IF NOT EXISTS schema_probe (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    note       TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO schema_probe (note, created_at)
VALUES ('baseline', '2026-10-14T00:00:00');
