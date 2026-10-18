package com.change.repo;

import com.change.domain.ExerciseMuscle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 动作↔肌群标注的读写入口。 */
public interface ExerciseMuscleRepo extends JpaRepository<ExerciseMuscle, Long> {

    List<ExerciseMuscle> findByExerciseId(Long exerciseId);

    /** 撤销整个导入时按动作 id 批量删。 */
    void deleteByExerciseIdIn(List<Long> exerciseIds);
}
