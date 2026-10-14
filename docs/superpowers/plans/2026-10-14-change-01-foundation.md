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
    url: jdbc:sqlite:${change.data-dir:./target/data}/change.db?busy_timeout=5000&foreign_keys=true
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1          # SQLite 单写者，连接池开大只会互相等锁
      connection-timeout: 3000      # 别用默认 30s：单连接池被占满时要快速失败而不是挂住
      # 约定：池只有 1 条连接，禁止 REQUIRES_NEW（会自己等自己）
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
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

- [ ] **Step 1: 写训练域迁移 V2**

```sql
-- V2: 训练域 6 张表
-- 第一行先清掉 V1 的探针表：它是基线迁移的历史遗留，没有业务含义，
-- 不该永久留在每个用户的库里（理由见上一节的约定 3）。
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
    source_id     TEXT,                    -- free-exercise-db 的 id
    note          TEXT,
    archived      INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_exercise_source ON exercise(source_id) WHERE source_id IS NOT NULL;
CREATE INDEX ix_exercise_name_zh ON exercise(name_zh);

CREATE TABLE exercise_muscle (
    exercise_id     INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,
    muscle_group_id INTEGER NOT NULL REFERENCES muscle_group(id) ON DELETE CASCADE,
    role            TEXT    NOT NULL CHECK (role IN ('primary', 'secondary')),
    PRIMARY KEY (exercise_id, muscle_group_id)
);
CREATE INDEX ix_exercise_muscle_muscle ON exercise_muscle(muscle_group_id);

CREATE TABLE workout_session (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    session_date           TEXT    NOT NULL,   -- ISO-8601 日期
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
CREATE INDEX ix_sessex_session ON session_exercise(session_id);

CREATE TABLE workout_set (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    session_exercise_id INTEGER NOT NULL REFERENCES session_exercise(id) ON DELETE CASCADE,
    set_index           INTEGER NOT NULL,
    set_type            TEXT    NOT NULL DEFAULT 'normal'
                        CHECK (set_type IN ('normal','warmup','dropset','myo','partial',
                                            'forced','tut','iso','jump')),
    weight              REAL,
    repetitions         INTEGER,
    duration_seconds    INTEGER,               -- 计时动作（平板支撑等），与 repetitions 二选一
    per_side            INTEGER NOT NULL DEFAULT 0,
    rir                 REAL,
    rest_seconds        INTEGER,
    completed           INTEGER NOT NULL DEFAULT 0,
    target_weight       REAL,
    target_reps         INTEGER
);
CREATE INDEX ix_set_sessex ON workout_set(session_exercise_id);
```

- [ ] **Step 2: 写饮食域迁移 V3**

```sql
-- V3: 饮食域 4 张表
-- 铁律：营养一律按每 100 克存；条目只存份量与换算克数，绝不存绝对热量。

CREATE TABLE food (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name_zh      TEXT    NOT NULL,
    name_en      TEXT,
    source       TEXT    NOT NULL,     -- 'free-exercise-db' | 'manual' | 'cn-food-table'
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

CREATE TABLE meal (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    meal_date  TEXT    NOT NULL,
    slot       TEXT    NOT NULL CHECK (slot IN ('breakfast','lunch','dinner','snack')),
    eaten_at   TEXT,
    note       TEXT,
    photo_id   INTEGER                 -- 外键在 V4 建表后才有意义，此处不加约束
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
```

- [ ] **Step 3: 写辅助域迁移 V4**

```sql
-- V4: 辅助域 5 张表

CREATE TABLE photo (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    taken_on   TEXT    NOT NULL,
    rel_path   TEXT    NOT NULL,       -- 只存相对路径，根目录由配置决定
    sha256     TEXT    NOT NULL,
    width      INTEGER,
    height     INTEGER,
    kind       TEXT    NOT NULL CHECK (kind IN ('food','body','other')),
    note       TEXT
);
CREATE UNIQUE INDEX ux_photo_sha ON photo(sha256);
CREATE INDEX ix_photo_kind_date ON photo(kind, taken_on);

CREATE TABLE body_metric (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    measured_on TEXT    NOT NULL,
    metric_type TEXT    NOT NULL,      -- weight / body_fat / waist / arm / ...
    value       REAL    NOT NULL,
    unit        TEXT    NOT NULL,
    note        TEXT
);
CREATE INDEX ix_metric_type_date ON body_metric(metric_type, measured_on);

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
CREATE INDEX ix_chat_msg_session ON chat_message(session_id);

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
    undone      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_changelog_target ON change_log(target_table, target_id);
CREATE INDEX ix_changelog_time ON change_log(changed_at);
```

- [ ] **Step 4: 写表结构测试**

用一张期望清单核对，**避免漏建或多建**。

```java
package com.change;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
        List<String> names = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'", String.class);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void meal_item_has_no_stored_calorie_column() {
        // 这条是防回归：一旦有人「顺手」加了 kcal 列，测试立刻失败。
        List<String> cols = jdbc.queryForList("PRAGMA table_info(meal_item)", String.class);
        assertThat(cols).doesNotContain("kcal", "calories", "energy");
    }

    @Test
    void workout_set_supports_timed_and_per_side() {
        Set<String> cols = new java.util.HashSet<>(
                jdbc.queryForList("SELECT name FROM pragma_table_info('workout_set')", String.class));
        assertThat(cols).contains("duration_seconds", "per_side", "target_weight", "target_reps");
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `cd backend && mvn -B test -Dtest=SchemaTest`

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/resources/db/migration/V2__training.sql \
        backend/src/main/resources/db/migration/V3__nutrition.sql \
        backend/src/main/resources/db/migration/V4__auxiliary.sql \
        backend/src/test/java/com/change/SchemaTest.java
git commit -m "feat: 十五张表的迁移，含两条防回归断言（不存热量、支持计时与每侧）"
```

---

## Task 4: 训练域 JPA 实体与仓储

**Files:**
- Create: `backend/src/main/java/com/change/domain/Exercise.java`
- Create: `backend/src/main/java/com/change/domain/WorkoutSession.java`
- Create: `backend/src/main/java/com/change/domain/SessionExercise.java`
- Create: `backend/src/main/java/com/change/domain/WorkoutSet.java`
- Create: `backend/src/main/java/com/change/repo/ExerciseRepo.java`
- Create: `backend/src/main/java/com/change/repo/WorkoutSessionRepo.java`
- Test: `backend/src/test/java/com/change/repo/TrainingRepoTest.java`

- [ ] **Step 1: 写实体（四个）**

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

- [ ] **Step 2: 写两个仓储**

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

- [ ] **Step 3: 写集成测试**

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

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn -B test -Dtest=TrainingRepoTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

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

- [ ] **Step 1: 写失败的测试**

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
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

Run: `cd backend && mvn -B test -Dtest=NutritionCalculatorTest`

Expected: 编译失败，提示 `cannot find symbol: class Nutrition` / `NutritionCalculator`

- [ ] **Step 3: 写实现**

```java
package com.change.calc;

/** 一份营养值。单位：kcal 与克。 */
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

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd backend && mvn -B test -Dtest=NutritionCalculatorTest`

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/change/calc/Nutrition.java \
        backend/src/main/java/com/change/calc/NutritionCalculator.java \
        backend/src/test/java/com/change/calc/NutritionCalculatorTest.java
git commit -m "feat: 热量计算（每 100 克基准 + 实时换算），含份量上限校验"
```

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
