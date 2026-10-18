package com.change.config;

import com.change.service.ExerciseNameZhService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时把 {@code seed/name-zh.json} 里的中文名写进动作库。
 *
 * <p><b>为什么要有它</b>：{@link ExerciseNameZhService} 是纯机械的「写名字」逻辑，如果没有
 * 调用方，它就是死代码（本项目一路在消灭的失效形态 D）。这份映射随包分发，启动时应用一次即可。
 *
 * <p><b>必须排在导入之后</b>（{@code @Order(2)}，导入是 {@code @Order(1)}）：名字要写在已导入的
 * 动作上，导入没跑完时库里没有那些动作，映射会全部落空。
 *
 * <p><b>幂等</b>：{@link ExerciseNameZhService#apply} 只改 {@code name_zh IS NULL} 的行，
 * 所以每次启动都会跳过已写好的；数据库是既有的老库时也不会重复劳动。
 *
 * <p>映射文件缺失（比如裁剪过的发行版）时不报错，只记一行日志跳过——中文名不是必需品
 * （{@code name_zh} 可空），缺了不该让应用起不来。
 *
 * <p>与导入共用 {@code change.exercise.import-on-startup} 开关：测试套件整体关掉它，
 * 否则每个 {@code @SpringBootTest} 都会往共享库塞 876 个动作 + 876 个中文名。
 */
@Component
@Order(2)
@ConditionalOnProperty(prefix = "change.exercise", name = "import-on-startup",
        havingValue = "true", matchIfMissing = true)
public class ExerciseNameZhRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExerciseNameZhRunner.class);

    /** classpath 上的映射位置。 */
    public static final String MAPPING_RESOURCE = "seed/name-zh.json";

    private final ExerciseNameZhService nameZhService;
    private final ObjectMapper json = new ObjectMapper();

    public ExerciseNameZhRunner(ExerciseNameZhService nameZhService) {
        this.nameZhService = nameZhService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> mapping = loadMapping();
        if (mapping == null) {
            log.info("classpath 上没有 {}，跳过中文名写入", MAPPING_RESOURCE);
            return;
        }
        ExerciseNameZhService.Result r = nameZhService.apply(mapping);
        log.info("动作中文名：写入 {} / 已有跳过 {} / 重名跳过 {} / 找不到导入动作 {}",
                r.updated(), r.skippedAlreadyNamed(), r.skippedDuplicate(), r.skippedNotImported());
    }

    /** 读映射；文件不存在返回 null（不算错误）。 */
    private Map<String, String> loadMapping() {
        if (!new ClassPathResource(MAPPING_RESOURCE).exists()) {
            return null;
        }
        try (var in = new ClassPathResource(MAPPING_RESOURCE).getInputStream()) {
            return json.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("读取中文名映射失败: " + MAPPING_RESOURCE, e);
        }
    }
}
