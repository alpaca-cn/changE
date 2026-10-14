# spike —— 版本搭配验证

**用途**：证明 JDK 25 + Spring Boot 3.5.x + LangChain4j + SQLite 可以共存，并且 Redis 不可达时应用能正常启动。

这是本项目此前唯一的高风险项。**2026-10-14 实测通过后，它不再是阻塞项。**

## 怎么复跑

```powershell
$env:JAVA_HOME = 'C:\Users\alpaca\.jdks\openjdk-25.0.2'
cd spike
mvn -B -DskipTests package
& "$env:JAVA_HOME\bin\java.exe" -jar target\spike-0.0.1-SNAPSHOT.jar
```

## 期望输出

```
==> java.version = 25.0.2
==> sqlite     = sqlite-ok
==> jdbc.url   = jdbc:sqlite:./target/spike.db
==> jdbc.driver= SQLite JDBC 3.50.1.0
==> langchain4j= dev.langchain4j.model.openai.OpenAiChatModel
==> RESULT: ALL_OK
```

四项各验一件事：

| 输出行 | 验的是什么 |
|---|---|
| `java.version` | JDK 25 能跑 Spring Boot 3.5.16 |
| `sqlite` | Hibernate 社区方言 + SQLite 驱动能建表读写 |
| `langchain4j` | LangChain4j 1.22.0 能加载并构造出 ChatModel（**不发起网络调用**） |
| 应用能启动 | **Redis 不可达时照样启动** —— `application.properties` 里指向的是那台常关的虚拟机 |

## 两个实测数字（写进了设计文档）

- **启动耗时 3.769 秒** —— 所以外壳必须配加载提示，不能让用户双击后干等近 4 秒
- **fat jar 79.1 MB** —— 完整安装包 ≈ 12 + 47 + 79 ≈ 138 MB

## 注意

- 这个模块**不是产品代码**，验证完就冻结，不要往里加功能。
- 里面的 `apiKey("not-a-real-key")` 是故意的：只构造对象、不发请求，所以不需要真 key。**任何情况下都不要把真 key 提交进来。**
- `target/` 已被 `.gitignore` 忽略，所以 79 MB 的 jar 不会进仓库。
