-- V3: 饮食域。
--
-- 为什么有这份文件：一条饮食记录要同时回答「吃了什么」和「吃了多少」，而这两个问题的
-- 分辨率完全不同——营养值对某个食物是固定属性，份量则每次都不一样。所以营养值只在
-- food 表里按每 100 克存一份，meal_item 只记份量与换算后的克数，热量由读取时现算。
--
-- 铁律：营养一律按每 100 克存；条目只存份量与换算克数，绝不存绝对热量。

CREATE TABLE food (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name_zh      TEXT    NOT NULL,
    name_en      TEXT,
    source       TEXT    NOT NULL,     -- 'free-exercise-db' | 'manual' | 'cn-food-table'
    kcal_per100  REAL    NOT NULL DEFAULT 0,
    protein_per100 REAL  NOT NULL DEFAULT 0,
    carb_per100  REAL    NOT NULL DEFAULT 0,
    fat_per100   REAL    NOT NULL DEFAULT 0,
    fiber_per100 REAL    NOT NULL DEFAULT 0,
    sodium_per100 REAL   NOT NULL DEFAULT 0
);
CREATE INDEX ix_food_name_zh ON food(name_zh);

CREATE TABLE food_portion (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    food_id   INTEGER NOT NULL REFERENCES food(id) ON DELETE CASCADE,
    unit_name TEXT    NOT NULL,        -- 碗 / 个 / 勺 / 份
    grams     REAL    NOT NULL CHECK (grams > 0)
);
CREATE UNIQUE INDEX ux_food_portion ON food_portion(food_id, unit_name);

CREATE TABLE meal (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    meal_date  TEXT    NOT NULL,
    slot       TEXT    NOT NULL CHECK (slot IN ('breakfast','lunch','dinner','snack')),
    eaten_at   TEXT,
    note       TEXT,
    photo_id   INTEGER                 -- 外键在 V4 建表后才有意义，此处不加约束
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
