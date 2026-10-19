# 计划 4a：饮食记录（完全本地的「记一餐」）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 不联网也能记一餐——搜食物库、按份量（碗/个/勺）或直接填克数加条目，热量与三大营养素**实时算出来**，写入 `Meal`/`MealItem` 并逐行审计；今日页显示「今天吃了多少」。

**Architecture:** 写路径一个 `@Transactional`：后端**自己**用 `PortionConverter.toGrams` 把份量折成克数（前端不传克数，避免界面算出与后端不一致的换算），三层逐行写 `change_log`（`origin='user'`，与计划 3 同一套审计）。读路径一律**实时算**：`NutritionCalculator.forGrams`（每 100 克 × 实际克数）+ `sum`（合计）——**任何地方都不存绝对热量**（设计文档决定 3 的铁律）。**不需要新迁移**：`food`/`food_portion`/`meal`/`meal_item` 在 V3/V4 已建好。

**这一份是「计划 4」的第一半。** 计划 4 的另一半（拍照 / 外卖截图 / 直接说话 → AI 出草稿）依赖多模态模型与 API key，另起一份；本计划**不含任何模型调用**，整条链路不依赖网络（设计文档的核心原则）。

**Tech Stack:** JDK 25 · Spring Boot 3.5.16 · Spring Data JPA + JdbcTemplate · SQLite + Flyway · JUnit 5；前端 Vue 3 + TS + vitest。

> **状态：已完成（2026-10-19）。** 7 个任务全部落地，提交 `3dab582` / `bbdf5d1` / `260eef4` / `10fd0d0` / `5679fe1` / `9595949`。后端 214 → **274**、前端 125 → **176**，干净库 8 条端到端实测的真实数字记在总览的「计划 4a」一节。与本文写法不一致的地方见文末「实施修正」。

---

## 前置事实（已核对磁盘，不需要重新验证）

写计划前把会用到的既有事实逐条核过（计划 2/3 里「计划自己的重述失真」出现过 8 次）。

| 事实 | 值 / 位置 |
|---|---|
| **表已存在** | `food` / `food_portion`（**V3**）、`meal` / `meal_item`（**V4**）。**本计划不需要新迁移**，迁移仍是 V1–V8 |
| `food` 的列 | `id, name_zh(NOT NULL), name_en, source(NOT NULL CHECK IN ('manual','cn-food-table','agent','takeout')), kcal_per100, protein_per100, carb_per100, fat_per100, fiber_per100, sodium_per100`——**后六个都 `NOT NULL DEFAULT 0`**。**没有 `archived` 列**，所以搜索不需要过滤归档 |
| `food_portion` 的列 | `id, food_id(NOT NULL → food), unit_name(NOT NULL), grams(NOT NULL CHECK > 0)`；`UNIQUE(food_id, unit_name)` |
| `meal` 的列 | `id, meal_date(NOT NULL, **GLOB CHECK 锁死 YYYY-MM-DD**), slot(NOT NULL CHECK IN ('breakfast','lunch','dinner','snack')), eaten_at(TEXT, **没有 CHECK**), note, photo_id(→ photo, ON DELETE SET NULL)` |
| `meal_item` 的列 | `id, meal_id(→ meal ON DELETE CASCADE), food_id(→ food, **裸外键**), order_index, amount(NOT NULL **CHECK > 0**), unit_name(**可空：NULL 表示 amount 已经是克**), grams(NOT NULL CHECK > 0), origin(NOT NULL DEFAULT 'manual' CHECK IN ('manual','photo','agent','takeout'))`。**没有 kcal 字段——故意的** |
| **审计白名单已就绪** | `ChangeLogService.ALLOWED_TABLES` 已含 `"food","food_portion","meal","meal_item"`——**不要改它** |
| `ALLOWED_ORIGINS` | `user` / `agent` / `import`。本计划的写路径用 **`"user"`** |
| **纯计算层已存在且有测试** | `com.change.calc.Nutrition`（record：`kcal, protein, carb, fat, fiber, sodium`；钠**按毫克**，其余按克）、`NutritionCalculator.forGrams(Nutrition per100g, double grams) → Nutrition`（`MAX_GRAMS = 5_000`，结果两位小数）、`NutritionCalculator.sum(List<Nutrition>) → Nutrition`（**空列表返回全 0**）、`PortionConverter.toGrams(double amount, String unit, List<Unit> units) → double`、`PortionConverter.Unit(String name, double grams)`、`PortionConverter.index(...)`。测试：`NutritionCalculatorTest` / `PortionConverterTest` **已存在**——**本计划不重写这些计算，只接线** |
| `toGrams` 的三个关键语义 | ① **`unit` 传 `null`/空串表示 `amount` 本身就是克数**（直接返回 amount）；② `amount` 必须 **> 0**（与 `meal_item.amount CHECK > 0` 同口径）；③ 返回值是**未定标的原始乘积**——`calc/package-info` 约定二明确「定标（两位小数）属于显示层，不在这里做」，所以**不要把 grams 先四舍五入再存** |
| 数值定义域必须对齐 | `toGrams` 的输出**直接喂** `forGrams`，两者的上界共用 `MAX_GRAMS`；上游放行、下游拒绝会产出「数据存得下但算不出来」的记录（热量没有快照可兜底） |
| `Round.to2` | **包私有**（`com.change.calc`）——`calc` 之外拿不到，所以服务层不要试图自己定标 |
| **`java.time` 的两个坑（照抄计划 1/3 的结论）** | `meal_date` 有 GLOB CHECK，映射错了会**响亮失败**；`eaten_at` **没有 CHECK**，xerial 驱动默认会把 `LocalDateTime` 写成纪元毫秒而**静默存成数字串**。所以实体上要照抄 `workout_session` 的注解：`@JdbcTypeCode(SqlTypes.LOCAL_DATE)` / `@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)` |
| **快照里不要放 `java.time` 对象** | `ChangeLogService` 用的是裸 `new ObjectMapper()`（没注册 `JavaTimeModule`），放 `LocalDate`/`LocalDateTime` 会在**记日志那一步**抛异常、把整次写入弄失败。日期与时间戳一律 `.toString()` |
| 布尔值进快照用 0/1 | 本计划涉及的表**没有布尔列**，这条用不上，写在这里是为了别照抄进无关的地方 |
| `meal_item.food_id` 是裸外键 | 测试里清库的顺序必须是子表在前：`meal_item` → `meal` → `food_portion` → `food` |
| **没有**对应的实体/仓储 | `com.change.domain` 只有 `Exercise/ExerciseMuscle/MuscleGroup/WorkoutSession/SessionExercise/WorkoutSet`；`com.change.repo` 只有那六个。**四个实体与四个仓储都要新建** |
| Runner 放哪 | `com.change.config`（`ExerciseImportRunner` / `ExerciseNameZhRunner` 在那里），seed 放 `src/main/resources/seed/` |
| 现有前端接口 | `frontend/src/api/training.ts` 的 `callApi` 是前端**唯一**发请求的地方（走 `invoke('api_request')`）；饮食域照它的形状写 `api/nutrition.ts`，**不要再写一套 fetch** |
| 当前测试数 | `mvn -B clean test` → **214 条、0 失败、1 skipped**；`pnpm test` → **125 passed（13 files）**；`cargo test` → 93 passed + 4 integration，3 ignored |

---

## 接口契约（本计划新增，前端要用）

基地址由外壳持有；前端只给 path。所有响应 JSON。

| 方法 | 路径 | 请求 | 响应要点 |
|---|---|---|---|
| GET | `/api/foods` | `?q=&limit=` | `[{id, nameZh, nameEn, source, per100:{kcal,protein,carb,fat,fiber,sodium}, portions:[{unitName,grams}]}]`；`q` 匹配中文名或英文名（英文不分大小写）；limit 默认 50、封顶 200 |
| POST | `/api/meals` | `MealInput`（见下） | `{mealId, items, nutrition:{…}}`；不合法 → **400** `{error}` |
| GET | `/api/meals/{id}` | — | `{id, mealDate, slot, eatenAt, note, items:[{mealItemId, foodId, nameZh, orderIndex, amount, unitName, grams, nutrition:{…}}], nutrition:{…}}`；不存在 → **404** |
| GET | `/api/meals` | `?date=YYYY-MM-DD` | 当天的餐（按 `id` 升序）——`[{id, mealDate, slot, eatenAt, note, items, nutrition}]` |
| GET | `/api/nutrition/daily` | `?date=YYYY-MM-DD`（省略＝今天） | `{date, nutrition:{…}, meals, items}`；**空的一天是全 0，不是 404** |

`MealInput`（POST body）——**注意没有 `grams` 字段**：

```json
{
  "mealDate": "2026-10-19",
  "slot": "lunch",
  "eatenAt": "2026-10-19T12:30",
  "note": "食堂",
  "items": [
    { "foodId": 3, "amount": 1,   "unitName": "碗" },
    { "foodId": 7, "amount": 150, "unitName": null }
  ]
}
```

**为什么前端不传 `grams`**：`grams` 是 `amount × 每单位克数`，而每单位克数在 `food_portion` 里。让前端算就等于把「哪个单位是多少克」抄一份到前端，两边一旦不一致，写进去的克数就是错的，而**热量永远是实时算的、没有快照可以兜底**。所以克数由后端用 `PortionConverter.toGrams` 算——前端只在**预览**时按同一公式估一下。

`nutrition` 的形状固定为六个字段：`{kcal, protein, carb, fat, fiber, sodium}`（钠按毫克）。

---

## 文件结构

**新建（后端）**

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/change/domain/Food.java` | 食物库一行（每 100 克六项） |
| `backend/src/main/java/com/change/domain/FoodPortion.java` | 份量换算（碗=150 克） |
| `backend/src/main/java/com/change/domain/Meal.java` | 一餐 |
| `backend/src/main/java/com/change/domain/MealItem.java` | 餐中条目（份量 + 换算克数） |
| `backend/src/main/java/com/change/repo/FoodRepo.java` | 按名称搜食物 |
| `backend/src/main/java/com/change/repo/FoodPortionRepo.java` | 一次取多个食物的单位表 |
| `backend/src/main/java/com/change/repo/MealRepo.java` | 按日期取餐 |
| `backend/src/main/java/com/change/repo/MealItemRepo.java` | 一次取多餐的条目（按 `order_index`） |
| `backend/src/main/java/com/change/service/NutritionService.java` | 写一餐（事务 + 审计）、读一餐/当天合计 |
| `backend/src/main/java/com/change/service/FoodQueryService.java` | 食物搜索（带单位表） |
| `backend/src/main/java/com/change/web/NutritionController.java` | `/api/foods`、`/api/meals`、`/api/nutrition/daily` |
| `backend/src/main/resources/seed/food-zh.json` | 常吃项 seed（见 Task 2 的数值口径） |
| `backend/src/main/java/com/change/config/FoodSeedRunner.java` | 启动时幂等写入 seed |

**新建（测试）**

| 文件 |
|---|
| `backend/src/test/java/com/change/repo/MealItemRepoTest.java` |
| `backend/src/test/java/com/change/service/NutritionServiceTest.java` |
| `backend/src/test/java/com/change/service/FoodQueryServiceTest.java` |
| `backend/src/test/java/com/change/web/NutritionControllerTest.java` |
| `backend/src/test/java/com/change/config/FoodSeedRunnerTest.java` |

**新建（前端）**

| 文件 | 职责 |
|---|---|
| `frontend/src/api/nutrition.ts` | 饮食域类型 + 调用（复用 `training.ts` 的 `callApi`） |
| `frontend/src/api/nutrition.spec.ts` | 上者的测试 |
| `frontend/src/views/nutrition-form.ts` | 条目的纯函数：份量→克数、预览热量、请求体 |
| `frontend/src/views/nutrition-form.spec.ts` | 上者的测试 |
| `frontend/src/views/DietView.spec.ts` | 饮食记录页测试 |

**修改（前端）**

| 文件 | 改什么 |
|---|---|
| `frontend/src/views/DietView.vue` | 占位 → 真正的饮食记录页 |
| `frontend/src/views/TodayView.vue` | 加「今天吃了多少」（调 `/api/nutrition/daily`）＋ 补测试 |
| `frontend/src/views/TodayView.spec.ts` | 今日摄入的断言 |
| `frontend/src/api/__fixtures__/training-responses.ts` | 或新建 `nutrition-responses.ts`：饮食域的真机夹具 |

> **不改**：任何迁移（表已存在）、`ChangeLogService`（白名单已含四张表）、`ApiErrors`、`api/training.ts` 的 `callApi`、`tauri.conf.json`、capabilities。

---

## Task 1: 四个实体 + 四个仓储

**Files:**
- Create: `domain/Food.java`、`domain/FoodPortion.java`、`domain/Meal.java`、`domain/MealItem.java`
- Create: `repo/FoodRepo.java`、`repo/FoodPortionRepo.java`、`repo/MealRepo.java`、`repo/MealItemRepo.java`
- Test: `repo/MealItemRepoTest.java`

**要守住的两件事**：① `Meal.mealDate` 与 `Meal.eatenAt` 必须照抄 `WorkoutSession` 的 `@JdbcTypeCode` 注解（一个是响亮失败、一个是**静默**存成数字串）；② `MealItemRepo` 必须**按 `orderIndex` 升序**——调用方（餐详情）依赖顺序，反了不会报错、只会错位。

- [x] **Step 1: 先看 `WorkoutSession.java`**
  把 `@JdbcTypeCode(SqlTypes.LOCAL_DATE)` / `(SqlTypes.LOCAL_DATE_TIME)` 那两行**原样抄过来**（计划 3 的前置事实第 3 条就是踩过的坑）。字段用 public 字段还是 getter 沿用既有实体的写法（**不要**一半一种）。

- [x] **Step 2: 写失败的测试**（`MealItemRepoTest`）

| 测试 | 断言 |
|---|---|
| `items_come_back_in_order_index_order_not_insertion_order` | 先插 `order_index=1`、再插 `0` → 读回来是 `[0, 1]`（按插入序会红） |
| `the_meal_date_round_trips_as_an_iso_string` | 直接 `jdbc.queryForObject("SELECT meal_date …", String.class)` → `"2026-10-19"`（**用 JdbcTemplate 读原始列**，绕开实体，才看得见驱动写进去的到底是什么） |
| `the_eaten_at_is_not_stored_as_epoch_millis` | 同上读 `eaten_at` → 以 `"2026-10-19T12:30"` 开头。**这条是那个静默坑的杀手**：`eaten_at` 没有 CHECK，写错不会报错，只会存成 `1750000000000` 这样的串 |

- [x] **Step 3: 跑测试确认失败 → 写实现 → 跑测试确认通过**

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -q -Dtest=MealItemRepoTest test
```

- [x] **Step 4: 提交**

```bash
git add backend/src/main/java/com/change/domain/Food.java \
        backend/src/main/java/com/change/domain/FoodPortion.java \
        backend/src/main/java/com/change/domain/Meal.java \
        backend/src/main/java/com/change/domain/MealItem.java \
        backend/src/main/java/com/change/repo/FoodRepo.java \
        backend/src/main/java/com/change/repo/FoodPortionRepo.java \
        backend/src/main/java/com/change/repo/MealRepo.java \
        backend/src/main/java/com/change/repo/MealItemRepo.java \
        backend/src/test/java/com/change/repo/MealItemRepoTest.java
git commit -m "feat(nutrition): 饮食域的四张表实体与仓储"
```

---

## Task 2: 常吃项 seed（**数值口径要诚实**）

**Files:**
- Create: `backend/src/main/resources/seed/food-zh.json`
- Create: `backend/src/main/java/com/change/config/FoodSeedRunner.java`
- Test: `backend/src/test/java/com/change/config/FoodSeedRunnerTest.java`

**⚠️ 这一条最容易被写成假数据，先说清口径**：
- **中国食物成分表的转录版有版权、不可分发**（设计文档明确），所以**不许**把它 vendored 进来。
- seed 里放 **30 条左右**常见中式食物/食材，每 100 克的六项数值是**常见公开资料的量级近似值**，**由智能体填写、未经逐条核对**——这一点必须写在 `food-zh.json` 的顶部注释与 `FoodSeedRunner` 的类注释里，并在总览里登记为待人工抽查项。
- 设计文档对精度的定位是「**不需要太精准，大致热量即可。精度不是目标，可修正是**」，所以近似值是可接受的**前提是它被说成近似值**——不要在任何地方写「权威」「准确」。
- 每条都带 `source: "manual"`；每条至少一个 `portions` 项（否则「1 碗」这种常用单位选不了）。

- [x] **Step 1: 写 `food-zh.json`**（形状：`[{nameZh, nameEn, source, per100:{…}, portions:[{unitName, grams}]}]`）

  覆盖要实用：主食（白米饭/馒头/面条/全麦面包）、蛋白（鸡胸/鸡蛋/牛奶/豆腐/牛肉/鱼）、蔬菜水果（西兰花/番茄/苹果/香蕉）、常见菜（番茄炒蛋/宫保鸡丁）。**至少 30 条**。

- [x] **Step 2: 写失败的测试**

| 测试 | 断言 |
|---|---|
| `the_seed_file_parses_and_every_item_has_a_name_and_at_least_one_portion` | 解析成功；条数 ≥ 30；每条 `nameZh` 非空、`portions` 非空、每个 `grams > 0`——**这同时是 schema 校验**（`food_portion.grams` 有 `CHECK > 0`，一份 grams=0 的 seed 会让启动直接炸） |
| `seeding_is_idempotent` | 连跑两次 → `food` 行数不变（按 `name_zh + source='manual'` 跳过已存在） |
| `every_seed_value_is_non_negative_and_finite` | 六项数值都 `>= 0` 且有限（`Nutrition` 的紧凑构造器本来就拒绝 NaN/负值，但**在写入前就拦**能报出是**哪一条**食物坏了） |
| `the_seed_does_not_contain_a_disallowed_source` | 所有 `source` ∈ `('manual','cn-food-table','agent','takeout')`——**尤其不能出现 `cn-food-table`**（那会谎称数据来自不可分发的成分表） |

- [x] **Step 3: 跑测试 → 写 Runner → 跑测试**

  `FoodSeedRunner` 形状照抄 `ExerciseNameZhRunner`（`ApplicationRunner` + 日志打计数）。**日志必须能看出它干了什么**，例如
  `食物库：写入 30 / 已存在跳过 0 / 非法跳过 0`。

- [x] **Step 4: 提交**

```bash
git commit -m "feat(nutrition): 常吃项 seed（30 条，数值为近似值并标注）"
```

---

## Task 3: 记一餐（一个事务 + 逐行审计）

**Files:**
- Create: `service/NutritionService.java`、`web/NutritionController.java`
- Test: `service/NutritionServiceTest.java`、`web/NutritionControllerTest.java`

**要守住的三件事**：① 三层一次写完，任何一步失败整批回滚；② **克数由后端算**（`PortionConverter.toGrams`，前端传的 `unitName` 只是选择）；③ 每行都进 `change_log`（`origin='user'`），日期进快照一律 `.toString()`。

- [x] **Step 1: 写失败的测试**

`NutritionServiceTest`（`@SpringBootTest` + `@Transactional`，照 `WorkoutServiceTest` 的形状）：

| 测试 | 断言 |
|---|---|
| `records_a_meal_and_logs_every_row` | 一餐 2 个条目 → `Result.items()==2`；`meal`/`meal_item` 都落库；`change_log` 按**本次写入的 target_id** 收口：`meal` 1 条、`meal_item` 2 条（**不要**用全局 `COUNT(*) WHERE origin='user'`——共享库里有别的测试提交过的 user 日志） |
| `computes_grams_from_the_unit_not_from_the_client` | 传 `amount=1, unitName="碗"`（该食物 1 碗 = 150 克）→ 库里 `grams == 150`；传 `unitName=null, amount=175` → `grams == 175` |
| `grams_are_stored_unrounded_not_display_scaled` | 一个 `1 个 = 55.5 克` 的单位 × `1.5` → `grams == 83.25`（**不是** 83.2 或 83）——`calc/package-info` 约定二：定标属于显示层，先舍再累加会引入有方向性的偏差 |
| `the_snapshot_stores_dates_as_strings_not_java_time_objects` | `change_log.after_json` 含 `"meal_date":"2026-10-19"`（放 `LocalDate` 会在记日志那步抛异常，把整次写入弄失败） |
| `refuses_a_meal_with_no_items` | `IllegalArgumentException`，message 含「一餐」或「条目」 |
| `refuses_an_unknown_portion_unit` | `unitName="盆"` → 异常（`PortionConverter` 的「未知的份量单位」原样透出） |
| `refuses_an_unknown_food` | `foodId` 不存在 → 异常，**并且整批回滚**（断言没有留下半条 `meal_item`） |
| `refuses_a_bad_slot` | `slot="brunch"` → 异常（`meal.slot` 的 CHECK 只认四个值；**在服务层先拦**才能给出 400 而不是 500） |

`NutritionControllerTest`（独立库文件，照 `TrainingControllerTest` 的形状，`@BeforeEach` 按 `meal_item → meal → food_portion → food` 清）：

| 测试 | 断言 |
|---|---|
| `posts_a_meal_and_returns_its_totals` | 200，`$.items==2`，`$.nutrition.kcal` 是数字 |
| `a_bad_request_is_a_400_with_a_readable_message` | `items: []` → 400 且 `$.error` 存在 |

- [x] **Step 2: 跑测试确认失败**

```powershell
mvn -B -q -Dtest=NutritionServiceTest test
```

- [x] **Step 3: 写 `NutritionService`**

  形状与 `WorkoutService` 对齐（record 化的 DTO 嵌在服务里、构造器注入仓储、`@Transactional` 的 `recordMeal`）。要点：
  - `MealInput(LocalDate mealDate, String slot, LocalDateTime eatenAt, String note, List<ItemInput> items)`；`ItemInput(Long foodId, double amount, String unitName)`
  - `Result(long mealId, int items, Nutrition nutrition)`
  - 克数：`PortionConverter.toGrams(amount, unitName, unitsOf(foodId))`——**单位表从 `food_portion` 取**，不要自己造
  - 合计：`NutritionCalculator.sum(items.stream().map(i -> NutritionCalculator.forGrams(per100(food), i.grams)).toList())`
  - 每插入一行就 `changeLog.recordInsert("meal"|"meal_item", id, snapshot, "user", null)`；快照里 `meal_date`/`eaten_at` 用 `.toString()`
  - 校验：`mealDate != null`、`slot` ∈ 四值、`items` 非空、每条 `foodId != null` 且食物存在、`amount > 0`

- [x] **Step 4: 写 `NutritionController`**（POST + 两个 GET 的骨架可以在 Task 4/5 补齐，但**建议一次把三个端点写完再分别补测试**——`ApiErrors` 已经能把 `IllegalArgumentException` 翻成 400，不用额外处理）

- [x] **Step 5: 跑测试确认通过 → 提交**

```bash
git commit -m "feat(nutrition): 记一餐（事务 + 后端算克数 + 逐行审计）"
```

---

## Task 4: 读一餐与当天合计

**Files:**
- Modify: `service/NutritionService.java`、`web/NutritionController.java`
- Test: `service/NutritionServiceTest.java`、`web/NutritionControllerTest.java`

- [x] **Step 1: 补测试**

| 测试 | 断言 |
|---|---|
| `the_meal_detail_reads_items_in_order_with_their_nutrition` | 一餐 2 个条目（顺序反过来插）→ `items` 按 `orderIndex` 升序；每项有 `grams` 与算出来的 `nutrition`；顶层 `nutrition` 是两项之和 |
| `an_unknown_meal_has_no_detail` | ——/不存在的 id → `empty()` → HTTP **404**（走 `ResponseStatusException`，`ApiErrors` 会给它包上 `{error}`） |
| `daily_totals_sum_all_meals_of_the_day` | 同一天记两餐 → `nutrition` 是两餐之和；`meals==2` |
| `an_empty_day_is_all_zeros_not_a_404` | 那天没记 → 200 且六项都是 0，`meals==0`——**「没吃」与「查不到」是两回事** |
| `daily_totals_ignore_other_days` | 相邻两天各记一餐 → 各自只算自己那天 |
| `a_bad_date_is_a_400` | `?date=2026/10/19` → 400（`ApiErrors` 已覆盖 `MethodArgumentTypeMismatchException`） |

- [x] **Step 2: 实现 → 跑测试 → 提交**

```bash
git commit -m "feat(nutrition): 读一餐与当天合计"
```

---

## Task 5: 食物搜索（带单位表）

**Files:**
- Create: `service/FoodQueryService.java`
- Modify: `web/NutritionController.java`
- Test: `service/FoodQueryServiceTest.java`、`web/NutritionControllerTest.java`

**为什么一次把 `portions` 也带出来**：前端要**预览**热量就得知道「1 碗等于多少克」，而这一份数据在 `food_portion` 里。让前端再发一次请求既慢又容易和 POST 时后端算的克数不一致。**注意：前端算出来的只是预览，入库的克数与合计一律由后端算。**

- [x] **Step 1: 补测试**

| 测试 | 断言 |
|---|---|
| `matches_chinese_or_english_ignoring_case` | 搜「豆腐」与「tofu」都能命中同一条 |
| `an_empty_query_lists_foods` | `q` 省略 → 返回前若干条（不是空数组） |
| `the_limit_is_capped_at_200` | `limit=9999` → 至多 200 条 |
| `portions_ride_along_and_are_empty_for_a_food_without_units` | 有单位的食物带上 `portions`；没有的给 `[]`，不是 `null` |
| `every_food_carries_the_per100_values` | 六个字段都在（前端预览要用；少一个界面会显示 `NaN`） |

- [x] **Step 2: 实现 → 跑测试 → 提交**

```bash
git commit -m "feat(nutrition): 食物搜索（带份量单位表）"
```

---

## Task 6: 前端——饮食记录页与今日摄入

**Files:**
- Create: `api/nutrition.ts`、`api/nutrition.spec.ts`、`views/nutrition-form.ts`、`views/nutrition-form.spec.ts`、`views/DietView.spec.ts`
- Modify: `views/DietView.vue`、`views/TodayView.vue`、`views/TodayView.spec.ts`
- Test: 见上

**三条硬约束**：
1. **不发 HTTP**——复用 `api/training.ts` 导出的 `callApi`（它走 `invoke('api_request')`）。
2. **条目里不存绝对热量**：界面上的 kcal 是**预览**；保存后显示的是**后端返回的合计**。
3. **公式只能有一处**：前端预览用 `views/nutrition-form.ts` 里的纯函数，并配一条**契约测试**——拿一段**真机** `/api/meals/{id}` 的响应，断言「前端按 `per100 + grams` 算出来的每项热量」与「后端算出来的」一致（这是把两份实现钉在一起的那种测试；不一致时它红）。

- [x] **Step 1: 写 `api/nutrition.ts`**

  形状照抄 `api/training.ts`：类型（`FoodSummary`/`Portion`/`Nutrition`/`MealInput`/`MealDetail`/`DailyNutrition`）+ `searchFoods()` / `recordMeal()` / `mealDetail()` / `mealsOn()` / `dailyNutrition()`。错误分流沿用 `callApi`，**不要重复实现**。

- [x] **Step 2: 写 `views/nutrition-form.ts`**（纯函数，可单测）

```ts
/** 份量 → 克数。`unitName` 为 null 表示 amount 本身就是克。**与后端 PortionConverter 同口径**。 */
export function toGrams(amount: number, unitName: string | null, portions: Portion[]): number | null

/** 一条条目的**预览**热量。**显示层**才定标（保留一位），存储不是它的事。 */
export function itemNutrition(per100: Nutrition, grams: number): Nutrition

/** 表单 → POST /api/meals 的请求体。**不含 grams**（克数由后端算）。 */
export function toMealInput(args: {...}): MealInput
```

  **空值口径**：`amount` 空串 → `null`（不是 0）；`unitName` 空 → `null`（表示「按克算」）——`Number('') === 0` 那条老账，计划 3 已吃过一次。

- [x] **Step 3: 写 `DietView.vue`**

  流程：选日期 + 餐次 → 搜食物（防抖 300ms，照 `TrainingView`）→ 选食物 → 选份量单位（下拉，含「克」）或直接填克数 → 「加一条目」→ 条目表（每行显示 `amount × 单位 → 克` 与**预览** kcal；可删行）→ 「保存这一餐」→ 显示**后端返回的**合计。

  界面上必须写清两件事（计划 3 的「打勾的组才算训练量」那次教训）：
  - 每 100 克是**食物属性**，你填的是**这一次的份量**；
  - 保存后显示的数字是**后端算的**，与上面的预览可能差最后一两位小数。

- [x] **Step 4: 给 `TodayView` 加「今天吃了多少」**（调 `/api/nutrition/daily`，展示 kcal + 三大营养素；**空的一天显示 0**）＋补 `TodayView.spec.ts` 的断言。

- [x] **Step 5: 测试与构建**

```powershell
cd frontend
pnpm test
pnpm build
```

- [x] **Step 6: 提交**

```bash
git commit -m "feat(frontend): 饮食记录页与今日摄入"
```

---

## Task 7: 端到端验收与文档

**Files:** Modify: `docs/superpowers/plans/2026-10-14-change-phase1-overview.md`

- [x] **Step 1: 干净库全链路（照计划 3 Task 7 的做法，用 Python 直打 HTTP + 只读 SQLite）**

| # | 请求 | 期望 |
|---|---|---|
| 1 | `GET /api/foods?q=豆腐` | 200，至少一条，带 `per100` 与 `portions` |
| 2 | `POST /api/meals`（1 碗米饭 + 150 克鸡胸） | 200，`items==2`，`nutrition.kcal` 是一个合理的数 |
| 3 | 直接读库：`SELECT grams FROM meal_item …` | 米饭那行 = 该食物「碗」的克数（**不是前端传的数**）；鸡胸那行 = `150` |
| 4 | `GET /api/meals/{id}` | 200，两项目按 `orderIndex` 升序，各自有 `nutrition`，顶层是两项之和 |
| 5 | `GET /api/nutrition/daily?date=<今天>` | 200，合计等于第 4 条之和，`meals==1` |
| 6 | `SELECT COUNT(*) FROM change_log WHERE origin='user'` | = `1 + 2 = 3` |
| 7 | `GET /api/nutrition/daily?date=<别的日期>` | 200，全 0（**不是 404、不是 500**） |
| 8 | 再启动一次 | `食物库：写入 0 / 已存在跳过 30`——seed 幂等，不重复插 |

  > 每一步的真实输出都要抄进文档。

- [x] **Step 2: 记进总览**：补「计划 4a：饮食记录（本地）」一节——三条口径（后端算克数、实时算热量、不存绝对热量）、seed 的数值口径与待抽查项、8 条真实结果、测试条数。

- [x] **Step 3: `mvn -B clean test` 与 `pnpm test` 全绿并报告条数**（不要用「看着像绿的」代替报告数字）

- [x] **Step 4: 提交**

```bash
git commit -m "docs: 总览补计划 4a 的端到端实测结果"
```

---

## 完成标准

- [x] `cd backend && mvn -B clean test` 全绿，**报告实际条数**（本计划开始时 214）
- [x] `cd frontend && pnpm test` 全绿（起点 125），`pnpm build` 退 0
- [x] **无新迁移**：迁移仍是 V1–V8、连续无缺口；`ChangeLogService.ALLOWED_TABLES` 一个字符都没改
- [x] **记一餐是三层一个事务**；每行都进 `change_log`（`origin='user'`）；快照里的日期是**字符串**
- [x] **克数由后端算**：库里 `meal_item.grams` 来自 `PortionConverter`，不是前端传的
- [x] **grams 存的是未定标的值**（约定二）；定标只在显示层
- [x] **绝对热量不落库**：`meal_item` 上没有 kcal 列，也没有任何地方回存合计
- [x] **空的一天 → 全 0**（不是 404）；不存在的餐 → **404**（`ApiErrors` 给它 `{error}`）；参数写错 → **400**
- [x] seed 的数值口径在文件头、类注释与总览里都写明了是**近似值、未经逐条核对**
- [x] 前端**没有**新增 `fetch`（全走 `callApi`）；预览公式有**契约测试**钉住它和后端一致
- [x] 总览里补上计划 4a 的真实结果
- [x] `git status --short` 无输出（`.commandcode/` 除外）

## 风险与未知

| 风险 | 为什么担心 | 怎么应对 |
|---|---|---|
| `eaten_at` 静默存成纪元毫秒 | 那一列**没有 CHECK**，映射错了不报错、只写进一个数字串 | Task 1 有一条测试**用 JdbcTemplate 读原始列**（绕开实体）；实体照抄 `WorkoutSession` 的 `@JdbcTypeCode` |
| 前端传 `grams` | 两边各算一次，不一致时写进去的是错数据，而热量没有快照兜底 | 契约里**没有** `grams` 字段；Task 3 有一条测试钉「1 碗 = 150」与「unitName=null 时 amount 就是克」 |
| 把 grams 先定标再存 | `calc/package-info` 约定二：先舍再累加会有方向性偏差，两条录入路径显示的热量不一致 | Task 3 有一条测试断言 `1.5 × 55.5 == 83.25` |
| 前端预览公式与后端分叉 | 界面上显示 300 kcal、存进去算出来 305，用户会以为程序算错了 | Task 6 的契约测试拿**真机响应**把两边钉在一起 |
| seed 的数值被当成权威值 | 近似值一旦不带说明地进了界面，用户会按它做决定 | 文件头 + 类注释 + 总览三处写明「近似、未逐条核对」；某条被断言不许出现 `source='cn-food-table'`（那会谎称来自不可分发的成分表） |
| 把版权数据顺手 vendor 进来 | 中国食物成分表转录版不可再分发，而它「看起来很该有」 | 非目标里明确排除；seed 上限 30 条手工近似值；`source` 校验挡住 `cn-food-table` |
| 共享库里的 `origin='user'` 计数 | 别的测试（`ChangeLogJoinTableTest`）会留下真实提交的日志 | 审计断言一律**按本次写入的 target_id 收口**，不用全局计数（计划 3 的教训） |
| 「没吃」被当成「查不到」 | 空的一天返回 404 会让刚装上的人以为坏了 | Task 4 有一条专门的测试断言空的一天是 200 + 全 0 |
| `meal_item.food_id` 是裸外键 | 测试清库顺序错了会撞 FK（计划 3 踩过同一个坑） | 清库顺序写死在 `@BeforeEach`：`meal_item → meal → food_portion → food` |

## 非目标（本计划不做）

- **不做拍照 / 外卖截图 / 直接说话 → AI 草稿**——那是计划 4b，要先定多模态模型与 API key 的处理
- **不做食物库管理界面**——设计文档明确「不做管理界面；直接改数据即可」
- **不做营养素目标 / 建议摄入量 / 超标提示**——先能把数字记准，再谈参照系
- **不做份量单位的自定义编辑**（新增/改「1 碗 = 多少克」）——seed 里带常用单位；改数据即可
- **不做日/周/月的营养报表与图表**——那是计划 6 的进步曲线那一侧
- **不 vendor 任何有版权的食物成分数据**
- **不做导出/导入食物库**——设计文档说「使用者若需要中式食物库，由他自己导入」，那条路等 4b 一起设计

---

## 实施修正（2026-10-19，实现过程中发现）

1. **seed 的安装策略改了：不是「逐条按名字 upsert」，而是「食物库为空时才装一次」。**
   Task 2 原写的是「按 `name_zh + source='manual'` 已存在则跳过」。逐条 upsert 有个坏处：
   用户删掉一条不想再看到的 seed 食物（比如不吃红烧肉），**重启会被加回来**——
   而设计文档说食物库就是「直接改数据即可」。改成「库里只要有任何一条就整体跳过」，
   删除就是真的删除；代价是把整张表删空会重新装一遍，那反而是想要的。
   `does_not_resurrect_a_food_the_user_deleted` 就是钉这个行为的（变异实测：
   把那条判断改成 `if (false)` → 它和 `does_not_re_seed_…` 一起变红）。

2. **`new ObjectMapper().enable(FAIL_ON_UNKNOWN_PROPERTIES)` 那一行今天是冗余的。**
   变异实测：删掉它，15 条**全绿**——因为裸 `ObjectMapper` 本来就把它默认为 `true`
   （被 Spring Boot 关掉的是**容器里那个**）。所以「protein 拼成 protien 会静默变 0」
   这件事目前靠的是 Jackson 的默认值，而不是这一行。保留它并把这件事写在代码注释里，
   理由见注释（哪天有人改成注入 Spring 的 mapper，它就是唯一还拦得住的东西）。
   **这条记在这里是因为它说明一个更一般的教训**：变异不红不等于守卫在起作用，
   也可能是「那件事本来就成立」——两者要分清。

3. **测试文件比计划多一个**：计划只列了 `FoodSeedRunnerTest`，实际另加了
   `MealRepoTest`（`meal_date` / `eaten_at` 的落库形态）——那两个字段的测试放在
   `MealItemRepoTest` 里会名不副实。
