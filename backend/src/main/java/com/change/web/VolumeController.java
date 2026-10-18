package com.change.web;

import com.change.service.WeeklyVolumeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 容量的读入口。 */
@RestController
@RequestMapping("/api/volume")
public class VolumeController {

    private final WeeklyVolumeService weeklyVolume;

    public VolumeController(WeeklyVolumeService weeklyVolume) {
        this.weeklyVolume = weeklyVolume;
    }

    /**
     * 某一天所在那一周的各肌群折算组数。{@code date} 省略时取今天。
     *
     * <p>格式锁死 ISO（{@code YYYY-MM-DD}）；写错的日期是 400，不是静默取今天。
     */
    @GetMapping("/weekly")
    public WeeklyVolumeService.WeeklyVolume weekly(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return weeklyVolume.weekly(date == null ? LocalDate.now() : date);
    }
}
