package com.change.agent;

import com.change.domain.Food;
import com.change.repo.FoodRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 智能体的 {@code create_food} 工具。
 *
 * <p>这条工具是「拍照记饮食」能不能落地的那一环：模型从图里认出「黄焖鸡米饭」，
 * 而预置库里没有它。没有这条工具，模型只能拿近似食物顶替（静默记错）或放弃（白看图）。
 *
 * <p>所以这里的用例守的是**它真的写了库**，而不只是返回了一句「好的」。
 */
@SpringBootTest
class CreateFoodToolTest {

    /** 每次运行唯一的后缀。见类注释里关于「测试跑在真库上」的说明。 */
    private static String uniq(String base) {
        return base + "-" + Long.toHexString(System.nanoTime());
    }

    @Autowired AgentTools tools;

    @Autowired FoodRepo foods;

    @Test
    void 建完之后库里真的有这条食物() {
        String name = uniq("工具建的黄焖鸡");
        String out = tools.execute("create_food", """
                {"name":"%s","grams":400,"protein":28.8,"fat":12,"carb":40}
                """.formatted(name));
        assertThat(out).contains(name);

        List<Food> hit = foods.findAll().stream()
                .filter(f -> name.equals(f.nameZh))
                .toList();
        assertThat(hit).hasSize(1);
        // 工具收的是「这一份」的值，库里存的是每 100 克：28.8/400*100 = 7.2
        assertThat(hit.get(0).proteinPer100).isEqualTo(7.2);
        assertThat(hit.get(0).fatPer100).isEqualTo(3.0);
        assertThat(hit.get(0).carbPer100).isEqualTo(10.0);
        // 没给热量时按 4/4/9：(4*28.8 + 4*40 + 9*12)/400*100 = 95.8
        assertThat(hit.get(0).kcalPer100).isEqualTo(95.8);
    }

    @Test
    void 份量为0时拒绝并且不写库() {
        int before = foods.findAll().size();
        assertThatThrownBy(() -> tools.execute("create_food", """
                {"name":"%s","grams":0,"protein":5}
                """.formatted(uniq("工具建的坏数据"))))
                .hasMessageContaining("多少克");
        assertThat(foods.findAll().size()).isEqualTo(before);
    }

    @Test
    void 重名时拒绝() {
        String name = uniq("工具建的重名");
        tools.execute("create_food", """
                {"name":"%s","grams":100,"protein":5}
                """.formatted(name));
        assertThatThrownBy(() -> tools.execute("create_food", """
                {"name":"%s","grams":100,"protein":5}
                """.formatted(name)))
                .hasMessageContaining("已经有");
    }
}
