package com.change.repo;

import com.change.domain.Meal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * 餐的读入口。
 *
 * <p>按 `id` 升序而不是按 {@code eaten_at}：后者可空，而 SQLite 的
 * {@code ORDER BY} 对 NULL 的处理与「没记时间的那一餐该排前面还是后面」这件事无关，
 * 靠它排序会得到一个随数据分布变化的结果。`id` 顺序 =
 * 录入顺序，稳定且能解释。
 */
public interface MealRepo extends JpaRepository<Meal, Long> {

    /** 某一天的餐，按录入顺序。 */
    List<Meal> findByMealDateOrderByIdAsc(LocalDate mealDate);
}
