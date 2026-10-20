package com.change.seed;

/**
 * 人体图区域 id 的**历史词汇表**（V7 的取值）。
 *
 * <p><b>⚠️ 从 V10 起，库里的 {@code muscle_group.region_ids} 不再使用这里的值。</b>
 * V7 当初填的是 {@code chest_left,chest_right} 这种下划线名——那时还没有人体图素材，
 * 名字是占位的。V10 把它们换成了 vendored 素材（{@code vulovix/body-muscles}，
 * Apache-2.0）里的真实区域 id，风格是连字符 + 子分组：{@code chest-upper-left}、
 * {@code lats-mid-right}。
 *
 * <p><b>现在谁是权威</b>：{@code V10__muscle_region_ids.sql} 里的字面量，与
 * {@code frontend/src/assets/body-map/data/muscles.{front,back}.ts} 里的 {@code id}。
 * 两者由 {@code MuscleRegionMappingTest} 交叉校验（那个测试直接读素材原文，
 * 不读任何副本）。本枚举**不该再被用来写 {@code region_ids}**。
 *
 * <p>为什么保留它而不是删掉：V7 是已发布的迁移，它的字节被 {@code MigrationTest} 冻结，
 * 而 {@code MuscleGroupSeed} 仍需要一份「V7 当时写了什么」的记录来生成那个文件。
 * 删掉枚举会让那份历史无从对照。
 *
 * <p>为什么自己定义而不是直接用某个 SVG 文件里的 path id：那是外部实现细节，
 * 换了文件版本不该让库里 876 条动作的标注跟着漂。映射的责任放在前端
 * （计划 6 的 2D 人体图拿 SVG path → 这里的 id，是一张静态表）。
 *
 * <p>大部分肌群只对应一个区域；{@code chest} 拆左右是因为人体图上胸肌
 * 是左右两块独立路径，而「本周胸 12 组」这种统计要能分别点亮。
 *
 * <p>这里的 {@link #id} **曾经**是 {@code muscle_group.region_ids} 里的字面量。
 * 「改一个 id 等于改数据，要写迁移」这条原则仍然成立——V10 就是那样一次迁移。
 * 只是现在被改的已经不是这几个值了。
 */
public enum MuscleRegion {
    /** 胸（左右分开，人体图上是两块路径）。 */
    CHEST_LEFT("chest_left"),
    CHEST_RIGHT("chest_right"),
    ABDOMINALS("abdominals"),
    OBLIQUES("obliques"),
    LATS_LEFT("lats_left"),
    LATS_RIGHT("lats_right"),
    MIDDLE_BACK("middle_back"),
    LOWER_BACK("lower_back"),
    TRAPS("traps"),
    SHOULDERS_LEFT("shoulders_left"),
    SHOULDERS_RIGHT("shoulders_right"),
    BICEPS_LEFT("biceps_left"),
    BICEPS_RIGHT("biceps_right"),
    TRICEPS_LEFT("triceps_left"),
    TRICEPS_RIGHT("triceps_right"),
    FOREARMS("forearms"),
    GLUTES_LEFT("glutes_left"),
    GLUTES_RIGHT("glutes_right"),
    QUADRICEPS_LEFT("quadriceps_left"),
    QUADRICEPS_RIGHT("quadriceps_right"),
    HAMSTRINGS_LEFT("hamstrings_left"),
    HAMSTRINGS_RIGHT("hamstrings_right"),
    ADDUCTORS("adductors"),
    ABDUCTORS("abductors"),
    CALVES_LEFT("calves_left"),
    CALVES_RIGHT("calves_right"),
    NECK("neck");

    /** 存进 `muscle_group.region_ids` 的字面量。**改它等于改数据**，要写迁移。 */
    public final String id;

    MuscleRegion(String id) {
        this.id = id;
    }
}
