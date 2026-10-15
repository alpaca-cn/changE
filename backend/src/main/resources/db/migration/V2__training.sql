-- V2: 训练域。
--
-- 为什么有这份文件：「一次训练 = 多动作 × 多组」是这个应用的核心事实，一个动作的
-- 同一组里既要知道「我们打算做多少」，也要知道「实际做了多少」。把这两件事放进不同的表
-- 会让「今天练得比计划重还是轻」变成一次跨表对齐，因此这里刻意建成三层嵌套
-- （workout_session → session_exercise → workout_set），并让目标值与实际值同处一行。
-- 组与组之间的差异用 set_type 枚举表达，而不是靠「第几组」的序号去猜。
--
-- 第一行先清掉 V1 的探针表：它是基线迁移的历史遗留，没有业务含义，不该永久留在
-- 每个用户的库里。删在这里而不是回头去改 V1——V1 的 checksum 已经发布，一动所有
-- 跑过它的库启动即抛 FlywayValidateException。
DROP TABLE schema_probe;

CREATE TABLE muscle_group (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name_zh         TEXT    NOT NULL,
    name_en         TEXT    NOT NULL,
    body_part       TEXT    NOT NULL,      -- 逗号分隔的多值（如 "left,right"），需要时再拆表
    region_ids      TEXT,                  -- 人体图区域 id，逗号分隔
    weekly_sets_min INTEGER,
    weekly_sets_max INTEGER
);
CREATE UNIQUE INDEX ux_muscle_group_en ON muscle_group(name_en);

CREATE TABLE exercise (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name_en       TEXT    NOT NULL,
    name_zh       TEXT,
    equipment     TEXT,
    is_compound   INTEGER NOT NULL DEFAULT 0,
    default_unit  TEXT    NOT NULL DEFAULT 'kg',
    source_id     TEXT,                    -- free-exercise-db 的 id
    note          TEXT,
    archived      INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_exercise_source ON exercise(source_id) WHERE source_id IS NOT NULL;
CREATE INDEX ix_exercise_name_zh ON exercise(name_zh);

CREATE TABLE exercise_muscle (
    exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,
    muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE,
    role            TEXT    NOT NULL CHECK (role IN ('primary', 'secondary')),
    PRIMARY KEY (exercise_id, muscle_group_id)
);
CREATE INDEX ix_exercise_muscle_muscle ON exercise_muscle(muscle_group_id);

CREATE TABLE workout_session (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    session_date           TEXT    NOT NULL,   -- ISO-8601 日期
    started_at             TEXT,
    ended_at               TEXT,
    title                  TEXT,
    perceived_state        TEXT,
    note                   TEXT,
    source_routine_day_id  INTEGER             -- 为将来「计划驱动」预留，第一阶段不写
);
CREATE INDEX ix_session_date ON workout_session(session_date);

CREATE TABLE session_exercise (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    INTEGER NOT NULL REFERENCES workout_session(id) ON DELETE CASCADE,
    exercise_id   INTEGER NOT NULL REFERENCES exercise(id),
    order_index   INTEGER NOT NULL,
    note          TEXT,
    superset_group TEXT                        -- 同值即超级组；NULL 表示不参与超级组
);
CREATE INDEX ix_sessex_session ON session_exercise(session_id);

CREATE TABLE workout_set (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    session_exercise_id INTEGER NOT NULL REFERENCES session_exercise(id) ON DELETE CASCADE,
    set_index           INTEGER NOT NULL,
    set_type            TEXT    NOT NULL DEFAULT 'normal'
                        CHECK (set_type IN ('normal','warmup','dropset','myo','partial',
                                            'forced','tut','iso','jump')),
    weight              REAL,
    repetitions         INTEGER,
    duration_seconds    INTEGER,               -- 计时动作（平板支撑等），与 repetitions 二选一
    per_side            INTEGER NOT NULL DEFAULT 0,
    rir                 REAL,
    rest_seconds        INTEGER,
    completed           INTEGER NOT NULL DEFAULT 0,
    target_weight       REAL,
    target_reps         INTEGER
);
CREATE INDEX ix_set_sessex ON workout_set(session_exercise_id);
