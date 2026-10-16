-- V3: 食物库与照片 3 张表（food / food_portion / photo）。
--
-- 为什么有这份文件：一条饮食记录要同时回答「吃了什么」和「吃了多少」，而这两个问题的
-- 分辨率完全不同——营养值对某个食物是固定属性，份量则每次都不一样。所以营养值只在
-- food 表里按每 100 克存一份，meal_item 只记份量与换算克数，热量由读取时现算。
--
-- 为什么 photo 在这份迁移而不是 V4：meal.photo_id 要指向 photo(id)，外键要求被引用
-- 的表先存在。所以按依赖顺序重排——本文件建食物库与照片，V4 建餐记录（依赖 photo）
-- 与其余辅助表。一份迁移仍然只承担一个域，只是把「照片」归到了它被引用的那一侧。
--
-- 铁律：营养一律按每 100 克存；条目只存份量与换算克数，绝不存绝对热量。

CREATE TABLE food (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    -- name_zh 刻意**不加唯一约束**：中式食物里「同名不同做法」的条目真实存在
    -- （同一个菜名对应完全不同的配料与热量）。同名条目由导入器负责去重与报告，
    -- 不在数据库层强制唯一——在这里唯一化会逼导入器丢掉真实存在的条目。
    name_zh      TEXT    NOT NULL,
    name_en      TEXT,
    -- 食物来源枚举。注意这里**没有** free-exercise-db：那是动作数据集，不是食物库
    -- （V2 的 exercise.source_id 才是它的命名空间）。这条注释曾经误抄了动作那边的枚举。
    source       TEXT    NOT NULL
                 CHECK (source IN ('manual','cn-food-table','agent','takeout')),
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

-- 照片先建在这里（被 meal.photo_id 引用），本表没有任何指向业务表的引用。
CREATE TABLE photo (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 纯日期，形状锁死为 YYYY-MM-DD。理由见 V2 里 workout_session.session_date 上方的长注释。
    taken_on   TEXT    NOT NULL CHECK (taken_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    rel_path   TEXT    NOT NULL,       -- 只存相对路径，根目录由配置决定
    sha256     TEXT    NOT NULL,
    width      INTEGER,
    height     INTEGER,
    kind       TEXT    NOT NULL CHECK (kind IN ('food','body','other')),
    note       TEXT
);
CREATE UNIQUE INDEX ux_photo_sha ON photo(sha256);
CREATE INDEX ix_photo_kind_date ON photo(kind, taken_on);
