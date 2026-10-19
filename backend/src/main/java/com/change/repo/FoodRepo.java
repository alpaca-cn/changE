package com.change.repo;

import com.change.domain.Food;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 食物库的读入口。
 *
 * <p>这张表**没有 archived 列**（与 {@code exercise} 不同）：食物库按设计就是「直接改数据」
 * 的，不做管理界面，所以不需要「隐藏但可解析」那一层。
 */
public interface FoodRepo extends JpaRepository<Food, Long> {

    /**
     * 中文名或英文名包含关键词（英文不分大小写）。
     *
     * <p>刻意**不写** {@code OrArchivedFalse…} 那一套（{@code exercise} 上需要，
     * 这里没有 archived 概念）。空关键词的「列全部」由调用方走 {@code findAll()}。
     */
    List<Food> findByNameZhContainingOrNameEnContainingIgnoreCase(String nameZh, String nameEn);
}
