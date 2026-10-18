package com.change.web;

import com.change.service.WorkoutService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 训练记录的读写入口。 */
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

    /** 最近 5 次训练。 */
    @GetMapping("/recent")
    public List<WorkoutService.SessionSummary> recent() {
        return workouts.recentSessions();
    }

    /** 一次训练的详情；不存在时 404。 */
    @GetMapping("/{id}")
    public WorkoutService.SessionDetail detail(@PathVariable long id) {
        return workouts.sessionDetail(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "找不到这次训练: " + id));
    }
}
