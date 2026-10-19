package com.change.config;

import com.change.service.ExerciseInstructionsZhService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把 {@code seed/instructions-zh.json} 里的中文做法写进动作库。
 *
 * <p><b>为什么要有它</b>：{@link ExerciseInstructionsZhService} 是纯机械的「写译文」逻辑，
 * 如果没有调用方，它就是死代码（本项目一路在消灭的失效形态 D）。这份映射随包分发，
 * 启动时应用一次即可。
 *
 * <p><b>必须排在导入与中文名之后</b>（{@code @Order(3)}：导入 {@code @Order(1)}、
 * 中文名 {@code @Order(2)}）：做法要写在已导入的动作上，导入没跑完时库里没有那些动作，
 * 映射会全部落空。排在中文名之后是刻意的顺序——两个写入器互不依赖，但一个人的详情页
 * 上「中文名 + 中文做法」应当同时就绪。
 *
 * <p><b>幂等</b>：{@link ExerciseInstructionsZhService#apply} 只改
 * {@code instructions_zh IS NULL} 的行，所以每次启动都会跳过已写好的；数据库是既有的
 * 老库时也不会重复劳动。
 *
 * <p><b>没有要写的行时直接跳过</b>（{@link ExerciseInstructionsZhService#hasUnwrittenImportedInstructions()}）：
 * 正常启动不必解析那份 473 KB 的映射。映射文件缺失（比如裁剪过的发行版）时只记一行日志——
 * 中文做法不是必需品（{@code instructions_zh} 可空），缺了不该让应用起不来。
 *
 * <p>与导入共用 {@code change.exercise.import-on-startup} 开关：测试套件整体关掉它，
 * 否则每个 {@code @SpringBootTest} 都会往共享库塞 876 个动作 + 871 条做法。
 */
@Component
@Order(3)
@ConditionalOnProperty(prefix = "change.exercise", name = "import-on-startup",
        havingValue = "true", matchIfMissing = true)
public class ExerciseInstructionsZhRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExerciseInstructionsZhRunner.class);

    private final ExerciseInstructionsZhService instructionsZhService;

    public ExerciseInstructionsZhRunner(ExerciseInstructionsZhService instructionsZhService) {
        this.instructionsZhService = instructionsZhService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!instructionsZhService.hasUnwrittenImportedInstructions()) {
            log.info("动作中文做法：都已写好，跳过");
            return;
        }
        ExerciseInstructionsZhService.Result r =
                instructionsZhService.applyMappingFromClasspath();
        if (r == null) {
            log.info("classpath 上没有 {}，跳过中文做法写入",
                    ExerciseInstructionsZhService.MAPPING_RESOURCE);
            return;
        }
        log.info("动作中文做法：写入 {} / 已有跳过 {} / 原文为空 {} / 找不到导入动作 {}",
                r.updated(), r.skippedAlready(), r.skippedEmptySource(), r.skippedNotImported());
    }
}
