/**
 * 真机响应夹具——**全是 2026-10-19 从后端抓下来的原始文本，一个字符都没改**。
 *
 * 为什么不手写：手写的会漏掉真实形态，而且会随文案漂移（计划 2/3 里「计划自己的
 * 重述失真」出现过 8 次）。抓法：Python `socket` / `urllib` 直打
 * `127.0.0.1:<随机端口>`，后端是计划 3 的 jar，库里当时有 1 次训练
 * （「交替地板卧推」2 组 60×8 完成 + 1 组未完成）。
 *
 * 放在一个模块里给多个 spec 共用，避免同一段 JSON 抄三遍——抄三遍就会有三份
 * 各自漂移的可能。
 */

/** `GET /api/volume/weekly?date=2026-10-19` —— **17 块肌群**，固定顺序（肌群 id 序）。 */
export const WEEKLY_17 =
  '{"weekStart":"2026-10-19","weekEnd":"2026-10-25","muscles":[{"muscleGroupId":1,"nameZh":"胸部","nameEn":"chest","sets":2.0,"min":10,"max":20,"status":"BELOW"},{"muscleGroupId":2,"nameZh":"腹直肌","nameEn":"abdominals","sets":1.0,"min":8,"max":16,"status":"BELOW"},{"muscleGroupId":3,"nameZh":"背阔肌","nameEn":"lats","sets":0.0,"min":10,"max":20,"status":"UNTRAINED"},{"muscleGroupId":4,"nameZh":"中背部","nameEn":"middle back","sets":0.0,"min":8,"max":16,"status":"UNTRAINED"},{"muscleGroupId":5,"nameZh":"下背部","nameEn":"lower back","sets":0.0,"min":4,"max":10,"status":"UNTRAINED"},{"muscleGroupId":6,"nameZh":"斜方肌","nameEn":"traps","sets":0.0,"min":6,"max":14,"status":"UNTRAINED"},{"muscleGroupId":7,"nameZh":"肩部","nameEn":"shoulders","sets":1.0,"min":8,"max":18,"status":"BELOW"},{"muscleGroupId":8,"nameZh":"肱二头肌","nameEn":"biceps","sets":0.0,"min":6,"max":14,"status":"UNTRAINED"},{"muscleGroupId":9,"nameZh":"肱三头肌","nameEn":"triceps","sets":1.0,"min":6,"max":14,"status":"BELOW"},{"muscleGroupId":10,"nameZh":"前臂","nameEn":"forearms","sets":0.0,"min":4,"max":10,"status":"UNTRAINED"},{"muscleGroupId":11,"nameZh":"股四头肌","nameEn":"quadriceps","sets":0.0,"min":10,"max":20,"status":"UNTRAINED"},{"muscleGroupId":12,"nameZh":"腘绳肌","nameEn":"hamstrings","sets":0.0,"min":8,"max":16,"status":"UNTRAINED"},{"muscleGroupId":13,"nameZh":"臀肌","nameEn":"glutes","sets":0.0,"min":8,"max":16,"status":"UNTRAINED"},{"muscleGroupId":14,"nameZh":"小腿","nameEn":"calves","sets":0.0,"min":6,"max":14,"status":"UNTRAINED"},{"muscleGroupId":15,"nameZh":"内收肌","nameEn":"adductors","sets":0.0,"min":4,"max":10,"status":"UNTRAINED"},{"muscleGroupId":16,"nameZh":"外展肌","nameEn":"abductors","sets":0.0,"min":4,"max":10,"status":"UNTRAINED"},{"muscleGroupId":17,"nameZh":"颈部","nameEn":"neck","sets":0.0,"min":null,"max":null,"status":"NOT_CONFIGURED"}]}'

/** `GET /api/sessions/recent` —— 一条（就是上面那次训练）。 */
export const RECENT =
  '[{"id":1,"sessionDate":"2026-10-19","title":"胸 + 三头","perceivedState":"睡得好","exercises":2,"sets":5,"topExerciseName":"杠铃卧推","topWeight":62.5}]'

/** `GET /api/sessions/1` —— 3 组，`setIndex` 0/1/2 升序，最后一组未完成。 */
export const SESSION_1 =
  '{"id":1,"sessionDate":"2026-10-19","title":"胸 + 三头","perceivedState":"睡得好","note":"计划 3 Task 7 e2e","startedAt":"2026-10-19T19:30:00","endedAt":null,"exercises":[{"sessionExerciseId":1,"exerciseId":16,"nameZh":"交替地板卧推","nameEn":"Alternating Floor Press","orderIndex":0,"note":"主项","supersetGroup":null,"sets":[{"id":1,"setIndex":0,"setType":"normal","weight":60.0,"repetitions":8,"durationSeconds":null,"perSide":false,"rir":null,"restSeconds":null,"completed":true,"targetWeight":null,"targetReps":null},{"id":2,"setIndex":1,"setType":"normal","weight":60.0,"repetitions":8,"durationSeconds":null,"perSide":false,"rir":null,"restSeconds":null,"completed":true,"targetWeight":null,"targetReps":null},{"id":3,"setIndex":2,"setType":"normal","weight":null,"repetitions":null,"durationSeconds":null,"perSide":false,"rir":null,"restSeconds":null,"completed":false,"targetWeight":null,"targetReps":null}]}]}'

/** `GET /api/exercises?muscleGroupId=1&limit=3` —— 胸部动作（拉伸已被后端滤掉）。 */
export const EXERCISES_CHEST =
  '[{"id":364,"nameEn":"JM Press","nameZh":"JM 卧推","equipment":"barbell","isCompound":true,"category":"strength"},{"id":154,"nameEn":"Chest Push from 3 point stance","nameZh":"三点支撑推胸","equipment":"medicine ball","isCompound":true,"category":"plyometrics"},{"id":347,"nameEn":"Incline Push-Up","nameZh":"上斜俯卧撑","equipment":"body only","isCompound":true,"category":"strength"}]'

/**
 * `GET /api/exercises/16/last-performance` —— 上次 60×8 两组 + 一组没记。
 *
 * 被别的动作 id 复用时，只有**数值**是测试关心的（两份 60×8、一份空）；
 * `sessionDate` / `sessionExerciseId` 是原样带过来的真实字段。
 */
export const LAST_PERF =
  '{"sessionDate":"2026-10-19","sessionExerciseId":1,"sets":[{"setType":"normal","weight":60.0,"repetitions":8,"durationSeconds":null,"perSide":false},{"setType":"normal","weight":60.0,"repetitions":8,"durationSeconds":null,"perSide":false},{"setType":"normal","weight":null,"repetitions":null,"durationSeconds":null,"perSide":false}]}'
