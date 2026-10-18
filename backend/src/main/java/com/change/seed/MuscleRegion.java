package com.change.seed;

/**
 * 人体图区域 id 的**唯一词汇表**。
 *
 * <p>为什么自己定义而不是直接用某个 SVG 文件里的 path id：那是外部实现细节，
 * 换了文件版本不该让库里 876 条动作的标注跟着漂。映射的责任放在前端
 * （计划 6 的 2D 人体图拿 SVG path → 这里的 id，是一张静态表）。
 *
 * <p>大部分肌群只对应一个区域；{@code chest} 拆左右是因为人体图上胸肌
 * 是左右两块独立路径，而「本周胸 12 组」这种统计要能分别点亮。
 *
 * <p>这里的 {@link #id} 会被写成 {@code muscle_group.region_ids} 里的字面量
 * （逗号分隔的字符串，见 {@code MuscleGroupSeed}）。**它是持久化数据的一部分**：
 * 改一个 id 等于改数据，要让已有行跟着迁移，并同步更新前端的映射表。
 * 新增常量只是扩大词汇表，不改变已写入的值。
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
