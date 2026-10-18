package com.change.repo;

import com.change.domain.MuscleGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 肌群字典的读写入口。
 *
 * <p>导入时按英文名查（数据集里的 primaryMuscles / secondaryMuscles 就是这些英文名）。
 * 名字与数据集逐字对齐，所以这里**不能模糊匹配**：查不到就是数据集或种子真的对不上，
 * 应该立刻抛异常让导入失败，而不是猜一个「差不多的」肌群把动作挂错地方。
 */
public interface MuscleGroupRepo extends JpaRepository<MuscleGroup, Long> {

    /** 导入时按英文名查。查不到返回 {@link Optional#empty()}，调用方负责报错。 */
    Optional<MuscleGroup> findByNameEn(String nameEn);
}
