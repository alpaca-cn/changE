package com.change.seed;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 876 条数据集的解析与字段映射。
 *
 * <p>这些是纯数据变换，不起 Spring 就能毫秒级覆盖全部 876 条——
 * 而 876 条里有 9 条重叠、87 条 mechanic=null、77 条 equipment=null、
 * 844 条 id≠name，靠抽查是漏不掉的。
 */
class FreeExerciseDbTest {

    private static final List<FreeExerciseDb.Row> ROWS = FreeExerciseDb.load();

    @Test
    void loads_exactly_876_rows() {
        assertThat(ROWS).hasSize(876);
    }

    @Test
    void every_row_has_a_name_and_a_source_id_with_the_namespace() {
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.nameEn).isNotBlank();
            // source_id 必须带命名空间前缀，否则接第二个数据集时静默冲突
            assertThat(r.sourceId).startsWith("free-exercise-db:");
        }
    }

    @Test
    void source_ids_are_unique() {
        // 库上那个部分唯一索引会拦，但在这里暴露能直接指出是哪两条
        List<String> ids = ROWS.stream().map(r -> r.sourceId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void english_names_are_unique() {
        // ux_exercise_name_en 会拦，但这条能指出冲突的是哪几个
        List<String> names = ROWS.stream().map(r -> r.nameEn).toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void every_row_has_at_least_one_primary_muscle() {
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.primaryMuscles).as("%s 没有主肌群", r.nameEn).isNotEmpty();
        }
    }

    @Test
    void every_muscle_name_resolves_against_the_seed() {
        // 差一个字符，这条动作就挂不上肌群——而它不会报错，只会少一条标注。
        for (FreeExerciseDb.Row r : ROWS) {
            for (String m : r.primaryMuscles) {
                assertThat(MuscleGroupSeed.byNameEn(m))
                        .as("%s 的主肌群 %s 在种子数据里找不到", r.nameEn, m).isNotNull();
            }
            for (String m : r.secondaryMuscles) {
                assertThat(MuscleGroupSeed.byNameEn(m))
                        .as("%s 的次肌群 %s 在种子数据里找不到", r.nameEn, m).isNotNull();
            }
        }
    }

    @Test
    void the_nine_overlapping_rows_resolve_primary_over_secondary() {
        // 9 条动作的 primaryMuscles 与 secondaryMuscles 有重叠。
        // 朴素地两边都写会撞 (exercise_id, muscle_group_id) 的唯一索引，整批回滚。
        // 规则：primary 赢。这条测试同时钉住「重叠确实存在」与「被正确化解」。
        long overlapping = ROWS.stream().filter(FreeExerciseDb.Row::hasOverlap).count();
        assertThat(overlapping).as("数据集里应当有 9 条重叠").isEqualTo(9);

        for (FreeExerciseDb.Row r : ROWS) {
            Set<String> keys = r.muscleAssignments().stream()
                    .map(a -> a.muscleNameEn)
                    .collect(Collectors.toSet());
            assertThat(keys).as("%s 的肌群标注有重复键", r.nameEn)
                    .hasSize(r.muscleAssignments().size());
        }
    }

    @Test
    void a_known_overlapping_row_is_marked_primary() {
        // 抽一条具体的来钉死规则，防止上面那条被「整体过滤掉重复」蒙过去
        FreeExerciseDb.Row r = ROWS.stream()
                .filter(x -> x.nameEn.equals("Barbell Step Ups")).findFirst().orElseThrow();
        Map<String, String> roles = r.muscleAssignments().stream()
                .filter(a -> a.muscleNameEn.equals("quadriceps"))
                .collect(Collectors.toMap(a -> a.muscleNameEn, a -> a.role));
        assertThat(roles).containsEntry("quadriceps", "primary");
    }

    @Test
    void total_assignments_are_2587() {
        // 实测：朴素相加 2596，去重后 2587。数字变了说明数据集换了版本。
        int total = ROWS.stream().mapToInt(r -> r.muscleAssignments().size()).sum();
        assertThat(total).isEqualTo(2587);
    }

    @Test
    void is_compound_is_false_when_mechanic_is_missing() {
        // 87 条 mechanic=null，is_compound 是 NOT NULL，默认必须是 false
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.isCompound).isEqualTo("compound".equals(r.mechanic));
        }
        assertThat(ROWS.stream().filter(r -> r.mechanic == null).count()).isEqualTo(87);
    }

    @Test
    void default_unit_is_bw_only_for_bodyweight_exercises() {
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.defaultUnit).isEqualTo("body only".equals(r.equipment) ? "bw" : "kg");
        }
        assertThat(ROWS.stream().filter(r -> "body only".equals(r.equipment)).count()).isEqualTo(111);
    }

    @Test
    void category_is_preserved_and_the_non_strength_count_is_292() {
        // 292 条非 strength 是「选择器必须过滤」这件事的数据依据
        long nonStrength = ROWS.stream().filter(r -> !"strength".equals(r.category)).count();
        assertThat(nonStrength).isEqualTo(292);
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.category).isNotBlank();
        }
    }

    @Test
    void instructions_are_joined_into_one_string() {
        // 871 条有 instructions，其余为空。多段之间用换行连，空则 null。
        assertThat(ROWS.stream().filter(r -> r.instructions != null).count()).isEqualTo(871);
        for (FreeExerciseDb.Row r : ROWS) {
            if (r.instructions != null) {
                assertThat(r.instructions).doesNotContain("\r");
            }
        }
    }

    @Test
    void name_zh_is_null_at_import_time() {
        // 中文名是后面的单独任务（Task 7）产出的。
        // 这里钉住「导入不编中文名」——空着比编一个错的诚实。
        for (FreeExerciseDb.Row r : ROWS) {
            assertThat(r.nameZh).isNull();
        }
    }

    @Test
    void a_tampered_dataset_is_rejected_not_silently_parsed() throws Exception {
        // 「加载即校验」是一条守卫，而守卫本身也要有测试——否则把 verify() 删掉，
        // 上面 14 条断言一条都不会红（它们只碰真实数据）。这里喂两份坏数据进来：
        // 一份内容变了（sha256 拦），一份长度变了（大小拦）。
        byte[] real = readDataset();

        byte[] flipped = real.clone();
        flipped[100] ^= 0x01;
        assertThatThrownBy(() -> FreeExerciseDb.decode(flipped))
                .as("同为 1,005,327 字节但内容变了时，sha256 校验必须拦下它")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sha256");

        byte[] truncated = Arrays.copyOf(real, real.length - 1);
        assertThatThrownBy(() -> FreeExerciseDb.decode(truncated))
                .as("长度变了时，大小校验必须拦下它")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("大小");
    }

    private static byte[] readDataset() throws Exception {
        try (var in = new ClassPathResource(FreeExerciseDb.RESOURCE).getInputStream()) {
            return in.readAllBytes();
        }
    }
}
