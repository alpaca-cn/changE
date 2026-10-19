package com.change.repo;

import com.change.domain.BodyMetric;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 身体数据的读入口。
 *
 * <p>时间线的三个筛选条件（类型 / 起 / 止）都可省略，所以用一条带可空参数的 JPQL，
 * 而不是 8 个派生方法（2 种类型 × 2 个起止 × ...）。可空参数写成
 * {@code :param is null or ...}：Hibernate 6 能从与 {@code m.measuredOn} 的比较里
 * 推断出参数类型，因此 {@code null} 绑定的是 LOCAL_DATE 而不是 Object。
 *
 * <p><b>排序是 {@code measured_on, id}</b>：日期相同时按录入顺序。用 id 做次级键不是
 * 为了好看——同一天补录两条体重时，没有它返回顺序就取决于执行计划（SQLite 不保证
 * {@code ORDER BY} 之外任何东西），而「哪条先显示」会随数据分布变化。
 */
public interface BodyMetricRepo extends JpaRepository<BodyMetric, Long> {

    /**
     * 时间线。
     *
     * @param metricType 类型字面量；null 表示全部类型
     * @param fromDate   起始日期（含）；null 表示不限
     * @param toDate     截止日期（含）；null 表示不限
     * @param page       只用来带 limit；调用方保证 1 ≤ limit ≤ 上限，且不带 sort
     */
    @Query("""
            select m from BodyMetric m
            where (:metricType is null or m.metricType = :metricType)
              and (:fromDate is null or m.measuredOn >= :fromDate)
              and (:toDate is null or m.measuredOn <= :toDate)
            order by m.measuredOn asc, m.id asc
            """)
    List<BodyMetric> search(@Param("metricType") String metricType,
                            @Param("fromDate") LocalDate fromDate,
                            @Param("toDate") LocalDate toDate,
                            Pageable page);
}
