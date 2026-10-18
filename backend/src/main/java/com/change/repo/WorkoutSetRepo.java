package com.change.repo;

import com.change.domain.WorkoutSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 一个动作下的组。 */
public interface WorkoutSetRepo extends JpaRepository<WorkoutSet, Long> {

    /** 一个动作下的组，**按 setIndex 升序**。理由同 {@link SessionExerciseRepo}。 */
    List<WorkoutSet> findBySessionExerciseIdOrderBySetIndexAsc(Long sessionExerciseId);
}
