package com.change.repo;

import com.change.domain.Exercise;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 动作字典的读写入口。
 *
 * <p>没有为 nameEn 加 findByNameEn：它有唯一索引，按名字取单个动作迟早会遇到
 * 「名字有空格/大小写不同」的输入，那时应该走下面的模糊搜索让人选，而不是抛异常。
 */
public interface ExerciseRepo extends JpaRepository<Exercise, Long> {

    /** 选动作时默认只看未归档的：归档的动作仍要能被旧记录解析出来，但不该出现在候选里。 */
    List<Exercise> findByArchivedFalse();

    /** 中英文混合搜索。nameZh 用 Containing（区分大小写对中文无意义），nameEn 用 IgnoreCase。 */
    List<Exercise> findByNameZhContainingOrNameEnContainingIgnoreCase(String zh, String en);
}
