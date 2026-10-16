package com.change.repo;

import com.change.domain.WorkoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

/**
 * 训练的读写入口。
 *
 * <p>两个查询方法都按 sessionDate 排序而不是按 id：日期是用户看到的分组维度，
 * 而 id 顺序只在「同一天先后录入」时才与日期一致。库里 ix_session_date 覆盖这两个查询的过滤。
 */
public interface WorkoutSessionRepo extends JpaRepository<WorkoutSession, Long> {

    /** 日期区间查询（含两端），最近的排前面。 */
    List<WorkoutSession> findBySessionDateBetweenOrderBySessionDateDesc(LocalDate from, LocalDate to);

    /** 首页用：最近 5 次训练。 */
    List<WorkoutSession> findTop5ByOrderBySessionDateDesc();
}
