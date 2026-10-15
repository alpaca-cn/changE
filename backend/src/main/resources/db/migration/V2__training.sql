-- V2: 训练域 6 张表。
--
-- 索引命名：唯一索引 ux_<表名全称>_<列>；普通索引 ix_<表名全称>_<列>；表名不缩写。
--
-- 为什么这条规则要写死在文件头：SQLite 给索引另起一个名字再建一次**不会报错**，
-- 只会静默多出一个功能重复的索引（写变慢、占用翻倍，而没有任何提示）。表名缩写在
-- 这里尤其危险——`ix_sessex_session` 对下一个人来说不显然等于 `session_exercise`，
-- 于是他很可能再建一个 `ix_session_exercise_session`，而两者并存时谁也不会告诉他。
-- 一条可机械照抄的规则比一句「请保持一致」有用得多。
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
    -- source_id：外部数据集的主键，必须带命名空间前缀，形如
    -- 'free-exercise-db:barbell-bench-press'。手工录入的动作必须写 NULL，
    -- **空串不算 NULL**——`source_id` 上的部分唯一索引只看 NULL：
    -- 写 '' 的动作第二次导入就撞 UNIQUE constraint failed，整批回滚；
    -- 而不带前缀的裸 id 会在接入第二个数据集时静默冲突（两个数据集都叫 'curl'）。
    source_id     TEXT,
    note          TEXT,
    archived      INTEGER NOT NULL DEFAULT 0
);
-- 部分唯一索引：只约束「有外部来源」的动作，手工动作（source_id IS NULL）可以无限重复。
CREATE UNIQUE INDEX ux_exercise_source ON exercise(source_id) WHERE source_id IS NOT NULL;
-- 同名动作必须挡在库外，而且**只能现在挡**：一旦库里出现两行同名，唯一索引就建不上，
-- 事后合并要写迁移 + 重挂 session_exercise.exercise_id + 重算所有衍生指标。而在此之前，
-- 重复行会安静地毁掉每一个统计：进步曲线分裂成两条线、周容量翻倍、PR 被拆成两个。
CREATE UNIQUE INDEX ux_exercise_name_en ON exercise(name_en);
CREATE INDEX ix_exercise_name_zh ON exercise(name_zh);

CREATE TABLE exercise_muscle (
    exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,
    muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE,
    role            TEXT    NOT NULL CHECK (role IN ('primary', 'secondary')),
    PRIMARY KEY (exercise_id, muscle_group_id)
);
CREATE INDEX ix_exercise_muscle_muscle_group ON exercise_muscle(muscle_group_id);

CREATE TABLE workout_session (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 纯日期，形状锁死为 YYYY-MM-DD（另见 meal.meal_date / body_metric.measured_on /
    -- photo.taken_on 的同款 CHECK）。这一条拦的不是脏数据，而是**静默失效**：
    -- 一旦有代码写进完整时间戳（DTO 用 LocalDateTime、前端传了 toISOString()），
    -- `WHERE session_date = '2026-10-14'` 对 '2026-10-14T03:22:11' 返回 0 行——
    -- 查询没报错，只是永远查不到；改用 date(session_date) 归一化则用不上 ix_session_date，
    -- 索引白建；按日期分组的统计还会把同一天拆成两行。
    session_date           TEXT    NOT NULL CHECK (session_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    -- started_at / ended_at 是**真时间戳**，本来就该含时间，不要加日期形状 CHECK。
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
CREATE INDEX ix_session_exercise_session ON session_exercise(session_id);
-- order_index 是「这个动作在本次训练里排第几」，同一次训练里两行同序没有意义。
CREATE UNIQUE INDEX ux_session_exercise_session_order
    ON session_exercise(session_id, order_index);

CREATE TABLE workout_set (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    session_exercise_id INTEGER NOT NULL REFERENCES session_exercise(id) ON DELETE CASCADE,
    -- set_index 的语义（冻结，不要再解释成别的意思）：
    -- **每个子组各占一个 set_index，用 set_type 区分性质。**
    -- 递减组的三段是 set_index 0/1/2 + set_type 'dropset'，巨集组同理，
    -- 而不是三段共用同一个 set_index。理由：set_index 是「带出上次重量」的对齐键，
    -- 两行同号会让对齐随机取到其中一行——而取错的那次不会有任何报错。
    set_index           INTEGER NOT NULL,
    set_type            TEXT    NOT NULL DEFAULT 'normal'
                        CHECK (set_type IN ('normal','warmup','dropset','myo','partial',
                                            'forced','tut','iso','jump')),
    -- weight / repetitions / duration_seconds 刻意**可为空**：三空的行是一个真实状态
    -- （「这一组还没做/没记」），训练中界面靠增量保存逐字段补上，加 NOT NULL 会逼客户端编造数字。
    -- 空值放行、有值必须合法，所以约束写成 `IS NULL OR ...`。
    weight              REAL,
    repetitions         INTEGER,
    duration_seconds    INTEGER,               -- 计时动作（平板支撑等），与 repetitions 二选一
    per_side            INTEGER NOT NULL DEFAULT 0,
    rir                 REAL,
    rest_seconds        INTEGER,
    completed           INTEGER NOT NULL DEFAULT 0,
    target_weight       REAL,
    target_reps         INTEGER,
    -- 这里只保证单列数值合法。「completed=1 却三空」「weight 与 duration_seconds 同时有值」
    -- 这类**跨列语义**由应用层保证，数据库层不拦。
    CHECK (weight IS NULL OR weight >= 0),
    CHECK (repetitions IS NULL OR repetitions >= 0),
    CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    CHECK (target_weight IS NULL OR target_weight >= 0),
    CHECK (target_reps IS NULL OR target_reps >= 0)
);
CREATE INDEX ix_workout_set_session_exercise ON workout_set(session_exercise_id);
-- 同一个动作下 set_index 必须唯一——否则上面那段语义冻结只是一句注释。
CREATE UNIQUE INDEX ux_workout_set_session_exercise_index
    ON workout_set(session_exercise_id, set_index);
