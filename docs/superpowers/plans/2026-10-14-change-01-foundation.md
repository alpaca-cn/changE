# 地基：数据层与核心计算 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立后端骨架、十五张表的数据层，以及三个必须正确的核心计算（热量、份量换算、训练容量），让后续所有功能都有可信的地基。

**Architecture:** 单体 Spring Boot 应用，SQLite 单文件存储，Flyway 管迁移。三个核心计算写成**不依赖 Spring 上下文**的纯类，用单元测试锁死行为——因为它们的错误会污染所有下游统计，而且很难事后发现。

**Tech Stack:** JDK 25 · Spring Boot 3.5.16 · Flyway · SQLite（Hibernate 社区方言）· LangChain4j 1.22.0（仅装配，本计划不使用）· Redis（可选，必须可降级）

**前置：** 读 `docs/superpowers/plans/2026-10-14-change-phase1-overview.md` 的「全局约定」。`spike/` 里已验证过的 `pom.xml` 是本计划 Task 1 的基础。

---

## 文件结构

```
backend/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/change/
    │   │   ├── ChangeApplication.java
    │   │   ├── config/            # 缓存、端口上报等基础设施装配
    │   │   │   ├── CacheConfig.java
    │   │   │   ├── DataDirectoryInitializer.java   # Task 1：建数据目录 + 规范化路径回写
    │   │   │   └── PortReporter.java
    │   │   ├── domain/            # JPA 实体，一表一类
    │   │   │   ├── Exercise.java
    │   │   │   ├── WorkoutSession.java
    │   │   │   ├── SessionExercise.java
    │   │   │   ├── WorkoutSet.java
    │   │   │   └── ...
    │   │   ├── repo/              # Spring Data 仓储
    │   │   ├── calc/              # ★ 三个核心计算，纯类、无 Spring 依赖
    │   │   │   ├── Round.java     # Task 5：舍入工具（包内可见，饮食与 1RM 两处共用）
    │   │   │   ├── Nutrition.java
    │   │   │   ├── NutritionCalculator.java
    │   │   │   ├── PortionConverter.java
    │   │   │   ├── VolumeCalculator.java
    │   │   │   └── OneRepMax.java
    │   │   └── service/
    │   │       └── ChangeLogService.java
    │   └── resources/
    │       ├── application.yml
    │       ├── META-INF/spring.factories          # 注册 DataDirectoryInitializer
    │       └── db/migration/
    │           ├── V1__baseline.sql
    │           ├── V2__training.sql
    │           ├── V3__nutrition.sql
    │           └── V4__auxiliary.sql
    └── test/
        ├── java/com/change/
        │   ├── ChangeApplicationTests.java
        │   ├── calc/
        │   │   ├── NutritionCalculatorTest.java
        │   │   ├── PortionConverterTest.java
        │   │   ├── VolumeCalculatorTest.java
        │   │   └── OneRepMaxTest.java
        │   ├── MigrationTest.java
        │   ├── repo/TrainingRepoTest.java
        │   └── service/ChangeLogServiceTest.java
        └── resources/application.properties        # 测试用绝对数据目录，避免碰真实数据
```

**为什么 `calc/` 要独立成包且不依赖 Spring**：这三个计算的正确性决定所有统计的可信度，但它们和框架无关。做成纯类可以用最快的单元测试锁死行为，跑一次不到一秒。

---

## Task 1: 后端项目骨架

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/change/ChangeApplication.java`
- Create: `backend/src/main/java/com/change/config/DataDirectoryInitializer.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/META-INF/spring.factories`
- Test: `backend/src/test/java/com/change/ChangeApplicationTests.java`
- Test: `backend/src/test/java/com/change/config/DataDirectoryInitializerTest.java`
- Test: `backend/src/test/resources/application.properties`

**为什么需要数据目录初始化器（`DataDirectoryInitializer`）**

SQLite 不会创建父目录，目录不存在时 Flyway 会在建连阶段直接抛 `SQLITE_CANTOPEN`，Hibernate 根本轮不到执行。所以数据目录必须在**任何 Bean 实例化之前**就建好。

- 用 `EnvironmentPostProcessor` 而不是普通 Bean：它是环境准备阶段跑的，早于 `DataSource`/Flyway 的装配，也早于 `@SpringBootTest` 的环境装配——`main()` 与测试都能覆盖到（注册在 `META-INF/spring.factories`）。
- 规范化后**回写 environment**：目录的创建者与消费者不能各自解释同一个字符串。`CHANGE_DATA_DIR=E:\devtools\cache\dt\` 这种带尾部反斜杠的写法，`Path.of()` 会规范化掉，而 SQLite 拿到的是原串，结果就是 `SQLITE_CANTOPEN`。回写成同一个绝对路径后，数据源 URL、Flyway、将来的照片根目录消费的都是同一个值。
- **空串兜底**：`${APPDATA:...}` 的多级兜底只能处理「变量不存在」。`APPDATA` **存在且为空串**时（实测的机器环境），占位符会解析成空串，拼出 `/changE`，在 Windows 上等于「当前盘:\changE」。这一层在初始化器里按 `user.home` 再兜一次，兜不住就报错，绝不静默写进盘根。
- 启动日志用 `DeferredLogFactory` 注入的延迟日志打一行数据目录位置——应用起来后「库到底建哪去了」是排查时的刚需。

- [x] **Step 1: 写 pom.xml**

以 `spike/pom.xml` 为基础，去掉 spike 专用的部分，加上 Flyway。**版本号照抄，不要凭记忆改**——这些是 2026-10-14 实测通过的组合。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
    <relativePath/>
  </parent>

  <groupId>com.change</groupId>
  <artifactId>change-backend</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>changE backend</name>

  <properties>
    <java.version>25</java.version>
    <langchain4j.version>1.22.0</langchain4j.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.50.1.0</version>
    </dependency>
    <dependency>
      <groupId>org.hibernate.orm</groupId>
      <artifactId>hibernate-community-dialects</artifactId>
    </dependency>

    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>

    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j</artifactId>
      <version>${langchain4j.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-open-ai</artifactId>
      <version>${langchain4j.version}</version>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [x] **Step 2: 写启动类**

```java
package com.change;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChangeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChangeApplication.class, args);
    }
}
```

- [x] **Step 3: 写 application.yml**

```yaml
spring:
  application:
    name: change
  datasource:
    # busy_timeout=5000：SQLite 默认 busy_timeout=0，写冲突会立刻失败；给 5 秒让单写者排队
    # foreign_keys=true：SQLite 默认 PRAGMA foreign_keys=0，且该开关是「每连接」的；
    # 迁移里大量 REFERENCES ... ON DELETE CASCADE 不开这一项就只是装饰（级联删除不发生）
    url: jdbc:sqlite:${change.data-dir:./target/data}/change.db?busy_timeout=5000&foreign_keys=true&journal_mode=WAL
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1          # SQLite 单写者，连接池开大只会互相等锁
      connection-timeout: 3000      # 别用默认 30s：单连接池被占满时要快速失败而不是挂住
      # 约定：池只有 1 条连接，禁止 REQUIRES_NEW（会自己等自己）
  jpa:
    # 必须用自建方言，不能用 community 的 SQLiteDialect。
    # 实测：community 方言把 Long→bigint、boolean→boolean、LocalDate→date，
    # 而 V1–V4 里逐字写的是 INTEGER/REAL/TEXT，validate 会逐列报错、上下文起不来：
    #   Schema-validation: wrong column type encountered in column [id] in table [exercise];
    #   found [integer (Types#INTEGER)], but expecting [bigint (Types#BIGINT)]
    # ChangeSqliteDialect 覆写 columnType() 把类型映射回 schema 的四个存储类。
    database-platform: com.change.config.ChangeSqliteDialect
    hibernate:
      ddl-auto: validate            # 建表交给 Flyway，Hibernate 只校验
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: ${change.redis.host:192.168.100.128}
      port: ${change.redis.port:6379}
      timeout: 500ms
      connect-timeout: 300ms
  main:
    banner-mode: off

server:
  address: 127.0.0.1                # 无鉴权的后端只许本机访问；Tauri 壳也走 127.0.0.1
  port: 0                           # 由系统分配；实际端口由 PortReporter 上报（Task 10）

change:
  # APPDATA 存在且为空串时 ${APPDATA}/changE 会解析成 /changE，在 Windows 上等于「当前盘:\changE」，
  # 静默把库写进盘根不可接受，所以这里逐级兜底到 user.home（空串情形另由 DataDirectoryInitializer 兜底）
  data-dir: ${CHANGE_DATA_DIR:${APPDATA:${user.home}/AppData/Roaming}/changE}
  redis:
    enabled: true
```

**注意 `server.address`**：不写这一行时 Tomcat 绑的是 `::`（等价 `0.0.0.0`），而这个后端**没有任何鉴权**，Task 10 之后还会有能读写全部数据、能调模型 API 的 `/api/chat`。设计文档写的是「本地 HTTP，127.0.0.1 随机端口」，所以必须显式绑回环。

- [x] **Step 4: 写上下文加载测试**

```java
package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文加载 + 数据目录约定的验收。
 *
 * 这里刻意保持默认的 MOCK web 环境（不起真容器），端口协商与监听地址由 Task 10
 * 用 RANDOM_PORT / 显式绑定覆盖；本测试只关心「换个工作目录也不换库」这条约定。
 */
@SpringBootTest
class ChangeApplicationTests {

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Test
    void contextLoads() {
        // 能加载即通过。Redis 不可达时也必须通过——这是降级的验收点。
    }

    @Test
    void data_directory_is_absolute_created_and_usable() throws Exception {
        Path dir = Path.of(env.getProperty("change.data-dir"));

        assertThat(dir.isAbsolute())
                .as("数据目录必须是绝对路径，否则换个工作目录就换一个库")
                .isTrue();
        assertThat(Files.isDirectory(dir))
                .as("数据目录必须被自动创建（SQLite 不会建父目录）")
                .isTrue();

        try (Connection c = dataSource.getConnection()) {
            assertThat(c.isValid(1)).as("连接必须真的可用").isTrue();
        }
    }
}
```

测试用 `src/test/resources/application.properties` 把 `change.data-dir` 指到 `${user.dir}/target/data`（绝对路径），保证测试永远碰不到真实数据目录：

```properties
change.data-dir=${user.dir}/target/data
```

上面这条断言是恒真的（初始化器先回写、后读取，见 Step 5 的说明），所以另建一个补盲区的测试类。它不起 Spring 上下文，手搓 `MockEnvironment` 直接对初始化器下断言：

```java
package com.change.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接对 DataDirectoryInitializer 的行为下断言，而不是透过 @SpringBootTest 看回写后的结果。
 *
 * 为什么需要这个测试：ChangeApplicationTests 里那条断言（读到的是绝对路径）是恒真的，
 * 因为初始化器在 Spring 读取属性之前就把值回写成了绝对路径——它永远看不到「配置被改坏」，
 * 真正被证明的只是「数据目录可用」。本类补上三个盲区：
 *   - 回写与规范化这条动作本身（删掉回写 / 删掉 normalize 立刻变红）；
 *   - 盘根兜底真的兜住了（空串 APPDATA 那条路径）；
 *   - 发布的默认值不是工作目录相对路径（只能靠读未经回写的原始配置来测）。
 *
 * 全部是纯单元测试：手搓 MockEnvironment、手搓 DeferredLogFactory，不起 Spring 上下文。
 */
class DataDirectoryInitializerTest {

    /**
     * 与 spring.factories 注册的构造函数一致：真实类依赖 DeferredLogFactory，
     * 就用 DeferredLog 直接顶上，不为迁就测试去改生产代码的构造函数。
     */
    private static DataDirectoryInitializer initializer() {
        return new DataDirectoryInitializer(clazz -> new DeferredLog());
    }

    @Test
    void relative_input_is_rewritten_to_an_absolute_normalized_path() throws Exception {
        String configured = "./target/rel/";
        MockEnvironment env = new MockEnvironment();
        env.setProperty("change.data-dir", configured);

        initializer().postProcessEnvironment(env, null);

        // 断言「配置 → 绝对路径」的转换结果本身，而不是它是否可用。
        // 删掉回写那两行，或者去掉 .normalize()，这条立刻变红。
        assertThat(env.getProperty("change.data-dir"))
                .isEqualTo(Path.of(configured).toAbsolutePath().normalize().toString());
        assertThat(Files.isDirectory(Path.of(env.getProperty("change.data-dir")))).isTrue();
    }

    @Test
    void root_level_path_falls_back_under_user_home() throws Exception {
        // 这条会真的 createDirectories，所以把 user.home 指到 target 下的临时目录，
        // 绝不碰真实用户数据目录（C:\Users\<me>\AppData\Roaming）。
        Path tempHome = Path.of("target", "user-home-probe").toAbsolutePath().normalize();
        deleteRecursively(tempHome);
        Files.createDirectories(tempHome);

        String previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("change.data-dir", "/changE");

            initializer().postProcessEnvironment(env, null);

            Path result = Path.of(env.getProperty("change.data-dir"));
            Path rootLevelCandidate = Path.of("/changE").toAbsolutePath().normalize();

            // 复现前提：这个输入解析后确实只剩「盘根 + 一层」，否则这条测试没在测兜底
            assertThat(rootLevelCandidate.getNameCount()).isEqualTo(1);
            assertThat(result)
                    .as("盘根下的单个目录必须被兜底掉，而不是落在盘根")
                    .isNotEqualTo(rootLevelCandidate);
            assertThat(result.getNameCount()).isGreaterThan(1);
            assertThat(result.startsWith(tempHome)).isTrue();
            assertThat(Files.isDirectory(result)).isTrue();
            assertThat(Files.exists(rootLevelCandidate)).isFalse();
        } finally {
            System.setProperty("user.home", previousUserHome);
            deleteRecursively(tempHome);            // 跑完清理临时 home
        }
    }

    @Test
    void published_default_data_dir_is_not_a_working_directory_relative_path() throws Exception {
        // 唯一能防住「默认值被改回相对路径」的方式：读未经初始化器回写的原始配置。
        // 走 Spring 环境拿到的值一定已经是绝对路径，看不出默认值本身写的是什么。
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        Object raw = sources.get(0).getProperty("change.data-dir");

        assertThat(raw).isNotNull();
        assertThat(raw.toString()).doesNotStartWith("./").doesNotStartWith(".\\");
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
```

- [x] **Step 5: 跑测试**

Run: `cd backend && Remove-Item -Recurse -Force target; mvn -B test`

Expected: `BUILD SUCCESS`，`Tests run: 5, Failures: 0, Errors: 0`
（`ChangeApplicationTests` 2 条 + 复审补齐的 `DataDirectoryInitializerTest` 3 条）

两个测试分别锁住：① 上下文能加载（Redis 不可达时必须通过，这是 Task 11 的降级验收点）；② `change.data-dir` 是绝对路径、目录确实被创建、`DataSource` 拿到的连接真的可用。

**② 真正证明的只是「数据目录最终可用」**：数据目录最终是绝对路径、已经被创建、连接可用。换工作目录也就换库这条约定，靠的是「目录真的建好了 + 连接真的能开」这个结果，而不是靠读取配置本身。

**② 不能证明「配置没被改坏」**：`DataDirectoryInitializer` 是 `EnvironmentPostProcessor`，它在 Spring 读到 `change.data-dir` **之前**就把规范化结果回写进了属性源，所以 `env.getProperty("change.data-dir")` 拿到的永远是回写值——哪怕 `application.yml` 的默认值被改成 `./target/data`，`isAbsolute()` 也照样为真。这条断言在结构上不可能是红的（实测：把配置改成 `./target/relative-probe`，套件依然全绿）。

要真正防住「默认值被改回相对路径」，只能读**未经回写的原始配置**，也就是 `DataDirectoryInitializerTest.published_default_data_dir_is_not_a_working_directory_relative_path()`。

如果因为 Redis 连不上而失败，说明降级还没做——**先继续，Task 11 会解决它**；但记下这个失败，Task 11 结束时它必须变绿。

- [x] **Step 6: 提交**

```bash
git add backend/pom.xml backend/src backend/src/test/resources
git commit -m "chore: 后端项目骨架（Spring Boot 3.5.16 + JDK 25 + SQLite + Flyway）"
```

---

## Task 2: 接通 SQLite 与 Flyway

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__baseline.sql`
- Test: `backend/src/test/java/com/change/MigrationTest.java`

- [x] **Step 1: 写基线迁移**

```sql
-- V1: 基线。只建一张探针表，用来验证 Flyway 真的跑起来了。
CREATE TABLE IF NOT EXISTS schema_probe (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    note       TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO schema_probe (note, created_at)
VALUES ('baseline', '2026-10-14T00:00:00');
```

- [x] **Step 2: 写迁移测试**

```java
package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void baseline_migration_created_probe_table() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_probe WHERE note = 'baseline'", Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void flyway_history_has_v1() {
        String desc = jdbc.queryForObject(
                "SELECT description FROM flyway_schema_history WHERE version = '1'", String.class);
        assertThat(desc).isEqualTo("baseline");
    }
}
```

- [x] **Step 3: 跑测试**

Run: `cd backend && mvn -B test -Dtest=MigrationTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [x] **Step 4: 提交**

```bash
git add backend/src/main/resources/db/migration/V1__baseline.sql backend/src/test/java/com/change/MigrationTest.java
git commit -m "feat: 接通 SQLite 与 Flyway，加基线迁移与验证测试"
```

---

## Task 3: 十五张表的迁移

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__training.sql`
- Create: `backend/src/main/resources/db/migration/V3__nutrition.sql`
- Create: `backend/src/main/resources/db/migration/V4__auxiliary.sql`
- Test: `backend/src/test/java/com/change/SchemaTest.java`

### 进入 Task 3 前必须遵守的约定

> 以下五条来自 Task 2 的代码质量审查，**在动手写 `V2__training.sql` 之前先读完**。
> 第 1 条是审查实测；第 2、3 条在本轮复核里重新实测过一遍（结论与原审查一致）。
> 它们不是风格偏好，每一条都对应一次会真实发生的故障。

1. **任何迁移脚本里不出现 `PRAGMA`** —— 一律走 JDBC URL 参数（`application.yml` 的 `url`）。
   实测：让 Flyway 跑一份含 `PRAGMA journal_mode=WAL;` 的迁移，SQLite 直接拒绝，报
   `cannot change into wal mode from within a transaction` —— Flyway 默认把迁移包在事务里。
   加 `-- flyway:executeInTransaction=false` 也不是绕过办法（实测仍失败）。
   所以想开 `journal_mode=WAL` 只能写在 URL 上：**迁移脚本这条路无论怎么走都是错的**。

2. **迁移里不加 `IF NOT EXISTS`**（唯一例外是 V1 这个已发布的历史遗留，且它不许再改）。
   `flyway_schema_history` 已经保证「一个版本只跑一次」，`IF NOT EXISTS` 换不来任何保护，
   却制造了「表存在 ≠ 本迁移建了它」这个模糊地带——测试断言正是因此失效的。
   实测：把 V1 的内容清空、只留注释，Flyway 照样报 `Successfully applied 1 migration` 并记下
   `success=1`。此时旧断言（数一行数据）之所以变红，只是因为查一张不存在的表抛了
   `no such table: schema_probe`——**是碰巧撞红的，不是断言发现了「这次迁移什么都没建」**；
   而如果 V1 只是把表建错了（漏 `NOT NULL`、丢了 `AUTOINCREMENT`），旧断言会全绿。
   对**唯一索引**尤其危险：被静默跳过不会有任何报错，只会留下悄悄变慢、或悄悄允许重复的查询。

3. **`V2__training.sql` 的第一行加 `DROP TABLE schema_probe;`**。探针表是 V1 的历史遗留，
   没有业务含义，不该永久留在每个用户的库里。同时把 `SchemaTest.all_fifteen_tables_exist`
   里 `name <> 'schema_probe'` 那个特例一并删掉（本计划 Step 4 的代码已同步改好）。
   **绝不要改 `V1__baseline.sql`**：checksum 一变，所有已经跑过 V1 的库启动即抛
   `FlywayValidateException: Migrations have failed validation / Migration checksum mismatch
   for migration version 1`（实测：仅仅给 V1 加一行注释，`MigrationTest` 三条就全部报错、
   上下文根本起不来）。这就是「已发布的迁移不可变」这条纪律的由来——所以
   `DROP TABLE schema_probe` 放在 V2 的第一行，而不是回头去删 V1 里那两行。

4. **备份 / 导出文档（Task 9 起）必须写明**：应用运行时复制数据库要连 `-wal` / `-shm`
   一起拷；关掉应用再拷则只需 `change.db`。开了 WAL 之后，最新提交可能还躺在 `-wal` 里，
   只拷 `.db` 会得到一个「少了最后几次训练记录」的库。
   （实测：应用正常关闭后 `-wal` / `-shm` 已被 checkpoint 并删除，数据目录里只剩 `change.db`；
   但崩溃或强杀之后它们会留下来，所以「关掉再拷」必须是**正常退出**，不能是「进程没了就算」。）

5. **硬编码时间戳要带注释**说明「本地时间，无时区后缀，与全局约定一致」，否则下一个人会
   以为是 UTC，或者以为是笔误。V1 里的 `'2026-10-14T00:00:00'` 正是这种时间戳——
   **但它已发布，不许为了补注释去改它**（理由见约定 3）；这条从 V2 起生效。

**写 V2 / V3 / V4 时该照抄 V1 的：**

- 文件头注释写「**为什么有这份文件**」，而不是复述下面的 SQL；
- 列一律显式 `NOT NULL`（SQLite 默认「可为空」，漏写就是一个静默的可空列）；
- 主键用 `INTEGER PRIMARY KEY AUTOINCREMENT`；
- 时间戳用 `TEXT` + ISO-8601（SQLite 没有原生日期类型）；
- 一份迁移只承担一个域（训练 / 饮食 / 辅助），别把三个域的改动塞进同一个版本号。

**不该照抄的：**

- `CREATE TABLE IF NOT EXISTS` —— 理由见上面第 2 条。

### 进入 Task 4 前必须验证：8 种破坏都要能让测试变红

> 以下内容来自 Task 3 的**代码质量审查**。审查者在当时的实现上做了 8 处破坏，
> 套件**依然 12/12 全绿**；九条修复做完之后，每一种破坏都必须让某条测试变红。
> **进入 Task 4 之前请把这 8 条逐一做一遍并确认变红**（每条都要先删 `target/`
> 再跑 `mvn -B test`：`src/main/resources` 下的改动可能没被复制进 `target/classes`，
> 不删就会读到上一轮的旧脚本）。这一步不能省：它是「这一版 schema 真的有守卫」的
> 唯一证据，而迁移一旦发布就不可再改。

| # | 破坏 | 当时实测后果 | 现在由谁拦 |
|---|---|---|---|
| 1 | `muscle_group.name_zh` 去掉 `NOT NULL` | NULL 中文名可入库 | `SchemaTest.not_null_snapshot_is_exact_for_all_fifteen_tables` + `constraint_snapshot_key_ddl_clauses` |
| 2 | `exercise` 多一列 | 无人察觉 | `SchemaTest.column_snapshot_is_exact_for_all_fifteen_tables` |
| 3 | 删掉部分唯一索引 `ux_exercise_source` | 同名 `source_id` 写入 4 行 | `SchemaTest.index_snapshot_names_and_uniqueness` + `constraint_snapshot_key_ddl_clauses`（`WHERE source_id IS NOT NULL`） |
| 4 | 删掉 `workout_set.set_type` 的 CHECK | `set_type='total_nonsense'` 入库 | `SchemaTest.constraint_snapshot_key_ddl_clauses` |
| 5 | 删掉 `session_exercise.session_id` 的外键 | 级联删除失效 | `SchemaTest.constraint_snapshot_key_ddl_clauses` |
| 6 | `food.kcal_per100` 去掉 `NOT NULL` 与默认值 | 营养值可空 | `SchemaTest.not_null_snapshot_...` + `constraint_snapshot_key_ddl_clauses`（`NOT NULL DEFAULT 0`） |
| 7 | 删掉 `chat_message.session_id` 的外键 | 级联删除失效 | `SchemaTest.constraint_snapshot_key_ddl_clauses` |
| 8 | 删掉 `change_log.origin`/`action` 的 CHECK | `origin='martian'` 入库 | `SchemaTest.constraint_snapshot_key_ddl_clauses` |

**结论：schema 这一层在此之前没有任何守卫。** Flyway 的 checksum 只在「已经跑过该版本的
库」上报警，本地删掉 `target` 之后每次都是全新的库，checksum 校验根本不会被触发——
所以 `MigrationTest` 里另加了一条 `published_migrations_are_byte_frozen`，
把 V1–V4 的 checksum 钉死在测试里。

### 本轮对 schema 的九条修复（已落地）

1. **重排 V3/V4，让 `meal.photo_id` 能加外键**：V3 建 `photo` + `food` + `food_portion`
   （照片被餐引用，必须在前），V4 建 `meal` + `meal_item` + `body_metric` + `chat_session`
   + `chat_message` + `change_log`。`photo_id INTEGER REFERENCES photo(id) ON DELETE SET NULL`
   ——照片是餐的可选元数据：用 `SET NULL` 而不是拒绝，是因为删一张照片不该被一顿饭挡住；
   用 `SET NULL` 而不是 CASCADE，是因为删照片绝不能连带删掉用餐记录。
2. **四个纯日期列加形状约束**：`workout_session.session_date`、`meal.meal_date`、
   `body_metric.measured_on`、`photo.taken_on` 一律
   `CHECK (<列> GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')`。
   不这么做的话，一旦有代码写进完整时间戳（DTO 用 `LocalDateTime`、前端传 `toISOString()`），
   `WHERE session_date = '2026-10-14'` 对 `'2026-10-14T03:22:11'` **返回 0 行**（等值查询静默失效）；
   改用 `date(session_date)` 归一化则用不上 `ix_session_date`，索引白建。
   `started_at` / `ended_at` / `eaten_at` / `created_at` / `changed_at` 是**真时间戳**，
   刻意不加这个约束。
3. **`change_log` 补 `CHECK (action <> 'update' OR before_json IS NOT NULL)`**：
   「一键回退」是第一阶段的核心安全机制，没有 before 快照就无从恢复，而 UI 只看 `action`。
4. **`workout_set` 补数值合法性 CHECK**（`weight`/`target_weight`/`reps` ≥ 0、
   `duration_seconds` > 0）。**刻意不给这三列加 `NOT NULL`**：三空的行表达「这一组还没做/没记」，
   训练中的增量保存需要它。「`completed=1` 却三空」这类跨列语义由应用层保证。
5. **索引命名统一为 `ix_`/`ux_` + 表名全称 + 列名**，规则写进 V2 文件头。改名前：
   `ix_sessex_session`、`ix_set_sessex`、`ix_exercise_muscle_muscle`、`ix_chat_msg_session`、
   `ix_changelog_target`、`ix_changelog_time`、`ix_metric_type_date`。
6. **`set_index` 语义冻结 + 唯一索引**：每个子组各占一个 `set_index`，用 `set_type` 区分性质
   （递减组的三段是 `set_index` 0/1/2 + `set_type='dropset'`，不是三段共用同一个号）。
   加 `ux_workout_set_session_exercise_index` 与 `ux_session_exercise_session_order`。
7. **`exercise.source_id` 加命名空间约定**（形如 `'free-exercise-db:barbell-bench-press'`；
   **空串一律视为无效，必须写 NULL**——空串不算 NULL，导入器写 `''` 会让第二次导入整批失败），
   并加 `ux_exercise_name_en`。同名动作事后合并要写迁移 + 重挂 `session_exercise.exercise_id`
   + 重算所有衍生指标，所以**只能现在定**。
   **`food.name_zh` 刻意不加唯一约束**：中式食物同名不同做法的条目真实存在，
   去重与报告由导入器负责。
8. **修掉 `food.source` 注释里的错误枚举**：原来写的是 `'free-exercise-db'`——那是动作数据集，
   不是食物库（从 V2 复制粘贴留下的错误）。改成 `'manual' | 'cn-food-table' | 'agent' | 'takeout'`
   并补上 CHECK。
9. **补真正的 schema 守卫**：`SchemaTest` 加列级 / NOT NULL / 索引级 / 约束级四层结构快照，
   `MigrationTest` 加 checksum 冻结与「内存库执行 V1」的列级断言。

---

- [x] **Step 1: 写训练域迁移 V2**

> 实际文件第一行是 `DROP TABLE schema_probe;`（下面代码块的头部注释解释了理由），
> 剪掉了它后面的内容，这里照抄时**不要漏掉那一行**。

```sql
-- V2: 训练域 6 张表。
--
-- 索引命名：唯一索引 ux_<表名全称>_<列>；普通索引 ix_<表名全称>_<列>；表名不缩写。
--
-- 为什么这条规则要写死在文件头：SQLite 给索引另起一个名字再建一次**不会报错**，
-- 只会静默多出一个功能重复的索引（写变慢、占用翻倍，而没有任何提示）。表名缩写在
-- 这里尤其危险——`ix_sessex_session` 对下一个人来说不显然等于 `session_exercise`，
-- 于是他很可能再建一个 `ix_session_exercise_session`，而两者并存时谁也不会告诉他。
-- 一条可机械照抄的规则比一句「请保持一致」有用得多。
--
-- 为什么有这份文件：「一次训练 = 多动作 × 多组」是这个应用的核心事实，一个动作的
-- 同一组里既要知道「我们打算做多少」，也要知道「实际做了多少」。把这两件事放进不同的表
-- 会让「今天练得比计划重还是轻」变成一次跨表对齐，因此这里刻意建成三层嵌套
-- （workout_session → session_exercise → workout_set），并让目标值与实际值同处一行。
-- 组与组之间的差异用 set_type 枚举表达，而不是靠「第几组」的序号去猜。
--
-- 第一行先清掉 V1 的探针表：它是基线迁移的历史遗留，没有业务含义，不该永久留在
-- 每个用户的库里。删在这里而不是回头去改 V1——V1 的 checksum 已经发布，一动所有
-- 跑过它的库启动即抛 FlywayValidateException。
DROP TABLE schema_probe;

CREATE TABLE muscle_group (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name_zh         TEXT    NOT NULL,
    name_en         TEXT    NOT NULL,
    body_part       TEXT    NOT NULL,      -- 逗号分隔的多值（如 "left,right"），需要时再拆表
    region_ids      TEXT,                  -- 人体图区域 id，逗号分隔
    weekly_sets_min INTEGER,
    weekly_sets_max INTEGER
);
CREATE UNIQUE INDEX ux_muscle_group_en ON muscle_group(name_en);

CREATE TABLE exercise (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name_en       TEXT    NOT NULL,
    name_zh       TEXT,
    equipment     TEXT,
    is_compound   INTEGER NOT NULL DEFAULT 0,
    default_unit  TEXT    NOT NULL DEFAULT 'kg',
    -- source_id：外部数据集的主键，必须带命名空间前缀，形如
    -- 'free-exercise-db:barbell-bench-press'。手工录入的动作必须写 NULL，
    -- **空串不算 NULL**——`source_id` 上的部分唯一索引只看 NULL：
    -- 写 '' 的动作第二次导入就撞 UNIQUE constraint failed，整批回滚；
    -- 而不带前缀的裸 id 会在接入第二个数据集时静默冲突（两个数据集都叫 'curl'）。
    source_id     TEXT,
    note          TEXT,
    archived      INTEGER NOT NULL DEFAULT 0
);
-- 部分唯一索引：只约束「有外部来源」的动作，手工动作（source_id IS NULL）可以无限重复。
CREATE UNIQUE INDEX ux_exercise_source ON exercise(source_id) WHERE source_id IS NOT NULL;
-- 同名动作必须挡在库外，而且**只能现在挡**：一旦库里出现两行同名，唯一索引就建不上，
-- 事后合并要写迁移 + 重挂 session_exercise.exercise_id + 重算所有衍生指标。而在此之前，
-- 重复行会安静地毁掉每一个统计：进步曲线分裂成两条线、周容量翻倍、PR 被拆成两个。
CREATE UNIQUE INDEX ux_exercise_name_en ON exercise(name_en);
CREATE INDEX ix_exercise_name_zh ON exercise(name_zh);

CREATE TABLE exercise_muscle (
    exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,
    muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE,
    role            TEXT    NOT NULL CHECK (role IN ('primary', 'secondary')),
    PRIMARY KEY (exercise_id, muscle_group_id)
);
CREATE INDEX ix_exercise_muscle_muscle_group ON exercise_muscle(muscle_group_id);

CREATE TABLE workout_session (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 纯日期，形状锁死为 YYYY-MM-DD（另见 meal.meal_date / body_metric.measured_on /
    -- photo.taken_on 的同款 CHECK）。这一条拦的不是脏数据，而是**静默失效**：
    -- 一旦有代码写进完整时间戳（DTO 用 LocalDateTime、前端传了 toISOString()），
    -- `WHERE session_date = '2026-10-14'` 对 '2026-10-14T03:22:11' 返回 0 行——
    -- 查询没报错，只是永远查不到；改用 date(session_date) 归一化则用不上 ix_session_date，
    -- 索引白建；按日期分组的统计还会把同一天拆成两行。
    session_date           TEXT    NOT NULL CHECK (session_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    -- started_at / ended_at 是**真时间戳**，本来就该含时间，不要加日期形状 CHECK。
    started_at             TEXT,
    ended_at               TEXT,
    title                  TEXT,
    perceived_state        TEXT,
    note                   TEXT,
    source_routine_day_id  INTEGER             -- 为将来「计划驱动」预留，第一阶段不写
);
CREATE INDEX ix_session_date ON workout_session(session_date);

CREATE TABLE session_exercise (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    INTEGER NOT NULL REFERENCES workout_session(id) ON DELETE CASCADE,
    exercise_id   INTEGER NOT NULL REFERENCES exercise(id),
    order_index   INTEGER NOT NULL,
    note          TEXT,
    superset_group TEXT                        -- 同值即超级组；NULL 表示不参与超级组
);
CREATE INDEX ix_session_exercise_session ON session_exercise(session_id);
-- order_index 是「这个动作在本次训练里排第几」，同一次训练里两行同序没有意义。
CREATE UNIQUE INDEX ux_session_exercise_session_order
    ON session_exercise(session_id, order_index);

CREATE TABLE workout_set (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    session_exercise_id INTEGER NOT NULL REFERENCES session_exercise(id) ON DELETE CASCADE,
    -- set_index 的语义（冻结，不要再解释成别的意思）：
    -- **每个子组各占一个 set_index，用 set_type 区分性质。**
    -- 递减组的三段是 set_index 0/1/2 + set_type 'dropset'，巨集组同理，
    -- 而不是三段共用同一个 set_index。理由：set_index 是「带出上次重量」的对齐键，
    -- 两行同号会让对齐随机取到其中一行——而取错的那次不会有任何报错。
    set_index           INTEGER NOT NULL,
    set_type            TEXT    NOT NULL DEFAULT 'normal'
                        CHECK (set_type IN ('normal','warmup','dropset','myo','partial',
                                            'forced','tut','iso','jump')),
    -- weight / repetitions / duration_seconds 刻意**可为空**：三空的行是一个真实状态
    -- （「这一组还没做/没记」），训练中界面靠增量保存逐字段补上，加 NOT NULL 会逼客户端编造数字。
    -- 空值放行、有值必须合法，所以约束写成 `IS NULL OR ...`。
    weight              REAL,
    repetitions         INTEGER,
    duration_seconds    INTEGER,               -- 计时动作（平板支撑等），与 repetitions 二选一
    per_side            INTEGER NOT NULL DEFAULT 0,
    rir                 REAL,
    rest_seconds        INTEGER,
    completed           INTEGER NOT NULL DEFAULT 0,
    target_weight       REAL,
    target_reps         INTEGER,
    -- 这里只保证单列数值合法。「completed=1 却三空」「weight 与 duration_seconds 同时有值」
    -- 这类**跨列语义**由应用层保证，数据库层不拦。
    CHECK (weight IS NULL OR weight >= 0),
    CHECK (repetitions IS NULL OR repetitions >= 0),
    CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    CHECK (target_weight IS NULL OR target_weight >= 0),
    CHECK (target_reps IS NULL OR target_reps >= 0)
);
CREATE INDEX ix_workout_set_session_exercise ON workout_set(session_exercise_id);
-- 同一个动作下 set_index 必须唯一——否则上面那段语义冻结只是一句注释。
CREATE UNIQUE INDEX ux_workout_set_session_exercise_index
    ON workout_set(session_exercise_id, set_index);
```

- [x] **Step 2: 写食物库与照片迁移 V3**

照片从原来的 V4 提到这里：`meal.photo_id` 要指向 `photo(id)`，外键要求被引用的表先存在。

```sql
-- V3: 食物库与照片 3 张表（food / food_portion / photo）。
--
-- 为什么有这份文件：一条饮食记录要同时回答「吃了什么」和「吃了多少」，而这两个问题的
-- 分辨率完全不同——营养值对某个食物是固定属性，份量则每次都不一样。所以营养值只在
-- food 表里按每 100 克存一份，meal_item 只记份量与换算克数，热量由读取时现算。
--
-- 为什么 photo 在这份迁移而不是 V4：meal.photo_id 要指向 photo(id)，外键要求被引用
-- 的表先存在。所以按依赖顺序重排——本文件建食物库与照片，V4 建餐记录（依赖 photo）
-- 与其余辅助表。一份迁移仍然只承担一个域，只是把「照片」归到了它被引用的那一侧。
--
-- 铁律：营养一律按每 100 克存；条目只存份量与换算克数，绝不存绝对热量。

CREATE TABLE food (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    -- name_zh 刻意**不加唯一约束**：中式食物里「同名不同做法」的条目真实存在
    -- （同一个菜名对应完全不同的配料与热量）。同名条目由导入器负责去重与报告，
    -- 不在数据库层强制唯一——在这里唯一化会逼导入器丢掉真实存在的条目。
    name_zh      TEXT    NOT NULL,
    name_en      TEXT,
    -- 食物来源枚举。注意这里**没有** free-exercise-db：那是动作数据集，不是食物库
    -- （V2 的 exercise.source_id 才是它的命名空间）。这条注释曾经误抄了动作那边的枚举。
    source       TEXT    NOT NULL
                 CHECK (source IN ('manual','cn-food-table','agent','takeout')),
    kcal_per100  REAL    NOT NULL DEFAULT 0,
    protein_per100 REAL  NOT NULL DEFAULT 0,
    carb_per100  REAL    NOT NULL DEFAULT 0,
    fat_per100   REAL    NOT NULL DEFAULT 0,
    fiber_per100 REAL    NOT NULL DEFAULT 0,
    sodium_per100 REAL   NOT NULL DEFAULT 0
);
CREATE INDEX ix_food_name_zh ON food(name_zh);

CREATE TABLE food_portion (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    food_id   INTEGER NOT NULL REFERENCES food(id) ON DELETE CASCADE,
    unit_name TEXT    NOT NULL,        -- 碗 / 个 / 勺 / 份
    grams     REAL    NOT NULL CHECK (grams > 0)
);
CREATE UNIQUE INDEX ux_food_portion ON food_portion(food_id, unit_name);

-- 照片先建在这里（被 meal.photo_id 引用），本表没有任何指向业务表的引用。
CREATE TABLE photo (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 纯日期，形状锁死为 YYYY-MM-DD。理由见 V2 里 workout_session.session_date 上方的长注释。
    taken_on   TEXT    NOT NULL CHECK (taken_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    rel_path   TEXT    NOT NULL,       -- 只存相对路径，根目录由配置决定
    sha256     TEXT    NOT NULL,
    width      INTEGER,
    height     INTEGER,
    kind       TEXT    NOT NULL CHECK (kind IN ('food','body','other')),
    note       TEXT
);
CREATE UNIQUE INDEX ux_photo_sha ON photo(sha256);
CREATE INDEX ix_photo_kind_date ON photo(kind, taken_on);
```

- [x] **Step 3: 写餐记录与其余辅助迁移 V4**

`meal` 从原来的 V3 移到这里，与它依赖的 `photo` 分开在两份迁移里；
`body_metric` / `chat_session` / `chat_message` / `change_log` 位置不变。

```sql
-- V4: 餐记录与其余辅助表 6 张表（meal / meal_item / body_metric / chat_session /
--     chat_message / change_log）。
--
-- 为什么有这份文件：餐记录、身体指标、对话记录与变更日志都不是训练本身的数据，
-- 但它们各自被别的域引用（meal.photo_id 指向 V3 的 photo，撤销一次训练改动时要回放
-- change_log），所以必须与业务表同库、同事务，而不是另一个存储。这些表彼此的耦合
-- 只到这里为止，单独成一份迁移是为了让日后的改动不会牵动 V2/V3 的 checksum。
--
-- 为什么 meal 在 V4 而不是 V3：它的 photo_id 外键指向 photo(id)，而被引用的表必须先存在。
-- 于是按依赖顺序重排：V3 建食物库与照片，本文件建依赖 photo 的餐记录与其余辅助表。

CREATE TABLE meal (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 纯日期，形状锁死为 YYYY-MM-DD。理由见 V2 里 workout_session.session_date 上方的长注释。
    meal_date  TEXT    NOT NULL CHECK (meal_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    slot       TEXT    NOT NULL CHECK (slot IN ('breakfast','lunch','dinner','snack')),
    -- eaten_at 是**真时间戳**，本来就该含时间，不要加日期形状 CHECK。
    eaten_at   TEXT,
    note       TEXT,
    -- ON DELETE SET NULL（而不是默认的拒绝、也不是 CASCADE）是有意的取舍：
    -- 照片是餐的**可选元数据**。删一张照片不该被一顿饭的存在挡住（拒绝），
    -- 删照片更绝不能连带删掉用餐记录（CASCADE 会把「今天吃了什么」删没）；
    -- 所以照片没了就把这一格清空，餐本身留下。
    photo_id   INTEGER REFERENCES photo(id) ON DELETE SET NULL
);
CREATE INDEX ix_meal_date ON meal(meal_date);

CREATE TABLE meal_item (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    meal_id      INTEGER NOT NULL REFERENCES meal(id) ON DELETE CASCADE,
    food_id      INTEGER NOT NULL REFERENCES food(id),
    order_index  INTEGER NOT NULL,
    amount       REAL    NOT NULL CHECK (amount > 0),   -- 份量数值
    unit_name    TEXT,                                  -- 单位名；NULL 表示 amount 已是克
    grams        REAL    NOT NULL CHECK (grams > 0),    -- 换算后的克数，写入时算好
    origin       TEXT    NOT NULL DEFAULT 'manual'
                 CHECK (origin IN ('manual','photo','agent','takeout'))
    -- 注意：这里没有 kcal 字段，故意的。
);
CREATE INDEX ix_meal_item_meal ON meal_item(meal_id);

CREATE TABLE body_metric (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 纯日期，形状锁死为 YYYY-MM-DD。理由见 V2 里 workout_session.session_date 上方的长注释。
    measured_on TEXT    NOT NULL CHECK (measured_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    metric_type TEXT    NOT NULL,      -- weight / body_fat / waist / arm / ...
    value       REAL    NOT NULL,
    unit        TEXT    NOT NULL,
    note        TEXT
);
CREATE INDEX ix_body_metric_type_date ON body_metric(metric_type, measured_on);

CREATE TABLE chat_session (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT,
    created_at TEXT    NOT NULL
);

CREATE TABLE chat_message (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    INTEGER NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role          TEXT    NOT NULL CHECK (role IN ('user','assistant','tool')),
    content       TEXT,
    tool_calls    TEXT,                -- JSON 数组，记录本次调用了哪些工具
    created_at    TEXT    NOT NULL
);
CREATE INDEX ix_chat_message_session ON chat_message(session_id);

CREATE TABLE change_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    changed_at  TEXT    NOT NULL,
    origin      TEXT    NOT NULL CHECK (origin IN ('user','agent')),
    action      TEXT    NOT NULL CHECK (action IN ('insert','update')),
    target_table TEXT   NOT NULL,
    target_id   INTEGER NOT NULL,
    before_json TEXT,                  -- insert 时为 NULL
    after_json  TEXT    NOT NULL,
    chat_session_id INTEGER,
    undone      INTEGER NOT NULL DEFAULT 0,
    -- 「一键回退」是第一阶段的核心安全机制，而它唯一的依据就是 before_json：
    -- action='update' 却没有 before 快照，等于这一行永远回不去，可 UI 只看 action
    -- 就会照常画出一个「撤销」按钮——按下去才发现无事可撤。设计文档明确不允许删除，
    -- 所以 update 必然存在变更前的状态，这条约束是纯收益。
    CHECK (action <> 'update' OR before_json IS NOT NULL)
);
CREATE INDEX ix_change_log_target ON change_log(target_table, target_id);
CREATE INDEX ix_change_log_time ON change_log(changed_at);
```

- [x] **Step 4: 写结构测试**

四层快照，逐张表收口：**列清单 + NOT NULL 清单**（拦多一列、少一列、NOT NULL 被拿掉）、
**业务索引清单**（名字 + 唯一性 + 是否部分索引，拦索引被删 / 改名 / 唯一性被去掉）、
**关键 DDL 片段**（拦 CHECK / `REFERENCES ... ON DELETE ...` / DEFAULT 被删）。
约束那层断言的是 DDL 原文而不是「写入被拒」，因为只有前者能指出**哪一条**约束没了；
「写入真的被拒」由 Task 3 的验收逐条实测覆盖。

代码块整体缩进了 4 格（迁移到计划文档时的排版需要），贴回文件时请去掉这层缩进。

> **实施时对本块的修正（已落地）**：原写的 `PRAGMA table_info(meal_item)` 是写不出来的——
> 它是「一行六列」的表函数，`queryForList(..., String.class)` 会对它抛
> `IncorrectResultSetColumnCountException`（`org.springframework.jdbc` 下的类，与
> `IncorrectResultSizeDataAccessException` 是**兄弟**关系，`instanceof` 判不出来），
> 于是 `doesNotContain` 那一行**永远执行不到**。统一改走：
>
> ```java
> SELECT name FROM pragma_table_info('<表>')
> ```
>
> `PRAGMA index_list` 同理，要写成表函数形式 `pragma_index_list('<表>')`；
> 另外**不要**写 `pragma_index_list_all`——实测该名字不存在（SQLite 3.53.1 报
> `no such table: pragma_index_list_all`）。
>
> **另一个必须记住的坑**：`sqlite_master.sql` 存的是**原样文本**（列是按列对齐的，
> `name_zh         TEXT    NOT NULL`），拿对齐后的字面量去 `contains` 必然失败。
> 所以 DDL 片段断言一律先过 `collapseWhitespace(...)`，断言里写单空格的自然形式。

```java
    package com.change;

    import org.junit.jupiter.api.Test;
    import org.springframework.beans.factory.annotation.Autowired;
    import org.springframework.boot.test.context.SpringBootTest;
    import org.springframework.jdbc.core.JdbcTemplate;

    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;
    import java.util.Set;

    import static org.assertj.core.api.Assertions.assertThat;

    /**
     * 十五张业务表的结构快照。
     *
     * 为什么用一张「期望清单」而不是逐个断言表存在：逐个断言只能发现「少建了」，
     * 发现不了「多建了」——多出来的表（比如 V1 的探针表没被清掉）会让下一个人以为
     * 它是模型的一部分。containsExactlyInAnyOrderElementsOf 两侧都收口。
     *
     * 为什么这一轮要补列级 / 索引级 / 约束级三层快照：Task 3 的代码质量审查做了 8 处
     * 破坏——去掉 NOT NULL、多一列、删掉部分唯一索引、删掉 CHECK、删掉外键——套件**依然
     * 12/12 全绿**。当时只有 4 条断言，且都只问「某张表某几列在不在」，对约束、
     * 索引、可空性完全不设防。而迁移一旦发布就不可再改（改 V1/V2/V3/V4 的任何一个字节都会
     * 让跑过它的库抛 FlywayValidateException），所以现在这一版 schema 的守卫只能建在测试里。
     *
     * 三层各拦一类破坏：
     * - 列快照（列清单 + NOT NULL 清单）拦「多一列 / 少一列 / NOT NULL 被拿掉」；
     * - 索引快照（名字 + 唯一性 + 是否部分索引）拦「索引被删 / 被改名 / 唯一性被去掉」——
     *   SQLite 对「另起一个名字再建一次」不报错，只会静默留下两个功能重复的索引；
     * - 约束快照（sqlite_master.sql 里的关键片段）拦「CHECK / REFERENCES ... ON DELETE
     *   CASCADE / DEFAULT 被删」——这些在 pragma 里问不出来（pragma 只报外键的目标表与动作，
     *   报不出 CHECK 的内容），只有 DDL 原文里有。
     *
     * 注意约束快照断言的是 DDL 片段，不是「写入真的被拒」。两者都值得有，但只有前者能
     * 精确指出「哪一条约束没了」；后者已经在验收里逐条实测过。
     */
    @SpringBootTest
    class SchemaTest {

        @Autowired
        JdbcTemplate jdbc;

        private static final Set<String> EXPECTED = Set.of(
                "exercise", "workout_session", "session_exercise", "workout_set",
                "muscle_group", "exercise_muscle",
                "food", "food_portion", "meal", "meal_item",
                "photo", "body_metric", "chat_session", "chat_message", "change_log");

        @Test
        void all_fifteen_tables_exist() {
            // sqlite_% 排除的是 SQLite 自己维护的表（AUTOINCREMENT 会建 sqlite_sequence）；
            // flyway_schema_history 是迁移框架的历史表，不属于业务模型。
            // 这里刻意不排除 schema_probe：它若还在，这条断言就该红。
            //
            // 将来新增**内部**表（FTS5 全文检索的影子表如 *_data / *_idx / *_content /
            // *_docsize / *_config、sqlite_stat1 等）时，必须同步更新本断言——否则这条
            // 「多建了也要红」的收口会先把真正的业务表变化挡住，让人误以为只是影子表噪音。
            List<String> names = jdbc.queryForList(
                    "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'", String.class);
            assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED);
        }

        @Test
        void probe_table_was_dropped_by_v2() {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='schema_probe'",
                    Integer.class);
            assertThat(n)
                    .as("V2 的第一行应当 DROP 掉 V1 的探针表，让它不再留在用户的库里")
                    .isZero();
        }

        @Test
        void meal_item_has_no_stored_calorie_column() {
            // 这条是防回归：一旦有人「顺手」加了 kcal 列，测试立刻失败。
            // 走 pragma_table_info 而不是 PRAGMA table_info：后者是「一行多列」的表函数，
            // queryForList(..., String.class) 会对它抛 IncorrectResultSetColumnCountException
            // （org.springframework.jdbc 下的类，与 IncorrectResultSizeDataAccessException 是
            // 兄弟关系，instanceof 判不出来），断言就永远走不到 doesNotContain 那一行。
            assertThat(columns("meal_item")).doesNotContain("kcal", "calories", "energy");
        }

        @Test
        void workout_set_supports_timed_and_per_side() {
            // 用 containsExactlyInAnyOrder 而不是 contains：contains 对「多出一列」恒绿，
            // 而「多出一列没人察觉」正是本轮审查实测发生的破坏之一。
            assertThat(columns("workout_set")).containsExactlyInAnyOrder(
                    "id", "session_exercise_id", "set_index", "set_type", "weight", "repetitions",
                    "duration_seconds", "per_side", "rir", "rest_seconds", "completed",
                    "target_weight", "target_reps");
        }

        // ------------------------------------------------------------------
        // 第一层：列级快照
        // ------------------------------------------------------------------

        /**
         * 列清单**逐表、按声明顺序**精确断言，外加每表的 NOT NULL 列清单。
         *
         * 顺序也算在内是有意的：pragma_table_info 按声明顺序返回，而列顺序是「一份迁移
         * 建出来什么」的一部分，插入到中间的新列值得被看见（它往往意味着有人在原地改
         * 一份已发布的迁移）。
         */
        @Test
        void column_snapshot_is_exact_for_all_fifteen_tables() {
            Map<String, List<String>> expectedColumns = new LinkedHashMap<>();
            expectedColumns.put("muscle_group", List.of(
                    "id", "name_zh", "name_en", "body_part", "region_ids",
                    "weekly_sets_min", "weekly_sets_max"));
            expectedColumns.put("exercise", List.of(
                    "id", "name_en", "name_zh", "equipment", "is_compound", "default_unit",
                    "source_id", "note", "archived"));
            expectedColumns.put("exercise_muscle", List.of(
                    "exercise_id", "muscle_group_id", "role"));
            expectedColumns.put("workout_session", List.of(
                    "id", "session_date", "started_at", "ended_at", "title", "perceived_state",
                    "note", "source_routine_day_id"));
            expectedColumns.put("session_exercise", List.of(
                    "id", "session_id", "exercise_id", "order_index", "note", "superset_group"));
            expectedColumns.put("workout_set", List.of(
                    "id", "session_exercise_id", "set_index", "set_type", "weight", "repetitions",
                    "duration_seconds", "per_side", "rir", "rest_seconds", "completed",
                    "target_weight", "target_reps"));
            expectedColumns.put("food", List.of(
                    "id", "name_zh", "name_en", "source", "kcal_per100", "protein_per100",
                    "carb_per100", "fat_per100", "fiber_per100", "sodium_per100"));
            expectedColumns.put("food_portion", List.of(
                    "id", "food_id", "unit_name", "grams"));
            expectedColumns.put("photo", List.of(
                    "id", "taken_on", "rel_path", "sha256", "width", "height", "kind", "note"));
            expectedColumns.put("meal", List.of(
                    "id", "meal_date", "slot", "eaten_at", "note", "photo_id"));
            expectedColumns.put("meal_item", List.of(
                    "id", "meal_id", "food_id", "order_index", "amount", "unit_name", "grams",
                    "origin"));
            expectedColumns.put("body_metric", List.of(
                    "id", "measured_on", "metric_type", "value", "unit", "note"));
            expectedColumns.put("chat_session", List.of(
                    "id", "title", "created_at"));
            expectedColumns.put("chat_message", List.of(
                    "id", "session_id", "role", "content", "tool_calls", "created_at"));
            expectedColumns.put("change_log", List.of(
                    "id", "changed_at", "origin", "action", "target_table", "target_id",
                    "before_json", "after_json", "chat_session_id", "undone"));

            // 双侧收口：EXPECTED 与这里的键集合必须一致，少写一张表也会红。
            assertThat(expectedColumns.keySet())
                    .as("列快照必须覆盖全部十五张表")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED);

            expectedColumns.forEach((table, columns) ->
                    assertThat(columns(table))
                            .as("表 %s 的列清单（多一列、少一列、改名、换序都会红）", table)
                            .containsExactlyElementsOf(columns));
        }

        /**
         * NOT NULL 清单。这是本轮审查第 1、6 条破坏的唯一守卫：去掉
         * `muscle_group.name_zh` 或 `food.kcal_per100` 的 NOT NULL 之后，列清单纹丝不动。
         */
        @Test
        void not_null_snapshot_is_exact_for_all_fifteen_tables() {
            Map<String, List<String>> expectedNotNull = new LinkedHashMap<>();
            expectedNotNull.put("muscle_group", List.of("name_zh", "name_en", "body_part"));
            expectedNotNull.put("exercise", List.of("name_en", "is_compound", "default_unit", "archived"));
            expectedNotNull.put("exercise_muscle", List.of("exercise_id", "muscle_group_id", "role"));
            expectedNotNull.put("workout_session", List.of("session_date"));
            expectedNotNull.put("session_exercise", List.of("session_id", "exercise_id", "order_index"));
            expectedNotNull.put("workout_set", List.of(
                    "session_exercise_id", "set_index", "set_type", "per_side", "completed"));
            expectedNotNull.put("food", List.of(
                    "name_zh", "source", "kcal_per100", "protein_per100", "carb_per100",
                    "fat_per100", "fiber_per100", "sodium_per100"));
            expectedNotNull.put("food_portion", List.of("food_id", "unit_name", "grams"));
            expectedNotNull.put("photo", List.of("taken_on", "rel_path", "sha256", "kind"));
            expectedNotNull.put("meal", List.of("meal_date", "slot"));
            expectedNotNull.put("meal_item", List.of(
                    "meal_id", "food_id", "order_index", "amount", "grams", "origin"));
            expectedNotNull.put("body_metric", List.of("measured_on", "metric_type", "value", "unit"));
            expectedNotNull.put("chat_session", List.of("created_at"));
            expectedNotNull.put("chat_message", List.of("session_id", "role", "created_at"));
            expectedNotNull.put("change_log", List.of(
                    "changed_at", "origin", "action", "target_table", "target_id", "after_json",
                    "undone"));

            assertThat(expectedNotNull.keySet())
                    .as("NOT NULL 快照必须覆盖全部十五张表")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED);

            expectedNotNull.forEach((table, columns) ->
                    assertThat(notNullColumns(table))
                            .as("表 %s 的 NOT NULL 列（漏写 NOT NULL 在 SQLite 里等于静默可空）", table)
                            .containsExactlyElementsOf(columns));
        }

        // ------------------------------------------------------------------
        // 第二层：索引级快照
        // ------------------------------------------------------------------

        /**
         * 业务索引的完整清单（名字 + 唯一性 + 是否部分索引）。sqlite_ 开头的自动索引
         * （UNIQUE 约束 / 复合主键产生的）不算业务索引，不进这张表。
         *
         * 命名规则（与 V2 文件头同一句）：唯一索引 `ux_<表名全称>_<列>`，普通索引
         * `ix_<表名全称>_<列>`，表名不缩写。这里把名字也钉死，是因为 SQLite 允许
         * 同名索引以外的新名字静默重建——两个功能重复的索引不会报错，只会悄悄变慢。
         */
        @Test
        void index_snapshot_names_and_uniqueness() {
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("ux_muscle_group_en", "UNIQUE");
            expected.put("ux_exercise_source", "UNIQUE PARTIAL");
            expected.put("ux_exercise_name_en", "UNIQUE");
            expected.put("ix_exercise_name_zh", "NOT UNIQUE");
            expected.put("ix_exercise_muscle_muscle_group", "NOT UNIQUE");
            expected.put("ix_session_date", "NOT UNIQUE");
            expected.put("ix_session_exercise_session", "NOT UNIQUE");
            expected.put("ux_session_exercise_session_order", "UNIQUE");
            expected.put("ix_workout_set_session_exercise", "NOT UNIQUE");
            expected.put("ux_workout_set_session_exercise_index", "UNIQUE");
            expected.put("ix_food_name_zh", "NOT UNIQUE");
            expected.put("ux_food_portion", "UNIQUE");
            expected.put("ux_photo_sha", "UNIQUE");
            expected.put("ix_photo_kind_date", "NOT UNIQUE");
            expected.put("ix_meal_date", "NOT UNIQUE");
            expected.put("ix_meal_item_meal", "NOT UNIQUE");
            expected.put("ix_body_metric_type_date", "NOT UNIQUE");
            expected.put("ix_chat_message_session", "NOT UNIQUE");
            expected.put("ix_change_log_target", "NOT UNIQUE");
            expected.put("ix_change_log_time", "NOT UNIQUE");

            assertThat(businessIndexes())
                    .as("业务索引清单（名字 + 唯一性 + 是否部分索引，多一个少一个都要红）")
                    .containsExactlyInAnyOrderEntriesOf(expected);
        }

        // ------------------------------------------------------------------
        // 第三层：约束级快照（DDL 原文片段）
        // ------------------------------------------------------------------

        /**
         * 每张表的 DDL 里必须出现的关键片段：CHECK / REFERENCES ... ON DELETE ... /
         * NOT NULL / DEFAULT。
         *
         * 为什么读 sqlite_master.sql 而不是 pragma：CHECK 的内容在 pragma 里根本问不出来
         * （PRAGMA table_info 只给非空标记与默认值），`REFERENCES` 的目标动作虽然能用
         * pragma_foreign_key_list 读到，但读不出「这一列上到底写没写约束」。DDL 原文是
         * 唯一能同时覆盖这几类的地方；对应地，断言必须写成**带尾随逗号或整行**的形式，
         * 否则 `REFERENCES photo(id)` 会把 `REFERENCES photo(id) ON DELETE SET NULL`
         * 也算通过——那正好丢掉我们要守的那个动作。
         */
        @Test
        void constraint_snapshot_key_ddl_clauses() {
            assertThat(ddlOf("muscle_group")).contains(
                    collapseWhitespace("name_zh         TEXT    NOT NULL"));

            // 部分唯一索引的定义体：只约束有外部来源的动作，手工动作可以重复。
            // 名字层面的检查在 index_snapshot 里，这里确认「部分」这个语义没被丢掉
            // （去掉 WHERE 就变成「手工动作也只能有一个」，那是另一种错）。
            assertThat(indexDdl("ux_exercise_source"))
                    .contains(collapseWhitespace("WHERE source_id IS NOT NULL"));

            // 四个纯日期列的 GLOB 形状约束。少一个，那一路的等值查询就会在某天静默失效。
            assertThat(ddlOf("workout_session")).contains(collapseWhitespace(
                    "CHECK (session_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
            assertThat(ddlOf("meal")).contains(collapseWhitespace(
                    "CHECK (meal_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
            assertThat(ddlOf("body_metric")).contains(collapseWhitespace(
                    "CHECK (measured_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
            assertThat(ddlOf("photo")).contains(collapseWhitespace(
                    "CHECK (taken_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));

            // 真时间戳列**不该**被套上日期形状约束——它们本来就含时间。
            assertThat(ddlOf("workout_session")).doesNotContain("CHECK (started_at");
            assertThat(ddlOf("workout_session")).doesNotContain("CHECK (ended_at");
            assertThat(ddlOf("meal")).doesNotContain("CHECK (eaten_at");
            assertThat(ddlOf("chat_message")).doesNotContain("CHECK (created_at");
            assertThat(ddlOf("change_log")).doesNotContain("CHECK (changed_at");

            // 枚举 CHECK
            assertThat(ddlOf("exercise_muscle")).contains(
                    collapseWhitespace("CHECK (role IN ('primary', 'secondary'))"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace(
                    "CHECK (set_type IN ('normal','warmup','dropset','myo','partial', "
                            + "'forced','tut','iso','jump'))"));
            assertThat(ddlOf("food")).contains(
                    collapseWhitespace("CHECK (source IN ('manual','cn-food-table','agent','takeout'))"));
            assertThat(ddlOf("meal")).contains(
                    collapseWhitespace("CHECK (slot IN ('breakfast','lunch','dinner','snack'))"));
            assertThat(ddlOf("meal_item")).contains(
                    collapseWhitespace("CHECK (origin IN ('manual','photo','agent','takeout'))"));
            assertThat(ddlOf("photo")).contains(
                    collapseWhitespace("CHECK (kind IN ('food','body','other'))"));
            assertThat(ddlOf("chat_message")).contains(
                    collapseWhitespace("CHECK (role IN ('user','assistant','tool'))"));
            assertThat(ddlOf("change_log")).contains(
                    collapseWhitespace("CHECK (origin IN ('user','agent'))"));
            assertThat(ddlOf("change_log")).contains(
                    collapseWhitespace("CHECK (action IN ('insert','update'))"));
            // 「一键回退」的守卫：action='update' 必须有 before 快照，否则这行永远回不去，
            // 而 UI 只看 action 就会画出一个按下去无事可撤的「撤销」按钮。
            assertThat(ddlOf("change_log")).contains(
                    collapseWhitespace("CHECK (action <> 'update' OR before_json IS NOT NULL)"));

            // workout_set 的数值合法性（刻意不加 NOT NULL：三空行是有意的状态）
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (weight IS NULL OR weight >= 0)"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (repetitions IS NULL OR repetitions >= 0)"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (duration_seconds IS NULL OR duration_seconds > 0)"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (target_weight IS NULL OR target_weight >= 0)"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (target_reps IS NULL OR target_reps >= 0)"));

            // 级联删除：少一个 REFERENCES ... ON DELETE CASCADE，删训练/删会话就会留下孤儿行，
            // 而且**不会有任何报错**。尾随逗号是必须的，防止基础外键形式蒙混通过。
            assertThat(ddlOf("exercise_muscle")).contains(collapseWhitespace(
                    "exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE"));
            assertThat(ddlOf("exercise_muscle")).contains(collapseWhitespace(
                    "muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE"));
            assertThat(ddlOf("session_exercise")).contains(collapseWhitespace(
                    "session_id    INTEGER NOT NULL REFERENCES workout_session(id) ON DELETE CASCADE"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace(
                    "session_exercise_id INTEGER NOT NULL REFERENCES session_exercise(id) ON DELETE CASCADE"));
            assertThat(ddlOf("food_portion")).contains(collapseWhitespace(
                    "food_id   INTEGER NOT NULL REFERENCES food(id) ON DELETE CASCADE"));
            assertThat(ddlOf("meal_item")).contains(collapseWhitespace(
                    "meal_id      INTEGER NOT NULL REFERENCES meal(id) ON DELETE CASCADE"));
            assertThat(ddlOf("chat_message")).contains(collapseWhitespace(
                    "session_id    INTEGER NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE"));

            // 不级联的外键：动作与食物被引用时不允许连带删除，所以这里是裸 REFERENCES（带逗号）。
            assertThat(ddlOf("session_exercise")).contains(collapseWhitespace(
                    "exercise_id   INTEGER NOT NULL REFERENCES exercise(id),"));
            assertThat(ddlOf("meal_item")).contains(collapseWhitespace(
                    "food_id      INTEGER NOT NULL REFERENCES food(id),"));

            // meal.photo_id：照片是餐的可选元数据，删照片只能清空这一格，绝不能删掉用餐记录。
            assertThat(ddlOf("meal")).contains(
                    collapseWhitespace("REFERENCES photo(id) ON DELETE SET NULL"));

            // 营养值必须有默认 0 且非空：既非空又有默认，去掉哪一个都会让「只填了名字的食物」
            // 在读取时炸掉或者悄悄变成 NULL 热量。
            assertThat(ddlOf("food")).contains(collapseWhitespace("kcal_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("protein_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("carb_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("fat_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("fiber_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("sodium_per100 REAL NOT NULL DEFAULT 0"));

            // autoincrement：丢掉它，SQLite 会把删掉的最大 rowid 重新发出去，
            // 于是「删掉第 7 条训练之后再建一条」会拿到 7，而 change_log 里还留着旧的 7。
            // 十四张表都有单列 id 主键；exercise_muscle 是复合主键的关联表
            // （PRIMARY KEY (exercise_id, muscle_group_id)），本来就没有 AUTOINCREMENT，
            // 它由上面那条 DDL 断言里的 PRIMARY KEY 片段守着。
            EXPECTED.stream()
                    .filter(table -> !"exercise_muscle".equals(table))
                    .forEach(table -> assertThat(ddlOf(table))
                            .as("表 %s 的主键必须显式 AUTOINCREMENT", table)
                            .contains(collapseWhitespace("INTEGER PRIMARY KEY AUTOINCREMENT")));
            assertThat(ddlOf("exercise_muscle")).contains(
                    collapseWhitespace("PRIMARY KEY (exercise_id, muscle_group_id)"));
        }

        /**
         * 「Java 侧白名单 == DDL 侧白名单」的防漂移测试（Task 7 质量审查轮新增）。
         *
         * 上面那条只钉住了 **DB 侧**（`sqlite_master.sql` 里逐字的
         * `CHECK (role IN ('primary', 'secondary'))`）。缺的是另一半：
         * `VolumeCalculator.Entry` 的合法角色集是**另一份手写拷贝**，某一侧改了另一侧不会红。
         * 这条把两份值集拉在一起逐项比对（含**顺序**——DDL 里写的就是
         * `'primary', 'secondary'`），任何一侧改动都会失败。
         */
        @Test
        void role_whitelist_matches_the_ddl() {
            String ddl = ddlOf("exercise_muscle");

            // 只取 role 那一条 CHECK 的 IN (...) 值集
            Matcher m = Pattern.compile("role\\s+IN\\s*\\(([^)]*)\\)").matcher(ddl);
            assertThat(m.find())
                    .as("exercise_muscle 的 DDL 里应当有 role IN (...) 形式的 CHECK：%s", ddl)
                    .isTrue();
            List<String> fromDdl = new ArrayList<>();
            Matcher v = Pattern.compile("'([^']*)'").matcher(m.group(1));
            while (v.find()) {
                fromDdl.add(v.group(1));
            }

            assertThat(fromDdl)
                    .as("DDL 里的合法角色集")
                    .isNotEmpty();
            assertThat(VolumeCalculator.VALID_ROLES)
                    .as("Java 侧 VALID_ROLES 必须与 exercise_muscle.role 的 CHECK 逐项一致")
                    .containsExactlyElementsOf(fromDdl);
        }

        // ------------------------------------------------------------------
        // 结构查询工具
        // ------------------------------------------------------------------

        /** 按声明顺序返回列名。用 pragma_table_info 表函数，不能用 PRAGMA table_info。 */
        private List<String> columns(String table) {
            return jdbc.queryForList("SELECT name FROM pragma_table_info('" + table + "')", String.class);
        }

        /** 按声明顺序返回非空列名（不含主键：pragma 的 notnull 对 INTEGER PRIMARY KEY 恒为 0）。 */
        private List<String> notNullColumns(String table) {
            return jdbc.queryForList(
                    "SELECT name FROM pragma_table_info('" + table + "') WHERE \"notnull\" = 1",
                    String.class);
        }

        /**
         * 业务索引 -> "UNIQUE" / "NOT UNIQUE"（可加 " PARTIAL"）。sqlite_ 开头的自动索引
         * 由 UNIQUE 约束与复合主键产生，不属于本迁移显式建的索引，不计入。
         *
         * `PRAGMA index_list` 是「一行多列」的表函数，按 SQLite 的规则加表名参数写成
         * `pragma_index_list('表')` 就能在 FROM 里用。注意**不要**写 pragma_index_list_all：
         * 实测该名字不存在（SQLite 3.53.1 报 `no such table: pragma_index_list_all`）。
         */
        private Map<String, String> businessIndexes() {
            Map<String, String> out = new LinkedHashMap<>();
            for (String table : EXPECTED) {
                jdbc.query(
                        "SELECT name, \"unique\", partial FROM pragma_index_list('" + table + "') "
                                + "WHERE name NOT LIKE 'sqlite_%'",
                        rs -> {
                            while (rs.next()) {
                                out.put(rs.getString("name"),
                                        (rs.getInt("unique") == 1 ? "UNIQUE" : "NOT UNIQUE")
                                                + (rs.getInt("partial") == 1 ? " PARTIAL" : ""));
                            }
                            return null;
                        });
            }
            return out;
        }

        /** 建表 DDL 原文，空白折叠成单空格，便于断言跨行的 CHECK。 */
        private String ddlOf(String table) {
            return collapseWhitespace(jdbc.queryForObject(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name = ?", String.class, table));
        }

        private String indexDdl(String index) {
            return collapseWhitespace(jdbc.queryForObject(
                    "SELECT sql FROM sqlite_master WHERE type='index' AND name = ?", String.class, index));
        }

        private static String collapseWhitespace(String sql) {
            return sql.replaceAll("\\s+", " ").trim();
        }
    }
```

- [x] **Step 5: 跑测试**

Run: `cd backend && mvn -B test -Dtest=SchemaTest`

Expected: `Tests run: 9, Failures: 0, Errors: 0`（本轮修复后 SchemaTest 从 4 条变成 8 条；
Task 7 质量审查轮又加了一条 `role_whitelist_matches_the_ddl`，8 → **9**）

**但只跑 `-Dtest=SchemaTest` 是不够的**：V2 的第一行会把 `schema_probe` 删掉，而
`MigrationTest.baseline_migration_created_probe_table` 断言的是「这张表还在」——
全量测试会因此变红。实测确认过，这不是猜测。处理办法见 Step 5b。

- [x] **Step 5b: 修 MigrationTest 里被 V2 弄红的那条断言**

V2 一旦 DROP 掉探针表，`MigrationTest` 里那条断言就**结构性地无法成立**：
迁移在上下文启动时就跑完了，任何 `@Test` 方法都看不到这张表。它原本要守的是
「V1 里那句 CREATE 不能消失或被改坏」，这件事只有读脚本原文才问得出来，所以改成：

```java
    @Test
    void baseline_migration_still_defines_the_probe_table_it_is_known_for() {
        // 读资源原文而不是数据库：schema_probe 已被 V2 删掉（见 SchemaTest 的对应断言）。
        String v1 = readMigration("V1__baseline.sql");

        // 空迁移、CREATE 被删掉 / 改名 / 拆成别的表，都会在这里变红。
        assertThat(v1).contains("CREATE TABLE");
        assertThat(v1).contains("schema_probe");
        assertThat(v1).contains("INTEGER PRIMARY KEY AUTOINCREMENT");
        assertThat(v1).contains("NOT NULL");
    }

    private String readMigration(String fileName) {
        try (var in = new ClassPathResource("db/migration/" + fileName).getInputStream()) {
            // 统一换行符：断言只关心内容，不该因为 checkout 的行尾设置而变红。
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception e) {
            throw new IllegalStateException("读不到迁移脚本: " + fileName, e);
        }
    }
```

顺带补上了原断言查不到的盲区：改 V1 只让「已经跑过 V1 的库」报 checksum 失配，
**全新的库照样跑得通**（本仓库的测试库每次 `target` 一删就是全新的），
所以光靠运行时断言根本拦不住这类改动。

**但子串断言丢掉了列级精确性**（审查意见），所以本轮又补了一条**内存库**测试：
把 V1 的脚本文本对 `jdbc:sqlite::memory:` 执行一遍，再断言列清单与
`AUTOINCREMENT` / `NOT NULL`。下面是 `MigrationTest` 的最终实现（与
`SchemaTest.java` 一样，代码块整体缩进 4 格）：

> **三个实测踩出来的坑**：内存库是「一连接一库」，必须保持同一个 `Connection` 打开、
> 不能交给连接池；`PRAGMA table_info` 是表函数，要写
> `SELECT name FROM pragma_table_info('schema_probe')`；这条断言验证的是「脚本文本能跑出
> 什么」，**不能替代** Flyway 那条断言（它绕过了 `flyway_schema_history`、绕过了真实
> 的迁移执行路径），注释里已写清。

```java

    package com.change;

    import org.junit.jupiter.api.Test;
    import org.springframework.beans.factory.annotation.Autowired;
    import org.springframework.boot.test.context.SpringBootTest;
    import org.springframework.core.io.ClassPathResource;
    import org.springframework.jdbc.core.JdbcTemplate;
    import org.springframework.jdbc.datasource.SingleConnectionDataSource;
    import org.springframework.jdbc.datasource.init.ScriptUtils;

    import java.nio.charset.StandardCharsets;
    import java.sql.Connection;
    import java.sql.DriverManager;

    import static org.assertj.core.api.Assertions.assertThat;

    /**
     * 迁移机制的哨兵。
     *
     * 为什么第一条断言「结构」而不是「有一行数据」：把 V1 的内容清空、只留注释，
     * Flyway 照样会记一行 success=1——于是「数一行数据」式的断言仍然全绿，而 V1 其实
     * 什么也没做。对一个用来证明「迁移机制可靠」的测试来说，这恰好是反例。
     *
     * 为什么从 Task 3 起这条改成读迁移脚本本身：V2 的第一行就是 DROP TABLE schema_probe，
     * 迁移在上下文启动时就跑完，**任何**测试方法都看不到这张表了——继续断言「表在」
     * 无论怎么写都必然红，而断言「表不在」只是把 SchemaTest 那条抄一遍。
     * 想守住的其实还是同一件事：V1 里那句 CREATE 不能消失或被改坏。这件事只有读
     * 脚本原文才问得出来，顺带还补上了原断言查不到的盲区——改 V1 只让「已经跑过 V1 的库」
     * 报 checksum 失配，**全新的库照样跑得通**，所以光靠运行时断言拦不住这类改动。
     *
     * 本轮（Task 3 审查后）补了三条：
     * - v1_script_still_produces_the_probe_table_it_is_known_for：把 V1 的脚本文本对一条
     *   **内存**库执行一遍，再断言它真的建出那张表、列名与 AUTOINCREMENT / NOT NULL 都在。
     *   这是对上面那条子串断言的收窄：子串断言问不出「脚本还能不能跑」，也问不出列级细节。
     * - published_migrations_are_byte_frozen：把已发布版本的 checksum 钉死。
     * - （原有的 PRAGMA 哨兵不变。）
     */
    @SpringBootTest
    class MigrationTest {

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void baseline_migration_still_defines_the_probe_table_it_is_known_for() {
            // 读资源原文而不是数据库：schema_probe 已被 V2 删掉（见 SchemaTest 的对应断言）。
            String v1 = readMigration("V1__baseline.sql");

            // 空迁移、CREATE 被删掉 / 改名 / 拆成别的表，都会在这里变红。
            assertThat(v1).contains("CREATE TABLE");
            assertThat(v1).contains("schema_probe");
            assertThat(v1).contains("INTEGER PRIMARY KEY AUTOINCREMENT");
            assertThat(v1).contains("NOT NULL");

            // 反过来说：V1 真的建了这张表，才轮得到 V2 去 DROP 它——V2 里那句
            // DROP TABLE schema_probe（无 IF EXISTS）在空 schema 上必然报
            // "no such table: schema_probe"，整个上下文起不来。这条链子本身就是
            // V1 执行过的证据。
        }

        /**
         * 把 V1 的脚本文本对一条**内存** SQLite 连接执行一遍，然后断言列级细节。
         *
         * 为什么需要它：上面那条子串断言只看「文里有没有这几段字」，看不出脚本还能不能执行，
         * 也看不出列类型 / 顺序 / 非空标记。审查正是据此指出它「丢掉了列级精确性」。
         *
         * 三个必须注意的点（都是实测踩出来的）：
         * 1. `jdbc:sqlite::memory:` 是**一连接一库**：交给连接池（或每条语句各取一次连接）
         *    就会拿到一个空库，`no such table: schema_probe`。所以这里手动
         *    DriverManager.getConnection 并且全程只有这一条 Connection。
         * 2. `PRAGMA table_info` 是表函数（一行多列），queryForList(..., String.class) 会对它抛
         *    IncorrectResultSetColumnCountException；写成 `SELECT name FROM pragma_table_info(...)`。
         * 3. 它验证的是「脚本文本能跑出什么」，**不能替代** Flyway 那条断言——它绕过了
         *    flyway_schema_history、绕过了真实的迁移执行路径，也不检查 V1 是否真的被 Flyway
         *    应用过。两条断言守的是不同的东西，删哪一条都会露出一个洞。
         */
        @Test
        void v1_script_still_produces_the_probe_table_it_is_known_for() throws Exception {
            // 手动开连接、手动关：内存库随连接存在，不能交给连接池。
            try (Connection memory = DriverManager.getConnection("jdbc:sqlite::memory:")) {
                ScriptUtils.executeSqlScript(memory, new ClassPathResource("db/migration/V1__baseline.sql"));

                // 包一层 SingleConnectionDataSource，让下面几条查询都走这一条 Connection
                // （内存库是一连接一库，换一条连接就换一个空库）。
                JdbcTemplate mem = new JdbcTemplate(new SingleConnectionDataSource(memory, true));

                // 1) 列清单：少一列、改名、顺序变了都会红。
                assertThat(mem.queryForList(
                        "SELECT name FROM pragma_table_info('schema_probe')", String.class))
                        .containsExactly("id", "note", "created_at");

                // 2) DDL 原文里的 AUTOINCREMENT 与列级 NOT NULL。
                //    DDL 是按列对齐存的（`note       TEXT NOT NULL`），所以断言前先把空白折叠成
                //    单空格：这样断言既不怕迁移被重新排版，又能真正问出「这一列上有没有 NOT NULL」。
                String ddl = collapseWhitespace(mem.queryForObject(
                        "SELECT sql FROM sqlite_master WHERE type='table' AND name='schema_probe'",
                        String.class));
                assertThat(ddl).contains("INTEGER PRIMARY KEY AUTOINCREMENT");
                assertThat(ddl).contains("note TEXT NOT NULL,");
                assertThat(ddl).contains("created_at TEXT NOT NULL");

                // 3) 脚本里的那行数据也真的进去了（空脚本 / INSERT 被删掉会红）。
                assertThat(mem.queryForObject("SELECT note FROM schema_probe", String.class))
                        .isEqualTo("baseline");
            }
        }

        /**
         * 「已发布迁移不可变」这条纪律，此前只剩文档在守。
         *
         * 与 Flyway 自己的校验**不重复**：Flyway 只在「已经跑过该版本的库」上比 checksum，
         * 而本地删掉 target 之后每次跑测试都是全新的库，checksum 校验根本不会被触发——
         * 于是给 V1 加一行注释、或者把 V2 的某个 CHECK 删掉，套件照样全绿。
         * 这里把 checksum 钉死在测试里，任何对 V1–V4 的编辑（哪怕只加一行注释）都会让它变红。
         *
         * checksum 是 Flyway 对迁移文件内容算出的 CRC32，必须从**实跑后的库**里读出来填，
         * 不能猜。当前值对应 V1–V4 发布时的字节内容：
         *   1:baseline / 2:training / 3:nutrition / 4:auxiliary。
         *
         * 版本号写死成 containsExactly（不是「前四个必须是」）：将来新增 V5 时这条会红，
         * 那是**有意的**——新迁移一旦发布就该被一起冻结，加进来的同时也要确认它已发布。
         */
        @Test
        void published_migrations_are_byte_frozen() {
            assertThat(jdbc.queryForList(
                    "SELECT version || ':' || checksum FROM flyway_schema_history ORDER BY installed_rank",
                    String.class))
                    .containsExactly("1:1904735359", "2:-962992177", "3:1207314981", "4:1460974919");
        }

        private String readMigration(String fileName) {
            try (var in = new ClassPathResource("db/migration/" + fileName).getInputStream()) {
                // 统一换行符：断言只关心内容，不该因为 checkout 的行尾设置而变红。
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            } catch (Exception e) {
                throw new IllegalStateException("读不到迁移脚本: " + fileName, e);
            }
        }

        /** 把空白折叠成单空格：DDL 是按列对齐存的，断言片段不该因为排版而失配。 */
        private static String collapseWhitespace(String sql) {
            return sql.replaceAll("\\s+", " ").trim();
        }

        @Test
        void flyway_history_has_v1() {
            String desc = jdbc.queryForObject(
                    "SELECT description FROM flyway_schema_history WHERE version = '1'", String.class);
            assertThat(desc).isEqualTo("baseline");
        }

        @Test
        void connection_pragmas_are_effective() {
            // 这两个 PRAGMA 靠 JDBC URL 参数开启（迁移脚本里开不了：SQLite 在事务内
            // 拒绝切 WAL）。它们一旦写错大小写，驱动会当成 PRAGMA 名执行并直接
            // SQLITE_CANTOPEN，应用根本起不来——所以这条断言是必要的哨兵。
            assertThat(queryPragma("foreign_keys")).isEqualTo("1");
            assertThat(queryPragma("journal_mode")).isEqualTo("wal");
        }

        private String queryPragma(String name) {
            return jdbc.queryForObject("PRAGMA " + name, String.class);
        }
    }
```

- [x] **Step 6: 跑全量测试**

Run: `cd backend && Remove-Item -Recurse -Force target; mvn -B test`

Expected: `Tests run: 18, Failures: 0, Errors: 0`（实测值）

（本轮修复前的总数是 12 = 2 + 3 + 3 + 4。修复后 18 = `ChangeApplicationTests` 2 +
`DataDirectoryInitializerTest` 3 + `MigrationTest` 5 + `SchemaTest` 8；具体为
`MigrationTest` 3 → 5，新增 `v1_script_still_produces_the_probe_table_it_is_known_for`
与 `published_migrations_are_byte_frozen`；`SchemaTest` 4 → 8，新增列级 / NOT NULL /
索引级 / 约束级四条快照，并把 `workout_set_supports_timed_and_per_side` 由 `contains`
改成 `containsExactlyInAnyOrder`（对「多出一列」恒绿的写法）。**必须先删 `target`**：
`src/main/resources` 下的改动可能没被复制到 `target/classes`，验证结果会与源码不符。）

- [x] **Step 7: 提交（Task 3 原始实现）**

```bash
git add backend/src/main/resources/db/migration/V2__training.sql \
        backend/src/main/resources/db/migration/V3__nutrition.sql \
        backend/src/main/resources/db/migration/V4__auxiliary.sql \
        backend/src/test/java/com/change/SchemaTest.java \
        backend/src/test/java/com/change/MigrationTest.java
git commit -m "feat: 十五张表的迁移，含三条防回归断言"
```

- [x] **Step 8: 提交（Task 3 审查修复）**

```bash
git add -A
git commit -m "fix(schema): 硬化十五张表的约束与守卫（审查发现 8 种破坏均无人拦）"
```

**提交前必须完成上一节那张表里的 8 种破坏验证**：逐条破坏、删 `target`、跑
`mvn -B test` 确认变红、恢复。任何一种**抓不住**都说明修复没到位，要报告而不是挑
容易的做。做完这一步才允许勾选 Step 8 进入 Task 4。

> 实施记录见 commit message 与 `git log`；本轮实测的 8 条破坏结果（哪条测试变红、
> 报了什么错）已逐条记录。

### 由本 schema 推出、必须在别处兑现的约定

这一节记的不是「将来也许要考虑的事」，而是**数据库层已经刻意做出、并且写进了 DDL 的决定**。
它们全都属于同一类：拦得住一件事，就必须有另一处接住剩下的那半。schema 本身已经把话说死
（迁移一旦分发就字节冻结，没有第二次机会），所以约定写在这里，缺了哪一条都是漏做。

#### (a) 四个日期 CHECK 的错误消息必须翻译成人话；而且它只管形状、不管日历

四个纯日期列（`workout_session.session_date`、`meal.meal_date`、`body_metric.measured_on`、
`photo.taken_on`）都带同一条形状约束：

```sql
CHECK (<列> GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')
```

**接口层义务**：任何一条写入路径（用户输入、导入器、外部数据集）撞上它时，抛出的都是一句
`CHECK constraint failed: session_date GLOB ...`——英文、带内部约束名与 GLOB 模式，对用户毫无
意义。接口层必须把这条**按列翻译成可读提示**，例如「日期格式必须是 2026-10-14」。否则用户
只会看到一句约束名，既不知道哪一列错了，也不知道正确的写法是什么。

会撞上这条的三个现实来源，都不是脏数据、而是**格式约定不同**：

- 分隔符写成 `2026/10/14`；
- 从表格或 CSV 抄来的值带尾随空格（或前后空格）；
- 前端 `toLocaleDateString()` / `toISOString()` 的输出（分别是 `2026/10/14` 与
  `2026-10-14T03:22:11.000Z`）。后者尤其要防：`toISOString()` 的结果**看起来**像日期，
  但它正是这条 CHECK 要拦的「完整时间戳混进纯日期列」——拦住它是有意的，不是误伤。

**同时必须记住这条 CHECK 的边界：它只管形状，不管日历。** `2026-02-31`、`2026-13-01`
都能原样入库——GLOB 只数位，不判断月份天数。**日历合法性由应用层校验**：迁移能拦的只有
形状，拦不了「这个日期不存在」，而一个不存在的日期一旦入库，`strftime` / 区间查询 / 按日
排序都会给出静默错误的结果（不是报错）。

#### (b) `exercise.name_en` 的唯一索引把「动作身份」压在了英文名的书写规范上

`ux_exercise_name_en` 是 `exercise(name_en)` 的**单列**唯一索引：

```sql
CREATE UNIQUE INDEX ux_exercise_name_en ON exercise(name_en);
```

它不含 `name_zh`（该列可空，也不唯一）、不含 `equipment`、不含 `is_compound`。也就是说，
库里**没有**基于中文名或「中文名 + 器械」的唯一性——动作身份完全靠英文名的措辞区分：

- 「杠铃卧推」与「哑铃卧推」必须写作 `Barbell Bench Press` 与 `Dumbbell Bench Press`，
  不能两条都叫 `Bench Press`（否则第二条撞 `UNIQUE constraint failed`，整批导入回滚）；
- 反过来，同一动作的中文名怎么写都不影响唯一性，改中文名不会撞。

**计划 2（动作库导入）必须守住这个约定**，否则两条路都是坏的：要么导入直接失败，要么为了
绕过它去改英文名（把 `Barbell Bench Press` 改成 `Bench Press (Barbell)`），而后者会静默
污染「按英文名对齐外部数据集」这件事——`source_id` 的命名空间能防住跨数据集冲突，防不住
同数据集内的措辞漂移。**这条约束一旦库里有历史数据就加不上了**（要么建不上索引，要么得先
合并重复行、重挂 `session_exercise.exercise_id`、重算所有衍生指标），所以已按现在定下的
形态固化——导入器适配它，不是它适配导入器。

#### (c) `food.name_zh` 故意不唯一，代价要认

`food.name_zh` 上只有一条**非唯一**索引 `ix_food_name_zh`，没有唯一约束：

```sql
CREATE INDEX ix_food_name_zh ON food(name_zh);
```

这是有意的：中式食物同名不同做法很常见（「红烧肉」的炒法与炖法配料比例差很多，
「清蒸鲈鱼」与「红烧鲈鱼」是两道菜），在数据库层强制唯一会逼导入器二选一——要么丢数据，
要么给同一种食物编造变体名，两种都比允许重复更糟。所以**同名多行是对的**。

**代价必须认**：一旦同一名字存在多行，**统计「同一种食物」时若按名字聚合就会重复计数**——
按 `name_zh` 分组的「本周吃了多少红烧肉」会把每一行各自算一遍；更隐蔽的是把它当成同一种
食物去平均，会把两种做法的热量搅成一个不存在的中间值。

**但改成按 `food_id` 聚合并不解决这件事**，这一点必须说清楚，否则很容易以为已经修好了：
`meal_item.food_id` 是 `NOT NULL`，按它分组确实不会把两种做法搅在一起——可是**同一个 dish
被录进去两次时本来就会拿到两个不同的 `food_id`**（「红烧肉（炒）」和「红烧肉（炖）」是两行，
`id` 不同），于是「这周吃了 3 次红烧肉」依然数成 1 次炒 + 2 次炖，或者反过来把本该是同一道菜
的记录拆成两个「常吃食物」。**换句话说：重复计数的根因是「同一道菜有多个身份」，不是聚合
键选错了。** 按名字聚合是错在把不同做法当同一种；按 `food_id` 聚合是错在把同一种做法当不同
种。两个键各自都只能答对一半。

**导入器义务**（这才是真正要做的那一步）：在导入报告里**列出疑似重复项**（同名但营养值或
份量定义不一致的行），让人当场决定是合并、改名还是保留。只有这一步做了，「同一道菜」才会
收敛到一个 `food_id` 上，之后按 `food_id` 聚合与按名字聚合才会给出同一个答案。这一步不做，
味道差异就会变成热量统计里的噪声——而且它不会报错，只会让每日热量看起来「就是差那么一点」。
（若将来需要在库内表达「这两个条目其实是同一道菜」，那要一个新列或一张别名表——现在没有，
所以这个收敛只能发生在导入期。）

---

## Task 4: 训练域 JPA 实体与仓储

> ### ⚠️ 本任务有两个全局性前置约定（已实测，违反则静默损坏数据）
>
> **一、方言必须用 `com.change.config.ChangeSqliteDialect`，不能用 community 的 `SQLiteDialect`。**
> community 方言把 `Long`→`bigint`、`boolean`→`boolean`、`LocalDate`→`date`、`LocalDateTime`→`timestamp`、`Double`→`double precision`，而 V1–V4 里逐字写的是 `INTEGER`/`REAL`/`TEXT`。`ddl-auto=validate` 会逐列报错，**上下文根本起不来**（实测 17 个错误）。
> 该方言覆写 `columnType()` 把类型映射回 SQLite 的四个存储类。**这是全局的，后续十一个实体不再需要任何类型注解。**
> 注：给每个字段加 `@Column(columnDefinition=...)` 也**可行**（已实测能通过 validate，且不改 JDBC 绑定），但要为十五张表逐个记得、容易漏，所以选了方言这条集中路线。
>
> **二、`LocalDate` / `LocalDateTime` 字段必须加 `@JdbcTypeCode`。**
> xerial 驱动默认把 java.time 类型写成**纪元毫秒**。实测三种情形的区别：
>
> | 配置 | `session_date` | `started_at` | 是否报错 |
> |---|---|---|---|
> | 加注解（正确） | `'2026-10-14'` | `'2026-10-14T19:30:15'` | 否 |
> | 时间戳不加注解 | `'2026-10-14'` | `'1791977415000'` | **否 —— 静默损坏** |
> | 日期也不加注解 | CHECK 拒绝 | — | 是，`SQLITE_CONSTRAINT_CHECK` |
>
> `session_date` 有日期形状 CHECK 兜着（响亮失败）；但 `started_at`/`ended_at` **没有 CHECK**，会静默存成 `date()`/`datetime()` 都解析不了的数字串——这违反设计文档「时间戳用 TEXT + ISO-8601」的约定，而且事后极难发现。
>
> ```java
> @JdbcTypeCode(SqlTypes.LOCAL_DATE)
> @Column(name = "session_date", nullable = false)
> public LocalDate sessionDate;
>
> @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
> @Column(name = "started_at")
> public LocalDateTime startedAt;
> ```
>
> **顺带一条已实测的细节**：`LocalDateTime` 秒为 0 时 `toString()` 会省略，`20:15:00` 落库为 `'2026-10-14T20:15'`（16 字符）。仍是合法 ISO-8601，但**列内长度不固定**，将来做字符串比较或排序时要注意。

**Files:**
- Create: `backend/src/main/java/com/change/config/ChangeSqliteDialect.java`
- Create: `backend/src/main/java/com/change/domain/Exercise.java`
- Create: `backend/src/main/java/com/change/domain/WorkoutSession.java`
- Create: `backend/src/main/java/com/change/domain/SessionExercise.java`
- Create: `backend/src/main/java/com/change/domain/WorkoutSet.java`
- Create: `backend/src/main/java/com/change/repo/ExerciseRepo.java`
- Create: `backend/src/main/java/com/change/repo/WorkoutSessionRepo.java`
- Test: `backend/src/test/java/com/change/repo/TrainingRepoTest.java`
- Modify: `backend/src/main/resources/application.yml`（把 database-platform 换成自建方言）

- [x] **Step 1: 写实体（四个）**

```java
package com.change.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "exercise")
public class Exercise {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "name_en", nullable = false)
    public String nameEn;

    @Column(name = "name_zh")
    public String nameZh;

    public String equipment;

    @Column(name = "is_compound", nullable = false)
    public boolean compound = false;

    @Column(name = "default_unit", nullable = false)
    public String defaultUnit = "kg";

    @Column(name = "source_id")
    public String sourceId;

    public String note;

    @Column(nullable = false)
    public boolean archived = false;
}
```

```java
package com.change.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "workout_session")
public class WorkoutSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "session_date", nullable = false)
    public LocalDate sessionDate;

    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "ended_at")
    public LocalDateTime endedAt;

    public String title;

    @Column(name = "perceived_state")
    public String perceivedState;

    public String note;

    @Column(name = "source_routine_day_id")
    public Long sourceRoutineDayId;
}
```

```java
package com.change.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "session_exercise")
public class SessionExercise {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "session_id", nullable = false)
    public Long sessionId;

    @Column(name = "exercise_id", nullable = false)
    public Long exerciseId;

    @Column(name = "order_index", nullable = false)
    public int orderIndex;

    public String note;

    @Column(name = "superset_group")
    public String supersetGroup;
}
```

```java
package com.change.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "workout_set")
public class WorkoutSet {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "session_exercise_id", nullable = false)
    public Long sessionExerciseId;

    @Column(name = "set_index", nullable = false)
    public int setIndex;

    @Column(name = "set_type", nullable = false)
    public String setType = "normal";

    public Double weight;
    public Integer repetitions;

    /** 计时动作（平板支撑等）用秒数，与 repetitions 二选一。 */
    @Column(name = "duration_seconds")
    public Integer durationSeconds;

    /** 每侧计数（弓步、单臂动作）。 */
    @Column(name = "per_side", nullable = false)
    public boolean perSide = false;

    public Double rir;

    @Column(name = "rest_seconds")
    public Integer restSeconds;

    @Column(nullable = false)
    public boolean completed = false;

    @Column(name = "target_weight")
    public Double targetWeight;

    @Column(name = "target_reps")
    public Integer targetReps;
}
```

- [x] **Step 2: 写两个仓储**

```java
package com.change.repo;

import com.change.domain.Exercise;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExerciseRepo extends JpaRepository<Exercise, Long> {
    List<Exercise> findByArchivedFalse();
    List<Exercise> findByNameZhContainingOrNameEnContainingIgnoreCase(String zh, String en);
}
```

```java
package com.change.repo;

import com.change.domain.WorkoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface WorkoutSessionRepo extends JpaRepository<WorkoutSession, Long> {
    List<WorkoutSession> findBySessionDateBetweenOrderBySessionDateDesc(LocalDate from, LocalDate to);
    List<WorkoutSession> findTop5ByOrderBySessionDateDesc();
}
```

- [x] **Step 3: 写集成测试**

```java
package com.change.repo;

import com.change.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrainingRepoTest {

    @Autowired ExerciseRepo exercises;
    @Autowired WorkoutSessionRepo sessions;
    @Autowired jakarta.persistence.EntityManager em;

    @Test
    void can_persist_a_full_three_level_session() {
        Exercise ex = new Exercise();
        ex.nameEn = "Barbell Bench Press";
        ex.nameZh = "杠铃卧推";
        ex.equipment = "barbell";
        ex.compound = true;
        exercises.save(ex);

        WorkoutSession s = new WorkoutSession();
        s.sessionDate = LocalDate.of(2026, 10, 14);
        s.title = "胸 + 三头";
        sessions.save(s);

        SessionExercise se = new SessionExercise();
        se.sessionId = s.id;
        se.exerciseId = ex.id;
        se.orderIndex = 0;
        em.persist(se);

        WorkoutSet set = new WorkoutSet();
        set.sessionExerciseId = se.id;
        set.setIndex = 0;
        set.weight = 60.0;
        set.repetitions = 8;
        set.completed = true;
        set.targetWeight = 60.0;
        set.targetReps = 8;
        em.persist(set);
        em.flush();
        em.clear();

        WorkoutSet back = em.find(WorkoutSet.class, set.id);
        assertThat(back.weight).isEqualTo(60.0);
        assertThat(back.targetWeight).isEqualTo(60.0);   // 目标值与实际值同表
        assertThat(back.perSide).isFalse();
    }

    @Test
    void timed_set_stores_seconds_not_reps() {
        Exercise ex = new Exercise();
        ex.nameEn = "Plank";
        ex.nameZh = "平板支撑";
        exercises.save(ex);

        WorkoutSession s = new WorkoutSession();
        s.sessionDate = LocalDate.of(2026, 10, 14);
        sessions.save(s);

        SessionExercise se = new SessionExercise();
        se.sessionId = s.id;
        se.exerciseId = ex.id;
        se.orderIndex = 0;
        em.persist(se);

        WorkoutSet set = new WorkoutSet();
        set.sessionExerciseId = se.id;
        set.setIndex = 0;
        set.durationSeconds = 60;
        em.persist(set);
        em.flush();
        em.clear();

        WorkoutSet back = em.find(WorkoutSet.class, set.id);
        assertThat(back.durationSeconds).isEqualTo(60);
        assertThat(back.repetitions).isNull();
    }
}
```

- [x] **Step 4: 跑测试**

Run: `cd backend && mvn -B test -Dtest=TrainingRepoTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [x] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/domain backend/src/main/java/com/change/repo \
        backend/src/test/java/com/change/repo
git commit -m "feat: 训练域实体与仓储，覆盖三层嵌套与计时/每侧组"
```

---

## Task 5: 热量计算（核心，先写测试）

**这是本计划最重要的三个任务之一。** 它的错误会污染所有饮食统计。

**Files:**
- Create: `backend/src/main/java/com/change/calc/Nutrition.java`
- Create: `backend/src/main/java/com/change/calc/NutritionCalculator.java`
- Create: `backend/src/main/java/com/change/calc/Round.java`（质量审查轮新增：舍入工具，见 Step 3b）
- Test: `backend/src/test/java/com/change/calc/NutritionCalculatorTest.java`

- [x] **Step 1: 写失败的测试**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NutritionCalculatorTest {

    /** 每 100 克含 116 kcal、2.6 g 蛋白的米饭。 */
    private static final Nutrition RICE_PER_100G =
            new Nutrition(116, 2.6, 25.9, 0.3, 0.3, 2.0);

    @Test
    void computes_nutrition_for_150_grams() {
        Nutrition actual = NutritionCalculator.forGrams(RICE_PER_100G, 150);

        assertThat(actual.kcal()).isEqualTo(174.0);
        assertThat(actual.protein()).isEqualTo(3.9);
        assertThat(actual.carb()).isEqualTo(38.85);
    }

    @Test
    void changing_portion_recomputes_from_scratch() {
        // 这条是「不存绝对热量」那条铁律的验收点：
        // 改份量不需要「记得回去改旧值」，因为旧值从来没被存下来。
        Nutrition oneBowl = NutritionCalculator.forGrams(RICE_PER_100G, 150);
        Nutrition oneAndHalf = NutritionCalculator.forGrams(RICE_PER_100G, 225);

        assertThat(oneBowl.kcal()).isEqualTo(174.0);
        assertThat(oneAndHalf.kcal()).isEqualTo(261.0);
        assertThat(oneAndHalf.kcal()).isNotEqualTo(oneBowl.kcal());
    }

    @Test
    void zero_grams_yields_zero() {
        assertThat(NutritionCalculator.forGrams(RICE_PER_100G, 0).kcal()).isZero();
    }

    @Test
    void rejects_negative_grams() {
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grams");
    }

    @Test
    void rejects_absurd_grams() {
        // 份量上限校验：1 万克显然是录错了
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grams");
    }

    @Test
    void sums_a_meal() {
        Nutrition meal = NutritionCalculator.sum(List.of(
                NutritionCalculator.forGrams(RICE_PER_100G, 150),
                new Nutrition(240, 18, 6, 14, 1, 300)));

        assertThat(meal.kcal()).isEqualTo(414.0);
        assertThat(meal.protein()).isEqualTo(21.9);
        assertThat(meal.carb()).isEqualTo(44.85);
        assertThat(meal.fat()).isEqualTo(14.45);
        assertThat(meal.fiber()).isEqualTo(1.45);
        assertThat(meal.sodium()).isEqualTo(303.0);
    }

    @Test
    void rounding_is_exactly_two_decimals() {
        // 5 克的碳水 = 25.9 × 0.05 = 1.295；两位舍入给 1.3，三位会给 1.295。
        // 这条锁住 Round.to2 的精度本身，而不只是「不粗于两位」。
        assertThat(NutritionCalculator.forGrams(RICE_PER_100G, 5).carb()).isEqualTo(1.3);
    }

    @Test
    void accepts_exactly_the_upper_bound_and_rejects_just_above() {
        // 边界：5000 必须被接受，5000.001 必须被拒。
        // 只测「深处越界」（10000）的话，把 > 写成 >= 无人察觉。
        assertThat(NutritionCalculator.forGrams(RICE_PER_100G, 5_000).kcal()).isEqualTo(5_800.0);
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, 5_000.001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grams");
    }

    @Test
    void rejects_non_finite_grams() {
        // NaN 会同时绕过 < 0 与 > MAX_GRAMS 两个判断，一路走到 Round.to2
        // 变成 0.0——「一餐安静地显示 0 kcal」。
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
        assertThatThrownBy(() -> NutritionCalculator.forGrams(RICE_PER_100G, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
    }

    @Test
    void rejects_non_finite_and_negative_nutrition() {
        // NaN 经 round2 会变成 0.0（一餐安静地显示 0 kcal）；
        // ∞ 会被饱和成 9.2e16。两者都必须在构造时就拦住。
        assertThatThrownBy(() -> new Nutrition(Double.NaN, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kcal");
        assertThatThrownBy(() -> new Nutrition(0, Double.POSITIVE_INFINITY, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("protein");
        assertThatThrownBy(() -> new Nutrition(0, 0, -1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("carb");
    }

    @Test
    void gram_based_fields_are_validated_too() {
        assertThatThrownBy(() -> new Nutrition(0, 0, 0, Double.NaN, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fat");
        assertThatThrownBy(() -> new Nutrition(0, 0, 0, 0, Double.NEGATIVE_INFINITY, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fiber");
        assertThatThrownBy(() -> new Nutrition(0, 0, 0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sodium");
    }

    @Test
    void sum_of_empty_list_is_all_zero() {
        assertThat(NutritionCalculator.sum(List.of()))
                .isEqualTo(new Nutrition(0, 0, 0, 0, 0, 0));
    }

    @Test
    void sum_rejects_null_list_and_null_elements() {
        assertThatThrownBy(() -> NutritionCalculator.sum(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("items");
        assertThatThrownBy(() -> NutritionCalculator.sum(
                Arrays.asList(RICE_PER_100G, null)))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("不能");
        // 断言用「不能」而不是「null」：删掉 sum 里那行 Objects.requireNonNull(n, ...) 之后
        // 仍然会抛 NPE，而 JDK 的消息本身**也含 "null" 字样**
        // （Cannot invoke "com.change.calc.Nutrition.kcal()" because "n" is null），
        // 用 "null" 断言就恒真了——实测过，它拦不住「删掉校验」的变异。
    }
}
```

- [x] **Step 2: 跑测试，确认它失败**

Run: `cd backend && mvn -B test -Dtest=NutritionCalculatorTest`

Expected: 编译失败，提示 `cannot find symbol: class Nutrition` / `NutritionCalculator`

**实测（2026-10-15）**：`BUILD FAILURE`，`testCompile` 报 **15 个**「找不到符号」
（本机 locale 为 zh_CN，英文提示为 cannot find symbol）：多处 `类 Nutrition` 与
`变量 NutritionCalculator` 未定义。原始输出：

```text
[INFO] --- compiler:3.14.1:testCompile (default-testCompile) @ change-backend ---
[INFO] Compiling 6 source files with javac [debug parameters release 25] to target\test-classes
[ERROR] COMPILATION ERROR :
[ERROR] .../NutritionCalculatorTest.java:[11,26] 找不到符号
  符号:   类 Nutrition
  位置: 类 com.change.calc.NutritionCalculatorTest
[ERROR] .../NutritionCalculatorTest.java:[16,28] 找不到符号
  符号:   变量 NutritionCalculator
  位置: 类 com.change.calc.NutritionCalculatorTest
[ERROR] .../NutritionCalculatorTest.java:[37,20] 找不到符号
  （共 15 处，略）
[INFO] 15 errors
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:testCompile
```

> **补记（2026-10-15，规格评审轮）**：上面的 15 errors 与行号是**补那两条测试方法之前**
> 的实测记录，保留原样。新增的 `rounding_is_exactly_two_decimals` 与
> `accepts_exactly_the_upper_bound_and_rejects_just_above` 加在文件末尾，
> **不会改变打不开的符号个数**（`Nutrition` / `NutritionCalculator` 仍未定义），
> 只会让符号出现的位置行号增加。

⚠️ 读这条证据时要准确：编译失败意味着**一条断言都没跑到**。
它只证明「测试先于实现存在」，**不能**证明「断言会红」。
断言本身可失败的证据在 Step 4 的变异测试里。

- [x] **Step 3: 写实现**

```java
package com.change.calc;

/**
 * 一份营养值。
 *
 * 单位：热量为 kcal，蛋白质/碳水/脂肪/纤维为克，钠为毫克。
 * （钠用毫克与 USDA 及国内成分表一致；按克读会得到致死量级的数字。）
 *
 * 这个类型同时用于「每 100 克基准值」和「一餐合计值」两种语义——
 * 二者形状相同，record 无法区分。约定：**持有 per-100g 基准值的变量一律带 per100 前缀**
 * （`forGrams` 的参数已经这么做），没有该前缀的就是实际份量的值。
 */
public record Nutrition(double kcal, double protein, double carb,
                        double fat, double fiber, double sodium) {

    public Nutrition {
        requireNonNegativeFinite(kcal, "kcal");
        requireNonNegativeFinite(protein, "protein");
        requireNonNegativeFinite(carb, "carb");
        requireNonNegativeFinite(fat, "fat");
        requireNonNegativeFinite(fiber, "fiber");
        requireNonNegativeFinite(sodium, "sodium");
    }

    /**
     * 拒绝负数与非有限值（NaN / ±Infinity）。
     *
     * 为什么在类型上拦：这两个值一旦流进统计，失效方式都是**静默**的——
     * NaN 经 {@link Round#to2} 会变成 0.0（一餐安静地显示 0 kcal），
     * ∞ 会被饱和成 9.2e16（一个荒谬但看起来像数字的值）。
     * 对会分发给他人的客户端，「看起来对但其实不对」比崩溃更坏。
     */
    private static void requireNonNegativeFinite(double v, String field) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(field + " 必须是有限值，实际为 " + v);
        }
        if (v < 0) {
            throw new IllegalArgumentException(field + " 不能为负，实际为 " + v);
        }
    }
}
```

```java
package com.change.calc;

/**
 * 舍入工具。
 *
 * 为什么单独抽出来：饮食与 1RM 两处都需要「保留两位小数」，
 * 而它各自私有会导致两份逐字相同的拷贝——改一处就得记得改两处。
 * 训练容量**不需要**它：那里的值恒为 0.5 的整数倍（见
 * {@link VolumeCalculator#accumulate} 的 @return），累加与比较都是精确的。
 *
 * 为什么用 Math.round：它按「四舍五入远离零」处理 .5，
 * 且对负数对称（-1.005 → -1.01 而非 -1.00），适合营养与重量这类只关心量级的场景。
 */
final class Round {

    private Round() {
    }

    /** 保留两位小数：避免 0.1+0.2 那类浮点噪声显示成 0.30000000000000004。 */
    static double to2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
```

```java
package com.change.calc;

import java.util.List;
import java.util.Objects;

/**
 * 热量与宏量营养素计算。
 *
 * 铁律：所有营养值都按「每 100 克」保存，份量单独保存。
 * 本类只做一件事：把每 100 克的值按实际克数换算出来。
 * 结果永远实时计算，绝不回存——这样改份量、换食物、换单位都不会算错。
 *
 * 命名约定：凡持有 per-100g 基准值的变量/参数一律带 per100 前缀
 * （forGrams 的参数已经这么做）。没有该前缀的，就是实际份量的值。
 */
public final class NutritionCalculator {

    /** 单次录入的份量上限（克）。超过这个数几乎一定是录错了。 */
    public static final double MAX_GRAMS = 5_000;

    private NutritionCalculator() {
    }

    public static Nutrition forGrams(Nutrition per100g, double grams) {
        // NaN 会同时绕过 < 0 与 > MAX_GRAMS 两个判断，一路走到 Round.to2 变成 0.0
        // ——「一餐安静地显示 0 kcal」。这条校验与 Nutrition 紧凑构造器的口径一致。
        if (!Double.isFinite(grams)) {
            throw new IllegalArgumentException("grams 必须是有限值，实际为 " + grams);
        }
        if (grams < 0) {
            throw new IllegalArgumentException("grams 不能为负: " + grams);
        }
        if (grams > MAX_GRAMS) {
            throw new IllegalArgumentException(
                    "grams 超出上限 " + MAX_GRAMS + ": " + grams);
        }
        double k = grams / 100.0;
        return new Nutrition(
                Round.to2(per100g.kcal() * k),
                Round.to2(per100g.protein() * k),
                Round.to2(per100g.carb() * k),
                Round.to2(per100g.fat() * k),
                Round.to2(per100g.fiber() * k),
                Round.to2(per100g.sodium() * k));
    }

    /**
     * 合计若干份营养值。
     *
     * @param items 待合计的条目；**不得为 null，也不得含 null 元素**
     * @return 合计值；**空列表返回全 0**
     */
    public static Nutrition sum(List<Nutrition> items) {
        Objects.requireNonNull(items, "items 不能为 null");
        double kcal = 0, p = 0, c = 0, f = 0, fi = 0, na = 0;
        for (Nutrition n : items) {
            Objects.requireNonNull(n, "items 不能含 null 元素");
            kcal += n.kcal(); p += n.protein(); c += n.carb();
            f += n.fat();     fi += n.fiber();   na += n.sodium();
        }
        return new Nutrition(Round.to2(kcal), Round.to2(p), Round.to2(c),
                             Round.to2(f), Round.to2(fi), Round.to2(na));
    }
}
```

- [x] **Step 3b（质量审查轮新增）：抽 `Round.to2`、给 `Nutrition` 与 `sum` 补校验**

**为什么抽 `Round`**：本任务把 `round2` 写成 `NutritionCalculator` 的私有方法，
但 Task 7（训练容量）与 Task 8（1RM）面对的是同一个小数问题——
Task 8 的 `OneRepMax` fence 里已经是一份**逐字相同**的拷贝。
到 Task 8 时第三份拷贝会被当成「既有惯例」照抄，那时统一要同时改三个已验证过的类。
**成本窗口只在现在开着**，所以提前抽成包内可见的 `Round`。

> **再更正（2026-10-15，Task 7 质量审查轮）**：上面那句「Task 7 与 Task 8 面对的是
> 同一个小数问题」**只对 Task 8 成立**。全库 grep 实测：`VolumeCalculator` **一次都没调用**
> `Round`，`PortionConverter.toGrams` 还明确写了「定标属于显示层，不在这里做」
> （见 Task 6 的 C3-决定）。所以真实是**两处**（饮食 + 1RM），不是三处；
> 训练容量不需要舍入——它的值恒为 0.5 的整数倍，累加与比较都是精确的
> （见 `VolumeCalculator.accumulate` 的 `@return` 契约②）。
> 抽 `Round` 的决定本身**不受影响**（两份逐字拷贝仍然值得抽，且 Task 8 那份确实存在），
> 这里改的只是「几处」这个数字。

**为什么给 `Nutrition` 加不变量校验**（实测确认过的三个失效方式，全部**静默**）：

| 输入 | 改动前 | 改动后 |
| --- | --- | --- |
| `forGrams(new Nutrition(∞,…), 100)` | 返回 `9.223372036854776E16`（`Math.round` 饱和到 `Long.MAX_VALUE/100`） | 构造时抛 `IllegalArgumentException: kcal 必须是有限值，实际为 Infinity` |
| `new Nutrition(NaN, 0,0,0,0,0)` | **合法对象** | 抛 `IllegalArgumentException: kcal 必须是有限值，实际为 NaN` |
| `new Nutrition(-1, -1, -1, -1, -1, -1)` | **合法对象** | 抛 `IllegalArgumentException: kcal 不能为负，实际为 -1.0` |
| `sum([NaN 条目, 正常值])` | 返回 `kcal=0.0`——**一餐安静地显示 0 kcal** | NaN 条目在构造处就被拦下，压根进不了 `sum` |
| `sum(null)` | 裸 NPE：`Cannot invoke "java.util.List.iterator()" because "items" is null` | NPE：`items 不能为 null` |
| `sum([rice, null])` | 裸 NPE：`Cannot invoke "…Nutrition.kcal()" because "n" is null` | NPE：`items 不能含 null 元素` |
| `sum(List.of())` | 返回全 0，但**行为既没文档也没测试** | 返回全 0，且已在 javadoc 与测试里写死 |

**抽样验证（2026-10-15，质量审查轮）**：构造器拒绝矩阵 **14/14 拦下**
（六字段 × {NaN, -1}，加 kcal 的 ±∞）；合法值零误杀
（全 0、米饭基准值、一餐合计值 `414.0/21.9/44.85/14.45/1.45/303.0` 全部照常构造）。

**逐位不变性验证**：改动前后各跑一遍对照程序（反射调用私有 `round2` /
`forGrams` 的代表值），拿 double 的**十六进制位模式**逐行比：
`forGrams` 在 `0/5/100/150/225/1000/5000` 七个克数上 **8 行输出完全相同**，
拒绝路径（`-1/-0.0/5000.001/10000/NaN/∞`）**7 行完全相同**，
`sum([rice150, chicken])` 也完全相同。
`round2` 与 `Round.to2` 的共同输入 **6/6 位模式一致**（含 `NaN→0.0`、`∞→9.223372036854776E16`）。
`Round.to2` 的表达式与原来的 `round2` **逐字相同**（`Math.round(v * 100.0) / 100.0`），
所以 28 个既有测试与全部变异结论原样成立。

**为什么用 `Math.round`**：它按「四舍五入远离零」处理 `.5`，对负数对称
（`-1.005 → -1.01` 而非 `-1.00`）——注意实测值是 `round2(-1.005) = -1.0`
（`-1.005` 的 double 字面量略大于 `-1.005`，故落在 `-1.0` 一侧），
`-2.675 → -2.67`。营养与重量只关心量级，这个方向性无害。
**取舍已记录**：`Math.round` 返回 `long` 且饱和，超过约 `9.2e16` 会静默截断——
现在这条路径被 `Nutrition` 的有限性校验**在构造处**堵死，不再能流进统计。

**为什么 `Round` 用包内可见**：目前只有 `com.change.calc` 包内需要它。
将来的 service 层若需要，再提升可见性（而不是现在就 `public`）。

**命名约定（冻结，一行注释，不重命名）**：质量审查明确**不要**把 `Nutrition`
改名成 `NutritionPer100g`——`sum()` 返回的合计值没有 per-100g 基准，改名反而制造新的语义矛盾。
改为在 `NutritionCalculator` 的类注释里写死：
「凡持有 per-100g 基准值的变量/参数一律带 `per100` 前缀（`forGrams` 的参数已经这么做）。
没有该前缀的，就是实际份量的值。」

- [x] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=NutritionCalculatorTest`

Expected: `Tests run: 13, Failures: 0, Errors: 0`（8 + 质量审查轮新增 4 条 + 本轮 Minor-2 的 1 条）
（**本轮新增**：`rejects_non_finite_grams`——`forGrams` 补上有限性校验，关掉 Task 5 的欠账，见「遗留」。）

**实测（2026-10-15）**：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，
耗时 **0.115 s**——纯类、无 Spring 上下文，符合「`calc/` 不依赖框架」的设计意图
（对比：同一次全量里 `@SpringBootTest` 要 5.7 s）。

> **补记（2026-10-15，规格评审轮）**：上面这条 `Tests run: 6` 是补完 3 个覆盖缺口
> **之前**的实测记录。补完两个测试方法后复测为
> `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`。

> **再补记（2026-10-15，质量审查轮）**：补完 Step 3b 的 4 条校验测试后，
> `mvn -B test -Dtest=NutritionCalculatorTest` 实测
> **`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`**（8 + 4）。
> 4 条新增：`rejects_non_finite_and_negative_nutrition`、
> `gram_based_fields_are_validated_too`、`sum_of_empty_list_is_all_zero`、
> `sum_rejects_null_list_and_null_elements`。
> **既有 26 条断言行逐字未动**（用脚本对比 HEAD 版本与当前版本的断言行集合：
> HEAD 的 26 行全部原样保留，新增的 18 行全部来自上述 4 个新方法）。
>
> **三补记（2026-10-15，Task 6 质量审查放行轮）**：`forGrams` 补上有限性校验后，
> 该文件为 **13 条**（12 + `rejects_non_finite_grams`）。上面的 `Tests run: 12` 与
> M3′/M7′/M10′ 表里的 `Tests run: 12` 都是**当时那一次**的实测记录，**不改写历史**；
> 需要引用**当前**条数时用 13。

**Step 4b（原始计划没有，为回答「有没有恒真断言」而补做）：变异测试**

做法：每次只改实现里的一处（模拟手滑），跑测试，看哪些断言变红。去重后共 **13 个有效变异体**，
**13 杀 / 0 活**（补完下方 3 个覆盖缺口之后；补之前是 3 个存活：M3、M7、M10）。
两处不计入统计：**M6 是等价变异体**（见下方再更正），**M2 与 M4 是同一个变异体**（见下方三次更正）。

> **更正（2026-10-15）**：本段最初写的是「11 杀 / 6 活」，是我把两个版本的统计混在了一起：
> 第一轮只跑了 15 个变异体（11 杀 4 活）且失败用例名字解析有 bug，补上 M16/M17 后是
> **17 个变异体、13 杀、4 活**。上表的逐条结论两轮一致，只有这行汇总数错过，已改。

> **再更正（2026-10-15，规格评审轮）**：M6（`grams < 0` → `grams < -0.0`）**不是缺口，
> 是等价变异体**——IEEE-754 下 `-0.0 == 0.0`，两个比较对任何 double 都同真同假，
> 审查实测 19 个取值（含 `-0.0`、`NaN`、`±∞`、denormal、`MAX_VALUE`）结果全同，
> **没有任何测试能杀掉它**，它在变异集里承载零信息，故剔除。

> **三次更正（2026-10-15，规格评审轮）：M2 与 M4 其实是同一个变异体，必须去重。**
> 本表把 M2 记成「`round2` 的 `100.0` → `10.0`」——但 `round2` 的**方法体里有两个 `100.0`**
> （`Math.round(v * 100.0)` 的内层与被除数 `/ 100.0`），这句话没说改哪个。两种读法都试过：
> - 读法 A（改被除数）得到 `Math.round(v * 100.0) / 10.0` —— 与 M4 **逐字节相同**；
> - 读法 B（改内层因子）得到 `Math.round(v * 10.0) / 100.0`。
>
> 审查用真实编译产物实测：**两种读法对 18 条断言的杀手集合完全一致，也与 M4 完全一致**
> （同为 13 条变红）。也就是说按读法 A，M2 ≡ M4；按读法 B，M2 仍然与 M4 行为等价
> （对测试用到的这些输入，`round(10x)/100 == round(100x)/10` 恒成立）。
> 所以**变异集里 M2 与 M4 只能算一个**，去重后是 13 个有效变异体，不是 14 个。
> 这也解释了原表一个说不通的地方：它让 M2 去杀 `computes: carb`（第 20 行）、
> 又让 M4 只杀到第 18 行——**同一个变异体不可能既在第 18 行中止又杀到第 20 行**。

| 变异（只改一处） | 结果 |
| --- | --- |
| M1 `grams/100` → `grams/10` | 杀（3 条测试红） |
| M2 `round2` 的 `100.0` → `10.0` | **与 M4 是同一个变异体**（两种读法实测杀手集合都与 M4 全等，见上方三次更正；已去重） |
| M3 `round2` 精度改成三位 `*1000/1000` | **存活 —— 缺口 1**（补 `rounding_is_exactly_two_decimals` 后**被杀**） |
| M4 `Math.round(v*100.0)/10.0` | 杀（3 条） |
| M5 负数校验 `<` → `>`（条件永不触发） | 杀（4 条） |
| M6 `grams < 0` → `grams < -0.0` | **等价变异体**（不是缺口，见上方再更正；已剔出变异集） |
| M7 上限校验 `>` → `>=`（正好 5000 被误拒） | **存活 —— 缺口 2**（补 `accepts_exactly_the_upper_bound_and_rejects_just_above` 后**被杀**） |
| M8 上限校验 `>` → `<` | 杀（5 条） |
| M9 `MAX_GRAMS` 5000 → 50000 | 杀（`rejects_absurd_grams`） |
| M10 `sum` 里少累加 `carb` | **存活 —— 缺口 3**（补 `sums_a_meal` 的 4 行断言后**被杀**） |
| M11 `sum` 里 kcal/protein 互换累加 | 杀 |
| M12 `sum` 只返回首条 | 杀 |
| M13 `forGrams` 里 carb/protein 位置互换 | 杀（2 条） |
| M14 `Nutrition` record 参数顺序换 carb/protein | 杀 |
| M15 `forGrams` 不换算（`k = 1.0`） | 杀（4 条） |
| M16 `k` 硬编码成 `150/100.0` | 杀（2 条） |
| M17 把 225 映射回 150（两份量算出同一数） | 杀 `changing_portion_recomputes_from_scratch` |

**变异测试复验（2026-10-15，质量审查轮，抽取 `Round` 之后）**

抽 `Round` 会把 `round2` 从 `NutritionCalculator` 私有方法搬成另一个类的包内静态方法，
**风险是守卫被搬松**（比如搬的时候顺手改精度）。所以三个关键变异体重跑一遍：

| 变异体 | 改动 | 实测结果（删 `target` 后跑） | 红灯方法 |
| --- | --- | --- | --- |
| M3′ | `Round.to2` 精度改成三位 `*1000/1000` | **杀** —— `Tests run: 12, Failures: 1, Errors: 0`，`expected: 1.3  but was: 1.295` | `rounding_is_exactly_two_decimals` |
| M7′ | 上限校验 `>` 改 `>=` | **杀** —— `Tests run: 12, Failures: 0, Errors: 1`，`IllegalArgument grams 超出上限 5000.0: 5000.0` | `accepts_exactly_the_upper_bound_and_rejects_just_above` |
| M10′ | `sum` 不累加 `carb` | **杀** —— `Tests run: 12, Failures: 1, Errors: 0`，`expected: 44.85  but was: 0.0` | `sums_a_meal` |

**结论：抽取没有削弱守卫**，三个变异体全部仍被杀，且红灯落在预期的方法上。
每个变异体跑完后源文件用 sha256 逐字节校验已恢复（3/3 一致），恢复后全量回归 32/32 绿。

> **方法学提醒**：这轮替换源码用的是 Python（显式 UTF-8、bytes 级读写），
> **不是 PowerShell**——源码里有中文注释，PowerShell 按 GBK 读 UTF-8 会静默损坏文件。

**结论：原有 6 个测试方法里的每一条断言都不是恒真的**——每条都能指名一个让它变红的最小改动。
但变异测试同时暴露了 **3 个测试覆盖缺口**（是「漏测」，不是「恒真断言」）：

1. **`round2` 只被锁住了「精度不粗于两位」，没锁「不细于两位」**。
   把精度改成三位小数（M3）测试**全绿**，因为 `174.0 / 3.9 / 38.85 / 261.0 / 414.0 / 21.9`
   在三位小数精度下也是同一个 double。真正锁住精度的是 `38.85`。
   **已补**：`rounding_is_exactly_two_decimals`（`5 g` 米饭的碳水 `25.9 × 0.05 = 1.295`，
   两位给 `1.3`、三位给 `1.295`，故能杀掉 M3）。
2. **只测了「深处越界」，没测「边界本身」**：`10000`（越界 2 倍）与 `-1` 能红，
   但 `4999 / 5000 / 5000.001` 一个都没测，所以 `>` 写成 `>=`
   （把正好 5000 g 误拒）无人察觉。
   **已补**：`accepts_exactly_the_upper_bound_and_rejects_just_above`。
   ⚠️ 原稿把 M6（`grams < 0` → `grams < -0.0`）也算作本缺口的一部分，**这是错的**：
   IEEE-754 下 `-0.0 == 0.0`，两个比较对任何 double 都等价，**M6 是等价变异体，
   没有任何测试能杀掉它**，已从变异集剔除，不计入缺口。
3. **`sum` 有四个字段完全没被断言**：`sums_a_meal` 只断言了 `kcal` 与 `protein`，
   所以 `sum` 里 `carb / fat / fiber / sodium` 的累加整段删掉都不红（M10 存活）。
   **已补**：`sums_a_meal` 里补 4 行断言（`44.85 / 14.45 / 1.45 / 303.0`）。

- [x] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/calc/Nutrition.java \
        backend/src/main/java/com/change/calc/NutritionCalculator.java \
        backend/src/test/java/com/change/calc/NutritionCalculatorTest.java
git commit -m "feat: 热量计算（每 100 克基准 + 实时换算），含份量上限校验"
```

**Step 6（原始计划没有，本次任务要求补做）：全量回归**

Run: `cd backend && mvn -B clean test`

Expected（质量审查轮更新后）: `Tests run: 33, Failures: 0, Errors: 0`
（**本轮再更新**：Task 6 轮给 `forGrams` 补了 `rejects_non_finite_grams`，本任务贡献从 12 条变 13 条，
故 32 → 33。下面的 26 / 28 / 32 都是历次实测记录，保留原样。）

**实测（2026-10-15）**：`Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`——
**26 = 原 20 + 本任务 6**，与预期一致。六个测试类逐项：
`NutritionCalculatorTest` 6、`SchemaTest` 8、`MigrationTest` 5、
`DataDirectoryInitializerTest` 3、`ChangeApplicationTests` 2、`TrainingRepoTest` 2。

**复测（2026-10-15，规格评审轮补完 3 个覆盖缺口之后）**：
`Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`——
**28 = 原 20 + 本任务 8**（新增 `rounding_is_exactly_two_decimals` 与
`accepts_exactly_the_upper_bound_and_rejects_just_above` 两个方法，
另在 `sums_a_meal` 内补了 4 行断言）。逐项：
`NutritionCalculatorTest` 8、`SchemaTest` 8、`MigrationTest` 5、
`DataDirectoryInitializerTest` 3、`ChangeApplicationTests` 2、`TrainingRepoTest` 2。

**再复测（2026-10-15，质量审查轮补完 Step 3b 的 4 条校验测试之后）**：
删掉 `backend/target` 后 `mvn -B test` 实测
**`Tests run: 32, Failures: 0, Errors: 0, Skipped: 0`**——
**32 = 原 20 + 本任务 12**（8 + Step 3b 的 4 条）。逐项：
`NutritionCalculatorTest` **12**、`SchemaTest` 8、`MigrationTest` 5、
`DataDirectoryInitializerTest` 3、`ChangeApplicationTests` 2、`TrainingRepoTest` 2。
**新增的 4 条全是新方法，既有期望值一个都没改。**
（**⚠️ 这一条里的 `NutritionCalculatorTest` 12 与上面那条 Expected 的 32，是**当时**的记录；
Task 6 质量审查放行轮给它补了 `rejects_non_finite_grams`，所以当前是 **13** 与 **33**——
`Test 6` 的 Step 5 早就按 12 写了总数，是本文件内部两个数字对不上 45 的那处笔误的来源，
本轮一并按实测订正，见 Task 6 Step 5。）

### 期望值独立核算（不照抄，用 IEEE-754 逐位验证）

断言用的是 `isEqualTo(double)`，比的是**位模式**，不是「看起来相等」，所以逐条算了精确值：

| 算式 | 结果 | 说明 |
| --- | --- | --- |
| `116 × 150/100` | `174.0` ✅ | `150/100.0 = 1.5` 精确；乘积精确 |
| `2.6 × 1.5 = 3.9` | `3.9` ✅ | `2.6` 与 `3.9` 都不可精确表示。⚠️ **原稿这里写错了**：`2.6 × 1.5` 的**精确值**是 `3.89999999999999991118…`，但 **IEEE-754 乘法给出的是距精确值最近的 double `3.9000000000000004`**（`0x400f333333333334`），而 `double(3.9)` 是 `0x400f333333333333`——**差 1 个 ulp，并不相等**。**是 `round2` 把它拉回 `3.9` 的，不是「本来就落回」**：没有 `round2` 时 `protein()` 会是 `3.9000000000000004`，第 19 行立刻变红。**第二个覆盖缺口之外的实证**：变异体 X18（单独去掉 `protein` 的 `round2`）确实杀掉了测试，所以 `round2` 对 `protein` 同样必需 |
| `25.9 × 1.5 = 38.85` | `38.85` ✅ | 精确值 `38.84999999999999431…`，`×100 = 3884.9999999999995…`，`Math.round → 3885`，`/100` 落回 `double(38.85)`。**这一次 `round2` 不是锦上添花，是必需的**（不 round2 会得到 `38.849999999999994`） |
| `116 × 2.25 = 261.0` | `261.0` ✅ | `225/100.0 = 2.25` 精确 |
| `174.0 + 240 = 414.0` | `414.0` ✅ | 整数运算，精确 |
| `3.9 + 18 = 21.9` | `21.9` ✅ | IEEE-754 加法**按定义**就返回距精确和最近的 double，所以「结果看起来干净」是规范保证的行为，不是巧合。`3.9 + 18` 的精确和是 `21.89999999999999991118…`，而 `double(21.9)` 是 `21.89999999999999857891…`——两者不是同一个有理数，但加法返回的就是前者最近的 double，恰好是 `double(21.9)`。⚠️ **原稿把它标成「侥幸通过、边界只剩 1.3e-15」是误报，推理方向本身就错了**：它**漏看了 `sum` 末尾也调用 `round2`**。测试比较的是 `round2(3.9+18) = round2(21.899999999999999) = 21.9`，而 `round2` 会把约 ±0.005 宽的一整段值都吸附到 `double(21.9)`——**有效余量是 ~0.01，不是 1.3e-15**。该断言稳健是**结构性的**（`round2` 提供的余量），不是浮点巧合 |

**结论：6 个期望值全部算对，不需要改测试，也不需要改实现。**
（第 6 条原先记的「脆弱」标记已按上面的实测理由撤销；第 2 条的归因已改正。）
**补充（2026-10-15，规格评审轮）**：上表第 2 条的更正说明 `round2` 对 `protein` 是必需的——
这与第 1、3 条一起，构成「`forGrams` 的六个分量都必须 `round2`」的完整证据。

### 边界思考结论

**（1）`round2` 对负数与极大值的行为。**
`Math.round(v*100.0)/100.0` 在负数上是 **half-up 而非 half-even**，且方向朝 +∞：
`round2(-0.005) = 0.0`、`round2(-1.005) = -1.0`、`round2(-2.675) = -2.67`。
也就是说负数侧的「平局」会向零靠。这对本任务是**无害**的：`grams ≥ 0`，
`food` 表的营养值也应非负，`sum` 累加的同样是非负值，负数路径实际不可达。
真正的问题是**极大值**：`Math.round` 返回 `long` 且**饱和**，
`round2(1e17) = 9.223372036854776e16`、`round2(1e308)` 与 `round2(+∞)` 都塌到
`Long.MAX_VALUE/100`，`round2(NaN) = 0.0`。即**超过约 9.2e16 的输入会被静默截断**，
不抛异常。`sum` 的入参没有任何校验（`forGrams` 有，`sum` 没有），
所以如果将来有人把「一周总量」或异常数据喂进 `sum`，会得到静默的错误值而不是报错。
当前调用面（一餐 3–10 条，各 ≤5000 g）离这个量级有 12 个数量级，**不构成现实风险**；
建议在 Task 6 或汇总统计落地时复核。

**（2）`MAX_GRAMS = 5000` 是否合理。**
对「单次录入」合理：一顿饭单人份 5 kg 不可能。但要注意两件事：
① 这是**单条目上限**，不是单餐上限——一餐 5 条各 1 kg 的合计 5 kg 不受约束，
而 `sum` 也没有上限，所以「整餐上限」其实没设防；
② **批量录入确实会撞到**：一次记一周备餐（比如 7 份 × 800 g = 5600 g）如果被实现成
「一次调用 `forGrams` 传总克数」，就会误报。正确做法是**每份各调一次再 `sum`**，
这也正是 per-100g 铁律想要的使用方式。**建议**：把 `MAX_GRAMS` 的语义在注释里写死成
「单条目上限」，并在餐记录服务层另外设一个「单餐合计上限」。本次**不改**。

**（3）`Nutrition` 六个 `double` 的位置参数容易写错。**
确实容易：`new Nutrition(240, 18, 6, 14, 1, 300)` 光看数字读不出哪个是 fat、哪个是 fiber。
更防错的方案（**建议，本次不擅自改**，因为 record 的形状已被下游设计与迁移引用）：
- 用**静态工厂 + 具名参数**，或 builder；
- 至少给六个分量加**单位后缀**的意识；
- 最小成本的缓解：调用处一律用具名局部变量再传入。

⚠️ 规格评审时发现一个**真实的文档缺陷**：`Nutrition` 的类注释写「单位：kcal 与克」，
但 `sodium` 的取值 `2.0 / 300` 明显是**毫克**（300 mg 钠 ≈ 一份正餐），
按「克」读则 300 g 钠是致死量的上千倍。**注释与数据不符**。
**已修（2026-10-15，规格评审轮）**：注释已改为
「一份营养值。热量与三大营养素、纤维单位为克（kcal 除外）；钠为毫克。」，
计划 fence 与 `Nutrition.java` 同步（**只改注释，不改 record 形状**）。
（旁证：USDA 的 sodium 正是以 mg 记录。）

**（4）先 `round2` 再 `sum`，会不会有累积舍入误差？**
会，而且是**真实存在但很小**的偏差。逐条 `round2` 会把每条最多 0.005 的误差固定下来，
再累加，误差上界约 `条数 × 0.005`。实测（每条 0.005 kcal）：
`n=3` 差 `0.01`、`n=5` 差 `0.02`、`n=10` 差 `0.05`、`n=100` 差 `0.5`、`n=10000` 差 `50 kcal`。
**关键结论：这个差异在真实使用（一餐 3–10 条）里不重要。**
- 上界是 `n × 0.005 = 0.05 kcal`（n=10），相对一餐 400–800 kcal 是 **0.01% 量级**，
  远小于食物库本身的误差（USDA 数据自身有 5–10% 波动）。
- 用真实食物数据（5 条不同食物，含小数尾巴）实测：逐条舍入 `410.35`、
  先和后舍 `410.35`，**差 0**。因为 per-100g 值本身通常只到一位小数，
  `round2` 对它们多是恒等操作。
- 反过来，**先 `round2` 是对的**：它的价值在「显示与存储的一致性」——
  界面显示的每条就是这个数，合计必须等于这些显示值之和，否则用户会看到「加起来对不上」。
  为 0.01% 的理论精度去牺牲「显示可加性」是亏的。
- `sum` 末尾再 `round2` 一次是必要的：它把 double 累加的噪声（如 `0.1+0.2`）收掉。
  用精确算术（Decimal）验证过：在「分量已 `round2` 到两位」的前提下，
  「先舍后加」与「先加后舍」在真实取值范围内结果一致。

### 恒真断言逐条判断

**结论：没有恒真断言**——11 条断言的每一条都能指名「让它红」的最小改动，
且**该断言自身确实被实际执行并失败**（变异编号见 Step 4 的变异测试表）。

⚠️ **但原稿的论证方式本身是错的，下表已按实测重做**：原稿只记录了「哪个变异体让某个
*测试方法*变红」，就当成「该条断言变红」——而 JUnit 在方法内遇到第一条失败断言即中止，
方法里靠后的断言压根没跑到。重做后 **11 行里有 10 行的杀手名单是错的**：

| 断言行 | 原声称的杀手 | 实测杀手 | 错在哪 |
| --- | --- | --- | --- |
| `computes: kcal` | M1/M4/M15/M16 | **M1/M4/M15** | M16 落在 changing:31、zero:37 |
| `computes: protein` | M13/M14 | **M13** | M14 只杀 sums:62（fixture 的 protein/carb 同向互换互相抵消） |
| `computes: carb` | M2/M4 | **M2/M4**（同一变异体） | 在 fail-fast 口径下**两者都杀不到第 20 行**——它在第 18 行就中止（见下方独立复现） |
| `changing: oneBowl` | M1/M4/M15 | （保持不变，审查未标错） | — |
| `changing: oneAndHalf` | M1/M4/M15/M17 | **M16/M17** | 那三个在第 30 行就中止 |
| `isNotEqualTo` | M17 | **无** | 与同文档自述矛盾（见下方「多余吗」） |
| `zero: isZero` | M1/M4/M15 | **M8/M15/M16** | M1、M4 在 grams=0 时仍归零 |
| `rejects_negative` | M5/M8 | **M5** | M8 的 `grams < MAX_GRAMS` 对 -1 仍抛异常，测试照样通过 |
| `rejects_absurd` | （保持不变，审查确认正确） | — | — |
| `sums: kcal` | …/M16 | **去掉 M16** | M16 让 150g 结果不变，414.0 照样成立 |
| `sums: protein` | 「全部六个」 | **M13/M14** | 六个全错，真凶未列 |

> ⚠️ **方法学（这张表为什么被重做）**：原稿用「哪条断言能变红」来证明「无恒真断言」，
> 但它把**「某个变异体让整个测试方法变红」记成了「该条断言变红」**——忽略了
> **JUnit 在方法内遇到第一条失败断言就中止**，后面的断言根本没被执行到。
> 上表是审查逐条实测（把每条断言拆成独立用例、再用变异体逐个验证）后重做的：
> **每一行都是该断言自身被实际执行并失败**的记录，行号对应 `NutritionCalculatorTest`
> 的原文行号（`computes` 的三条在 18/19/20 行，`changing` 的三条在 30/31/32 行，
> `zero` 在 37 行，`sums` 的两条在 61/62 行）。

**独立复现（2026-10-15，修复轮）：上表逐行复核结果。**

复核方法：把 11 条断言拆成 11 个独立 JUnit 用例（同一 fixture、同一表达式、同一期望值，
只是每例一条断言），这样一条红断言不会遮住后面的断言；再对每个变异体读出
**每条断言自身**的真假。先用**未变异的**源码跑一遍作保真性检查：**11 条全绿**，
证明拆分没有改变断言语义。

结论：**上表 11 行里，10 行的「实测杀手」位置判得对**（唯一没被标错的是
`changing: oneBowl`）。个别行的杀手**名单**按更严的口径还能再收紧，逐条列在这里：

| 行 | 复核到的情况 |
| --- | --- |
| `computes: kcal` (18) | 除 M1/M4/M15 外，**M8** 也让它红。但要注意 M8 报的是 JUnit **error 而非断言失败**：`grams < MAX_GRAMS` 对 150 为真，于是在第 24 行抛出的是**上限**异常 `grams 超出上限 5000.0: 150.0`，第 18 行压根没跑到。这就是它被排除的原因——**「测试变红」有 failure 与 error 两种，不能被当成同一件事** |
| `computes: carb` (20) | 在 fail-fast 口径下**没有任何变异体能杀它**——M2/M4（同一变异体）在第 18 行、M13 在第 19 行就中止。它能红的路径只有「把 `Math.round(v*100)/100` 换成 `Math.round(v*100)/10` 这个外层因子」，而那恰好就是 M4 的行为，已被第 18 行截住 |
| `changing: oneBowl` (30) | M8 同样让它红（原因同上） |
| `zero: isZero` (37) | 复核为 **M8/M15**；M16 杀不到第 37 行（M16 的 `k` 对 `grams=0` 仍为 0，归零成立），**M16 应从上表该行去掉** |
| `computes: protein` (19) | 除 M13 外，审查新增的 **X18**（单独去掉 protein 的 `round2`）也让它红，这正是「`round2` 对 protein 必需」的实证 |
| `isNotEqualTo` (32) | 复核确认**无杀手**：它是同方法内第 3 条断言，前两条通过时它必然通过 |
| 新增 3 行 | `sums: carb`(64) 由 **M10** 唯一杀死；`rounding_is_exactly_two_decimals` 由 **M3** 杀死；`accepts_exactly_the_upper_bound…`(81) 由 **M7** 杀死 |

> 说明：这张复核表**保留审查原表的判断**（它已修正方法学错误），只补充复核到的名单差异；
> 分歧之处以「该断言在 fail-fast 下是否真的被执行并失败」为准。

**`isNotEqualTo(oneBowl.kcal())` 是多余的吗？——作为「检测手段」完全冗余，作为「文档/意图声明」有价值。**

证明（三段论即可）：该断言位于
`assertThat(oneBowl.kcal()).isEqualTo(174.0)` 与
`assertThat(oneAndHalf.kcal()).isEqualTo(261.0)` **之后**。若前两条都通过，
则 `oneAndHalf.kcal() == 261.0 != 174.0 == oneBowl.kcal()`，第三条**必然通过**。
反过来，第三条要能单独变红，就必须存在一种实现让「174.0 与 261.0 都成立、但两者相等」——
自相矛盾。**所以这条断言在任何实现下都不可能成为唯一的红灯**，实测也构不出反例：
M17 让两份量同值，第 31 行的 `oneAndHalf == 261.0` 先红，**第 32 行根本没被执行到**。

> **更正（2026-10-15，规格评审轮）**：原稿在这里声称它「是未来引入缓存时的哨兵」，
> 理由是「前两条 `isEqualTo` 可能会因为缓存里先存进去的是哪个值而**随机通过**」。
> **这个论据不成立**：审查按它自己描述的缓存（key 只含 `per100g`）模拟后发现，
> 测试里 **150 先被请求**，于是 225 的请求命中缓存、直接返回 `174.0`，
> **第 31 行必然、确定性地变红**——无论缓存里先存进去的是哪个值，
> 都是前一条 `isEqualTo` 先红，**不存在「随机通过」**。故该哨兵论据撤销。

**保留它的真实理由只有一条（文档/意图声明）**：
它锁的是「**函数真的依赖 `grams` 参数**」这条性质——前两条 `isEqualTo` 只锁了
「150→174、225→261」两个数据点，这条把意图写成了可读的断言。
**不要**再把它说成缓存哨兵。

**建议保留**（它是意图声明，不是检测手段）。本次仍**不改测试的期望值**，
只补了两个测试方法与 `sums_a_meal` 的 4 行断言。

> **补记（2026-10-15，Task 6 质量审查放行轮）**：本任务记在 Task 6「遗留」里的那条欠账
> **`forGrams(grams = NaN)` 放行 NaN**（`NaN < 0` 与 `NaN > MAX_GRAMS` 都是 `false`）
> **本轮已关**：`forGrams` 开头补了 `if (!Double.isFinite(grams)) throw …`
> （放在上限校验之前），配套测试 `rejects_non_finite_grams`。
> **只加这一条校验，`Round.to2` 与六个分量的换算都没碰。**
> 本任务 Step 4 / Step 5 的条数随之从 12 → 13、32 → 33。

---

## Task 6: 份量单位换算（核心）

**Files:**
- Create: `backend/src/main/java/com/change/calc/PortionConverter.java`
- Create: `backend/src/main/java/com/change/calc/package-info.java`（质量审查轮新增：把两条只活在示例里的包内约定写下来，见 F）
- Test: `backend/src/test/java/com/change/calc/PortionConverterTest.java`

- [x] **Step 1: 写失败的测试**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PortionConverterTest {

    private static final List<PortionConverter.Unit> RICE_UNITS = List.of(
            new PortionConverter.Unit("碗", 150),
            new PortionConverter.Unit("勺", 15));

    @Test
    void converts_bowl_to_grams() {
        assertThat(PortionConverter.toGrams(1, "碗", RICE_UNITS)).isEqualTo(150.0);
    }

    @Test
    void converts_fractional_units() {
        assertThat(PortionConverter.toGrams(1.5, "碗", RICE_UNITS)).isEqualTo(225.0);
    }

    @Test
    void converts_spoon_to_grams() {
        assertThat(PortionConverter.toGrams(2, "勺", RICE_UNITS)).isEqualTo(30.0);
    }

    @Test
    void null_unit_means_amount_is_already_grams() {
        assertThat(PortionConverter.toGrams(150, null, RICE_UNITS)).isEqualTo(150.0);
    }

    @Test
    void unknown_unit_is_rejected_with_a_useful_message() {
        assertThatThrownBy(() -> PortionConverter.toGrams(1, "碗半", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("碗半")
                // 拆成两条：断言整段提示文本（含全角冒号与空格）会跟实现措辞串在一起，
                // 改一个空格两边一起红。这里锁的是「列表出现过」与「列表内容与顺序」两件事。
                .hasMessageContaining("可用的单位")
                .hasMessageContaining("碗、勺");
    }

    @Test
    void three_entry_paths_yield_the_same_grams() {
        // 这是设计文档里点名的必测边界：三种录入路径结果必须一致
        double byBowl   = PortionConverter.toGrams(1, "碗", RICE_UNITS);       // 1 碗
        double bySpoon  = PortionConverter.toGrams(10, "勺", RICE_UNITS);      // 10 勺
        double byGram   = PortionConverter.toGrams(150, null, RICE_UNITS);     // 直接填克数

        assertThat(byBowl).isEqualTo(byGram);
        assertThat(bySpoon).isEqualTo(byGram);
    }

    @Test
    void decimal_amounts_agree_within_tolerance() {
        // 逐位相等只在「份量是 2 的幂」时成立：0.07 碗 × 150 = 10.500000000000002，
        // 而直接录 10.5 克得到 10.5——差 1 ulp。这里锁的是真实不变量（近似相等），
        // 逐位相等那条由 three_entry_paths_yield_the_same_grams 覆盖（它在整数份量下成立）。
        assertThat(PortionConverter.toGrams(0.07, "碗", RICE_UNITS))
                .isCloseTo(10.5, within(1e-9));
        assertThat(PortionConverter.toGrams(1.11, "勺", RICE_UNITS))
                .isCloseTo(16.65, within(1e-9));
    }

    @Test
    void rejects_conversions_above_the_single_entry_limit() {
        // 上游无上限、下游 forGrams 有 MAX_GRAMS=5000，缝隙会让
        // 「份量存得下、热量算不出来」的记录落库（meal_item 两头都收）。
        assertThat(PortionConverter.toGrams(33.33, "碗", RICE_UNITS)).isEqualTo(4999.5);
        assertThatThrownBy(() -> PortionConverter.toGrams(33.34, "碗", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5000");
    }

    @Test
    void index_maps_each_unit_name_to_its_grams() {
        Map<String, Double> idx = PortionConverter.index(RICE_UNITS);
        assertThat(idx).containsEntry("碗", 150.0).containsEntry("勺", 15.0);
    }

    @Test
    void rejects_non_finite_or_negative_amount() {
        // NaN < 0 是 false，所以不加有限性校验的话它会一路流到 forGrams：
        // 那里 Math.round(NaN) 是 0、Round.to2(NaN) 是 0.0——最终**静默显示 0 kcal**，
        // 比显示 NaN 更糟（NaN 一眼能看出坏了，0 看起来完全正常）。
        assertThatThrownBy(() -> PortionConverter.toGrams(Double.NaN, "碗", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> PortionConverter.toGrams(Double.POSITIVE_INFINITY, "碗", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> PortionConverter.toGrams(-1, "碗", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
    }

    @Test
    void rejects_invalid_unit_definitions() {
        assertThatThrownBy(() -> new PortionConverter.Unit("碗", 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
        assertThatThrownBy(() -> new PortionConverter.Unit("碗", -150))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
        assertThatThrownBy(() -> new PortionConverter.Unit("碗", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grams");
        assertThatThrownBy(() -> new PortionConverter.Unit("  ", 150))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("单位名");
    }

    @Test
    void blank_unit_is_treated_as_grams() {
        // unit 为 null、空串、纯空白都表示「amount 本身就是克数」
        assertThat(PortionConverter.toGrams(150, "", RICE_UNITS)).isEqualTo(150.0);
        assertThat(PortionConverter.toGrams(150, "   ", RICE_UNITS)).isEqualTo(150.0);
    }

    @Test
    void index_rejects_null_list() {
        assertThatThrownBy(() -> PortionConverter.index(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("units");
    }

    @Test
    void rejects_zero_amount_aligning_with_schema() {
        // meal_item.amount 是 CHECK (amount > 0)，0 克份量的记录注定写不进去；
        // 让 toGrams 当它合法只会把失败推迟到落库那一刻。Unit.grams 用的是同一个口径。
        assertThatThrownBy(() -> PortionConverter.toGrams(0, "碗", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
    }

    @Test
    void index_rejects_duplicate_unit_names() {
        // 数据库的 ux_food_portion 只在 (food_id, unit_name) 上唯一；
        // 混进别的食物的单位时静默取首个会算出看起来合理的错值。
        assertThatThrownBy(() -> PortionConverter.index(List.of(
                new PortionConverter.Unit("碗", 150),
                new PortionConverter.Unit("碗", 200))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("碗")
                .hasMessageContaining("150")
                .hasMessageContaining("200");
    }
}
```

- [x] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=PortionConverterTest`

Expected: 编译失败，`cannot find symbol: class PortionConverter`

- [x] **Step 3: 写实现**

```java
package com.change.calc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 份量单位换算：「1 碗」折成多少克。
 *
 * 换算表存在 food_portion 表里（每食物一套单位），本类只负责查表与计算。
 */
public final class PortionConverter {

    /**
     * 某食物上的一个份量单位。
     *
     * 这里校验的是**值**：名字非空、grams 有限且为正。food_portion 表上本来就有
     * {@code unit_name TEXT NOT NULL} 与 {@code grams REAL NOT NULL CHECK (grams > 0)}，
     * 所以 DB 层存不进下面这几种值——这道校验只是把 Java 侧对齐到既有 schema：
     * DB 愿意接受的每一行，{@code Unit} 也都接受；反过来，被拦下的值在 DB 侧同样进不去。
     *
     * 它**不校验名字唯一性**：同一个食物的单位名唯一由
     * {@code UNIQUE INDEX ux_food_portion(food_id, unit_name)} 保证，而那个约束管不到
     * 手工拼出来的列表（{@code new Unit("碗", 150)} 与 {@code new Unit("碗", 200)} 都能构造成功）。
     * 所以重复名由 {@link #index} 在检出时抛异常作为第二道防线——而不是静默取首个。
     */
    public record Unit(String name, double grams) {

        public Unit {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("单位名不能为空");
            }
            if (!Double.isFinite(grams)) {
                throw new IllegalArgumentException("grams 必须是有限值，实际为 " + grams);
            }
            if (grams <= 0) {
                throw new IllegalArgumentException("grams 必须为正，实际为 " + grams);
            }
        }
    }

    private PortionConverter() {
    }

    /**
     * 把份量折成克数。
     *
     * 返回的是**未定标的原始乘积**：定标（两位小数）属于显示层，不在这里做——
     * 先舍再累加会引入有方向性的系统偏差，而且会让「份量录入」与「直接填克数」
     * 两条路径显示的热量不再一致（见 package-info 的约定二）。
     *
     * @param amount 份量数值；必须为正——{@code meal_item.amount} 是
     *               {@code CHECK (amount > 0)}，0 克份量的记录注定写不进去，
     *               在这里拒绝它只是把失败提前到录入那一刻
     * @param unit   单位名；传 null（或空串/纯空白）表示 amount 本身就是克数
     * @param units  该食物的份量单位表；仅当 unit 需要查表时使用
     * @return 克数；上界与 {@link NutritionCalculator#forGrams} 共用
     *         {@link NutritionCalculator#MAX_GRAMS}，上游放行、下游拒绝的缝隙会
     *         产生「份量存得下、热量算不出来」的记录
     */
    public static double toGrams(double amount, String unit, List<Unit> units) {
        // NaN < 0 是 false，所以少了这条有限性校验的话 NaN 会一路流到 forGrams：
        // 那里 Math.round(NaN) 是 0、Round.to2(NaN) 是 0.0，最终**静默显示 0 kcal**。
        // 这比显示 NaN 更糟——NaN 一眼能看出坏了，0 看起来完全正常。
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount 必须是有限值，实际为 " + amount);
        }
        // 对齐 schema：meal_item.amount 是 CHECK (amount > 0)，与 Unit.grams 的 grams <= 0 同一口径。
        if (amount <= 0) {
            throw new IllegalArgumentException("amount 必须为正: " + amount);
        }
        if (unit == null || unit.isBlank()) {
            return amount;
        }
        Map<String, Double> idx = index(units);
        Double gramsPerUnit = idx.get(unit);
        if (gramsPerUnit == null) {
            throw new IllegalArgumentException(
                    "未知的份量单位「" + unit + "」；该食物可用的单位: "
                            + String.join("、", idx.keySet()));
        }
        double grams = amount * gramsPerUnit;
        if (!(grams <= NutritionCalculator.MAX_GRAMS)) {
            // 写成 !(grams <= MAX) 而不是 grams > MAX，是因为 NaN 与 > 比较恒为 false。
            // 本类已有的 NaN 处理用的是同一个套路。
            throw new IllegalArgumentException(
                    "换算后的克数超出单次录入上限 " + NutritionCalculator.MAX_GRAMS + ": " + grams);
        }
        return grams;
    }

    /**
     * 把份量单位表转成「单位名 → 每单位克数」的索引。
     *
     * 单位名重复时**抛异常**而不是静默取首个：数据库侧的 ux_food_portion
     * 只在 (food_id, unit_name) 上唯一，而本方法收到的是调用方拼的列表——
     * 如果混进了别的食物的单位，静默取首个会让 toGrams 按错误的克数换算，
     * 而调用方无从察觉。让它在拼错时就响，比让它算出一个看起来合理的值更划算。
     */
    public static Map<String, Double> index(List<Unit> units) {
        Objects.requireNonNull(units, "units 不能为 null");
        Map<String, Double> out = new LinkedHashMap<>();
        for (Unit u : units) {
            Objects.requireNonNull(u, "units 不能含 null 元素");
            Double prev = out.putIfAbsent(u.name(), u.grams());
            if (prev != null) {
                throw new IllegalArgumentException(
                        "份量单位名重复：「" + u.name() + "」既是 " + prev + " 克又是 " + u.grams() + " 克");
            }
        }
        return out;
    }
}
```

- [x] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=PortionConverterTest`

Expected: `Tests run: 16, Failures: 0, Errors: 0`
（原计划是 7；质量审查轮补了 A4 的 4 条校验测试：`rejects_non_finite_or_negative_amount`、
`rejects_invalid_unit_definitions`、`blank_unit_is_treated_as_grams`、`index_rejects_null_list`；
修恒真断言时又补了 2 条：`decimal_amounts_agree_within_tolerance`、
`index_rejects_duplicate_unit_names`；本轮关放行条件与 3 条 Minor 时再补 3 条：
`rejects_conversions_above_the_single_entry_limit`（放行条件 1）、
`rejects_zero_amount_aligning_with_schema`（Minor-3）、
以及把 `unknown_unit_is_rejected_with_a_useful_message` 的整段断言拆成两条（Minor-4b，条数不变）。共 7 → 11 → 13 → 16。）

- [x] **Step 5: 全量回归**

Run: `cd backend && mvn -B test`

Expected: **`Tests run: 48, Failures: 0, Errors: 0`**（原 32 + 本任务 16）。
逐项应为：`NutritionCalculatorTest` 13、`PortionConverterTest` 16、
`SchemaTest` 8、`MigrationTest` 5、`DataDirectoryInitializerTest` 3、
`ChangeApplicationTests` 2、`TrainingRepoTest` 2。
（**本轮实际为 48，而不是任务书写明的 49**：那个 49 是按「`NutritionCalculatorTest` 原本 12 条」
推的，实测该文件在本轮之前就已经是 13 条——所以 45 = 13 + 12 + 8 + 5 + 3 + 2 + 2，
新增 4 条后是 13 + 16 + 8 + 5 + 3 + 2 + 2 = **48**。以 `mvn` 的实际输出为准。）

- [x] **Step 6: 提交**

```bash
git add backend/src/main/java/com/change/calc backend/src/test/java/com/change/calc
git commit -m "feat: 份量单位换算，含「三种录入路径结果一致」的边界测试"
```

### A. 校验标准（与 Task 5 同一套，本轮已落地）

按 Task 5 确立的标准，第一轮在 `PortionConverter` 上补了 4 处（均为**新增**，未改任何既有期望值）；
修恒真断言轮又补了 2 条测试、改了 1 条既有断言与 3 处注释，并按下文审查实测修正了 A1/A2 的说明；
**本轮（质量审查放行）**再补 2 处：`toGrams` 的乘积上界（**放行条件 1**，见 A2-补）与
`amount` 口径从 `>= 0` 放行收紧成 `<= 0` 抛（**Minor-3**，见 A2 的边界）：

| # | 位置 | 加的是什么 | 为什么 |
| --- | --- | --- | --- |
| A1 | `Unit` 紧凑构造器 | `name` 非空、`grams` 有限且 `> 0` | 把对象层对齐到 `food_portion` 的既有 schema（`unit_name NOT NULL`、`grams REAL NOT NULL CHECK (grams > 0)`）。**它校验的是值，不校验名字唯一性**——名字唯一由 DB 的 `ux_food_portion` 保证，而那个约束管不到手工拼的列表，所以 Java 侧的第二道防线是本轮新加的「`index()` 检出重复即抛异常」 |
| A2 | `toGrams` 的 `amount` | `Double.isFinite` + **正数**（`amount <= 0` 即抛） | `NaN < 0` 是 `false`，不拦则一路流到 `forGrams`：`Math.round(NaN)` 是 `0` ⇒ `Round.to2(NaN)` 是 `0.0`，最终**静默显示 0 kcal**（比显示 NaN 更糟，见下）。正数口径对齐 `meal_item.amount CHECK (amount > 0)`，与 `Unit.grams` 的 `grams <= 0` 同一套 |
| A2-补 | `toGrams` 的**乘积** | `!(grams <= NutritionCalculator.MAX_GRAMS)` 即抛，**与下游共用同一个常量** | **放行条件 1**：上游原本只有下界，下游 `forGrams` 有 `MAX_GRAMS = 5000`，中间那条缝隙能让「份量存得下、热量算不出来」的记录落库（`meal_item` 两头都收，见下） |
| A3 | `index` 的 `units` | `Objects.requireNonNull` | 与 `sum(items)` 同一套写法 |
| A4 | 测试 | 第一轮 4 条 + 修断言轮 2 条 + 本轮 3 条（见 Step 1） | 单测 7 → 11 → 13 → 16，全量 32 → 43 → 45 → 48 |

**A1 不挡合法场景的第一个证据**：`food_portion.grams` 上本来就有 `CHECK (grams > 0)` 与 `NOT NULL`，
所以「某个单位暂时没称过、先记 0 克」在 **DB 层就已经不可能**。Java 侧这道校验是把对象层对齐到既有 schema，
不是新增限制——DB 愿意接受的每一行，`Unit` 也都接受。

**A2 的边界（本轮 Minor-3 已改）**：原实现在 `amount` 上是 `>= 0` 放行，即
`toGrams(0, "碗", units) = 0.0` 走得通，而 `meal_item.amount` 是 `CHECK (amount > 0)`，
落库时必然被拒——**同一层校验两种口径**，且把失败推迟到落库那一刻。
**本轮改为 `amount <= 0` 即抛**（`amount 必须为正: 0.0`），与 `Unit.grams` 的
`grams <= 0` 同理、同口径：DB 愿意接受的 `amount`，`toGrams` 都接受；被 `toGrams` 拦下的值在 DB 侧同样进不去。
`toGrams(150, null, units)` 这类正常路径不受影响（`150 > 0`）。
**没有「先占位、稍后填量」的合法场景被挡**：`meal_item.amount` 是 `NOT NULL CHECK (amount > 0)`，
这样的草稿行本来就写不进库（与 A1 拒掉 `grams = 0` 是同一条理由）。

**A2 的真实价值（审查实测，本轮按实测口径下调）**——它挡的是「**NaN 静默变 0 kcal**」那一半：

| 实测项 | 结果 |
| --- | --- |
| DB 对 `NaN` | **REJECTED**：`NOT NULL constraint failed: meal_item.grams`（SQLite 把 NaN 存成 NULL） |
| DB 对 `+Infinity` | **ACCEPTED**，落库为 `Inf`（`CHECK (grams > 0)` 判 `+inf > 0` 为真，放行） |
| `forGrams(RICE, Infinity)` | **REJECTED**：`grams 超出上限 5000.0: Infinity` |

**「DB 拒 NaN 却放行 +Infinity」这条静态对比仍然复现，但它撑不起原先那个结论**，两点修正：

1. **持久化层看不出差别**：SQLite 把 `10.500000000000002` 序列化成 `10.5`（15 位有效数字），
   `+Infinity` 写进去读出来是 `Inf`。所以「意图相同的记录存了不同克数」这条**只在位模式上成立**，
   在任何现实读取侧都不成立。DB 对这两个值的不同待遇，不是「A2 补上了一格」的证据。
2. **`Infinity` 到不了显示层**：`MAX_GRAMS` 那条校验**意外兼任了有限性校验**——实测
   `forGrams(RICE, Infinity)` 抛 `grams 超出上限 5000.0: Infinity`。所以原先那句
   「一个无限大的份量会顺利落库，再以 `inf` 的形式变成热量显示出来」**是错的**：它落得进库，
   但显示那一侧会先抛。

**所以 A2 真正守住的是另一半**：`NaN` 经 `Math.round(NaN)` ⇒ `Round.to2(NaN)` = `0.0`，
即「**一餐安静地显示 0 kcal**」——这一半是真的，DB 也拦不住（`sum` / `forGrams` 的直接调用方
根本不经过 DB），而且**失效方式是静默的**（0 看起来完全正常，NaN 一眼能看出坏了）。
**原先把它拔高成「堵住了 ∞ 落库后变成热量显示」是把价值放错了地方**，本轮按实测口径改正。

**A2-补：缝隙是「静默的持久化缺陷」，不是当前故障——本轮已堵**（放行条件 1）。
实跑（`RICE_UNITS` = 碗 150 / 勺 15）：

| 输入 | 换算后克数 | 本轮之前 | 本轮之后 |
| --- | --- | --- | --- |
| `toGrams(33.33, "碗", units)` | `4999.5` | 放行 | 放行（`isEqualTo(4999.5)` 锁住边界内侧） |
| `toGrams(33.34, "碗", units)` | `5001.000000000001` | **放行 ⇒ 下游 `forGrams` 拒绝** | **上游即抛**：`换算后的克数超出单次录入上限 5000.0: 5001.000000000001` |
| `toGrams(1e307, "碗", units)` | `Infinity`（乘法溢出） | 放行 ⇒ 落库为 `Inf` | **上游即抛**：`…上限 5000.0: Infinity` |

**为什么是「真故障缝隙」而不是纸面担忧**：`meal_item` 的 schema 两头都收——
`gram` 侧 `grams REAL NOT NULL CHECK (grams > 0)` 对 `5001` 无异议、`amount REAL NOT NULL CHECK (amount > 0)`
对 `33.34` 也无异议（审查用 V4 原文 DDL 实跑确认 `ACCEPTED`）。于是这条记录**可以被持久化**；
而铁律是「热量永远实时算」，读取路径**只有** `forGrams` 一条、没有 kcal 快照兜底，
一抛就是整个餐记录页打不开。当前全仓无生产调用方，所以它是**潜伏**而非现存故障——
但那正是要在有调用方之前堵上的原因。上表第三行（溢出成 `Infinity`）是同一个缝隙的另一半，
原先在 A2 末尾被记为「超出本任务范围」，本轮一并堵上。

**顺带纠正一处写错的后果**：原文写「NaN 会一路流成**热量显示 NaN**」，实测**是错的**——
`forGrams(per100, NaN)` 返回**全 0**（`Round.to2(NaN)` = `Math.round(NaN * 100.0) / 100.0` = `0 / 100.0` = `0.0`）。
所以真实后果是「**静默显示 0 kcal**」：NaN 一眼能看出坏了，0 看起来完全正常，**比 NaN 更糟**。
这条只改文字，校验本身（`Double.isFinite`）不变。

### B. 与 V3 schema 的交叉验证（读实际迁移文件，非推理）

`backend/src/main/resources/db/migration/V3__nutrition.sql` 第 33–39 行原文：

```sql
CREATE TABLE food_portion (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    food_id   INTEGER NOT NULL REFERENCES food(id) ON DELETE CASCADE,
    unit_name TEXT    NOT NULL,        -- 碗 / 个 / 勺 / 份
    grams     REAL    NOT NULL CHECK (grams > 0)
);
CREATE UNIQUE INDEX ux_food_portion ON food_portion(food_id, unit_name);
```

1. **`grams` 的 CHECK 是 `grams > 0`**（严格大于，不是 `>= 0`）。`Unit` 用的是 `grams <= 0` 就抛——
   两者**一致**：`0` 与负数在两侧都被拒，正数在两侧都被接受。若 Java 写成 `grams < 0` 就会不一致
   （DB 拒 0 而 Java 收 0），本实现没有这个问题。
2. **`unit_name` 有唯一性约束**，而且是复合唯一：`UNIQUE INDEX ux_food_portion ON food_portion(food_id, unit_name)`
   ——是 `(food_id, unit_name)` 而不是 `unit_name` 单列。**这个区别很关键**：不同食物当然都可以有「碗」
   （米饭的碗和面条的碗克数不同），只有在**同一个 `food_id` 内**单位名才唯一。
3. **是——这两个约束让「同一批 DB 行里不会出现两个 `"碗"`」成立。** `index(units)` 拿到的是**同一个食物**的若干
   `food_portion` 行，而 `(food_id, unit_name)` 已唯一，所以从 DB 读出来的行不可能重复。
   **但「重复单位名」这条路径并非不可达**：`index()` 收到的是调用方拼的列表，而 A1 只校验**值**、
   不校验名字唯一性——`new Unit("碗", 150)` 与 `new Unit("碗", 200)` 都是完全合法的对象，拼在一起就走到这条分支。
   审查实测：原实现的合并函数 `(a, b) -> a` 在这种输入下**静默返回 `{碗=150.0}`**，
   把调用方明明传进来的 `200.0` 丢掉，`toGrams(1, "碗")` 于是按 150 换算且毫无提示——
   这正是 A1 的 Javadoc 当初声称要避免的事，**它并没有做到**（A1 管不到重复名）。
   **本轮改法**：`index()` 检出重复即抛 `IllegalArgumentException`，消息同时给出两个克数
   （「份量单位名重复：「碗」既是 150.0 克又是 200.0 克」），作为 DB 唯一索引之外的第二道防线；
   正常单食物列表不受影响（`RICE_UNITS` 照常 index 成功）。实测对照见下方 D 的专项回答。

### C. 「三种录入路径一致」这条边界的稳健性（实测，非推理）

实测环境：本机 JDK 25，用 `Double.doubleToRawLongBits` 逐位比较（`isEqualTo(double)` 比的就是位模式）。

**C1：本测试选的三个值确实逐位相同——但那是「选对了值」，不是结构保证。**

| 路径 | 算式 | 位模式 | 精确值 |
| --- | --- | --- | --- |
| `1 碗` | `1 * 150.0` | `0x4062C00000000000` | 150 |
| `10 勺` | `10 * 15.0` | `0x4062C00000000000` | 150 |
| `150 克` | `150.0` | `0x4062C00000000000` | 150 |

三条全等的原因是**整数运算精确**：`150` 与 `15` 都是小整数（精确），`1`、`10` 也是整数，
IEEE-754 的乘法在积可精确表示时返回精确值。穷举 `1..20 × 1..20` 的整数乘法，**不一致组数 = 0**。

**C2：换一组同样合法的值就红——这条测试是脆弱的（fragile），只是恰好选了好值。**

反例（同为合法换算表，物理真值都是 0.3 克）：

| 路径 | 算式 | 位模式 | 精确值 |
| --- | --- | --- | --- |
| `1 碗`（碗=3） | `1 * 3.0` | `0x4008000000000000` | 3（**物理真值不对，见下**） |
| `3 勺`（勺=0.1） | `3 * 0.1` | `0x3FD3333333333334` | 0.3000000000000000444089209850062616169452667236328125 |
| `0.3 克` | `0.3` | `0x3FD3333333333333` | 0.299999999999999988897769753748434595763683319091796875 |

`3 勺` 与 `0.3 克` **差整整 1 个 ulp**，`isEqualTo` 会红。原因是 `0.1` 不可精确表示，
`3 * double(0.1)` 舍入到 `nextUp(0.3)`。

⚠️ **顺带纠正一个我原本的猜想**：我曾以为 `1 * 0.1 != 0.1`。**实测是相等的**——
`toGrams(1, "碗")` 与 `toGrams(0.1, null)` 都是 `0x3FB999999999999A`。
所以「乘以 1 就必然安全」是错的，不安全的是**非 2 的幂、且乘积需要舍入**的那些组合（如 `3 * 0.1`）。

**这条测试是脆弱的还是恰好选了好值？** 两者都对。但原文「脆弱性在当前数据下不可达」**是错的**：
审查用**同一张 150/15 表**、**未改动的原版实现**，只把份量换成合法小数就跑红了：

| 路径 | 算式 | 结果 | 直接录等值克数 | 判定 |
| --- | --- | --- | --- | --- |
| `0.07 碗` | `0.07 * 150.0` | `10.500000000000002` | `10.5` | **差 1 ulp** |
| `1.11 碗` | `1.11 * 150.0` | `166.50000000000003` | `166.5` | **差 1 ulp** |
| `1.11 勺` | `1.11 * 15.0` | `16.650000000000002` | `16.65` | **差 1 ulp** |
| `1 碗` | `1 * 150.0` | `150.0` | `150` | 相同（原测试用的正是它） |
| `10 勺` | `10 * 15.0` | `150.0` | `150` | 相同 |
| `150 克` | —（直接填克数） | `150.0` | `150` | 相同 |

**原文把触发条件归因错了**：它说安全性来自「克数是整数」，但上表第一行用的就是整数克数 `150`——
只把份量换成 `0.07`，立刻差 1 ulp。真正的结构性条件在**份量**这一侧：
份量若不是 2 的幂（不可精确表示），乘积就可能与「十进制精确值」差 1 ulp，
而**克数是不是整数完全不影响这件事**。（反过来，`0.5`、`0.25` 这类 2 的幂份量在实测样本里都是精确一致的，
`0.07 * 15` 也恰好一致——所以这是**逐值巧合**，巧合的概率如下表。）

比例（审查实测；判据 = `amount * g` 与「十进制精确乘积的最近 double」是否逐位相等，
份量按小数位穷举：1 位小数取 `0.1..3.0`，2 位小数取 `0.01..3.00`）：

| 换算表 | 1 位小数份量（30 组） | 2 位小数份量（300 组） |
| --- | --- | --- |
| 150 g/碗 | 0 / 30 = 0% | **40 / 300 = 13.3%** |
| 15 g/勺 | 0 / 30 = 0% | **78 / 300 = 26.0%** |
| 200 g/个 | **3 / 30 = 10.0%** | 35 / 300 = 11.7% |
| 非整数克数表（0.1 / 0.5 / 1.5 / 2.5 / 3.3 / 7.7） | **55 / 180 = 30.6%** | — |

也就是说：在这张**真实存在**的换算表上，两位小数份量下每 7 次换算就有 1 次以上与「直接填等值克数」
差 1 ulp（`200 g/个` 在一位小数下就已经 10.0%）。原实现与换算表都没错，
**错的是「当前数据下不可达」这个判断——它是可达的，而且在常见数据上并不罕见。**

**结论：这不是「结构性稳健」，是「数据碰巧稳健」——而那份「碰巧」比原文以为的窄得多。**

**C3：不要往 `toGrams` 里加舍入；定标只落在显示层——这是本轮定下的决定，不是待办。**

- **不该在 `toGrams` 加舍入**：它返回的是「克数」这个**物理量**，还会被 `sum` 之类聚合。
  先舍到 2 位再累加会引入**系统性偏差**（每餐 5 条各差 ±0.005，方向可能一致）。
  单位换算本身没有「精度损失」需要掩盖——`1 碗 = 150 g` 是精确的。
- **`forGrams` 确实在做两位小数舍入**（`Round.to2`），但它**吞不掉所有的 1 ulp 差**。
  原文那条「20000 组全部同值」的结论**依赖一个没写明的 `per100.kcal`，而且不可推广**。
  审查按食物逐个实测（相邻 double 克数 `g` 与 `nextUp(g)`；口径 `g = i/100`，`i = 1..20000`，
  即 `0.01..200.00` 共 20000 组；判据 = 与 `forGrams` 同构的
  `Round.to2(kcal * (g / 100.0))` 与 `Round.to2(kcal * (nextUp(g) / 100.0))` 是否同值）：

  | `per100.kcal` | kcal 不一致组数 | 占比 |
  | --- | --- | --- |
  | 116.0 | 0 | 0.00% |
  | 52.0 | 0 | 0.00% |
  | **150.0** | **1725** | **8.63%** ← 很常见的值 |
  | 165.0 | 178 | 0.89% |
  | 130.0 | 301 | 1.51% |

  所以正确表述是「**只对部分食物成立**」：`116`/`52` 这类值上确实 20000 组全同值，
  但在 `150 kcal/100g` 这种常见值上就有 **8.63%** 的相邻 double 对被舍入放大成不同的 kcal。
  （`Round.to2(3 * 0.1) == Round.to2(0.3) == 0.3` 这条本身没错，但它是**单点样本**，不能当普适结论用。）
- ⚠️ **但这仍然不等于「该在 `toGrams` 里加舍入」**：`forGrams` 的 `Round.to2` 只吸附落在一个**舍入区间**
  里的噪声。若两种录法算出的克数恰好骑在 `x.xx5` 边界的两侧，0.01 的跳变照样会发生。
  实测里 0.3 克正好是这种边界值（`116 * 0.3 / 100` 精确等于 `0.348`，落在 `round(34.8)` 的平局附近），
  所以我在验证时确实看到了两条路径给出 `0.35` 而第三条给出 `3.48` 的差异——
  **那不是 1 ulp 的问题，是 `0.3 g` 与 `3 g` 本来就是不同的量**（见上表 `1 碗` 那一行）。

#### C3-决定：克数**只在显示层定标**（本条取代原先「记为待办」的写法）

原文把「定标」记成留给 Task 4 / Task 9 的**待办**，那是**放行条件 2** 指出的问题：
留下待办，Task 9 的实现者就会照做，而**照做有害**。下面是审查实测的三条依据与本轮定下的决定。

**实测依据**

| 实测项 | 结果 |
| --- | --- |
| SQLite 把 `10.500000000000002` 写成文本 | `10.5`（15 位有效数字把 1 ulp 抹平） |
| 给克数定标后 kcal 发生变化的频率 | **5.10%–32.67%**（150 kcal/100g 上 **22.17%**） |
| 100 万行按克求和累积的噪声 | **1.86e-9 g**（顶到第 2 位小数需约 10¹⁴ 行） |

**决定**

- **(a) `toGrams` 返回前定标 —— 最坏。** 会把整个包的算术口径从「精确」降成「两位」；
  `sum` 之类聚合要的是原始量，**先舍再累加是有方向性的系统偏差**。
- **(b) 落库前定标（服务层）—— 看似合理，实测有害。** 会把用户**真实录入的量**移动最多 `0.005 g`，
  并在上面那个 **5.10%–32.67%** 的情形里把 kcal 一起移动 `0.01`。
  更糟的是它**制造**不一致：写进库的 `grams` 与页面当场显示的热量来自**不同的数**
  （库 `10.5`、页面按 `10.500000000000002` 算）——本来是「同一个数分两次算」，定标把它变成「两个数各算各的」。
- **(c) 只在显示层定标 —— 唯一无代价的选项，本轮选定它。**
  定标要解决的**唯一症状**是格式：`Double.toString(10.500000000000002)` 会渲染成
  `"10.500000000000002 g"`（本轮实测确认，见下），那是**格式化层**的事。
  **给 Task 9 实现者的指令**：在渲染层（DTO / 前端格式化）统一格式化克数，
  **不要**在服务层往 `meal_item.grams` 里写定标后的值。

**「意图相同的记录存了不同克数」实测无害**，四条理由：

1. **持久化层自己就抹平了**：SQLite 用 15 位有效数字把 `10.500000000000002` 序列化成 `10.5`，
   而且**任何**从库里读回的计算只可能拿到被抹平的那个数——「存了不同克数」在现实读取侧不存在。
2. **按克求和**：100 万行累积噪声 `1.86e-9 g`，要顶到第 2 位小数需要约 10¹⁴ 行。
3. **显示**：两位格式化直接吃掉。
4. **唯一敏感路径是 `forGrams`，而它用同一个原始 double 算**，所以三条录入路径显示**同一个** kcal
   （实测 `0.07 碗` 与 `10.5 克` 都渲染 `15.75`）——**定标反而会打破这个一致性**。

**本轮实测（格式症状确实存在，所以 (c) 不是「什么都不用做」）**：

```text
0.07 * 150.0            = 10.500000000000002   (Double.toString)
direct gram entry 10.5   = 10.5
equal?                   = false
```

- **测试处置（本轮已落地）**：`three_entry_paths_yield_the_same_grams` 的两条 `isEqualTo` **保留**——
  实测有检测力（把 `toGrams` 的乘法去掉后它们都会红，见 D 的专项回答），且逐位一致在整数份量下确实成立。
  另外**新增** `decimal_amounts_agree_within_tolerance`（`isCloseTo(..., within(1e-9))`），
  把「小数份量下近似相等」这条**真实不变量**也写进套件——否则「脆弱性可达」这件事在测试里毫无痕迹。
  两个测试的原文见 Step 1。

### D. 恒真断言自查（逐条实测，非纸面推断）

做法：把每条断言**原样复制**到若干「突变体实现」上跑一遍（完全不换算、grams 恒为 1、
返回空表、给出残缺的错误提示），再看该断言红不红。
**实测推翻了我在纸面上的两个预判**，所以下面每条都标了证据来源。

| # | 测试方法 | 怎样才能变红 | 判定 |
| --- | --- | --- | --- |
| 1 | `converts_bowl_to_grams` | `toGrams` 不做乘法 / `index` 取错 grams | ✅ 有检测力（实测：不换算的实现下红） |
| 2 | `converts_fractional_units` | 小数份量被截断成整数（1.5 → 1） | ✅ 有检测力，且是唯一覆盖小数份量的方法 |
| 3 | `converts_spoon_to_grams` | 「所有单位都按第一个单位算」——**只有它能抓** | ✅ 有检测力，且不可替代 |
| 4 | `null_unit_means_amount_is_already_grams` | 把 `null` 当未知单位抛异常 | ✅ 有检测力（它是 `isBlank` 分支中 `null` 那一半） |
| 5 | `unknown_unit_is_rejected_with_a_useful_message` | 见下方拆解 | ✅ **本轮已修两遍**：第 3 条断言原来恒真（见 E），改成断言整段列表后 M1/M2 双杀（实测）；**本轮（Minor-4b）再把整段断言拆成两条**——`hasMessageContaining("可用的单位: 碗、勺")` 里的 `"可用的单位: "` 与实现里的拼接文本逐字相同（含那个全角冒号与空格），改一个空格两边一起红。拆成 `("可用的单位")` + `("碗、勺")` 后**重新实测 M1/M2 仍然双杀**（见下） |
| 6 | `index_maps_each_unit_name_to_its_grams`（原 `builds_unit_index_from_rows`） | `index` 返回空表 / 少了某个条目（实测：空表下红） | ✅ 有检测力 |
| 7 | `three_entry_paths_yield_the_same_grams`（原 `three_conversion_paths_agree`） | 见下方专项 | ✅ 两条断言都有检测力（**实测推翻了我的预判**）；本轮另加第 12 条覆盖小数份量 |
| 8 | `rejects_non_finite_or_negative_amount` | 去掉 A2 的任一半 | ✅ 有检测力；只有它覆盖 `amount` 的有限性 |
| 9 | `rejects_invalid_unit_definitions` | 去掉 A1 的任一半 | ✅ 有检测力；`grams` 三条里 `0` 那条最关键（锁住 `> 0` 而非 `>= 0`） |
| 10 | `blank_unit_is_treated_as_grams` | 把空白当未知单位报错 | ⚠️ **近乎恒真**：实测「完全不换算」的实现下 `toGrams(150, "")` 也是 `150.0`，两条断言照样绿 |
| 11 | `index_rejects_null_list` | 去掉 A3 的 `requireNonNull` | ⚠️ **恒真**（就 `index` 而言）：测的是 NPE 与消息，与返回值无关；实测「返回空表」的实现照样通过 |
| 12 | `decimal_amounts_agree_within_tolerance` | 去掉 `toGrams` 里的乘法（实测红） | ✅ 有检测力；它锁的是「小数份量下近似相等」这条真实不变量，整数份量下无法替代第 7 条 |
| 13 | `index_rejects_duplicate_unit_names` | 把 `index` 改回「重复取首个」（实测红：不再抛异常） | ✅ 有检测力；这是唯一能抓「同名单位混入」的测试 |
| 14 | `rejects_conversions_above_the_single_entry_limit`（本轮新增） | 去掉 `toGrams` 的乘积上界（实测红：`33.34` 碗不再抛） | ✅ 有检测力；**放行条件 1** 的验收点，把 `4999.5` 与 `5001.000000000001` 分在边界两侧 |
| 15 | `rejects_zero_amount_aligning_with_schema`（本轮新增） | 把 `amount <= 0` 改回 `amount < 0`（实测红：`toGrams(0, …)` 不再抛） | ✅ 有检测力；**Minor-3** 的验收点，锁住与 `meal_item.amount CHECK (amount > 0)` 同口径 |

**第 5 条的两道修法都要重测（Minor-4b）**：拆成两条断言之后，三个缺陷变体实测如下
（本轮用与测试**逐字相同**的断言链跑，`碗`=150 / `勺`=15）：

| 变体 | 干了什么 | `("可用的单位")` | `("碗、勺")` | 判定 |
| --- | --- | --- | --- | --- |
| M0 | 正确实现 | PASS | PASS | **GREEN** |
| M1 | 把整个可用单位列表删掉 | FAIL | FAIL | **RED** |
| M2 | 列表只留 `勺` | PASS | FAIL | **RED** |

即 `"可用的单位"` 那一条只管「列表出现过」（M2 下它仍绿），**真正逐字锁住内容与顺序的是 `"碗、勺"` 那一条**；
两条合起来 M1/M2 才都红。**拆开没有拆坏检测力**，但两条的分工要写清楚，别把其中任一条删掉。

**关于第 7 条的专项回答**（原文点名要问的）：

> 「三条路径都调用同一个 `toGrams`，如果 `toGrams` 有个统一的错误，它会不会仍然通过？」

**会——而且这正是它唯一的失效模式。** 实测：一个「完全不做换算、直接把 `amount` 当克数返回」的
`toGrams` 让 `byBowl = 1.0`、`bySpoon = 10.0`、`byGram = 150.0`，两条断言**都红**。
反过来说，若 `toGrams` 的错误是**统一的**（三条路径同错），两条断言必然**同时通过**——
例如 `index` 忽略单位名、一律返回第一个单位的 grams，则 `1 碗 = 10 勺 = 150`。
**但那种错误会被第 3 条测试（`converts_spoon_to_grams`：`2 勺 = 30.0`）抓住**，
所以整份测试仍有覆盖，只是**不能把第 7 条当成换算正确性的证据**。
它真正锁的是**「份量与克数两条录入路径在同一个 `toGrams` 下自洽」**，
即：*如果* 实现是对的，三种录入法给同一个数。这与 Task 5 里
`changing_portion_recomputes_from_scratch` 的定位一致——**意图声明，不是检测手段**。

第 2 条断言（`bySpoon == byGram`）相对第 1 条**冗余**：三者同错时二者必同红；
但反向不成立（`勺` 的克数写错而 `碗` 正确时，只有第 2 条红），所以**保留它是对的**。

**关于 `index_maps_each_unit_name_to_its_grams` 的专项回答**：`containsEntry` × 2
**不会**因为 `index` 返回**多余**条目而变红。多余的条目只会在
`unknown_unit_is_rejected...` 的**提示文本**里显形（可用单位列表变长）。
**本轮补强后这条缺口小了一圈，但没有完全堵上**：

- 新加的 `index_rejects_duplicate_unit_names` 抓住了「混进了**同名**单位」
  （`碗=150` 与 `碗=200` 拼在一起）：原实现静默取首个，现在抛异常；
- 强化后的消息断言锁的是**列表内容与顺序**（`可用的单位` + `碗、勺` 两条），所以「列表被删空」
  「只剩一种」「顺序变了」「混进来的单位排在前面」都会变红；
- 但 `hasMessageContaining` 终究是**子串匹配**：一个**不同名**的单位追加在**末尾**
  （`可用的单位: 碗、勺、份`）仍然通过。要彻底堵住得断言 `index(...)` 的**键集**
  （如 `containsOnlyKeys("碗", "勺")`）——本轮未加，留作后续可选项。
  另外把断言拆成两条之后，「列表出现过」与「列表内容与顺序」是**两条各自独立**的检查：
  实测 M2（只留 `勺`）下第一条仍绿、**只有第二条红**（见 D 的变体表）——不要删掉第二条。

### E. 本轮发现的最弱断言（本轮已修）

**`unknown_unit_is_rejected_with_a_useful_message` 的第 3 条断言 `hasMessageContaining("碗")` 是恒真的。**

它**测不到它想要测的东西**。该断言的本意（原文注释：「提示里要带上可用单位，方便前端显示」）
是验证**可用单位列表**出现在消息里；但错误输入本身就是 `"碗半"`，
而断言要求的子串 `"碗"` 是 `"碗半"` 的一部分——**第 2 条断言已经保证了这个消息里含 `"碗"`**。
实测（三组消息）：

| 消息 | `contains("碗")`（旧断言） | `contains("可用的单位: 碗、勺")`（本轮新断言） |
| --- | --- | --- |
| `未知的份量单位「碗半」；该食物可用的单位: 碗、勺`（正确） | 绿 | 绿 |
| `未知的份量单位「碗半」`（**可用单位列表被整个删掉**） | **绿** ← 旧断言拦不住 | **红** ← 新断言抓住 |
| `未知的份量单位「碗半」；该食物可用的单位: 勺`（列表是错的） | **绿** ← 旧断言也拦不住 | **红** ← 新断言抓住 |

**修法（一行，本轮已落地）**：不是换成 `hasMessageContaining("勺")`——那只挡得住「列表被整个删掉」，
挡不住「列表只剩一种」（`…可用的单位: 勺` 里同样有 `"勺"`）。改成断言**整段列表片段**：

```java
.hasMessageContaining("可用的单位: 碗、勺")   // 整个列表片段：M1/M2 双杀，还锁住内容与顺序
```

`index` 用 `LinkedHashMap`、`List.of` 顺序固定，所以消息是**确定的**，可以这样逐字锁。
两个缺陷变体（M1 删掉整段列表、M2 只留 `勺`）实测**在新断言下都变红**，正确实现（M0）绿。

**⚠️ 但这一版整段断言本身有个坏味道（Minor-4b，本轮已改）**：`"可用的单位: "` 里那段
「全角冒号 + 空格」与实现里的拼接文本**逐字相同**——**改一个空格两边一起红**，测试与实现措辞串在一起。
本轮拆成两条：

```java
.hasMessageContaining("可用的单位")
.hasMessageContaining("碗、勺");   // 列表内容与顺序
```

**拆完必须重测检测力**（否则可能拆成一个恒真的组合），实测见 D 的变体表：
**M1/M2 仍然都红、M0 仍绿**。分工是：`"可用的单位"` 只管「列表出现过」（M2 下仍绿），
`"碗、勺"` 才逐字锁住内容与顺序——两条都不能删。

### F. 包内约定落纸：`package-info.java`（Minor-5，本轮新增；Task 7 质量审查轮扩到四条）

`calc/` 有两条约定原先只活在示例里，没写下来。本轮新建
`backend/src/main/java/com/change/calc/package-info.java`（纯 Javadoc，无代码，`{@link}` 同包自动解析、
编译期不报错），写下：

1. **顶层 record vs 嵌套 record 的判据**：跨操作**复用**的聚合值放顶层
   （`Nutrition` 既用于「每 100 克基准值」也用于「一餐合计值」）；只属于**单个操作**的
   条目/状态嵌在操作类里（`PortionConverter.Unit`，以及计划里 Task 7 的 `VolumeCalculator.Entry`、
   Task 8 的 `OneRepMax.Set`）。
   > **措辞更正（2026-10-15，Task 7 质量审查轮）**：原先写的是「作为某个操作**输入或输出的**
   > 聚合值放顶层」，而例子里 `VolumeCalculator.Entry`（一个**输入**条目）恰恰嵌在操作类里，
   > 判据与例子打着。实际判据是**「跨操作复用」vs「只属于单个操作」**，与「输入还是输出」无关。
2. **`PortionConverter.toGrams` → `NutritionCalculator.forGrams` 的流水线契约**，
   以及「**二者的定义域必须一致**」——这正是本轮**放行条件 1** 的来源。

**Task 7 质量审查轮又补了两条**（这两条原先只活在计划的 Task 9 小节与各处 Javadoc 里，
读 `package-info` 的人看不到）：

3. **公开数值入口必须显式拒绝 NaN / ±∞**（**可执行**约定）。包内惯用法两种：
   一般输入用 `!Double.isFinite(v)`（`forGrams`、`toGrams`、`Nutrition`、`Unit`），
   比较类场景用 `!(x <= MAX)`（NaN 与任何比较都返回 false，所以 `x > MAX` 这种写法放 NaN 过去）。
   理由：这个包已经因为 NaN 静默变成 `0.0` / `IN_RANGE` **栽过两次**
   （`Round.to2` 与 `VolumeCalculator.classify`）——NaN 不会崩，它给出一个看起来正常的错答案。
4. **可空的领域配置不得兜底，必须进入类型或状态**（见 `VolumeCalculator.Status.NOT_CONFIGURED`）。
   理由：任何兜底值都会静默说谎（三个具体失效见 Task 7 第 1 节的表）；判据是调用方
   **分得清**「没这个配置」与「配置成 0」这两个事实。
   这条契约原先只写在 Task 9 的小节里。

外加一条贯穿的价值观：所有校验都倾向于「让它响」而不是「让它算出一个看起来合理的值」，
因为这类缺陷的失效方式大多是**静默**的（NaN 经舍入变成 0、∞ 被饱和成大数、重复单位名取首个）。

### 遗留（仍未修的项，与 Task 5 记档一致；末尾附两条本轮已处理的旧记档）

- **`NutritionCalculator.forGrams(grams = NaN)` 放行 NaN —— 本轮已关（Minor-2）。**
  原记档：`NaN < 0` 是 `false`、`NaN > MAX_GRAMS` 也是 `false`，两条校验都放行；
  **实测后果不是「显示 NaN」，而是「静默显示全 0」**：
  `Round.to2` 里是 `Math.round(NaN * 100.0) / 100.0` = `0 / 100.0` = `0.0`。
  当时按任务约束「不要动 `NutritionCalculator`」记成欠账；**本轮质量审查明确把它列为需要关掉的欠账**，
  所以在 `forGrams` 里补了一条（放在 `grams > MAX_GRAMS` **之前**，更易读）：

  ```java
  if (!Double.isFinite(grams)) {
      throw new IllegalArgumentException("grams 必须是有限值，实际为 " + grams);
  }
  ```

  于是「同一个包对同一个值有两种态度」这条不一致消失了：`Nutrition` 紧凑构造器、
  `PortionConverter.Unit`、`toGrams`、`forGrams` 现在**四处同一个口径**。
  配套测试 `rejects_non_finite_grams`（`NaN` 与 `+Infinity` 都抛）。
  **只加了这一条校验，算法一行未动**（`Round.to2`、六个分量的换算都没碰）。
  原先记下的 DB 侧不一致（`meal_item.grams` 拒 `NaN`、却放行 `+Infinity`）**仍然成立**，
  但它的价值已按 A2 的实测口径下调——`+Infinity` 到不了显示层。
- **`sum` 的入参仍无上限校验**（Task 5 已记档：单条目 5000 g 的上限约束不到合计值）。
- **克数定标 —— 本轮已从「待办」改成决定（放行条件 2）：只在显示层定标。**
  原记档写「留待 Task 4 的饮食录入 / Task 9 的服务层处理」，那是**待办**；
  而**照做有害**（Task 9 的实现者会照旧文档去做）。依据与三条实测数字见 **C3-决定**，
  结论：`toGrams` 返回前定标最坏（把算术口径降成两位、`sum` 先舍再累加产生方向性偏差）、
  落库前定标看似合理但实测有害（移动真实录入量最多 0.005 g，5.10%–32.67% 的情形下 kcal 跟着动 0.01，
  更糟的是制造「库里的 `grams` 与页面显示的热量来自不同的数」），
  **只在显示层定标是唯一无代价的选项**。
  `toGrams` 的 Javadoc 已补上「返回未定标的原始乘积；定标属于显示层」。
  **给 Task 9 的指令是「在渲染层格式化」而不是「落库前定标」。**
- **`index` 的合并函数 `(a, b) -> a`（本轮已移除）**：原文写「纯防御性代码、当前不可达」，
  实测**可达**——两个都合法的同名 `Unit` 拼成列表即可命中，且会静默丢掉一个克数。
  已改为**重复即抛 `IllegalArgumentException`**（见 B3、D 第 13 条）。

---

## Task 7: 训练容量计算（核心）

**Files:**
- Create: `backend/src/main/java/com/change/calc/VolumeCalculator.java`
- Test: `backend/src/test/java/com/change/calc/VolumeCalculatorTest.java`

- [x] **Step 1: 写失败的测试**

> **（2026-10-15，规格评审轮）本 fence 已同步**：原计划这里是 **6 条测试**，磁盘上是
> **13 条**——`fb50b26` 的 A/B/C 三条偏离加了 5 条，本轮关掉「`classify` 收不下可空的
> `weekly_sets_min/max`」这个设计缺口又加了 2 条。下面这段与
> `backend/src/test/java/com/change/calc/VolumeCalculatorTest.java` **逐字一致**（脚本比对，非目测）。

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VolumeCalculatorTest {

    /** 一个动作在一次训练里的若干组，以及它牵连的肌群。 */
    private static VolumeCalculator.Entry entry(long muscleId, int sets, String role) {
        return new VolumeCalculator.Entry(muscleId, sets, role);
    }

    @Test
    void primary_muscle_counts_one_per_set() {
        var volume = VolumeCalculator.accumulate(List.of(entry(1, 4, "primary")));
        assertThat(volume).containsEntry(1L, 4.0);
    }

    @Test
    void secondary_muscle_counts_half_per_set() {
        var volume = VolumeCalculator.accumulate(List.of(entry(2, 4, "secondary")));
        assertThat(volume).containsEntry(2L, 2.0);
    }

    @Test
    void bench_press_example_from_the_design_doc() {
        // 设计文档里的算例：卧推 4 组 → 胸 +4、三头 +2、三角肌前束 +2
        var volume = VolumeCalculator.accumulate(List.of(
                entry(100, 4, "primary"),     // 胸
                entry(101, 4, "secondary"),   // 三头
                entry(102, 4, "secondary"))); // 三角肌前束

        assertThat(volume).containsEntry(100L, 4.0)
                          .containsEntry(101L, 2.0)
                          .containsEntry(102L, 2.0);
        // containsEntry 抓不住**多余键**（实测：往 accumulate 的结果里注入 out.put(999L, 1.0)，
        // 上面三条断言照旧全绿）。「设计文档算例恰好就是胸 / 三头 / 三角前束这三个肌群」
        // 本来就是这条测试的职责，所以这里必须把键集也锁死。
        assertThat(volume).containsOnlyKeys(100L, 101L, 102L);
    }

    @Test
    void same_muscle_from_two_exercises_accumulates() {
        // 卧推 4 组让三头 +2，绳索下压 3 组让三头 +3，合计 5
        var volume = VolumeCalculator.accumulate(List.of(
                entry(101, 4, "secondary"),
                entry(101, 3, "primary")));

        assertThat(volume).containsEntry(101L, 5.0);
    }

    @Test
    void unknown_role_is_rejected() {
        // ⚠️ 本测试守的是 **Entry 的白名单**，不是 accumulate 的 switch default。
        // 实测：只删掉 Entry 的 role 白名单（让非法状态可表示）时，本测试**照旧全绿**
        // ——异常此时由 accumulate 的 switch default 抛出，消息里有 "tertiary"。
        // 真正守住 switch 那条纵深防线的是**组合变异体**（同时放开白名单 + 把 default
        // 改成 0.0），那时本测试与 invalid_role_is_rejected 才会一起变红。
        // 第 2 道防线为何保留、以及为什么它单测不可达，见计划「规格评审轮记录」第 2 节。
        assertThatThrownBy(() -> VolumeCalculator.accumulate(List.of(entry(1, 3, "tertiary"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tertiary");
    }

    @Test
    void invalid_role_is_rejected() {
        // 与 Unit 同一条思路：让非法状态不可表示，比在下游补救更早。
        // 白名单取自 VolumeCalculator.VALID_ROLES（与 V2__training.sql 的 CHECK 两侧收口）。
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, 3, "tertiary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tertiary");
    }

    @Test
    void null_role_is_rejected_before_the_whitelist() {
        // 顺序是有意的：若把 null 检查放到白名单之后，消息会变成「未知的肌群角色: null」
        // ——那是**另一条**信息（分不清「没填」与「填错」）。
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, 3, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    void non_positive_muscle_id_is_rejected() {
        assertThatThrownBy(() -> new VolumeCalculator.Entry(0, 3, "primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("muscleId");
    }

    @Test
    void negative_sets_on_an_entry_is_rejected() {
        assertThatThrownBy(() -> new VolumeCalculator.Entry(1, -1, "primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sets");
    }

    @Test
    void classify_against_target_range() {
        assertThat(VolumeCalculator.classify(0, 10, 20)).isEqualTo(VolumeCalculator.Status.UNTRAINED);
        assertThat(VolumeCalculator.classify(3, 10, 20)).isEqualTo(VolumeCalculator.Status.BELOW);
        assertThat(VolumeCalculator.classify(14, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(26, 10, 20)).isEqualTo(VolumeCalculator.Status.ABOVE);
    }

    @Test
    void zero_set_contribution_does_not_create_a_key() {
        // 对下游只有两种可用状态：有量 / 无量。若建了 0 值键，按 entrySet() 渲染的
        // 热力图就会多画一个不该画的热区。
        assertThat(VolumeCalculator.accumulate(List.of(entry(1, 0, "primary")))).isEmpty();
        assertThat(VolumeCalculator.accumulate(List.of(
                entry(1, 0, "primary"),
                entry(2, 3, "primary"))))
                .containsOnlyKeys(2L);
    }

    @Test
    void null_entries_are_rejected_with_a_useful_message() {
        // 没有这两道校验时，null 会一路流进 for 循环，以 NPE 的形式炸出来——
        //   accumulate(null)          → Cannot invoke "java.util.List.iterator()" because "entries" is null
        //   accumulate([Entry, null]) → Cannot invoke "com.change.calc.VolumeCalculator$Entry.role()" because "e" is null
        // （两条都是实测消息；注意后者点的是 $Entry.role() 而不是 List.iterator()，
        //  曾把这两条弄混。）报错点离调用点很远，消息里也不点出是哪个参数坏了。
        //
        // 断言用「不能」而不是「null」：那些 JDK 消息本身**也含 "null" 字样**，
        // 用 "null" 断言就恒真了——实测过，它拦不住「删掉校验」的变异。
        assertThatThrownBy(() -> {
            VolumeCalculator.accumulate(null);
        }).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("不能");
        assertThatThrownBy(() -> {
            VolumeCalculator.accumulate(Arrays.asList(entry(1, 3, "primary"), null));
        }).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("不能");
    }

    @Test
    void rejects_inverted_target_range() {
        // 区间写反时会静默返回 BELOW（5 < 20 为真），给出一个看起来合理的错答案。
        assertThatThrownBy(() -> VolumeCalculator.classify(5, 20, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min=20")
                .hasMessageContaining("max=10");
    }

    @Test
    void rejects_negative_target_range_lower_bound() {
        assertThatThrownBy(() -> VolumeCalculator.classify(5, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("下限");
    }

    @Test
    void rejects_non_finite_or_negative_sets() {
        // NaN 会同时绕过 sets<=0 / sets<min / sets>max 三个比较，落到 return IN_RANGE，
        // 给出「达标」这个最危险的错答案；负组数会静默变成「没练」。
        assertThatThrownBy(() -> VolumeCalculator.classify(Double.NaN, 10, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sets");
        assertThatThrownBy(() -> VolumeCalculator.classify(-5, 10, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sets");
        // 未配目标时优先报「未配目标」，而不是因为 sets 坏掉而抛异常
        assertThat(VolumeCalculator.classify(Double.NaN, null, null))
                .isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
    }

    @Test
    void range_boundaries_are_inclusive() {
        // 区间是闭区间：等于下限或上限都算 IN_RANGE
        assertThat(VolumeCalculator.classify(10, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(20, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(9.5, 10, 20)).isEqualTo(VolumeCalculator.Status.BELOW);
        assertThat(VolumeCalculator.classify(20.5, 10, 20)).isEqualTo(VolumeCalculator.Status.ABOVE);
    }

    @Test
    void missing_target_range_is_its_own_state_and_short_circuits() {
        // 这条测试钉两件事（原先分在两条方法里，而实测「把 null 短路挪到区间校验之后」
        // 会让**两条同时变红**——边际内容只剩第二件，所以合并）：
        //
        // ①「没配目标」与「没练」是两个不同的事实。若把 null unbox 成 0：
        //   下限 0 → 「练得不够」永久消失；上限 0 → 撞上 max<min 全页抛异常；
        //   两者皆 0 → 任何训练量都判 ABOVE。
        assertThat(VolumeCalculator.classify(25, null, 20)).isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
        assertThat(VolumeCalculator.classify(25, 10, null)).isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
        assertThat(VolumeCalculator.classify(0, null, null)).isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
        // 与真正的「没练」区分开
        assertThat(VolumeCalculator.classify(0, 10, 20)).isEqualTo(VolumeCalculator.Status.UNTRAINED);
        // ② null 短路**排在区间校验之前**：`(null, 0)` 这种看起来写反的配置（max=0 < min 兜底）
        //   不该抛异常，而应直接回报「未设目标」——否则一个 NULL 上限就足以让整页打不开。
        assertThat(VolumeCalculator.classify(5, null, 0)).isEqualTo(VolumeCalculator.Status.NOT_CONFIGURED);
    }
}
```

- [x] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=VolumeCalculatorTest`

Expected: 编译失败，`cannot find symbol: class VolumeCalculator`
（本 fence 已是同步后的版本；`fb50b26` 那一次的原始输出是
`[ERROR] .../VolumeCalculatorTest.java:[13,36] 程序包VolumeCalculator不存在`——
即 `cannot find symbol` 的中文形态，报在两条 static import 行上。）

- [x] **Step 3: 写实现**

> **（2026-10-15，规格评审轮）本 fence 已同步**：原计划这里是 **50 行实现**，磁盘上是
> **149 行**（`fb50b26` 的 A/B/C 偏离 + 本轮新增的 `NOT_CONFIGURED` 第三态）。
> 下面这段与 `backend/src/main/java/com/change/calc/VolumeCalculator.java` **逐字一致**。
> 本轮那处设计改动的理由、以及三种 unbox 兜底的失效，见文末「规格评审轮记录」第 1 节。

```java
package com.change.calc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 训练容量统计：把「记了多少组」折算成「每个肌群多少组」。
 *
 * 规则（设计文档决定 5）：
 *   主要肌群 +1，次要肌群 +0.5。
 * 次要肌群算半分是训练量统计的通行做法——卧推对三头确实有刺激，
 * 但不该和臂屈伸算成一样的量。
 *
 * <p><b>本类的错误为什么特别难发现。</b>热量算错了，用户当天就能看出不对；
 * 容量算错了，它只是画出一张<b>看起来合理</b>的人体图热力。所以这里的
 * 失效模式不是「崩溃」，而是「静默地给出一个像样的错答案」，校验一律选「让它响」。
 */
public final class VolumeCalculator {

    /**
     * 合法的肌群角色。必须与 {@code V2__training.sql} 里 {@code exercise_muscle.role}
     * 的 {@code CHECK} 一致（DDL 原文：{@code role IN ('primary', 'secondary')}）。
     *
     * <p>抽成常量是为了让「Java 侧白名单」与「DDL 侧白名单」两侧**互相收口**：
     * {@code SchemaTest.role_whitelist_matches_the_ddl} 会把这里的值集与
     * {@code sqlite_master.sql} 里抽出来的值集逐项比对，任何一侧改了另一侧就红。
     * 构造器与 {@link #accumulate} 的 {@code switch} 都引用它，所以不存在
     * 「常量改了但校验没跟着改」这种中间态。
     */
    public static final List<String> VALID_ROLES = List.of("primary", "secondary");

    /**
     * 容量与目标区间的比较结果。
     *
     * <b>本枚举混了两个维度</b>：{@link #NOT_CONFIGURED} 说的是「没有参照系」，
     * 其余四态说的是「实际量与参照系的相对位置」。所以**枚举顺序不是严重程度刻度，
     * 禁止依赖 ordinal()**；要按量排序请显式列出那四个态。
     */
    public enum Status {
        /** 该肌群没有配置目标区间（weekly_sets_min/max 为 NULL）。 */
        NOT_CONFIGURED,
        /** 实际 0 组——列在训练里但一组都没做。与 NOT_CONFIGURED 是两个不同的事实。 */
        UNTRAINED,
        /** 有训练量，但低于目标区间下限。 */
        BELOW,
        /** 落在目标区间内（闭区间，等于下限或上限都算）。 */
        IN_RANGE,
        /** 高于目标区间上限。 */
        ABOVE
    }

    /**
     * 一条贡献：某个肌群、若干组、扮演什么角色。
     *
     * 这里校验的是值：{@code exercise_muscle} 表上本来就有
     * {@code role TEXT NOT NULL CHECK (role IN ('primary', 'secondary'))}，
     * 所以 DB 层存不进非法角色——这道校验只是把对象层对齐到既有 schema，
     * 让**非法状态不可表示**，而不是等到下游再补救。
     *
     * <p>为什么不能省掉、只靠 {@link #accumulate} 的 switch：{@code role} 为 null 时
     * {@code switch} 抛的是 NPE（消息形如
     * {@code Cannot invoke "String.hashCode()" because "<...>" is null}），
     * 而非有用的 {@link IllegalArgumentException}——它既没点出 {@code role} 这个字段，
     * 也没点出调用点（实测于 OpenJDK 25），而这个值在「动作 → 肌群」的映射里只是一段
     * 可选的下游数据，出错的地方离得很远。
     * <b>刻意不写编译器内部的局部变量槽位号</b>（这里曾经钉了一个具体的槽位编号，而实测出来的
     * 编号与注释里写的并不是同一个）：那编号换个编译器版本、或者多一个局部变量就会变，
     * 把它钉在注释里只会制造一处随时会过期的「事实」。
     *
     * <p>所以 {@code role} 的 null 检查必须放在白名单之前：放到后面，消息会变成
     * 「未知的肌群角色: null」——那是<b>另一条</b>信息。这个顺序由
     * {@code null_role_is_rejected_before_the_whitelist} 锁死。
     */
    public record Entry(long muscleId, int sets, String role) {

        public Entry {
            if (muscleId <= 0) {
                throw new IllegalArgumentException("muscleId 必须为正，实际为 " + muscleId);
            }
            if (sets < 0) {
                throw new IllegalArgumentException("sets 不能为负，实际为 " + sets);
            }
            if (role == null) {
                throw new IllegalArgumentException("role 不能为 null");
            }
            if (!VALID_ROLES.contains(role)) {
                throw new IllegalArgumentException(
                        "未知的肌群角色: " + role + "（只接受 " + String.join(" / ", VALID_ROLES) + "）");
            }
        }
    }

    private VolumeCalculator() {
    }

    /**
     * 把一个训练里的所有贡献累加成「肌群 id → 折算组数」。
     *
     * <p>零组的贡献**不创建键**：对下游只有两种可用状态——<b>有量 / 无量</b>；
     * 多出来的 0 值键只会让热力图多画一个不该画的热区。在 Map 层面 `containsKey`
     * 当然分得清「不在 map 里」与「在 map 里且为 0」，分不清的是**按 `entrySet()`
     * 渲染的下游**——所以这里不给 0 值留位置。
     *
     * @param entries 贡献列表；可以跨动作重复出现同一肌群，会累加
     * @return 肌群 id 到折算组数的映射。三条契约：
     *         ①**键序为首次出现序**（用 LinkedHashMap；不要改成 Map.copyOf——那会让每次启动的顺序都变，
     *           而下游是按 entrySet() 渲染热力图的）；
     *         ②值恒为 0.5 的整数倍（0.5 是 2 的幂，累加精确，所以容量这一路不需要舍入）；
     *         ③**缺键表示该肌群 0 组**，调用方用 getOrDefault(id, 0.0) 读取。
     */
    public static Map<Long, Double> accumulate(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries 不能为 null");
        Map<Long, Double> out = new LinkedHashMap<>();
        for (Entry e : entries) {
            Objects.requireNonNull(e, "entries 中不能含 null 元素");
            double weight = switch (e.role()) {
                case "primary" -> 1.0;
                case "secondary" -> 0.5;
                // 纵深防御，正常不可达：Entry 的紧凑构造器已经拒绝了非法角色。
                // 留在这里是因为「合法角色集」将来可能扩容（比如加 'stabilizer'），
                // 那时若忘了改这里，容量会被**静默算成 0**——这条分支让它当场响。
                default -> throw new IllegalArgumentException(
                        "未知的肌群角色: " + e.role() + "（只接受 "
                                + String.join(" / ", VALID_ROLES) + "）");
            };
            if (e.sets() == 0) {
                continue;   // 无量的肌群不建键：多出来的 0 值键会让下游多画一个热区
            }
            out.merge(e.muscleId(), e.sets() * weight, Double::sum);
        }
        return out;
    }

    /**
     * 与建议区间比较。
     *
     * <p>区间参数用装箱类型是<b>有意</b>的：{@code muscle_group.weekly_sets_min/max}
     * 在 schema 里可空且无 CHECK，而「没配目标」是一个与「没练」不同的事实。
     * 如果这里收 {@code int}，服务层就必须 unbox，而任何兜底值都会静默说谎：
     * <ul>
     *   <li>兜成 0 → 下限失效，「练得不够」（{@code BELOW}）这一态永久消失；</li>
     *   <li>上限兜成 0 → 撞上下面的 {@code max < min}，每个肌群都抛异常，整页打不开；</li>
     *   <li>两者都兜成 0 → 任何训练量都判 {@code ABOVE}，用户看到全身标红。</li>
     * </ul>
     * 所以未配目标时直接返回 {@link Status#NOT_CONFIGURED}，并且<b>排在区间校验之前</b>
     * 短路——否则一个 NULL 上限（被兜成 0）就足以把整页变成异常。
     *
     * <p>注意：这个区间是预设的参照系，不是科学结论；用户可以在设置里改。
     *
     * <p>区间本身也要校验：{@code classify(5, 20, 10)}（上下限写反）会返回
     * {@code BELOW}——因为 {@code 5 < 20} 为真——给出一个<b>看起来合理</b>的错答案。
     * 用户可以在设置页逐肌群改这个区间，打错数字完全可能，所以宁可在这里响。
     *
     * @param sets 实际折算组数；必须是有限且非负的。{@code NaN} 与任何比较都返回
     *             {@code false}，所以它<b>同时</b>绕过下面三处比较（{@code sets <= 0} /
     *             {@code sets < min} / {@code sets > max}），一路落到最后的
     *             {@code return IN_RANGE}——给出「达标」这个最危险的错答案。
     *             这条校验<b>排在 null 短路之后</b>是有意的：没配目标的肌群不该因为
     *             实际值坏了而改抛异常
     * @param min  区间下限；不得为负。传 {@code null} 表示该肌群未设目标
     * @param max  区间上限；不得小于下限。传 {@code null} 表示该肌群未设目标。
     *             比较用的是闭区间
     */
    public static Status classify(double sets, Integer min, Integer max) {
        if (min == null || max == null) {
            return Status.NOT_CONFIGURED;
        }
        if (!(sets >= 0)) {
            // 用 !(sets >= 0) 而不是 sets < 0：NaN 与任何比较都返回 false，
            // 所以 NaN 会绕过 sets < 0，一路落到最后的 return IN_RANGE——
            // 给出「达标」这个最危险的错答案。包内另三个数值入口都用了同一个套路。
            throw new IllegalArgumentException(
                    "sets 必须是有限且非负的组数，实际为 " + sets);
        }
        if (min < 0) {
            throw new IllegalArgumentException("区间下限不能为负: " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException(
                    "区间上限不能小于下限: min=" + min + ", max=" + max);
        }
        if (sets <= 0) return Status.UNTRAINED;
        if (sets < min) return Status.BELOW;
        if (sets > max) return Status.ABOVE;
        return Status.IN_RANGE;
    }
}
```

- [x] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=VolumeCalculatorTest`

Expected: `Tests run: 13, Failures: 0, Errors: 0`
（原计划写 6；`fb50b26` 实测 11（6 + A/B/C 的 5 条），本轮再 +2 = **13**。以 `mvn` 输出为准。）

- [x] **Step 4b（规格评审轮新增）：全量回归**

Run: `cd backend && mvn -B test`

Expected: **`Tests run: 66, Failures: 0, Errors: 0`**（质量审查轮结束时实测值）。
逐项应为：`NutritionCalculatorTest` 13、`PortionConverterTest` 15、`VolumeCalculatorTest` 17、
`SchemaTest` 9、`MigrationTest` 5、`DataDirectoryInitializerTest` 3、
`ChangeApplicationTests` 2、`TrainingRepoTest` 2。
（**61 → 66 的账**：规格评审轮结束时是 61；Task 7 质量审查轮把
`invalid_role_cannot_even_be_constructed`（4 条不变量挤在一个方法里）按不变量拆成 4 条，
并把 `rejects_inverted_or_negative_target_range`（2 条）拆成 2 条 → 净 **+4**，
外加一条新的 `rejects_non_finite_or_negative_sets`（Important-1）→ **+1**，
再把两条重叠的 null 区间测试合并 → **−1**，共 13 → **17**；
`SchemaTest` 加一条角色白名单防漂移测试 → 8 → **9**。新增的是**测试方法个数**，
所有既有断言的期望值一个都没动。以 `mvn` 的实际输出为准。）

- [x] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/calc/VolumeCalculator.java \
        backend/src/test/java/com/change/calc/VolumeCalculatorTest.java
git commit -m "feat: 训练容量计算（主要 +1 / 次要 +0.5），含设计文档算例"
```

- [x] **Step 6: 提交（Task 7 规格评审修复）**

```bash
git add backend/src/main/java/com/change/calc/VolumeCalculator.java \
        backend/src/test/java/com/change/calc/VolumeCalculatorTest.java \
        docs/superpowers/plans/2026-10-14-change-01-foundation.md
git commit -F <提交信息文件>
```

提交信息说清五件事：把「未设目标」做成第三态（附三种 unbox 兜底的失效）、勾选 Task 7 的复选框、
同步两个 fence、修 4 处事实性写错、给算例测试加 `containsOnlyKeys`。

- [x] **Step 7: 提交（Task 7 质量审查轮修复）**

```bash
git add backend/src/main/java/com/change/calc/VolumeCalculator.java \
        backend/src/main/java/com/change/calc/Round.java \
        backend/src/main/java/com/change/calc/package-info.java \
        backend/src/test/java/com/change/calc/VolumeCalculatorTest.java \
        backend/src/test/java/com/change/calc/NutritionCalculatorTest.java \
        backend/src/test/java/com/change/SchemaTest.java \
        docs/superpowers/plans/2026-10-14-change-01-foundation.md
git commit -F <提交信息文件>
```

提交信息说清九件事：补 `classify` 的 `sets` 守卫（附 `NaN → IN_RANGE` 的实测）、
`accumulate` 换 `LinkedHashMap` 并写清三条返回契约、`Status` 补枚举语义、
修隔壁文件的同类恒真断言、`Round` / `package-info` 的过期表述、`skip-zero` 理由改准、
测试拆分（M9）、新增角色白名单 ↔ DDL 的防漂移测试、`package-info` 补两条约定。
逐条依据见文末「**Task 7 质量审查轮记录**」。

### 规格评审轮记录

> 本节是**本轮（规格评审轮）**的改动记档。评审的 A/B/C 三条偏离，代码在 `fb50b26` 里
> 就已经落地（`Entry` 的四条校验、`accumulate` 的两道 null 校验 + 「零组不建键」、
> `classify` 的两条区间校验）——**漏的只是计划 fence**：上面第一步、第三步的两个 java fence
> 一直停在原计划的 **6 条测试 / 50 行实现**，本轮同步到磁盘上的 **13 条 / 149 行**。

#### 1. 设计缺口：`classify` 的签名收不下可空的 `weekly_sets_min/max`（本轮已修）

`muscle_group.weekly_sets_min/max` 在 `V2__training.sql:41-42` 是**可空且无 CHECK** 的：

```sql
    weekly_sets_min INTEGER,
    weekly_sets_max INTEGER
```

而 `classify(double sets, int min, int max)` 收不了 `null`。审查实测了四种 unbox 兜底的后果：

| 区间配置 | sets=0 | sets=1 | sets=5 | sets=25 |
|---|---|---|---|---|
| 正确 10..20 | UNTRAINED | BELOW | BELOW | ABOVE |
| `min = NULL → 0` | UNTRAINED | **IN_RANGE** | **IN_RANGE** | ABOVE |
| `max = NULL → 0` | **抛 IAE** | **抛 IAE** | **抛 IAE** | **抛 IAE** |
| 两者都 NULL → (0,0) | UNTRAINED | **ABOVE** | **ABOVE** | **ABOVE** |

三种后果全是「静默地说谎」：兜下限 →「**练得不够**」这一态永久消失，练过的肌肉一律变达标绿；
兜上限 → 撞上下面的 `max < min`，而容量页一次性渲染全部肌群，**一个没配上限的肌群就让整页打不开**；
两者都兜 → 任何训练量都判 `ABOVE`，用户看到**全身标红**并可能据此错误减量。
用 `UNTRAINED` 兜底最糟：它的语义是「没练」，拿它表示「没设目标」会把用户真有的 25 组直接抹掉。

**改法：让第三态进入类型。** 参数改成装箱 `Integer`，`null` 直接返回新状态
`Status.NOT_CONFIGURED`，并且**短路排在区间校验之前**（否则一个 NULL 上限被兜成 0，
未配目标反而变成抛异常）。四要点：

1. **装箱是刻意的**：让「没配目标」在类型上就表示得出，服务层没有机会 unbox、也就没有机会兜底。
   既有调用 `classify(0, 10, 20)` 走**自动装箱**，既有 10 条 classify 断言行**逐字未改**。
2. **`NOT_CONFIGURED` 与 `UNTRAINED` 是两个不同的事实**：前者「没设目标」，后者「没练」。
   两者同时被 `missing_target_range_is_its_own_state_and_short_circuits` 断言到。
3. **null 短路在校验之前**，由同一条 `missing_target_range_is_its_own_state_and_short_circuits`
   锁死（`classify(5, null, 0)` 返回 `NOT_CONFIGURED` 而不是抛 `max < min`）。
4. **不动 schema**：`muscle_group` 的两列**不加 `NOT NULL`**——NULL 正是「未设目标」的表达。

**本轮新增 2 条测试**（已进 fence）：`missing_target_range_is_its_own_state`、
`null_range_short_circuits_before_validation`。单测 11 → **13**，全量 59 → **61**。

> **后续更正（2026-10-15，Task 7 质量审查轮）**：上面这两条**已合并成一条**
> `missing_target_range_is_its_own_state_and_short_circuits`。理由是审查的实测：
> 把 null 短路挪到区间校验之后，**两条同时变红**——「守短路顺序」这件事第 2 条
> （旧名 `null_range_short_circuits_before_validation`）已经覆盖，
> 它的边际内容只剩「`(null, 0)` 这种看起来写反的配置不抛异常」，所以合并成一条并在注释里
> 点明它同时钉两件事。（当时的 13 这个数字因此不变；本轮的 13 → 17 另有其他来源，
> 见 Step 4b 的账。）

**破坏性评估**：全仓**目前没有任何生产调用方**（`classify(` 只被 `VolumeCalculatorTest` 调用），
UI 与服务层（Task 9）还没写，所以给 `Status` 加第五个值不会打到任何既有 `switch`。
但将来按枚举穷举渲染时必须显式处理 `NOT_CONFIGURED`（见第 5 节）。

#### 2. 覆盖关系更正：`unknown_role_is_rejected` 守的不是 `accumulate` 的 `switch`

`Entry` 落地之后，这条测试测的已经是「**非法角色构造不出来**」（异常从 `Entry` 的紧凑
构造器抛出），**对 `accumulate` 的 `switch default` 零覆盖**。审查的两条实测结论：

| 事实 | 实测 |
|---|---|
| 删掉 `default -> throw` | **javac 拒绝**：`switch` 表达式不覆盖所有输入值（表现为 `weight 可能未初始化`）——这条分支**不可删除** |
| 提交版里它可达吗 | **不可达**：`Entry` 先拦住了非法角色 |
| 「角色集将来扩容却漏改 switch」抓得到吗 | **抓得到**：组合变异体（同时放开白名单 + 把 `default` 改成 `0.0`）会让 `unknown_role_is_rejected` 与 `invalid_role_cannot_even_be_constructed` **双双变红** |

**所以 `default` 分支与那条测试都保留**（分支承载穷尽性、测试守白名单），但不要再以为
`unknown_role_is_rejected` 在守 `switch`——守它的是上面那个**组合**变异体。

**Task 7 质量审查轮的判定（保留 + 加注释，不删）：**

- 审查实测复核了上表：**只**删掉 `Entry` 的角色白名单时，**只有**
  `invalid_role_cannot_even_be_constructed` 变红，`unknown_role_is_rejected` **照旧全绿**
  （异常此时由 `switch default` 抛出，消息里同样有 `tertiary`）——所以旧名「rejected」
  没错，错的是**以为它在守 switch**。
- 审查给出两条路：删掉它、或在注释里写明它实际测什么。**本轮选择保留 + 加注释**，且
  **不改名**，理由是审查自己给的表格里那一行：「组合变异体下**两条同时变红**」。
  既然这两条在组合变异体下有**相同的检测力**、又各有一个单点的 `Entry` 不变量，
  删掉一条只会让「非法角色不可表示」这一件事少一个入口断言，换不来任何覆盖面。
  真正有害的**从来不是这条测试，而是那段误导性的注释**，所以治注释。
  （原本建议的改写 `accumulate_defends_in_depth_with_its_own_message` 反而更差：
  它构造 `new Entry(1, 3, "tertiary")` 再断言消息里含 `未知的肌群角色` 与 `tertiary`，
  与 `invalid_role_is_rejected` **逐字等价**，等于换了个名字的重复测试。）
- 结论：这条测试**单测不可达**第二道防线，只有组合变异体才杀得掉；它守的是白名单，
  这一点现在写在测试方法的注释里，也写在 `VolumeCalculator.Entry` 的 Javadoc 里。

#### 3. `bench_press_example_from_the_design_doc` 补 `containsOnlyKeys`

三条 `containsEntry` **抓不住多余键**：审查实测往 `accumulate` 的结果里注入
`out.put(999L, 1.0)` 之后，这条测试仍然全绿（本轮复核实验同结论，见提交报告）。
修改前，全套测试里只有 `zero_set_contribution_does_not_create_a_key` 能排除多余键。

**改法**：加一行

```java
        assertThat(volume).containsOnlyKeys(100L, 101L, 102L);
```

「设计文档算例恰好是胸 / 三头 / 三角前束这三个肌群」本来就是这条测试的职责，
加它只是**把语义钉死**（严格更强，语义不变），不改任何既有断言的期望值。

#### 4. schema 交叉验证（D1 / D2 / D3）

**D1** —— `exercise_muscle.role`（`V2__training.sql:73`）：

```sql
    role            TEXT    NOT NULL CHECK (role IN ('primary', 'secondary')),
```

与 `Entry` 的合法角色集**逐字一致**（小写、两个值，无第三个）。

**D2** —— `muscle_group.weekly_sets_min/max`（`V2__training.sql:41-42`）：**可空、无 CHECK**
（既没有 `min <= max`，也没有非负约束）→ 服务层的处理约定见第 1 节与第 5 节。

**D3 —— 枚举列一共 9 处。** 实施者报告里写的是「共 8 处」，但它自己给出的表就是 **9 行**；
逐条数一遍确实是 9 处 `TEXT` + `CHECK (col IN (...))`（报告里那个 8 是数错了，值清单没错）：

| # | 表.列 | 文件:行 | 合法值 |
|---|---|---|---|
| 1 | `exercise_muscle.role` | V2:73 | `primary` / `secondary` |
| 2 | `workout_set.set_type` | V2:119-121 | `normal` / `warmup` / `dropset` / `myo` / `partial` / `forced` / `tut` / `iso` / `jump`（**9 个值，跨两行**；原报告读到 `partial` 就打了省略号） |
| 3 | `food.source` | V3:23 | `manual` / `cn-food-table` / `agent` / `takeout` |
| 4 | `photo.kind` | V3:50 | `food` / `body` / `other` |
| 5 | `meal.slot` | V4:16 | `breakfast` / `lunch` / `dinner` / `snack` |
| 6 | `meal_item.origin` | V4:37 | `manual` / `photo` / `agent` / `takeout` |
| 7 | `chat_message.role` | V4:62 | `user` / `assistant` / `tool` |
| 8 | `change_log.origin` | V4:72 | `user` / `agent` |
| 9 | `change_log.action` | V4:73 | `insert` / `update` |

**值清单复核**：上表九行的合法值逐条对着三个迁移文件核过，**没有写错**。容易混淆的一点仍然成立：
`meal_item.origin` 与 `food.source` 的合法集**不同**（后者多 `cn-food-table`、少 `photo`），
不要抄成一份共用枚举。

**判据放宽一点，还有两列是同一形状**（`TEXT NOT NULL` + 取值集合靠约定或唯一索引，而不是 CHECK）：

- `food_portion.unit_name`（`V3:36`，`TEXT NOT NULL`，取值写在行尾注释 `碗 / 个 / 勺 / 份`，
  唯一性靠 `ux_food_portion(food_id, unit_name)`）；
- `body_metric.unit`（`V4:48`，`TEXT NOT NULL`，连注释里的取值清单都没有）。

原报告点的另外两列仍然成立，但它们**不是**这个形状：`body_metric.metric_type`（`V4:46`，
`TEXT NOT NULL`，取值清单只在注释里）与 `exercise.default_unit`（`V2:52`，
`TEXT NOT NULL DEFAULT 'kg'`）——这两列若要枚举化，得先补迁移。
**本轮不改迁移文件**，此处只记清单。

#### 5. 给 Task 9 的约定（服务层必须遵守）

这就是**这一节的用途**：Task 9 的实现者必须照此写，不要重新猜。

1. **不得 unbox**：`weekly_sets_min/max` 可空，**直接作为 `Integer` 传给 `classify`**，
   不要写 `min == null ? 0 : min` 这类兜底——三种兜底各自的失效见第 1 节的表。
2. **`NOT_CONFIGURED` 在 UI 上显示成「未设目标」**，**不能与 `UNTRAINED`（没练）合并显示**：
   两者语义不同，合并就是静默失真。
3. **区间合法性校验（`min >= 0`、`max >= min`）在设置页保存时做**，不要等到渲染热力图时才抛——
   用户打错数字应该在**保存那一刻**被告知，而不是几天后整页打不开。
4. **schema 观察**：`min = max = 0` 时任何 `sets > 0` 都判 `ABOVE`（`sets == 0` 判 `UNTRAINED`），
   所以「不统计这个肌群」**不该**用 0–0 表达，而应该用 **NULL**（即 `NOT_CONFIGURED`）。
5. **（Task 7 质量审查轮补）`weekly_sets_min` / `weekly_sets_max` 必须成对保存。**
   设置页保存时拒绝只有一边的配置。读到半个区间视为数据损坏——服务层必须**明确让它响**
   （或明确显示「配置不完整」），**不得静默当成「未设目标」**。
   *为什么补这一条*：schema 允许「只配下限」「只配上限」两种**真实**状态（两列各自可空、
   无成对约束），而 `classify(25, 10, null)` 与 `classify(25, null, 20)` 现在都返回
   `NOT_CONFIGURED`——等于把「配了一半」这个真实状态**并进了另一个标签**，
   而用户只填了下限时应用却告诉他「未设目标」。这是本轮审查的 **Important-2**，
   **本轮不改代码**（改签名要动 `classify`，超出本轮「只加一行守卫」的范围），
   在 Task 9 写设置页时收口即可。
6. **（同上的备选，等 Task 9 再定）** 把区间收成一个
   `record TargetRange(int min, int max)`、`classify(double sets, TargetRange range)`：
   这样「半个区间」变成**不可表示**的状态，且校验有了落脚点（构造即校验）。
   现在的 `Integer` 版本保留，因为 `TargetRange` 要多一个类型、调用方多一步构造。

---

## Task 8: 1RM 估算

**Files:**
- Create: `backend/src/main/java/com/change/calc/OneRepMax.java`
- Test: `backend/src/test/java/com/change/calc/OneRepMaxTest.java`

- [x] **Step 1: 写失败的测试**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OneRepMaxTest {

    @Test
    void epley_for_a_typical_set() {
        // Epley: 1RM = w * (1 + reps/30)
        // 62.5 * (1 + 6/30) = 62.5 * 1.2 = 75.0
        assertThat(OneRepMax.epley(62.5, 6)).isEqualTo(75.0);
    }

    @Test
    void single_rep_returns_the_weight_itself() {
        // 1RM 的定义就是「一组只能做一次的最大重量」。Epley 在 reps=1 处给 w*31/30（103.33），
        // 那是 3.3% 的系统性高估——最可靠的数据点反而最不该外推。所以 reps=1 直接返回实测重量。
        assertThat(OneRepMax.epley(100, 1)).isEqualTo(100.0);
        assertThat(OneRepMax.epley(62.5, 1)).isEqualTo(62.5);
        // reps=2 起才走 Epley
        assertThat(OneRepMax.epley(100, 2)).isEqualTo(Round.to2(100 * (1 + 2 / 30.0)));
        // reps=1 走特判，原值直返、不经舍入；reps>=2 走 Round.to2。
        // 这不是疏漏：单次的实测重量本身就是精确值，没有需要抹掉的浮点噪声。
        assertThat(OneRepMax.epley(60.125, 1)).isEqualTo(60.125);
        assertThat(OneRepMax.epley(60.125, 2)).isEqualTo(64.13);
    }

    @Test
    void the_upper_bound_itself_is_still_estimable() {
        // 12 是合法上界（拒绝侧 13 已有测试，合法侧此前没有）。
        // 把 > MAX_REPS 写成 >= MAX_REPS 会静默拒绝最后一档——这是边界漏测的典型。
        assertThat(OneRepMax.epley(60, 12)).isEqualTo(84.0);
        assertThat(OneRepMax.bestOf(java.util.List.of(new OneRepMax.Set(60, 12))))
                .hasValue(84.0);
    }

    @Test
    void high_reps_are_rejected_because_the_formula_degrades() {
        // 超过 12 次后 Epley 的误差迅速变大，宁可不算也不要给个假精确的数
        assertThatThrownBy(() -> OneRepMax.epley(60, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12");
    }

    @Test
    void best_of_a_session_picks_the_max() {
        // 顺序刻意打乱、让最大值落在中间：否则「取最大」与「取第一个」不可区分。
        // 60x8 = 76.0（最大，放中间）；62.5x6 = 75.0；65x4 = 73.67
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(62.5, 6),
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(65, 4)))
                .orElseThrow();

        assertThat(best).isEqualTo(76.0);
    }

    @Test
    void timed_sets_are_ignored() {
        // 平板支撑没有 1RM 的概念，传 null 次数应被跳过而不是抛错
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(0, null)))
                .orElseThrow();

        assertThat(best).isEqualTo(76.0);
    }

    @Test
    void no_estimable_sets_yields_empty_not_zero() {
        // 0.0 是个看起来像数字的哨兵，会被画成曲线上的一个点。
        // 「没有可估算的组」与「1RM 是 0 kg」必须可区分（包内约定四）。
        assertThat(OneRepMax.bestOf(java.util.List.of())).isEmpty();
        assertThat(OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(0, null)))).isEmpty();                       // 只有计时动作
        assertThat(OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 0)))).isEmpty();                         // 次数 0
        assertThat(OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 20)))).isEmpty();                        // 次数超上限
        assertThat(OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(0, 8)))).isEmpty();                          // 重量为 0
    }

    @Test
    void epley_of_zero_weight_is_zero_but_best_of_will_not_report_it() {
        // epley 是公式本身：0 重量算出的 1RM 就是 0。
        // 但「0 会被画成曲线上的一个点」这件事由 bestOf 负责挡住——
        // 领域入口是 bestOf，不要拿 epley 的结果直接画图或直接取 max。
        assertThat(OneRepMax.epley(0, 8)).isZero();
        assertThat(OneRepMax.bestOf(java.util.List.of(new OneRepMax.Set(0, 8)))).isEmpty();
    }

    @Test
    void epley_validates_its_own_inputs() {
        // 直接测公开入口：Set 的构造器会先挡住负数与非法重复次数，
        // 所以这几条守卫只有绕过 Set 才走得到（变异测试实测它们先前无覆盖）。
        assertThatThrownBy(() -> OneRepMax.epley(60, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reps");
        assertThatThrownBy(() -> OneRepMax.epley(-60, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> OneRepMax.epley(60, 13))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("12");
        // 特判必须排在验证之后：否则 reps=1 时这些非法值会被原样返回（静默）。
        assertThatThrownBy(() -> OneRepMax.epley(Double.NaN, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> OneRepMax.epley(-60, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> OneRepMax.epley(Double.POSITIVE_INFINITY, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
    }

    @Test
    void out_of_range_reps_are_skipped_not_thrown_by_best_of() {
        // javadoc 承诺「超范围跳过」而不是抛错——这条契约此前没有测试守住。
        // 一次训练里混进 20 次的组不该让整条 bestOf 炸掉。
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 20),
                new OneRepMax.Set(62.5, 6)))
                .orElseThrow();

        assertThat(best).isEqualTo(75.0);
    }

    @Test
    void rejects_non_finite_weight() {
        // NaN 会绕过 weight < 0（比较恒为 false），一路走到 Round.to2 变成 0.0——
        // 静默给出「1RM 是 0」这个看起来像数字的错答案。
        assertThatThrownBy(() -> OneRepMax.epley(Double.NaN, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> OneRepMax.epley(Double.POSITIVE_INFINITY, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
    }

    @Test
    void set_validates_its_own_fields() {
        assertThatThrownBy(() -> new OneRepMax.Set(Double.NaN, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        // 约定三承诺的是「拒绝 NaN 与 ±∞」：只测 NaN 的话，
        // 把 !Double.isFinite(weight) 写成 Double.isNaN(weight) 的变异会存活。
        assertThatThrownBy(() -> new OneRepMax.Set(Double.POSITIVE_INFINITY, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> new OneRepMax.Set(Double.NEGATIVE_INFINITY, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> new OneRepMax.Set(-60, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> new OneRepMax.Set(60, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repetitions");
        // 0 次是合法的（记了但没次数），与 null（计时动作）不同
        assertThat(new OneRepMax.Set(60, 0).repetitions()).isZero();
        // 0 kg 也是合法的（能构造），只是不可估算——由 bestOf 跳过，不由构造器拒绝。
        // 拒绝它会连带拒掉计时动作 new Set(0, null)。
        assertThat(new OneRepMax.Set(0, 8).weight()).isZero();
    }

    @Test
    void best_of_rejects_null_input() {
        // ⚠️ 断言用「不能」而不是「null」：JDK 裸 NPE 的消息里本来就含 "null"
        //（Cannot invoke "..." because "s" is null），用 "null" 做断言会恒真——
        // 删掉 requireNonNull 之后测试照样绿。这个坑在 Task 6/7 各踩过一次。
        assertThatThrownBy(() -> OneRepMax.bestOf(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("不能");
        assertThatThrownBy(() -> OneRepMax.bestOf(java.util.Arrays.asList(new OneRepMax.Set(60, 5), null)))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("不能");
    }
}
```

> **（2026-10-16，Task 8 落地轮）本 fence 已同步为磁盘原文，并修正了计划里两处算错的期望值。**
>
> **修正一：`best_of_a_session_picks_the_max` 原写 `75.0`，是错的。**
> 它与同文件 `timed_sets_are_ignored` 对**同一组 `Set(60, 8)`** 断言的 `76.0` 自相矛盾。
> 按 Epley 独立核算（1RM = w × (1 + reps/30)）：
> - `60 × (1 + 8/30) = 60 × 38/30 = 2280/30 = 76.00` ← **最大**
> - `62.5 × (1 + 6/30) = 62.5 × 1.2 = 75.00`
> - `65 × (1 + 4/30) = 65 × 34/30 = 2210/30 = 73.666… → 73.67`
>
> **正确值是 76.0**；实现（标准 Epley）是对的，改的是这条期望值。
>
> **修正二（⚠️ 已于 Task 8 收口轮撤销，现行结论见下方「收口轮」注）：`single_rep_returns_the_weight_itself` 原写 `epley(100, 1) == 100.0`。**
> 当时的判断是「标准 Epley 在 reps=1 处给 103.33，所以原期望值算错了」，
> 处置是按 Epley 的算术结果断言、并把测试名改成 `single_rep_is_still_scaled_by_epley`。
> **这条处置已被推翻**：103.33 不是「正确答案」，而是 Epley 在 `reps = 1` 上的**外推偏差**——
> `reps = 1` 落在 Epley 的适用域（`reps >= 2`）之外。
> `103.33` 这个**实测数字**仍然是真的，错的是把它当成「本方法应该返回的值」。
> 本节保留仅作历史记录。
> 仍然成立的部分只有一句：`w × 36/(37 − reps)` 是 **Brzycki**，与 Epley 不是同一个公式。
>
> **修正三（按包内约定三与既有标准补齐，非纠错）**：`epley` 的有限性校验、
> `Set` 的紧凑构造器、`bestOf` 的 null 防护，以及配套的 3 条测试 → 当时共 **8 条**测试
> （收口轮后为 **11 条**，见下）。

> **（2026-10-17，Task 8 收口轮）本 fence 已第三次同步为磁盘原文，并按人工裁定落实 4 件事。**
>
> **裁定一（撤销上面的修正二）：`reps == 1` 特判，直接返回 `weight`。**
> `103.33` 不是「Epley 的正确答案」，而是 Epley 在这一点上的**系统性高估（+3.3%）**。
> 理由是定义的：1RM 的定义就是「一组只能做一次的最大重量」，`reps = 1` 那一次举起的重量
> **就是** 1RM，不需要任何外推；Epley 是给 `reps >= 2` 做的拟合，所以适用域明确写成 `reps >= 2`。
> 危害不在于 3.3% 这个数字本身，而在于它出现在**最可靠的那个数据点**上：
> 用户做了 100 kg 的单次、界面说 1RM 是 103.33——他一眼就知道不对，
> 然后**不再信任整条 1RM 曲线**。这与 `MAX_REPS = 12` 那条「宁可不算也不要给个假精确的数」
> 是同一把尺子量下来的：`reps = 1` 处的 Epley 值就是**假精确**。
> 测试名改回它本来的 `single_rep_returns_the_weight_itself`——现在它真的成立了。
> 实测：`epley(100, 1) = 100.0`、`epley(62.5, 1) = 62.5`，而 `epley(100, 2) = 106.67`（`reps >= 2` 才走 Epley）。
>
> **裁定二：`best_of_a_session_picks_the_max` 名不副实，已修好。**
> 原三条 `Set(60,8)` / `Set(62.5,6)` / `Set(65,4)` 的估算值恰好**降序**（76.0 / 75.0 / 73.67），
> **最大项就在第一个**，所以把 `bestOf` 改成「返回第一组可估算的值」它**照样绿**——
> 它唯独没能证明的就是它名字里那件事。现在**刻意打乱顺序、让最大项落在中间**
> （62.5x6 = 75.0、60x8 = **76.0**、65x4 = 73.67）。反证实测见 Step 7 的 M04/M05：
> 同一变异在旧顺序下绿、在新顺序下红。
>
> **裁定三：`bestOf` 的返回类型 `double` → `OptionalDouble`，并把「重量不为正」也视为不可估算。**
> 原实现返回 `0.0`，把「本次没有可估算的组」伪装成「1RM 是 0 kg」——
> 一次只做了平板支撑的训练会被画成曲线上的一个 **0 点**，把进步曲线拽到地板上。
> 这违反包内**约定四**（可空的领域配置不得兜底，必须进入类型或状态），
> 先例是 `VolumeCalculator.Status.NOT_CONFIGURED`，同一把尺子。
> `weight == 0`（自重动作记 0 kg，或漏填）与「空列表」在原实现里经 `Math.max(0, 0)`
> 得到**同一个 `0`**，本来就不可区分，跟着一起解决。`Set(0, 8)` 仍然**可以构造**
> （`0` 是合法 `weight`），只是不可估算——这条写进了 `Set` 的注释。
>
> **裁定四：补齐 3 处无覆盖分支的测试。** 首轮那 3 个存活变异
> （`epley` 的 `reps < 1 → reps < 0`、去掉 `epley` 的 `weight < 0` 校验、
> 去掉 `bestOf` 的 `> MAX_REPS` 跳过）现在各有一条测试直接把守卫/契约钉住，
> 收口轮复测**三个全部被杀**（见 Step 7）。
>
> 本 fence 的测试条数 **8 → 11**：首轮 8 条里 1 条改名（裁定一）、1 条重写（裁定二）、
> 2 条改用 `orElseThrow()` 适配新签名（裁定三），本轮新增 3 条（裁定三 1 条 + 裁定四 2 条）。
> ℹ️ Step 2 那段「编译失败」的原始输出属于**首版**测试文件，其中的行号已随 fence 两轮同步而失效；
> 「首次运行必然编译失败（`OneRepMax` 还不存在）」这一事实不变。

> **（2026-10-16，Task 8 质量审查收口轮）本 fence 已第四次同步为磁盘原文，测试 11 → 13 条。**
> 本轮**只补测试与改字，不动 `epley` / `bestOf` / `Set` 的任何行为或签名**（审查结论「有条件批准」
> 的四条 Important 全是补测与改字）。逐条：
>
> - **I-1（`Set` 的 ±∞ 拒绝零覆盖）**：`set_validates_its_own_fields` 补 `±∞` 两条断言。
>   约定三承诺的是「拒绝 `NaN` **与 ±∞**」，而原先只喂了 `NaN` 与 `-60`——实测把
>   `!Double.isFinite(weight)` 改成 `Double.isNaN(weight)` 的变异**存活**（M21）。
> - **I-2（「特判必须排在验证之后」零覆盖）**：`epley_validates_its_own_inputs` 补
>   `epley(NaN, 1)` / `epley(-60, 1)` / `epley(+∞, 1)`。计划声称这个顺序是**有意的**，
>   但没有任何测试走在 `reps == 1` 与校验的相对顺序上——把特判移到方法**最前**的变异**存活**：
>   它会让 `epley(NaN, 1)` 静默返回 `NaN`、`epley(-60, 1)` 返回 `-60`、`epley(+∞, 1)` 返回 `∞`（M22）。
> - **I-3（`epley(0, r) == 0.0` 仍可达）**：新增
>   `epley_of_zero_weight_is_zero_but_best_of_will_not_report_it`，把这个行为标成**有意**；
>   `epley` 的 javadoc 写明**领域入口是 `bestOf`**、不要拿公式结果直接画图或直接取 `max`。
>   反证见 M25（删掉 `bestOf` 的 `weight <= 0` 跳过，该测试变红）。
> - **M-5（合法上界 12 无测试）**：新增 `the_upper_bound_itself_is_still_estimable`。
>   拒绝侧 `13` 早有测试，**合法上界 `12` 此前没有**；实测 `> MAX_REPS` 改成 `>= MAX_REPS`
>   在 `epley` 与 `bestOf` 两处都**存活**（M23/M24）。
> - **M-6（舍入契约不对称且未文档化）**：`epley` 的 javadoc 写明**舍入是有意不对称的**
>   （`reps == 1` 原值直返、不经舍入；`reps >= 2` 走 `Round.to2`），并在
>   `single_rep_returns_the_weight_itself` 补 `epley(60.125, 1) == 60.125` 与
>   `epley(60.125, 2) == 64.13` 两条断言钉住两侧。
> - **M-7（javadoc「适用域是 `reps >= 2`」措辞过强）**：外部资料的主流写法是 Epley 适用 1–10 次、
>   最佳 6–10 次，所以「适用域是 `reps >= 2`」是**对公式适用域的过强断言**。
>   改成**描述事实**：拟合值在 `reps=1` 处比实测高 3.33%，本项目在这一点取实测值，
>   与业界计算器在 1 rep 一行显示实测重量的通行呈现一致。`epley` 的 javadoc 与 `reps == 1`
>   分支里的代码注释同口径改（`bestOf` 的行为一个字没动）。
>
> ℹ️ 本轮的日期用**本机与 git 提交时钟**（2026-10-16）；上一轮的 `2026-10-17` 标注比提交时钟早一天，
> 属既有记录，本轮不做追溯修改。

- [x] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=OneRepMaxTest`

Expected: 编译失败。**注意本机 JDK 是中文 locale，实际输出是中文**，
计划原先写的 `cannot find symbol: class OneRepMax` 与实际措辞不符。实际输出（节选）：

```text
[ERROR] /E:/idea/program/change/backend/src/test/java/com/change/calc/OneRepMaxTest.java:[14,20] 找不到符号
[ERROR]   符号:   变量 OneRepMax
[ERROR]   位置: 类 com.change.calc.OneRepMaxTest
[ERROR] /E:/idea/program/change/backend/src/test/java/com/change/calc/OneRepMaxTest.java:[33,30] 程序包OneRepMax不存在
[INFO] 10 errors
[INFO] BUILD FAILURE
```

- [x] **Step 3: 写实现**

```java
package com.change.calc;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * 估算 1RM。
 *
 * 为什么默认用 1RM 而不是最大重量：最大重量只在冲重量那一次才动，
 * 中间几个月曲线是平的；1RM 由每次的「重量 × 次数」估算，每次训练都能让曲线动一点。
 *
 * 用 Epley 公式：1RM = w × (1 + reps / 30)。
 *
 * Epley 在 reps=1 处的拟合值比实测高 3.33%（w × 31/30），本项目在这一点取实测值——
 * 这与业界计算器的通行呈现一致（它们在 1 rep 一行也显示实测重量）。
 * 理由是定义的：1RM 就是「一组只能做一次的最大重量」，reps = 1 那一次举起的重量
 * 本身就是它，不需要任何外推。
 */
public final class OneRepMax {

    /** 超过这个次数后 Epley 误差迅速变大，不再估算。 */
    public static final int MAX_REPS = 12;

    /**
     * 一组的数据。
     *
     * {@code repetitions} 为 null 表示计时动作（平板支撑等），{@link #bestOf} 会跳过它；
     * 而 {@code repetitions == 0} 是**另一回事**——「这一组记了但没次数」，
     * 它是合法输入（构造器放行），由 {@link #bestOf} 按 {@code < 1} 跳过。
     * 两者的区别是「没有次数这个概念」与「有这个概念但值是 0」，别把它们合并成一个状态。
     *
     * <p>校验放在紧凑构造器里，沿用包内约定：值对象内部字段非法抛
     * {@link IllegalArgumentException}（见 {@link Nutrition} 与 {@link VolumeCalculator.Entry}）。
     * 这里挡掉的是 {@code NaN} 与 {@code ±∞}：{@code NaN} 会绕过 {@code weight < 0}
     * （比较恒为 false），一路走到 {@link Round#to2} 变成 {@code 0.0}——
     * 静默给出「1RM 是 0」这个看起来像数字的错答案。
     *
     * <p>注意 {@code weight == 0} **能构造出来**（0 是合法重量：上面那条校验只管负数与非有限值），
     * 但它**不可估算**：{@link #bestOf} 把 {@code weight <= 0} 当成「不是一次真实的负重」跳过。
     * 「能构造」与「可估算」是两件事——构造器若拒绝 0，会连带拒掉计时动作
     * {@code new Set(0, null)} 这种正常输入。所以 0 的处置落在 {@code bestOf}，不在这里。
     */
    public record Set(double weight, Integer repetitions) {

        public Set {
            if (!Double.isFinite(weight)) {
                throw new IllegalArgumentException("weight 必须是有限值，实际为 " + weight);
            }
            if (weight < 0) {
                throw new IllegalArgumentException("weight 不能为负，实际为 " + weight);
            }
            if (repetitions != null && repetitions < 0) {
                throw new IllegalArgumentException("repetitions 不能为负，实际为 " + repetitions);
            }
        }
    }

    private OneRepMax() {
    }

    /**
     * Epley 公式估算单组 1RM。
     *
     * <p>{@code reps == 1} 时**不外推**，直接返回实测 {@code weight}——
     * 本项目在这一点取实测值而不是 Epley 的拟合值（后者高 3.33%），理由见类注释。
     *
     * <p>注意：本方法是公式本身，不做领域过滤——{@code epley(0, r)} 返回 0。
     * 「0 重量不该产生 1RM 曲线上的点」由 {@link #bestOf} 负责（它会跳过非正重量）。
     * 领域入口是 {@code bestOf}，不要拿本方法的结果直接画图。
     *
     * <p>舍入是不对称的，这是有意的：{@code reps == 1} 走特判、**原值直接返回**、不经舍入
     * （单次的实测重量本身就是精确值，没有需要抹掉的浮点噪声）；
     * {@code reps >= 2} 才走 {@link Round#to2}。
     *
     * @param weight 重量；必须是有限且非负的
     * @param reps   次数；{@code 1..}{@link #MAX_REPS}。超过 {@link #MAX_REPS} 直接拒绝——
     *               Epley 在 15 次以上误差迅速变大，宁可不算也不要给个假精确的数
     */
    public static double epley(double weight, int reps) {
        if (reps < 1) {
            throw new IllegalArgumentException("reps 必须 >= 1: " + reps);
        }
        if (reps > MAX_REPS) {
            throw new IllegalArgumentException(
                    "reps 超过 " + MAX_REPS + " 时 Epley 估算不可靠: " + reps);
        }
        if (!Double.isFinite(weight)) {
            // 排在 weight < 0 之前：NaN 与任何比较都返回 false，会绕过 `weight < 0`，
            // 一路走到 Round.to2(NaN) = 0.0（静默返回 0），∞ 则被饱和成 9.2e16。
            throw new IllegalArgumentException("weight 必须是有限值，实际为 " + weight);
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight 不能为负: " + weight);
        }
        if (reps == 1) {
            // 1RM 的定义就是「一组只能做一次的最大重量」，reps=1 不需要外推。
            // Epley 是给 reps>=2 做的拟合，它在 reps=1 处给 w*31/30，即 3.3% 的系统性高估——
            // 而这是最可靠的一个数据点，高估它会让人不信任整条曲线。
            // 所以本项目在 reps=1 处取实测值，而不是 Epley 的外推值。
            return weight;
        }
        return Round.to2(weight * (1 + reps / 30.0));
    }

    /**
     * 一次训练里某个动作的所有组，取估算出的最大 1RM。
     *
     * 计时动作（{@code repetitions} 为 null）与超范围的次数**跳过而不是抛错**：
     * 一次训练里混着平板支撑和卧推是正常的，因为一组平板支撑让整条曲线算不出来才是不正常的。
     *
     * @param sets 组列表；不能为 null，也不能含 null 元素
     * @return 最高的 1RM；**没有任何可估算的组时返回 {@link java.util.OptionalDouble#empty()}**。
     *         刻意不用 0.0 当哨兵：0 是一个看起来像数字的答案，会被画成曲线上的一个点，
     *         把进步曲线拽到地板上（一次只做了平板支撑的训练就是这种情况）。
     *         计时动作（repetitions 为 null）、次数超出 1..12、以及**重量不为正**的组都不可估算。
     */
    public static OptionalDouble bestOf(List<Set> sets) {
        Objects.requireNonNull(sets, "sets 不能为 null");
        OptionalDouble best = OptionalDouble.empty();
        for (Set s : sets) {
            Objects.requireNonNull(s, "sets 不能含 null 元素");
            Integer reps = s.repetitions();
            if (reps == null || reps < 1 || reps > MAX_REPS) {
                continue;   // 计时动作，或次数超出可估算范围
            }
            if (s.weight() <= 0) {
                continue;   // 0 kg 不是一次真实的负重——自重动作记 0 或漏填都会走到这里
            }
            double oneRm = epley(s.weight(), reps);
            if (best.isEmpty() || oneRm > best.getAsDouble()) {
                best = OptionalDouble.of(oneRm);
            }
        }
        return best;
    }
}
```

> **（2026-10-15，质量审查轮）本 fence 已同步**：这里原先是**第三份逐字相同的 `round2` 拷贝**
> （`private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }`）。
> Task 5 的 Step 3b 已把它抽成包内可见的 `com.change.calc.Round.to2`，
> 所以本任务**直接调用 `Round.to2`，不要再写一份私有拷贝**——
> 否则三处各有一份，改一处就得记得改三处。
> 本任务只改这一处调用与 `import`（同包，无需 import）；`OneRepMax` 的算法逻辑与
> 上表 5 个期望值不受影响（`Round.to2` 的表达式与旧 `round2` 逐字相同）。
> ℹ️ 遗留观察（本轮**不处理**，留给 Task 8 自己判断）：`epley` 只校验了 `weight < 0`，
> **没有校验 `weight` 是否为有限值**——`epley(Double.POSITIVE_INFINITY, 5)` 会返回 `∞`
> 而不抛错。`Round.to2(∞)` 会饱和到 `9.223372036854776E16`（与 Task 5 改动前同一现象）。
> Task 8 若要跟上 `Nutrition` 的校验口径，应在这里补一条有限性校验。
>
> **（2026-10-16，Task 8 落地轮）上述遗留观察已处理**，并补齐包内约定：
> - **`epley` 补有限性校验**（约定三）。为什么必须补：`NaN < 0` 为 `false`，
>   所以 `NaN` **绕过**原先唯一的 `weight < 0` 校验，一路走到 `Round.to2(NaN)` 得到 **`0.0`**——
>   静默返回「1RM 是 0」这个看起来像数字的错答案；`∞` 则被饱和成 `9.223372036854776E16`。
>   校验刻意排在 `weight < 0` **之前**，惯用法 `!Double.isFinite(v)`。
> - **`Set` 加紧凑构造器**：`weight` 有限且非负、`repetitions` 非 null 时不得为负。
>   **`repetitions == 0` 刻意放行**——它表示「这一组记了但没次数」，
>   与 `repetitions == null`（计时动作，「没有次数这个概念」）是**两个不同的事实**，
>   由 `bestOf` 按 `< 1` 跳过。合并这两个状态就是约定四说的那种兜底说谎。
> - **`bestOf` 加 null 防护**：`sets` 与元素分别 `Objects.requireNonNull`（NPE，约定风格）。
> - 对应新增 3 条测试：`rejects_non_finite_weight`、`set_validates_its_own_fields`、
>   `best_of_rejects_null_input`。最后一条的断言刻意用「不能」而**不是**「null」——
>   JDK 裸 NPE 的消息里本来就含 `"null"`（`Cannot invoke "..." because "s" is null`），
>   用 `"null"` 做断言会**恒真**：删掉 `requireNonNull` 之后测试照样绿。
>   这个坑 Task 6/7 各踩过一次，注释原样保留在测试里。
>
> **（2026-10-17，Task 8 收口轮）本 fence 已第三次同步为磁盘原文，两处实现改动：**
> - **`epley` 加 `reps == 1` 特判，直接返回 `weight`**（依据见 Step 1 下的「收口轮」裁定一）。
>   这条特判**排在有限性校验与 `weight < 0` 之后**是有意的：`weight` 的合法性属于前置条件，
>   「怎么估」属于算法——顺序颠倒会让 `epley(NaN, 1)` 静默返回 `NaN` 而不是抛错。
> - **`bestOf` 返回 `double` → `OptionalDouble`，并新增 `weight <= 0` 跳过**（裁定三）。
>   为什么写 `<= 0` 而不是 `< 0`：`0` 不是一次真实的负重（自重动作记 0 或漏填），
>   而 `epley(0, reps)` 恒为 `0.0`——它会以「1RM 是 0」的形式进入曲线。
>   这条跳过**不能**挪进 `Set` 的紧凑构造器：那里拒绝 `0` 会连带拒掉计时动作 `new Set(0, null)`，
>   而 `repetitions == null`（没有次数这个概念）与 `weight == 0`（有重量这个概念但值是 0）
>   是两个不同的事实，正是约定四要求别合并的东西。
> - 类注释里写下 Epley 的**适用域 `reps >= 2`**（原先只在测试注释里隐含）。
>
> **（2026-10-16，Task 8 质量审查收口轮）本 fence 已第四次同步为磁盘原文，只有注释改动：**
> - **I-3**：`epley` 的 javadoc 新增「本方法是公式本身，不做领域过滤——`epley(0, r)` 返回 0；
>   「0 重量不该产生 1RM 曲线上的点」由 `bestOf` 负责（它跳过非正重量）；**领域入口是 `bestOf`**，
>   不要拿本方法的结果直接画图」。
> - **M-6**：`epley` 的 javadoc 新增「舍入是不对称的，这是有意的」一段
>   （`reps == 1` 原值直返、不经舍入；`reps >= 2` 才走 `Round.to2`）。
> - **M-7**：类注释里「Epley 的适用域是 `reps >= 2`」删掉，改成**描述事实**
>   （拟合值在 `reps=1` 处比实测高 3.33%、本项目在这一点取实测值、与业界计算器的通行呈现一致）；
>   `epley` 的 javadoc 与 `reps == 1` 分支里的代码注释同口径改；
>   `Set` 的 javadoc 里「上面那条校验只管负数与 NaN」更正为「只管负数与非有限值」。
> - ⚠️ 本轮**没有任何可执行语句改动**：算法、签名、`MAX_REPS` 全部逐字不变。

- [x] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=OneRepMaxTest`

Expected: `Tests run: 13, Failures: 0, Errors: 0`
（原计划写 5；修正一/二不改条数，B/C/D 各 +1 → 8；收口轮裁定三/四 +3 → **11**；
质量审查收口轮 I-3 与 M-5 各新增 1 条、I-1/I-2/M-6 是既有测试里补断言不改条数 → **13**。
以 `mvn` 输出为准。）

实测（收口轮，`cd backend && mvn -B test -Dtest=OneRepMaxTest`）：

```text
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.025 s -- in com.change.calc.OneRepMaxTest
```

实测（质量审查收口轮，`cd backend && mvn -B test -Dtest=OneRepMaxTest`）：

```text
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.123 s -- in com.change.calc.OneRepMaxTest
```

- [x] **Step 5: 跑全量回归**

Run: `cd backend && mvn -B test`

Expected: **`Tests run: 79, Failures: 0, Errors: 0`**（66 + 本任务 13 = 79）。
逐项：`NutritionCalculatorTest` 13、`PortionConverterTest` 15、`VolumeCalculatorTest` 17、
`SchemaTest` 9、`MigrationTest` 5、`DataDirectoryInitializerTest` 3、
`ChangeApplicationTests` 2、`TrainingRepoTest` 2、`OneRepMaxTest` 13。
（首轮实测 74 = 66 + 8；收口轮 OneRepMaxTest 8 → 11，故 77；质量审查收口轮 11 → 13，故 79。
以 `mvn` 输出为准。）

实测（收口轮，删 `target` 后 `cd backend && mvn -B test`）：

```text
[INFO] Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

实测（质量审查收口轮，删 `target` 后 `cd backend && mvn -B test`）：

```text
[INFO] Tests run: 79, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [x] **Step 6: 提交**

```bash
git add backend/src/main/java/com/change/calc/OneRepMax.java \
        backend/src/test/java/com/change/calc/OneRepMaxTest.java \
        docs/superpowers/plans/2026-10-14-change-01-foundation.md
git commit -F <提交信息文件>
```

提交信息说清（**收口轮**）：`epley` 的 `reps == 1` 特判直接返回实测重量
（附 3.3% 系统性高估的实测与理由）、`bestOf` 改 `OptionalDouble` 并跳过非正重量
（附「一次只做平板支撑被画成 0 点、拽平曲线」的危害）、修好名不副实的
`best_of_a_session_picks_the_max`、补齐 3 处无覆盖分支的测试，
以及本计划 fence 第三轮同步（首轮的 76.0 修正保留；103.33 那条按裁定一撤销）。

- [x] **Step 7（首轮新增，收口轮复测）：变异测试实测与遗留待办**

逻辑用**变异测试实测**过一轮（不是纸面推断）：对实现逐个注入变异、跑 `OneRepMaxTest`、
记录哪条断言变红。**17 个变异：14 杀、3 存活**（另有一个「半杀」，见下）。

下表是**首轮（8 条测试时）的原样记录**；收口轮在 11 条测试上复测了全部变异，
结果见本步末尾「收口轮复测」——两处不一致时以复测为准。

| 变异 | 结果 |
| --- | --- |
| `reps / 30.0` → `reps / 30`（整数除法） | 杀（4 条同时红） |
| 去掉 `Round.to2` 包装 | 杀，但**只有** `single_rep_is_still_scaled_by_epley` 一条红（该测试收口轮改名为 `single_rep_returns_the_weight_itself`） |
| `MAX_REPS` 12 → 20 | 杀 |
| `bestOf`：`Math.max` → `Math.min` | 杀（2 条） |
| 去掉 `bestOf` 的 `repetitions() == null` 短路 | 杀（NPE） |
| 去掉 `sets` 的 `requireNonNull` | 杀 |
| 去掉元素的 `requireNonNull` | 杀 |
| 去掉 `epley` 的有限性校验 | 杀 |
| 去掉 `Set` 的有限性校验 | 杀 |
| `Set`：`repetitions < 0` → `<= 0` | 杀 |
| 去掉 `Set` 的 `weight < 0` 校验 | 杀 |
| 去掉 `Set` 的 `repetitions < 0` 校验 | 杀 |
| 异常消息里去掉 `12` | 杀（消息断言是具体的，非恒真） |
| `epley`：`reps < 1` → `reps < 0`（放行 0 次） | **存活** → 收口轮**已杀** |
| 去掉 `epley` 的 `weight < 0` 校验 | **存活** → 收口轮**已杀** |
| 去掉 `bestOf` 的 `> MAX_REPS` 跳过 | **存活** → 收口轮**已杀** |
| `bestOf` 改成返回第一组可估算的值 | 半杀：`best_of_a_session_picks_the_max` **照样绿** → 收口轮**已杀** |

**3 个存活变异 = 3 处没被测到的分支**（首轮**不追加**测试，超出 A–F 授权，登记为待办）：
1. `epley(_, 0)`——`reps < 1` 这条**下界**没有任何测试走过（`bestOf` 自己先按 `< 1` 跳过，
   所以 0 次永远到不了 `epley`）。
2. `epley(-60, 5)`——`epley` 自己的 `weight < 0` 校验没被走过（`Set` 的构造器先挡住了负数）。
3. `bestOf` 里「次数 > 12 的组**跳过而不是抛错**」这条契约没被走过
   （没有任何测试把 `Set(w, 13..)` 喂给 `bestOf`）。

> **收口轮处置（2026-10-17）：三处全部补测、三个变异全部被杀。**
> 第一条与第二条由新测试 `epley_validates_its_own_inputs` 钉住——它**直接调 `epley` 这个公开入口**，
> 绕开 `Set` 的构造器，所以 `reps = 0` 与 `weight = -60` 真能走到那两条守卫；
> 第三条由 `out_of_range_reps_are_skipped_not_thrown_by_best_of` 钉住
> （`bestOf([Set(60,20), Set(62.5,6)])` 必须返回 75.0 而不是抛错）。
> 这也是它们首轮存活的原因：**被测的入口错了**——测试只经由 `Set` 这个「已经更严的入口」调用，
> `epley` 自己的守卫就成了不可达分支。

**最弱的一条断言：`best_of_a_session_picks_the_max`。**
它的名字声称在测「取最大」，但三条数据 `Set(60,8)` / `Set(62.5,6)` / `Set(65,4)` 的估算值
恰好是**降序**（76.0 / 75.0 / 73.67），**最大项就在第一个**。实测：把 `bestOf` 改成
「返回第一组可估算的值」（完全不取最大），这条测试**照样绿**——
它唯独没能证明的就是它名字里那件事。建议（**未擅自改**）：打乱列表顺序，或让最大项落在中间/最后。

> **收口轮处置（2026-10-17）：已按「打乱顺序、最大项落中间」修好。**
> 现在顺序是 `Set(62.5,6)` / `Set(60,8)` / `Set(65,4)`，最大项 76.0 在**中间**。
> 反证是成对做出来的（见下表 M04/M05）：**同一个变异**（返回第一组可估算的值）
> 在**旧顺序**下绿、在**新顺序**下红——所以这条测试现在真的在测「取最大」，
> 而不是在测「恰好第一项最大」。

**两条判断（首轮上报未改，收口轮均已落实）：**
- **`bestOf(List.of())` 返回 `0`：不理想。** 这个兜底值把「本次没有可估算的组」伪装成
  「1RM 是 0 kg」。按约定四（可空的领域配置**不得兜底**，必须进入类型或状态），
  这里应返回 `OptionalDouble.empty()`——`VolumeCalculator.Status.NOT_CONFIGURED` 是同一个道理的先例。
  实际危害：一次只做了平板支撑的训练会被画成曲线上的一个 **0 点**，把进步曲线拽到地板上。
  **首轮签名不改**（不在首轮授权内）→ **收口轮已改成 `OptionalDouble`**（裁定三）。
- **`weight == 0` 与「空列表」不可区分：重要，但与上一条同源。** `epley(0, 5) = 0.0`、
  `Math.max(0, 0) = 0`，所以两者在 `bestOf` 里得到同一个 `0`。`weight == 0` 在真实数据里
  几乎必然是「自重动作记了 0 kg」或漏填，不是真的 1RM。它**不需要单独修**：
  两种情况的正确处理是同一个（**别把这个 0 画进曲线**），跟着 `OptionalDouble` 一起解决即可。
  另外 `weight == 0` **不能**被构造器拒绝——`timed_sets_are_ignored` 里就有 `new OneRepMax.Set(0, null)`。
  **收口轮按此执行**：`bestOf` 里加了 `weight <= 0` 跳过，构造器的校验一个字没动。

ℹ️ **待人工决定（首轮已上报，未自作主张）：`reps == 1` 要不要特判直接返回 `weight`？**
特判能让单次实测不被高估 3.3%（也更符合用户直觉），但它是**算法改动**：
会让 `epley` 不再是纯 Epley，并要同时改掉本文件里写死的公式说明。超出首轮 A–F 授权。
**→ 人工裁定：特判。理由与实测见 Step 1 下的「收口轮」裁定一。**

- [x] **Step 8（收口轮新增）：复测全部变异 + 验收的原始输出**

**8.1 变异复测（20 个变异体，11 条测试）：19 杀 + 1 个刻意保留的对照（M05）存活。**

> ⚠️ **数字更正（2026-10-16，质量审查收口轮）**：本行原写「**20 杀、0 存活**」，
> 与紧接的下表 M05 那一行「**存活**（反证）」**自相矛盾**。M05 是**刻意保留的反证对照**
> （目的就写在同一行：同一变异在旧降序顺序下全绿，证明原来的测试测不出「取最大」），
> 所以它不是漏杀、更不是造假——但「0 存活」这个**汇总数字是错的**，
> 而汇总数字会被下游直接引用。现改为「**19 杀 + 1 个刻意保留的对照（M05）存活**」。
> 本轮另新增 5 个变异体（M21–M25）复测，见 Step 9。
> ℹ️ 上面的「20 个变异体 / 11 条测试」是**那一轮**的快照，本轮不改动那些原始输出。

每个变异体都是对**磁盘原文**做一处逐字文本替换（脚本先断言「替换点唯一」，不唯一就中止），
删掉 `backend/target`，再跑 `mvn -B test -Dtest=OneRepMaxTest`；跑完由脚本 `finally` 恢复原文，
并校验恢复后的 sha256 与原始一致（`RESTORED ok=True`）。

| # | 变异 | 结果 | 变红的测试 |
| --- | --- | --- | --- |
| M01 | `epley`：`reps < 1` → `reps < 0`（放行 0 次） | **杀**（首轮存活） | `epley_validates_its_own_inputs` |
| M02 | 去掉 `epley` 的 `weight < 0` 校验 | **杀**（首轮存活） | `epley_validates_its_own_inputs` |
| M03 | 去掉 `bestOf` 的 `> MAX_REPS` 跳过 | **杀**（首轮存活） | `no_estimable_sets_yields_empty_not_zero`、`out_of_range_reps_are_skipped_not_thrown_by_best_of` |
| M04 | `bestOf` 只取第一组可估算的值（当前**乱序**测试） | 杀 | `best_of_a_session_picks_the_max` |
| M05 | 同 M04 + **旧的降序测试顺序** | **存活**（反证） | —（11 条全绿） |
| M06 | 去掉 `bestOf` 的 `weight <= 0` 跳过 | 杀（本轮新增行为） | `no_estimable_sets_yields_empty_not_zero` |
| M07 | `epley` 去掉 `reps == 1` 特判 | 杀（本轮新增行为） | `single_rep_returns_the_weight_itself` |
| M08 | 去掉 `Round.to2` 包装 | 杀 | `single_rep_returns_the_weight_itself` |
| M09 | `reps / 30.0` → `reps / 30`（整数除法） | 杀 | `best_of_a_session_picks_the_max`、`epley_for_a_typical_set`、`out_of_range_reps_are_skipped_not_thrown_by_best_of`、`single_rep_returns_the_weight_itself`、`timed_sets_are_ignored` |
| M10 | `bestOf`：`oneRm > best` → `oneRm < best` | 杀 | `best_of_a_session_picks_the_max` |
| M11 | `MAX_REPS` 12 → 20 | 杀 | `epley_validates_its_own_inputs`、`high_reps_are_rejected_because_the_formula_degrades`、`no_estimable_sets_yields_empty_not_zero`、`out_of_range_reps_are_skipped_not_thrown_by_best_of` |
| M12 | 去掉 `bestOf` 的 `repetitions == null` 短路 | 杀 | `no_estimable_sets_yields_empty_not_zero`、`timed_sets_are_ignored`（NPE） |
| M13 | 去掉 `bestOf` 的 `sets` 上 `requireNonNull` | 杀 | `best_of_rejects_null_input` |
| M14 | 去掉 `bestOf` 的元素 `requireNonNull` | 杀 | `best_of_rejects_null_input` |
| M15 | 去掉 `epley` 的有限性校验 | 杀 | `rejects_non_finite_weight` |
| M16 | 去掉 `Set` 的有限性校验 | 杀 | `set_validates_its_own_fields` |
| M17 | 去掉 `Set` 的 `weight < 0` 校验 | 杀 | `set_validates_its_own_fields` |
| M18 | `Set`：`repetitions < 0` → `<= 0` | 杀 | `set_validates_its_own_fields`、`no_estimable_sets_yields_empty_not_zero` |
| M19 | 去掉 `Set` 的 `repetitions < 0` 校验 | 杀 | `set_validates_its_own_fields` |
| M20 | 异常消息里去掉 `12` | 杀 | `epley_validates_its_own_inputs`、`high_reps_are_rejected_because_the_formula_degrades` |

三个首轮存活变异体的原始输出（mvn 日志摘录）：

```text
M01 → Tests run: 11, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   OneRepMaxTest.epley_validates_its_own_inputs:78 
M02 → Tests run: 11, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   OneRepMaxTest.epley_validates_its_own_inputs:80 
M03 → Tests run: 11, Failures: 0, Errors: 2, Skipped: 0
[ERROR]   OneRepMaxTest.no_estimable_sets_yields_empty_not_zero:68 » IllegalArgument reps 超过 12 时 Epley 估算不可靠: 20
[ERROR]   OneRepMaxTest.out_of_range_reps_are_skipped_not_thrown_by_best_of:90 » IllegalArgument reps 超过 12 时 Epley 估算不可靠: 20
```

⚠️ **M04/M05 的第一次写法是错的，已重做。** 第一版把变异写成「遇到第一组就 `return`」，
它同时短路掉了元素的 `requireNonNull`，于是 `best_of_rejects_null_input` 也红，
**旧顺序那一次同样红**（`Failures: 1`）——反证不成立。
改成只动累加条件的 `if (best.isEmpty())`（循环与全部守卫原样保留）之后：
M04 在**乱序**测试下红（`Tests run: 11, Failures: 1, Errors: 0`），
M05 在**旧降序**测试下**全绿**（`Tests run: 11, Failures: 0, Errors: 0`）——
这正是「原来的测试测不出『取最大』」的硬证据，也是裁定二的验收第 4 条。

**8.2 全量回归（删 `target` 后 `cd backend && mvn -B test`）**

```text
[INFO] Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**8.3 `epley` 的四个返回值（直接跑公开入口，不经 `Set`）**

```text
=== acceptance 2: epley(weight, reps) ===
epley(100, 1)  = 100.0
epley(62.5, 1) = 62.5
epley(100, 2)  = 106.67
epley(62.5, 6) = 75.0
```

**8.4 `bestOf` 的两态（`OptionalDouble` 真的分得开）**

```text
=== acceptance 3: bestOf(list) ===
bestOf([])                    = OptionalDouble.empty
bestOf([Set(0, null)])        = OptionalDouble.empty
bestOf([Set(0, 8)])           = OptionalDouble.empty
bestOf([Set(60, 8)])          = OptionalDouble[76.0]
bestOf([Set(62.5,6),Set(60,8),Set(65,4)]) = OptionalDouble[76.0]
```

前三个 `empty` 里有两个是**首轮无法区分**的情形：`Set(0, 8)`（重量为 0）与空列表
在原实现下都得到 `0.0` 兜底值，现在都是 `empty`；第四个证明「有可估算的组」仍然给出数。
`bestOf([Set(60, 8)])` 这条同时证实 `timed_sets_are_ignored` 里那个 76.0 不受裁定一影响
（`60 × (1 + 8/30) = 76.0`，`reps >= 2` 走 Epley）。

- [x] **Step 9（质量审查收口轮新增）：4 处零覆盖不变量的补测复测 + 计划数字更正**

**9.1 新增 5 个变异体（M21–M25）：5 杀、0 存活。**

每个变异体都是对**磁盘原文**做一处逐字文本替换（脚本先断言「替换点唯一」，不唯一就中止），
删掉 `backend/target`，再跑 `mvn -B test -Dtest=OneRepMaxTest`；跑完由脚本 `finally` 恢复原文，
并校验恢复后的 sha256 与原始一致（`RESTORED ok=True`，5/5）。

| # | 变异 | 结果 | 变红的测试 |
| --- | --- | --- | --- |
| M21 | `Set`：`!Double.isFinite(weight)` → `Double.isNaN(weight)` | **杀**（本轮新增覆盖） | `set_validates_its_own_fields` |
| M22 | `epley`：把 `reps == 1` 特判移到方法**最前** | **杀**（本轮新增覆盖） | `epley_validates_its_own_inputs` |
| M23 | `epley`：`reps > MAX_REPS` → `reps >= MAX_REPS` | **杀**（本轮新增覆盖） | `the_upper_bound_itself_is_still_estimable` |
| M24 | `bestOf`：`reps > MAX_REPS` → `reps >= MAX_REPS` | **杀**（本轮新增覆盖） | `the_upper_bound_itself_is_still_estimable` |
| M25 | 删掉 `bestOf` 的 `if (s.weight() <= 0) continue;` | 杀（I-3 反证） | `epley_of_zero_weight_is_zero_but_best_of_will_not_report_it`、`no_estimable_sets_yields_empty_not_zero` |

M21–M24 在本轮补测**之前是存活的**——这正是 I-1 / I-2 / M-5 三条审查结论的实测依据：
四条新断言各自只杀掉自己那一个变异（M21 红在 `set_validates_its_own_fields` 的 `+∞` 那一行、
M22 红在 `epley(NaN, 1)` 那一行、M23/M24 红在新增的 `the_upper_bound_itself_is_still_estimable`），
说明**覆盖确实来自新增的断言**，不是被既有断言顺手接住。
M25 是**反证**：删掉那条跳过之后 I-3 的新测试**确实变红**，
说明「0 不进入 1RM 曲线」是被测试钉住的，不是纸面承诺。

原始输出（mvn 日志摘录，每个变异体都先删 `target`）：

```text
M21 → Tests run: 13, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   OneRepMaxTest.set_validates_its_own_fields:143
[ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
M22 → Tests run: 13, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   OneRepMaxTest.epley_validates_its_own_inputs:107
[ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
M23 → Tests run: 13, Failures: 0, Errors: 1, Skipped: 0
[ERROR]   OneRepMaxTest.the_upper_bound_itself_is_still_estimable:35 » IllegalArgument reps 超过 12 时 Epley 估算不可靠: 12
[ERROR] Tests run: 13, Failures: 0, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
M24 → Tests run: 13, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   OneRepMaxTest.the_upper_bound_itself_is_still_estimable:37
[ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
M25 → Tests run: 13, Failures: 2, Errors: 0, Skipped: 0
[ERROR]   OneRepMaxTest.epley_of_zero_weight_is_zero_but_best_of_will_not_report_it:93
[ERROR]   OneRepMaxTest.no_estimable_sets_yields_empty_not_zero:84
[ERROR] Tests run: 13, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

恢复后复核（脚本 `finally` 已恢复原文并校验 sha256 一致，再跑 `-Dtest=OneRepMaxTest`）：

```text
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.123 s -- in com.change.calc.OneRepMaxTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**9.2 全量回归（删 `target` 后 `cd backend && mvn -B test`）**

```text
[INFO] Tests run: 79, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**9.3 计划数字更正**：8.1 的汇总数字「20 杀、0 存活」与同表 M05「存活（反证）」矛盾，
已改为「19 杀 + 1 个刻意保留的对照（M05）存活」（见 8.1 的更正注）。
M05 本身是刻意设计的反证对照，**保留不动**。

---

## Task 9: ChangeLog 与回退

这是「智能体可写」那条决定的安全带。

**Files:**
- Create: `backend/src/main/java/com/change/service/ChangeLogService.java`
- Test: `backend/src/test/java/com/change/service/ChangeLogServiceTest.java`

- [x] **Step 1: 写失败的测试**

```java
package com.change.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ChangeLogServiceTest {

    @Autowired ChangeLogService logs;
    @Autowired JdbcTemplate jdbc;

    private long insertFood(String name, double kcal) {
        jdbc.update("INSERT INTO food(name_zh, source, kcal_per100) VALUES (?,?,?)",
                name, "manual", kcal);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /**
     * 绕过服务层直写一行 change_log，用来构造「库里已经有一行服务层写不出来的坏数据」：
     * target_table 不在白名单、before_json 的键不是合法列名。
     * 服务层的正常路径写不出这种行，所以只能手工造——但库里的行**可以**长这样
     * （target_table 上没有 CHECK），undo 必须自己扛住。
     */
    private long insertRawLog(String action, String targetTable, String beforeJson) {
        jdbc.update("INSERT INTO change_log(changed_at, origin, action, target_table, " +
                    "target_id, before_json, after_json, undone) VALUES (?,?,?,?,?,?,?,0)",
                "2026-10-16T00:00:00", "agent", action, targetTable, 1L,
                beforeJson, "{\"x\":1}");
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Test
    void records_an_insert_with_null_before() {
        long id = insertFood("测试食物A", 100);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "测试食物A"),
                "user", null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT origin, action, target_table, before_json, after_json " +
                "FROM change_log WHERE id = ?", logId);
        assertThat(row.get("origin")).isEqualTo("user");
        assertThat(row.get("action")).isEqualTo("insert");
        assertThat(row.get("before_json")).isNull();
        assertThat((String) row.get("after_json")).contains("测试食物A");
    }

    @Test
    void records_the_chat_session_id_when_there_is_one() {
        // chat_session_id 是设计文档给 ChangeLog 列的字段之一（「会话 id」），
        // 而在此之前**没有任何测试传过非 null 的值**——把它整个写成 null 的变异能活下来
        // （实测 M17：11 条全绿）。所以补这一条，把「日志行真的挂在会话上」钉住。
        // 注意 change_log.chat_session_id 上没有 REFERENCES，这里仍先建一行会话，
        // 走的是将来真实调用会走的形状。
        jdbc.update("INSERT INTO chat_session(title, created_at) VALUES (?,?)",
                "会话", "2026-10-16T00:00:00");
        long sessionId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        long id = insertFood("测试食物I", 900);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "测试食物I"),
                "agent", sessionId);

        assertThat(jdbc.queryForObject(
                "SELECT chat_session_id FROM change_log WHERE id = ?", Long.class, logId))
                .isEqualTo(sessionId);
    }

    @Test
    void rollback_of_an_insert_deletes_the_row() {
        long id = insertFood("测试食物B", 200);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "测试食物B"),
                "agent", null);

        logs.undo(logId);

        Integer remain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, id);
        assertThat(remain).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId))
                .isEqualTo(1);
    }

    @Test
    void rollback_of_an_update_restores_previous_values() {
        long id = insertFood("测试食物C", 300);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100", 300.0), Map.of("kcal_per100", 999.0),
                "agent", null);

        jdbc.update("UPDATE food SET kcal_per100 = 999 WHERE id = ?", id);
        logs.undo(logId);

        Double kcal = jdbc.queryForObject(
                "SELECT kcal_per100 FROM food WHERE id = ?", Double.class, id);
        assertThat(kcal).isEqualTo(300.0);
    }

    @Test
    void cannot_undo_twice() {
        long id = insertFood("测试食物D", 400);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "测试食物D"),
                "agent", null);
        logs.undo(logId);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已经撤销过");
    }

    @Test
    void rejects_unknown_table() {
        assertThatThrownBy(() -> logs.recordInsert("drop_table_attempt", 1,
                Map.of("x", 1), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
        // 两条写入路径共用 write() 里的白名单校验，但「共用」是实现的巧合而不是契约，
        // 所以 recordUpdate 这一侧也单独钉一条（before 合法，卡住的只能是表名）。
        long id = insertFood("测试食物H", 800);
        assertThatThrownBy(() -> logs.recordUpdate("drop_table_attempt", id,
                Map.of("kcal_per100", 800.0), Map.of("kcal_per100", 801.0), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void every_allowed_table_has_a_single_integer_id_column() {
        // 回退靠 WHERE id = ? 定位目标行。复合主键的表（exercise_muscle）定位不到，
        // 所以白名单的准入条件是「必须有单列整型主键 id」。
        // 这条测试是给未来的自己设的闸：往白名单加表时忘了这个条件，它会立刻红。
        for (String table : com.change.service.ChangeLogService.ALLOWED_TABLES) {
            // 表名只能拼进 SQL（pragma_table_info 的参数是标识符，不是绑定值）；
            // 它来自本项目的常量，不是外部输入。
            // 另：PRAGMA table_info(...) 是**表函数**，只取一列——取多列会让
            // queryForList(sql, String.class) 抛 IncorrectResultSetColumnCountException。
            var cols = jdbc.queryForList(
                    "SELECT name FROM pragma_table_info('" + table + "')", String.class);
            assertThat(cols).as("表 %s 的列", table).contains("id");

            String idType = jdbc.queryForMap(
                    "SELECT type, pk FROM pragma_table_info('" + table + "') WHERE name = 'id'")
                    .get("type").toString().toUpperCase();
            assertThat(idType).as("表 %s 的 id 类型", table).isEqualTo("INTEGER");

            Integer pkCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE pk > 0",
                    Integer.class);
            assertThat(pkCount).as("表 %s 的主键列数", table).isEqualTo(1);
        }
    }

    @Test
    void undo_rejects_a_non_identifier_column_name() {
        // 参数占位符保护值，保护不了标识符——列名是拼进 SQL 的。
        // 这一条同时也是「注入确实会被执行」的反证：键名本身是 `kcal_per100 = 1, name_zh`，
        // 去掉校验后它就会变成 SQL 的一部分（M02 实测变红）。
        // 报错文案随校验方式改成了按 schema 校验（详见 writableColumns 的注释），
        // 断言跟着改成钉「这个恶意列名被点名」+ 那句说明，而不是钉具体的措辞。
        long id = insertFood("测试食物E", 500);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100 = 1, name_zh", 1.0),   // 恶意键
                Map.of("kcal_per100", 1.0), "agent", null);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kcal_per100 = 1, name_zh")
                .hasMessageContaining("可回退写入的列");
    }

    @Test
    void undo_refuses_to_rewrite_the_primary_key() {
        // SQLite 的 rowid / _rowid_ / oid 是主键的别名（INTEGER PRIMARY KEY 表上就是 id），
        // 它们能通过任何标识符正则，而 SET rowid = ? 是合法 SQL。
        // 不拦的话，撤销会静默把目标行的主键搬走，而 change_log.target_id 仍指向旧 id——
        // 后续 undo 会定位到别的行。
        long id = insertFood("测试食物H", 800);
        long targetId = id;

        for (String pkAlias : java.util.List.of("id", "rowid", "_rowid_", "oid")) {
            long logId = logs.recordUpdate("food", targetId,
                    Map.of(pkAlias, 7L), Map.of("kcal_per100", 1.0), "agent", null);

            assertThatThrownBy(() -> logs.undo(logId))
                    .as("主键别名 %s 必须被拒绝", pkAlias)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(pkAlias);

            // 拒绝之后 undone 必须保持 0：不能留下「日志说撤了、数据没动」的半截状态
            assertThat(jdbc.queryForObject(
                    "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)).isZero();
            // 目标行必须原地不动
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, targetId)).isEqualTo(1);
        }
    }

    @Test
    void undo_refuses_a_column_that_does_not_exist() {
        // 拼写错误与保留字都落到这里（select 这类保留字通过任何标识符正则，只在 SQLite 层报语法错）
        long id = insertFood("测试食物I", 900);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100_typo", 1.0), Map.of("kcal_per100", 1.0), "agent", null);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kcal_per100_typo");
    }

    @Test
    void undo_fails_when_the_target_row_is_gone() {
        // 影响 0 行却照样置 undone=1 的话，审计日志会声称「已撤销」而数据什么都没动。
        long id = insertFood("测试食物J", 1000);
        long logId = logs.recordUpdate("food", id,
                Map.of("kcal_per100", 1000.0), Map.of("kcal_per100", 1.0), "agent", null);

        jdbc.update("DELETE FROM food WHERE id = ?", id);   // 目标行先没了

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("目标行已不存在");
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)).isZero();
    }

    @Test
    void undo_of_a_referenced_row_translates_the_foreign_key_error() {
        // undo 一条 insert 是物理 DELETE。这条 food 已被 meal_item.food_id 引用
        // （meal_item.food_id 是裸 REFERENCES，默认 NO ACTION），DELETE 会被外键拦下。
        // 不翻译的话，用户看到的是一句 SQLITE_CONSTRAINT_FOREIGNKEY 天书。
        long foodId = insertFood("测试食物K", 1100);
        long logId = logs.recordInsert("food", foodId, Map.of("name_zh", "测试食物K"),
                "agent", null);

        jdbc.update("INSERT INTO meal(meal_date, slot) VALUES (?,?)", "2026-10-16", "lunch");
        long mealId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO meal_item(meal_id, food_id, order_index, amount, grams) " +
                    "VALUES (?,?,?,?,?)", mealId, foodId, 0, 100.0, 100.0);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("引用");
        // 报错路径同样不能留下半截状态：food 还在，日志也还是「没撤」。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM food WHERE id = ?", Integer.class, foodId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId)).isZero();
    }

    @Test
    void undo_of_a_cascaded_parent_row_silently_drops_the_join_rows() {
        // 契约的另一半（见 ChangeLogService 的类 javadoc）：撤销一个被 CASCADE 引用的父行
        // 会连带删掉子行，而快照两侧都不含子行。这条测试把这个**实际后果**钉下来，
        // 免得将来做「动作编辑」的人以为撤销后再重做能拿回肌群标注。
        jdbc.update("INSERT INTO muscle_group(name_zh, name_en, body_part) VALUES (?,?,?)",
                "胸", "ChangE Test Chest", "chest");
        long muscleId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO exercise(name_en, name_zh) VALUES (?,?)",
                "ChangE Test Bench Press", "测试卧推");
        long exerciseId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO exercise_muscle(exercise_id, muscle_group_id, role) VALUES (?,?,?)",
                exerciseId, muscleId, "primary");
        long logId = logs.recordInsert("exercise", exerciseId,
                Map.of("name_en", "ChangE Test Bench Press"), "agent", null);

        logs.undo(logId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise WHERE id = ?", Integer.class, exerciseId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_muscle WHERE exercise_id = ?",
                Integer.class, exerciseId))
                .as("级联删掉了映射行，而快照里没有它——这就是契约里写明的缺口")
                .isZero();
    }

    @Test
    void undo_of_a_set_null_parent_row_silently_clears_the_referencing_column() {
        // meal.photo_id 是 ON DELETE SET NULL：撤销 photo 的创建会**静默**清空 meal.photo_id，
        // 被引用的那一侧没有任何报错可言。契约里写明这个后果。
        jdbc.update("INSERT INTO photo(taken_on, rel_path, sha256, kind) VALUES (?,?,?,?)",
                "2026-10-16", "photos/2026/10/16/changelog-test.png",
                "changelog-test-sha256", "food");
        long photoId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO meal(meal_date, slot, photo_id) VALUES (?,?,?)",
                "2026-10-16", "dinner", photoId);
        long mealId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        long logId = logs.recordInsert("photo", photoId,
                Map.of("rel_path", "photos/2026/10/16/changelog-test.png"), "agent", null);

        logs.undo(logId);

        assertThat(jdbc.queryForObject(
                "SELECT photo_id FROM meal WHERE id = ?", Long.class, mealId))
                .as("SET NULL 把这一格清空了，而回退过程一声不响")
                .isNull();
    }

    @Test
    void record_update_requires_a_before_snapshot() {
        long id = insertFood("测试食物F", 600);
        assertThatThrownBy(() -> logs.recordUpdate("food", id, null,
                Map.of("kcal_per100", 1.0), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before")
                // ⚠️ 上面那行 hasMessageContaining("before") 在本测试里**实际没有判别力**：
                // 真正先失败的是它前一行 isInstanceOf(IllegalArgumentException.class)——
                // 去掉 Java 侧校验后 DB 兜底抛的是 UncategorizedSQLException（M03 实测），
                // 类型断言先失败，消息断言根本执行不到。
                // （原先写的理由「DB 原文含 before_json，稀释了判别力」不对：DB 原文确实含
                // before_json，但那句话不是这条断言失效的原因，类型断言才是。）
                // 保留并补上「快照」是因为将来若有人放松类型断言，它是消息层面的真防线。
                .hasMessageContaining("快照");
        assertThatThrownBy(() -> logs.recordUpdate("food", id, Map.of(),
                Map.of("kcal_per100", 1.0), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before")
                .hasMessageContaining("快照");
    }

    @Test
    void rejects_empty_after_and_unknown_origin() {
        long id = insertFood("测试食物G", 700);
        assertThatThrownBy(() -> logs.recordInsert("food", id, null, "agent", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("after");
        assertThatThrownBy(() -> logs.recordInsert("food", id, Map.of(), "agent", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("after");
        assertThatThrownBy(() -> logs.recordInsert("food", id,
                Map.of("name_zh", "x"), "robot", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("origin");
    }

    @Test
    void undo_of_an_unknown_log_id_is_rejected() {
        assertThatThrownBy(() -> logs.undo(999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到");
    }

    @Test
    void undo_rejects_a_target_table_outside_the_whitelist() {
        // undo 的 target_table 来自**数据库**，不是来自本次调用的参数——
        // 库里一行坏数据就是一条越权回退路径（能删/改白名单外的任何表）。
        // 删掉 undo 里的 requireAllowed(table) 时，只有这条测试会红。
        long logId = insertRawLog("insert", "drop_table_attempt", null);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
        // 拦住之后不能留下「已撤销」的半截状态，否则再点一次撤销就无事可做。
        assertThat(jdbc.queryForObject(
                "SELECT undone FROM change_log WHERE id = ?", Integer.class, logId))
                .isZero();
    }
}
```

- [x] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=ChangeLogServiceTest`

Expected: 编译失败，`cannot find symbol: class ChangeLogService`

> ℹ️ 实跑（本机 Maven 的 locale 是 zh_CN，编译器输出的是同一句话的中文）：
> `[ERROR] ChangeLogServiceTest.java:[18,16] 找不到符号` /
> `[ERROR]   符号:   类 ChangeLogService`，即 `cannot find symbol: class ChangeLogService`。
> 那两个行号属于首版 5 条测试的文件，已随 fence 同步失效；
> 「首次运行必然编译失败（`ChangeLogService` 还不存在）」这一事实不变。原始输出见 6.1。

- [x] **Step 3: 写实现**

```java
package com.change.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变更日志：让「智能体可写」这件事有安全带。
 *
 * 三条设计约束（设计文档决定 4）：
 *   1. 表名必须在白名单里 —— 工具集是白名单，不是让模型自由写 SQL
 *   2. 只记录 insert / update，不提供 delete —— 删除无法撤销，所以干脆不给
 *   3. 每条改动存下 before / after 的 JSON 快照，可一键回退
 *
 * ## 回退的语义（契约）
 *
 * - **update 的回退是按列写回**：只有 `before` 快照里出现的列会被恢复，
 *   未出现的列保持当前值。所以「回退一次部分修改」是合法且精确的——
 *   调用方只需把**这次改动实际动过的列**放进 `before`。
 * - 但这也意味着：如果调用方漏放了某一列，那一列**不会被恢复，且不会报错**。
 *   写 `before` 的地方应当由「改动前读到的整行」或「本次改动涉及的列」构造，
 *   不要凭记忆手写列名。
 * - **列名必须真实存在于目标表且不是主键**；SQLite 的 rowid / _rowid_ / oid
 *   是主键别名，会被拒绝（否则回退会搬走目标行的主键）。
 * - **撤销一个被外键 CASCADE 引用的父行会连带删掉子行**，而快照里不含子行。
 *   典型例子：撤销 `exercise` 的创建会级联删掉 `exercise_muscle` 的映射行，
 *   而快照两侧都不含映射——「撤销后再重做」会静默丢掉肌群标注。
 *   动作编辑功能落地时必须扩展快照（记下 muscle_map）或改为整组重建 join 行。
 */
@Service
public class ChangeLogService {

    /**
     * 允许被记录与回退的表。
     *
     * **准入条件：必须有单列整型主键 `id`。** 回退靠 `WHERE id = ?` 定位目标行，
     * 复合主键的表（如 exercise_muscle，主键是 (exercise_id, muscle_group_id)）
     * 定位不到，所以不进白名单——它的增删应通过所属的 exercise 记录来审计。
     *
     * 加表必须改这里，改这里必须同时加测试（见 ChangeLogServiceTest 的白名单自检）。
     */
    public static final Set<String> ALLOWED_TABLES = Set.of(
            "food", "food_portion", "meal", "meal_item",
            "exercise",
            "workout_session", "session_exercise", "workout_set",
            "body_metric", "photo");

    /**
     * 合法的变更来源。必须与 {@code V4__auxiliary.sql} 里
     * {@code change_log.origin} 的 {@code CHECK (origin IN ('user','agent'))} 一致。
     *
     * <p>用 {@link List} 而不是 {@code Set}：报错文案要把它拼进消息，而 {@code Set.of}
     * 的迭代顺序按 JVM 运行随机（同一个集合两次运行可能给出不同顺序），
     * 拼进消息就成了「同一处错误、两种文案」。包内 {@code VolumeCalculator.VALID_ROLES}
     * 出于同一理由也是 {@code List}。
     */
    public static final List<String> ALLOWED_ORIGINS = List.of("user", "agent");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public ChangeLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long recordInsert(String table, long targetId, Map<String, Object> after,
                             String origin, Long chatSessionId) {
        return write(table, targetId, "insert", null, after, origin, chatSessionId);
    }

    public long recordUpdate(String table, long targetId, Map<String, Object> before,
                             Map<String, Object> after, String origin, Long chatSessionId) {
        if (before == null || before.isEmpty()) {
            // 数据库侧有 CHECK (action <> 'update' OR before_json IS NOT NULL) 兜底，
            // 但在 Java 侧先拦能给出能读懂的报错，而不是一句 CHECK constraint failed。
            throw new IllegalArgumentException(
                    "update 记录必须带 before 快照，否则撤销时无从恢复");
        }
        return write(table, targetId, "update", before, after, origin, chatSessionId);
    }

    private long write(String table, long targetId, String action,
                       Map<String, Object> before, Map<String, Object> after,
                       String origin, Long chatSessionId) {
        requireAllowed(table);
        if (!ALLOWED_ORIGINS.contains(origin)) {
            // schema 侧同样有 CHECK (origin IN ('user','agent')) 兜底；
            // 在这里先拦的理由与上面 before 那条相同：把约束翻译成能读懂的话。
            throw new IllegalArgumentException(
                    "origin 必须是 " + String.join(" 或 ", ALLOWED_ORIGINS)
                            + "，实际为 " + origin);
        }
        if (after == null || after.isEmpty()) {
            // 不拦的话 toJson(null) 会写出**字面量字符串 "null"**：after_json 是 NOT NULL，
            // 于是库里留下一行「看起来合法、语义为空」的记录。
            throw new IllegalArgumentException("after 快照不能为空");
        }
        jdbc.update("INSERT INTO change_log(changed_at, origin, action, target_table, " +
                    "target_id, before_json, after_json, chat_session_id, undone) " +
                    "VALUES (?,?,?,?,?,?,?,?,0)",
                LocalDateTime.now().format(ISO), origin, action, table, targetId,
                before == null ? null : toJson(before), toJson(after), chatSessionId);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /**
     * 回退一条改动。insert 的回退是删除该行；update 的回退是写回 before 值。
     *
     * {@code @Transactional} 不是可选的：本方法要写两处（目标表 + change_log）。
     * 没有事务时第二步失败会留下「数据删了、日志说没撤」的矛盾状态，
     * 而审计日志与数据不一致正是这个类最不该有的失效。
     * 传播用默认（REQUIRED）——连接池只有 1 条连接，REQUIRES_NEW 会自己等自己。
     */
    @Transactional
    public void undo(long logId) {
        // 用 queryForList 而不是 queryForMap：查不到时前者给一个空列表，
        // 后者抛 Spring 的 EmptyResultDataAccessException——对调用方是一句读不懂的话。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, target_table, target_id, before_json, undone " +
                "FROM change_log WHERE id = ?", logId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到这条变更记录: " + logId);
        }
        Map<String, Object> row = rows.get(0);

        if (((Number) row.get("undone")).intValue() == 1) {
            throw new IllegalStateException("这条改动已经撤销过了: " + logId);
        }

        String table = (String) row.get("target_table");
        long targetId = ((Number) row.get("target_id")).longValue();
        String action = (String) row.get("action");
        // target_table 来自**数据库**（不是本次调用的参数，那个在写入时已经查过一遍），
        // 而 target_table 上没有 CHECK：一行被写坏的日志就能变成一条越权回退路径
        // （删掉/改写白名单之外的任何表）。所以这里必须重新校验。
        requireAllowed(table);

        int affected;
        try {
            if ("insert".equals(action)) {
                affected = jdbc.update("DELETE FROM " + table + " WHERE id = ?", targetId);
            } else {
                Map<String, Object> before = fromJson((String) row.get("before_json"));
                if (before == null || before.isEmpty()) {
                    throw new IllegalStateException("update 记录缺少 before 快照: " + logId);
                }
                // sets 与 values 必须来自**同一次** entrySet() 迭代。
                // 「keySet() 与 values() 顺序一致」是实现细节而不是 Map 的契约：
                // 两次独立迭代一旦错位，就会把 A 列的值写给 B 列——静默写错数据，且不报错。
                StringBuilder sets = new StringBuilder();
                List<Object> values = new ArrayList<>();
                Set<String> writable = writableColumns(table);
                for (Map.Entry<String, Object> e : before.entrySet()) {
                    String column = e.getKey();
                    if (!writable.contains(column)) {
                        throw new IllegalArgumentException(
                                "列名「" + column + "」不是 " + table + " 上可回退写入的列"
                                        + "（必须真实存在且不是主键——SQLite 的 rowid/_rowid_/oid 是主键别名）");
                    }
                    if (sets.length() > 0) {
                        sets.append(", ");
                    }
                    sets.append(column).append(" = ?");
                    values.add(e.getValue());
                }
                values.add(targetId);
                affected = jdbc.update(
                        "UPDATE " + table + " SET " + sets + " WHERE id = ?", values.toArray());
            }
        } catch (DataAccessException e) {
            // 回退一条 insert 是物理 DELETE，会被外键拦下：默认动作（NO ACTION）直接抛
            // SQLITE_CONSTRAINT_FOREIGNKEY，用户看到的是一句天书。这里翻译成能读懂的话。
            //
            // 为什么抓的是 DataAccessException 而不是 DataIntegrityViolationException（实测）：
            // Spring 的 SQLite 错误码映射表里 **没有** 19 = SQLITE_CONSTRAINT，所以
            // JdbcTemplate 把这个外键违例落到了兜底类 UncategorizedSQLException——
            // 它**不是** DataIntegrityViolationException 的子类（后者继承
            // NonTransientDataAccessException）。两者是兄弟，instanceof 判不出来。
            // 实测证据：catch DataIntegrityViolationException 版本跑
            // undo_of_a_referenced_row_translates_the_foreign_key_error，
            // 断言报 “Expecting actual throwable to be an instance of
            // java.lang.IllegalStateException but was:
            // org.springframework.jdbc.UncategorizedSQLException … [SQLITE_CONSTRAINT_FOREIGNKEY]”。
            // DataAccessException 是两者的共同父类，且本方法里不会有别的东西抛它。
            throw new IllegalStateException(
                    "回退失败：目标行被其他记录引用（" + table + ".id = " + targetId
                            + "）。请先撤销引用它的记录。原始错误: "
                            + e.getMostSpecificCause().getMessage(), e);
        }

        if (affected == 0) {
            // 影响 0 行却照样写 undone=1 的话，审计日志会声称「已撤销」而数据什么都没动。
            // 这条检查必须在 UPDATE change_log 之前——@Transactional 会把前面的写入一起回滚。
            throw new IllegalStateException(
                    "目标行已不存在，" + table + ".id = " + targetId + "，无法回退（未标记为已撤销）");
        }

        jdbc.update("UPDATE change_log SET undone = 1 WHERE id = ?", logId);
    }

    private void requireAllowed(String table) {
        if (!ALLOWED_TABLES.contains(table)) {
            throw new IllegalArgumentException(
                    "表「" + table + "」不在白名单里，拒绝记录或回退");
        }
    }

    /**
     * 某张表上「可被回退写入」的列名集合（即：真实存在、且不是主键的列）。
     *
     * 为什么要按 schema 校验而不是用黑名单：SQLite 的 rowid / _rowid_ / oid
     * 是主键的**别名**（在 INTEGER PRIMARY KEY 表上就是 id），它们能通过任何
     * 标识符正则，而 `SET rowid = ?` 是合法 SQL——用黑名单会漏，且维护不动。
     * 「必须真实存在且不是主键」同时关掉伪列、保留字、拼写错误三类问题。
     *
     * 缓存：schema 由 Flyway 冻结，进程内不会变。
     */
    private final Map<String, Set<String>> writableColumnsCache = new ConcurrentHashMap<>();

    private Set<String> writableColumns(String table) {
        return writableColumnsCache.computeIfAbsent(table, t -> Set.copyOf(jdbc.queryForList(
                "SELECT name FROM pragma_table_info('" + t + "') WHERE pk = 0", String.class)));
    }

    private String toJson(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("序列化快照失败", e);
        }
    }

    private Map<String, Object> fromJson(String s) {
        if (s == null) return null;
        try {
            return json.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("解析快照失败", e);
        }
    }
}
```

- [x] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=ChangeLogServiceTest`

Expected（原计划写 5，落地轮**实测 12**）：原计划 5 条 + A2/B/C/D/E 的 5 条 + 本轮自查
补的 2 条（undo 白名单、chat_session_id）= **`Tests run: 12, Failures: 0, Errors: 0`**。
> ⚠️ 安全审查轮之后本类已增至 **18 条**（新增 4 条 + 2 条契约测试，见 6.7），
> 全量套件 **102 条**；下面 6.1 的原始输出是**落地轮当时**的记录，保持原样。
原始输出见 6.1，两条补测的依据见 6.3 的 M09 / M17。

- [x] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/service/ChangeLogService.java \
        backend/src/test/java/com/change/service/ChangeLogServiceTest.java
git commit -m "feat: 变更日志与一键回退，表名白名单 + 禁止二次撤销"
```

> ℹ️ 本轮实际提交：提交信息写成「做了什么 + 为什么」的完整中文说明（A–F 八处收口逐条写清
> 依据），并用 `git commit -F <文件>` 提交；本计划文档与两个源文件一起进同一次提交
> （与 Task 5–8 各轮的提交口径一致）。上面这段原计划的命令**保留不动**。

- [x] **Step 6（落地轮新增）：A–F 全部落地 + 17 个变异体实测 + schema 交叉验证**

> 本轮（Task 9 落地轮）的改动记档。Step 1 / Step 3 两个 ```java fence 已**首次同步为磁盘原文**，
> 由脚本逐字比对，identical=True。
> ⚠️ 这两个 fence 在**安全审查轮**（见 6.7）里被再次同步到了当时的磁盘原文：
> 此后测试 18 条 362 行、实现 254 行，
> sha256 `eb7d0b19f54456de…` / `de34a2206ca326bf…`（两份文件无 BOM、无 U+FFFD、CRLF=0）；
> 落地轮当时的 sha 是 `c857ade0963530bf…` / `a7a0037955e400c6…`。
>
> **原计划 5 条测试 → 落地 12 条**：A2/B/C/D/E 各 1 条（共 10 条），另 2 条是本轮**变异测试实测**
> 出来的零覆盖项——undo 的表名白名单（M09）、`chat_session_id`（M17）。两条都不是顺手多写，
> 而是先看到变异存活才补的。

#### 6.1 TDD 三步的原始输出

Step 2（先写测试、跑一次确认失败）：
```text
[ERROR] COMPILATION ERROR : 
[ERROR] /E:/idea/program/change/backend/src/test/java/com/change/service/ChangeLogServiceTest.java:[18,16] 找不到符号
[ERROR] /E:/idea/program/change/backend/src/test/java/com/change/service/ChangeLogServiceTest.java:[119,47] 找不到符号
[ERROR] 2 errors 
[INFO] BUILD FAILURE
[ERROR]   符号:   类 ChangeLogService
[ERROR]   位置: 类 com.change.service.ChangeLogServiceTest
```
（本机 Maven 的 locale 是 zh_CN，`cannot find symbol: class ChangeLogService` 显示为
`找不到符号 / 符号: 类 ChangeLogService`，是同一句话。）

Step 4（写实现后单类跑绿；**删 `target` 后重跑**，避免拿到陈旧 class）：
```text
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.076 s -- in com.change.service.ChangeLogServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Step 5 全量回归（**删 `target` 后** `cd backend && mvn -B test`）：
```text
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.calc.NutritionCalculatorTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.calc.OneRepMaxTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.calc.PortionConverterTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.calc.VolumeCalculatorTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.ChangeApplicationTests
[INFO] Tests run:  3, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.config.DataDirectoryInitializerTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.MigrationTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.repo.TrainingRepoTest
[INFO] Tests run:  9, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.SchemaTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.service.ChangeLogServiceTest
[INFO] Tests run: 91, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
79 → **91**（+12）。`SchemaTest` 9 条不变——本轮**没有动 schema**。

#### 6.2 A–F 的落地

**A1 先核实**：`V2__training.sql:70-75`

```sql
CREATE TABLE exercise_muscle (
    exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,
    muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE,
    role            TEXT    NOT NULL CHECK (role IN ('primary', 'secondary')),
    PRIMARY KEY (exercise_id, muscle_group_id)
);
```

主键是 `(exercise_id, muscle_group_id)`，**没有 `id` 列**——`undo` 的
`DELETE FROM <table> WHERE id = ?` 对它必然抛 `no such column: id`。
计划的 5 条测试全用 `food`，所以这个缺陷测不出来。
落地：把 `exercise_muscle` 移出 `ALLOWED_TABLES`，并在常量 javadoc 里把准入条件写成
**「必须有单列整型主键 `id`」**；A2 的自检测试（`pragma_table_info` 逐表核 `id` /
`INTEGER` / 主键列数）把这条条件钉死——M01 反证实测：把它加回白名单，只有 A2 那条红。

**B** `undo` 的 UPDATE 路径加 `requireIdentifier`（列名是拼进 SQL 的，占位符保护不了标识符），
并补一条**真会执行注入**的测试（键名 `kcal_per100 = 1, name_zh`）。M02：去掉校验 → 该测试红
（说明注入的 SQL 确实被执行，而不是「本来就不可达」）。

**C** `recordUpdate` 前置拒绝 `null` / 空 `before`。M03：去掉 Java 侧校验后抛的是
`UncategorizedSQLException`，消息 `CHECK constraint failed: action <> 'update' OR
before_json IS NOT NULL` —— 正是「读不懂的报错」。

**D1** `write` 前置拒绝 `null` / 空 `after`。M04 的实测输出把失效形态写得很清楚：
`expected: null but was: "null"` —— 不拦的话 `toJson(null)` 写出**字面量字符串 `"null"`**，
`after_json` 是 NOT NULL，于是库里留下一行「看起来合法、语义为空」的记录。

**D2** `origin` 白名单 `ALLOWED_ORIGINS = List.of("user","agent")`，与
`change_log.origin` 的 `CHECK` 逐字一致；用 `List` 而非 `Set` 是为了让拼进报错文案的顺序确定
（`Set.of` 的迭代顺序按 JVM 运行随机）。M05：去掉校验 → `CHECK constraint failed:
origin IN ('user','agent')`。

**E** `undo` 改用 `queryForList` + 空列表检查，对不存在的 `logId` 抛
`IllegalArgumentException("找不到这条变更记录: …")`。M06：去掉检查 → `IndexOutOfBoundsException`。

**F** `sets` 与 `values` 改为**同一次 `entrySet()` 迭代**构造。注意：M07（退回两次独立迭代）
**存活**——在当前设计下 `before` 总是经 Jackson 反序列化回来的 `LinkedHashMap`，
两次迭代顺序一致，所以 F 是纵深防御、**没有测试钉住**，不假装它被测住（见 6.6 顾虑 1）。

#### 6.3 G：变异测试（17 个变异体，**16 杀 / 1 存活**）

每个变异体都是对**磁盘原文**做一处逐字文本替换（脚本先断言「替换点唯一」，不唯一就中止：
M03 的替换点起初写法命中了 2 处——`undo` 里那处缩进更深，8 空格串是 12 空格串的**子串**，
带上后即唯一），删掉 `backend/target`，再跑 `mvn -B test -Dtest=ChangeLogServiceTest`；
跑完由脚本 `finally` 恢复原文并校验 sha256
（主轮 16 次 + M17 两轮，**18/18 次 `RESTORED ok=True sha=a7a0037955e400c6…`**）。

> 两次跑的测试条数不同：M01–M16 与 M17 的补测前一轮都跑在 **11 条**测试的文件上
> （那是补 `chat_session_id` 那条之前），M17 的补测后一轮跑在 **12 条**上——
> 这个条数差本身就是「新增覆盖确实来自新断言」的证据。

| # | 变异（对磁盘原文做一处逐字替换） | 结果 | 变红的测试 |
| --- | --- | --- | --- |
| M01 | 把 `exercise_muscle` 加回 `ALLOWED_TABLES`（反证 A1） | **杀** | `every_allowed_table_has_a_single_integer_id_column` |
| M02 | 删掉 UPDATE 路径的 `requireIdentifier`（B） | **杀** | `undo_rejects_a_non_identifier_column_name` |
| M03 | 删掉 `recordUpdate` 的 before 前置校验（C） | **杀** | `record_update_requires_a_before_snapshot` |
| M04 | 删掉 `write` 的 after 前置校验（D1） | **杀** | `rejects_empty_after_and_unknown_origin` |
| M05 | 删掉 origin 白名单校验（D2） | **杀** | `rejects_empty_after_and_unknown_origin` |
| M06 | 删掉 undo 的「找不到」空检查（E） | **杀** | `undo_of_an_unknown_log_id_is_rejected` |
| M07 | 退回「keySet() 拼 sets + values() 取数」两次独立迭代（F） | **存活**（反证，见 6.6） | —（11 条全绿） |
| M08 | 删掉写入侧 `requireAllowed(table)`（白名单） | **杀** | `rejects_unknown_table` |
| M09 | 删掉回退侧 `requireAllowed(table)`（白名单） | **杀** | `undo_rejects_a_target_table_outside_the_whitelist` |
| M10 | 删掉二次撤销检查 | **杀** | `cannot_undo_twice` |
| M11 | insert 时 before 写成字面量 `"null"` 而不是 SQL NULL | **杀** | `records_an_insert_with_null_before` |
| M12 | undo 永远走 update 分支 | **杀** | `cannot_undo_twice`、`rollback_of_an_insert_deletes_the_row` |
| M13 | undo 的 insert 分支不真的删行 | **杀** | `rollback_of_an_insert_deletes_the_row` |
| M14 | undo 完成后不标记 undone | **杀** | `cannot_undo_twice`、`rollback_of_an_insert_deletes_the_row` |
| M15 | update 回退拿 after 快照冒充 before | **杀** | `rollback_of_an_update_restores_previous_values`、`undo_rejects_a_non_identifier_column_name` |
| M16 | 回退时把列名当值写回（键值错配） | **杀** | `rollback_of_an_update_restores_previous_values` |
| M17 | `chat_session_id` 写成 null | **杀** | `records_the_chat_session_id_when_there_is_one` |

原始输出摘录（每个变异体都先删 `target`）：
```text
M01 → Tests run: 11, Failures: 1, Errors: 0  [表 exercise_muscle 的列] Expecting ["exercise_id","muscle_group_id","role"] to contain ["id"]
M02 → Tests run: 11, Failures: 1, Errors: 0  undo_rejects_a_non_identifier_column_name:148 Expecting code to raise a throwable.
M03 → Tests run: 11, Failures: 1, Errors: 0  ... but was: UncategorizedSQLException ... CHECK constraint failed: action <> 'update' OR before_json IS NOT NULL
M06 → Tests run: 11, Failures: 1, Errors: 0  ... but was: java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
M07 → Tests run: 11, Failures: 0, Errors: 0  BUILD SUCCESS   ← 唯一存活
M09 → Tests run: 11, Failures: 1, Errors: 0  ... DELETE FROM drop_table_attempt WHERE id = ? （白名单没拦住就会被执行）
M11 → Tests run: 11, Failures: 1, Errors: 0  records_an_insert_with_null_before:52 expected: null but was: "null"
M16 → Tests run: 11, Failures: 1, Errors: 0  rollback_of_an_update_restores_previous_values:84 expected: 300.0 but was: 0.0
```

M17 分两次跑，用来证明补测**确实**是覆盖的来源（与 Task 8 的 M21–M24 同一手法）：
```text
补测前（11 条）：M17 SURVIVED Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 []
补测后（12 条）：M17 KILLED   Tests run: 12, Failures: 1, Errors: 0, Skipped: 0 ['records_the_chat_session_id_when_there_is_one']
```

**M09 是本节最重要的一条**：去掉 `undo` 里的 `requireAllowed(table)` 时，
计划里的 10 条测试**全绿**——`rejects_unknown_table` 用的是 `recordInsert`，
`recordUpdate` / `undo` 两条路径都没被表名白名单覆盖。而 `undo` 的 `target_table`
来自**数据库**（不是调用参数）、该列上又没有 `CHECK`，所以一行被写坏的日志就是一条
真实的越权回退路径。补测后才被杀。

#### 6.4 G：恒真断言自查

- 没有 `hasMessageContaining("null")` 这类恒真写法（JDK 裸 NPE 的消息本身就含 `null`）。
- 但本轮发现了**同一类、更隐蔽**的一处：`record_update_requires_a_before_snapshot`
  里的 `hasMessageContaining("before")` **实际没有判别力**。
  > ⚠️ **理由已在安全审查轮改正**（原先写的理由不准确）：真正先失败的是它前面那行
  > `isInstanceOf(IllegalArgumentException.class)`——去掉 Java 侧校验后 DB 兜底抛的是
  > `UncategorizedSQLException`（M03 实测），**类型断言先失败，消息断言根本执行不到**。
  > 原先写的「DB 原文含 `before_json`，稀释了判别力」虽然也是事实，但**不是**这条断言失效的原因。
  同理 `rejects_empty_after_and_unknown_origin` 里 `hasMessageContaining("origin")` 的处境一样
  （DB 原文 `origin IN ('user','agent')` 含 `origin`，但类型断言先挡住）。
  处置（本轮不变，只是把理由改准）：保留 `hasMessageContaining("快照")`——DB 原文里没有「快照」，
  将来若有人放松类型断言，它就是消息层面的真防线。
- **最弱的一条**：`records_an_insert_with_null_before` 只断言了
  `origin` / `action` / `before_json` / `after_json` 四列，而这条 SQL **查了 5 列**
  （`target_table` 查出来却没用）。`target_table` / `target_id` 只是被 undo 的测试
  **间接**覆盖（undo 从库里读它们）；`chat_session_id` 在本轮之前**零覆盖**（M17 存活）。

#### 6.5 H：schema 交叉验证（读迁移文件 + 机械核对，不凭推理）

`V4__auxiliary.sql:69-87` 的 `change_log` 原文：

```sql
CREATE TABLE change_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    changed_at  TEXT    NOT NULL,
    origin      TEXT    NOT NULL CHECK (origin IN ('user','agent')),
    action      TEXT    NOT NULL CHECK (action IN ('insert','update')),
    target_table TEXT   NOT NULL,
    target_id   INTEGER NOT NULL,
    before_json TEXT,                  -- insert 时为 NULL
    after_json  TEXT    NOT NULL,
    chat_session_id INTEGER,
    undone      INTEGER NOT NULL DEFAULT 0,
    CHECK (action <> 'update' OR before_json IS NOT NULL)
);
CREATE INDEX ix_change_log_target ON change_log(target_table, target_id);
CREATE INDEX ix_change_log_time ON change_log(changed_at);
```

逐列与实现对账：

| 列 | schema | 实现 | 一致？ |
| --- | --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | 不回写，只读 `last_insert_rowid()` | ✅ |
| `changed_at` | TEXT NOT NULL（**无**形状 CHECK） | `LocalDateTime.now().format(ISO_LOCAL_DATE_TIME)` | ✅（见下） |
| `origin` | NOT NULL + `CHECK (origin IN ('user','agent'))` | D2 的 `ALLOWED_ORIGINS` 逐字一致 | ✅ |
| `action` | NOT NULL + `CHECK (action IN ('insert','update'))` | 只由 `recordInsert`/`recordUpdate` 写死两个字面量，无 delete | ✅ |
| `target_table` | TEXT NOT NULL（**无** CHECK） | 写入前 `requireAllowed`；回退前**再查一次**（值来自库） | ✅（M08/M09） |
| `target_id` | INTEGER NOT NULL | `long` | ✅ |
| `before_json` | TEXT（可空） | insert 写 SQL NULL；update 前置拒绝 null/空 | ✅（M03/M11） |
| `after_json` | **TEXT NOT NULL** | D1 前置拒绝 null/空 | ✅（M04） |
| `chat_session_id` | INTEGER（可空，**无** REFERENCES） | 直传 | ✅（M17） |
| `undone` | NOT NULL DEFAULT 0 | 显式写 0，回退后写 1 | ✅（M14） |

- **CHECK 与 Java 校验的一致性**：三处 Java 校验都比 DB 更**早**、报错更**可读**，
  而不是更宽松——DB 约束是兜底。唯一的**刻意不对称**是 C：DB 只要求
  `before_json IS NOT NULL`（`'{}'` 合法），Java 连**空快照**也拒——因为空快照回到
  `undo` 时会被「update 记录缺少 before 快照」挡住，那一行永远撤销不了（M12 实测了这句报错）。
- **`changed_at` 的格式**：schema 只有 `TEXT NOT NULL`，**没有**日期形状 CHECK——这与
  `meal.meal_date` / `body_metric.measured_on` / `photo.taken_on` /
  `workout_session.session_date` 四个**纯日期**列的 `GLOB 'YYYY-MM-DD'` 是刻意的对照
  （`V4:17-18` 对 `eaten_at` 有同款注释：真时间戳不该加日期形状 CHECK）。
  `ISO_LOCAL_DATE_TIME` 与它一致。**实测（JDK 25 单文件探针，不是推理）**：

  ```text
  [2026-10-16T10:06:00]  len=19  parse-back=2026-10-16T10:06
  [2026-10-16T10:06:30]  len=19  parse-back=2026-10-16T10:06:30
  [2026-10-16T10:06:30.123456]  len=26  parse-back=2026-10-16T10:06:30.123456
  [2026-10-16T10:06:00.000000001]  len=29  parse-back=2026-10-16T10:06:00.000000001
  ```
  秒**总是**在（原以为「秒为 0 时会被省略」，探针否掉了这个猜测）；纳秒非 0 时才追加
  `.SSSSSS`（3/6/9 位）。前 19 字符定长，字典序 == 时间序
  （`…T10:06:00` < `…T10:06:00.000000001`，实测 -10），所以 `ix_change_log_time` 上的
  范围扫描与排序成立；同一个 formatter 也能 parse 回来。
  **遗留（本轮不改）**：schema 与测试都没把「`changed_at` 必须是这个形状」钉住。
- **白名单 10 张表的 `id`（机械核对 V1–V4 的 DDL 文本，不是推理）**：
  10/10 都是 `id INTEGER PRIMARY KEY AUTOINCREMENT`、主键列数 = 1；
  `exercise_muscle` 无 `id` 列，且是唯一的表级复合主键。
  V1–V4 共 16 条 `CREATE TABLE`，其中 `schema_probe` 在 `V2:33` 被 `DROP`，余 15 张 = 「十五张表」。
- **`exercise_muscle` 移出白名单之后的审计路径（判断 + 建议，本轮未改别的代码）**：
  它为 `exercise` 而存在（两列都是 `ON DELETE CASCADE` 的子键，自身没有独立身份，
  `role` 只是关系的属性），所以**审计单元应该是 exercise，而不是这条关系**——
  一次「给动作加/删肌群」应当落成一条挂在 `exercise` 上的 `action='update'` 日志，
  由 before/after 快照承载肌群映射集合。**但当前快照格式承载不了它**：
  `before/after` 是扁平 `Map<String,Object>`，`undo` 只会拼 `列 = ?`。
  因此建议（留给做「动作编辑工具」的那个任务）：要么给快照加一个受控的
  `muscle_map` 段（形如 `[{"muscle_group_id":3,"role":"primary"}]`）由服务层解释，
  要么把 undo 的口径定成「按快照里的映射集合**整组重建** join 行」。
  **现在这一步不能省**：白名单拒绝写入 `exercise_muscle` 是对的（工具层不许直接写它），
  但如果不换成 exercise 级的审计，肌群映射的改动就会落在 `change_log` 之外——
  而 `undo` 一条 exercise 的 insert 会**级联**删掉它的映射行，
  快照里若没有映射，「撤销后再重做」就会静默丢掉肌群标注。

  > ⚠️ **安全审查轮实测并纠正（concern 6）**：上面「应通过所属的 exercise 记录来审计」
  > 这条**降级路径在当前实现下不成立**——`undo` 只回放父行、不重建 join 行。
  > 实测 `exercise_muscle` 行数 before undo = 1 / after undo = 0 / after redo = 0；
  > 也就是说，移出白名单之后肌群标注目前**既不在日志里、也回不来**。
  > 这个缺口由本任务自己引入，已升级为必须交给「动作编辑工具」任务的**前置条件**，
  > 详见 6.7 的 concern 6 与 6.8。

#### 6.6 自查发现与顾虑（本轮**未**改的项）

1. **F 没有测试钉住（M07 存活）**。当前 `before` 一律来自 Jackson 的 `LinkedHashMap`，
   两次独立迭代的顺序一致，所以旧写法在本设计下不可达——F 是纵深防御。
   要真钉住它得喂一个 `keySet()` 与 `values()` 顺序不一致的 `Map`，而 JSON 往返会把这种
   分歧抹平（`undo` 只从库里读快照），所以在现有形状下**测不到**。不留假测试。
2. **undo 可能被外键挡下，而报错读不懂**。undo 一条 `insert` 是物理 `DELETE`：
   若这条 `food` 已被 `meal_item.food_id` 引用（`foreign_keys=true`，且该外键没有
   `ON DELETE` 动作），`DELETE` 会抛 FK 违例，用户看到的是一句 SQLite 报错。
   建议后续在 `undo` 里接住并翻译成「这条记录已被 X 引用，请先撤销 X」。
   本轮**不在 A–F 范围内**，未改。
3. **`ALLOWED_ORIGINS` 与 DDL 没有防漂移测试**。`VolumeCalculator.VALID_ROLES` 有
   `SchemaTest.role_whitelist_matches_the_ddl` 做双向收口，`ALLOWED_ORIGINS` 与
   `change_log.origin` / `action` 的 `CHECK` 目前只靠注释对齐。建议后续把
   「Java 侧白名单 ↔ `sqlite_master.sql` 里的值集」的比对扩到 `origin` / `action`。
4. **`changed_at` 的形状没被钉住**（见 6.5）：schema 没有 CHECK，测试也没断言。
   现状一致，但将来换 `Instant` / 带 `Z` 的格式时没有任何东西会提醒。
5. 本轮**没有**动 `V1–V4`、`application.yml`、`pom.xml`、`spike/`、`calc/`；
   `SchemaTest` 的 9 条结构快照全绿。

#### 6.7 安全审查轮（Task 9 收口，2026-10-16）

审查对 Task 9 的结论是**不批准（1 Critical + 5 Important/Minor）**。本轮逐条关闭，
下面每一节都是**实测**而不是推理；完整原始输出见 6.8。

**C1（Critical）`undo` 能静默改写目标行的主键。** `rowid` / `_rowid_` / `oid` 是 SQLite 给
主键的**别名**（`INTEGER PRIMARY KEY` 表上就是 `id`），它们能通过任何标识符正则，而
`SET rowid = ?` 是合法 SQL。实测：`before_json = {"rowid": 5}` 通过 `requireIdentifier`，
`undo` 报 SUCCEEDED，目标行被搬到 id 5，而 `change_log.target_id` 还指向旧 id——
**后续 undo 会定位到别的行**。同一件事用普通列名 `id` 也能做到（`{"id": 7}`），
而任何把整行 `queryForMap` 结果当 `before` 传进来的调用方，`before` 里天然含 `id`。
处置：**不用黑名单**（会漏保留字与将来的伪列，且维护不动），改成按目标表的**真实 schema**
校验——`writableColumns(table)` 取 `pragma_table_info` 里 `pk = 0` 的列名集合，
快照的键必须在这个集合里。这一条同时关掉**伪列、保留字、拼写错误**三类问题
（`select` / `where` / `check` 不是真实列名，顺带被挡）。`requireIdentifier` 与它的正则
**已删除**（被 schema 校验取代，不再保留第二道：同一件事测两遍没有额外收益，
而保留一个「看起来在防注入、其实谁也拦不住」的正则只会让人误判防线在哪）。

**C2（Important）`undo` 的三条 SQL 不在一个事务里。** 实测（触发器强制第 3 步失败）：
`DELETE` 已提交、`UPDATE change_log SET undone = 1` 失败 → **数据删了、日志说没撤**。
而测试类上的 `@Transactional` 让 12 条测试全跑在一个测试事务里，**生产形态的这个行为
测试完全观察不到**——这正是它此前全绿的原因。处置：给 `undo` 加
`@Transactional`（`org.springframework.transaction.annotation.Transactional`，
传播用默认 `REQUIRED`；连接池 `maximum-pool-size=1`，`REQUIRES_NEW` 会自己等自己）。
**两条路都选了**：新建**不带 `@Transactional`** 的测试类
`ChangeLogServiceUndoTransactionTest` 用触发器做真实回滚验证（行为证据），
外加一条反射断言钉住「注解在、且传播是 REQUIRED」（快、且能挡住 `REQUIRES_NEW`）。

**C3（Important）`undo` 不校验目标行是否存在。** 目标行已被删除时 `UPDATE` 影响 0 行**无异常**，
然后照样 `undone = 1`——审计日志声称「已撤销」而数据什么都没动。处置：检查
`jdbc.update(...)` 的返回值，0 时抛 `IllegalStateException`，且这条检查放在
`UPDATE change_log SET undone = 1` **之前**，靠 `@Transactional` 保证前面的写入被回滚。

**C4（Important）外键后果的报错读不懂。** 三种后果实测：`food` 被 `meal_item.food_id`
引用（NO ACTION）→ `SQLITE_CONSTRAINT_FOREIGNKEY` 天书；`undo` 一条 `exercise` 的 insert
（CASCADE）→ 成功但 `exercise_muscle` 行被级联删掉；`undo` 一条 `photo` 的 insert
（SET NULL）→ `meal.photo_id` 被静默清空。处置：把外键违例翻译成
「回退失败：目标行被其他记录引用（表.id = N）。请先撤销引用它的记录。原始错误: …」。
> ⚠️ **实测纠正**：任务书写的是「`JdbcTemplate` 会把 SQLite 的外键违例包成
> `DataIntegrityViolationException`」——**实测不是**。Spring 的 SQLite 错误码映射表里
> **没有** 19 = `SQLITE_CONSTRAINT`，所以它落到了兜底的 `UncategorizedSQLException`，
> 而后者**不是** `DataIntegrityViolationException` 的子类（两者是兄弟，`instanceof` 判不出来）。
> 第一版按任务书写的就是 `catch (DataIntegrityViolationException e)`，实测报
> `Expecting actual throwable to be an instance of java.lang.IllegalStateException but was:
> org.springframework.jdbc.UncategorizedSQLException … [SQLITE_CONSTRAINT_FOREIGNKEY]`。
> 因此改成抓共同父类 **`DataAccessException`**，并把这段实测证据写进了代码注释。

**C5（Important）部分快照的契约没定义。** 实测：`before = {kcal_per100: 100}`（只一列）
回退后 `(kcal=100, protein=77, fat=88)`——未列出的列保留现值。数据没被写坏
（这是「只回退你改过的那几列」的合理语义），但调用方无从知道该传全量还是增量。
处置：**定义它，不禁止它**——写进 `ChangeLogService` 的类 javadoc「## 回退的语义（契约）」，
并顺手把 C1 / C4 的后果与 **concern 6** 一起写进同一节契约。

**C6（Minor）`:142` 的 `hasMessageContaining("before")` 实际无判别力。** 审查实测指出
**真正的理由**与我原先写的不同：判别力是被前一行 `isInstanceOf(IllegalArgumentException.class)`
挡住的（DB 兜底抛 `UncategorizedSQLException`，类型断言先失败，消息断言根本执行不到），
而不是「DB 原文含 `before_json` 稀释了判别力」。处置：注释理由改准（6.4 已同步），
`hasMessageContaining("快照")` 保留——将来类型断言一旦放松，它是消息层面的真防线。

**concern 6（审查指出，本轮实测确认并升级为交接项）** 见 6.5 的更正与本轮新增的两条契约测试：
`undo_of_a_cascaded_parent_row_silently_drops_the_join_rows` 实测
`exercise_muscle` 行数 before undo = 1 / after undo = 0；`undo_of_a_set_null_parent_row_silently_clears_the_referencing_column`
实测 `meal.photo_id` 被清空。**这个缺口是本任务自己引入的**：A1 把 `exercise_muscle`
移出白名单时写的「它的增删应通过所属的 exercise 记录来审计」，在当前实现下**不成立**——
`undo` 只回放父行、不重建 join 行。已作为**前置条件**交给「动作编辑工具」任务（见 6.8）。

**白名单与越权回退**：本轮**没有**动 `ALLOWED_TABLES` 的内容（10 张表已核对正确），
也没有动迁移文件、`application.yml`、`pom.xml`、`spike/`、`calc/`。

#### 6.8 安全审查轮的原始输出

**验收 1：全量套件（删 `target` 后 `cd backend && mvn -B test`）**
```text
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.172 s -- in com.change.calc.NutritionCalculatorTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.033 s -- in com.change.calc.OneRepMaxTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.038 s -- in com.change.calc.PortionConverterTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.025 s -- in com.change.calc.VolumeCalculatorTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.647 s -- in com.change.ChangeApplicationTests
[INFO] Tests run:  3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.025 s -- in com.change.config.DataDirectoryInitializerTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.063 s -- in com.change.MigrationTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.220 s -- in com.change.repo.TrainingRepoTest
[INFO] Tests run:  9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.056 s -- in com.change.SchemaTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.122 s -- in com.change.service.ChangeLogServiceTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.528 s -- in com.change.service.ChangeLogServiceUndoTransactionTest
[INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  11.198 s
```
91 → **102**（`ChangeLogServiceTest` 12 → 18，新增 `ChangeLogServiceUndoTransactionTest` 5 条）。
`SchemaTest` 9 条不变——本轮**没有动 schema**。

**验收 2：Critical 关死（四个主键别名各跑一次真实 `undo`，在**没有测试事务**的上下文里）**
```text
PROBE PK victim id = 9
PROBE PK alias=id before_json={"id":7}
PROBE PK alias=id undo -> THREW IllegalArgumentException: 列名「id」不是 food 上可回退写入的列（必须真实存在且不是主键——SQLite 的 rowid/_rowid_/oid 是主键别名）
PROBE PK alias=id undone=0 victimStillAtaId=1 foodIds=[9]
PROBE PK alias=rowid before_json={"rowid":7}
PROBE PK alias=rowid undo -> THREW IllegalArgumentException: 列名「rowid」不是 food 上可回退写入的列（…）
PROBE PK alias=rowid undone=0 victimStillAtaId=1 foodIds=[9]
PROBE PK alias=_rowid_ before_json={"_rowid_":7}
PROBE PK alias=_rowid_ undo -> THREW IllegalArgumentException: 列名「_rowid_」不是 food 上可回退写入的列（…）
PROBE PK alias=_rowid_ undone=0 victimStillAtaId=1 foodIds=[9]
PROBE PK alias=oid before_json={"oid":7}
PROBE PK alias=oid undo -> THREW IllegalArgumentException: 列名「oid」不是 food 上可回退写入的列（…）
PROBE PK alias=oid undone=0 victimStillAtaId=1 foodIds=[9]
```
四次都抛异常、`undone` 四次都保持 0、目标行 id **始终是 9**（`foodIds=[9]`，没有被搬到 7）。
路径是真实的「先 `recordUpdate` 写日志 → 再 `undo`」，且跑在**非测试事务**里。

**验收 3：事务生效（第 3 步被触发器挡住 → 第 1 步的 DELETE 被回滚）**
```text
PROBE TX step3 failure = org.springframework.jdbc.UncategorizedSQLException: PreparedStatementCallback; uncategorized SQLException for SQL [UPDATE change_log SET undone = 1 WHERE id = ?]; SQL state [null]; error code [19]; [SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (测试触发器：禁止把 undone 置为 1)
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.852 s -- in com.change.service.ChangeLogServiceUndoTransactionTest
```
这条断言（`victimStillPresent == 1`）在没有 `@Transactional` 时是 0——MM3 实测，见下。

**验收 4：目标行不存在被拦（`ChangeLogServiceTest`，测试事务内）**
```text
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 -- in com.change.service.ChangeLogServiceTest
```
（`undo_fails_when_the_target_row_is_gone`：`IllegalStateException`「目标行已不存在…」+
`undone` 保持 0。）

**验收 5：外键报错可读（非测试事务上下文里的实际文案）**
```text
PROBE FK thrown = java.lang.IllegalStateException
PROBE FK message = 回退失败：目标行被其他记录引用（food.id = 10）。请先撤销引用它的记录。原始错误: [SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (FOREIGN KEY constraint failed)
```

**验收 6：变异复验（4 个新变异体，**全杀**；每个都先删 `target`）**

| # | 变异（对磁盘原文做一处逐字替换） | 结果 | 变红的测试 | `Tests run` |
| --- | --- | --- | --- | --- |
| MM1 | 删掉 schema 校验（退回只靠标识符正则） | **杀** | `undo_refuses_to_rewrite_the_primary_key`、`undo_refuses_a_column_that_does_not_exist`、`undo_rejects_a_non_identifier_column_name` | 21 / 3 failures |
| MM2 | 删掉 `affected == 0` 检查 | **杀** | `undo_fails_when_the_target_row_is_gone` | 21 / 1 failure |
| MM3 | 删掉 `undo` 上的 `@Transactional` | **杀** | `undo_writes_both_places_in_one_transaction`、`undo_is_annotated_transactional_with_default_propagation` | 21 / 2 failures |
| MM4 | 删掉外键翻译（改成永不触发的 catch，原样抛出） | **杀** | `undo_of_a_referenced_row_translates_the_foreign_key_error` | 21 / 1 failure |

每个变异体都断言过「替换点在原文里**唯一**」，跑完由脚本恢复原文并校验 sha256：
`RESTORED ok=True sha=de34a2206ca326bf`（4/4）。

> ℹ️ 表里的 `Tests run` 是那四次变异**实测**的值：当轮两个 ChangeLog 测试类共 **21** 条
> （`ChangeLogServiceTest` 16 条 + 事务类 5 条）。此后又补了两条契约测试
> （CASCADE / SET NULL 的后果，见 6.7 的 concern 6 一节）与 C6 的注释更正，
> 两个类现为 **23** 条（全量套件 102 条，见验收 1）。
> 「哪个变异被杀」的归属不受这两条影响（它们断言的是外键后果，不是这四处防线），
> 所以这里保留实测原值并加注，不把没有重跑过的数字改写成想要的样子。

**计划文档与磁盘的逐字一致（本轮改完后由脚本比对）**：见本节末尾的比对输出。

**验收 7：计划文档自查（脚本 `_review/verify_plan_t9.py` 的真实输出）**
```text
=== A. encoding ===
  plan                                     bytes= 384935 lines=6768 BOM=False U+FFFD=0 CRLF=0 CR=0 LF=6768 sha256=c903c8b8537c0f2a
  ChangeLogService.java                    bytes=  13773 lines= 254 BOM=False U+FFFD=0 CRLF=0 CR=0 LF=254 sha256=de34a2206ca326bf
  ChangeLogServiceTest.java                bytes=  18946 lines= 362 BOM=False U+FFFD=0 CRLF=0 CR=0 LF=362 sha256=eb7d0b19f54456de
  ChangeLogServiceUndoTransactionTest.java bytes=  14949 lines= 268 BOM=False U+FFFD=0 CRLF=0 CR=0 LF=268 sha256=98a26afa187f0ba7

=== B. Task 9 java fences vs disk (byte-exact) ===
  java fences in Task 9: 2
  ChangeLogServiceTest     fence_lines= 362 disk_lines= 362 fence_sha=2a8377e8af59dc2e disk_sha=2a8377e8af59dc2e IDENTICAL=True
  ChangeLogService         fence_lines= 254 disk_lines= 254 fence_sha=5606ab14fca2ebda disk_sha=5606ab14fca2ebda IDENTICAL=True
  ALL FENCES IDENTICAL = True

=== C. 契约 (contract) present on disk and inside the plan's service fence ===
  OK   ## 回退的语义（契约）
  OK   update 的回退是按列写回
  OK   列名必须真实存在于目标表且不是主键
  OK   撤销一个被外键 CASCADE 引用的父行会连带删掉子行
  OK   动作编辑功能落地时必须扩展快照（记下 muscle_map）或改为整组重建 join 行

=== D. checkboxes ===
  Task 9 section   : checked=6 unchecked=0
  Task 10+ (to EOF): checked=0 unchecked=22
    Task 8   checked=  9 unchecked=  0
    Task 9   checked=  6 unchecked=  0
    Task 10  checked=  0 unchecked=  7
    Task 11  checked=  0 unchecked=  9

=== E. concern 6 recorded ===
  OK   concern 6
  OK   after undo = 0
  OK   既不在日志里、也回不来
  OK   6.7 的 concern 6 与 6.8

=== F. fences balanced / markdown sanity ===
  ``` count in plan : 209 (must be even)
  ### task headings : 11
  NEW sections 6.7/6.8 present: True True
```
> 关于 F 的 209：这个计数把**行内**出现的 ``` 也算进去了（3 条是 `> ```java` 形式的
> blockquote 围栏、1 条是散文里提到 ```java fence 的那句话）。按「**行首**围栏」重算：
> 196 条，奇偶校验 **0**（闭合），**没有未闭合的围栏**（脚本 `_review/check_fences.py` 实测）。
> 这一项在本轮之前是 188（同样含行内），不是本轮引入的。
>
> ⚠️ 上面 A 段里 plan 自己的 `bytes` / `lines` / `sha256` 是**本节定稿前**那一次比对的值：
> 本节一写进去，plan 自身的哈希就变了（自指，无法收敛）。这一节的结论是
> **两个 java fence 的 IDENTICAL=True**、**两份源文件的 sha256**、BOM/U+FFFD/CRLF 三项全 0——
> 这些都不受自指影响（plan 那一行只是当时的事实快照）。要复现，跑
> `python _review/verify_plan_t9.py`。
---

## Task 10: HTTP 骨架与端口协商

外壳需要知道后台起在哪个端口。约定：**后台把端口以固定格式打到标准输出**，外壳读这一行。

**Files:**
- Create: `backend/src/main/java/com/change/config/PortReporter.java`
- Create: `backend/src/main/java/com/change/web/HealthController.java`
- Test: `backend/src/test/java/com/change/web/HealthControllerTest.java`

- [x] **Step 1: 写失败的测试**

```java
package com.change.web;

import com.change.config.PortReporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthControllerTest {

    @LocalServerPort int port;

    @Autowired TestRestTemplate http;

    @Autowired Environment env;

    @Test
    void random_port_was_assigned() {
        assertThat(port).isGreaterThan(0);
    }

    @Test
    void health_reports_ok_without_touching_redis() {
        // ⚠️ 这条测试的名字比断言强：它断言的是「健康端点在 Redis 不可达时仍能响应」。
        // 这本身就是隔离的证据——Spring 上下文能起来就说明缓存不是硬依赖
        // （CacheConfig 的条件装配 + NoOp 兜底）。它**不是**「不碰 Redis」的机制断言，
        // 没有拦在 Redis 客户端上的探针。
        ResponseEntity<String> r = http.getForEntity("/api/health", String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getBody()).contains("\"status\":\"up\"");
        // 查值，不是查键名：只查键名时 cache 的值改成 "" 或 "totally-bogus" 都能存活。
        // 质量审查轮实测过这两个变异体都能全绿——而 Task 11 的验收标准正是要求
        // 这个字段「如实报告」缓存状态，所以必须是值断言。
        assertThat(r.getBody()).contains("\"cache\":\"disabled(placeholder)\"");
    }

    @Test
    void port_reporter_prints_the_agreed_protocol_line() {
        // 这一行是外壳与后端的协议：外壳按 CHANGE_PORT= 前缀匹配 stdout。
        // 改动 PREFIX 或格式等于改协议，会让外壳找不到后端。
        assertThat(PortReporter.PREFIX).isEqualTo("CHANGE_PORT=");

        // 在真实上下文里跑一次 report()，捕获 stdout
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        try {
            System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            new PortReporter(env).report();
        } finally {
            System.setOut(original);
        }

        String line = captured.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
        assertThat(line).startsWith(PortReporter.PREFIX);
        // 这条是唯一能把「外壳拿到的是自己这个进程的端口」钉住的断言：
        // report() 读 local.server.port，必须与注入进来的 @LocalServerPort 逐字相等。
        // 只校验后缀是 \d+ 的话，打印出别的端口照样绿。
        assertThat(line).isEqualTo(PortReporter.PREFIX + port);
    }
}
```

- [x] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=HealthControllerTest`

Expected: FAIL —— 404，因为 `/api/health` 还不存在

- [x] **Step 3: 写实现**

```java
package com.change.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 把实际监听端口以固定格式打到标准输出，供 Tauri 外壳读取。
 *
 * 为什么要走标准输出而不是写文件：外壳是后台进程的父进程，
 * 直接读子进程的 stdout 最简单，也不需要约定文件路径与清理时机。
 *
 * 输出格式固定为一行，外壳按前缀匹配：
 *   CHANGE_PORT=12345
 */
@Component
public class PortReporter {

    private static final Logger log = LoggerFactory.getLogger(PortReporter.class);
    public static final String PREFIX = "CHANGE_PORT=";

    private final Environment env;

    public PortReporter(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        String port = env.getProperty("local.server.port");
        if (port == null) {
            log.warn("拿不到 local.server.port，外壳将无法定位后端");
            return;
        }
        // 这一行必须原样存在，外壳靠它定位；改动等于改协议
        System.out.println(PREFIX + port);
        System.out.flush();
    }
}
```

```java
package com.change.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final CacheStatus cacheStatus;

    public HealthController(CacheStatus cacheStatus) {
        this.cacheStatus = cacheStatus;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "up",
                "cache", cacheStatus.describe());
    }

    /** 由 Task 11 提供实现；此处先声明接口，让健康端点不必依赖 Redis。 */
    public interface CacheStatus {
        String describe();
    }
}
```

- [x] **Step 4: 临时提供一个 CacheStatus 实现**

Task 11 会替换成真正的实现。先放一个最小的，让本任务能独立跑绿。

```java
package com.change.config;

import com.change.web.HealthController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    /** 占位实现：Task 11 会换成 Redis / NoOp 二选一。 */
    @Bean
    public HealthController.CacheStatus cacheStatus() {
        return () -> "disabled(placeholder)";
    }
}
```

- [x] **Step 5: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=HealthControllerTest`

Expected: `Tests run: 3, Failures: 0, Errors: 0`

> 计划原文写的是 `Tests run: 2`，那是**加上下面 B 那条协议测试之前**的数字。
> `port_reporter_prints_the_agreed_protocol_line` 是在落地轮补上的（见 Step 6b 末），
> 所以这里的期望值是 3。实测见 Step 6b。

- [x] **Step 6: 手动验证端口上报格式**

Run:
```bash
cd backend && mvn -B -q -DskipTests package && java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

Expected: 启动日志中有一行形如 `CHANGE_PORT=54321`。按 `Ctrl+C` 停掉。

- [x] **Step 6b: 生产形态实测（落地轮新增）**

Step 6 在原计划里只说「Expected: 有一行」。这不够——外壳真的能不能用这行，要**实测**。
落地轮的实测（`_review/t10_probe.py`，从 `%TEMP%\change-t10-probe` 起子进程，模拟外壳）：

- `local.server.port` 在 `java -jar` 生产形态下**确实有值**，`ApplicationReadyEvent` 时机没问题：
  协议行在启动后约 **5.2 s** 出现在 stdout（比设计文档记的 3.8 s 慢，加载提示的时间要按 5 s 留）。
- 端口真的能访问：`GET http://127.0.0.1:<port>/api/health` → `200`，
  body = `{"cache":"disabled(placeholder)","status":"up"}`。
- 监听地址是 **`127.0.0.1`**，不是 `::`/`0.0.0.0`：
  `netstat -ano` 给出 `TCP 127.0.0.1:<port> 0.0.0.0:0 LISTENING <pid>`，
  且从本机非回环地址连它一律 `ConnectionRefused`。
- **stdout / stderr 归属**：Spring Boot 的日志（含 INFO）走 **stdout**；
  stderr 上只有 JVM 的 `WARNING: A restricted method...` 那几行。
  协议行在 stdout 上**恰好一行**、**没有任何日志前缀**，`CHANGE_PORT=` 只出现 1 次——
  外壳按前缀匹配是安全的。
- ⚠️ **给外壳实现的坑（必须按这个写解析）**：Windows 上 `System.out.println` 输出的是
  `\r\n`，所以那一行的**原始字节是 `b'CHANGE_PORT=56934\r'`**（18 字节，不是 17）。
  外壳必须**按行读取并 trim**（Rust 侧 `BufRead::lines()` 已经会去掉 `\r`），
  **不要**用 `split('\n')` 之类只切 `\n` 的写法，否则后缀变成 `"56934\r"`，
  `parse::<u16>()` 直接报错。Java 的 `Integer.parseInt` 恰好容忍尾部 `\r`，别被它骗过。

**外壳侧必须实现的两条**（否则后端没有输出协议行时，外壳会无限等待）：

1. **读取协议行必须有超时**（例如 30 秒——实测正常启动约 4–5 秒，30 秒足够宽松）。
2. **子进程退出而未产出协议行 = 启动失败**，不是「继续等」。
   实测：`--spring.main.web-application-type=none` 下后端 4.6 秒 exit 0 且**不打协议行**
   （只打一行不含前缀的 WARN），外壳若只匹配前缀就会永远等下去。

**协议行必须恰好一行**：外壳按前缀匹配并取第一行，出现第二行（例如将来把令牌
写成第二个监听方法）不会报错，只会让外壳沉默地选错。把「恰好一行」写成协议的一部分。

> 落地轮还补了一条**协议测试** `port_reporter_prints_the_agreed_protocol_line`（见 Step 1 的 fence）。
> 理由：`PREFIX` 是外壳与后端的协议，改一个字符外壳就找不到后端，而原来没有任何测试守着它。
> 实测有效：把 `PREFIX` 改成 `"CHANGE_PORTX="` → 该测试变红（失败在 `HealthControllerTest.java:40`）。

- [x] **Step 7: 提交**

```bash
git add backend/src/main/java/com/change/config/PortReporter.java \
        backend/src/main/java/com/change/config/CacheConfig.java \
        backend/src/main/java/com/change/web/HealthController.java \
        backend/src/test/java/com/change/web/HealthControllerTest.java
git commit -m "feat: HTTP 骨架 + 端口协商（stdout 上报 CHANGE_PORT=）"
```

### 给加写接口的任务的安全约定（Task 10 质量审查轮修正）

**适用范围**：任何会改数据的端点（`/api/chat` 及之后所有写接口）。
**必须在第一个写接口落地之前完成**，不是「以后再说」。

> 本节在 Task 10 质量审查轮**被整节重写**。原版把 **Origin 校验与自定义头令牌写成「二选一」的
> 等效方案**，审查实测证明这是错的（Origin 校验在 DNS rebinding 下会放行攻击请求），
> 而**唯一能挡住 rebinding 的 `Host` 校验在原版里根本没有提**。
> 同时要说明：原版里「**CORS 只挡读响应、不挡发请求**」那一句**本身是对的**，本版保留。

**一、必须做（两条都要，不是二选一）**

1. **校验 `Host` 头**：只接受 `127.0.0.1:<当前端口>` 与 `localhost:<当前端口>`，其余一律 403。
   这是**反 DNS rebinding** 的唯一最小措施，也是本节里最容易被漏掉的一条。
   实测背景：本后端接受任意 `Host`（`Host: evil.example.com` → 200）。
   没有它，rebinding 场景下攻击页面与目标**同源**，后面所有基于「跨站」的判断都会失效。

2. **强制要求一个自定义请求头**（例如 `X-Change-Client: <启动时生成的一次性令牌>`）。
   原理：自定义头会**强制触发 CORS 预检**，而服务端不返回 `Access-Control-Allow-*`，
   预检失败 → **浏览器根本不发真实请求**（预检失败挡的是请求本身，不只是响应）。
   实测背景：不配 CORS 时 `OPTIONS` 预检返回 403 且无任何 ACAO 头。
   **端点必须强制要求该头存在**——如果允许省略，`Content-Type: text/plain`
   这类「简单请求」不触发预检，请求会真的执行。
   令牌用 `SecureRandom` 生成，只经 stdout 交付给外壳（与端口同一信道），
   **不落盘、不进日志、不进前端 JS**——Vue 层一次 XSS 就等于令牌泄露。
   校验放在一个 `HandlerInterceptor`（或 Filter）里，对「非只读方法 + 写路径」生效。
   注意别把校验写成「头不存在就放行」——那等于没做。

**二、Origin 校验：降级为补充，且启用前必须先实测**

**不要把 Origin 校验当作上面两条的替代品**——它在 DNS rebinding 下会放行攻击请求
（攻击页面与目标同源，Origin 是合法的 http(s)，且不触发预检）。

如果仍要加它，注意一个**会锁死外壳自己**的坑：Tauri v2 在 Windows/WebView2 上的
自定义协议来源是 `http://tauri.localhost`，属于 http 来源；而 Tauri 对本地 HTTP 请求
是否携带 Origin 并无稳定承诺。所以**必须先实测外壳实际发送的 Origin 是什么**，
再决定允许名单，否则会把外壳自己的请求判成攻击者。

**三、别指望随机端口当安全措施**

端口随机只增加猜中的成本，不是防护（页面可以扫端口）。这一点原版写对了，措辞保留：

> **随机端口不能挡住跨站请求。CORS 只挡「读响应」，不挡「发请求」。**
> 用户浏览器里任何一个网页都能向 `http://127.0.0.1:<端口>/api/...` 发 POST——请求真的会被
> 后端执行，只是网页读不到响应而已。对「写数据」这种有副作用的操作，读不到响应**不等于**
> 没造成损害。

**四、边界（诚实写明挡不住什么）**

- **挡不住本机同用户的其它进程**：令牌经 stdout 交付，同用户进程理论上可读
  （Windows 需要 `OpenProcess` + 句柄复制权限，非默认但可得）。
  真正的风险不是信道，而是「外壳把 stdout 落盘」——所以那条「不落盘」的约束是硬约束。
- **更简单的等效替代**（按性价比）：
  ① `Host` 白名单（上面第 1 条，反 rebinding 的唯一最小措施）；
  ② 只要求「存在某个自定义头」（如 `X-Requested-With`）而不是随机令牌——
     目的只是强制预检，不需要生成/交付/保管秘密；
  ③ 写接口只接受 `application/json`（挡掉表单与纯文本这两个简单请求的 Content-Type）。
- **如果以后要真正隔离**：Unix domain socket / 命名管道能让浏览器根本够不着，
  安全上限最高，但 Windows 支持与 Tauri/Rust 侧工作量明显更大。
  本项目规模下不推荐，但值得作为备选记录。

**五、这条在增加第一个写接口之前必须落地。** 具体地说，Task 11 落地 `/api/chat` 时，
**要么**同一个提交里带上上面第一、二条，**要么**把 `/api/chat` 的写操作挡在未校验的来源之外
（例如先只上只读端点）。先上线一个能改数据的接口、再补校验，是不可接受的顺序。

---

## Task 11: Redis 缓存抽象与降级

**这是本计划最后一个任务，也是 Task 1 那个「Redis 连不上导致测试失败」的收尾。**

设计文档规定：**没有 Redis 时应用必须完全正常工作**，连接超时 300 ms、命令超时 500 ms，连续失败熔断。

**Files:**
- Create: `backend/src/main/java/com/change/cache/CacheService.java`
- Create: `backend/src/main/java/com/change/cache/NoOpCacheService.java`
- Create: `backend/src/main/java/com/change/cache/RedisCacheService.java`
- Modify: `backend/src/main/java/com/change/config/CacheConfig.java`
- Test: `backend/src/test/java/com/change/cache/CacheServiceTest.java`

> ⚠️ **Step 1 必须同时把 `HealthControllerTest` 的 `cache` 断言换成查新实现的值。**
> 质量审查轮已把那条断言从「只查键名」改成查值
> （`HealthControllerTest.java:33` 现在是
> `assertThat(r.getBody()).contains("\"cache\":\"disabled(placeholder)\"")`）。
> 但 `disabled(placeholder)` 是 Task 10 的**占位实现**的值；本任务 Step 5 把
> `CacheStatus` 换成 `cache::describe` 之后它就不再成立，必须同步换成新实现真正会返回的值
> （`change.redis.enabled=false` 下是 `缓存：未启用（配置为关闭）`）。
> **这一步不能省**：留着一个对占位值成立、对真实现不成立的断言，等于把这个已知漏洞
> 用「占位实现」的理由一路带过去——而本计划的完成标准里写明 `/api/health`
> **「如实报告缓存状态」**，查值的断言正是那句话的唯一守卫。

- [x] **Step 1: 写失败的测试**

```java
package com.change.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CacheServiceTest {

    @Test
    void noop_always_misses_and_never_throws() {
        CacheService c = new NoOpCacheService();

        assertThat(c.get("anything", String.class)).isEmpty();
        c.put("anything", "value", Duration.ofMinutes(5));   // 不应抛异常
        c.invalidatePrefix("change:");
        assertThat(c.isAvailable()).isFalse();
        assertThat(c.describe()).contains("未启用");
    }

    @Test
    void noop_is_the_guaranteed_safe_fallback() {
        // 这条测试的意义：无论 Redis 处于什么状态，注入 NoOp 时业务代码都不会崩。
        CacheService c = new NoOpCacheService();
        for (int i = 0; i < 100; i++) {
            assertThat(c.get("k" + i, Integer.class)).isEqualTo(Optional.empty());
        }
    }
}
```

- [x] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=CacheServiceTest`

Expected: 编译失败，`cannot find symbol: class CacheService`

- [x] **Step 3: 写接口与降级实现**

```java
package com.change.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存抽象。
 *
 * 关键约定：任何方法都不得向上抛异常。Redis 不可用是常态
 * （它跑在一台经常关机的虚拟机上），业务代码不该为此写 try/catch。
 */
public interface CacheService {

    <T> Optional<T> get(String key, Class<T> type);

    void put(String key, Object value, Duration ttl);

    /** 按前缀批量失效。写操作后调用，对应设计文档「写入即失效」。 */
    void invalidatePrefix(String prefix);

    boolean isAvailable();

    /** 给设置页显示的人话描述，例如「已连接 192.168.100.128:6379」。 */
    String describe();
}
```

```java
package com.change.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 降级实现：永远未命中，永远不抛异常。
 *
 * 什么时候用它：
 *   - Redis 配置为关闭
 *   - 启动时探测不到 Redis
 *   - 连续失败触发了熔断
 */
public class NoOpCacheService implements CacheService {

    private final String reason;

    public NoOpCacheService() {
        this("未启用");
    }

    public NoOpCacheService(String reason) {
        this.reason = reason;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return Optional.empty();
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        // 故意什么都不做
    }

    @Override
    public void invalidatePrefix(String prefix) {
        // 故意什么都不做
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String describe() {
        return "缓存：未启用（" + reason + "）";
    }
}
```

- [x] **Step 4: 写 Redis 实现（含熔断）**

```java
package com.change.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 实现。
 *
 * 两条自我保护：
 *   1. 所有调用都捕获异常并降级为「未命中」——Redis 挂了不该让接口 500
 *   2. 连续失败达阈值后熔断一段时间，避免每次请求都白等 300 ms 连接超时
 */
public class RedisCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /** 连续失败多少次后熔断。 */
    static final int FAILURE_THRESHOLD = 3;
    /** 熔断持续多久。 */
    static final Duration BREAK_DURATION = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final String endpoint;

    private int consecutiveFailures = 0;
    private Instant brokenUntil = Instant.EPOCH;

    public RedisCacheService(StringRedisTemplate redis, String host, int port) {
        this.redis = redis;
        this.endpoint = host + ":" + port;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        if (isBroken()) return Optional.empty();
        try {
            String raw = redis.opsForValue().get(key);
            onSuccess();
            if (raw == null) return Optional.empty();
            return Optional.of(json.readValue(raw, type));
        } catch (Exception e) {
            onFailure("get", e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        if (isBroken()) return;
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
            onSuccess();
        } catch (Exception e) {
            onFailure("put", e);
        }
    }

    @Override
    public void invalidatePrefix(String prefix) {
        if (isBroken()) return;
        try {
            Set<String> keys = redis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            onSuccess();
        } catch (Exception e) {
            onFailure("invalidatePrefix", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return !isBroken();
    }

    @Override
    public String describe() {
        if (isBroken()) {
            return "缓存：已熔断（" + endpoint + " 连续失败 " + consecutiveFailures + " 次）";
        }
        return "缓存：已连接 " + endpoint;
    }

    private boolean isBroken() {
        if (Instant.now().isBefore(brokenUntil)) {
            return true;
        }
        if (consecutiveFailures > 0) {
            consecutiveFailures = 0;   // 熔断期结束，给一次机会
        }
        return false;
    }

    private void onSuccess() {
        consecutiveFailures = 0;
    }

    private void onFailure(String op, Exception e) {
        consecutiveFailures++;
        if (consecutiveFailures == FAILURE_THRESHOLD) {
            brokenUntil = Instant.now().plus(BREAK_DURATION);
            log.warn("Redis {} 连续失败 {} 次，熔断 {} 秒。原因: {}",
                    op, FAILURE_THRESHOLD, BREAK_DURATION.toSeconds(), e.toString());
        }
    }
}
```

- [x] **Step 5: 改 CacheConfig 做启动探测与条件装配**

```java
package com.change.config;

import com.change.cache.CacheService;
import com.change.cache.NoOpCacheService;
import com.change.cache.RedisCacheService;
import com.change.web.HealthController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存装配。
 *
 * 设计原则：**没有 Redis 时应用必须完全正常工作**。
 * 所以这里不是「连不上就启动失败」，而是「连不上就换成 NoOp，并在设置页说清楚」。
 */
@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public CacheService cacheService(
            @Value("${change.redis.enabled:true}") boolean enabled,
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            ObjectProvider<StringRedisTemplate> templateProvider) {

        if (!enabled) {
            log.info("缓存：配置为关闭，使用 NoOp");
            return new NoOpCacheService("配置为关闭");
        }

        StringRedisTemplate t = templateProvider.getIfAvailable();
        if (t == null) {
            return new NoOpCacheService("未装配 Redis 客户端");
        }

        try {
            // 探测一次：连不上就立刻降级，不要让第一个请求去承担 300ms 超时
            t.execute((RedisCallback<String>) conn -> {
                conn.ping();
                return "PONG";
            });
            log.info("缓存：已连接 {}:{}", host, port);
            return new RedisCacheService(t, host, port);
        } catch (Exception e) {
            log.info("缓存：连不上 {}:{}，本次运行使用 NoOp（原因: {}）", host, port, e.toString());
            return new NoOpCacheService("连不上 " + host + ":" + port);
        }
    }

    @Bean
    public HealthController.CacheStatus cacheStatus(CacheService cache) {
        return cache::describe;
    }
}
```

- [x] **Step 6: 跑缓存测试**

Run: `cd backend && mvn -B test -Dtest=CacheServiceTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [x] **Step 7: 跑全量测试，确认 Task 1 的失败已消失**

Run: `cd backend && mvn -B test`

Expected: 全部通过。**特别确认 `ChangeApplicationTests.contextLoads` 通过**——这条在 Task 1 时可能因 Redis 不可达而失败，现在应该绿。如果它仍然失败，说明降级没生效，回到 Step 5 检查。

- [x] **Step 8: 验证两个场景（不需要开虚拟机）**

场景一，Redis 关闭时：
```bash
cd backend && mvn -B -q -DskipTests package
java -jar target/change-backend-0.1.0-SNAPSHOT.jar --change.redis.enabled=false
```
Expected: 启动成功；`curl http://localhost:<CHANGE_PORT>/api/health` 返回 `"cache":"缓存：未启用（配置为关闭）"`

场景二，Redis 指向不可达地址时（默认配置就是这台关着的虚拟机）：
```bash
java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```
Expected: **启动不失败**；健康端点返回 `"cache":"缓存：未启用（连不上 192.168.100.128:6379）"`

> 这一步需要 Redis 真在线时才能验「已连接」。**到时候跟我说一声，我把虚拟机开起来**，再跑一次确认 `"cache":"已连接 192.168.100.128:6379"`。

- [x] **Step 9: 提交**

```bash
git add backend/src/main/java/com/change/cache backend/src/main/java/com/change/config/CacheConfig.java \
        backend/src/test/java/com/change/cache
git commit -m "feat: Redis 缓存抽象与降级（NoOp 兜底 + 启动探测 + 连续失败熔断）"
```

---

## 完成标准

本计划结束时，以下命令必须全绿：

```bash
cd backend && mvn -B test
```

且满足：

- [ ] 十五张表全部由 Flyway 建出，`SchemaTest` 通过
- [ ] 三个核心计算各有独立单元测试，且**测试里包含了设计文档点名的边界**（改份量重算、三种录入路径一致、次要肌群算半分）
- [ ] `ChangeLogService` 能回退 insert 与 update，且拒绝二次撤销与非白名单表
- [ ] 后端启动后打印 `CHANGE_PORT=<端口>`
- [ ] **`change.redis.enabled=false` 与 Redis 不可达两种情况下，应用都能正常启动**，且 `/api/health` 如实报告缓存状态
- [ ] 全量 `mvn test` 通过，不依赖任何外部服务

## 下一步

计划 1 完成后进入 **计划 2：动作库导入与肌群映射**。它需要本计划产出的 `exercise` / `muscle_group` / `exercise_muscle` 三张表与 `ExerciseRepo`。

---

# Task 7 质量审查轮记录（2026-10-15）

> 本节记 **Task 7 的代码质量审查修复**。审查结论：0 Critical / 2 Important / 9 Minor，
> 批准。改动全部落在 `calc/` 包与 `SchemaTest`，**不动迁移文件、`application.yml`、`pom.xml`、`spike/`**。
> 测试从 61 → **66**（新增测试方法 5 个、拆出 4 个，见 Step 4b 的账）。

## Important-1：`classify` 补 `sets` 守卫（已改代码）

审查实测：

| 调用 | 改前 | 改后 |
|---|---|---|
| `classify(NaN, 10, 20)` | **`IN_RANGE`** | 抛 `IllegalArgumentException: sets 必须是有限且非负的组数，实际为 NaN` |
| `classify(-5, 10, 20)` | `UNTRAINED`（负组数静默变成「没练」） | 抛 `IllegalArgumentException: … 实际为 -5.0` |
| `classify(NaN, null, null)` | `NOT_CONFIGURED` | `NOT_CONFIGURED`（**不变**——短路排在守卫之前） |

`NaN → IN_RANGE` 是最危险的一态（「达标」绿）。包内另外三个数值入口都拒 NaN
（`NutritionCalculator.forGrams`、`Nutrition` 紧凑构造器、`PortionConverter.toGrams`，
后者还专门用 `!(grams <= MAX_GRAMS)` 的写法吃 NaN）——**只有 `classify` 破了这个口径**。

改法：在 `min == null || max == null` 短路**之后**、区间校验**之前**加一行守卫，
并把这个**相对顺序**用 `rejects_non_finite_or_negative_sets` 的最后一条断言钉住
（`classify(Double.NaN, null, null) == NOT_CONFIGURED`：没配目标的肌群不该因为实际值坏了而改抛异常）。

## Important-2：「半个区间」的口径（只写进计划，见 Task 9 约定第 5/6 条）

`classify(25, 10, null)` 与 `classify(25, null, 20)` 都返回 `NOT_CONFIGURED`，
而 schema 允许「只配下限」「只配上限」两种**真实**状态。详见上面新增的 Task 9 约定
第 5 条（必须成对保存）与第 6 条（`TargetRange` 备选）。

## Minor 逐条

| # | 位置 | 改法 | 依据（审查实测） |
|---|---|---|---|
| M1 | `accumulate` 的 Javadoc + 返回类型实现 | `HashMap` → `LinkedHashMap`；`@return` 写清三条契约（①键序为首次出现序 ②值恒为 0.5 的整数倍 ③缺键 = 0 组）。**返回类型不动** | 审查实测：`HashMap` 是哈希序（既非插入序也非 id 序）；换 `Map.copyOf` 则**连跑 4 次 JVM 得到 4 个不同顺序**，而下游按 `entrySet()` 渲染热力图，**顺序用户可见**。`LinkedHashMap` 与 `PortionConverter.index` 同一口径 |
| M2 | `Status` | 纯注释：加枚举级 Javadoc（**混了两个维度、禁止依赖 `ordinal()`**）+ 四态各一行说明（尤其 `sets == 0` 是 `UNTRAINED` 而非 `BELOW`）。零代码改动 | `values()` 实测是 `NOT_CONFIGURED(0), UNTRAINED(1), BELOW(2), IN_RANGE(3), ABOVE(4)`——不同维度的态插在「训练量从少到多」的四态前面，写「至少 BELOW」会把「未设目标」算得比「没练」还轻 |
| M3 | `VolumeCalculatorTest.unknown_role_is_rejected` | **保留 + 加注释说明它实际测什么**（不改名，但注释点明它不守 `switch`）。**理由见下方专节** | 审查实测：只删 `Entry` 的白名单（让非法状态可表示），13 条里**只有** `invalid_role_cannot_even_be_constructed` 变红 |
| M4 | 两条 null 区间测试 | 合并成 `missing_target_range_is_its_own_state_and_short_circuits`，注释里点明它同时钉两件事 | 审查实测：把 null 短路挪到区间校验之后，**两条同时变红**——短路顺序已被前者覆盖，后者边际内容只剩「`(null, 0)` 不炸」 |
| M5 | `NutritionCalculatorTest:133` | `hasMessageContaining("null")` → `hasMessageContaining("不能")` + 注释 | 删掉 `sum` 里那行 `Objects.requireNonNull(n, ...)` 后，该文件 **13/13 仍全绿**——JDK 的 NPE 消息（`Cannot invoke "…Nutrition.kcal()" because "n" is null`）本身就含 `null` 字样 |
| M6 | `Round.java` 类注释 + 文件树 + Step 3b 说明 | 「饮食、训练容量、1RM 三处」→「饮食与 1RM 两处」+ 一句「训练容量不需要：值恒为 0.5 的整数倍」 | 全库 grep：`VolumeCalculator` **一次都没调用** `Round`；`PortionConverter:49-51` 还写了「定标属于显示层，不在这里做」 |
| M7 | `package-info.java` | 「后续的 `VolumeCalculator.Entry`」→ `{@link PortionConverter.Unit}` / `{@link VolumeCalculator.Entry}` / 后续的 `OneRepMax.Set` | `Entry` 已落地 |
| M8 | `accumulate` 的 skip-zero 理由 + 对应测试注释 | 换成「对下游只有两种可用状态：有量 / 无量；多出来的 0 值键只会多画一个不该画的热区」 | 「在 map 里且为 0」与「不在 map 里」在 **Map 层面** `containsKey` 分得清清楚楚；分不清的是**按 `entrySet()` 渲染的下游** |
| M9 | `invalid_role_cannot_even_be_constructed`（4 个不变量）、`rejects_inverted_or_negative_target_range`（2 个） | 按不变量名拆成 4 条 + 2 条（纯加法，**不碰任何断言的期望值**） | 一个方法塞多个不变量，失败时只能靠行号分辨 |

### M3 的判定：为什么保留 `unknown_role_is_rejected` 而不是删掉或改名

审查给了两条路（删掉、或改名 + 注释写明它不守 `switch`），并说「更好的做法是删掉」。
本轮**判定为保留 + 加注释、不改名**，依据是审查自己那张表里的一行：

| 事实 | 实测 |
|---|---|
| 只删 `Entry` 白名单 | 只有 `invalid_role_cannot_even_be_constructed` 变红 |
| **组合**变异体（放开白名单 + `default` 改成 `0.0`） | `unknown_role_is_rejected` 与 `invalid_role_cannot_even_be_constructed` **双双变红** |

既然两条在组合变异体下检测力相同、又各自绑定一个 `Entry` 不变量，删掉一条只会让
「非法角色不可表示」少一个入口断言，覆盖面**一点不减**。真正有害的是那段误导性注释。
另外，审查另一处建议的替代写法 `accumulate_defends_in_depth_with_its_own_message`
构造的是 `new Entry(1, 3, "tertiary")` 并断言消息含 `未知的肌群角色` 与 `tertiary`，
与 `invalid_role_is_rejected` **逐字等价**——那是换名字的重复测试，比现状更差。

## 防漂移测试：Java 白名单 ↔ DDL 白名单（已改代码）

`SchemaTest:289-291` 已经逐字断言了 `sqlite_master.sql` 里的
`CHECK (role IN ('primary', 'secondary'))`，**DB 侧钉死了**；缺的是「Java 侧那份手写拷贝
== DDL 里那一串」。改法：把 `VolumeCalculator.Entry` 的合法角色集暴露成常量
（**不改枚举、不动任何签名**）：

```java
    /** 合法的肌群角色。必须与 V2__training.sql 里 exercise_muscle.role 的 CHECK 一致。 */
    public static final List<String> VALID_ROLES = List.of("primary", "secondary");
```

构造器与 `accumulate` 的 `switch` 都引用它；`SchemaTest.role_whitelist_matches_the_ddl`
从 `ddlOf("exercise_muscle")` 抽出 `role IN (...)` 的值集，与 `VALID_ROLES` **逐项 + 按序**比对。
两侧双向收口：任何一侧改了、或顺序换了，另一侧就红。

## `package-info.java`：补两条约定 + 修一处措辞

见上面 F 节的第 3/4 条与那条措辞更正：约定三（公开数值入口必须显式拒绝 NaN / ±∞，
给出 `!Double.isFinite(v)` 与 `!(x <= MAX)` 两种惯用法，理由是已因 NaN 栽过两次）、
约定四（可空的领域配置不得兜底，必须进入类型或状态）、
以及约定一的判据从「输入或输出的聚合值」改成「**跨操作复用** vs 只属于单个操作」
（原措辞与 `Entry` 这个**输入**条目嵌在操作类里的例子打着）。

---

# Task 10 质量审查轮记录（2026-10-18）

> 本节记 **Task 10 的质量审查收口**。审查结论：**2 Critical / 2 Important / 4 Minor**。
> 改动落在**计划文本 + 1 个测试断言**，**没有动任何生产代码**
> （`PortReporter` / `HealthController` / `CacheConfig` 一行未动），
> 也没有动 `application.yml`、`pom.xml`、迁移文件、`calc/`、`service/`。
> 测试从 105 → **105**（断言换值、加对照，条数不变）。

## C1（Critical）：安全约定把 Origin 校验写成了与令牌二选一的等效方案

审查实测推翻了原版 Task 10 末尾那一节的第一个「最小可行做法（二选一）」：

| 审查实测 | 结论 |
|---|---|
| `evil.example.com` 解析到 `127.0.0.1` 后，攻击页面与请求 URL **同源**、**不触发预检**，`Origin: http://evil.example.com` 是合法 http(s) | 按原版「非 http(s) 才放行」的规则，rebinding 攻击请求会被**放行** |
| `Host: evil.example.com` → HTTP **200** | 后端接受任意 `Host`，这是 rebinding 的**前置条件**，而原版**完全没提 Host 校验** |
| 不配 CORS 时 `OPTIONS` 预检返回 **403** 且**无任何 `Access-Control-Allow-*`** | 原版对的那一半：「自定义头确实能挡住浏览器发出的跨站写请求」成立——**前提是端点强制要求该头** |
| — | 原版「CORS 只挡读响应、不挡发请求」那句**本身是对的**，重写时保留 |

处置：**整节重写**（见 Task 10 末尾那一节），结构改为
「一、必须做（Host 白名单 + 强制自定义头，两条都要）→ 二、Origin 校验降级为补充且启用前先实测
→ 三、别指望随机端口 → 四、边界 → 五、落地时机」。

## C2（Critical）：协议行的缺失是静默的

审查实测：`--spring.main.web-application-type=none` 下 JPA / Flyway / Hikari 全部正常初始化、
`Started ChangeApplication in 4.211 seconds`，然后 `PortReporter` 走 `log.warn`
（**不含 `CHANGE_PORT`**），进程 **4.6 秒后 exit code 0**。

外壳如果只做「等协议前缀」，会**无限等待**——用户看到永远不出现的窗口，且没有超时提示。

处置：在 Task 10 的协议说明处（`CHANGE_PORT=` 那一段）补两条**外壳侧**硬约束
（读取协议行必须有超时、子进程退出而未产出协议行 = 启动失败），
并把「**协议行必须恰好一行**」写成协议的一部分。

## I1（Important）：`cache` 断言只查键名不查值

`HealthControllerTest.java:33` 原来是 `contains("\"cache\"")`。审查实测：把 `cache` 的值改成
`""` 或 `"totally-bogus"` 都**存活**（Task 10 提交信息 D 节自己也记了这个结论）。
而本计划的完成标准里写明 `/api/health` 必须**「如实报告缓存状态」**。

处置：改成查值 `assertThat(r.getBody()).contains("\"cache\":\"disabled(placeholder)\"")`；
并把「Task 11 Step 1 必须把这条断言换成查新实现的值」写进 Task 11 的步骤。

## I2（Important）：`port_reporter` 测试的覆盖缺口

`port_reporter_prints_the_agreed_protocol_line` 手动 `new PortReporter(env).report()`，所以：

| 缺口 | 后果 |
|---|---|
| 不守 `@EventListener` 事件接线本身 | 删掉 `@EventListener` 它照样绿 |
| 没有断言「恰好一行」 | 多打一行不会红 |
| 只校验后缀 `\d+` | **打印出别的端口它照样绿** |

处置：加一条把注入的 `@LocalServerPort` 作为对照的断言
`assertThat(line).isEqualTo(PortReporter.PREFIX + port)`——
这是唯一能把「外壳拿到的是**自己**的端口」钉住的断言。
（`report()` 读 `local.server.port`，与注入的 `port` 实测一致，见验收 4。）

## Minor 逐条

| # | 位置 | 改法 |
|---|---|---|
| M1 | Task 10 提交信息里的测试数字 | **不改历史提交**（`amend` 会破坏「一次任务一次提交」的可追溯性，分支虽未推送也不用它）。改为在这里记档：`%B` 里的 **`Tests run: 104`** 是**补协议测试之前的中间态**，终值是 **105**——`104` 出现在「TDD 三步」的 GREEN 那一步（那时只加了 2 条测试），`105` 出现在后面「测试与验证」的全量一轮（补上协议测试后是 3 条） |
| M2 | Task 10 提交信息里的变异计数口径 | 原写「**11 个变异体实测：8 杀、3 存活**」，把 **2 个对照组**（注释里加一个空格、删 `cacheStatus()` bean）混进了分母。**正确口径是「7 杀 / 2 存活 / 2 个对照」**（有效变异体 9 个：`PREFIX`、`status` 键、`"up"`→`"down"`、`cache` 键、`port > 65535`、去 `RANDOM_PORT` 单类、去 `RANDOM_PORT` 全量、`cache` 值→`""`、`cache` 值→假值） |
| M3 | `health_reports_ok_without_touching_redis` 的名字 | 它只断言返回 200 且 JSON 里有 `cache` 键，**没有任何「不碰 Redis」的机制断言**，名字承诺得比断言强。处置：**加注释说明它实际验证的是什么**（健康端点**在 Redis 不可达时仍能响应**——这本身就是隔离的证据，因为 Spring 上下文起得来就说明没有强依赖），不改名 |
| M4 | `_review/` 目录 | 见下 |

## M4 的处置：`_review/` 清理

- **删除 `_review/commit-msg-task10.txt`**：与 `git log -1 --format=%B` 逐字重复
  （仅差一个结尾空行，实测 9262 B vs 9261 B，其余逐字节相同），是**第二份可能不一致的真相**。
- **把 `_review/` 整体移出版本库**：`git rm -r --cached _review` + `.gitignore` 加 `_review/`。
  理由有两条，都是审查实测的：① `_review/*.py` **硬编码了 `E:\maven\...` 与
  `C:\Users\alpaca\.jdks\...`**，换机器就废，是单机调试产物而**不是可复跑验证**；
  ② `t10_probe.py` 自身的期望值算错（打印「应为 17」而实测 18——`\r` 那一个字节）。
- ⚠️ `git rm --cached` **只取消跟踪、不删磁盘文件**。本轮**没有**用 `rm`，
  也没有删除任何脚本或日志文件，全部仍留在磁盘上。
- **计划历史快照 `_review/plan-before-security-round.md`（355 KB）必须保留**：
  它是那段时间计划演进的历史快照，删掉就不可追溯了。
  本轮把它**留在磁盘上**（同时也不再被跟踪，与整个 `_review/` 一致）。

## 本轮验收的原始输出

见提交信息里的「验收」一节（7 条各自贴了原始输出）。关键两条：

- **§3 的改法有效**：把 `CacheConfig` 的返回值临时改成 `""`，新值断言**变红**
  （改前是绿的——这正是审查实测的漏洞）。
- **§4 的改法有效**：把 `PortReporter.PREFIX` 改成 `PREFIX + "0" + port` 的输出逻辑后，
  新的 `isEqualTo(PREFIX + port)` 断言**变红**。


