package com.change.repo;

import com.change.domain.FoodPortion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 份量换算的读入口。 */
public interface FoodPortionRepo extends JpaRepository<FoodPortion, Long> {

    /** 一个食物的全部单位。 */
    List<FoodPortion> findByFoodId(Long foodId);

    /**
     * 一次取多个食物的单位——**读一餐时不要按条目逐个查**（一餐 5 个条目就是 5 次往返，
     * 而它们本来可以一次取回）。这是它存在的唯一理由。
     */
    List<FoodPortion> findByFoodIdIn(List<Long> foodIds);
}
