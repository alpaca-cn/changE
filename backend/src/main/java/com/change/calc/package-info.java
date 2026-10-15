/**
 * 纯计算层：不依赖 Spring、不碰数据库、不发起网络调用。
 *
 * <h2>为什么单独成包</h2>
 *
 * 这里的三个计算（热量换算、份量换算、训练容量）决定所有下游统计的可信度。
 * 做成不依赖框架的纯类，测试跑一次不到一秒——对比 {@code @SpringBootTest} 要 4 秒起上下文。
 *
 * <h2>四条约定</h2>
 *
 * <p><b>一、顶层 record 还是嵌套 record。</b>
 * <b>跨操作复用</b>的聚合值放顶层（{@link Nutrition} 既用于「每 100 克基准值」
 * 也用于「一餐合计值」）；只属于<b>单个操作</b>的条目/状态嵌在操作类里
 * （{@link PortionConverter.Unit}、{@link VolumeCalculator.Entry}，以及后续的 {@code OneRepMax.Set}）。
 *
 * <p><b>二、数值定义域必须在流水线上保持一致。</b>
 * {@link PortionConverter#toGrams} 的输出直接喂给 {@link NutritionCalculator#forGrams}，
 * 所以两者的上下界必须对齐——上游放行、下游拒绝会产生「数据存得下但算不出来」的记录，
 * 而热量永远是实时计算的，没有快照可以兜底。
 *
 * <p><b>三、公开数值入口必须显式拒绝 NaN / ±∞。</b>
 * 惯用法有两种：一般输入用 {@code !Double.isFinite(v)}（见
 * {@link NutritionCalculator#forGrams} 与 {@link PortionConverter#toGrams}），
 * 比较类场景用 {@code !(x <= MAX)}（NaN 与任何比较都返回 false，所以
 * {@code x > MAX} 这种写法放 NaN 过去；见
 * {@link PortionConverter#toGrams} 里克数上界那一段）。
 * 理由：这个包已经因为 NaN 静默变成 {@code 0.0} / {@code IN_RANGE} 栽过两次
 * （{@link Round#to2} 与 {@link VolumeCalculator#classify}）——NaN 不会崩，
 * 它会给出一个看起来正常的错答案。
 *
 * <p><b>四、可空的领域配置不得兜底，必须进入类型或状态。</b>
 * 任何兜底值都会静默说谎：{@code muscle_group.weekly_sets_min/max} 可空时，
 * 兜成 0 会让「练得不够」这一态永久消失、兜上限会让整页抛异常、两者都兜会让
 * 任何训练量都判 {@code ABOVE}。所以「没配目标」被做成一个<b>状态</b>
 * （{@link VolumeCalculator.Status#NOT_CONFIGURED}），而不是一个数字。
 * 判据：调用方**分得清**「没这个配置」与「配置成 0」这两个事实。
 *
 * <h2>一条贯穿的价值观</h2>
 *
 * <p>所有校验都倾向于「让它响」而不是「让它算出一个看起来合理的值」：
 * 这类缺陷的失效方式大多是<b>静默</b>的（NaN 经舍入变成 0、∞ 被饱和成大数、
 * 重复单位名取首个），而对一个会分发给他人的客户端，
 * 数据看起来是对的但其实不是，比崩溃更坏。
 */
package com.change.calc;
