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

Expected: `Tests run: 8, Failures: 0, Errors: 0`（本轮修复后 SchemaTest 从 4 条变成 8 条）

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
- Test: `backend/src/test/java/com/change/calc/NutritionCalculatorTest.java`

- [x] **Step 1: 写失败的测试**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

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
        Nutrition meal = NutritionCalculator.sum(java.util.List.of(
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
        // 这条锁住 round2 的精度本身，而不只是「不粗于两位」。
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

/** 一份营养值。热量与三大营养素、纤维单位为克（kcal 除外）；钠为毫克。 */
public record Nutrition(double kcal, double protein, double carb,
                        double fat, double fiber, double sodium) {
}
```

```java
package com.change.calc;

import java.util.List;

/**
 * 热量与宏量营养素计算。
 *
 * 铁律：所有营养值都按「每 100 克」保存，份量单独保存。
 * 本类只做一件事：把每 100 克的值按实际克数换算出来。
 * 结果永远实时计算，绝不回存——这样改份量、换食物、换单位都不会算错。
 */
public final class NutritionCalculator {

    /** 单次录入的份量上限（克）。超过这个数几乎一定是录错了。 */
    public static final double MAX_GRAMS = 5_000;

    private NutritionCalculator() {
    }

    public static Nutrition forGrams(Nutrition per100g, double grams) {
        if (grams < 0) {
            throw new IllegalArgumentException("grams 不能为负: " + grams);
        }
        if (grams > MAX_GRAMS) {
            throw new IllegalArgumentException(
                    "grams 超出上限 " + MAX_GRAMS + ": " + grams);
        }
        double k = grams / 100.0;
        return new Nutrition(
                round2(per100g.kcal() * k),
                round2(per100g.protein() * k),
                round2(per100g.carb() * k),
                round2(per100g.fat() * k),
                round2(per100g.fiber() * k),
                round2(per100g.sodium() * k));
    }

    public static Nutrition sum(List<Nutrition> items) {
        double kcal = 0, p = 0, c = 0, f = 0, fi = 0, na = 0;
        for (Nutrition n : items) {
            kcal += n.kcal(); p += n.protein(); c += n.carb();
            f += n.fat();     fi += n.fiber();   na += n.sodium();
        }
        return new Nutrition(round2(kcal), round2(p), round2(c),
                             round2(f), round2(fi), round2(na));
    }

    /** 保留两位小数：避免 0.1+0.2 那类浮点噪声显示成 0.30000000000000004。 */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
```

- [x] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=NutritionCalculatorTest`

Expected: `Tests run: 8, Failures: 0, Errors: 0`

**实测（2026-10-15）**：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，
耗时 **0.115 s**——纯类、无 Spring 上下文，符合「`calc/` 不依赖框架」的设计意图
（对比：同一次全量里 `@SpringBootTest` 要 5.7 s）。

> **补记（2026-10-15，规格评审轮）**：上面这条 `Tests run: 6` 是补完 3 个覆盖缺口
> **之前**的实测记录。补完两个测试方法后复测为
> `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`。

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

---

## Task 6: 份量单位换算（核心）

**Files:**
- Create: `backend/src/main/java/com/change/calc/PortionConverter.java`
- Test: `backend/src/test/java/com/change/calc/PortionConverterTest.java`

- [ ] **Step 1: 写失败的测试**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .hasMessageContaining("碗");   // 提示里要带上可用单位，方便前端显示
    }

    @Test
    void three_conversion_paths_agree() {
        // 这是设计文档里点名的必测边界：三种录入路径结果必须一致
        double byBowl   = PortionConverter.toGrams(1, "碗", RICE_UNITS);       // 1 碗
        double bySpoon  = PortionConverter.toGrams(10, "勺", RICE_UNITS);      // 10 勺
        double byGram   = PortionConverter.toGrams(150, null, RICE_UNITS);     // 直接填克数

        assertThat(byBowl).isEqualTo(byGram);
        assertThat(bySpoon).isEqualTo(byGram);
    }

    @Test
    void builds_unit_index_from_rows() {
        Map<String, Double> idx = PortionConverter.index(RICE_UNITS);
        assertThat(idx).containsEntry("碗", 150.0).containsEntry("勺", 15.0);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=PortionConverterTest`

Expected: 编译失败，`cannot find symbol: class PortionConverter`

- [ ] **Step 3: 写实现**

```java
package com.change.calc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 份量单位换算：「1 碗」折成多少克。
 *
 * 换算表存在 food_portion 表里（每食物一套单位），本类只负责查表与计算。
 */
public final class PortionConverter {

    /** 某食物上的一个份量单位。 */
    public record Unit(String name, double grams) {
    }

    private PortionConverter() {
    }

    /**
     * @param amount 份量数值
     * @param unit   单位名；传 null 表示 amount 本身就是克数
     */
    public static double toGrams(double amount, String unit, List<Unit> units) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount 不能为负: " + amount);
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
        return amount * gramsPerUnit;
    }

    public static Map<String, Double> index(List<Unit> units) {
        return units.stream().collect(Collectors.toMap(
                Unit::name, Unit::grams, (a, b) -> a, LinkedHashMap::new));
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=PortionConverterTest`

Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/calc/PortionConverter.java \
        backend/src/test/java/com/change/calc/PortionConverterTest.java
git commit -m "feat: 份量单位换算，含「三种录入路径结果一致」的边界测试"
```

---

## Task 7: 训练容量计算（核心）

**Files:**
- Create: `backend/src/main/java/com/change/calc/VolumeCalculator.java`
- Test: `backend/src/test/java/com/change/calc/VolumeCalculatorTest.java`

- [ ] **Step 1: 写失败的测试**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> VolumeCalculator.accumulate(List.of(entry(1, 3, "tertiary"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tertiary");
    }

    @Test
    void classify_against_target_range() {
        assertThat(VolumeCalculator.classify(0, 10, 20)).isEqualTo(VolumeCalculator.Status.UNTRAINED);
        assertThat(VolumeCalculator.classify(3, 10, 20)).isEqualTo(VolumeCalculator.Status.BELOW);
        assertThat(VolumeCalculator.classify(14, 10, 20)).isEqualTo(VolumeCalculator.Status.IN_RANGE);
        assertThat(VolumeCalculator.classify(26, 10, 20)).isEqualTo(VolumeCalculator.Status.ABOVE);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=VolumeCalculatorTest`

Expected: 编译失败，`cannot find symbol: class VolumeCalculator`

- [ ] **Step 3: 写实现**

```java
package com.change.calc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 训练容量统计：把「记了多少组」折算成「每个肌群多少组」。
 *
 * 规则（设计文档决定 5）：
 *   主要肌群 +1，次要肌群 +0.5。
 * 次要肌群算半分是训练量统计的通行做法——卧推对三头确实有刺激，
 * 但不该和臂屈伸算成一样的量。
 */
public final class VolumeCalculator {

    public enum Status { UNTRAINED, BELOW, IN_RANGE, ABOVE }

    /** 一条贡献：某个肌群、若干组、扮演什么角色。 */
    public record Entry(long muscleId, int sets, String role) {
    }

    private VolumeCalculator() {
    }

    public static Map<Long, Double> accumulate(List<Entry> entries) {
        Map<Long, Double> out = new HashMap<>();
        for (Entry e : entries) {
            double weight = switch (e.role()) {
                case "primary" -> 1.0;
                case "secondary" -> 0.5;
                default -> throw new IllegalArgumentException(
                        "未知的肌群角色: " + e.role() + "（只接受 primary / secondary）");
            };
            out.merge(e.muscleId(), e.sets() * weight, Double::sum);
        }
        return out;
    }

    /**
     * 与建议区间比较。
     * 注意：这个区间是预设的参照系，不是科学结论；用户可以在设置里改。
     */
    public static Status classify(double sets, int min, int max) {
        if (sets <= 0) return Status.UNTRAINED;
        if (sets < min) return Status.BELOW;
        if (sets > max) return Status.ABOVE;
        return Status.IN_RANGE;
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=VolumeCalculatorTest`

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/calc/VolumeCalculator.java \
        backend/src/test/java/com/change/calc/VolumeCalculatorTest.java
git commit -m "feat: 训练容量计算（主要 +1 / 次要 +0.5），含设计文档算例"
```

---

## Task 8: 1RM 估算

**Files:**
- Create: `backend/src/main/java/com/change/calc/OneRepMax.java`
- Test: `backend/src/test/java/com/change/calc/OneRepMaxTest.java`

- [ ] **Step 1: 写失败的测试**

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
        assertThat(OneRepMax.epley(100, 1)).isEqualTo(100.0);
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
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(62.5, 6),
                new OneRepMax.Set(65, 4)));

        assertThat(best).isEqualTo(75.0);
    }

    @Test
    void timed_sets_are_ignored() {
        // 平板支撑没有 1RM 的概念，传 null 次数应被跳过而不是抛错
        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(0, null)));

        assertThat(best).isEqualTo(76.0);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=OneRepMaxTest`

Expected: 编译失败，`cannot find symbol: class OneRepMax`

- [ ] **Step 3: 写实现**

```java
package com.change.calc;

import java.util.List;

/**
 * 估算 1RM。
 *
 * 为什么默认用 1RM 而不是最大重量：最大重量只在冲重量那一次才动，
 * 中间几个月曲线是平的；1RM 由每次的「重量 × 次数」估算，每次训练都能让曲线动一点。
 *
 * 用 Epley 公式：1RM = w × (1 + reps / 30)。
 */
public final class OneRepMax {

    /** 超过这个次数后 Epley 误差迅速变大，不再估算。 */
    public static final int MAX_REPS = 12;

    /** 一组的数据。repetitions 为 null 表示计时动作，跳过。 */
    public record Set(double weight, Integer repetitions) {
    }

    private OneRepMax() {
    }

    public static double epley(double weight, int reps) {
        if (reps < 1) {
            throw new IllegalArgumentException("reps 必须 >= 1: " + reps);
        }
        if (reps > MAX_REPS) {
            throw new IllegalArgumentException(
                    "reps 超过 " + MAX_REPS + " 时 Epley 估算不可靠: " + reps);
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight 不能为负: " + weight);
        }
        return round2(weight * (1 + reps / 30.0));
    }

    public static double bestOf(List<Set> sets) {
        double best = 0;
        for (Set s : sets) {
            if (s.repetitions() == null || s.repetitions() < 1 || s.repetitions() > MAX_REPS) {
                continue;   // 计时动作或超范围，跳过
            }
            best = Math.max(best, epley(s.weight(), s.repetitions()));
        }
        return best;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=OneRepMaxTest`

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/calc/OneRepMax.java \
        backend/src/test/java/com/change/calc/OneRepMaxTest.java
git commit -m "feat: Epley 1RM 估算，超过 12 次拒绝估算而不是给假精确值"
```

---

## Task 9: ChangeLog 与回退

这是「智能体可写」那条决定的安全带。

**Files:**
- Create: `backend/src/main/java/com/change/service/ChangeLogService.java`
- Test: `backend/src/test/java/com/change/service/ChangeLogServiceTest.java`

- [ ] **Step 1: 写失败的测试**

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
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=ChangeLogServiceTest`

Expected: 编译失败，`cannot find symbol: class ChangeLogService`

- [ ] **Step 3: 写实现**

```java
package com.change.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * 变更日志：让「智能体可写」这件事有安全带。
 *
 * 三条设计约束（设计文档决定 4）：
 *   1. 表名必须在白名单里 —— 工具集是白名单，不是让模型自由写 SQL
 *   2. 只记录 insert / update，不提供 delete —— 删除无法撤销，所以干脆不给
 *   3. 每条改动存下 before / after 的 JSON 快照，可一键回退
 */
@Service
public class ChangeLogService {

    /** 允许被记录与回退的表。加表必须改这里，改这里必须同时加测试。 */
    public static final Set<String> ALLOWED_TABLES = Set.of(
            "food", "food_portion", "meal", "meal_item",
            "exercise", "exercise_muscle",
            "workout_session", "session_exercise", "workout_set",
            "body_metric", "photo");

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
        return write(table, targetId, "update", before, after, origin, chatSessionId);
    }

    private long write(String table, long targetId, String action,
                       Map<String, Object> before, Map<String, Object> after,
                       String origin, Long chatSessionId) {
        requireAllowed(table);
        jdbc.update("INSERT INTO change_log(changed_at, origin, action, target_table, " +
                    "target_id, before_json, after_json, chat_session_id, undone) " +
                    "VALUES (?,?,?,?,?,?,?,?,0)",
                LocalDateTime.now().format(ISO), origin, action, table, targetId,
                before == null ? null : toJson(before), toJson(after), chatSessionId);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /** 回退一条改动。insert 的回退是删除该行；update 的回退是写回 before 值。 */
    public void undo(long logId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT action, target_table, target_id, before_json, undone " +
                "FROM change_log WHERE id = ?", logId);

        if (((Number) row.get("undone")).intValue() == 1) {
            throw new IllegalStateException("这条改动已经撤销过了: " + logId);
        }

        String table = (String) row.get("target_table");
        long targetId = ((Number) row.get("target_id")).longValue();
        String action = (String) row.get("action");
        requireAllowed(table);

        if ("insert".equals(action)) {
            jdbc.update("DELETE FROM " + table + " WHERE id = ?", targetId);
        } else {
            Map<String, Object> before = fromJson((String) row.get("before_json"));
            if (before == null || before.isEmpty()) {
                throw new IllegalStateException("update 记录缺少 before 快照: " + logId);
            }
            String sets = before.keySet().stream()
                    .map(k -> k + " = ?")
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
            Object[] values = before.values().toArray();
            Object[] args = new Object[values.length + 1];
            System.arraycopy(values, 0, args, 0, values.length);
            args[values.length] = targetId;
            jdbc.update("UPDATE " + table + " SET " + sets + " WHERE id = ?", args);
        }

        jdbc.update("UPDATE change_log SET undone = 1 WHERE id = ?", logId);
    }

    private void requireAllowed(String table) {
        if (!ALLOWED_TABLES.contains(table)) {
            throw new IllegalArgumentException(
                    "表「" + table + "」不在白名单里，拒绝记录或回退");
        }
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

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=ChangeLogServiceTest`

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/service/ChangeLogService.java \
        backend/src/test/java/com/change/service/ChangeLogServiceTest.java
git commit -m "feat: 变更日志与一键回退，表名白名单 + 禁止二次撤销"
```

---

## Task 10: HTTP 骨架与端口协商

外壳需要知道后台起在哪个端口。约定：**后台把端口以固定格式打到标准输出**，外壳读这一行。

**Files:**
- Create: `backend/src/main/java/com/change/config/PortReporter.java`
- Create: `backend/src/main/java/com/change/web/HealthController.java`
- Test: `backend/src/test/java/com/change/web/HealthControllerTest.java`

- [ ] **Step 1: 写失败的测试**

```java
package com.change.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthControllerTest {

    @LocalServerPort int port;

    @Autowired TestRestTemplate http;

    @Test
    void random_port_was_assigned() {
        assertThat(port).isGreaterThan(0);
    }

    @Test
    void health_reports_ok_without_touching_redis() {
        ResponseEntity<String> r = http.getForEntity("/api/health", String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getBody()).contains("\"status\":\"up\"");
        assertThat(r.getBody()).contains("\"cache\"");
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=HealthControllerTest`

Expected: FAIL —— 404，因为 `/api/health` 还不存在

- [ ] **Step 3: 写实现**

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

import org.springframework.beans.factory.annotation.Value;
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

- [ ] **Step 4: 临时提供一个 CacheStatus 实现**

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

- [ ] **Step 5: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=HealthControllerTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: 手动验证端口上报格式**

Run:
```bash
cd backend && mvn -B -q -DskipTests package && java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

Expected: 启动日志中有一行形如 `CHANGE_PORT=54321`。按 `Ctrl+C` 停掉。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/change/config/PortReporter.java \
        backend/src/main/java/com/change/config/CacheConfig.java \
        backend/src/main/java/com/change/web/HealthController.java \
        backend/src/test/java/com/change/web/HealthControllerTest.java
git commit -m "feat: HTTP 骨架 + 端口协商（stdout 上报 CHANGE_PORT=）"
```

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

- [ ] **Step 1: 写失败的测试**

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

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd backend && mvn -B test -Dtest=CacheServiceTest`

Expected: 编译失败，`cannot find symbol: class CacheService`

- [ ] **Step 3: 写接口与降级实现**

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

- [ ] **Step 4: 写 Redis 实现（含熔断）**

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

- [ ] **Step 5: 改 CacheConfig 做启动探测与条件装配**

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

- [ ] **Step 6: 跑缓存测试**

Run: `cd backend && mvn -B test -Dtest=CacheServiceTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: 跑全量测试，确认 Task 1 的失败已消失**

Run: `cd backend && mvn -B test`

Expected: 全部通过。**特别确认 `ChangeApplicationTests.contextLoads` 通过**——这条在 Task 1 时可能因 Redis 不可达而失败，现在应该绿。如果它仍然失败，说明降级没生效，回到 Step 5 检查。

- [ ] **Step 8: 验证两个场景（不需要开虚拟机）**

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

- [ ] **Step 9: 提交**

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
