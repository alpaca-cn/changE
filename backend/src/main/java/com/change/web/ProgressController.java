package com.change.web;

import com.change.service.ProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进步曲线的读入口。
 *
 * <p>这一层只做路由：from/to 是**字符串**而不是 LocalDate，与
 * {@link BodyMetricController} 同一个理由——格式不对时走服务层的
 * {@link IllegalArgumentException}，由 {@link ApiErrors} 翻成 `{error}` 形状的 400；
 * 用 {@code @DateTimeFormat} 的话它会变成 {@code MethodArgumentTypeMismatchException}，
 * 正文里带参数名和类型名，前端还得认第二种形状。
 *
 * <p>动作不存在是 404（由服务层抛 {@link org.springframework.web.server.ResponseStatusException}），
 * 动作存在但从没练过是 200 + {@code "points": []}：这两件事对调用方意义完全不同。
 */
@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final ProgressService progress;

    public ProgressController(ProgressService progress) {
        this.progress = progress;
    }

    /**
     * 一个动作的曲线：{@code /api/progress/{exerciseId}?from=&to=}。
     *
     * <p>from/to 是闭区间，可省略；点只出现在**有训练数据的那天**，不补 0。
     * 那天的组全都估不出 1RM 时 `e1rm` 是 null（点仍然在），
     * `maxWeight` / `totalVolume` 照常给值。
     */
    @GetMapping("/{exerciseId}")
    public ProgressService.ExerciseProgress progress(
            @PathVariable long exerciseId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return progress.progress(exerciseId, from, to);
    }
}
