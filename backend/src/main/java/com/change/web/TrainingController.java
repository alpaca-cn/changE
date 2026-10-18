package com.change.web;

import com.change.service.WorkoutService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 训练记录的写入口。 */
@RestController
@RequestMapping("/api/sessions")
public class TrainingController {

    private final WorkoutService workouts;

    public TrainingController(WorkoutService workouts) {
        this.workouts = workouts;
    }

    /** 记一次完整训练。请求体见 {@link WorkoutService.SessionInput}。 */
    @PostMapping
    public WorkoutService.Result record(@RequestBody WorkoutService.SessionInput input) {
        return workouts.recordSession(input);
    }
}
