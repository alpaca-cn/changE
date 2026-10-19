package com.change.repo;

import com.change.domain.MealItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 餐中条目的读入口。 */
public interface MealItemRepo extends JpaRepository<MealItem, Long> {

    /**
     * 一餐的条目，**按 orderIndex 升序**。
     *
     * <p>顺序是这方法契约的一部分：界面上条目按录入顺序展示，乱了不会报错、只会错位
     * （与 {@code SessionExerciseRepo.findBySessionIdOrderByOrderIndexAsc} 同一个理由）。
     */
    List<MealItem> findByMealIdOrderByOrderIndexAsc(Long mealId);

    /**
     * 一次取多餐的条目，先按 mealId 再按 orderIndex 排序。
     *
     * <p>两个排序键都要：调用方要按 `mealId` 把结果分组，组内还要保持 `orderIndex` 顺序。
     * 少了 `mealId` 那一半，分组时得自己再排一次；少了 `orderIndex` 那一半，组内顺序就丢了。
     */
    List<MealItem> findByMealIdInOrderByMealIdAscOrderIndexAsc(List<Long> mealIds);
}
