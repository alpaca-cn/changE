package com.change.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 数据备份：把整个库导出成一个独立文件。
 *
 * ## 为什么这个功能比它看起来重要
 *
 * 这是个只在本地跑的应用——没有云、没有同步、没有账号。硬盘坏了、把文件夹删了、
 * 换电脑，数据就没了。而用户现在已经往里记了真实的训练与饮食。
 * 「能导出」是这类应用最基本的承诺之一，没有它，用户的数据安全感是空的。
 *
 * ## 为什么用 `VACUUM INTO` 而不是直接拷文件
 *
 * 直接拷 {@code change.db} 在应用运行中是不安全的：SQLite 可能正在写 WAL，
 * 拷到的可能是「主库 + 半个 WAL」，恢复时数据对不上。
 * `VACUUM INTO` 是 SQLite 自己的在线备份做法——它在一次读事务里生成一个
 * **一致且已整理**的新库文件，不需要停应用，也不依赖文件系统层面的原子性。
 *
 * 附带好处：导出的文件是 VACUUM 之后的，比原库小，也没有空闲页。
 *
 * ## 为什么落在「文档」而不是数据目录里
 *
 * 备份和数据放同一个文件夹，防不住「整个文件夹被删」和「磁盘坏了」这两件最可能的事。
 * 所以默认落到用户的 Documents 下；拿不到 Documents 时才退回数据目录，
 * 并在返回结果里**如实说明落在哪**，让用户自己决定要不要再拷到别处。
 */
@Service
public class BackupService {

    /**
     * 备份文件名的时间戳。精确到秒。
     *
     * <p>文件名形如 `change-backup-20261021-181500-01.db`——**末尾那个两位计数是定宽的、
     * 而且永远存在**。这不是为了好看：`list()` 与 `prune()` 都靠文件名的字典序来近似
     * 「最新的在前」，而字典序里 `-2` 排在 `.db` **之前**（`-` 是 0x2D，`.` 是 0x2E），
     * 于是「同秒第二份」会被当成更旧的那一个——清理时先删掉它。
     * 定宽两位计数让「字典序 = 时间序」在所有情况下都成立。
     */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 最多保留多少个备份。够回溯几次，又不至于把用户的文档塞满。 */
    static final int KEEP = 10;

    private final JdbcTemplate jdbc;
    private final Path dataDir;

    public BackupService(JdbcTemplate jdbc, @Value("${change.data-dir}") String dataDir) {
        this.jdbc = jdbc;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
    }

    /** 一次备份的结果。 */
    public record Backup(String path, long bytes, String at, boolean inDocuments) {}

    /**
     * 生成一个备份。
     *
     * <p>**不要**加 {@code @Transactional}：`VACUUM` 不能在事务里跑，
     * 而 Spring 的事务会把连接置为 autocommit=false。
     *
     * @throws IllegalStateException 目标目录不可写、或 SQLite 拒绝生成
     */
    public Backup create() {
        Path dir = targetDir();
        String base = "change-backup-" + LocalDateTime.now().format(STAMP);
        Path file = uniqueIn(dir, base);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建备份目录: " + dir, e);
        }

        // `VACUUM INTO` 的参数位置只接受表达式，而部分驱动对「绑定参数」支持不一致。
        // 这里的路径是我们自己拼的（目录 + 时间戳），不含用户输入，所以直接内联；
        // 仍然把单引号转义掉，免得路径里恰好带引号时拼出坏 SQL。
        String escaped = file.toString().replace("'", "''");
        try {
            jdbc.execute("VACUUM INTO '" + escaped + "'");
        } catch (RuntimeException e) {
            throw new IllegalStateException("导出失败：" + e.getMessage(), e);
        }

        long bytes;
        try {
            bytes = Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("备份写出去了但读不到大小: " + file, e);
        }

        prune(dir);
        return new Backup(file.toString(), bytes,
                LocalDateTime.now().format(HUMAN),
                dir.equals(documentsDir()));
    }

    /** 已有的备份，按时间倒序。前端用它显示「上次备份是什么时候」。 */
    public List<Backup> list() {
        List<Backup> out = new ArrayList<>();
        for (Path dir : List.of(documentsDir(), dataDir.resolve("backups"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().startsWith("change-backup-"))
                        .filter(p -> p.getFileName().toString().endsWith(".db"))
                        .forEach(p -> {
                            try {
                                out.add(new Backup(p.toString(), Files.size(p),
                                        Files.getLastModifiedTime(p).toString(),
                                        dir.equals(documentsDir())));
                            } catch (IOException ignored) {
                                // 单个文件读不到不该让整个列表失败
                            }
                        });
            } catch (IOException ignored) {
                // 目录读不到就跳过它
            }
        }
        out.sort(Comparator.comparing(Backup::path).reversed());
        return out;
    }

    /** 备份落在哪：优先 Documents，拿不到就退回数据目录。 */
    public Path targetDir() {
        Path docs = documentsDir();
        return docs == null ? dataDir.resolve("backups") : docs;
    }

    /** `~/Documents/changE`；拿不到 user.home 时返回 null。 */
    private Path documentsDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, "Documents", "changE").toAbsolutePath().normalize();
    }

    /** 同秒时追加 -02 / -03。计数定宽两位，保证字典序与时间序一致（见 {@link #STAMP}）。 */
    private Path uniqueIn(Path dir, String base) {
        for (int n = 1; n < 100; n++) {
            Path candidate = dir.resolve(String.format("%s-%02d.db", base, n));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        // 同一秒内做了 99 次备份：直接失败，别覆盖别人的文件
        throw new IllegalStateException("同一秒内备份次数过多，等一秒再试");
    }

    /** 只保留最近 {@link #KEEP} 个。删掉的是导出文件，不是原始数据——失败也就算了。 */
    private void prune(Path dir) {
        try (var stream = Files.list(dir)) {
            var files = stream
                    .filter(p -> p.getFileName().toString().startsWith("change-backup-"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = KEEP; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException ignored) {
            // 清理失败不影响这次备份的成功
        }
    }
}
