/**
 * 进步曲线的**真机响应**夹具。
 *
 * 出处：`backend/src/test/java/com/change/web/ProgressControllerTest` 的
 * `the_response_has_exactly_the_four_contract_keys_and_iso_dates` 在 2026-10-19
 * 实测打印的那一行（`mvn -B -Dtest=ProgressControllerTest test`，日志里标记为
 * `[PROGRESS_JSON]`）。**这串字符是原样复制**：字段名、键的顺序、`76.0` 这种
 * 带小数点写法、`"e1rm":null` 一个都没改。`exerciseId` 也是那次测试库里的真实 id。
 *
 * 那次造的三个训练日：60 kg × 8（e1rm 76）、20 kg × 15（次数超出可估算范围 →
 * e1rm 为 null）、65 kg × 5（e1rm 75.83）。
 *
 * 为什么不手写一个 `{date:'2026-10-14', e1rm:76, ...}`：手写的对象只能证明
 * 「前端能读自己编的东西」。后端把 `e1rm` 改名、把 `date` 写成数组、或者把 null
 * 省略掉时，手写对象一个字都不会变——那条测试会继续绿。喂真实 JSON 至少把
 * 「前端读的键名与后端真实响应对齐」这件事钉住了；后端的形状本身由
 * `ProgressControllerTest` 的键集合断言守着。
 */
export const PROGRESS_THREE_DAYS =
  '{"exerciseId":10521,"nameEn":"ZZ Progress Http Bench","nameZh":null,"points":[{"date":"2026-10-14","e1rm":76.0,"maxWeight":60.0,"totalVolume":480.0},{"date":"2026-10-15","e1rm":null,"maxWeight":20.0,"totalVolume":300.0},{"date":"2026-10-17","e1rm":75.83,"maxWeight":65.0,"totalVolume":325.0}]}'
