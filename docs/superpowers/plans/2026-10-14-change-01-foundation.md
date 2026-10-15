# 鍦板熀锛氭暟鎹眰涓庢牳蹇冭绠?路 瀹炴柦璁″垝

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 寤虹珛鍚庣楠ㄦ灦銆佸崄浜斿紶琛ㄧ殑鏁版嵁灞傦紝浠ュ強涓変釜蹇呴』姝ｇ‘鐨勬牳蹇冭绠楋紙鐑噺銆佷唤閲忔崲绠椼€佽缁冨閲忥級锛岃鍚庣画鎵€鏈夊姛鑳介兘鏈夊彲淇＄殑鍦板熀銆?
**Architecture:** 鍗曚綋 Spring Boot 搴旂敤锛孲QLite 鍗曟枃浠跺瓨鍌紝Flyway 绠¤縼绉汇€備笁涓牳蹇冭绠楀啓鎴?*涓嶄緷璧?Spring 涓婁笅鏂?*鐨勭函绫伙紝鐢ㄥ崟鍏冩祴璇曢攣姝昏涓衡€斺€斿洜涓哄畠浠殑閿欒浼氭薄鏌撴墍鏈変笅娓哥粺璁★紝鑰屼笖寰堥毦浜嬪悗鍙戠幇銆?
**Tech Stack:** JDK 25 路 Spring Boot 3.5.16 路 Flyway 路 SQLite锛圚ibernate 绀惧尯鏂硅█锛壜?LangChain4j 1.22.0锛堜粎瑁呴厤锛屾湰璁″垝涓嶄娇鐢級路 Redis锛堝彲閫夛紝蹇呴』鍙檷绾э級

**鍓嶇疆锛?* 璇?`docs/superpowers/plans/2026-10-14-change-phase1-overview.md` 鐨勩€屽叏灞€绾﹀畾銆嶃€俙spike/` 閲屽凡楠岃瘉杩囩殑 `pom.xml` 鏄湰璁″垝 Task 1 鐨勫熀纭€銆?
---

## 鏂囦欢缁撴瀯

```
backend/
鈹溾攢鈹€ pom.xml
鈹斺攢鈹€ src/
    鈹溾攢鈹€ main/
    鈹?  鈹溾攢鈹€ java/com/change/
    鈹?  鈹?  鈹溾攢鈹€ ChangeApplication.java
    鈹?  鈹?  鈹溾攢鈹€ config/            # 缂撳瓨銆佺鍙ｄ笂鎶ョ瓑鍩虹璁炬柦瑁呴厤
    鈹?  鈹?  鈹?  鈹溾攢鈹€ CacheConfig.java
    鈹?  鈹?  鈹?  鈹溾攢鈹€ DataDirectoryInitializer.java   # Task 1锛氬缓鏁版嵁鐩綍 + 瑙勮寖鍖栬矾寰勫洖鍐?    鈹?  鈹?  鈹?  鈹斺攢鈹€ PortReporter.java
    鈹?  鈹?  鈹溾攢鈹€ domain/            # JPA 瀹炰綋锛屼竴琛ㄤ竴绫?    鈹?  鈹?  鈹?  鈹溾攢鈹€ Exercise.java
    鈹?  鈹?  鈹?  鈹溾攢鈹€ WorkoutSession.java
    鈹?  鈹?  鈹?  鈹溾攢鈹€ SessionExercise.java
    鈹?  鈹?  鈹?  鈹溾攢鈹€ WorkoutSet.java
    鈹?  鈹?  鈹?  鈹斺攢鈹€ ...
    鈹?  鈹?  鈹溾攢鈹€ repo/              # Spring Data 浠撳偍
    鈹?  鈹?  鈹溾攢鈹€ calc/              # 鈽?涓変釜鏍稿績璁＄畻锛岀函绫汇€佹棤 Spring 渚濊禆
    鈹?  鈹?  鈹?  鈹溾攢鈹€ NutritionCalculator.java
    鈹?  鈹?  鈹?  鈹溾攢鈹€ PortionConverter.java
    鈹?  鈹?  鈹?  鈹溾攢鈹€ VolumeCalculator.java
    鈹?  鈹?  鈹?  鈹斺攢鈹€ OneRepMax.java
    鈹?  鈹?  鈹斺攢鈹€ service/
    鈹?  鈹?      鈹斺攢鈹€ ChangeLogService.java
    鈹?  鈹斺攢鈹€ resources/
    鈹?      鈹溾攢鈹€ application.yml
    鈹?      鈹溾攢鈹€ META-INF/spring.factories          # 娉ㄥ唽 DataDirectoryInitializer
    鈹?      鈹斺攢鈹€ db/migration/
    鈹?          鈹溾攢鈹€ V1__baseline.sql
    鈹?          鈹溾攢鈹€ V2__training.sql
    鈹?          鈹溾攢鈹€ V3__nutrition.sql
    鈹?          鈹斺攢鈹€ V4__auxiliary.sql
    鈹斺攢鈹€ test/
        鈹溾攢鈹€ java/com/change/
        鈹?  鈹溾攢鈹€ ChangeApplicationTests.java
        鈹?  鈹溾攢鈹€ calc/
        鈹?  鈹?  鈹溾攢鈹€ NutritionCalculatorTest.java
        鈹?  鈹?  鈹溾攢鈹€ PortionConverterTest.java
        鈹?  鈹?  鈹溾攢鈹€ VolumeCalculatorTest.java
        鈹?  鈹?  鈹斺攢鈹€ OneRepMaxTest.java
        鈹?  鈹溾攢鈹€ MigrationTest.java
        鈹?  鈹溾攢鈹€ repo/TrainingRepoTest.java
        鈹?  鈹斺攢鈹€ service/ChangeLogServiceTest.java
        鈹斺攢鈹€ resources/application.properties        # 娴嬭瘯鐢ㄧ粷瀵规暟鎹洰褰曪紝閬垮厤纰扮湡瀹炴暟鎹?```

**涓轰粈涔?`calc/` 瑕佺嫭绔嬫垚鍖呬笖涓嶄緷璧?Spring**锛氳繖涓変釜璁＄畻鐨勬纭€у喅瀹氭墍鏈夌粺璁＄殑鍙俊搴︼紝浣嗗畠浠拰妗嗘灦鏃犲叧銆傚仛鎴愮函绫诲彲浠ョ敤鏈€蹇殑鍗曞厓娴嬭瘯閿佹琛屼负锛岃窇涓€娆′笉鍒颁竴绉掋€?
---

## Task 1: 鍚庣椤圭洰楠ㄦ灦

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/change/ChangeApplication.java`
- Create: `backend/src/main/java/com/change/config/DataDirectoryInitializer.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/META-INF/spring.factories`
- Test: `backend/src/test/java/com/change/ChangeApplicationTests.java`
- Test: `backend/src/test/java/com/change/config/DataDirectoryInitializerTest.java`
- Test: `backend/src/test/resources/application.properties`

**涓轰粈涔堥渶瑕佹暟鎹洰褰曞垵濮嬪寲鍣紙`DataDirectoryInitializer`锛?*

SQLite 涓嶄細鍒涘缓鐖剁洰褰曪紝鐩綍涓嶅瓨鍦ㄦ椂 Flyway 浼氬湪寤鸿繛闃舵鐩存帴鎶?`SQLITE_CANTOPEN`锛孒ibernate 鏍规湰杞笉鍒版墽琛屻€傛墍浠ユ暟鎹洰褰曞繀椤诲湪**浠讳綍 Bean 瀹炰緥鍖栦箣鍓?*灏卞缓濂姐€?
- 鐢?`EnvironmentPostProcessor` 鑰屼笉鏄櫘閫?Bean锛氬畠鏄幆澧冨噯澶囬樁娈佃窇鐨勶紝鏃╀簬 `DataSource`/Flyway 鐨勮閰嶏紝涔熸棭浜?`@SpringBootTest` 鐨勭幆澧冭閰嶁€斺€擿main()` 涓庢祴璇曢兘鑳借鐩栧埌锛堟敞鍐屽湪 `META-INF/spring.factories`锛夈€?- 瑙勮寖鍖栧悗**鍥炲啓 environment**锛氱洰褰曠殑鍒涘缓鑰呬笌娑堣垂鑰呬笉鑳藉悇鑷В閲婂悓涓€涓瓧绗︿覆銆俙CHANGE_DATA_DIR=E:\devtools\cache\dt\` 杩欑甯﹀熬閮ㄥ弽鏂滄潬鐨勫啓娉曪紝`Path.of()` 浼氳鑼冨寲鎺夛紝鑰?SQLite 鎷垮埌鐨勬槸鍘熶覆锛岀粨鏋滃氨鏄?`SQLITE_CANTOPEN`銆傚洖鍐欐垚鍚屼竴涓粷瀵硅矾寰勫悗锛屾暟鎹簮 URL銆丗lyway銆佸皢鏉ョ殑鐓х墖鏍圭洰褰曟秷璐圭殑閮芥槸鍚屼竴涓€笺€?- **绌轰覆鍏滃簳**锛歚${APPDATA:...}` 鐨勫绾у厹搴曞彧鑳藉鐞嗐€屽彉閲忎笉瀛樺湪銆嶃€俙APPDATA` **瀛樺湪涓斾负绌轰覆**鏃讹紙瀹炴祴鐨勬満鍣ㄧ幆澧冿級锛屽崰浣嶇浼氳В鏋愭垚绌轰覆锛屾嫾鍑?`/changE`锛屽湪 Windows 涓婄瓑浜庛€屽綋鍓嶇洏:\changE銆嶃€傝繖涓€灞傚湪鍒濆鍖栧櫒閲屾寜 `user.home` 鍐嶅厹涓€娆★紝鍏滀笉浣忓氨鎶ラ敊锛岀粷涓嶉潤榛樺啓杩涚洏鏍广€?- 鍚姩鏃ュ織鐢?`DeferredLogFactory` 娉ㄥ叆鐨勫欢杩熸棩蹇楁墦涓€琛屾暟鎹洰褰曚綅缃€斺€斿簲鐢ㄨ捣鏉ュ悗銆屽簱鍒板簳寤哄摢鍘讳簡銆嶆槸鎺掓煡鏃剁殑鍒氶渶銆?
- [x] **Step 1: 鍐?pom.xml**

浠?`spike/pom.xml` 涓哄熀纭€锛屽幓鎺?spike 涓撶敤鐨勯儴鍒嗭紝鍔犱笂 Flyway銆?*鐗堟湰鍙风収鎶勶紝涓嶈鍑蹇嗘敼**鈥斺€旇繖浜涙槸 2026-10-14 瀹炴祴閫氳繃鐨勭粍鍚堛€?
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

- [x] **Step 2: 鍐欏惎鍔ㄧ被**

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

- [x] **Step 3: 鍐?application.yml**

```yaml
spring:
  application:
    name: change
  datasource:
    # busy_timeout=5000锛歋QLite 榛樿 busy_timeout=0锛屽啓鍐茬獊浼氱珛鍒诲け璐ワ紱缁?5 绉掕鍗曞啓鑰呮帓闃?    # foreign_keys=true锛歋QLite 榛樿 PRAGMA foreign_keys=0锛屼笖璇ュ紑鍏虫槸銆屾瘡杩炴帴銆嶇殑锛?    # 杩佺Щ閲屽ぇ閲?REFERENCES ... ON DELETE CASCADE 涓嶅紑杩欎竴椤瑰氨鍙槸瑁呴グ锛堢骇鑱斿垹闄や笉鍙戠敓锛?    url: jdbc:sqlite:${change.data-dir:./target/data}/change.db?busy_timeout=5000&foreign_keys=true
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1          # SQLite 鍗曞啓鑰咃紝杩炴帴姹犲紑澶у彧浼氫簰鐩哥瓑閿?      connection-timeout: 3000      # 鍒敤榛樿 30s锛氬崟杩炴帴姹犺鍗犳弧鏃惰蹇€熷け璐ヨ€屼笉鏄寕浣?      # 绾﹀畾锛氭睜鍙湁 1 鏉¤繛鎺ワ紝绂佹 REQUIRES_NEW锛堜細鑷繁绛夎嚜宸憋級
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: validate            # 寤鸿〃浜ょ粰 Flyway锛孒ibernate 鍙牎楠?    open-in-view: false
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
  address: 127.0.0.1                # 鏃犻壌鏉冪殑鍚庣鍙鏈満璁块棶锛汿auri 澹充篃璧?127.0.0.1
  port: 0                           # 鐢辩郴缁熷垎閰嶏紱瀹為檯绔彛鐢?PortReporter 涓婃姤锛圱ask 10锛?
change:
  # APPDATA 瀛樺湪涓斾负绌轰覆鏃?${APPDATA}/changE 浼氳В鏋愭垚 /changE锛屽湪 Windows 涓婄瓑浜庛€屽綋鍓嶇洏:\changE銆嶏紝
  # 闈欓粯鎶婂簱鍐欒繘鐩樻牴涓嶅彲鎺ュ彈锛屾墍浠ヨ繖閲岄€愮骇鍏滃簳鍒?user.home锛堢┖涓叉儏褰㈠彟鐢?DataDirectoryInitializer 鍏滃簳锛?  data-dir: ${CHANGE_DATA_DIR:${APPDATA:${user.home}/AppData/Roaming}/changE}
  redis:
    enabled: true
```

**娉ㄦ剰 `server.address`**锛氫笉鍐欒繖涓€琛屾椂 Tomcat 缁戠殑鏄?`::`锛堢瓑浠?`0.0.0.0`锛夛紝鑰岃繖涓悗绔?*娌℃湁浠讳綍閴存潈**锛孴ask 10 涔嬪悗杩樹細鏈夎兘璇诲啓鍏ㄩ儴鏁版嵁銆佽兘璋冩ā鍨?API 鐨?`/api/chat`銆傝璁℃枃妗ｅ啓鐨勬槸銆屾湰鍦?HTTP锛?27.0.0.1 闅忔満绔彛銆嶏紝鎵€浠ュ繀椤绘樉寮忕粦鍥炵幆銆?
- [x] **Step 4: 鍐欎笂涓嬫枃鍔犺浇娴嬭瘯**

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
 * 涓婁笅鏂囧姞杞?+ 鏁版嵁鐩綍绾﹀畾鐨勯獙鏀躲€? *
 * 杩欓噷鍒绘剰淇濇寔榛樿鐨?MOCK web 鐜锛堜笉璧风湡瀹瑰櫒锛夛紝绔彛鍗忓晢涓庣洃鍚湴鍧€鐢?Task 10
 * 鐢?RANDOM_PORT / 鏄惧紡缁戝畾瑕嗙洊锛涙湰娴嬭瘯鍙叧蹇冦€屾崲涓伐浣滅洰褰曚篃涓嶆崲搴撱€嶈繖鏉＄害瀹氥€? */
@SpringBootTest
class ChangeApplicationTests {

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Test
    void contextLoads() {
        // 鑳藉姞杞藉嵆閫氳繃銆俁edis 涓嶅彲杈炬椂涔熷繀椤婚€氳繃鈥斺€旇繖鏄檷绾х殑楠屾敹鐐广€?    }

    @Test
    void data_directory_is_absolute_created_and_usable() throws Exception {
        Path dir = Path.of(env.getProperty("change.data-dir"));

        assertThat(dir.isAbsolute())
                .as("鏁版嵁鐩綍蹇呴』鏄粷瀵硅矾寰勶紝鍚﹀垯鎹釜宸ヤ綔鐩綍灏辨崲涓€涓簱")
                .isTrue();
        assertThat(Files.isDirectory(dir))
                .as("鏁版嵁鐩綍蹇呴』琚嚜鍔ㄥ垱寤猴紙SQLite 涓嶄細寤虹埗鐩綍锛?)
                .isTrue();

        try (Connection c = dataSource.getConnection()) {
            assertThat(c.isValid(1)).as("杩炴帴蹇呴』鐪熺殑鍙敤").isTrue();
        }
    }
}
```

娴嬭瘯鐢?`src/test/resources/application.properties` 鎶?`change.data-dir` 鎸囧埌 `${user.dir}/target/data`锛堢粷瀵硅矾寰勶級锛屼繚璇佹祴璇曟案杩滅涓嶅埌鐪熷疄鏁版嵁鐩綍锛?
```properties
change.data-dir=${user.dir}/target/data
```

涓婇潰杩欐潯鏂█鏄亽鐪熺殑锛堝垵濮嬪寲鍣ㄥ厛鍥炲啓銆佸悗璇诲彇锛岃 Step 5 鐨勮鏄庯級锛屾墍浠ュ彟寤轰竴涓ˉ鐩插尯鐨勬祴璇曠被銆傚畠涓嶈捣 Spring 涓婁笅鏂囷紝鎵嬫悡 `MockEnvironment` 鐩存帴瀵瑰垵濮嬪寲鍣ㄤ笅鏂█锛?
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
 * 鐩存帴瀵?DataDirectoryInitializer 鐨勮涓轰笅鏂█锛岃€屼笉鏄€忚繃 @SpringBootTest 鐪嬪洖鍐欏悗鐨勭粨鏋溿€? *
 * 涓轰粈涔堥渶瑕佽繖涓祴璇曪細ChangeApplicationTests 閲岄偅鏉℃柇瑷€锛堣鍒扮殑鏄粷瀵硅矾寰勶級鏄亽鐪熺殑锛? * 鍥犱负鍒濆鍖栧櫒鍦?Spring 璇诲彇灞炴€т箣鍓嶅氨鎶婂€煎洖鍐欐垚浜嗙粷瀵硅矾寰勨€斺€斿畠姘歌繙鐪嬩笉鍒般€岄厤缃鏀瑰潖銆嶏紝
 * 鐪熸琚瘉鏄庣殑鍙槸銆屾暟鎹洰褰曞彲鐢ㄣ€嶃€傛湰绫昏ˉ涓婁笁涓洸鍖猴細
 *   - 鍥炲啓涓庤鑼冨寲杩欐潯鍔ㄤ綔鏈韩锛堝垹鎺夊洖鍐?/ 鍒犳帀 normalize 绔嬪埢鍙樼孩锛夛紱
 *   - 鐩樻牴鍏滃簳鐪熺殑鍏滀綇浜嗭紙绌轰覆 APPDATA 閭ｆ潯璺緞锛夛紱
 *   - 鍙戝竷鐨勯粯璁ゅ€间笉鏄伐浣滅洰褰曠浉瀵硅矾寰勶紙鍙兘闈犺鏈粡鍥炲啓鐨勫師濮嬮厤缃潵娴嬶級銆? *
 * 鍏ㄩ儴鏄函鍗曞厓娴嬭瘯锛氭墜鎼?MockEnvironment銆佹墜鎼?DeferredLogFactory锛屼笉璧?Spring 涓婁笅鏂囥€? */
class DataDirectoryInitializerTest {

    /**
     * 涓?spring.factories 娉ㄥ唽鐨勬瀯閫犲嚱鏁颁竴鑷达細鐪熷疄绫讳緷璧?DeferredLogFactory锛?     * 灏辩敤 DeferredLog 鐩存帴椤朵笂锛屼笉涓鸿縼灏辨祴璇曞幓鏀圭敓浜т唬鐮佺殑鏋勯€犲嚱鏁般€?     */
    private static DataDirectoryInitializer initializer() {
        return new DataDirectoryInitializer(clazz -> new DeferredLog());
    }

    @Test
    void relative_input_is_rewritten_to_an_absolute_normalized_path() throws Exception {
        String configured = "./target/rel/";
        MockEnvironment env = new MockEnvironment();
        env.setProperty("change.data-dir", configured);

        initializer().postProcessEnvironment(env, null);

        // 鏂█銆岄厤缃?鈫?缁濆璺緞銆嶇殑杞崲缁撴灉鏈韩锛岃€屼笉鏄畠鏄惁鍙敤銆?        // 鍒犳帀鍥炲啓閭ｄ袱琛岋紝鎴栬€呭幓鎺?.normalize()锛岃繖鏉＄珛鍒诲彉绾€?        assertThat(env.getProperty("change.data-dir"))
                .isEqualTo(Path.of(configured).toAbsolutePath().normalize().toString());
        assertThat(Files.isDirectory(Path.of(env.getProperty("change.data-dir")))).isTrue();
    }

    @Test
    void root_level_path_falls_back_under_user_home() throws Exception {
        // 杩欐潯浼氱湡鐨?createDirectories锛屾墍浠ユ妸 user.home 鎸囧埌 target 涓嬬殑涓存椂鐩綍锛?        // 缁濅笉纰扮湡瀹炵敤鎴锋暟鎹洰褰曪紙C:\Users\<me>\AppData\Roaming锛夈€?        Path tempHome = Path.of("target", "user-home-probe").toAbsolutePath().normalize();
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

            // 澶嶇幇鍓嶆彁锛氳繖涓緭鍏ヨВ鏋愬悗纭疄鍙墿銆岀洏鏍?+ 涓€灞傘€嶏紝鍚﹀垯杩欐潯娴嬭瘯娌″湪娴嬪厹搴?            assertThat(rootLevelCandidate.getNameCount()).isEqualTo(1);
            assertThat(result)
                    .as("鐩樻牴涓嬬殑鍗曚釜鐩綍蹇呴』琚厹搴曟帀锛岃€屼笉鏄惤鍦ㄧ洏鏍?)
                    .isNotEqualTo(rootLevelCandidate);
            assertThat(result.getNameCount()).isGreaterThan(1);
            assertThat(result.startsWith(tempHome)).isTrue();
            assertThat(Files.isDirectory(result)).isTrue();
            assertThat(Files.exists(rootLevelCandidate)).isFalse();
        } finally {
            System.setProperty("user.home", previousUserHome);
            deleteRecursively(tempHome);            // 璺戝畬娓呯悊涓存椂 home
        }
    }

    @Test
    void published_default_data_dir_is_not_a_working_directory_relative_path() throws Exception {
        // 鍞竴鑳介槻浣忋€岄粯璁ゅ€艰鏀瑰洖鐩稿璺緞銆嶇殑鏂瑰紡锛氳鏈粡鍒濆鍖栧櫒鍥炲啓鐨勫師濮嬮厤缃€?        // 璧?Spring 鐜鎷垮埌鐨勫€间竴瀹氬凡缁忔槸缁濆璺緞锛岀湅涓嶅嚭榛樿鍊兼湰韬啓鐨勬槸浠€涔堛€?        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
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

- [x] **Step 5: 璺戞祴璇?*

Run: `cd backend && Remove-Item -Recurse -Force target; mvn -B test`

Expected: `BUILD SUCCESS`锛宍Tests run: 5, Failures: 0, Errors: 0`
锛坄ChangeApplicationTests` 2 鏉?+ 澶嶅琛ラ綈鐨?`DataDirectoryInitializerTest` 3 鏉★級

涓や釜娴嬭瘯鍒嗗埆閿佷綇锛氣憼 涓婁笅鏂囪兘鍔犺浇锛圧edis 涓嶅彲杈炬椂蹇呴』閫氳繃锛岃繖鏄?Task 11 鐨勯檷绾ч獙鏀剁偣锛夛紱鈶?`change.data-dir` 鏄粷瀵硅矾寰勩€佺洰褰曠‘瀹炶鍒涘缓銆乣DataSource` 鎷垮埌鐨勮繛鎺ョ湡鐨勫彲鐢ㄣ€?
**鈶?鐪熸璇佹槑鐨勫彧鏄€屾暟鎹洰褰曟渶缁堝彲鐢ㄣ€?*锛氭暟鎹洰褰曟渶缁堟槸缁濆璺緞銆佸凡缁忚鍒涘缓銆佽繛鎺ュ彲鐢ㄣ€傛崲宸ヤ綔鐩綍涔熷氨鎹㈠簱杩欐潯绾﹀畾锛岄潬鐨勬槸銆岀洰褰曠湡鐨勫缓濂戒簡 + 杩炴帴鐪熺殑鑳藉紑銆嶈繖涓粨鏋滐紝鑰屼笉鏄潬璇诲彇閰嶇疆鏈韩銆?
**鈶?涓嶈兘璇佹槑銆岄厤缃病琚敼鍧忋€?*锛歚DataDirectoryInitializer` 鏄?`EnvironmentPostProcessor`锛屽畠鍦?Spring 璇诲埌 `change.data-dir` **涔嬪墠**灏辨妸瑙勮寖鍖栫粨鏋滃洖鍐欒繘浜嗗睘鎬ф簮锛屾墍浠?`env.getProperty("change.data-dir")` 鎷垮埌鐨勬案杩滄槸鍥炲啓鍊尖€斺€斿摢鎬?`application.yml` 鐨勯粯璁ゅ€艰鏀规垚 `./target/data`锛宍isAbsolute()` 涔熺収鏍蜂负鐪熴€傝繖鏉℃柇瑷€鍦ㄧ粨鏋勪笂涓嶅彲鑳芥槸绾㈢殑锛堝疄娴嬶細鎶婇厤缃敼鎴?`./target/relative-probe`锛屽浠朵緷鐒跺叏缁匡級銆?
瑕佺湡姝ｉ槻浣忋€岄粯璁ゅ€艰鏀瑰洖鐩稿璺緞銆嶏紝鍙兘璇?*鏈粡鍥炲啓鐨勫師濮嬮厤缃?*锛屼篃灏辨槸 `DataDirectoryInitializerTest.published_default_data_dir_is_not_a_working_directory_relative_path()`銆?
濡傛灉鍥犱负 Redis 杩炰笉涓婅€屽け璐ワ紝璇存槑闄嶇骇杩樻病鍋氣€斺€?*鍏堢户缁紝Task 11 浼氳В鍐冲畠**锛涗絾璁颁笅杩欎釜澶辫触锛孴ask 11 缁撴潫鏃跺畠蹇呴』鍙樼豢銆?
- [x] **Step 6: 鎻愪氦**

```bash
git add backend/pom.xml backend/src backend/src/test/resources
git commit -m "chore: 鍚庣椤圭洰楠ㄦ灦锛圫pring Boot 3.5.16 + JDK 25 + SQLite + Flyway锛?
```

---

## Task 2: 鎺ラ€?SQLite 涓?Flyway

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__baseline.sql`
- Test: `backend/src/test/java/com/change/MigrationTest.java`

- [x] **Step 1: 鍐欏熀绾胯縼绉?*

```sql
-- V1: 鍩虹嚎銆傚彧寤轰竴寮犳帰閽堣〃锛岀敤鏉ラ獙璇?Flyway 鐪熺殑璺戣捣鏉ヤ簡銆?CREATE TABLE IF NOT EXISTS schema_probe (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    note       TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO schema_probe (note, created_at)
VALUES ('baseline', '2026-10-14T00:00:00');
```

- [x] **Step 2: 鍐欒縼绉绘祴璇?*

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

- [x] **Step 3: 璺戞祴璇?*

Run: `cd backend && mvn -B test -Dtest=MigrationTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [x] **Step 4: 鎻愪氦**

```bash
git add backend/src/main/resources/db/migration/V1__baseline.sql backend/src/test/java/com/change/MigrationTest.java
git commit -m "feat: 鎺ラ€?SQLite 涓?Flyway锛屽姞鍩虹嚎杩佺Щ涓庨獙璇佹祴璇?
```

---

## Task 3: 鍗佷簲寮犺〃鐨勮縼绉?
**Files:**
- Create: `backend/src/main/resources/db/migration/V2__training.sql`
- Create: `backend/src/main/resources/db/migration/V3__nutrition.sql`
- Create: `backend/src/main/resources/db/migration/V4__auxiliary.sql`
- Test: `backend/src/test/java/com/change/SchemaTest.java`

### 杩涘叆 Task 3 鍓嶅繀椤婚伒瀹堢殑绾﹀畾

> 浠ヤ笅浜旀潯鏉ヨ嚜 Task 2 鐨勪唬鐮佽川閲忓鏌ワ紝**鍦ㄥ姩鎵嬪啓 `V2__training.sql` 涔嬪墠鍏堣瀹?*銆?> 绗?1 鏉℃槸瀹℃煡瀹炴祴锛涚 2銆? 鏉″湪鏈疆澶嶆牳閲岄噸鏂板疄娴嬭繃涓€閬嶏紙缁撹涓庡師瀹℃煡涓€鑷达級銆?> 瀹冧滑涓嶆槸椋庢牸鍋忓ソ锛屾瘡涓€鏉￠兘瀵瑰簲涓€娆′細鐪熷疄鍙戠敓鐨勬晠闅溿€?
1. **浠讳綍杩佺Щ鑴氭湰閲屼笉鍑虹幇 `PRAGMA`** 鈥斺€?涓€寰嬭蛋 JDBC URL 鍙傛暟锛坄application.yml` 鐨?`url`锛夈€?   瀹炴祴锛氳 Flyway 璺戜竴浠藉惈 `PRAGMA journal_mode=WAL;` 鐨勮縼绉伙紝SQLite 鐩存帴鎷掔粷锛屾姤
   `cannot change into wal mode from within a transaction` 鈥斺€?Flyway 榛樿鎶婅縼绉诲寘鍦ㄤ簨鍔￠噷銆?   鍔?`-- flyway:executeInTransaction=false` 涔熶笉鏄粫杩囧姙娉曪紙瀹炴祴浠嶅け璐ワ級銆?   鎵€浠ユ兂寮€ `journal_mode=WAL` 鍙兘鍐欏湪 URL 涓婏細**杩佺Щ鑴氭湰杩欐潯璺棤璁烘€庝箞璧伴兘鏄敊鐨?*銆?
2. **杩佺Щ閲屼笉鍔?`IF NOT EXISTS`**锛堝敮涓€渚嬪鏄?V1 杩欎釜宸插彂甯冪殑鍘嗗彶閬楃暀锛屼笖瀹冧笉璁稿啀鏀癸級銆?   `flyway_schema_history` 宸茬粡淇濊瘉銆屼竴涓増鏈彧璺戜竴娆°€嶏紝`IF NOT EXISTS` 鎹笉鏉ヤ换浣曚繚鎶わ紝
   鍗村埗閫犱簡銆岃〃瀛樺湪 鈮?鏈縼绉诲缓浜嗗畠銆嶈繖涓ā绯婂湴甯︹€斺€旀祴璇曟柇瑷€姝ｆ槸鍥犳澶辨晥鐨勩€?   瀹炴祴锛氭妸 V1 鐨勫唴瀹规竻绌恒€佸彧鐣欐敞閲婏紝Flyway 鐓ф牱鎶?`Successfully applied 1 migration` 骞惰涓?   `success=1`銆傛鏃舵棫鏂█锛堟暟涓€琛屾暟鎹級涔嬫墍浠ュ彉绾紝鍙槸鍥犱负鏌ヤ竴寮犱笉瀛樺湪鐨勮〃鎶涗簡
   `no such table: schema_probe`鈥斺€?*鏄宸ф挒绾㈢殑锛屼笉鏄柇瑷€鍙戠幇浜嗐€岃繖娆¤縼绉讳粈涔堥兘娌″缓銆?*锛?   鑰屽鏋?V1 鍙槸鎶婅〃寤洪敊浜嗭紙婕?`NOT NULL`銆佷涪浜?`AUTOINCREMENT`锛夛紝鏃ф柇瑷€浼氬叏缁裤€?   瀵?*鍞竴绱㈠紩**灏ゅ叾鍗遍櫓锛氳闈欓粯璺宠繃涓嶄細鏈変换浣曟姤閿欙紝鍙細鐣欎笅鎮勬倓鍙樻參銆佹垨鎮勬倓鍏佽閲嶅鐨勬煡璇€?
3. **`V2__training.sql` 鐨勭涓€琛屽姞 `DROP TABLE schema_probe;`**銆傛帰閽堣〃鏄?V1 鐨勫巻鍙查仐鐣欙紝
   娌℃湁涓氬姟鍚箟锛屼笉璇ユ案涔呯暀鍦ㄦ瘡涓敤鎴风殑搴撻噷銆傚悓鏃舵妸 `SchemaTest.all_fifteen_tables_exist`
   閲?`name <> 'schema_probe'` 閭ｄ釜鐗逛緥涓€骞跺垹鎺夛紙鏈鍒?Step 4 鐨勪唬鐮佸凡鍚屾鏀瑰ソ锛夈€?   **缁濅笉瑕佹敼 `V1__baseline.sql`**锛歝hecksum 涓€鍙橈紝鎵€鏈夊凡缁忚窇杩?V1 鐨勫簱鍚姩鍗虫姏
   `FlywayValidateException: Migrations have failed validation / Migration checksum mismatch
   for migration version 1`锛堝疄娴嬶細浠呬粎缁?V1 鍔犱竴琛屾敞閲婏紝`MigrationTest` 涓夋潯灏卞叏閮ㄦ姤閿欍€?   涓婁笅鏂囨牴鏈捣涓嶆潵锛夈€傝繖灏辨槸銆屽凡鍙戝竷鐨勮縼绉讳笉鍙彉銆嶈繖鏉＄邯寰嬬殑鐢辨潵鈥斺€旀墍浠?   `DROP TABLE schema_probe` 鏀惧湪 V2 鐨勭涓€琛岋紝鑰屼笉鏄洖澶村幓鍒?V1 閲岄偅涓よ銆?
4. **澶囦唤 / 瀵煎嚭鏂囨。锛圱ask 9 璧凤級蹇呴』鍐欐槑**锛氬簲鐢ㄨ繍琛屾椂澶嶅埗鏁版嵁搴撹杩?`-wal` / `-shm`
   涓€璧锋嫹锛涘叧鎺夊簲鐢ㄥ啀鎷峰垯鍙渶 `change.db`銆傚紑浜?WAL 涔嬪悗锛屾渶鏂版彁浜ゅ彲鑳借繕韬哄湪 `-wal` 閲岋紝
   鍙嫹 `.db` 浼氬緱鍒颁竴涓€屽皯浜嗘渶鍚庡嚑娆¤缁冭褰曘€嶇殑搴撱€?   锛堝疄娴嬶細搴旂敤姝ｅ父鍏抽棴鍚?`-wal` / `-shm` 宸茶 checkpoint 骞跺垹闄わ紝鏁版嵁鐩綍閲屽彧鍓?`change.db`锛?   浣嗗穿婧冩垨寮烘潃涔嬪悗瀹冧滑浼氱暀涓嬫潵锛屾墍浠ャ€屽叧鎺夊啀鎷枫€嶅繀椤绘槸**姝ｅ父閫€鍑?*锛屼笉鑳芥槸銆岃繘绋嬫病浜嗗氨绠椼€嶃€傦級

5. **纭紪鐮佹椂闂存埑瑕佸甫娉ㄩ噴**璇存槑銆屾湰鍦版椂闂达紝鏃犳椂鍖哄悗缂€锛屼笌鍏ㄥ眬绾﹀畾涓€鑷淬€嶏紝鍚﹀垯涓嬩竴涓汉浼?   浠ヤ负鏄?UTC锛屾垨鑰呬互涓烘槸绗旇銆俈1 閲岀殑 `'2026-10-14T00:00:00'` 姝ｆ槸杩欑鏃堕棿鎴斥€斺€?   **浣嗗畠宸插彂甯冿紝涓嶈涓轰簡琛ユ敞閲婂幓鏀瑰畠**锛堢悊鐢辫绾﹀畾 3锛夛紱杩欐潯浠?V2 璧风敓鏁堛€?
**鍐?V2 / V3 / V4 鏃惰鐓ф妱 V1 鐨勶細**

- 鏂囦欢澶存敞閲婂啓銆?*涓轰粈涔堟湁杩欎唤鏂囦欢**銆嶏紝鑰屼笉鏄杩颁笅闈㈢殑 SQL锛?- 鍒椾竴寰嬫樉寮?`NOT NULL`锛圫QLite 榛樿銆屽彲涓虹┖銆嶏紝婕忓啓灏辨槸涓€涓潤榛樼殑鍙┖鍒楋級锛?- 涓婚敭鐢?`INTEGER PRIMARY KEY AUTOINCREMENT`锛?- 鏃堕棿鎴崇敤 `TEXT` + ISO-8601锛圫QLite 娌℃湁鍘熺敓鏃ユ湡绫诲瀷锛夛紱
- 涓€浠借縼绉诲彧鎵挎媴涓€涓煙锛堣缁?/ 楗 / 杈呭姪锛夛紝鍒妸涓変釜鍩熺殑鏀瑰姩濉炶繘鍚屼竴涓増鏈彿銆?
**涓嶈鐓ф妱鐨勶細**

- `CREATE TABLE IF NOT EXISTS` 鈥斺€?鐞嗙敱瑙佷笂闈㈢ 2 鏉°€?
### 杩涘叆 Task 4 鍓嶅繀椤婚獙璇侊細8 绉嶇牬鍧忛兘瑕佽兘璁╂祴璇曞彉绾?
> 浠ヤ笅鍐呭鏉ヨ嚜 Task 3 鐨?*浠ｇ爜璐ㄩ噺瀹℃煡**銆傚鏌ヨ€呭湪褰撴椂鐨勫疄鐜颁笂鍋氫簡 8 澶勭牬鍧忥紝
> 濂椾欢**渚濈劧 12/12 鍏ㄧ豢**锛涗節鏉′慨澶嶅仛瀹屼箣鍚庯紝姣忎竴绉嶇牬鍧忛兘蹇呴』璁╂煇鏉℃祴璇曞彉绾€?> **杩涘叆 Task 4 涔嬪墠璇锋妸杩?8 鏉￠€愪竴鍋氫竴閬嶅苟纭鍙樼孩**锛堟瘡鏉￠兘瑕佸厛鍒?`target/`
> 鍐嶈窇 `mvn -B test`锛歚src/main/resources` 涓嬬殑鏀瑰姩鍙兘娌¤澶嶅埗杩?`target/classes`锛?> 涓嶅垹灏变細璇诲埌涓婁竴杞殑鏃ц剼鏈級銆傝繖涓€姝ヤ笉鑳界渷锛氬畠鏄€岃繖涓€鐗?schema 鐪熺殑鏈夊畧鍗€嶇殑
> 鍞竴璇佹嵁锛岃€岃縼绉讳竴鏃﹀彂甯冨氨涓嶅彲鍐嶆敼銆?
| # | 鐮村潖 | 褰撴椂瀹炴祴鍚庢灉 | 鐜板湪鐢辫皝鎷?|
|---|---|---|---|
| 1 | `muscle_group.name_zh` 鍘绘帀 `NOT NULL` | NULL 涓枃鍚嶅彲鍏ュ簱 | `SchemaTest.not_null_snapshot_is_exact_for_all_fifteen_tables` + `constraint_snapshot_key_ddl_clauses` |
| 2 | `exercise` 澶氫竴鍒?| 鏃犱汉瀵熻 | `SchemaTest.column_snapshot_is_exact_for_all_fifteen_tables` |
| 3 | 鍒犳帀閮ㄥ垎鍞竴绱㈠紩 `ux_exercise_source` | 鍚屽悕 `source_id` 鍐欏叆 4 琛?| `SchemaTest.index_snapshot_names_and_uniqueness` + `constraint_snapshot_key_ddl_clauses`锛坄WHERE source_id IS NOT NULL`锛?|
| 4 | 鍒犳帀 `workout_set.set_type` 鐨?CHECK | `set_type='total_nonsense'` 鍏ュ簱 | `SchemaTest.constraint_snapshot_key_ddl_clauses` |
| 5 | 鍒犳帀 `session_exercise.session_id` 鐨勫閿?| 绾ц仈鍒犻櫎澶辨晥 | `SchemaTest.constraint_snapshot_key_ddl_clauses` |
| 6 | `food.kcal_per100` 鍘绘帀 `NOT NULL` 涓庨粯璁ゅ€?| 钀ュ吇鍊煎彲绌?| `SchemaTest.not_null_snapshot_...` + `constraint_snapshot_key_ddl_clauses`锛坄NOT NULL DEFAULT 0`锛?|
| 7 | 鍒犳帀 `chat_message.session_id` 鐨勫閿?| 绾ц仈鍒犻櫎澶辨晥 | `SchemaTest.constraint_snapshot_key_ddl_clauses` |
| 8 | 鍒犳帀 `change_log.origin`/`action` 鐨?CHECK | `origin='martian'` 鍏ュ簱 | `SchemaTest.constraint_snapshot_key_ddl_clauses` |

**缁撹锛歴chema 杩欎竴灞傚湪姝や箣鍓嶆病鏈変换浣曞畧鍗€?* Flyway 鐨?checksum 鍙湪銆屽凡缁忚窇杩囪鐗堟湰鐨?搴撱€嶄笂鎶ヨ锛屾湰鍦板垹鎺?`target` 涔嬪悗姣忔閮芥槸鍏ㄦ柊鐨勫簱锛宑hecksum 鏍￠獙鏍规湰涓嶄細琚Е鍙戔€斺€?鎵€浠?`MigrationTest` 閲屽彟鍔犱簡涓€鏉?`published_migrations_are_byte_frozen`锛?鎶?V1鈥揤4 鐨?checksum 閽夋鍦ㄦ祴璇曢噷銆?
### 鏈疆瀵?schema 鐨勪節鏉′慨澶嶏紙宸茶惤鍦帮級

1. **閲嶆帓 V3/V4锛岃 `meal.photo_id` 鑳藉姞澶栭敭**锛歏3 寤?`photo` + `food` + `food_portion`
   锛堢収鐗囪椁愬紩鐢紝蹇呴』鍦ㄥ墠锛夛紝V4 寤?`meal` + `meal_item` + `body_metric` + `chat_session`
   + `chat_message` + `change_log`銆俙photo_id INTEGER REFERENCES photo(id) ON DELETE SET NULL`
   鈥斺€旂収鐗囨槸椁愮殑鍙€夊厓鏁版嵁锛氱敤 `SET NULL` 鑰屼笉鏄嫆缁濓紝鏄洜涓哄垹涓€寮犵収鐗囦笉璇ヨ涓€椤块キ鎸′綇锛?   鐢?`SET NULL` 鑰屼笉鏄?CASCADE锛屾槸鍥犱负鍒犵収鐗囩粷涓嶈兘杩炲甫鍒犳帀鐢ㄩ璁板綍銆?2. **鍥涗釜绾棩鏈熷垪鍔犲舰鐘剁害鏉?*锛歚workout_session.session_date`銆乣meal.meal_date`銆?   `body_metric.measured_on`銆乣photo.taken_on` 涓€寰?   `CHECK (<鍒? GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')`銆?   涓嶈繖涔堝仛鐨勮瘽锛屼竴鏃︽湁浠ｇ爜鍐欒繘瀹屾暣鏃堕棿鎴筹紙DTO 鐢?`LocalDateTime`銆佸墠绔紶 `toISOString()`锛夛紝
   `WHERE session_date = '2026-10-14'` 瀵?`'2026-10-14T03:22:11'` **杩斿洖 0 琛?*锛堢瓑鍊兼煡璇㈤潤榛樺け鏁堬級锛?   鏀圭敤 `date(session_date)` 褰掍竴鍖栧垯鐢ㄤ笉涓?`ix_session_date`锛岀储寮曠櫧寤恒€?   `started_at` / `ended_at` / `eaten_at` / `created_at` / `changed_at` 鏄?*鐪熸椂闂存埑**锛?   鍒绘剰涓嶅姞杩欎釜绾︽潫銆?3. **`change_log` 琛?`CHECK (action <> 'update' OR before_json IS NOT NULL)`**锛?   銆屼竴閿洖閫€銆嶆槸绗竴闃舵鐨勬牳蹇冨畨鍏ㄦ満鍒讹紝娌℃湁 before 蹇収灏辨棤浠庢仮澶嶏紝鑰?UI 鍙湅 `action`銆?4. **`workout_set` 琛ユ暟鍊煎悎娉曟€?CHECK**锛坄weight`/`target_weight`/`reps` 鈮?0銆?   `duration_seconds` > 0锛夈€?*鍒绘剰涓嶇粰杩欎笁鍒楀姞 `NOT NULL`**锛氫笁绌虹殑琛岃〃杈俱€岃繖涓€缁勮繕娌″仛/娌¤銆嶏紝
   璁粌涓殑澧為噺淇濆瓨闇€瑕佸畠銆傘€宍completed=1` 鍗翠笁绌恒€嶈繖绫昏法鍒楄涔夌敱搴旂敤灞備繚璇併€?5. **绱㈠紩鍛藉悕缁熶竴涓?`ix_`/`ux_` + 琛ㄥ悕鍏ㄧО + 鍒楀悕**锛岃鍒欏啓杩?V2 鏂囦欢澶淬€傛敼鍚嶅墠锛?   `ix_sessex_session`銆乣ix_set_sessex`銆乣ix_exercise_muscle_muscle`銆乣ix_chat_msg_session`銆?   `ix_changelog_target`銆乣ix_changelog_time`銆乣ix_metric_type_date`銆?6. **`set_index` 璇箟鍐荤粨 + 鍞竴绱㈠紩**锛氭瘡涓瓙缁勫悇鍗犱竴涓?`set_index`锛岀敤 `set_type` 鍖哄垎鎬ц川
   锛堥€掑噺缁勭殑涓夋鏄?`set_index` 0/1/2 + `set_type='dropset'`锛屼笉鏄笁娈靛叡鐢ㄥ悓涓€涓彿锛夈€?   鍔?`ux_workout_set_session_exercise_index` 涓?`ux_session_exercise_session_order`銆?7. **`exercise.source_id` 鍔犲懡鍚嶇┖闂寸害瀹?*锛堝舰濡?`'free-exercise-db:barbell-bench-press'`锛?   **绌轰覆涓€寰嬭涓烘棤鏁堬紝蹇呴』鍐?NULL**鈥斺€旂┖涓蹭笉绠?NULL锛屽鍏ュ櫒鍐?`''` 浼氳绗簩娆″鍏ユ暣鎵瑰け璐ワ級锛?   骞跺姞 `ux_exercise_name_en`銆傚悓鍚嶅姩浣滀簨鍚庡悎骞惰鍐欒縼绉?+ 閲嶆寕 `session_exercise.exercise_id`
   + 閲嶇畻鎵€鏈夎鐢熸寚鏍囷紝鎵€浠?*鍙兘鐜板湪瀹?*銆?   **`food.name_zh` 鍒绘剰涓嶅姞鍞竴绾︽潫**锛氫腑寮忛鐗╁悓鍚嶄笉鍚屽仛娉曠殑鏉＄洰鐪熷疄瀛樺湪锛?   鍘婚噸涓庢姤鍛婄敱瀵煎叆鍣ㄨ礋璐ｃ€?8. **淇帀 `food.source` 娉ㄩ噴閲岀殑閿欒鏋氫妇**锛氬師鏉ュ啓鐨勬槸 `'free-exercise-db'`鈥斺€旈偅鏄姩浣滄暟鎹泦锛?   涓嶆槸椋熺墿搴擄紙浠?V2 澶嶅埗绮樿创鐣欎笅鐨勯敊璇級銆傛敼鎴?`'manual' | 'cn-food-table' | 'agent' | 'takeout'`
   骞惰ˉ涓?CHECK銆?9. **琛ョ湡姝ｇ殑 schema 瀹堝崼**锛歚SchemaTest` 鍔犲垪绾?/ NOT NULL / 绱㈠紩绾?/ 绾︽潫绾у洓灞傜粨鏋勫揩鐓э紝
   `MigrationTest` 鍔?checksum 鍐荤粨涓庛€屽唴瀛樺簱鎵ц V1銆嶇殑鍒楃骇鏂█銆?
---

- [x] **Step 1: 鍐欒缁冨煙杩佺Щ V2**

> 瀹為檯鏂囦欢绗竴琛屾槸 `DROP TABLE schema_probe;`锛堜笅闈唬鐮佸潡鐨勫ご閮ㄦ敞閲婅В閲婁簡鐞嗙敱锛夛紝
> 鍓帀浜嗗畠鍚庨潰鐨勫唴瀹癸紝杩欓噷鐓ф妱鏃?*涓嶈婕忔帀閭ｄ竴琛?*銆?
```sql
-- V2: 璁粌鍩?6 寮犺〃銆?--
-- 绱㈠紩鍛藉悕锛氬敮涓€绱㈠紩 ux_<琛ㄥ悕鍏ㄧО>_<鍒?锛涙櫘閫氱储寮?ix_<琛ㄥ悕鍏ㄧО>_<鍒?锛涜〃鍚嶄笉缂╁啓銆?--
-- 涓轰粈涔堣繖鏉¤鍒欒鍐欐鍦ㄦ枃浠跺ご锛歋QLite 缁欑储寮曞彟璧蜂竴涓悕瀛楀啀寤轰竴娆?*涓嶄細鎶ラ敊**锛?-- 鍙細闈欓粯澶氬嚭涓€涓姛鑳介噸澶嶇殑绱㈠紩锛堝啓鍙樻參銆佸崰鐢ㄧ炕鍊嶏紝鑰屾病鏈変换浣曟彁绀猴級銆傝〃鍚嶇缉鍐欏湪
-- 杩欓噷灏ゅ叾鍗遍櫓鈥斺€擿ix_sessex_session` 瀵逛笅涓€涓汉鏉ヨ涓嶆樉鐒剁瓑浜?`session_exercise`锛?-- 浜庢槸浠栧緢鍙兘鍐嶅缓涓€涓?`ix_session_exercise_session`锛岃€屼袱鑰呭苟瀛樻椂璋佷篃涓嶄細鍛婅瘔浠栥€?-- 涓€鏉″彲鏈烘鐓ф妱鐨勮鍒欐瘮涓€鍙ャ€岃淇濇寔涓€鑷淬€嶆湁鐢ㄥ緱澶氥€?--
-- 涓轰粈涔堟湁杩欎唤鏂囦欢锛氥€屼竴娆¤缁?= 澶氬姩浣?脳 澶氱粍銆嶆槸杩欎釜搴旂敤鐨勬牳蹇冧簨瀹烇紝涓€涓姩浣滅殑
-- 鍚屼竴缁勯噷鏃㈣鐭ラ亾銆屾垜浠墦绠楀仛澶氬皯銆嶏紝涔熻鐭ラ亾銆屽疄闄呭仛浜嗗灏戙€嶃€傛妸杩欎袱浠朵簨鏀捐繘涓嶅悓鐨勮〃
-- 浼氳銆屼粖澶╃粌寰楁瘮璁″垝閲嶈繕鏄交銆嶅彉鎴愪竴娆¤法琛ㄥ榻愶紝鍥犳杩欓噷鍒绘剰寤烘垚涓夊眰宓屽
-- 锛坵orkout_session 鈫?session_exercise 鈫?workout_set锛夛紝骞惰鐩爣鍊间笌瀹為檯鍊煎悓澶勪竴琛屻€?-- 缁勪笌缁勪箣闂寸殑宸紓鐢?set_type 鏋氫妇琛ㄨ揪锛岃€屼笉鏄潬銆岀鍑犵粍銆嶇殑搴忓彿鍘荤寽銆?--
-- 绗竴琛屽厛娓呮帀 V1 鐨勬帰閽堣〃锛氬畠鏄熀绾胯縼绉荤殑鍘嗗彶閬楃暀锛屾病鏈変笟鍔″惈涔夛紝涓嶈姘镐箙鐣欏湪
-- 姣忎釜鐢ㄦ埛鐨勫簱閲屻€傚垹鍦ㄨ繖閲岃€屼笉鏄洖澶村幓鏀?V1鈥斺€擵1 鐨?checksum 宸茬粡鍙戝竷锛屼竴鍔ㄦ墍鏈?-- 璺戣繃瀹冪殑搴撳惎鍔ㄥ嵆鎶?FlywayValidateException銆?DROP TABLE schema_probe;

CREATE TABLE muscle_group (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name_zh         TEXT    NOT NULL,
    name_en         TEXT    NOT NULL,
    body_part       TEXT    NOT NULL,      -- 閫楀彿鍒嗛殧鐨勫鍊硷紙濡?"left,right"锛夛紝闇€瑕佹椂鍐嶆媶琛?    region_ids      TEXT,                  -- 浜轰綋鍥惧尯鍩?id锛岄€楀彿鍒嗛殧
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
    -- source_id锛氬閮ㄦ暟鎹泦鐨勪富閿紝蹇呴』甯﹀懡鍚嶇┖闂村墠缂€锛屽舰濡?    -- 'free-exercise-db:barbell-bench-press'銆傛墜宸ュ綍鍏ョ殑鍔ㄤ綔蹇呴』鍐?NULL锛?    -- **绌轰覆涓嶇畻 NULL**鈥斺€擿source_id` 涓婄殑閮ㄥ垎鍞竴绱㈠紩鍙湅 NULL锛?    -- 鍐?'' 鐨勫姩浣滅浜屾瀵煎叆灏辨挒 UNIQUE constraint failed锛屾暣鎵瑰洖婊氾紱
    -- 鑰屼笉甯﹀墠缂€鐨勮８ id 浼氬湪鎺ュ叆绗簩涓暟鎹泦鏃堕潤榛樺啿绐侊紙涓や釜鏁版嵁闆嗛兘鍙?'curl'锛夈€?    source_id     TEXT,
    note          TEXT,
    archived      INTEGER NOT NULL DEFAULT 0
);
-- 閮ㄥ垎鍞竴绱㈠紩锛氬彧绾︽潫銆屾湁澶栭儴鏉ユ簮銆嶇殑鍔ㄤ綔锛屾墜宸ュ姩浣滐紙source_id IS NULL锛夊彲浠ユ棤闄愰噸澶嶃€?CREATE UNIQUE INDEX ux_exercise_source ON exercise(source_id) WHERE source_id IS NOT NULL;
-- 鍚屽悕鍔ㄤ綔蹇呴』鎸″湪搴撳锛岃€屼笖**鍙兘鐜板湪鎸?*锛氫竴鏃﹀簱閲屽嚭鐜颁袱琛屽悓鍚嶏紝鍞竴绱㈠紩灏卞缓涓嶄笂锛?-- 浜嬪悗鍚堝苟瑕佸啓杩佺Щ + 閲嶆寕 session_exercise.exercise_id + 閲嶇畻鎵€鏈夎鐢熸寚鏍囥€傝€屽湪姝や箣鍓嶏紝
-- 閲嶅琛屼細瀹夐潤鍦版瘉鎺夋瘡涓€涓粺璁★細杩涙鏇茬嚎鍒嗚鎴愪袱鏉＄嚎銆佸懆瀹归噺缈诲€嶃€丳R 琚媶鎴愪袱涓€?CREATE UNIQUE INDEX ux_exercise_name_en ON exercise(name_en);
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
    -- 绾棩鏈燂紝褰㈢姸閿佹涓?YYYY-MM-DD锛堝彟瑙?meal.meal_date / body_metric.measured_on /
    -- photo.taken_on 鐨勫悓娆?CHECK锛夈€傝繖涓€鏉℃嫤鐨勪笉鏄剰鏁版嵁锛岃€屾槸**闈欓粯澶辨晥**锛?    -- 涓€鏃︽湁浠ｇ爜鍐欒繘瀹屾暣鏃堕棿鎴筹紙DTO 鐢?LocalDateTime銆佸墠绔紶浜?toISOString()锛夛紝
    -- `WHERE session_date = '2026-10-14'` 瀵?'2026-10-14T03:22:11' 杩斿洖 0 琛屸€斺€?    -- 鏌ヨ娌℃姤閿欙紝鍙槸姘歌繙鏌ヤ笉鍒帮紱鏀圭敤 date(session_date) 褰掍竴鍖栧垯鐢ㄤ笉涓?ix_session_date锛?    -- 绱㈠紩鐧藉缓锛涙寜鏃ユ湡鍒嗙粍鐨勭粺璁¤繕浼氭妸鍚屼竴澶╂媶鎴愪袱琛屻€?    session_date           TEXT    NOT NULL CHECK (session_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    -- started_at / ended_at 鏄?*鐪熸椂闂存埑**锛屾湰鏉ュ氨璇ュ惈鏃堕棿锛屼笉瑕佸姞鏃ユ湡褰㈢姸 CHECK銆?    started_at             TEXT,
    ended_at               TEXT,
    title                  TEXT,
    perceived_state        TEXT,
    note                   TEXT,
    source_routine_day_id  INTEGER             -- 涓哄皢鏉ャ€岃鍒掗┍鍔ㄣ€嶉鐣欙紝绗竴闃舵涓嶅啓
);
CREATE INDEX ix_session_date ON workout_session(session_date);

CREATE TABLE session_exercise (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    INTEGER NOT NULL REFERENCES workout_session(id) ON DELETE CASCADE,
    exercise_id   INTEGER NOT NULL REFERENCES exercise(id),
    order_index   INTEGER NOT NULL,
    note          TEXT,
    superset_group TEXT                        -- 鍚屽€煎嵆瓒呯骇缁勶紱NULL 琛ㄧず涓嶅弬涓庤秴绾х粍
);
CREATE INDEX ix_session_exercise_session ON session_exercise(session_id);
-- order_index 鏄€岃繖涓姩浣滃湪鏈璁粌閲屾帓绗嚑銆嶏紝鍚屼竴娆¤缁冮噷涓よ鍚屽簭娌℃湁鎰忎箟銆?CREATE UNIQUE INDEX ux_session_exercise_session_order
    ON session_exercise(session_id, order_index);

CREATE TABLE workout_set (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    session_exercise_id INTEGER NOT NULL REFERENCES session_exercise(id) ON DELETE CASCADE,
    -- set_index 鐨勮涔夛紙鍐荤粨锛屼笉瑕佸啀瑙ｉ噴鎴愬埆鐨勬剰鎬濓級锛?    -- **姣忎釜瀛愮粍鍚勫崰涓€涓?set_index锛岀敤 set_type 鍖哄垎鎬ц川銆?*
    -- 閫掑噺缁勭殑涓夋鏄?set_index 0/1/2 + set_type 'dropset'锛屽法闆嗙粍鍚岀悊锛?    -- 鑰屼笉鏄笁娈靛叡鐢ㄥ悓涓€涓?set_index銆傜悊鐢憋細set_index 鏄€屽甫鍑轰笂娆￠噸閲忋€嶇殑瀵归綈閿紝
    -- 涓よ鍚屽彿浼氳瀵归綈闅忔満鍙栧埌鍏朵腑涓€琛屸€斺€旇€屽彇閿欑殑閭ｆ涓嶄細鏈変换浣曟姤閿欍€?    set_index           INTEGER NOT NULL,
    set_type            TEXT    NOT NULL DEFAULT 'normal'
                        CHECK (set_type IN ('normal','warmup','dropset','myo','partial',
                                            'forced','tut','iso','jump')),
    -- weight / repetitions / duration_seconds 鍒绘剰**鍙负绌?*锛氫笁绌虹殑琛屾槸涓€涓湡瀹炵姸鎬?    -- 锛堛€岃繖涓€缁勮繕娌″仛/娌¤銆嶏級锛岃缁冧腑鐣岄潰闈犲閲忎繚瀛橀€愬瓧娈佃ˉ涓婏紝鍔?NOT NULL 浼氶€煎鎴风缂栭€犳暟瀛椼€?    -- 绌哄€兼斁琛屻€佹湁鍊煎繀椤诲悎娉曪紝鎵€浠ョ害鏉熷啓鎴?`IS NULL OR ...`銆?    weight              REAL,
    repetitions         INTEGER,
    duration_seconds    INTEGER,               -- 璁℃椂鍔ㄤ綔锛堝钩鏉挎敮鎾戠瓑锛夛紝涓?repetitions 浜岄€変竴
    per_side            INTEGER NOT NULL DEFAULT 0,
    rir                 REAL,
    rest_seconds        INTEGER,
    completed           INTEGER NOT NULL DEFAULT 0,
    target_weight       REAL,
    target_reps         INTEGER,
    -- 杩欓噷鍙繚璇佸崟鍒楁暟鍊煎悎娉曘€傘€宑ompleted=1 鍗翠笁绌恒€嶃€寃eight 涓?duration_seconds 鍚屾椂鏈夊€笺€?    -- 杩欑被**璺ㄥ垪璇箟**鐢卞簲鐢ㄥ眰淇濊瘉锛屾暟鎹簱灞備笉鎷︺€?    CHECK (weight IS NULL OR weight >= 0),
    CHECK (repetitions IS NULL OR repetitions >= 0),
    CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    CHECK (target_weight IS NULL OR target_weight >= 0),
    CHECK (target_reps IS NULL OR target_reps >= 0)
);
CREATE INDEX ix_workout_set_session_exercise ON workout_set(session_exercise_id);
-- 鍚屼竴涓姩浣滀笅 set_index 蹇呴』鍞竴鈥斺€斿惁鍒欎笂闈㈤偅娈佃涔夊喕缁撳彧鏄竴鍙ユ敞閲娿€?CREATE UNIQUE INDEX ux_workout_set_session_exercise_index
    ON workout_set(session_exercise_id, set_index);
```

- [x] **Step 2: 鍐欓鐗╁簱涓庣収鐗囪縼绉?V3**

鐓х墖浠庡師鏉ョ殑 V4 鎻愬埌杩欓噷锛歚meal.photo_id` 瑕佹寚鍚?`photo(id)`锛屽閿姹傝寮曠敤鐨勮〃鍏堝瓨鍦ㄣ€?
```sql
-- V3: 椋熺墿搴撲笌鐓х墖 3 寮犺〃锛坒ood / food_portion / photo锛夈€?--
-- 涓轰粈涔堟湁杩欎唤鏂囦欢锛氫竴鏉￠ギ椋熻褰曡鍚屾椂鍥炵瓟銆屽悆浜嗕粈涔堛€嶅拰銆屽悆浜嗗灏戙€嶏紝鑰岃繖涓や釜闂鐨?-- 鍒嗚鲸鐜囧畬鍏ㄤ笉鍚屸€斺€旇惀鍏诲€煎鏌愪釜椋熺墿鏄浐瀹氬睘鎬э紝浠介噺鍒欐瘡娆￠兘涓嶄竴鏍枫€傛墍浠ヨ惀鍏诲€煎彧鍦?-- food 琛ㄩ噷鎸夋瘡 100 鍏嬪瓨涓€浠斤紝meal_item 鍙浠介噺涓庢崲绠楀厠鏁帮紝鐑噺鐢辫鍙栨椂鐜扮畻銆?--
-- 涓轰粈涔?photo 鍦ㄨ繖浠借縼绉昏€屼笉鏄?V4锛歮eal.photo_id 瑕佹寚鍚?photo(id)锛屽閿姹傝寮曠敤
-- 鐨勮〃鍏堝瓨鍦ㄣ€傛墍浠ユ寜渚濊禆椤哄簭閲嶆帓鈥斺€旀湰鏂囦欢寤洪鐗╁簱涓庣収鐗囷紝V4 寤洪璁板綍锛堜緷璧?photo锛?-- 涓庡叾浣欒緟鍔╄〃銆備竴浠借縼绉讳粛鐒跺彧鎵挎媴涓€涓煙锛屽彧鏄妸銆岀収鐗囥€嶅綊鍒颁簡瀹冭寮曠敤鐨勯偅涓€渚с€?--
-- 閾佸緥锛氳惀鍏讳竴寰嬫寜姣?100 鍏嬪瓨锛涙潯鐩彧瀛樹唤閲忎笌鎹㈢畻鍏嬫暟锛岀粷涓嶅瓨缁濆鐑噺銆?
CREATE TABLE food (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    -- name_zh 鍒绘剰**涓嶅姞鍞竴绾︽潫**锛氫腑寮忛鐗╅噷銆屽悓鍚嶄笉鍚屽仛娉曘€嶇殑鏉＄洰鐪熷疄瀛樺湪
    -- 锛堝悓涓€涓彍鍚嶅搴斿畬鍏ㄤ笉鍚岀殑閰嶆枡涓庣儹閲忥級銆傚悓鍚嶆潯鐩敱瀵煎叆鍣ㄨ礋璐ｅ幓閲嶄笌鎶ュ憡锛?    -- 涓嶅湪鏁版嵁搴撳眰寮哄埗鍞竴鈥斺€斿湪杩欓噷鍞竴鍖栦細閫煎鍏ュ櫒涓㈡帀鐪熷疄瀛樺湪鐨勬潯鐩€?    name_zh      TEXT    NOT NULL,
    name_en      TEXT,
    -- 椋熺墿鏉ユ簮鏋氫妇銆傛敞鎰忚繖閲?*娌℃湁** free-exercise-db锛氶偅鏄姩浣滄暟鎹泦锛屼笉鏄鐗╁簱
    -- 锛圴2 鐨?exercise.source_id 鎵嶆槸瀹冪殑鍛藉悕绌洪棿锛夈€傝繖鏉℃敞閲婃浘缁忚鎶勪簡鍔ㄤ綔閭ｈ竟鐨勬灇涓俱€?    source       TEXT    NOT NULL
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
    unit_name TEXT    NOT NULL,        -- 纰?/ 涓?/ 鍕?/ 浠?    grams     REAL    NOT NULL CHECK (grams > 0)
);
CREATE UNIQUE INDEX ux_food_portion ON food_portion(food_id, unit_name);

-- 鐓х墖鍏堝缓鍦ㄨ繖閲岋紙琚?meal.photo_id 寮曠敤锛夛紝鏈〃娌℃湁浠讳綍鎸囧悜涓氬姟琛ㄧ殑寮曠敤銆?CREATE TABLE photo (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 绾棩鏈燂紝褰㈢姸閿佹涓?YYYY-MM-DD銆傜悊鐢辫 V2 閲?workout_session.session_date 涓婃柟鐨勯暱娉ㄩ噴銆?    taken_on   TEXT    NOT NULL CHECK (taken_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    rel_path   TEXT    NOT NULL,       -- 鍙瓨鐩稿璺緞锛屾牴鐩綍鐢遍厤缃喅瀹?    sha256     TEXT    NOT NULL,
    width      INTEGER,
    height     INTEGER,
    kind       TEXT    NOT NULL CHECK (kind IN ('food','body','other')),
    note       TEXT
);
CREATE UNIQUE INDEX ux_photo_sha ON photo(sha256);
CREATE INDEX ix_photo_kind_date ON photo(kind, taken_on);
```

- [x] **Step 3: 鍐欓璁板綍涓庡叾浣欒緟鍔╄縼绉?V4**

`meal` 浠庡師鏉ョ殑 V3 绉诲埌杩欓噷锛屼笌瀹冧緷璧栫殑 `photo` 鍒嗗紑鍦ㄤ袱浠借縼绉婚噷锛?`body_metric` / `chat_session` / `chat_message` / `change_log` 浣嶇疆涓嶅彉銆?
```sql
-- V4: 椁愯褰曚笌鍏朵綑杈呭姪琛?6 寮犺〃锛坢eal / meal_item / body_metric / chat_session /
--     chat_message / change_log锛夈€?--
-- 涓轰粈涔堟湁杩欎唤鏂囦欢锛氶璁板綍銆佽韩浣撴寚鏍囥€佸璇濊褰曚笌鍙樻洿鏃ュ織閮戒笉鏄缁冩湰韬殑鏁版嵁锛?-- 浣嗗畠浠悇鑷鍒殑鍩熷紩鐢紙meal.photo_id 鎸囧悜 V3 鐨?photo锛屾挙閿€涓€娆¤缁冩敼鍔ㄦ椂瑕佸洖鏀?-- change_log锛夛紝鎵€浠ュ繀椤讳笌涓氬姟琛ㄥ悓搴撱€佸悓浜嬪姟锛岃€屼笉鏄彟涓€涓瓨鍌ㄣ€傝繖浜涜〃褰兼鐨勮€﹀悎
-- 鍙埌杩欓噷涓烘锛屽崟鐙垚涓€浠借縼绉绘槸涓轰簡璁╂棩鍚庣殑鏀瑰姩涓嶄細鐗靛姩 V2/V3 鐨?checksum銆?--
-- 涓轰粈涔?meal 鍦?V4 鑰屼笉鏄?V3锛氬畠鐨?photo_id 澶栭敭鎸囧悜 photo(id)锛岃€岃寮曠敤鐨勮〃蹇呴』鍏堝瓨鍦ㄣ€?-- 浜庢槸鎸変緷璧栭『搴忛噸鎺掞細V3 寤洪鐗╁簱涓庣収鐗囷紝鏈枃浠跺缓渚濊禆 photo 鐨勯璁板綍涓庡叾浣欒緟鍔╄〃銆?
CREATE TABLE meal (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 绾棩鏈燂紝褰㈢姸閿佹涓?YYYY-MM-DD銆傜悊鐢辫 V2 閲?workout_session.session_date 涓婃柟鐨勯暱娉ㄩ噴銆?    meal_date  TEXT    NOT NULL CHECK (meal_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    slot       TEXT    NOT NULL CHECK (slot IN ('breakfast','lunch','dinner','snack')),
    -- eaten_at 鏄?*鐪熸椂闂存埑**锛屾湰鏉ュ氨璇ュ惈鏃堕棿锛屼笉瑕佸姞鏃ユ湡褰㈢姸 CHECK銆?    eaten_at   TEXT,
    note       TEXT,
    -- ON DELETE SET NULL锛堣€屼笉鏄粯璁ょ殑鎷掔粷銆佷篃涓嶆槸 CASCADE锛夋槸鏈夋剰鐨勫彇鑸嶏細
    -- 鐓х墖鏄鐨?*鍙€夊厓鏁版嵁**銆傚垹涓€寮犵収鐗囦笉璇ヨ涓€椤块キ鐨勫瓨鍦ㄦ尅浣忥紙鎷掔粷锛夛紝
    -- 鍒犵収鐗囨洿缁濅笉鑳借繛甯﹀垹鎺夌敤椁愯褰曪紙CASCADE 浼氭妸銆屼粖澶╁悆浜嗕粈涔堛€嶅垹娌★級锛?    -- 鎵€浠ョ収鐗囨病浜嗗氨鎶婅繖涓€鏍兼竻绌猴紝椁愭湰韬暀涓嬨€?    photo_id   INTEGER REFERENCES photo(id) ON DELETE SET NULL
);
CREATE INDEX ix_meal_date ON meal(meal_date);

CREATE TABLE meal_item (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    meal_id      INTEGER NOT NULL REFERENCES meal(id) ON DELETE CASCADE,
    food_id      INTEGER NOT NULL REFERENCES food(id),
    order_index  INTEGER NOT NULL,
    amount       REAL    NOT NULL CHECK (amount > 0),   -- 浠介噺鏁板€?    unit_name    TEXT,                                  -- 鍗曚綅鍚嶏紱NULL 琛ㄧず amount 宸叉槸鍏?    grams        REAL    NOT NULL CHECK (grams > 0),    -- 鎹㈢畻鍚庣殑鍏嬫暟锛屽啓鍏ユ椂绠楀ソ
    origin       TEXT    NOT NULL DEFAULT 'manual'
                 CHECK (origin IN ('manual','photo','agent','takeout'))
    -- 娉ㄦ剰锛氳繖閲屾病鏈?kcal 瀛楁锛屾晠鎰忕殑銆?);
CREATE INDEX ix_meal_item_meal ON meal_item(meal_id);

CREATE TABLE body_metric (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 绾棩鏈燂紝褰㈢姸閿佹涓?YYYY-MM-DD銆傜悊鐢辫 V2 閲?workout_session.session_date 涓婃柟鐨勯暱娉ㄩ噴銆?    measured_on TEXT    NOT NULL CHECK (measured_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
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
    tool_calls    TEXT,                -- JSON 鏁扮粍锛岃褰曟湰娆¤皟鐢ㄤ簡鍝簺宸ュ叿
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
    before_json TEXT,                  -- insert 鏃朵负 NULL
    after_json  TEXT    NOT NULL,
    chat_session_id INTEGER,
    undone      INTEGER NOT NULL DEFAULT 0,
    -- 銆屼竴閿洖閫€銆嶆槸绗竴闃舵鐨勬牳蹇冨畨鍏ㄦ満鍒讹紝鑰屽畠鍞竴鐨勪緷鎹氨鏄?before_json锛?    -- action='update' 鍗存病鏈?before 蹇収锛岀瓑浜庤繖涓€琛屾案杩滃洖涓嶅幓锛屽彲 UI 鍙湅 action
    -- 灏变細鐓у父鐢诲嚭涓€涓€屾挙閿€銆嶆寜閽€斺€旀寜涓嬪幓鎵嶅彂鐜版棤浜嬪彲鎾ゃ€傝璁℃枃妗ｆ槑纭笉鍏佽鍒犻櫎锛?    -- 鎵€浠?update 蹇呯劧瀛樺湪鍙樻洿鍓嶇殑鐘舵€侊紝杩欐潯绾︽潫鏄函鏀剁泭銆?    CHECK (action <> 'update' OR before_json IS NOT NULL)
);
CREATE INDEX ix_change_log_target ON change_log(target_table, target_id);
CREATE INDEX ix_change_log_time ON change_log(changed_at);
```

- [x] **Step 4: 鍐欑粨鏋勬祴璇?*

鍥涘眰蹇収锛岄€愬紶琛ㄦ敹鍙ｏ細**鍒楁竻鍗?+ NOT NULL 娓呭崟**锛堟嫤澶氫竴鍒椼€佸皯涓€鍒椼€丯OT NULL 琚嬁鎺夛級銆?**涓氬姟绱㈠紩娓呭崟**锛堝悕瀛?+ 鍞竴鎬?+ 鏄惁閮ㄥ垎绱㈠紩锛屾嫤绱㈠紩琚垹 / 鏀瑰悕 / 鍞竴鎬ц鍘绘帀锛夈€?**鍏抽敭 DDL 鐗囨**锛堟嫤 CHECK / `REFERENCES ... ON DELETE ...` / DEFAULT 琚垹锛夈€?绾︽潫閭ｅ眰鏂█鐨勬槸 DDL 鍘熸枃鑰屼笉鏄€屽啓鍏ヨ鎷掋€嶏紝鍥犱负鍙湁鍓嶈€呰兘鎸囧嚭**鍝竴鏉?*绾︽潫娌′簡锛?銆屽啓鍏ョ湡鐨勮鎷掋€嶇敱 Task 3 鐨勯獙鏀堕€愭潯瀹炴祴瑕嗙洊銆?
浠ｇ爜鍧楁暣浣撶缉杩涗簡 4 鏍硷紙杩佺Щ鍒拌鍒掓枃妗ｆ椂鐨勬帓鐗堥渶瑕侊級锛岃创鍥炴枃浠舵椂璇峰幓鎺夎繖灞傜缉杩涖€?
> **瀹炴柦鏃跺鏈潡鐨勪慨姝ｏ紙宸茶惤鍦帮級**锛氬師鍐欑殑 `PRAGMA table_info(meal_item)` 鏄啓涓嶅嚭鏉ョ殑鈥斺€?> 瀹冩槸銆屼竴琛屽叚鍒椼€嶇殑琛ㄥ嚱鏁帮紝`queryForList(..., String.class)` 浼氬瀹冩姏
> `IncorrectResultSetColumnCountException`锛坄org.springframework.jdbc` 涓嬬殑绫伙紝涓?> `IncorrectResultSizeDataAccessException` 鏄?*鍏勫紵**鍏崇郴锛宍instanceof` 鍒や笉鍑烘潵锛夛紝
> 浜庢槸 `doesNotContain` 閭ｄ竴琛?*姘歌繙鎵ц涓嶅埌**銆傜粺涓€鏀硅蛋锛?>
> ```java
> SELECT name FROM pragma_table_info('<琛?')
> ```
>
> `PRAGMA index_list` 鍚岀悊锛岃鍐欐垚琛ㄥ嚱鏁板舰寮?`pragma_index_list('<琛?')`锛?> 鍙﹀**涓嶈**鍐?`pragma_index_list_all`鈥斺€斿疄娴嬭鍚嶅瓧涓嶅瓨鍦紙SQLite 3.53.1 鎶?> `no such table: pragma_index_list_all`锛夈€?>
> **鍙︿竴涓繀椤昏浣忕殑鍧?*锛歚sqlite_master.sql` 瀛樼殑鏄?*鍘熸牱鏂囨湰**锛堝垪鏄寜鍒楀榻愮殑锛?> `name_zh         TEXT    NOT NULL`锛夛紝鎷垮榻愬悗鐨勫瓧闈㈤噺鍘?`contains` 蹇呯劧澶辫触銆?> 鎵€浠?DDL 鐗囨鏂█涓€寰嬪厛杩?`collapseWhitespace(...)`锛屾柇瑷€閲屽啓鍗曠┖鏍肩殑鑷劧褰㈠紡銆?
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
     * 鍗佷簲寮犱笟鍔¤〃鐨勭粨鏋勫揩鐓с€?     *
     * 涓轰粈涔堢敤涓€寮犮€屾湡鏈涙竻鍗曘€嶈€屼笉鏄€愪釜鏂█琛ㄥ瓨鍦細閫愪釜鏂█鍙兘鍙戠幇銆屽皯寤轰簡銆嶏紝
     * 鍙戠幇涓嶄簡銆屽寤轰簡銆嶁€斺€斿鍑烘潵鐨勮〃锛堟瘮濡?V1 鐨勬帰閽堣〃娌¤娓呮帀锛変細璁╀笅涓€涓汉浠ヤ负
     * 瀹冩槸妯″瀷鐨勪竴閮ㄥ垎銆俢ontainsExactlyInAnyOrderElementsOf 涓や晶閮芥敹鍙ｃ€?     *
     * 涓轰粈涔堣繖涓€杞琛ュ垪绾?/ 绱㈠紩绾?/ 绾︽潫绾т笁灞傚揩鐓э細Task 3 鐨勪唬鐮佽川閲忓鏌ュ仛浜?8 澶?     * 鐮村潖鈥斺€斿幓鎺?NOT NULL銆佸涓€鍒椼€佸垹鎺夐儴鍒嗗敮涓€绱㈠紩銆佸垹鎺?CHECK銆佸垹鎺夊閿€斺€斿浠?*渚濈劧
     * 12/12 鍏ㄧ豢**銆傚綋鏃跺彧鏈?4 鏉℃柇瑷€锛屼笖閮藉彧闂€屾煇寮犺〃鏌愬嚑鍒楀湪涓嶅湪銆嶏紝瀵圭害鏉熴€?     * 绱㈠紩銆佸彲绌烘€у畬鍏ㄤ笉璁鹃槻銆傝€岃縼绉讳竴鏃﹀彂甯冨氨涓嶅彲鍐嶆敼锛堟敼 V1/V2/V3/V4 鐨勪换浣曚竴涓瓧鑺傞兘浼?     * 璁╄窇杩囧畠鐨勫簱鎶?FlywayValidateException锛夛紝鎵€浠ョ幇鍦ㄨ繖涓€鐗?schema 鐨勫畧鍗彧鑳藉缓鍦ㄦ祴璇曢噷銆?     *
     * 涓夊眰鍚勬嫤涓€绫荤牬鍧忥細
     * - 鍒楀揩鐓э紙鍒楁竻鍗?+ NOT NULL 娓呭崟锛夋嫤銆屽涓€鍒?/ 灏戜竴鍒?/ NOT NULL 琚嬁鎺夈€嶏紱
     * - 绱㈠紩蹇収锛堝悕瀛?+ 鍞竴鎬?+ 鏄惁閮ㄥ垎绱㈠紩锛夋嫤銆岀储寮曡鍒?/ 琚敼鍚?/ 鍞竴鎬ц鍘绘帀銆嶁€斺€?     *   SQLite 瀵广€屽彟璧蜂竴涓悕瀛楀啀寤轰竴娆°€嶄笉鎶ラ敊锛屽彧浼氶潤榛樼暀涓嬩袱涓姛鑳介噸澶嶇殑绱㈠紩锛?     * - 绾︽潫蹇収锛坰qlite_master.sql 閲岀殑鍏抽敭鐗囨锛夋嫤銆孋HECK / REFERENCES ... ON DELETE
     *   CASCADE / DEFAULT 琚垹銆嶁€斺€旇繖浜涘湪 pragma 閲岄棶涓嶅嚭鏉ワ紙pragma 鍙姤澶栭敭鐨勭洰鏍囪〃涓庡姩浣滐紝
     *   鎶ヤ笉鍑?CHECK 鐨勫唴瀹癸級锛屽彧鏈?DDL 鍘熸枃閲屾湁銆?     *
     * 娉ㄦ剰绾︽潫蹇収鏂█鐨勬槸 DDL 鐗囨锛屼笉鏄€屽啓鍏ョ湡鐨勮鎷掋€嶃€備袱鑰呴兘鍊煎緱鏈夛紝浣嗗彧鏈夊墠鑰呰兘
     * 绮剧‘鎸囧嚭銆屽摢涓€鏉＄害鏉熸病浜嗐€嶏紱鍚庤€呭凡缁忓湪楠屾敹閲岄€愭潯瀹炴祴杩囥€?     */
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
            // sqlite_% 鎺掗櫎鐨勬槸 SQLite 鑷繁缁存姢鐨勮〃锛圓UTOINCREMENT 浼氬缓 sqlite_sequence锛夛紱
            // flyway_schema_history 鏄縼绉绘鏋剁殑鍘嗗彶琛紝涓嶅睘浜庝笟鍔℃ā鍨嬨€?            // 杩欓噷鍒绘剰涓嶆帓闄?schema_probe锛氬畠鑻ヨ繕鍦紝杩欐潯鏂█灏辫绾€?            //
            // 灏嗘潵鏂板**鍐呴儴**琛紙FTS5 鍏ㄦ枃妫€绱㈢殑褰卞瓙琛ㄥ *_data / *_idx / *_content /
            // *_docsize / *_config銆乻qlite_stat1 绛夛級鏃讹紝蹇呴』鍚屾鏇存柊鏈柇瑷€鈥斺€斿惁鍒欒繖鏉?            // 銆屽寤轰簡涔熻绾€嶇殑鏀跺彛浼氬厛鎶婄湡姝ｇ殑涓氬姟琛ㄥ彉鍖栨尅浣忥紝璁╀汉璇互涓哄彧鏄奖瀛愯〃鍣煶銆?            List<String> names = jdbc.queryForList(
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
                    .as("V2 鐨勭涓€琛屽簲褰?DROP 鎺?V1 鐨勬帰閽堣〃锛岃瀹冧笉鍐嶇暀鍦ㄧ敤鎴风殑搴撻噷")
                    .isZero();
        }

        @Test
        void meal_item_has_no_stored_calorie_column() {
            // 杩欐潯鏄槻鍥炲綊锛氫竴鏃︽湁浜恒€岄『鎵嬨€嶅姞浜?kcal 鍒楋紝娴嬭瘯绔嬪埢澶辫触銆?            // 璧?pragma_table_info 鑰屼笉鏄?PRAGMA table_info锛氬悗鑰呮槸銆屼竴琛屽鍒椼€嶇殑琛ㄥ嚱鏁帮紝
            // queryForList(..., String.class) 浼氬瀹冩姏 IncorrectResultSetColumnCountException
            // 锛坥rg.springframework.jdbc 涓嬬殑绫伙紝涓?IncorrectResultSizeDataAccessException 鏄?            // 鍏勫紵鍏崇郴锛宨nstanceof 鍒や笉鍑烘潵锛夛紝鏂█灏辨案杩滆蛋涓嶅埌 doesNotContain 閭ｄ竴琛屻€?            assertThat(columns("meal_item")).doesNotContain("kcal", "calories", "energy");
        }

        @Test
        void workout_set_supports_timed_and_per_side() {
            // 鐢?containsExactlyInAnyOrder 鑰屼笉鏄?contains锛歝ontains 瀵广€屽鍑轰竴鍒椼€嶆亽缁匡紝
            // 鑰屻€屽鍑轰竴鍒楁病浜哄療瑙夈€嶆鏄湰杞鏌ュ疄娴嬪彂鐢熺殑鐮村潖涔嬩竴銆?            assertThat(columns("workout_set")).containsExactlyInAnyOrder(
                    "id", "session_exercise_id", "set_index", "set_type", "weight", "repetitions",
                    "duration_seconds", "per_side", "rir", "rest_seconds", "completed",
                    "target_weight", "target_reps");
        }

        // ------------------------------------------------------------------
        // 绗竴灞傦細鍒楃骇蹇収
        // ------------------------------------------------------------------

        /**
         * 鍒楁竻鍗?*閫愯〃銆佹寜澹版槑椤哄簭**绮剧‘鏂█锛屽鍔犳瘡琛ㄧ殑 NOT NULL 鍒楁竻鍗曘€?         *
         * 椤哄簭涔熺畻鍦ㄥ唴鏄湁鎰忕殑锛歱ragma_table_info 鎸夊０鏄庨『搴忚繑鍥烇紝鑰屽垪椤哄簭鏄€屼竴浠借縼绉?         * 寤哄嚭鏉ヤ粈涔堛€嶇殑涓€閮ㄥ垎锛屾彃鍏ュ埌涓棿鐨勬柊鍒楀€煎緱琚湅瑙侊紙瀹冨線寰€鎰忓懗鐫€鏈変汉鍦ㄥ師鍦版敼
         * 涓€浠藉凡鍙戝竷鐨勮縼绉伙級銆?         */
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

            // 鍙屼晶鏀跺彛锛欵XPECTED 涓庤繖閲岀殑閿泦鍚堝繀椤讳竴鑷达紝灏戝啓涓€寮犺〃涔熶細绾€?            assertThat(expectedColumns.keySet())
                    .as("鍒楀揩鐓у繀椤昏鐩栧叏閮ㄥ崄浜斿紶琛?)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED);

            expectedColumns.forEach((table, columns) ->
                    assertThat(columns(table))
                            .as("琛?%s 鐨勫垪娓呭崟锛堝涓€鍒椼€佸皯涓€鍒椼€佹敼鍚嶃€佹崲搴忛兘浼氱孩锛?, table)
                            .containsExactlyElementsOf(columns));
        }

        /**
         * NOT NULL 娓呭崟銆傝繖鏄湰杞鏌ョ 1銆? 鏉＄牬鍧忕殑鍞竴瀹堝崼锛氬幓鎺?         * `muscle_group.name_zh` 鎴?`food.kcal_per100` 鐨?NOT NULL 涔嬪悗锛屽垪娓呭崟绾逛笣涓嶅姩銆?         */
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
                    .as("NOT NULL 蹇収蹇呴』瑕嗙洊鍏ㄩ儴鍗佷簲寮犺〃")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED);

            expectedNotNull.forEach((table, columns) ->
                    assertThat(notNullColumns(table))
                            .as("琛?%s 鐨?NOT NULL 鍒楋紙婕忓啓 NOT NULL 鍦?SQLite 閲岀瓑浜庨潤榛樺彲绌猴級", table)
                            .containsExactlyElementsOf(columns));
        }

        // ------------------------------------------------------------------
        // 绗簩灞傦細绱㈠紩绾у揩鐓?        // ------------------------------------------------------------------

        /**
         * 涓氬姟绱㈠紩鐨勫畬鏁存竻鍗曪紙鍚嶅瓧 + 鍞竴鎬?+ 鏄惁閮ㄥ垎绱㈠紩锛夈€俿qlite_ 寮€澶寸殑鑷姩绱㈠紩
         * 锛圲NIQUE 绾︽潫 / 澶嶅悎涓婚敭浜х敓鐨勶級涓嶇畻涓氬姟绱㈠紩锛屼笉杩涜繖寮犺〃銆?         *
         * 鍛藉悕瑙勫垯锛堜笌 V2 鏂囦欢澶村悓涓€鍙ワ級锛氬敮涓€绱㈠紩 `ux_<琛ㄥ悕鍏ㄧО>_<鍒?`锛屾櫘閫氱储寮?         * `ix_<琛ㄥ悕鍏ㄧО>_<鍒?`锛岃〃鍚嶄笉缂╁啓銆傝繖閲屾妸鍚嶅瓧涔熼拤姝伙紝鏄洜涓?SQLite 鍏佽
         * 鍚屽悕绱㈠紩浠ュ鐨勬柊鍚嶅瓧闈欓粯閲嶅缓鈥斺€斾袱涓姛鑳介噸澶嶇殑绱㈠紩涓嶄細鎶ラ敊锛屽彧浼氭倓鎮勫彉鎱€?         */
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
                    .as("涓氬姟绱㈠紩娓呭崟锛堝悕瀛?+ 鍞竴鎬?+ 鏄惁閮ㄥ垎绱㈠紩锛屽涓€涓皯涓€涓兘瑕佺孩锛?)
                    .containsExactlyInAnyOrderEntriesOf(expected);
        }

        // ------------------------------------------------------------------
        // 绗笁灞傦細绾︽潫绾у揩鐓э紙DDL 鍘熸枃鐗囨锛?        // ------------------------------------------------------------------

        /**
         * 姣忓紶琛ㄧ殑 DDL 閲屽繀椤诲嚭鐜扮殑鍏抽敭鐗囨锛欳HECK / REFERENCES ... ON DELETE ... /
         * NOT NULL / DEFAULT銆?         *
         * 涓轰粈涔堣 sqlite_master.sql 鑰屼笉鏄?pragma锛欳HECK 鐨勫唴瀹瑰湪 pragma 閲屾牴鏈棶涓嶅嚭鏉?         * 锛圥RAGMA table_info 鍙粰闈炵┖鏍囪涓庨粯璁ゅ€硷級锛宍REFERENCES` 鐨勭洰鏍囧姩浣滆櫧鐒惰兘鐢?         * pragma_foreign_key_list 璇诲埌锛屼絾璇讳笉鍑恒€岃繖涓€鍒椾笂鍒板簳鍐欐病鍐欑害鏉熴€嶃€侱DL 鍘熸枃鏄?         * 鍞竴鑳藉悓鏃惰鐩栬繖鍑犵被鐨勫湴鏂癸紱瀵瑰簲鍦帮紝鏂█蹇呴』鍐欐垚**甯﹀熬闅忛€楀彿鎴栨暣琛?*鐨勫舰寮忥紝
         * 鍚﹀垯 `REFERENCES photo(id)` 浼氭妸 `REFERENCES photo(id) ON DELETE SET NULL`
         * 涔熺畻閫氳繃鈥斺€旈偅姝ｅソ涓㈡帀鎴戜滑瑕佸畧鐨勯偅涓姩浣溿€?         */
        @Test
        void constraint_snapshot_key_ddl_clauses() {
            assertThat(ddlOf("muscle_group")).contains(
                    collapseWhitespace("name_zh         TEXT    NOT NULL"));

            // 閮ㄥ垎鍞竴绱㈠紩鐨勫畾涔変綋锛氬彧绾︽潫鏈夊閮ㄦ潵婧愮殑鍔ㄤ綔锛屾墜宸ュ姩浣滃彲浠ラ噸澶嶃€?            // 鍚嶅瓧灞傞潰鐨勬鏌ュ湪 index_snapshot 閲岋紝杩欓噷纭銆岄儴鍒嗐€嶈繖涓涔夋病琚涪鎺?            // 锛堝幓鎺?WHERE 灏卞彉鎴愩€屾墜宸ュ姩浣滀篃鍙兘鏈変竴涓€嶏紝閭ｆ槸鍙︿竴绉嶉敊锛夈€?            assertThat(indexDdl("ux_exercise_source"))
                    .contains(collapseWhitespace("WHERE source_id IS NOT NULL"));

            // 鍥涗釜绾棩鏈熷垪鐨?GLOB 褰㈢姸绾︽潫銆傚皯涓€涓紝閭ｄ竴璺殑绛夊€兼煡璇㈠氨浼氬湪鏌愬ぉ闈欓粯澶辨晥銆?            assertThat(ddlOf("workout_session")).contains(collapseWhitespace(
                    "CHECK (session_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
            assertThat(ddlOf("meal")).contains(collapseWhitespace(
                    "CHECK (meal_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
            assertThat(ddlOf("body_metric")).contains(collapseWhitespace(
                    "CHECK (measured_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));
            assertThat(ddlOf("photo")).contains(collapseWhitespace(
                    "CHECK (taken_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"));

            // 鐪熸椂闂存埑鍒?*涓嶈**琚涓婃棩鏈熷舰鐘剁害鏉熲€斺€斿畠浠湰鏉ュ氨鍚椂闂淬€?            assertThat(ddlOf("workout_session")).doesNotContain("CHECK (started_at");
            assertThat(ddlOf("workout_session")).doesNotContain("CHECK (ended_at");
            assertThat(ddlOf("meal")).doesNotContain("CHECK (eaten_at");
            assertThat(ddlOf("chat_message")).doesNotContain("CHECK (created_at");
            assertThat(ddlOf("change_log")).doesNotContain("CHECK (changed_at");

            // 鏋氫妇 CHECK
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
            // 銆屼竴閿洖閫€銆嶇殑瀹堝崼锛歛ction='update' 蹇呴』鏈?before 蹇収锛屽惁鍒欒繖琛屾案杩滃洖涓嶅幓锛?            // 鑰?UI 鍙湅 action 灏变細鐢诲嚭涓€涓寜涓嬪幓鏃犱簨鍙挙鐨勩€屾挙閿€銆嶆寜閽€?            assertThat(ddlOf("change_log")).contains(
                    collapseWhitespace("CHECK (action <> 'update' OR before_json IS NOT NULL)"));

            // workout_set 鐨勬暟鍊煎悎娉曟€э紙鍒绘剰涓嶅姞 NOT NULL锛氫笁绌鸿鏄湁鎰忕殑鐘舵€侊級
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (weight IS NULL OR weight >= 0)"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (repetitions IS NULL OR repetitions >= 0)"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (duration_seconds IS NULL OR duration_seconds > 0)"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (target_weight IS NULL OR target_weight >= 0)"));
            assertThat(ddlOf("workout_set")).contains(collapseWhitespace("CHECK (target_reps IS NULL OR target_reps >= 0)"));

            // 绾ц仈鍒犻櫎锛氬皯涓€涓?REFERENCES ... ON DELETE CASCADE锛屽垹璁粌/鍒犱細璇濆氨浼氱暀涓嬪鍎胯锛?            // 鑰屼笖**涓嶄細鏈変换浣曟姤閿?*銆傚熬闅忛€楀彿鏄繀椤荤殑锛岄槻姝㈠熀纭€澶栭敭褰㈠紡钂欐贩閫氳繃銆?            assertThat(ddlOf("exercise_muscle")).contains(collapseWhitespace(
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

            // 涓嶇骇鑱旂殑澶栭敭锛氬姩浣滀笌椋熺墿琚紩鐢ㄦ椂涓嶅厑璁歌繛甯﹀垹闄わ紝鎵€浠ヨ繖閲屾槸瑁?REFERENCES锛堝甫閫楀彿锛夈€?            assertThat(ddlOf("session_exercise")).contains(collapseWhitespace(
                    "exercise_id   INTEGER NOT NULL REFERENCES exercise(id),"));
            assertThat(ddlOf("meal_item")).contains(collapseWhitespace(
                    "food_id      INTEGER NOT NULL REFERENCES food(id),"));

            // meal.photo_id锛氱収鐗囨槸椁愮殑鍙€夊厓鏁版嵁锛屽垹鐓х墖鍙兘娓呯┖杩欎竴鏍硷紝缁濅笉鑳藉垹鎺夌敤椁愯褰曘€?            assertThat(ddlOf("meal")).contains(
                    collapseWhitespace("REFERENCES photo(id) ON DELETE SET NULL"));

            // 钀ュ吇鍊煎繀椤绘湁榛樿 0 涓旈潪绌猴細鏃㈤潪绌哄張鏈夐粯璁わ紝鍘绘帀鍝竴涓兘浼氳銆屽彧濉簡鍚嶅瓧鐨勯鐗┿€?            // 鍦ㄨ鍙栨椂鐐告帀鎴栬€呮倓鎮勫彉鎴?NULL 鐑噺銆?            assertThat(ddlOf("food")).contains(collapseWhitespace("kcal_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("protein_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("carb_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("fat_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("fiber_per100 REAL NOT NULL DEFAULT 0"));
            assertThat(ddlOf("food")).contains(collapseWhitespace("sodium_per100 REAL NOT NULL DEFAULT 0"));

            // autoincrement锛氫涪鎺夊畠锛孲QLite 浼氭妸鍒犳帀鐨勬渶澶?rowid 閲嶆柊鍙戝嚭鍘伙紝
            // 浜庢槸銆屽垹鎺夌 7 鏉¤缁冧箣鍚庡啀寤轰竴鏉°€嶄細鎷垮埌 7锛岃€?change_log 閲岃繕鐣欑潃鏃х殑 7銆?            // 鍗佸洓寮犺〃閮芥湁鍗曞垪 id 涓婚敭锛沞xercise_muscle 鏄鍚堜富閿殑鍏宠仈琛?            // 锛圥RIMARY KEY (exercise_id, muscle_group_id)锛夛紝鏈潵灏辨病鏈?AUTOINCREMENT锛?            // 瀹冪敱涓婇潰閭ｆ潯 DDL 鏂█閲岀殑 PRIMARY KEY 鐗囨瀹堢潃銆?            EXPECTED.stream()
                    .filter(table -> !"exercise_muscle".equals(table))
                    .forEach(table -> assertThat(ddlOf(table))
                            .as("琛?%s 鐨勪富閿繀椤绘樉寮?AUTOINCREMENT", table)
                            .contains(collapseWhitespace("INTEGER PRIMARY KEY AUTOINCREMENT")));
            assertThat(ddlOf("exercise_muscle")).contains(
                    collapseWhitespace("PRIMARY KEY (exercise_id, muscle_group_id)"));
        }

        // ------------------------------------------------------------------
        // 缁撴瀯鏌ヨ宸ュ叿
        // ------------------------------------------------------------------

        /** 鎸夊０鏄庨『搴忚繑鍥炲垪鍚嶃€傜敤 pragma_table_info 琛ㄥ嚱鏁帮紝涓嶈兘鐢?PRAGMA table_info銆?*/
        private List<String> columns(String table) {
            return jdbc.queryForList("SELECT name FROM pragma_table_info('" + table + "')", String.class);
        }

        /** 鎸夊０鏄庨『搴忚繑鍥為潪绌哄垪鍚嶏紙涓嶅惈涓婚敭锛歱ragma 鐨?notnull 瀵?INTEGER PRIMARY KEY 鎭掍负 0锛夈€?*/
        private List<String> notNullColumns(String table) {
            return jdbc.queryForList(
                    "SELECT name FROM pragma_table_info('" + table + "') WHERE \"notnull\" = 1",
                    String.class);
        }

        /**
         * 涓氬姟绱㈠紩 -> "UNIQUE" / "NOT UNIQUE"锛堝彲鍔?" PARTIAL"锛夈€俿qlite_ 寮€澶寸殑鑷姩绱㈠紩
         * 鐢?UNIQUE 绾︽潫涓庡鍚堜富閿骇鐢燂紝涓嶅睘浜庢湰杩佺Щ鏄惧紡寤虹殑绱㈠紩锛屼笉璁″叆銆?         *
         * `PRAGMA index_list` 鏄€屼竴琛屽鍒椼€嶇殑琛ㄥ嚱鏁帮紝鎸?SQLite 鐨勮鍒欏姞琛ㄥ悕鍙傛暟鍐欐垚
         * `pragma_index_list('琛?)` 灏辫兘鍦?FROM 閲岀敤銆傛敞鎰?*涓嶈**鍐?pragma_index_list_all锛?         * 瀹炴祴璇ュ悕瀛椾笉瀛樺湪锛圫QLite 3.53.1 鎶?`no such table: pragma_index_list_all`锛夈€?         */
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

        /** 寤鸿〃 DDL 鍘熸枃锛岀┖鐧芥姌鍙犳垚鍗曠┖鏍硷紝渚夸簬鏂█璺ㄨ鐨?CHECK銆?*/
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

- [x] **Step 5: 璺戞祴璇?*

Run: `cd backend && mvn -B test -Dtest=SchemaTest`

Expected: `Tests run: 8, Failures: 0, Errors: 0`锛堟湰杞慨澶嶅悗 SchemaTest 浠?4 鏉″彉鎴?8 鏉★級

**浣嗗彧璺?`-Dtest=SchemaTest` 鏄笉澶熺殑**锛歏2 鐨勭涓€琛屼細鎶?`schema_probe` 鍒犳帀锛岃€?`MigrationTest.baseline_migration_created_probe_table` 鏂█鐨勬槸銆岃繖寮犺〃杩樺湪銆嶁€斺€?鍏ㄩ噺娴嬭瘯浼氬洜姝ゅ彉绾€傚疄娴嬬‘璁よ繃锛岃繖涓嶆槸鐚滄祴銆傚鐞嗗姙娉曡 Step 5b銆?
- [x] **Step 5b: 淇?MigrationTest 閲岃 V2 寮勭孩鐨勯偅鏉℃柇瑷€**

V2 涓€鏃?DROP 鎺夋帰閽堣〃锛宍MigrationTest` 閲岄偅鏉℃柇瑷€灏?*缁撴瀯鎬у湴鏃犳硶鎴愮珛**锛?杩佺Щ鍦ㄤ笂涓嬫枃鍚姩鏃跺氨璺戝畬浜嗭紝浠讳綍 `@Test` 鏂规硶閮界湅涓嶅埌杩欏紶琛ㄣ€傚畠鍘熸湰瑕佸畧鐨勬槸
銆孷1 閲岄偅鍙?CREATE 涓嶈兘娑堝け鎴栬鏀瑰潖銆嶏紝杩欎欢浜嬪彧鏈夎鑴氭湰鍘熸枃鎵嶉棶寰楀嚭鏉ワ紝鎵€浠ユ敼鎴愶細

```java
    @Test
    void baseline_migration_still_defines_the_probe_table_it_is_known_for() {
        // 璇昏祫婧愬師鏂囪€屼笉鏄暟鎹簱锛歴chema_probe 宸茶 V2 鍒犳帀锛堣 SchemaTest 鐨勫搴旀柇瑷€锛夈€?        String v1 = readMigration("V1__baseline.sql");

        // 绌鸿縼绉汇€丆REATE 琚垹鎺?/ 鏀瑰悕 / 鎷嗘垚鍒殑琛紝閮戒細鍦ㄨ繖閲屽彉绾€?        assertThat(v1).contains("CREATE TABLE");
        assertThat(v1).contains("schema_probe");
        assertThat(v1).contains("INTEGER PRIMARY KEY AUTOINCREMENT");
        assertThat(v1).contains("NOT NULL");
    }

    private String readMigration(String fileName) {
        try (var in = new ClassPathResource("db/migration/" + fileName).getInputStream()) {
            // 缁熶竴鎹㈣绗︼細鏂█鍙叧蹇冨唴瀹癸紝涓嶈鍥犱负 checkout 鐨勮灏捐缃€屽彉绾€?            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception e) {
            throw new IllegalStateException("璇讳笉鍒拌縼绉昏剼鏈? " + fileName, e);
        }
    }
```

椤哄甫琛ヤ笂浜嗗師鏂█鏌ヤ笉鍒扮殑鐩插尯锛氭敼 V1 鍙銆屽凡缁忚窇杩?V1 鐨勫簱銆嶆姤 checksum 澶遍厤锛?**鍏ㄦ柊鐨勫簱鐓ф牱璺戝緱閫?*锛堟湰浠撳簱鐨勬祴璇曞簱姣忔 `target` 涓€鍒犲氨鏄叏鏂扮殑锛夛紝
鎵€浠ュ厜闈犺繍琛屾椂鏂█鏍规湰鎷︿笉浣忚繖绫绘敼鍔ㄣ€?
**浣嗗瓙涓叉柇瑷€涓㈡帀浜嗗垪绾х簿纭€?*锛堝鏌ユ剰瑙侊級锛屾墍浠ユ湰杞張琛ヤ簡涓€鏉?*鍐呭瓨搴?*娴嬭瘯锛?鎶?V1 鐨勮剼鏈枃鏈 `jdbc:sqlite::memory:` 鎵ц涓€閬嶏紝鍐嶆柇瑷€鍒楁竻鍗曚笌
`AUTOINCREMENT` / `NOT NULL`銆備笅闈㈡槸 `MigrationTest` 鐨勬渶缁堝疄鐜帮紙涓?`SchemaTest.java` 涓€鏍凤紝浠ｇ爜鍧楁暣浣撶缉杩?4 鏍硷級锛?
> **涓変釜瀹炴祴韪╁嚭鏉ョ殑鍧?*锛氬唴瀛樺簱鏄€屼竴杩炴帴涓€搴撱€嶏紝蹇呴』淇濇寔鍚屼竴涓?`Connection` 鎵撳紑銆?> 涓嶈兘浜ょ粰杩炴帴姹狅紱`PRAGMA table_info` 鏄〃鍑芥暟锛岃鍐?> `SELECT name FROM pragma_table_info('schema_probe')`锛涜繖鏉℃柇瑷€楠岃瘉鐨勬槸銆岃剼鏈枃鏈兘璺戝嚭
> 浠€涔堛€嶏紝**涓嶈兘鏇夸唬** Flyway 閭ｆ潯鏂█锛堝畠缁曡繃浜?`flyway_schema_history`銆佺粫杩囦簡鐪熷疄
> 鐨勮縼绉绘墽琛岃矾寰勶級锛屾敞閲婇噷宸插啓娓呫€?
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
     * 杩佺Щ鏈哄埗鐨勫摠鍏点€?     *
     * 涓轰粈涔堢涓€鏉℃柇瑷€銆岀粨鏋勩€嶈€屼笉鏄€屾湁涓€琛屾暟鎹€嶏細鎶?V1 鐨勫唴瀹规竻绌恒€佸彧鐣欐敞閲婏紝
     * Flyway 鐓ф牱浼氳涓€琛?success=1鈥斺€斾簬鏄€屾暟涓€琛屾暟鎹€嶅紡鐨勬柇瑷€浠嶇劧鍏ㄧ豢锛岃€?V1 鍏跺疄
     * 浠€涔堜篃娌″仛銆傚涓€涓敤鏉ヨ瘉鏄庛€岃縼绉绘満鍒跺彲闈犮€嶇殑娴嬭瘯鏉ヨ锛岃繖鎭板ソ鏄弽渚嬨€?     *
     * 涓轰粈涔堜粠 Task 3 璧疯繖鏉℃敼鎴愯杩佺Щ鑴氭湰鏈韩锛歏2 鐨勭涓€琛屽氨鏄?DROP TABLE schema_probe锛?     * 杩佺Щ鍦ㄤ笂涓嬫枃鍚姩鏃跺氨璺戝畬锛?*浠讳綍**娴嬭瘯鏂规硶閮界湅涓嶅埌杩欏紶琛ㄤ簡鈥斺€旂户缁柇瑷€銆岃〃鍦ㄣ€?     * 鏃犺鎬庝箞鍐欓兘蹇呯劧绾紝鑰屾柇瑷€銆岃〃涓嶅湪銆嶅彧鏄妸 SchemaTest 閭ｆ潯鎶勪竴閬嶃€?     * 鎯冲畧浣忕殑鍏跺疄杩樻槸鍚屼竴浠朵簨锛歏1 閲岄偅鍙?CREATE 涓嶈兘娑堝け鎴栬鏀瑰潖銆傝繖浠朵簨鍙湁璇?     * 鑴氭湰鍘熸枃鎵嶉棶寰楀嚭鏉ワ紝椤哄甫杩樿ˉ涓婁簡鍘熸柇瑷€鏌ヤ笉鍒扮殑鐩插尯鈥斺€旀敼 V1 鍙銆屽凡缁忚窇杩?V1 鐨勫簱銆?     * 鎶?checksum 澶遍厤锛?*鍏ㄦ柊鐨勫簱鐓ф牱璺戝緱閫?*锛屾墍浠ュ厜闈犺繍琛屾椂鏂█鎷︿笉浣忚繖绫绘敼鍔ㄣ€?     *
     * 鏈疆锛圱ask 3 瀹℃煡鍚庯級琛ヤ簡涓夋潯锛?     * - v1_script_still_produces_the_probe_table_it_is_known_for锛氭妸 V1 鐨勮剼鏈枃鏈涓€鏉?     *   **鍐呭瓨**搴撴墽琛屼竴閬嶏紝鍐嶆柇瑷€瀹冪湡鐨勫缓鍑洪偅寮犺〃銆佸垪鍚嶄笌 AUTOINCREMENT / NOT NULL 閮藉湪銆?     *   杩欐槸瀵逛笂闈㈤偅鏉″瓙涓叉柇瑷€鐨勬敹绐勶細瀛愪覆鏂█闂笉鍑恒€岃剼鏈繕鑳戒笉鑳借窇銆嶏紝涔熼棶涓嶅嚭鍒楃骇缁嗚妭銆?     * - published_migrations_are_byte_frozen锛氭妸宸插彂甯冪増鏈殑 checksum 閽夋銆?     * - 锛堝師鏈夌殑 PRAGMA 鍝ㄥ叺涓嶅彉銆傦級
     */
    @SpringBootTest
    class MigrationTest {

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void baseline_migration_still_defines_the_probe_table_it_is_known_for() {
            // 璇昏祫婧愬師鏂囪€屼笉鏄暟鎹簱锛歴chema_probe 宸茶 V2 鍒犳帀锛堣 SchemaTest 鐨勫搴旀柇瑷€锛夈€?            String v1 = readMigration("V1__baseline.sql");

            // 绌鸿縼绉汇€丆REATE 琚垹鎺?/ 鏀瑰悕 / 鎷嗘垚鍒殑琛紝閮戒細鍦ㄨ繖閲屽彉绾€?            assertThat(v1).contains("CREATE TABLE");
            assertThat(v1).contains("schema_probe");
            assertThat(v1).contains("INTEGER PRIMARY KEY AUTOINCREMENT");
            assertThat(v1).contains("NOT NULL");

            // 鍙嶈繃鏉ヨ锛歏1 鐪熺殑寤轰簡杩欏紶琛紝鎵嶈疆寰楀埌 V2 鍘?DROP 瀹冣€斺€擵2 閲岄偅鍙?            // DROP TABLE schema_probe锛堟棤 IF EXISTS锛夊湪绌?schema 涓婂繀鐒舵姤
            // "no such table: schema_probe"锛屾暣涓笂涓嬫枃璧蜂笉鏉ャ€傝繖鏉￠摼瀛愭湰韬氨鏄?            // V1 鎵ц杩囩殑璇佹嵁銆?        }

        /**
         * 鎶?V1 鐨勮剼鏈枃鏈涓€鏉?*鍐呭瓨** SQLite 杩炴帴鎵ц涓€閬嶏紝鐒跺悗鏂█鍒楃骇缁嗚妭銆?         *
         * 涓轰粈涔堥渶瑕佸畠锛氫笂闈㈤偅鏉″瓙涓叉柇瑷€鍙湅銆屾枃閲屾湁娌℃湁杩欏嚑娈靛瓧銆嶏紝鐪嬩笉鍑鸿剼鏈繕鑳戒笉鑳芥墽琛岋紝
         * 涔熺湅涓嶅嚭鍒楃被鍨?/ 椤哄簭 / 闈炵┖鏍囪銆傚鏌ユ鏄嵁姝ゆ寚鍑哄畠銆屼涪鎺変簡鍒楃骇绮剧‘鎬с€嶃€?         *
         * 涓変釜蹇呴』娉ㄦ剰鐨勭偣锛堥兘鏄疄娴嬭俯鍑烘潵鐨勶級锛?         * 1. `jdbc:sqlite::memory:` 鏄?*涓€杩炴帴涓€搴?*锛氫氦缁欒繛鎺ユ睜锛堟垨姣忔潯璇彞鍚勫彇涓€娆¤繛鎺ワ級
         *    灏变細鎷垮埌涓€涓┖搴擄紝`no such table: schema_probe`銆傛墍浠ヨ繖閲屾墜鍔?         *    DriverManager.getConnection 骞朵笖鍏ㄧ▼鍙湁杩欎竴鏉?Connection銆?         * 2. `PRAGMA table_info` 鏄〃鍑芥暟锛堜竴琛屽鍒楋級锛宷ueryForList(..., String.class) 浼氬瀹冩姏
         *    IncorrectResultSetColumnCountException锛涘啓鎴?`SELECT name FROM pragma_table_info(...)`銆?         * 3. 瀹冮獙璇佺殑鏄€岃剼鏈枃鏈兘璺戝嚭浠€涔堛€嶏紝**涓嶈兘鏇夸唬** Flyway 閭ｆ潯鏂█鈥斺€斿畠缁曡繃浜?         *    flyway_schema_history銆佺粫杩囦簡鐪熷疄鐨勮縼绉绘墽琛岃矾寰勶紝涔熶笉妫€鏌?V1 鏄惁鐪熺殑琚?Flyway
         *    搴旂敤杩囥€備袱鏉℃柇瑷€瀹堢殑鏄笉鍚岀殑涓滆タ锛屽垹鍝竴鏉￠兘浼氶湶鍑轰竴涓礊銆?         */
        @Test
        void v1_script_still_produces_the_probe_table_it_is_known_for() throws Exception {
            // 鎵嬪姩寮€杩炴帴銆佹墜鍔ㄥ叧锛氬唴瀛樺簱闅忚繛鎺ュ瓨鍦紝涓嶈兘浜ょ粰杩炴帴姹犮€?            try (Connection memory = DriverManager.getConnection("jdbc:sqlite::memory:")) {
                ScriptUtils.executeSqlScript(memory, new ClassPathResource("db/migration/V1__baseline.sql"));

                // 鍖呬竴灞?SingleConnectionDataSource锛岃涓嬮潰鍑犳潯鏌ヨ閮借蛋杩欎竴鏉?Connection
                // 锛堝唴瀛樺簱鏄竴杩炴帴涓€搴擄紝鎹竴鏉¤繛鎺ュ氨鎹竴涓┖搴擄級銆?                JdbcTemplate mem = new JdbcTemplate(new SingleConnectionDataSource(memory, true));

                // 1) 鍒楁竻鍗曪細灏戜竴鍒椼€佹敼鍚嶃€侀『搴忓彉浜嗛兘浼氱孩銆?                assertThat(mem.queryForList(
                        "SELECT name FROM pragma_table_info('schema_probe')", String.class))
                        .containsExactly("id", "note", "created_at");

                // 2) DDL 鍘熸枃閲岀殑 AUTOINCREMENT 涓庡垪绾?NOT NULL銆?                //    DDL 鏄寜鍒楀榻愬瓨鐨勶紙`note       TEXT NOT NULL`锛夛紝鎵€浠ユ柇瑷€鍓嶅厛鎶婄┖鐧芥姌鍙犳垚
                //    鍗曠┖鏍硷細杩欐牱鏂█鏃笉鎬曡縼绉昏閲嶆柊鎺掔増锛屽張鑳界湡姝ｉ棶鍑恒€岃繖涓€鍒椾笂鏈夋病鏈?NOT NULL銆嶃€?                String ddl = collapseWhitespace(mem.queryForObject(
                        "SELECT sql FROM sqlite_master WHERE type='table' AND name='schema_probe'",
                        String.class));
                assertThat(ddl).contains("INTEGER PRIMARY KEY AUTOINCREMENT");
                assertThat(ddl).contains("note TEXT NOT NULL,");
                assertThat(ddl).contains("created_at TEXT NOT NULL");

                // 3) 鑴氭湰閲岀殑閭ｈ鏁版嵁涔熺湡鐨勮繘鍘讳簡锛堢┖鑴氭湰 / INSERT 琚垹鎺変細绾級銆?                assertThat(mem.queryForObject("SELECT note FROM schema_probe", String.class))
                        .isEqualTo("baseline");
            }
        }

        /**
         * 銆屽凡鍙戝竷杩佺Щ涓嶅彲鍙樸€嶈繖鏉＄邯寰嬶紝姝ゅ墠鍙墿鏂囨。鍦ㄥ畧銆?         *
         * 涓?Flyway 鑷繁鐨勬牎楠?*涓嶉噸澶?*锛欶lyway 鍙湪銆屽凡缁忚窇杩囪鐗堟湰鐨勫簱銆嶄笂姣?checksum锛?         * 鑰屾湰鍦板垹鎺?target 涔嬪悗姣忔璺戞祴璇曢兘鏄叏鏂扮殑搴擄紝checksum 鏍￠獙鏍规湰涓嶄細琚Е鍙戔€斺€?         * 浜庢槸缁?V1 鍔犱竴琛屾敞閲娿€佹垨鑰呮妸 V2 鐨勬煇涓?CHECK 鍒犳帀锛屽浠剁収鏍峰叏缁裤€?         * 杩欓噷鎶?checksum 閽夋鍦ㄦ祴璇曢噷锛屼换浣曞 V1鈥揤4 鐨勭紪杈戯紙鍝€曞彧鍔犱竴琛屾敞閲婏級閮戒細璁╁畠鍙樼孩銆?         *
         * checksum 鏄?Flyway 瀵硅縼绉绘枃浠跺唴瀹圭畻鍑虹殑 CRC32锛屽繀椤讳粠**瀹炶窇鍚庣殑搴?*閲岃鍑烘潵濉紝
         * 涓嶈兘鐚溿€傚綋鍓嶅€煎搴?V1鈥揤4 鍙戝竷鏃剁殑瀛楄妭鍐呭锛?         *   1:baseline / 2:training / 3:nutrition / 4:auxiliary銆?         *
         * 鐗堟湰鍙峰啓姝绘垚 containsExactly锛堜笉鏄€屽墠鍥涗釜蹇呴』鏄€嶏級锛氬皢鏉ユ柊澧?V5 鏃惰繖鏉′細绾紝
         * 閭ｆ槸**鏈夋剰鐨?*鈥斺€旀柊杩佺Щ涓€鏃﹀彂甯冨氨璇ヨ涓€璧峰喕缁擄紝鍔犺繘鏉ョ殑鍚屾椂涔熻纭瀹冨凡鍙戝竷銆?         */
        @Test
        void published_migrations_are_byte_frozen() {
            assertThat(jdbc.queryForList(
                    "SELECT version || ':' || checksum FROM flyway_schema_history ORDER BY installed_rank",
                    String.class))
                    .containsExactly("1:1904735359", "2:-962992177", "3:1207314981", "4:1460974919");
        }

        private String readMigration(String fileName) {
            try (var in = new ClassPathResource("db/migration/" + fileName).getInputStream()) {
                // 缁熶竴鎹㈣绗︼細鏂█鍙叧蹇冨唴瀹癸紝涓嶈鍥犱负 checkout 鐨勮灏捐缃€屽彉绾€?                return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            } catch (Exception e) {
                throw new IllegalStateException("璇讳笉鍒拌縼绉昏剼鏈? " + fileName, e);
            }
        }

        /** 鎶婄┖鐧芥姌鍙犳垚鍗曠┖鏍硷細DDL 鏄寜鍒楀榻愬瓨鐨勶紝鏂█鐗囨涓嶈鍥犱负鎺掔増鑰屽け閰嶃€?*/
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
            // 杩欎袱涓?PRAGMA 闈?JDBC URL 鍙傛暟寮€鍚紙杩佺Щ鑴氭湰閲屽紑涓嶄簡锛歋QLite 鍦ㄤ簨鍔″唴
            // 鎷掔粷鍒?WAL锛夈€傚畠浠竴鏃﹀啓閿欏ぇ灏忓啓锛岄┍鍔ㄤ細褰撴垚 PRAGMA 鍚嶆墽琛屽苟鐩存帴
            // SQLITE_CANTOPEN锛屽簲鐢ㄦ牴鏈捣涓嶆潵鈥斺€旀墍浠ヨ繖鏉℃柇瑷€鏄繀瑕佺殑鍝ㄥ叺銆?            assertThat(queryPragma("foreign_keys")).isEqualTo("1");
            assertThat(queryPragma("journal_mode")).isEqualTo("wal");
        }

        private String queryPragma(String name) {
            return jdbc.queryForObject("PRAGMA " + name, String.class);
        }
    }
```

- [x] **Step 6: 璺戝叏閲忔祴璇?*

Run: `cd backend && Remove-Item -Recurse -Force target; mvn -B test`

Expected: `Tests run: 18, Failures: 0, Errors: 0`锛堝疄娴嬪€硷級

锛堟湰杞慨澶嶅墠鐨勬€绘暟鏄?12 = 2 + 3 + 3 + 4銆備慨澶嶅悗 18 = `ChangeApplicationTests` 2 +
`DataDirectoryInitializerTest` 3 + `MigrationTest` 5 + `SchemaTest` 8锛涘叿浣撲负
`MigrationTest` 3 鈫?5锛屾柊澧?`v1_script_still_produces_the_probe_table_it_is_known_for`
涓?`published_migrations_are_byte_frozen`锛沗SchemaTest` 4 鈫?8锛屾柊澧炲垪绾?/ NOT NULL /
绱㈠紩绾?/ 绾︽潫绾у洓鏉″揩鐓э紝骞舵妸 `workout_set_supports_timed_and_per_side` 鐢?`contains`
鏀规垚 `containsExactlyInAnyOrder`锛堝銆屽鍑轰竴鍒椼€嶆亽缁跨殑鍐欐硶锛夈€?*蹇呴』鍏堝垹 `target`**锛?`src/main/resources` 涓嬬殑鏀瑰姩鍙兘娌¤澶嶅埗鍒?`target/classes`锛岄獙璇佺粨鏋滀細涓庢簮鐮佷笉绗︺€傦級

- [x] **Step 7: 鎻愪氦锛圱ask 3 鍘熷瀹炵幇锛?*

```bash
git add backend/src/main/resources/db/migration/V2__training.sql \
        backend/src/main/resources/db/migration/V3__nutrition.sql \
        backend/src/main/resources/db/migration/V4__auxiliary.sql \
        backend/src/test/java/com/change/SchemaTest.java \
        backend/src/test/java/com/change/MigrationTest.java
git commit -m "feat: 鍗佷簲寮犺〃鐨勮縼绉伙紝鍚笁鏉￠槻鍥炲綊鏂█"
```

- [x] **Step 8: 鎻愪氦锛圱ask 3 瀹℃煡淇锛?*

```bash
git add -A
git commit -m "fix(schema): 纭寲鍗佷簲寮犺〃鐨勭害鏉熶笌瀹堝崼锛堝鏌ュ彂鐜?8 绉嶇牬鍧忓潎鏃犱汉鎷︼級"
```

**鎻愪氦鍓嶅繀椤诲畬鎴愪笂涓€鑺傞偅寮犺〃閲岀殑 8 绉嶇牬鍧忛獙璇?*锛氶€愭潯鐮村潖銆佸垹 `target`銆佽窇
`mvn -B test` 纭鍙樼孩銆佹仮澶嶃€備换浣曚竴绉?*鎶撲笉浣?*閮借鏄庝慨澶嶆病鍒颁綅锛岃鎶ュ憡鑰屼笉鏄寫
瀹规槗鐨勫仛銆傚仛瀹岃繖涓€姝ユ墠鍏佽鍕鹃€?Step 8 杩涘叆 Task 4銆?
> 瀹炴柦璁板綍瑙?commit message 涓?`git log`锛涙湰杞疄娴嬬殑 8 鏉＄牬鍧忕粨鏋滐紙鍝潯娴嬭瘯鍙樼孩銆?> 鎶ヤ簡浠€涔堥敊锛夊凡閫愭潯璁板綍銆?
### 鐢辨湰 schema 鎺ㄥ嚭銆佸繀椤诲湪鍒鍏戠幇鐨勭害瀹?
杩欎竴鑺傝鐨勪笉鏄€屽皢鏉ヤ篃璁歌鑰冭檻鐨勪簨銆嶏紝鑰屾槸**鏁版嵁搴撳眰宸茬粡鍒绘剰鍋氬嚭銆佸苟涓斿啓杩涗簡 DDL 鐨勫喅瀹?*銆?瀹冧滑鍏ㄩ兘灞炰簬鍚屼竴绫伙細鎷﹀緱浣忎竴浠朵簨锛屽氨蹇呴』鏈夊彟涓€澶勬帴浣忓墿涓嬬殑閭ｅ崐銆俿chema 鏈韩宸茬粡鎶婅瘽璇存
锛堣縼绉讳竴鏃﹀垎鍙戝氨瀛楄妭鍐荤粨锛屾病鏈夌浜屾鏈轰細锛夛紝鎵€浠ョ害瀹氬啓鍦ㄨ繖閲岋紝缂轰簡鍝竴鏉￠兘鏄紡鍋氥€?
#### (a) 鍥涗釜鏃ユ湡 CHECK 鐨勯敊璇秷鎭繀椤荤炕璇戞垚浜鸿瘽锛涜€屼笖瀹冨彧绠″舰鐘躲€佷笉绠℃棩鍘?
鍥涗釜绾棩鏈熷垪锛坄workout_session.session_date`銆乣meal.meal_date`銆乣body_metric.measured_on`銆?`photo.taken_on`锛夐兘甯﹀悓涓€鏉″舰鐘剁害鏉燂細

```sql
CHECK (<鍒? GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')
```

**鎺ュ彛灞備箟鍔?*锛氫换浣曚竴鏉″啓鍏ヨ矾寰勶紙鐢ㄦ埛杈撳叆銆佸鍏ュ櫒銆佸閮ㄦ暟鎹泦锛夋挒涓婂畠鏃讹紝鎶涘嚭鐨勯兘鏄竴鍙?`CHECK constraint failed: session_date GLOB ...`鈥斺€旇嫳鏂囥€佸甫鍐呴儴绾︽潫鍚嶄笌 GLOB 妯″紡锛屽鐢ㄦ埛姣棤
鎰忎箟銆傛帴鍙ｅ眰蹇呴』鎶婅繖鏉?*鎸夊垪缈昏瘧鎴愬彲璇绘彁绀?*锛屼緥濡傘€屾棩鏈熸牸寮忓繀椤绘槸 2026-10-14銆嶃€傚惁鍒欑敤鎴?鍙細鐪嬪埌涓€鍙ョ害鏉熷悕锛屾棦涓嶇煡閬撳摢涓€鍒楅敊浜嗭紝涔熶笉鐭ラ亾姝ｇ‘鐨勫啓娉曟槸浠€涔堛€?
浼氭挒涓婅繖鏉＄殑涓変釜鐜板疄鏉ユ簮锛岄兘涓嶆槸鑴忔暟鎹€佽€屾槸**鏍煎紡绾﹀畾涓嶅悓**锛?
- 鍒嗛殧绗﹀啓鎴?`2026/10/14`锛?- 浠庤〃鏍兼垨 CSV 鎶勬潵鐨勫€煎甫灏鹃殢绌烘牸锛堟垨鍓嶅悗绌烘牸锛夛紱
- 鍓嶇 `toLocaleDateString()` / `toISOString()` 鐨勮緭鍑猴紙鍒嗗埆鏄?`2026/10/14` 涓?  `2026-10-14T03:22:11.000Z`锛夈€傚悗鑰呭挨鍏惰闃诧細`toISOString()` 鐨勭粨鏋?*鐪嬭捣鏉?*鍍忔棩鏈燂紝
  浣嗗畠姝ｆ槸杩欐潯 CHECK 瑕佹嫤鐨勩€屽畬鏁存椂闂存埑娣疯繘绾棩鏈熷垪銆嶁€斺€旀嫤浣忓畠鏄湁鎰忕殑锛屼笉鏄浼ゃ€?
**鍚屾椂蹇呴』璁颁綇杩欐潯 CHECK 鐨勮竟鐣岋細瀹冨彧绠″舰鐘讹紝涓嶇鏃ュ巻銆?* `2026-02-31`銆乣2026-13-01`
閮借兘鍘熸牱鍏ュ簱鈥斺€擥LOB 鍙暟浣嶏紝涓嶅垽鏂湀浠藉ぉ鏁般€?*鏃ュ巻鍚堟硶鎬х敱搴旂敤灞傛牎楠?*锛氳縼绉昏兘鎷︾殑鍙湁
褰㈢姸锛屾嫤涓嶄簡銆岃繖涓棩鏈熶笉瀛樺湪銆嶏紝鑰屼竴涓笉瀛樺湪鐨勬棩鏈熶竴鏃﹀叆搴擄紝`strftime` / 鍖洪棿鏌ヨ / 鎸夋棩
鎺掑簭閮戒細缁欏嚭闈欓粯閿欒鐨勭粨鏋滐紙涓嶆槸鎶ラ敊锛夈€?
#### (b) `exercise.name_en` 鐨勫敮涓€绱㈠紩鎶娿€屽姩浣滆韩浠姐€嶅帇鍦ㄤ簡鑻辨枃鍚嶇殑涔﹀啓瑙勮寖涓?
`ux_exercise_name_en` 鏄?`exercise(name_en)` 鐨?*鍗曞垪**鍞竴绱㈠紩锛?
```sql
CREATE UNIQUE INDEX ux_exercise_name_en ON exercise(name_en);
```

瀹冧笉鍚?`name_zh`锛堣鍒楀彲绌猴紝涔熶笉鍞竴锛夈€佷笉鍚?`equipment`銆佷笉鍚?`is_compound`銆備篃灏辨槸璇达紝
搴撻噷**娌℃湁**鍩轰簬涓枃鍚嶆垨銆屼腑鏂囧悕 + 鍣ㄦ銆嶇殑鍞竴鎬р€斺€斿姩浣滆韩浠藉畬鍏ㄩ潬鑻辨枃鍚嶇殑鎺緸鍖哄垎锛?
- 銆屾潬閾冨崸鎺ㄣ€嶄笌銆屽搼閾冨崸鎺ㄣ€嶅繀椤诲啓浣?`Barbell Bench Press` 涓?`Dumbbell Bench Press`锛?  涓嶈兘涓ゆ潯閮藉彨 `Bench Press`锛堝惁鍒欑浜屾潯鎾?`UNIQUE constraint failed`锛屾暣鎵瑰鍏ュ洖婊氾級锛?- 鍙嶈繃鏉ワ紝鍚屼竴鍔ㄤ綔鐨勪腑鏂囧悕鎬庝箞鍐欓兘涓嶅奖鍝嶅敮涓€鎬э紝鏀逛腑鏂囧悕涓嶄細鎾炪€?
**璁″垝 2锛堝姩浣滃簱瀵煎叆锛夊繀椤诲畧浣忚繖涓害瀹?*锛屽惁鍒欎袱鏉¤矾閮芥槸鍧忕殑锛氳涔堝鍏ョ洿鎺ュけ璐ワ紝瑕佷箞涓轰簡
缁曡繃瀹冨幓鏀硅嫳鏂囧悕锛堟妸 `Barbell Bench Press` 鏀规垚 `Bench Press (Barbell)`锛夛紝鑰屽悗鑰呬細闈欓粯
姹℃煋銆屾寜鑻辨枃鍚嶅榻愬閮ㄦ暟鎹泦銆嶈繖浠朵簨鈥斺€擿source_id` 鐨勫懡鍚嶇┖闂磋兘闃蹭綇璺ㄦ暟鎹泦鍐茬獊锛岄槻涓嶄綇
鍚屾暟鎹泦鍐呯殑鎺緸婕傜Щ銆?*杩欐潯绾︽潫涓€鏃﹀簱閲屾湁鍘嗗彶鏁版嵁灏卞姞涓嶄笂浜?*锛堣涔堝缓涓嶄笂绱㈠紩锛岃涔堝緱鍏?鍚堝苟閲嶅琛屻€侀噸鎸?`session_exercise.exercise_id`銆侀噸绠楁墍鏈夎鐢熸寚鏍囷級锛屾墍浠ュ凡鎸夌幇鍦ㄥ畾涓嬬殑
褰㈡€佸浐鍖栤€斺€斿鍏ュ櫒閫傞厤瀹冿紝涓嶆槸瀹冮€傞厤瀵煎叆鍣ㄣ€?
#### (c) `food.name_zh` 鏁呮剰涓嶅敮涓€锛屼唬浠疯璁?
`food.name_zh` 涓婂彧鏈変竴鏉?*闈炲敮涓€**绱㈠紩 `ix_food_name_zh`锛屾病鏈夊敮涓€绾︽潫锛?
```sql
CREATE INDEX ix_food_name_zh ON food(name_zh);
```

杩欐槸鏈夋剰鐨勶細涓紡椋熺墿鍚屽悕涓嶅悓鍋氭硶寰堝父瑙侊紙銆岀孩鐑ц倝銆嶇殑鐐掓硶涓庣倴娉曢厤鏂欐瘮渚嬪樊寰堝锛?銆屾竻钂搁矆楸笺€嶄笌銆岀孩鐑ч矆楸笺€嶆槸涓ら亾鑿滐級锛屽湪鏁版嵁搴撳眰寮哄埗鍞竴浼氶€煎鍏ュ櫒浜岄€変竴鈥斺€旇涔堜涪鏁版嵁锛?瑕佷箞缁欏悓涓€绉嶉鐗╃紪閫犲彉浣撳悕锛屼袱绉嶉兘姣斿厑璁搁噸澶嶆洿绯熴€傛墍浠?*鍚屽悕澶氳鏄鐨?*銆?
**浠ｄ环蹇呴』璁?*锛氫竴鏃﹀悓涓€鍚嶅瓧瀛樺湪澶氳锛?*缁熻銆屽悓涓€绉嶉鐗┿€嶆椂鑻ユ寜鍚嶅瓧鑱氬悎灏变細閲嶅璁℃暟**鈥斺€?鎸?`name_zh` 鍒嗙粍鐨勩€屾湰鍛ㄥ悆浜嗗灏戠孩鐑ц倝銆嶄細鎶婃瘡涓€琛屽悇鑷畻涓€閬嶏紱鏇撮殣钄界殑鏄妸瀹冨綋鎴愬悓涓€绉?椋熺墿鍘诲钩鍧囷紝浼氭妸涓ょ鍋氭硶鐨勭儹閲忔悈鎴愪竴涓笉瀛樺湪鐨勪腑闂村€笺€?
**浣嗘敼鎴愭寜 `food_id` 鑱氬悎骞朵笉瑙ｅ喅杩欎欢浜?*锛岃繖涓€鐐瑰繀椤昏娓呮锛屽惁鍒欏緢瀹规槗浠ヤ负宸茬粡淇ソ浜嗭細
`meal_item.food_id` 鏄?`NOT NULL`锛屾寜瀹冨垎缁勭‘瀹炰笉浼氭妸涓ょ鍋氭硶鎼呭湪涓€璧封€斺€斿彲鏄?*鍚屼竴涓?dish
琚綍杩涘幓涓ゆ鏃舵湰鏉ュ氨浼氭嬁鍒颁袱涓笉鍚岀殑 `food_id`**锛堛€岀孩鐑ц倝锛堢倰锛夈€嶅拰銆岀孩鐑ц倝锛堢倴锛夈€嶆槸涓よ锛?`id` 涓嶅悓锛夛紝浜庢槸銆岃繖鍛ㄥ悆浜?3 娆＄孩鐑ц倝銆嶄緷鐒舵暟鎴?1 娆＄倰 + 2 娆＄倴锛屾垨鑰呭弽杩囨潵鎶婃湰璇ユ槸鍚屼竴閬撹彍
鐨勮褰曟媶鎴愪袱涓€屽父鍚冮鐗┿€嶃€?*鎹㈠彞璇濊锛氶噸澶嶈鏁扮殑鏍瑰洜鏄€屽悓涓€閬撹彍鏈夊涓韩浠姐€嶏紝涓嶆槸鑱氬悎
閿€夐敊浜嗐€?* 鎸夊悕瀛楄仛鍚堟槸閿欏湪鎶婁笉鍚屽仛娉曞綋鍚屼竴绉嶏紱鎸?`food_id` 鑱氬悎鏄敊鍦ㄦ妸鍚屼竴绉嶅仛娉曞綋涓嶅悓
绉嶃€備袱涓敭鍚勮嚜閮藉彧鑳界瓟瀵逛竴鍗娿€?
**瀵煎叆鍣ㄤ箟鍔?*锛堣繖鎵嶆槸鐪熸瑕佸仛鐨勯偅涓€姝ワ級锛氬湪瀵煎叆鎶ュ憡閲?*鍒楀嚭鐤戜技閲嶅椤?*锛堝悓鍚嶄絾钀ュ吇鍊兼垨
浠介噺瀹氫箟涓嶄竴鑷寸殑琛岋級锛岃浜哄綋鍦哄喅瀹氭槸鍚堝苟銆佹敼鍚嶈繕鏄繚鐣欍€傚彧鏈夎繖涓€姝ュ仛浜嗭紝銆屽悓涓€閬撹彍銆嶆墠浼?鏀舵暃鍒颁竴涓?`food_id` 涓婏紝涔嬪悗鎸?`food_id` 鑱氬悎涓庢寜鍚嶅瓧鑱氬悎鎵嶄細缁欏嚭鍚屼竴涓瓟妗堛€傝繖涓€姝ヤ笉鍋氾紝
鍛抽亾宸紓灏变細鍙樻垚鐑噺缁熻閲岀殑鍣０鈥斺€旇€屼笖瀹冧笉浼氭姤閿欙紝鍙細璁╂瘡鏃ョ儹閲忕湅璧锋潵銆屽氨鏄樊閭ｄ箞涓€鐐广€嶃€?锛堣嫢灏嗘潵闇€瑕佸湪搴撳唴琛ㄨ揪銆岃繖涓や釜鏉＄洰鍏跺疄鏄悓涓€閬撹彍銆嶏紝閭ｈ涓€涓柊鍒楁垨涓€寮犲埆鍚嶈〃鈥斺€旂幇鍦ㄦ病鏈夛紝
鎵€浠ヨ繖涓敹鏁涘彧鑳藉彂鐢熷湪瀵煎叆鏈熴€傦級

---

## Task 4: 璁粌鍩?JPA 瀹炰綋涓庝粨鍌?
**Files:**
- Create: `backend/src/main/java/com/change/domain/Exercise.java`
- Create: `backend/src/main/java/com/change/domain/WorkoutSession.java`
- Create: `backend/src/main/java/com/change/domain/SessionExercise.java`
- Create: `backend/src/main/java/com/change/domain/WorkoutSet.java`
- Create: `backend/src/main/java/com/change/repo/ExerciseRepo.java`
- Create: `backend/src/main/java/com/change/repo/WorkoutSessionRepo.java`
- Test: `backend/src/test/java/com/change/repo/TrainingRepoTest.java`

- [ ] **Step 1: 鍐欏疄浣擄紙鍥涗釜锛?*

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

    /** 璁℃椂鍔ㄤ綔锛堝钩鏉挎敮鎾戠瓑锛夌敤绉掓暟锛屼笌 repetitions 浜岄€変竴銆?*/
    @Column(name = "duration_seconds")
    public Integer durationSeconds;

    /** 姣忎晶璁℃暟锛堝紦姝ャ€佸崟鑷傚姩浣滐級銆?*/
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

- [ ] **Step 2: 鍐欎袱涓粨鍌?*

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

- [ ] **Step 3: 鍐欓泦鎴愭祴璇?*

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
        ex.nameZh = "鏉犻搩鍗ф帹";
        ex.equipment = "barbell";
        ex.compound = true;
        exercises.save(ex);

        WorkoutSession s = new WorkoutSession();
        s.sessionDate = LocalDate.of(2026, 10, 14);
        s.title = "鑳?+ 涓夊ご";
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
        assertThat(back.targetWeight).isEqualTo(60.0);   // 鐩爣鍊间笌瀹為檯鍊煎悓琛?        assertThat(back.perSide).isFalse();
    }

    @Test
    void timed_set_stores_seconds_not_reps() {
        Exercise ex = new Exercise();
        ex.nameEn = "Plank";
        ex.nameZh = "骞虫澘鏀拺";
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

- [ ] **Step 4: 璺戞祴璇?*

Run: `cd backend && mvn -B test -Dtest=TrainingRepoTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 鎻愪氦**

```bash
git add backend/src/main/java/com/change/domain backend/src/main/java/com/change/repo \
        backend/src/test/java/com/change/repo
git commit -m "feat: 璁粌鍩熷疄浣撲笌浠撳偍锛岃鐩栦笁灞傚祵濂椾笌璁℃椂/姣忎晶缁?
```

---

## Task 5: 鐑噺璁＄畻锛堟牳蹇冿紝鍏堝啓娴嬭瘯锛?
**杩欐槸鏈鍒掓渶閲嶈鐨勪笁涓换鍔′箣涓€銆?* 瀹冪殑閿欒浼氭薄鏌撴墍鏈夐ギ椋熺粺璁°€?
**Files:**
- Create: `backend/src/main/java/com/change/calc/Nutrition.java`
- Create: `backend/src/main/java/com/change/calc/NutritionCalculator.java`
- Test: `backend/src/test/java/com/change/calc/NutritionCalculatorTest.java`

- [ ] **Step 1: 鍐欏け璐ョ殑娴嬭瘯**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NutritionCalculatorTest {

    /** 姣?100 鍏嬪惈 116 kcal銆?.6 g 铔嬬櫧鐨勭背楗€?*/
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
        // 杩欐潯鏄€屼笉瀛樼粷瀵圭儹閲忋€嶉偅鏉￠搧寰嬬殑楠屾敹鐐癸細
        // 鏀逛唤閲忎笉闇€瑕併€岃寰楀洖鍘绘敼鏃у€笺€嶏紝鍥犱负鏃у€间粠鏉ユ病琚瓨涓嬫潵銆?        Nutrition oneBowl = NutritionCalculator.forGrams(RICE_PER_100G, 150);
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
        // 浠介噺涓婇檺鏍￠獙锛? 涓囧厠鏄剧劧鏄綍閿欎簡
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

- [ ] **Step 2: 璺戞祴璇曪紝纭瀹冨け璐?*

Run: `cd backend && mvn -B test -Dtest=NutritionCalculatorTest`

Expected: 缂栬瘧澶辫触锛屾彁绀?`cannot find symbol: class Nutrition` / `NutritionCalculator`

- [ ] **Step 3: 鍐欏疄鐜?*

```java
package com.change.calc;

/** 涓€浠借惀鍏诲€笺€傚崟浣嶏細kcal 涓庡厠銆?*/
public record Nutrition(double kcal, double protein, double carb,
                        double fat, double fiber, double sodium) {
}
```

```java
package com.change.calc;

import java.util.List;

/**
 * 鐑噺涓庡畯閲忚惀鍏荤礌璁＄畻銆? *
 * 閾佸緥锛氭墍鏈夎惀鍏诲€奸兘鎸夈€屾瘡 100 鍏嬨€嶄繚瀛橈紝浠介噺鍗曠嫭淇濆瓨銆? * 鏈被鍙仛涓€浠朵簨锛氭妸姣?100 鍏嬬殑鍊兼寜瀹為檯鍏嬫暟鎹㈢畻鍑烘潵銆? * 缁撴灉姘歌繙瀹炴椂璁＄畻锛岀粷涓嶅洖瀛樷€斺€旇繖鏍锋敼浠介噺銆佹崲椋熺墿銆佹崲鍗曚綅閮戒笉浼氱畻閿欍€? */
public final class NutritionCalculator {

    /** 鍗曟褰曞叆鐨勪唤閲忎笂闄愶紙鍏嬶級銆傝秴杩囪繖涓暟鍑犱箮涓€瀹氭槸褰曢敊浜嗐€?*/
    public static final double MAX_GRAMS = 5_000;

    private NutritionCalculator() {
    }

    public static Nutrition forGrams(Nutrition per100g, double grams) {
        if (grams < 0) {
            throw new IllegalArgumentException("grams 涓嶈兘涓鸿礋: " + grams);
        }
        if (grams > MAX_GRAMS) {
            throw new IllegalArgumentException(
                    "grams 瓒呭嚭涓婇檺 " + MAX_GRAMS + ": " + grams);
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

    /** 淇濈暀涓や綅灏忔暟锛氶伩鍏?0.1+0.2 閭ｇ被娴偣鍣０鏄剧ず鎴?0.30000000000000004銆?*/
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
```

- [ ] **Step 4: 璺戞祴璇曪紝纭閫氳繃**

Run: `cd backend && mvn -B test -Dtest=NutritionCalculatorTest`

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 鎻愪氦**

```bash
git add backend/src/main/java/com/change/calc/Nutrition.java \
        backend/src/main/java/com/change/calc/NutritionCalculator.java \
        backend/src/test/java/com/change/calc/NutritionCalculatorTest.java
git commit -m "feat: 鐑噺璁＄畻锛堟瘡 100 鍏嬪熀鍑?+ 瀹炴椂鎹㈢畻锛夛紝鍚唤閲忎笂闄愭牎楠?
```

---

## Task 6: 浠介噺鍗曚綅鎹㈢畻锛堟牳蹇冿級

**Files:**
- Create: `backend/src/main/java/com/change/calc/PortionConverter.java`
- Test: `backend/src/test/java/com/change/calc/PortionConverterTest.java`

- [ ] **Step 1: 鍐欏け璐ョ殑娴嬭瘯**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortionConverterTest {

    private static final List<PortionConverter.Unit> RICE_UNITS = List.of(
            new PortionConverter.Unit("纰?, 150),
            new PortionConverter.Unit("鍕?, 15));

    @Test
    void converts_bowl_to_grams() {
        assertThat(PortionConverter.toGrams(1, "纰?, RICE_UNITS)).isEqualTo(150.0);
    }

    @Test
    void converts_fractional_units() {
        assertThat(PortionConverter.toGrams(1.5, "纰?, RICE_UNITS)).isEqualTo(225.0);
    }

    @Test
    void converts_spoon_to_grams() {
        assertThat(PortionConverter.toGrams(2, "鍕?, RICE_UNITS)).isEqualTo(30.0);
    }

    @Test
    void null_unit_means_amount_is_already_grams() {
        assertThat(PortionConverter.toGrams(150, null, RICE_UNITS)).isEqualTo(150.0);
    }

    @Test
    void unknown_unit_is_rejected_with_a_useful_message() {
        assertThatThrownBy(() -> PortionConverter.toGrams(1, "纰楀崐", RICE_UNITS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("纰楀崐")
                .hasMessageContaining("纰?);   // 鎻愮ず閲岃甯︿笂鍙敤鍗曚綅锛屾柟渚垮墠绔樉绀?    }

    @Test
    void three_conversion_paths_agree() {
        // 杩欐槸璁捐鏂囨。閲岀偣鍚嶇殑蹇呮祴杈圭晫锛氫笁绉嶅綍鍏ヨ矾寰勭粨鏋滃繀椤讳竴鑷?        double byBowl   = PortionConverter.toGrams(1, "纰?, RICE_UNITS);       // 1 纰?        double bySpoon  = PortionConverter.toGrams(10, "鍕?, RICE_UNITS);      // 10 鍕?        double byGram   = PortionConverter.toGrams(150, null, RICE_UNITS);     // 鐩存帴濉厠鏁?
        assertThat(byBowl).isEqualTo(byGram);
        assertThat(bySpoon).isEqualTo(byGram);
    }

    @Test
    void builds_unit_index_from_rows() {
        Map<String, Double> idx = PortionConverter.index(RICE_UNITS);
        assertThat(idx).containsEntry("纰?, 150.0).containsEntry("鍕?, 15.0);
    }
}
```

- [ ] **Step 2: 璺戞祴璇曪紝纭澶辫触**

Run: `cd backend && mvn -B test -Dtest=PortionConverterTest`

Expected: 缂栬瘧澶辫触锛宍cannot find symbol: class PortionConverter`

- [ ] **Step 3: 鍐欏疄鐜?*

```java
package com.change.calc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 浠介噺鍗曚綅鎹㈢畻锛氥€? 纰椼€嶆姌鎴愬灏戝厠銆? *
 * 鎹㈢畻琛ㄥ瓨鍦?food_portion 琛ㄩ噷锛堟瘡椋熺墿涓€濂楀崟浣嶏級锛屾湰绫诲彧璐熻矗鏌ヨ〃涓庤绠椼€? */
public final class PortionConverter {

    /** 鏌愰鐗╀笂鐨勪竴涓唤閲忓崟浣嶃€?*/
    public record Unit(String name, double grams) {
    }

    private PortionConverter() {
    }

    /**
     * @param amount 浠介噺鏁板€?     * @param unit   鍗曚綅鍚嶏紱浼?null 琛ㄧず amount 鏈韩灏辨槸鍏嬫暟
     */
    public static double toGrams(double amount, String unit, List<Unit> units) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount 涓嶈兘涓鸿礋: " + amount);
        }
        if (unit == null || unit.isBlank()) {
            return amount;
        }
        Map<String, Double> idx = index(units);
        Double gramsPerUnit = idx.get(unit);
        if (gramsPerUnit == null) {
            throw new IllegalArgumentException(
                    "鏈煡鐨勪唤閲忓崟浣嶃€? + unit + "銆嶏紱璇ラ鐗╁彲鐢ㄧ殑鍗曚綅: "
                            + String.join("銆?, idx.keySet()));
        }
        return amount * gramsPerUnit;
    }

    public static Map<String, Double> index(List<Unit> units) {
        return units.stream().collect(Collectors.toMap(
                Unit::name, Unit::grams, (a, b) -> a, LinkedHashMap::new));
    }
}
```

- [ ] **Step 4: 璺戞祴璇曪紝纭閫氳繃**

Run: `cd backend && mvn -B test -Dtest=PortionConverterTest`

Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: 鎻愪氦**

```bash
git add backend/src/main/java/com/change/calc/PortionConverter.java \
        backend/src/test/java/com/change/calc/PortionConverterTest.java
git commit -m "feat: 浠介噺鍗曚綅鎹㈢畻锛屽惈銆屼笁绉嶅綍鍏ヨ矾寰勭粨鏋滀竴鑷淬€嶇殑杈圭晫娴嬭瘯"
```

---

## Task 7: 璁粌瀹归噺璁＄畻锛堟牳蹇冿級

**Files:**
- Create: `backend/src/main/java/com/change/calc/VolumeCalculator.java`
- Test: `backend/src/test/java/com/change/calc/VolumeCalculatorTest.java`

- [ ] **Step 1: 鍐欏け璐ョ殑娴嬭瘯**

```java
package com.change.calc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeCalculatorTest {

    /** 涓€涓姩浣滃湪涓€娆¤缁冮噷鐨勮嫢骞茬粍锛屼互鍙婂畠鐗佃繛鐨勮倢缇ゃ€?*/
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
        // 璁捐鏂囨。閲岀殑绠椾緥锛氬崸鎺?4 缁?鈫?鑳?+4銆佷笁澶?+2銆佷笁瑙掕倢鍓嶆潫 +2
        var volume = VolumeCalculator.accumulate(List.of(
                entry(100, 4, "primary"),     // 鑳?                entry(101, 4, "secondary"),   // 涓夊ご
                entry(102, 4, "secondary"))); // 涓夎鑲屽墠鏉?
        assertThat(volume).containsEntry(100L, 4.0)
                          .containsEntry(101L, 2.0)
                          .containsEntry(102L, 2.0);
    }

    @Test
    void same_muscle_from_two_exercises_accumulates() {
        // 鍗ф帹 4 缁勮涓夊ご +2锛岀怀绱笅鍘?3 缁勮涓夊ご +3锛屽悎璁?5
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

- [ ] **Step 2: 璺戞祴璇曪紝纭澶辫触**

Run: `cd backend && mvn -B test -Dtest=VolumeCalculatorTest`

Expected: 缂栬瘧澶辫触锛宍cannot find symbol: class VolumeCalculator`

- [ ] **Step 3: 鍐欏疄鐜?*

```java
package com.change.calc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 璁粌瀹归噺缁熻锛氭妸銆岃浜嗗灏戠粍銆嶆姌绠楁垚銆屾瘡涓倢缇ゅ灏戠粍銆嶃€? *
 * 瑙勫垯锛堣璁℃枃妗ｅ喅瀹?5锛夛細
 *   涓昏鑲岀兢 +1锛屾瑕佽倢缇?+0.5銆? * 娆¤鑲岀兢绠楀崐鍒嗘槸璁粌閲忕粺璁＄殑閫氳鍋氭硶鈥斺€斿崸鎺ㄥ涓夊ご纭疄鏈夊埡婵€锛? * 浣嗕笉璇ュ拰鑷傚眻浼哥畻鎴愪竴鏍风殑閲忋€? */
public final class VolumeCalculator {

    public enum Status { UNTRAINED, BELOW, IN_RANGE, ABOVE }

    /** 涓€鏉¤础鐚細鏌愪釜鑲岀兢銆佽嫢骞茬粍銆佹壆婕斾粈涔堣鑹层€?*/
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
                        "鏈煡鐨勮倢缇よ鑹? " + e.role() + "锛堝彧鎺ュ彈 primary / secondary锛?);
            };
            out.merge(e.muscleId(), e.sets() * weight, Double::sum);
        }
        return out;
    }

    /**
     * 涓庡缓璁尯闂存瘮杈冦€?     * 娉ㄦ剰锛氳繖涓尯闂存槸棰勮鐨勫弬鐓х郴锛屼笉鏄瀛︾粨璁猴紱鐢ㄦ埛鍙互鍦ㄨ缃噷鏀广€?     */
    public static Status classify(double sets, int min, int max) {
        if (sets <= 0) return Status.UNTRAINED;
        if (sets < min) return Status.BELOW;
        if (sets > max) return Status.ABOVE;
        return Status.IN_RANGE;
    }
}
```

- [ ] **Step 4: 璺戞祴璇曪紝纭閫氳繃**

Run: `cd backend && mvn -B test -Dtest=VolumeCalculatorTest`

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 鎻愪氦**

```bash
git add backend/src/main/java/com/change/calc/VolumeCalculator.java \
        backend/src/test/java/com/change/calc/VolumeCalculatorTest.java
git commit -m "feat: 璁粌瀹归噺璁＄畻锛堜富瑕?+1 / 娆¤ +0.5锛夛紝鍚璁℃枃妗ｇ畻渚?
```

---

## Task 8: 1RM 浼扮畻

**Files:**
- Create: `backend/src/main/java/com/change/calc/OneRepMax.java`
- Test: `backend/src/test/java/com/change/calc/OneRepMaxTest.java`

- [ ] **Step 1: 鍐欏け璐ョ殑娴嬭瘯**

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
        // 瓒呰繃 12 娆″悗 Epley 鐨勮宸繀閫熷彉澶э紝瀹佸彲涓嶇畻涔熶笉瑕佺粰涓亣绮剧‘鐨勬暟
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
        // 骞虫澘鏀拺娌℃湁 1RM 鐨勬蹇碉紝浼?null 娆℃暟搴旇璺宠繃鑰屼笉鏄姏閿?        double best = OneRepMax.bestOf(java.util.List.of(
                new OneRepMax.Set(60, 8),
                new OneRepMax.Set(0, null)));

        assertThat(best).isEqualTo(76.0);
    }
}
```

- [ ] **Step 2: 璺戞祴璇曪紝纭澶辫触**

Run: `cd backend && mvn -B test -Dtest=OneRepMaxTest`

Expected: 缂栬瘧澶辫触锛宍cannot find symbol: class OneRepMax`

- [ ] **Step 3: 鍐欏疄鐜?*

```java
package com.change.calc;

import java.util.List;

/**
 * 浼扮畻 1RM銆? *
 * 涓轰粈涔堥粯璁ょ敤 1RM 鑰屼笉鏄渶澶ч噸閲忥細鏈€澶ч噸閲忓彧鍦ㄥ啿閲嶉噺閭ｄ竴娆℃墠鍔紝
 * 涓棿鍑犱釜鏈堟洸绾挎槸骞崇殑锛?RM 鐢辨瘡娆＄殑銆岄噸閲?脳 娆℃暟銆嶄及绠楋紝姣忔璁粌閮借兘璁╂洸绾垮姩涓€鐐广€? *
 * 鐢?Epley 鍏紡锛?RM = w 脳 (1 + reps / 30)銆? */
public final class OneRepMax {

    /** 瓒呰繃杩欎釜娆℃暟鍚?Epley 璇樊杩呴€熷彉澶э紝涓嶅啀浼扮畻銆?*/
    public static final int MAX_REPS = 12;

    /** 涓€缁勭殑鏁版嵁銆俽epetitions 涓?null 琛ㄧず璁℃椂鍔ㄤ綔锛岃烦杩囥€?*/
    public record Set(double weight, Integer repetitions) {
    }

    private OneRepMax() {
    }

    public static double epley(double weight, int reps) {
        if (reps < 1) {
            throw new IllegalArgumentException("reps 蹇呴』 >= 1: " + reps);
        }
        if (reps > MAX_REPS) {
            throw new IllegalArgumentException(
                    "reps 瓒呰繃 " + MAX_REPS + " 鏃?Epley 浼扮畻涓嶅彲闈? " + reps);
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight 涓嶈兘涓鸿礋: " + weight);
        }
        return round2(weight * (1 + reps / 30.0));
    }

    public static double bestOf(List<Set> sets) {
        double best = 0;
        for (Set s : sets) {
            if (s.repetitions() == null || s.repetitions() < 1 || s.repetitions() > MAX_REPS) {
                continue;   // 璁℃椂鍔ㄤ綔鎴栬秴鑼冨洿锛岃烦杩?            }
            best = Math.max(best, epley(s.weight(), s.repetitions()));
        }
        return best;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
```

- [ ] **Step 4: 璺戞祴璇曪紝纭閫氳繃**

Run: `cd backend && mvn -B test -Dtest=OneRepMaxTest`

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 鎻愪氦**

```bash
git add backend/src/main/java/com/change/calc/OneRepMax.java \
        backend/src/test/java/com/change/calc/OneRepMaxTest.java
git commit -m "feat: Epley 1RM 浼扮畻锛岃秴杩?12 娆℃嫆缁濅及绠楄€屼笉鏄粰鍋囩簿纭€?
```

---

## Task 9: ChangeLog 涓庡洖閫€

杩欐槸銆屾櫤鑳戒綋鍙啓銆嶉偅鏉″喅瀹氱殑瀹夊叏甯︺€?
**Files:**
- Create: `backend/src/main/java/com/change/service/ChangeLogService.java`
- Test: `backend/src/test/java/com/change/service/ChangeLogServiceTest.java`

- [ ] **Step 1: 鍐欏け璐ョ殑娴嬭瘯**

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
        long id = insertFood("娴嬭瘯椋熺墿A", 100);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "娴嬭瘯椋熺墿A"),
                "user", null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT origin, action, target_table, before_json, after_json " +
                "FROM change_log WHERE id = ?", logId);
        assertThat(row.get("origin")).isEqualTo("user");
        assertThat(row.get("action")).isEqualTo("insert");
        assertThat(row.get("before_json")).isNull();
        assertThat((String) row.get("after_json")).contains("娴嬭瘯椋熺墿A");
    }

    @Test
    void rollback_of_an_insert_deletes_the_row() {
        long id = insertFood("娴嬭瘯椋熺墿B", 200);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "娴嬭瘯椋熺墿B"),
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
        long id = insertFood("娴嬭瘯椋熺墿C", 300);
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
        long id = insertFood("娴嬭瘯椋熺墿D", 400);
        long logId = logs.recordInsert("food", id, Map.of("name_zh", "娴嬭瘯椋熺墿D"),
                "agent", null);
        logs.undo(logId);

        assertThatThrownBy(() -> logs.undo(logId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("宸茬粡鎾ら攢杩?);
    }

    @Test
    void rejects_unknown_table() {
        assertThatThrownBy(() -> logs.recordInsert("drop_table_attempt", 1,
                Map.of("x", 1), "agent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("鐧藉悕鍗?);
    }
}
```

- [ ] **Step 2: 璺戞祴璇曪紝纭澶辫触**

Run: `cd backend && mvn -B test -Dtest=ChangeLogServiceTest`

Expected: 缂栬瘧澶辫触锛宍cannot find symbol: class ChangeLogService`

- [ ] **Step 3: 鍐欏疄鐜?*

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
 * 鍙樻洿鏃ュ織锛氳銆屾櫤鑳戒綋鍙啓銆嶈繖浠朵簨鏈夊畨鍏ㄥ甫銆? *
 * 涓夋潯璁捐绾︽潫锛堣璁℃枃妗ｅ喅瀹?4锛夛細
 *   1. 琛ㄥ悕蹇呴』鍦ㄧ櫧鍚嶅崟閲?鈥斺€?宸ュ叿闆嗘槸鐧藉悕鍗曪紝涓嶆槸璁╂ā鍨嬭嚜鐢卞啓 SQL
 *   2. 鍙褰?insert / update锛屼笉鎻愪緵 delete 鈥斺€?鍒犻櫎鏃犳硶鎾ら攢锛屾墍浠ュ共鑴嗕笉缁? *   3. 姣忔潯鏀瑰姩瀛樹笅 before / after 鐨?JSON 蹇収锛屽彲涓€閿洖閫€
 */
@Service
public class ChangeLogService {

    /** 鍏佽琚褰曚笌鍥為€€鐨勮〃銆傚姞琛ㄥ繀椤绘敼杩欓噷锛屾敼杩欓噷蹇呴』鍚屾椂鍔犳祴璇曘€?*/
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

    /** 鍥為€€涓€鏉℃敼鍔ㄣ€俰nsert 鐨勫洖閫€鏄垹闄よ琛岋紱update 鐨勫洖閫€鏄啓鍥?before 鍊笺€?*/
    public void undo(long logId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT action, target_table, target_id, before_json, undone " +
                "FROM change_log WHERE id = ?", logId);

        if (((Number) row.get("undone")).intValue() == 1) {
            throw new IllegalStateException("杩欐潯鏀瑰姩宸茬粡鎾ら攢杩囦簡: " + logId);
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
                throw new IllegalStateException("update 璁板綍缂哄皯 before 蹇収: " + logId);
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
                    "琛ㄣ€? + table + "銆嶄笉鍦ㄧ櫧鍚嶅崟閲岋紝鎷掔粷璁板綍鎴栧洖閫€");
        }
    }

    private String toJson(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("搴忓垪鍖栧揩鐓уけ璐?, e);
        }
    }

    private Map<String, Object> fromJson(String s) {
        if (s == null) return null;
        try {
            return json.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("瑙ｆ瀽蹇収澶辫触", e);
        }
    }
}
```

- [ ] **Step 4: 璺戞祴璇曪紝纭閫氳繃**

Run: `cd backend && mvn -B test -Dtest=ChangeLogServiceTest`

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 鎻愪氦**

```bash
git add backend/src/main/java/com/change/service/ChangeLogService.java \
        backend/src/test/java/com/change/service/ChangeLogServiceTest.java
git commit -m "feat: 鍙樻洿鏃ュ織涓庝竴閿洖閫€锛岃〃鍚嶇櫧鍚嶅崟 + 绂佹浜屾鎾ら攢"
```

---

## Task 10: HTTP 楠ㄦ灦涓庣鍙ｅ崗鍟?
澶栧３闇€瑕佺煡閬撳悗鍙拌捣鍦ㄥ摢涓鍙ｃ€傜害瀹氾細**鍚庡彴鎶婄鍙ｄ互鍥哄畾鏍煎紡鎵撳埌鏍囧噯杈撳嚭**锛屽澹宠杩欎竴琛屻€?
**Files:**
- Create: `backend/src/main/java/com/change/config/PortReporter.java`
- Create: `backend/src/main/java/com/change/web/HealthController.java`
- Test: `backend/src/test/java/com/change/web/HealthControllerTest.java`

- [ ] **Step 1: 鍐欏け璐ョ殑娴嬭瘯**

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

- [ ] **Step 2: 璺戞祴璇曪紝纭澶辫触**

Run: `cd backend && mvn -B test -Dtest=HealthControllerTest`

Expected: FAIL 鈥斺€?404锛屽洜涓?`/api/health` 杩樹笉瀛樺湪

- [ ] **Step 3: 鍐欏疄鐜?*

```java
package com.change.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 鎶婂疄闄呯洃鍚鍙ｄ互鍥哄畾鏍煎紡鎵撳埌鏍囧噯杈撳嚭锛屼緵 Tauri 澶栧３璇诲彇銆? *
 * 涓轰粈涔堣璧版爣鍑嗚緭鍑鸿€屼笉鏄啓鏂囦欢锛氬澹虫槸鍚庡彴杩涚▼鐨勭埗杩涚▼锛? * 鐩存帴璇诲瓙杩涚▼鐨?stdout 鏈€绠€鍗曪紝涔熶笉闇€瑕佺害瀹氭枃浠惰矾寰勪笌娓呯悊鏃舵満銆? *
 * 杈撳嚭鏍煎紡鍥哄畾涓轰竴琛岋紝澶栧３鎸夊墠缂€鍖归厤锛? *   CHANGE_PORT=12345
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
            log.warn("鎷夸笉鍒?local.server.port锛屽澹冲皢鏃犳硶瀹氫綅鍚庣");
            return;
        }
        // 杩欎竴琛屽繀椤诲師鏍峰瓨鍦紝澶栧３闈犲畠瀹氫綅锛涙敼鍔ㄧ瓑浜庢敼鍗忚
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

    /** 鐢?Task 11 鎻愪緵瀹炵幇锛涙澶勫厛澹版槑鎺ュ彛锛岃鍋ュ悍绔偣涓嶅繀渚濊禆 Redis銆?*/
    public interface CacheStatus {
        String describe();
    }
}
```

- [ ] **Step 4: 涓存椂鎻愪緵涓€涓?CacheStatus 瀹炵幇**

Task 11 浼氭浛鎹㈡垚鐪熸鐨勫疄鐜般€傚厛鏀句竴涓渶灏忕殑锛岃鏈换鍔¤兘鐙珛璺戠豢銆?
```java
package com.change.config;

import com.change.web.HealthController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    /** 鍗犱綅瀹炵幇锛歍ask 11 浼氭崲鎴?Redis / NoOp 浜岄€変竴銆?*/
    @Bean
    public HealthController.CacheStatus cacheStatus() {
        return () -> "disabled(placeholder)";
    }
}
```

- [ ] **Step 5: 璺戞祴璇曪紝纭閫氳繃**

Run: `cd backend && mvn -B test -Dtest=HealthControllerTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: 鎵嬪姩楠岃瘉绔彛涓婃姤鏍煎紡**

Run:
```bash
cd backend && mvn -B -q -DskipTests package && java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```

Expected: 鍚姩鏃ュ織涓湁涓€琛屽舰濡?`CHANGE_PORT=54321`銆傛寜 `Ctrl+C` 鍋滄帀銆?
- [ ] **Step 7: 鎻愪氦**

```bash
git add backend/src/main/java/com/change/config/PortReporter.java \
        backend/src/main/java/com/change/config/CacheConfig.java \
        backend/src/main/java/com/change/web/HealthController.java \
        backend/src/test/java/com/change/web/HealthControllerTest.java
git commit -m "feat: HTTP 楠ㄦ灦 + 绔彛鍗忓晢锛坰tdout 涓婃姤 CHANGE_PORT=锛?
```

---

## Task 11: Redis 缂撳瓨鎶借薄涓庨檷绾?
**杩欐槸鏈鍒掓渶鍚庝竴涓换鍔★紝涔熸槸 Task 1 閭ｄ釜銆孯edis 杩炰笉涓婂鑷存祴璇曞け璐ャ€嶇殑鏀跺熬銆?*

璁捐鏂囨。瑙勫畾锛?*娌℃湁 Redis 鏃跺簲鐢ㄥ繀椤诲畬鍏ㄦ甯稿伐浣?*锛岃繛鎺ヨ秴鏃?300 ms銆佸懡浠よ秴鏃?500 ms锛岃繛缁け璐ョ啍鏂€?
**Files:**
- Create: `backend/src/main/java/com/change/cache/CacheService.java`
- Create: `backend/src/main/java/com/change/cache/NoOpCacheService.java`
- Create: `backend/src/main/java/com/change/cache/RedisCacheService.java`
- Modify: `backend/src/main/java/com/change/config/CacheConfig.java`
- Test: `backend/src/test/java/com/change/cache/CacheServiceTest.java`

- [ ] **Step 1: 鍐欏け璐ョ殑娴嬭瘯**

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
        c.put("anything", "value", Duration.ofMinutes(5));   // 涓嶅簲鎶涘紓甯?        c.invalidatePrefix("change:");
        assertThat(c.isAvailable()).isFalse();
        assertThat(c.describe()).contains("鏈惎鐢?);
    }

    @Test
    void noop_is_the_guaranteed_safe_fallback() {
        // 杩欐潯娴嬭瘯鐨勬剰涔夛細鏃犺 Redis 澶勪簬浠€涔堢姸鎬侊紝娉ㄥ叆 NoOp 鏃朵笟鍔′唬鐮侀兘涓嶄細宕┿€?        CacheService c = new NoOpCacheService();
        for (int i = 0; i < 100; i++) {
            assertThat(c.get("k" + i, Integer.class)).isEqualTo(Optional.empty());
        }
    }
}
```

- [ ] **Step 2: 璺戞祴璇曪紝纭澶辫触**

Run: `cd backend && mvn -B test -Dtest=CacheServiceTest`

Expected: 缂栬瘧澶辫触锛宍cannot find symbol: class CacheService`

- [ ] **Step 3: 鍐欐帴鍙ｄ笌闄嶇骇瀹炵幇**

```java
package com.change.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 缂撳瓨鎶借薄銆? *
 * 鍏抽敭绾﹀畾锛氫换浣曟柟娉曢兘涓嶅緱鍚戜笂鎶涘紓甯搞€俁edis 涓嶅彲鐢ㄦ槸甯告€? * 锛堝畠璺戝湪涓€鍙扮粡甯稿叧鏈虹殑铏氭嫙鏈轰笂锛夛紝涓氬姟浠ｇ爜涓嶈涓烘鍐?try/catch銆? */
public interface CacheService {

    <T> Optional<T> get(String key, Class<T> type);

    void put(String key, Object value, Duration ttl);

    /** 鎸夊墠缂€鎵归噺澶辨晥銆傚啓鎿嶄綔鍚庤皟鐢紝瀵瑰簲璁捐鏂囨。銆屽啓鍏ュ嵆澶辨晥銆嶃€?*/
    void invalidatePrefix(String prefix);

    boolean isAvailable();

    /** 缁欒缃〉鏄剧ず鐨勪汉璇濇弿杩帮紝渚嬪銆屽凡杩炴帴 192.168.100.128:6379銆嶃€?*/
    String describe();
}
```

```java
package com.change.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 闄嶇骇瀹炵幇锛氭案杩滄湭鍛戒腑锛屾案杩滀笉鎶涘紓甯搞€? *
 * 浠€涔堟椂鍊欑敤瀹冿細
 *   - Redis 閰嶇疆涓哄叧闂? *   - 鍚姩鏃舵帰娴嬩笉鍒?Redis
 *   - 杩炵画澶辫触瑙﹀彂浜嗙啍鏂? */
public class NoOpCacheService implements CacheService {

    private final String reason;

    public NoOpCacheService() {
        this("鏈惎鐢?);
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
        // 鏁呮剰浠€涔堥兘涓嶅仛
    }

    @Override
    public void invalidatePrefix(String prefix) {
        // 鏁呮剰浠€涔堥兘涓嶅仛
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String describe() {
        return "缂撳瓨锛氭湭鍚敤锛? + reason + "锛?;
    }
}
```

- [ ] **Step 4: 鍐?Redis 瀹炵幇锛堝惈鐔旀柇锛?*

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
 * Redis 瀹炵幇銆? *
 * 涓ゆ潯鑷垜淇濇姢锛? *   1. 鎵€鏈夎皟鐢ㄩ兘鎹曡幏寮傚父骞堕檷绾т负銆屾湭鍛戒腑銆嶁€斺€擱edis 鎸備簡涓嶈璁╂帴鍙?500
 *   2. 杩炵画澶辫触杈鹃槇鍊煎悗鐔旀柇涓€娈垫椂闂达紝閬垮厤姣忔璇锋眰閮界櫧绛?300 ms 杩炴帴瓒呮椂
 */
public class RedisCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /** 杩炵画澶辫触澶氬皯娆″悗鐔旀柇銆?*/
    static final int FAILURE_THRESHOLD = 3;
    /** 鐔旀柇鎸佺画澶氫箙銆?*/
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
            return "缂撳瓨锛氬凡鐔旀柇锛? + endpoint + " 杩炵画澶辫触 " + consecutiveFailures + " 娆★級";
        }
        return "缂撳瓨锛氬凡杩炴帴 " + endpoint;
    }

    private boolean isBroken() {
        if (Instant.now().isBefore(brokenUntil)) {
            return true;
        }
        if (consecutiveFailures > 0) {
            consecutiveFailures = 0;   // 鐔旀柇鏈熺粨鏉燂紝缁欎竴娆℃満浼?        }
        return false;
    }

    private void onSuccess() {
        consecutiveFailures = 0;
    }

    private void onFailure(String op, Exception e) {
        consecutiveFailures++;
        if (consecutiveFailures == FAILURE_THRESHOLD) {
            brokenUntil = Instant.now().plus(BREAK_DURATION);
            log.warn("Redis {} 杩炵画澶辫触 {} 娆★紝鐔旀柇 {} 绉掋€傚師鍥? {}",
                    op, FAILURE_THRESHOLD, BREAK_DURATION.toSeconds(), e.toString());
        }
    }
}
```

- [ ] **Step 5: 鏀?CacheConfig 鍋氬惎鍔ㄦ帰娴嬩笌鏉′欢瑁呴厤**

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
 * 缂撳瓨瑁呴厤銆? *
 * 璁捐鍘熷垯锛?*娌℃湁 Redis 鏃跺簲鐢ㄥ繀椤诲畬鍏ㄦ甯稿伐浣?*銆? * 鎵€浠ヨ繖閲屼笉鏄€岃繛涓嶄笂灏卞惎鍔ㄥけ璐ャ€嶏紝鑰屾槸銆岃繛涓嶄笂灏辨崲鎴?NoOp锛屽苟鍦ㄨ缃〉璇存竻妤氥€嶃€? */
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
            log.info("缂撳瓨锛氶厤缃负鍏抽棴锛屼娇鐢?NoOp");
            return new NoOpCacheService("閰嶇疆涓哄叧闂?);
        }

        StringRedisTemplate t = templateProvider.getIfAvailable();
        if (t == null) {
            return new NoOpCacheService("鏈閰?Redis 瀹㈡埛绔?);
        }

        try {
            // 鎺㈡祴涓€娆★細杩炰笉涓婂氨绔嬪埢闄嶇骇锛屼笉瑕佽绗竴涓姹傚幓鎵挎媴 300ms 瓒呮椂
            t.execute((RedisCallback<String>) conn -> {
                conn.ping();
                return "PONG";
            });
            log.info("缂撳瓨锛氬凡杩炴帴 {}:{}", host, port);
            return new RedisCacheService(t, host, port);
        } catch (Exception e) {
            log.info("缂撳瓨锛氳繛涓嶄笂 {}:{}锛屾湰娆¤繍琛屼娇鐢?NoOp锛堝師鍥? {}锛?, host, port, e.toString());
            return new NoOpCacheService("杩炰笉涓?" + host + ":" + port);
        }
    }

    @Bean
    public HealthController.CacheStatus cacheStatus(CacheService cache) {
        return cache::describe;
    }
}
```

- [ ] **Step 6: 璺戠紦瀛樻祴璇?*

Run: `cd backend && mvn -B test -Dtest=CacheServiceTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: 璺戝叏閲忔祴璇曪紝纭 Task 1 鐨勫け璐ュ凡娑堝け**

Run: `cd backend && mvn -B test`

Expected: 鍏ㄩ儴閫氳繃銆?*鐗瑰埆纭 `ChangeApplicationTests.contextLoads` 閫氳繃**鈥斺€旇繖鏉″湪 Task 1 鏃跺彲鑳藉洜 Redis 涓嶅彲杈捐€屽け璐ワ紝鐜板湪搴旇缁裤€傚鏋滃畠浠嶇劧澶辫触锛岃鏄庨檷绾ф病鐢熸晥锛屽洖鍒?Step 5 妫€鏌ャ€?
- [ ] **Step 8: 楠岃瘉涓や釜鍦烘櫙锛堜笉闇€瑕佸紑铏氭嫙鏈猴級**

鍦烘櫙涓€锛孯edis 鍏抽棴鏃讹細
```bash
cd backend && mvn -B -q -DskipTests package
java -jar target/change-backend-0.1.0-SNAPSHOT.jar --change.redis.enabled=false
```
Expected: 鍚姩鎴愬姛锛沗curl http://localhost:<CHANGE_PORT>/api/health` 杩斿洖 `"cache":"缂撳瓨锛氭湭鍚敤锛堥厤缃负鍏抽棴锛?`

鍦烘櫙浜岋紝Redis 鎸囧悜涓嶅彲杈惧湴鍧€鏃讹紙榛樿閰嶇疆灏辨槸杩欏彴鍏崇潃鐨勮櫄鎷熸満锛夛細
```bash
java -jar target/change-backend-0.1.0-SNAPSHOT.jar
```
Expected: **鍚姩涓嶅け璐?*锛涘仴搴风鐐硅繑鍥?`"cache":"缂撳瓨锛氭湭鍚敤锛堣繛涓嶄笂 192.168.100.128:6379锛?`

> 杩欎竴姝ラ渶瑕?Redis 鐪熷湪绾挎椂鎵嶈兘楠屻€屽凡杩炴帴銆嶃€?*鍒版椂鍊欒窡鎴戣涓€澹帮紝鎴戞妸铏氭嫙鏈哄紑璧锋潵**锛屽啀璺戜竴娆＄‘璁?`"cache":"宸茶繛鎺?192.168.100.128:6379"`銆?
- [ ] **Step 9: 鎻愪氦**

```bash
git add backend/src/main/java/com/change/cache backend/src/main/java/com/change/config/CacheConfig.java \
        backend/src/test/java/com/change/cache
git commit -m "feat: Redis 缂撳瓨鎶借薄涓庨檷绾э紙NoOp 鍏滃簳 + 鍚姩鎺㈡祴 + 杩炵画澶辫触鐔旀柇锛?
```

---

## 瀹屾垚鏍囧噯

鏈鍒掔粨鏉熸椂锛屼互涓嬪懡浠ゅ繀椤诲叏缁匡細

```bash
cd backend && mvn -B test
```

涓旀弧瓒筹細

- [ ] 鍗佷簲寮犺〃鍏ㄩ儴鐢?Flyway 寤哄嚭锛宍SchemaTest` 閫氳繃
- [ ] 涓変釜鏍稿績璁＄畻鍚勬湁鐙珛鍗曞厓娴嬭瘯锛屼笖**娴嬭瘯閲屽寘鍚簡璁捐鏂囨。鐐瑰悕鐨勮竟鐣?*锛堟敼浠介噺閲嶇畻銆佷笁绉嶅綍鍏ヨ矾寰勪竴鑷淬€佹瑕佽倢缇ょ畻鍗婂垎锛?- [ ] `ChangeLogService` 鑳藉洖閫€ insert 涓?update锛屼笖鎷掔粷浜屾鎾ら攢涓庨潪鐧藉悕鍗曡〃
- [ ] 鍚庣鍚姩鍚庢墦鍗?`CHANGE_PORT=<绔彛>`
- [ ] **`change.redis.enabled=false` 涓?Redis 涓嶅彲杈句袱绉嶆儏鍐典笅锛屽簲鐢ㄩ兘鑳芥甯稿惎鍔?*锛屼笖 `/api/health` 濡傚疄鎶ュ憡缂撳瓨鐘舵€?- [ ] 鍏ㄩ噺 `mvn test` 閫氳繃锛屼笉渚濊禆浠讳綍澶栭儴鏈嶅姟

## 涓嬩竴姝?
璁″垝 1 瀹屾垚鍚庤繘鍏?**璁″垝 2锛氬姩浣滃簱瀵煎叆涓庤倢缇ゆ槧灏?*銆傚畠闇€瑕佹湰璁″垝浜у嚭鐨?`exercise` / `muscle_group` / `exercise_muscle` 涓夊紶琛ㄤ笌 `ExerciseRepo`銆?
