package com.change.config;

import com.change.service.ExerciseImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把 vendor 的动作库导入库（可关闭）。
 *
 * <p><b>为什么需要它</b>：{@link ExerciseImportService} 原本没有任何生产调用方——
 * 干净库上启动后 {@code exercise} 是 0（实测）。876 条动作是随包分发的**内容**，
 * 用户第一次打开就该看得见，所以由启动钩子导入。
 *
 * <p><b>为什么可以每次启动都跑</b>：{@code importAll()} 是幂等的（按 {@code source_id} 跳过
 * 已存在的行）。首次启动写入 876 动作 + 2587 标注（约 1–3 秒）；之后每次启动只是解析一遍
 * 数据集、查一次已有 id，然后全部跳过，开销很小。
 *
 * <p><b>与 {@code unimport()} 的关系（一个已知的取舍）</b>：既然每次启动都会导入，
 * 一次 {@code unimport()} 之后**重启会把动作库装回来**。对「随包分发的动作库」来说这是
 * 合理语义（它是应用内容，不是用户数据；unimport 更像「重置」）；但若将来要让用户的
 * 删除动作持久生效，就得在这里加一个「只导入一次」的持久标记。这一点已在总览文档里记下。
 *
 * <p><b>失败时的行为</b>：数据集损坏 / sha256 不符时 {@code importAll()} 会抛异常，
 * 应用**启动失败**——这是刻意的（数据集换了版本必须让人知道，不要静默给一个缺斤少两的库）。
 *
 * <p>用 {@code @ConditionalOnProperty} 开关（默认开）：测试套件把
 * {@code change.exercise.import-on-startup=false}（见 {@code src/test/resources/application.properties}），
 * 否则每个 {@code @SpringBootTest} 都会往共享库塞 876 行——那会让 {@code TrainingRepoTest}
 * 的 {@code Plank} 撞上 {@code ux_exercise_name_en}，红在无关的地方。
 */
@Component
@Order(1)
@ConditionalOnProperty(prefix = "change.exercise", name = "import-on-startup",
        havingValue = "true", matchIfMissing = true)
public class ExerciseImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExerciseImportRunner.class);

    private final ExerciseImportService importService;

    public ExerciseImportRunner(ExerciseImportService importService) {
        this.importService = importService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 只在「库里没有、且用户从没撤销过」时才装。撤销过就让它保持被撤销的状态——
        // 否则「撤销」会被下一次重启悄悄推翻。要装回来是显式动作（POST /api/exercises/import）。
        if (!importService.shouldInstallOnStartup()) {
            log.info("动作库：已安装（或已被撤销），跳过启动导入");
            return;
        }
        ExerciseImportService.Result result = importService.importAll();
        log.info("动作库导入：新增 {} 个动作 / 跳过 {} 个（已存在）/ 写入 {} 条肌群标注（源前缀 {}）",
                result.inserted(), result.skipped(), result.musclesInserted(),
                ExerciseImportService.SOURCE_PREFIX);
    }
}
