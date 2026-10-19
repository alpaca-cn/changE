/**
 * 饮食域的真机响应夹具——**全是 2026-10-19 从干净库里的后端抓下来的**（只压掉了空白，
 * 字段与数值一个没改）。抓法见计划 4a 的端到端脚本：`java -jar` 起后端 →
 * `curl`/`urllib` 打 HTTP → 原文写文件。
 *
 * 库里当时的状态：随包 seed 装了 35 条食物；记了一餐（1 碗白米饭 + 150 克鸡胸肉）。
 *
 * 为什么不手写：手写的会漏掉真实形态（`grams` 是 `150.0`、`per100` 里 `carb` 是 `0.0`），
 * 而这份夹具要用来把**前端的预览公式**和**后端的算法**钉在一起——
 * 数值只要差一点点，那条契约测试就没有意义了。
 */

/** `GET /api/meals/1` —— 一餐两个条目（米饭按「碗」、鸡胸按克）。 */
export const MEAL_DETAIL =
  '{"id":1,"mealDate":"2026-10-19","slot":"lunch","eatenAt":"2026-10-19T12:30:00","note":"计划 4a e2e","items":[{"mealItemId":1,"foodId":1,"nameZh":"白米饭","orderIndex":0,"amount":1.0,"unitName":"碗","grams":150.0,"nutrition":{"kcal":174.0,"protein":3.9,"carb":38.85,"fat":0.45,"fiber":0.45,"sodium":3.0}},{"mealItemId":2,"foodId":9,"nameZh":"鸡胸肉（熟）","orderIndex":1,"amount":150.0,"unitName":null,"grams":150.0,"nutrition":{"kcal":247.5,"protein":46.5,"carb":0.0,"fat":5.4,"fiber":0.0,"sodium":111.0}}],"nutrition":{"kcal":421.5,"protein":50.4,"carb":38.85,"fat":5.85,"fiber":0.45,"sodium":114.0}}'

/** `GET /api/nutrition/daily?date=2026-10-19` —— 上面那一餐的合计。 */
export const DAILY =
  '{"date":"2026-10-19","nutrition":{"kcal":421.5,"protein":50.4,"carb":38.85,"fat":5.85,"fiber":0.45,"sodium":114.0},"meals":1,"items":2}'

/** `GET /api/nutrition/daily?date=2026-08-20` —— **没记过的那一天：全 0，不是 404**。 */
export const EMPTY_DAY =
  '{"date":"2026-08-20","nutrition":{"kcal":0.0,"protein":0.0,"carb":0.0,"fat":0.0,"fiber":0.0,"sodium":0.0},"meals":0,"items":0}'

/** `GET /api/foods?q=豆腐` —— 两条命中，其中「豆腐（北）」带一个「块 = 100 克」。 */
export const FOODS_TOFU =
  '[{"id":14,"nameZh":"豆腐（北）","nameEn":"firm tofu","source":"manual","per100":{"kcal":98.0,"protein":12.2,"carb":3.8,"fat":4.8,"fiber":0.5,"sodium":7.2},"portions":[{"unitName":"块","grams":100.0}]},{"id":32,"nameZh":"麻婆豆腐","nameEn":"mapo tofu","source":"manual","per100":{"kcal":120.0,"protein":8.0,"carb":5.0,"fat":8.0,"fiber":1.0,"sodium":650.0},"portions":[{"unitName":"份","grams":200.0}]}]'

/**
 * `GET /api/foods?q=白米饭` —— 契约测试要用它的 `per100` 与 `portions`。
 *
 * 注意 `per100` 是**每 100 克**的值，而 MEAL_DETAIL 里那一项的 `grams` 是 150：
 * 「每 100 克 × 1.5」正是前端预览要复现的那一步。
 */
export const FOOD_RICE =
  '[{"id":1,"nameZh":"白米饭","nameEn":"cooked white rice","source":"manual","per100":{"kcal":116.0,"protein":2.6,"carb":25.9,"fat":0.3,"fiber":0.3,"sodium":2.0},"portions":[{"unitName":"碗","grams":150.0}]}]'

/** `GET /api/foods?q=鸡胸` —— 同上，这一条是**按克**录的（`unitName` 为 null）。 */
export const FOOD_CHICKEN =
  '[{"id":9,"nameZh":"鸡胸肉（熟）","nameEn":"cooked chicken breast","source":"manual","per100":{"kcal":165.0,"protein":31.0,"carb":0.0,"fat":3.6,"fiber":0.0,"sodium":74.0},"portions":[{"unitName":"块","grams":120.0}]}]'
