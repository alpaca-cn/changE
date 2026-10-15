package com.change.repo;

import com.change.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三层嵌套（训练 → 动作 → 组）在真实库上的落盘/读回验证。
 *
 * <p>为什么是 @SpringBootTest 而不是 @DataJpaTest：这里要验证的正是「实体映射与 Flyway 建出来的
 * schema 是否一致」——ddl-auto=validate 会在上下文启动时校验，映射错了这一整个类根本起不来。
 * @DataJpaTest 会换成内存库或自建 schema，就绕开了这一层。
 *
 * <p>@Transactional 让每条测试跑完回滚，测试之间不互相污染。
 *
 * <p>注意两条测试的动作名必须不同：exercise.name_en 上有唯一索引 ux_exercise_name_en。
 */
@SpringBootTest
@Transactional
class TrainingRepoTest {

    @Autowired
    ExerciseRepo exercises;
    @Autowired
    WorkoutSessionRepo sessions;
    @Autowired
    EntityManager em;

    @Test
    void can_persist_a_full_three_level_session() {
        Exercise ex = new Exercise();
        ex.nameEn = "Barbell Bench Press";
        ex.nameZh = "杠铃卧推";
        ex.equipment = "barbell";
        ex.compound = true;
        exercises.save(ex);

        WorkoutSession s = new WorkoutSession();
        s.sessionDate = LocalDate.of(2026, 10, 14);
        s.title = "胸 + 三头";
        sessions.save(s);

        SessionExercise se = new SessionExercise();
        se.sessionId = s.id;
        se.exerciseId = ex.id;
        se.orderIndex = 0;
        em.persist(se);

        WorkoutSet set = new WorkoutSet();
        set.sessionExerciseId = se.id;
        set.setIndex = 0;
        set.weight = 60.0;
        set.repetitions = 8;
        set.completed = true;
        set.targetWeight = 60.0;
        set.targetReps = 8;
        em.persist(set);
        em.flush();
        em.clear();

        WorkoutSet back = em.find(WorkoutSet.class, set.id);
        assertThat(back.weight).isEqualTo(60.0);
        assertThat(back.targetWeight).isEqualTo(60.0);   // 目标值与实际值同表
        assertThat(back.perSide).isFalse();

        // 「日期列存成了什么形状」与「按日期查不查得到」是两件事：形状合法的值如果和查询
        // 条件绑成的是两种字面量，WHERE 会安静地返回 0 行、不报错。这两条断言同时覆盖两者，
        // 也顺带跑通仓储的两个日期查询（否则它们在本任务里是死代码）。
        // 用 contains 而不是 hasSize(1)：断言的是「这一行查得到」，不依赖库里只有这一行。
        LocalDate day = LocalDate.of(2026, 10, 14);
        assertThat(sessions.findBySessionDateBetweenOrderBySessionDateDesc(day, day))
                .extracting(ws -> ws.sessionDate)
                .contains(day);
        assertThat(sessions.findTop5ByOrderBySessionDateDesc())
                .extracting(ws -> ws.sessionDate)
                .contains(day);
    }

    @Test
    void timed_set_stores_seconds_not_reps() {
        Exercise ex = new Exercise();
        ex.nameEn = "Plank";
        ex.nameZh = "平板支撑";
        exercises.save(ex);

        WorkoutSession s = new WorkoutSession();
        s.sessionDate = LocalDate.of(2026, 10, 14);
        sessions.save(s);

        SessionExercise se = new SessionExercise();
        se.sessionId = s.id;
        se.exerciseId = ex.id;
        se.orderIndex = 0;
        em.persist(se);

        WorkoutSet set = new WorkoutSet();
        set.sessionExerciseId = se.id;
        set.setIndex = 0;
        set.durationSeconds = 60;
        em.persist(set);
        em.flush();
        em.clear();

        WorkoutSet back = em.find(WorkoutSet.class, set.id);
        assertThat(back.durationSeconds).isEqualTo(60);
        assertThat(back.repetitions).isNull();
    }
}
