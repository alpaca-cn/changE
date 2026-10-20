package com.change.web;

import com.change.service.ExerciseDetailService;
import com.change.service.ExerciseImportService;
import com.change.service.ExerciseInstructionsZhService;
import com.change.service.ExerciseNameZhService;
import com.change.service.ExerciseQueryService;
import com.change.service.LastPerformanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作库的维护入口：看状态、装回来、整体移除。
 *
 * <h2>为什么是 HTTP 端点</h2>
 *
 * <p>动作库是**随包分发的内容**，正常情况下用户不需要管它——启动时会自动装一次
 * （见 {@code ExerciseImportRunner}）。但「移除整个动作库，或把它装回来」得有个入口，
 * 否则 {@link ExerciseImportService#unimport()} 就是一段没有调用方的死代码（本项目的
 * 失效形态 D）。这个应用是「本机后端 + 前端界面」，界面将来要用它，所以做成端点而不是
 * 一个只在命令行里跑的子命令。后端只监听 {@code 127.0.0.1}（见 application.yml），
 * 与健康端点同一套暴露面。
 *
 * <h2>三个端点各自意味着什么</h2>
 *
 * <ul>
 *   <li>{@code GET /status}：只读。给界面显示「动作库：876 个动作，中文名 876 个」。</li>
 *   <li>{@code POST /import}：幂等（靠 {@code source_id} 跳过已存在的），所以可以随时点。
 *       它**不受**「从没撤销过」那条启动策略限制——用户显式要求装回来时就是要装。</li>
 *   <li>{@code POST /unimport}：整体移除导入的动作（手工动作不动）。它**会**让
 *       {@link ExerciseImportService#shouldInstallOnStartup()} 从此返回 false，
 *       也就是移除之后重启不会又冒出来；想装回来走上面的 {@code POST /import}。</li>
 * </ul>
 *
 * <p>注意：移除是**物理删除**导入的动作，并留下一条汇总日志（可追溯）。这与
 * {@code exercise.archived} 的含义不同——归档是「隐藏单个动作、但仍要能被旧记录解析」，
 * 属于逐个动作的操作；这里移除的是整批随包内容。
 */
@RestController
@RequestMapping("/api/exercises")
public class ExerciseController {

    private final ExerciseImportService importService;
    private final ExerciseNameZhService nameZhService;
    private final ExerciseInstructionsZhService instructionsZhService;
    private final LastPerformanceService lastPerformanceService;
    private final ExerciseQueryService query;
    private final ExerciseDetailService details;
    private final JdbcTemplate jdbc;

    public ExerciseController(ExerciseImportService importService,
                              ExerciseNameZhService nameZhService,
                              ExerciseInstructionsZhService instructionsZhService,
                              LastPerformanceService lastPerformanceService,
                              ExerciseQueryService query,
                              ExerciseDetailService details,
                              JdbcTemplate jdbc) {
        this.importService = importService;
        this.nameZhService = nameZhService;
        this.instructionsZhService = instructionsZhService;
        this.lastPerformanceService = lastPerformanceService;
        this.query = query;
        this.details = details;
        this.jdbc = jdbc;
    }

    /**
     * 动作选择器：按肌群 / 关键词 / 分类筛。
     *
     * <p>不给 {@code category} 时**排除拉伸**（见 {@link ExerciseQueryService} 的说明）。
     */
    @GetMapping
    public List<ExerciseQueryService.ExerciseSummary> list(
            @RequestParam(required = false) Long muscleGroupId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer limit) {
        return query.search(muscleGroupId, q, category, limit);
    }

    /**
     * 动作详情：做法（中英文）与肌群，给「点一个动作看它怎么做」的面板用。
     *
     * <p>不存在时 404 + {@code {error}}（见 {@code ApiErrors}）；存在但没有任何做法/肌群时
     * 仍是 200——「这个动作没有原文」是数据事实，不是请求错误。
     *
     * <p>返回值刻意只包含前端需要的九个键，不返回 {@code source_id} / {@code note} /
     * {@code archived}：每多一个键都是要维护的契约。
     */
    @GetMapping("/{id}")
    public ExerciseDetailService.ExerciseDetail detail(@PathVariable long id) {
        return details.detail(id);
    }

    /** 只读状态。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", count(
                "SELECT COUNT(*) FROM exercise WHERE source_id IS NOT NULL"));
        out.put("withChineseName", count(
                "SELECT COUNT(*) FROM exercise WHERE source_id IS NOT NULL AND name_zh IS NOT NULL"));
        out.put("everUnimported", importService.wasEverUnimported());
        return out;
    }

    /**
     * 安装（幂等），并把中文名与中文做法一并写好——只导入动作不写这两样的话，库会停在
     * 「有动作、没中文名/没中文做法」的半成品状态（要等下一次重启才补）。重新导入是
     * 「删了重建」，不在这里补，871 条中文做法会静默消失在 NULL 里。
     *
     * <p><b>顺序与启动时一致</b>（{@code @Order(1)} 导入 → {@code @Order(2)} 中文名 →
     * {@code @Order(3)} 中文做法）：两者互不依赖，但中文名先、中文做法后是启动路径上的既有顺序。
     *
     * <p>响应是导入结果：inserted / skipped / musclesInserted。
     */
    @PostMapping("/import")
    public ExerciseImportService.Result importAll() {
        ExerciseImportService.Result result = importService.importAll();
        nameZhService.applyMappingFromClasspath();
        instructionsZhService.applyMappingFromClasspath();
        return result;
    }

    /** 整体移除导入的动作，返回删掉的动作数（没有可删的时返回 0，幂等）。 */
    @PostMapping("/unimport")
    public Map<String, Object> unimport() {
        return Map.of("removed", importService.unimport());
    }

    /** 某动作上次练了多重；从没做过时 204（不是 404——「没做过」不是错误）。 */
    @GetMapping("/{id}/last-performance")
    public ResponseEntity<LastPerformanceService.LastPerformance> lastPerformance(@PathVariable long id) {
        return lastPerformanceService.lastPerformance(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private Integer count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
