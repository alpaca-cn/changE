-- V4: 餐记录与其余辅助表 6 张表（meal / meal_item / body_metric / chat_session /
--     chat_message / change_log）。
--
-- 为什么有这份文件：餐记录、身体指标、对话记录与变更日志都不是训练本身的数据，
-- 但它们各自被别的域引用（meal.photo_id 指向 V3 的 photo，撤销一次训练改动时要回放
-- change_log），所以必须与业务表同库、同事务，而不是另一个存储。这些表彼此的耦合
-- 只到这里为止，单独成一份迁移是为了让日后的改动不会牵动 V2/V3 的 checksum。
--
-- 为什么 meal 在 V4 而不是 V3：它的 photo_id 外键指向 photo(id)，而被引用的表必须先存在。
-- 于是按依赖顺序重排：V3 建食物库与照片，本文件建依赖 photo 的餐记录与其余辅助表。

CREATE TABLE meal (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 纯日期，形状锁死为 YYYY-MM-DD。理由见 V2 里 workout_session.session_date 上方的长注释。
    meal_date  TEXT    NOT NULL CHECK (meal_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    slot       TEXT    NOT NULL CHECK (slot IN ('breakfast','lunch','dinner','snack')),
    -- eaten_at 是**真时间戳**，本来就该含时间，不要加日期形状 CHECK。
    eaten_at   TEXT,
    note       TEXT,
    -- ON DELETE SET NULL（而不是默认的拒绝、也不是 CASCADE）是有意的取舍：
    -- 照片是餐的**可选元数据**。删一张照片不该被一顿饭的存在挡住（拒绝），
    -- 删照片更绝不能连带删掉用餐记录（CASCADE 会把「今天吃了什么」删没）；
    -- 所以照片没了就把这一格清空，餐本身留下。
    photo_id   INTEGER REFERENCES photo(id) ON DELETE SET NULL
);
CREATE INDEX ix_meal_date ON meal(meal_date);

CREATE TABLE meal_item (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    meal_id      INTEGER NOT NULL REFERENCES meal(id) ON DELETE CASCADE,
    food_id      INTEGER NOT NULL REFERENCES food(id),
    order_index  INTEGER NOT NULL,
    amount       REAL    NOT NULL CHECK (amount > 0),   -- 份量数值
    unit_name    TEXT,                                  -- 单位名；NULL 表示 amount 已是克
    grams        REAL    NOT NULL CHECK (grams > 0),    -- 换算后的克数，写入时算好
    origin       TEXT    NOT NULL DEFAULT 'manual'
                 CHECK (origin IN ('manual','photo','agent','takeout'))
    -- 注意：这里没有 kcal 字段，故意的。
);
CREATE INDEX ix_meal_item_meal ON meal_item(meal_id);

CREATE TABLE body_metric (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 纯日期，形状锁死为 YYYY-MM-DD。理由见 V2 里 workout_session.session_date 上方的长注释。
    measured_on TEXT    NOT NULL CHECK (measured_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    metric_type TEXT    NOT NULL,      -- weight / body_fat / waist / arm / ...
    value       REAL    NOT NULL,
    unit        TEXT    NOT NULL,
    note        TEXT
);
CREATE INDEX ix_body_metric_type_date ON body_metric(metric_type, measured_on);

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
CREATE INDEX ix_chat_message_session ON chat_message(session_id);

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
    undone      INTEGER NOT NULL DEFAULT 0,
    -- 「一键回退」是第一阶段的核心安全机制，而它唯一的依据就是 before_json：
    -- action='update' 却没有 before 快照，等于这一行永远回不去，可 UI 只看 action
    -- 就会照常画出一个「撤销」按钮——按下去才发现无事可撤。设计文档明确不允许删除，
    -- 所以 update 必然存在变更前的状态，这条约束是纯收益。
    CHECK (action <> 'update' OR before_json IS NOT NULL)
);
CREATE INDEX ix_change_log_target ON change_log(target_table, target_id);
CREATE INDEX ix_change_log_time ON change_log(changed_at);
