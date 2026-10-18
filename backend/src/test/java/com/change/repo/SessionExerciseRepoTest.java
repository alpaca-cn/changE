package com.change.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个派生查询的守卫：它们必须**按 orderIndex / setIndex 升序**返回。
 *
 * <p>为什么单独测这个：这两个查询的调用方（训练详情、上次重量带出）都依赖顺序——
 * 反了的话动作在界面上会乱序、组与组之间会错位，而查询本身不会报错。
 * 插入时故意打乱顺序，断言读回来的顺序是升序（不是插入序）。
 *
 * <p>@Transactional：跑完回滚，不给共享库留行。
 */
@SpringBootTest
@Transactional
class SessionExerciseRepoTest {

    @Autowired SessionExerciseRepo sessionExercises;
    @Autowired WorkoutSetRepo sets;
    @Autowired JdbcTemplate jdbc;

    private long newExercise(String nameEn) {
        jdbc.update("INSERT INTO exercise(name_en, default_unit) VALUES (?, 'kg')", nameEn);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long newSession() {
        jdbc.update("INSERT INTO workout_session(session_date) VALUES (?)", LocalDate.now().toString());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long newSessionExercise(long sessionId, long exerciseId, int orderIndex) {
        jdbc.update("INSERT INTO session_exercise(session_id, exercise_id, order_index) VALUES (?,?,?)",
                sessionId, exerciseId, orderIndex);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Test
    void exercises_come_back_in_order_index_order_not_insertion_order() {
        long sessionId = newSession();
        long exA = newExercise("ZZ Repo SE A");
        long exB = newExercise("ZZ Repo SE B");

        long second = newSessionExercise(sessionId, exB, 1);
        long first = newSessionExercise(sessionId, exA, 0);

        assertThat(sessionExercises.findBySessionIdOrderByOrderIndexAsc(sessionId))
                .extracting(se -> se.id)
                .containsExactly(first, second);
    }

    @Test
    void sets_come_back_in_set_index_order_not_insertion_order() {
        long sessionExerciseId = newSessionExercise(newSession(), newExercise("ZZ Repo SE C"), 0);

        jdbc.update("INSERT INTO workout_set(session_exercise_id, set_index, set_type) VALUES (?,?,?)",
                sessionExerciseId, 2, "normal");
        long last = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO workout_set(session_exercise_id, set_index, set_type) VALUES (?,?,?)",
                sessionExerciseId, 0, "normal");
        long first = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        assertThat(sets.findBySessionExerciseIdOrderBySetIndexAsc(sessionExerciseId))
                .extracting(ws -> ws.id)
                .containsExactly(first, last);
    }
}
