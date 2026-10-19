package com.change.service;

import com.change.domain.MetricType;
import com.change.service.BodyMetricService.Created;
import com.change.service.BodyMetricService.MetricInput;
import com.change.service.BodyMetricService.MetricView;
import com.change.service.BodyMetricService.TypeSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 身体数据：写入、默认单位、校验、时间线、当前值。
 *
 * <p>@Transactional 同 {@link NutritionServiceTest}：跑完回滚，不给共享库留行。
 * 所以这里测不了「事务回滚」（测试事务本来就会回滚掉一切），写路径的
 * {@code @Transactional} 由「一次写入 + 一条审计行」同生共死这条断言间接守着。
 */
@SpringBootTest
@Transactional
class BodyMetricServiceTest {

    @Autowired BodyMetricService bodyMetrics;
    @Autowired JdbcTemplate jdbc;

    /** 日期一律写成字符串：服务层的契约就是「字符串进、按 YYYY-MM-DD 解析」。 */
    private static MetricInput weight(String measuredOn, double value, String unit) {
        return new MetricInput(measuredOn, "weight", value, unit, null);
    }

    private String unitOf(long id) {
        return jdbc.queryForObject("SELECT unit FROM body_metric WHERE id = ?", String.class, id);
    }

    private int auditRowsFor(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_log WHERE target_table = 'body_metric' AND target_id = ?",
                Integer.class, id);
    }

    // ---- 写 ----

    @Test
    void records_a_weight_and_the_row_is_really_in_the_database() {
        Created created = bodyMetrics.record(
                new MetricInput("2026-10-19", "weight", 72.5, "kg", "早上空腹"));

        assertThat(created.id()).isPositive();
        // 用 JdbcTemplate 读**原始列**：日期必须是 YYYY-MM-DD 文本，不是纪元毫秒。
        // 这条同时守着 LOCAL_DATE 映射——写成 '1791907200000' 会被 GLOB CHECK 拒绝，
        // 而只断言「能读回 LocalDate」的话，读回值可能来自另一条路径。
        assertThat(jdbc.queryForObject(
                "SELECT measured_on FROM body_metric WHERE id = ?", String.class, created.id()))
                .isEqualTo("2026-10-19");
        assertThat(jdbc.queryForObject(
                "SELECT metric_type FROM body_metric WHERE id = ?", String.class, created.id()))
                .isEqualTo("weight");
        assertThat(jdbc.queryForObject(
                "SELECT value FROM body_metric WHERE id = ?", Double.class, created.id()))
                .isEqualTo(72.5);
        assertThat(unitOf(created.id())).isEqualTo("kg");
        assertThat(jdbc.queryForObject(
                "SELECT note FROM body_metric WHERE id = ?", String.class, created.id()))
                .isEqualTo("早上空腹");
    }

    @Test
    void fills_in_the_default_unit_of_the_type_when_none_is_given() {
        // 默认单位是**类型**的属性（weight→kg、waist→cm），不是全局默认；
        // 不填就留 null 的话，库的 NOT NULL 会把它变成 500，而它其实该是一个能自动补上的值。
        long weightId = bodyMetrics.record(weight("2026-10-19", 72.5, null)).id();
        long waistId = bodyMetrics.record(
                new MetricInput("2026-10-19", "waist", 80.0, "  ", null)).id();

        assertThat(unitOf(weightId)).isEqualTo("kg");
        assertThat(unitOf(waistId)).isEqualTo("cm");
    }

    @Test
    void refuses_an_unknown_metric_type_with_the_valid_ids_in_the_message() {
        assertThatThrownBy(() -> bodyMetrics.record(
                new MetricInput("2026-10-19", "bicep", 33.0, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bicep")
                // 报错要把合法值列出来，用户才知道能写什么
                .hasMessageContaining("weight")
                .hasMessageContaining("body_fat")
                .hasMessageContaining("waist");
    }

    @Test
    void refuses_a_missing_metric_type() {
        assertThatThrownBy(() -> bodyMetrics.record(
                new MetricInput("2026-10-19", " ", 72.5, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metricType");
    }

    @Test
    void refuses_a_measured_on_that_is_not_yyyy_mm_dd() {
        // 形状错（2026/10/19）与「形状对但日期不存在」（2026-02-31）都要在服务层拦下：
        // 否则前者会变成 Jackson 的另一套 400 正文，后者会撞库的 GLOB CHECK 变成 500。
        assertThatThrownBy(() -> bodyMetrics.record(weight("2026/10/19", 72.5, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> bodyMetrics.record(weight("2026-02-31", 72.5, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> bodyMetrics.record(weight("2026-10-19T08:00", 72.5, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    void refuses_a_negative_value() {
        assertThatThrownBy(() -> bodyMetrics.record(weight("2026-10-19", -1, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("负");
    }

    @Test
    void refuses_a_missing_value() {
        // Double 而不是 double：JSON 里漏掉 value 时是 null，而不是悄悄变成 0。
        assertThatThrownBy(() -> bodyMetrics.record(
                new MetricInput("2026-10-19", "weight", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
    }

    @Test
    void two_measurements_of_the_same_type_on_the_same_day_are_both_kept() {
        // **不去重**：早上空腹 72.5 与晚上 72.1 是两个真实观测，
        // 合并成一个等于替用户篡改历史。
        long first = bodyMetrics.record(weight("2026-10-19", 72.5, null)).id();
        long second = bodyMetrics.record(weight("2026-10-19", 72.1, null)).id();

        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM body_metric WHERE measured_on = '2026-10-19' "
                        + "AND metric_type = 'weight'", Integer.class)).isEqualTo(2);
    }

    @Test
    void the_write_leaves_one_audit_row_whose_keys_are_real_columns() {
        long id = bodyMetrics.record(
                new MetricInput("2026-10-19", "waist", 80.0, null, "腰")).id();

        // 恰好一条：多写会让 undo 一条体重时连带撤掉别的记录
        assertThat(auditRowsFor(id)).isEqualTo(1);
        String afterJson = jdbc.queryForObject(
                "SELECT after_json FROM change_log WHERE target_table = 'body_metric' "
                        + "AND target_id = ?", String.class, id);
        // 键名必须与真实列名逐字一致（ChangeLogService 会按 writableColumns 校验，
        // 写错就抛 IllegalArgumentException）；日期必须已经是字符串——快照用的是
        // 裸 ObjectMapper（没有 JavaTimeModule），放 LocalDate 会在记日志那一步抛异常。
        assertThat(afterJson).contains("\"measured_on\":\"2026-10-19\"");
        assertThat(afterJson).contains("\"metric_type\":\"waist\"");
        assertThat(afterJson).contains("\"value\":80.0");
        assertThat(afterJson).contains("\"unit\":\"cm\"");
        // note 是这条记录里**唯一携带上下文**的列（「早上空腹」「饭后两小时」）——
        // 少了它，审计只能回答「改了什么数」，回答不了「为什么是这个数」。
        assertThat(afterJson).contains("\"note\":\"腰\"");
    }

    @Test
    void an_absent_note_is_missing_from_the_snapshot_rather_than_null() {
        // 两个不同的东西：键不存在 = 当时没写备注；"note":null = 备注被清空了。
        // 后者会让读日志的人以为「有人把备注删了」，而实际从来没写过。
        long id = bodyMetrics.record(new MetricInput("2026-10-19", "weight", 72.5, null, null)).id();

        String afterJson = jdbc.queryForObject(
                "SELECT after_json FROM change_log WHERE target_table = 'body_metric' "
                        + "AND target_id = ?", String.class, id);

        assertThat(afterJson).contains("\"unit\":\"kg\"");
        assertThat(afterJson).doesNotContain("\"note\"");
    }

    // ---- 时间线 ----

    @Test
    void the_timeline_filters_by_type_and_sorts_by_date_ascending() {
        bodyMetrics.record(weight("2026-10-19", 72.5, null));
        bodyMetrics.record(new MetricInput("2026-10-18", "waist", 80.0, null, null));
        bodyMetrics.record(weight("2026-10-17", 73.0, null));

        List<MetricView> weightOnly = bodyMetrics.timeline("weight", null, null, null);

        assertThat(weightOnly).extracting(MetricView::metricType).containsOnly("weight");
        assertThat(weightOnly).extracting(MetricView::measuredOn)
                .containsExactly(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 19));
        assertThat(weightOnly).extracting(MetricView::value).containsExactly(73.0, 72.5);

        // 省略 type = 全部类型，同样按日期升序
        assertThat(bodyMetrics.timeline(null, null, null, null))
                .extracting(MetricView::measuredOn)
                .containsExactly(LocalDate.of(2026, 10, 17),
                        LocalDate.of(2026, 10, 18),
                        LocalDate.of(2026, 10, 19));
    }

    @Test
    void from_and_to_are_closed_bounds() {
        bodyMetrics.record(weight("2026-10-15", 74.0, null));
        bodyMetrics.record(weight("2026-10-16", 73.5, null));
        bodyMetrics.record(weight("2026-10-17", 73.0, null));

        // 两侧都是**闭区间**：from=10-16 与 to=10-16 各自都要包含那一天自己
        assertThat(bodyMetrics.timeline(null, "2026-10-16", "2026-10-16", null))
                .extracting(MetricView::measuredOn)
                .containsExactly(LocalDate.of(2026, 10, 16));
        assertThat(bodyMetrics.timeline(null, "2026-10-16", null, null))
                .extracting(MetricView::measuredOn)
                .containsExactly(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17));
        assertThat(bodyMetrics.timeline(null, null, "2026-10-16", null))
                .extracting(MetricView::measuredOn)
                .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
    }

    @Test
    void an_empty_timeline_is_an_empty_list_not_an_error() {
        // 「还没有数据」是刚启用时的常态，不是错误。返回 null / 抛异常 / 404 都会让
        // 界面把「没数据」显示成「出错了」。
        assertThat(bodyMetrics.timeline("weight", "2030-01-01", "2030-12-31", null)).isEmpty();
        assertThat(bodyMetrics.timeline("zz_never_recorded", null, null, null)).isEmpty();
    }

    // ---- 当前值（types）----

    @Test
    void types_reports_the_unit_latest_value_latest_date_and_count_per_type() {
        bodyMetrics.record(new MetricInput("2026-10-10", "body_fat", 18.5, null, null));
        bodyMetrics.record(new MetricInput("2026-10-19", "body_fat", 18.0, null, null));
        bodyMetrics.record(weight("2026-10-19", 72.5, null));

        List<TypeSummary> types = bodyMetrics.types();

        TypeSummary bodyFat = types.stream()
                .filter(t -> t.metricType().equals("body_fat")).findFirst().orElseThrow();
        assertThat(bodyFat.unit()).isEqualTo("%");
        assertThat(bodyFat.latestValue()).isEqualTo(18.0);
        assertThat(bodyFat.latestOn()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(bodyFat.count()).isEqualTo(2L);

        TypeSummary weight = types.stream()
                .filter(t -> t.metricType().equals("weight")).findFirst().orElseThrow();
        assertThat(weight.unit()).isEqualTo("kg");
        assertThat(weight.count()).isEqualTo(1L);
    }

    @Test
    void types_are_listed_in_enum_order_not_alphabetically() {
        // 枚举顺序是 weight, body_fat, ...；字母序会是 body_fat, waist, weight。
        // 这条是「按 MetricType 枚举顺序排」的实现杀手。
        bodyMetrics.record(new MetricInput("2026-10-19", "body_fat", 18.0, null, null));
        bodyMetrics.record(weight("2026-10-19", 72.5, null));
        bodyMetrics.record(new MetricInput("2026-10-19", "waist", 80.0, null, null));

        assertThat(bodyMetrics.types()).extracting(TypeSummary::metricType)
                .containsExactly("weight", "body_fat", "waist");
        // 单位也跟着来（体重 kg、体脂 %、腰围 cm），卡片不用自己再猜
        assertThat(bodyMetrics.types()).extracting(TypeSummary::unit)
                .containsExactly("kg", "%", "cm");
    }

    @Test
    void the_latest_value_is_the_row_with_the_greatest_measured_on_not_the_greatest_id() {
        // 补录一条历史数据：它 id 更大，但日期更早。最新值必须仍取自 10-19 那条。
        // 按 id 取最大（"最后写入的那条"）会返回 73.5 / 10-18——同一天补录时静默错。
        bodyMetrics.record(weight("2026-10-19", 72.5, null));
        bodyMetrics.record(weight("2026-10-18", 73.5, null));

        TypeSummary weight = bodyMetrics.types().stream()
                .filter(t -> t.metricType().equals("weight")).findFirst().orElseThrow();

        assertThat(weight.latestOn()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(weight.latestValue()).isEqualTo(72.5);
        assertThat(weight.count()).isEqualTo(2L);
    }

    @Test
    void an_empty_database_has_no_types() {
        assertThat(bodyMetrics.types()).isEmpty();
    }

    // ---- limit ----

    @Test
    void the_limit_defaults_to_two_hundred_and_is_capped_at_one_thousand() {
        // 封顶如果只写在注释里，它就是一句注释。这里直接塞 1001 行、传 limit=99999。
        List<Object[]> rows = new ArrayList<>();
        LocalDate firstDay = LocalDate.of(2020, 1, 1);
        for (int i = 0; i <= 1000; i++) {
            rows.add(new Object[]{firstDay.plusDays(i).toString(), "zz_limit_probe", 1.0});
        }
        jdbc.batchUpdate("INSERT INTO body_metric(measured_on, metric_type, value, unit) "
                + "VALUES (?, ?, ?, 'kg')", rows);

        assertThat(BodyMetricService.DEFAULT_LIMIT).isEqualTo(200);
        assertThat(BodyMetricService.MAX_LIMIT).isEqualTo(1000);
        // 不传 = 默认 200
        assertThat(bodyMetrics.timeline("zz_limit_probe", null, null, null)).hasSize(200);
        // 小 limit 原样生效
        assertThat(bodyMetrics.timeline("zz_limit_probe", null, null, 3)).hasSize(3);
        // 超大 limit 被压到 1000，而不是把 1001 行全给出去
        assertThat(bodyMetrics.timeline("zz_limit_probe", null, null, 99999)).hasSize(1000);
    }

    @Test
    void the_metric_type_enum_keeps_the_documented_ids_and_default_units() {
        // 字面量是**数据**：改了它，库里已有的行就再也对不上枚举里的一半。
        assertThat(MetricType.byId("weight")).isEqualTo(MetricType.WEIGHT);
        assertThat(MetricType.WEIGHT.defaultUnit).isEqualTo("kg");
        assertThat(MetricType.BODY_FAT.defaultUnit).isEqualTo("%");
        assertThat(MetricType.byId("nope")).isNull();
        assertThat(MetricType.validIds()).containsExactly(
                "weight", "body_fat", "waist", "chest", "hip", "arm", "thigh", "calf", "shoulder");
    }
}
