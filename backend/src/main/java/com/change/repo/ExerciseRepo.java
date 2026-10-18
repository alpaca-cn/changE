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

    /**
     * 中英文混合搜索。nameZh 用 Containing（区分大小写对中文无意义），nameEn 用 IgnoreCase。
     *
     * <p><b>必须排除已归档的</b>：归档的语义是「不再出现在候选里，但仍要能被旧记录
     * 解析」。搜索是「候选」的一种，所以它和 {@link #findByArchivedFalse()} 遵守同一条规则。
     * 原来这个方法漏了 archived 过滤，归档的动作会从搜索里冒出来——而它的零测试让这个
     * 不一致一直没被发现。
     *
     * <p>名字很长，但 Spring Data 的方法名派生就是靠它。**不要**为它换成 {@code @Query}：
     * 本项目的约定是「能用方法名派生就不用 JPQL」。
     *
     * <p>空串的行为：{@code Containing("")} 生成 {@code LIKE '%%'}，所以空串匹配
     * **所有未归档**的行（归档的仍被排除）。这条被 {@code ExerciseRepoTest} 钉住。
     */
    List<Exercise> findByArchivedFalseAndNameZhContainingOrArchivedFalseAndNameEnContainingIgnoreCase(
            String zh, String en);
}
