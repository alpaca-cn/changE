package com.change.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 周容量端点：日期参数解析 + 17 块肌群的形状。具体数值由 WeeklyVolumeServiceTest 覆盖。 */
@SpringBootTest
@AutoConfigureMockMvc
class VolumeControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void weekly_volume_accepts_an_iso_date_and_returns_all_muscles() throws Exception {
        mvc.perform(get("/api/volume/weekly").param("date", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value("2026-10-12"))
                .andExpect(jsonPath("$.weekEnd").value("2026-10-18"))
                .andExpect(jsonPath("$.muscles.length()").value(27))
                .andExpect(jsonPath("$.muscles[0].nameEn").exists())
                .andExpect(jsonPath("$.muscles[0].status").exists());
    }

    @Test
    void a_bad_date_is_a_400() throws Exception {
        mvc.perform(get("/api/volume/weekly").param("date", "2026/10/14"))
                .andExpect(status().isBadRequest());
    }
}
