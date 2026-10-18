package com.change.repo;

import com.change.domain.SessionExercise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 一次训练里的动作。 */
public interface SessionExerciseRepo extends JpaRepository<SessionExercise, Long> {

    /**
     * 一次训练里的动作，**按 orderIndex 升序**。顺序是这方法契约的一部分：
     * 详情页与「上次重量带出」都按它展示，乱了不会报错、只会错位。
     */
    List<SessionExercise> findBySessionIdOrderByOrderIndexAsc(Long sessionId);
}
