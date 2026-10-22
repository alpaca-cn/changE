package com.change.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户自建食物。
 *
 * <p>这一组用例守的是**换算口径**：用户填的是每份值，库里的每 100 克值。
 * 这类换算一旦方向反了，界面上不会报错 —— 只会让每一餐都差好几倍，
 * 而用户看不出来是哪一步错的。
 *
 * <h2>为什么每个名字都带唯一后缀</h2>
 *
 * <p><b>测试跑在真实的 SQLite 文件上，不是内存库。</b> 所以同一条用例跑第二次
 * 会撞上第一次留下的那条食物，然后被判重名拒绝 —— 而那看起来像「功能坏了」。
 * 2026-10-22 就撞过一次。名字唯一化之后，这些用例可以反复跑。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomFoodTest {

    @Autowired MockMvc mvc;

    @Autowired ObjectMapper json;

    /** 每次运行唯一的后缀。见类注释里关于「测试跑在真库上」的说明。 */
    private static String uniq(String base) {
        return base + "-" + Long.toHexString(System.nanoTime());
    }

    private String body(Object... kv) throws Exception {
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return json.writeValueAsString(map);
    }

    @Test
    void 每份值换算成每100克() throws Exception {
        String name = uniq("测试麻辣烫A");
        // 一份 400 克，含蛋白 28.8 克、脂肪 12 克、碳水 40 克
        String payload = body("nameZh", name, "grams", 400,
                "protein", 28.8, "fat", 12, "carb", 40);
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                // 库里的 CHECK 约束只允许 manual / cn-food-table / agent / takeout
                .andExpect(jsonPath("$.source").value("manual"))
                // 28.8 / 400 * 100 = 7.2
                .andExpect(jsonPath("$.per100.protein").value(7.2))
                // 12 / 400 * 100 = 3.0
                .andExpect(jsonPath("$.per100.fat").value(3.0))
                // 40 / 400 * 100 = 10.0
                .andExpect(jsonPath("$.per100.carb").value(10.0));
    }

    @Test
    void 没给热量时按4_4_9从宏量推算() throws Exception {
        String name = uniq("测试食物B");
        // 一份 100 克：蛋白 10、脂肪 5、碳水 20 -> 4*10 + 4*20 + 9*5 = 165
        String payload = body("nameZh", name, "grams", 100,
                "protein", 10, "fat", 5, "carb", 20);
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.per100.kcal").value(165.0));
    }

    @Test
    void 给了热量就用给的那个不覆盖() throws Exception {
        String name = uniq("测试食物C");
        // 用户抄的是包装标签 200 千卡；若按 4/4/9 会算成 4*10+0+9*5=85，
        // 但包装上的热量含了别的东西，要用用户的。
        String payload = body("nameZh", name, "grams", 100,
                "protein", 10, "fat", 5, "carb", 0, "kcal", 200);
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.per100.kcal").value(200.0));
    }

    @Test
    void 建完立刻能在搜索里搜到() throws Exception {
        String name = uniq("测试沙县小吃D");
        String payload = body("nameZh", name, "grams", 350,
                "protein", 20, "fat", 15, "carb", 60);
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());

        String found = mvc.perform(get("/api/foods").param("q", name))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(found).contains(name);
    }

    @Test
    void 重名被拒并提示直接搜它() throws Exception {
        String name = uniq("测试重名E");
        String payload = body("nameZh", name, "grams", 100, "protein", 5);
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("已经有")));
    }

    @Test
    void 份量为空或0被拒() throws Exception {
        int i = 0;
        for (Object grams : new Object[]{null, 0}) {
            String name = uniq("测试份量F" + i++);
            mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON)
                            .content(body("nameZh", name, "grams", grams, "protein", 5)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(
                            org.hamcrest.Matchers.containsString("多少克")));
        }
    }

    @Test
    void 什么都不填被拒() throws Exception {
        String name = uniq("测试空G");
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON)
                        .content(body("nameZh", name, "grams", 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("至少填一项")));
    }

    @Test
    void 名字为空被拒() throws Exception {
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON)
                        .content(body("nameZh", "   ", "grams", 100, "protein", 5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("名字")));
    }

    @Test
    void 把每份当成每100克填会被拦下() throws Exception {
        String name = uniq("测试离谱H");
        // 一份只填 50 克却写了 600 千卡 -> 每 100 克 1200 千卡，超过 800 的上界。
        // 不拦的话用户下次吃 300 克会看到 3600 千卡，而没人解释得清。
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON)
                        .content(body("nameZh", name, "grams", 50, "kcal", 600)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("常识")));
    }

    @Test
    void 负数被拒() throws Exception {
        String name = uniq("测试负数I");
        mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON)
                        .content(body("nameZh", name, "grams", 100, "protein", -5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("负数")));
    }

    @Test
    void 建好后带一个1份的份量单位() throws Exception {
        String name = uniq("测试份量单位J");
        // 用户是按「份」想的；没有这个单位，他下次录入还得自己算克数
        String created = mvc.perform(post("/api/foods").contentType(MediaType.APPLICATION_JSON)
                        .content(body("nameZh", name, "grams", 250, "protein", 10)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> parsed = json.readValue(created, Map.class);
        assertThat(parsed.get("portions").toString()).contains("1 份").contains("250.0");
    }
}
