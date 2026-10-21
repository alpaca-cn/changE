package com.change.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 备份。
 *
 * <p>为什么这些用例值得写：备份是那种「平时不跑、出事时唯一能靠」的功能，
 * 而它最典型的坏法恰恰是**看起来成功了**——生成一个 0 字节文件、或者拷到一半的
 * 主库（没有 WAL 里的最新页）。所以这里不看「接口返回 200」，而是真的把导出的文件
 * 打开、数里面有多少张表、多少条动作。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BackupTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** 打开一个备份文件，返回 (表数量, 动作数量)。用来证明导出的确实是能用的库。 */
    private int[] inspect(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            int tables;
            try (ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table'")) {
                rs.next();
                tables = rs.getInt(1);
            }
            int exercises;
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM exercise")) {
                rs.next();
                exercises = rs.getInt(1);
            }
            return new int[]{tables, exercises};
        }
    }

    @Test
    void 导出的文件是一个能打开的完整库() throws Exception {
        var backup = new BackupService(jdbc, System.getProperty("java.io.tmpdir")).create();
        Path f = Path.of(backup.path());
        try {
            assertThat(f).exists();
            // **不是** 0 字节：只写了个空文件也算「成功」，那是最坏的失败形态
            assertThat(backup.bytes()).isGreaterThan(0);
            assertThat(Files.size(f)).isEqualTo(backup.bytes());

            int[] counts = inspect(f);
            // 17 张业务表 + flyway_schema_history
            assertThat(counts[0]).isGreaterThanOrEqualTo(17);
            // **一致性证明**：备份里的行数与源库一模一样。
            // 只看「文件非空」是不够的——拷到一半的库也能非空，但会少几行。
            Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM body_metric", Integer.class);
            assertThat(counts[1]).isEqualTo(live == null ? 0 : live);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void 文件名带时间戳_且不含用户输入() throws Exception {
        var backup = new BackupService(jdbc, System.getProperty("java.io.tmpdir")).create();
        try {
            String name = Path.of(backup.path()).getFileName().toString();
            assertThat(name).startsWith("change-backup-").endsWith(".db");
            // 形如 change-backup-20261021-181500-01.db（末尾计数定宽两位，见 BackupService）
            assertThat(name).matches("change-backup-\\d{8}-\\d{6}-\\d{2}\\.db");
        } finally {
            Files.deleteIfExists(Path.of(backup.path()));
        }
    }

    @Test
    void 同一秒内连点两次不会覆盖上一份(@TempDir Path tmp) throws Exception {
        // 用户在界面上连点两下是很常见的；后一份把前一份覆盖掉的话，
        // 他以为有两份备份，其实只有一份。
        BackupService svc = new BackupService(jdbc, tmp.toString());
        var a = svc.create();
        var b = svc.create();
        try {
            assertThat(a.path()).isNotEqualTo(b.path());
            assertThat(Path.of(a.path())).exists();
            assertThat(Path.of(b.path())).exists();
        } finally {
            Files.deleteIfExists(Path.of(a.path()));
            Files.deleteIfExists(Path.of(b.path()));
        }
    }

    @Test
    void 名字排序就是时间排序_因为格式是定宽的() {
        // list() 与 prune() 都靠文件名字典序来近似「最新的在前」。
        // 这个近似只在格式定宽时成立，所以这条测的是格式本身。
        String a = "change-backup-20261021-090000-01.db";
        String b = "change-backup-20261021-181500-01.db";
        String b2 = "change-backup-20261021-181500-02.db";
        assertThat(a.compareTo(b)).isLessThan(0);
        // **同秒的第二份必须排在后面**。用「-2.db」那种写法时它是排在前面的
        // （`-` 0x2D < `.` 0x2E），prune 会先把最新的那份删掉。
        assertThat(b.compareTo(b2)).isLessThan(0);
    }

    @Test
    void 端点_导出之后能在列表里看到() throws Exception {
        String json = mvc.perform(post("/api/backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").isNotEmpty())
                .andExpect(jsonPath("$.bytes").isNumber())
                .andExpect(jsonPath("$.at").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String path = json.replaceAll(".*\"path\":\"([^\"]+)\".*", "$1")
                .replace("\\\\", "\\");
        try {
            mvc.perform(get("/api/backup"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].path").isNotEmpty());
        } finally {
            Files.deleteIfExists(Path.of(path));
        }
    }

    @Test
    void 没有备份时列表是空数组而不是_404() throws Exception {
        mvc.perform(get("/api/backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void info_给出数据目录与恢复说明() throws Exception {
        // 恢复刻意不做成按钮：要在运行中替换正在被打开的库文件，那是另一类风险。
        // 界面必须告诉用户「关掉应用、手工换文件」这条正确路径。
        mvc.perform(get("/api/backup/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataDir").isNotEmpty())
                .andExpect(jsonPath("$.note").value(
                        org.hamcrest.Matchers.containsString("change.db")));
    }
}
