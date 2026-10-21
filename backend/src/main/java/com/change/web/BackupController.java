package com.change.web;

import com.change.service.BackupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据备份的入口。
 *
 * <p>只有两个动作：现在导出一份、看看已经导过哪些。
 * **不做「恢复」**——恢复要在应用运行时替换正在被打开的库文件，那是另一类风险
 * （得先停后端、换文件、再起来）。恢复的正确做法是让用户关掉应用、手工把备份文件
 * 改名成 {@code change.db} 放回数据目录，README 与界面上都这么写。
 * 在这条路径上省掉一个按钮，比提供一个半可靠的恢复安全。
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    /** 导出一份。返回文件路径与大小——用户需要知道东西在哪，才能把它拷走。 */
    @PostMapping
    public BackupService.Backup create() {
        return backups.create();
    }

    /** 已有的备份，按时间倒序。没有就返回空数组，不是 404。 */
    @GetMapping
    public List<BackupService.Backup> list() {
        return backups.list();
    }

    /** 应用版本与数据目录位置，给「关于」和排查问题用。 */
    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "dataDir", backups.targetDir().toString(),
                "note", "恢复：关掉应用，把备份文件改名成 change.db 放回数据目录。");
    }
}
