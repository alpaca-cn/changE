package com.change.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 身体数据的指标类型。
 *
 * <p><b>为什么不加数据库 CHECK</b>：这是一个**开放词汇**——设计文档的列注释写的是
 * {@code weight / body_fat / waist / arm / ...}，那个 {@code ...} 是有意的：
 * 以后要加「小腿围」「肩宽」时不该写迁移。所以约束放在 Java 侧，DB 只保证非空。
 *
 * <p>代价：绕过本枚举直接写库会产生认不出的类型。**这是有意的取舍**。
 *
 * <p>枚举顺序就是「当前值」卡片的显示顺序（见 {@code BodyMetricService#types}）：
 * 先体重、再体脂，然后从腰到肩的围度。所以**不要**为了字母序或其它方便重排这个枚举，
 * 它已经是界面顺序的一部分。
 */
public enum MetricType {
    /** 体重，kg。 */
    WEIGHT("weight", "kg"),
    /** 体脂率，%。 */
    BODY_FAT("body_fat", "%"),
    // ——— 围度，一律 cm ———
    WAIST("waist", "cm"),
    CHEST("chest", "cm"),
    HIP("hip", "cm"),
    ARM("arm", "cm"),
    THIGH("thigh", "cm"),
    CALF("calf", "cm"),
    SHOULDER("shoulder", "cm");

    /** 存进 {@code body_metric.metric_type} 的字面量。**改它等于改数据**。 */
    public final String id;
    /** 该类型的默认单位。客户端不传 unit 时用它。 */
    public final String defaultUnit;

    MetricType(String id, String defaultUnit) {
        this.id = id;
        this.defaultUnit = defaultUnit;
    }

    /** 按库里的字面量找类型；认不出来返回 {@code null}（由调用方翻成一句能读懂的错）。 */
    public static MetricType byId(String id) {
        for (MetricType t : values()) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    /** 报错文案用：把已知类型拼进去，用户一眼知道能写什么。 */
    public static List<String> validIds() {
        return Arrays.stream(values()).map(t -> t.id).toList();
    }
}
