package com.change.seed;

import java.util.List;

/**
 * 17 块肌群的种子数据。
 *
 * <p>英文名与 free-exercise-db 的 primaryMuscles / secondaryMuscles **逐字对齐**——
 * 差一个字符，876 条里所有挂这块肌肉的动作都会在导入时找不到对应行。
 * 数据集的这个 17 值枚举是实测出来的（Task 2 从缓存的 exercises.json 独立算过，
 * 大小写与空格都核对过：只有 {@code middle back} / {@code lower back} 带空格）。
 *
 * <p>{@code bodyPart} 是粗粒度分区，用来给动作选择器分组；
 * {@code regionIds} 是逗号分隔的 {@link MuscleRegion} id，写库时拼成一个字符串。
 *
 * <h2>weeklySetsMin / weeklySetsMax 是什么、不是什么</h2>
 *
 * <p>这两个值是**界面上「本周容量」提醒的比较基准**：它们只决定「本周这块肌肉的
 * 组数落在参考区间的哪一段」，以及要不要显示一句温和的提醒。它们**不是训练建议**，
 * 更不是医学指导——不针对伤病、康复、孕期或任何个体情况，也不该被当作处方；
 * 任何训练安排都应由用户与专业人士决定。
 *
 * <p>数值取自关于肌肥大训练容量的公开讨论中常见的区间（每块肌肉每周约 10–20 组，
 * 见 {@link #SCALE_SOURCE}），并刻意把下限放松：宁可提醒得晚一点，也不要让一个
 * 刚起步的人天天看到红字。{@code neck} 的区间是 {@code null}——颈部训练不该被
 * 「本周 0 组」追着（计划 1 的 {@code VolumeCalculator} 对未配置区间返回
 * {@code NOT_CONFIGURED}，正好用得上）。
 *
 * <p><b>播种方式。</b>库里的 17 行来自迁移 {@code V8__muscle_group_seed.sql}，
 * 而那份 SQL **是由代码从本类的 {@link #ALL} 生成的，不要手改**。改种子请改这里，
 * 再按 {@code MuscleGroupSeedTest} 里的说明重新生成；生成物与常量之间由
 * {@code MuscleGroupSeedTest#the_seed_migration_matches_the_java_constants} 钉住。
 */
public final class MuscleGroupSeed {

    private MuscleGroupSeed() {}

    /**
     * 周容量区间的依据。
     *
     * <p>取值落在公开的肌肥大容量讨论给出的区间内（每块肌肉每周约 10–20 组
     * 有效容量是常见说法）。**这不是医学建议**，它是界面上「本周 0 组」这类
     * 提醒的比较基准。值刻意偏宽松——把下限压低，宁可提醒得晚一点，
     * 也不要让一个刚起步的人天天看到红字。
     */
    public static final String SCALE_SOURCE =
            "每块肌肉每周约 10–20 组有效容量（肌肥大训练的常见共识区间），此处取偏宽松的下限";

    /** 一块肌群的种子定义。 */
    public static final class G {
        public final String nameEn;
        public final String nameZh;
        public final String bodyPart;
        public final List<String> regionIds;
        public final Integer weeklySetsMin;
        public final Integer weeklySetsMax;

        G(String nameEn, String nameZh, String bodyPart,
          List<String> regionIds, Integer min, Integer max) {
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.bodyPart = bodyPart;
            this.regionIds = regionIds;
            this.weeklySetsMin = min;
            this.weeklySetsMax = max;
        }
    }

    public static final List<G> ALL = List.of(
        // ——— 躯干前侧 ———
        new G("chest",       "胸部",   "躯干前侧",
              List.of(MuscleRegion.CHEST_LEFT.id, MuscleRegion.CHEST_RIGHT.id), 10, 20),
        new G("abdominals",  "腹直肌", "躯干前侧",
              List.of(MuscleRegion.ABDOMINALS.id), 8, 16),
        // ——— 躯干后侧 ———
        new G("lats",        "背阔肌", "躯干后侧",
              List.of(MuscleRegion.LATS_LEFT.id, MuscleRegion.LATS_RIGHT.id), 10, 20),
        new G("middle back", "中背部", "躯干后侧",
              List.of(MuscleRegion.MIDDLE_BACK.id), 8, 16),
        new G("lower back",  "下背部", "躯干后侧",
              List.of(MuscleRegion.LOWER_BACK.id), 4, 10),
        new G("traps",       "斜方肌", "躯干后侧",
              List.of(MuscleRegion.TRAPS.id), 6, 14),
        // ——— 上肢 ———
        new G("shoulders",   "肩部",   "上肢",
              List.of(MuscleRegion.SHOULDERS_LEFT.id, MuscleRegion.SHOULDERS_RIGHT.id), 8, 18),
        new G("biceps",      "肱二头肌", "上肢",
              List.of(MuscleRegion.BICEPS_LEFT.id, MuscleRegion.BICEPS_RIGHT.id), 6, 14),
        new G("triceps",     "肱三头肌", "上肢",
              List.of(MuscleRegion.TRICEPS_LEFT.id, MuscleRegion.TRICEPS_RIGHT.id), 6, 14),
        new G("forearms",    "前臂",   "上肢",
              List.of(MuscleRegion.FOREARMS.id), 4, 10),
        // ——— 下肢 ———
        new G("quadriceps",  "股四头肌", "下肢",
              List.of(MuscleRegion.QUADRICEPS_LEFT.id, MuscleRegion.QUADRICEPS_RIGHT.id), 10, 20),
        new G("hamstrings",  "腘绳肌", "下肢",
              List.of(MuscleRegion.HAMSTRINGS_LEFT.id, MuscleRegion.HAMSTRINGS_RIGHT.id), 8, 16),
        new G("glutes",      "臀肌",   "下肢",
              List.of(MuscleRegion.GLUTES_LEFT.id, MuscleRegion.GLUTES_RIGHT.id), 8, 16),
        new G("calves",      "小腿",   "下肢",
              List.of(MuscleRegion.CALVES_LEFT.id, MuscleRegion.CALVES_RIGHT.id), 6, 14),
        new G("adductors",   "内收肌", "下肢",
              List.of(MuscleRegion.ADDUCTORS.id), 4, 10),
        new G("abductors",   "外展肌", "下肢",
              List.of(MuscleRegion.ABDUCTORS.id), 4, 10),
        // ——— 颈 ———
        new G("neck",        "颈部",   "颈",
              List.of(MuscleRegion.NECK.id), null, null)
    );

    /** 按英文名查，导入时用。 */
    public static G byNameEn(String nameEn) {
        for (G g : ALL) {
            if (g.nameEn.equals(nameEn)) return g;
        }
        return null;
    }
}
