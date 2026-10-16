"""Task 10 / D: 变异测试实测。

对 3 条测试逐个做变异：改动一段实现，跑 mvn，看测试**能不能变红**。
每次都在 finally 里把文件按原始字节恢复，绝不留下变异代码。

不用 PowerShell 做文本往返：全部用 Python 读写，encoding='utf-8'，newline=''。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

BACKEND = r"E:\idea\program\change\backend"
ENV = dict(os.environ)
ENV["JAVA_HOME"] = r"C:\Users\alpaca\.jdks\openjdk-25.0.2"
# Windows 环境变量大小写不敏感但 Python 的 dict 不是：统一按大写键查找，再补一份 Path
_upper = {k.upper(): v for k, v in ENV.items()}
ENV["Path"] = ENV["JAVA_HOME"] + r"\bin;" + _upper.get("PATH", "")
ENV["PATH"] = ENV["Path"]
ENV["PYTHONIOENCODING"] = "utf-8"
MVN = r"E:\maven\apache-maven-3.9.4-bin\apache-maven-3.9.4\bin\mvn.cmd"

REPORTER = os.path.join(BACKEND, r"src\main\java\com\change\config\PortReporter.java")
HEALTH = os.path.join(BACKEND, r"src\main\java\com\change\web\HealthController.java")
TEST = os.path.join(BACKEND, r"src\test\java\com\change\web\HealthControllerTest.java")

MUTANTS = [
    dict(
        name="PREFIX 改一个字符（协议变异）",
        target="port_reporter_prints_the_agreed_protocol_line",
        file=REPORTER,
        old='public static final String PREFIX = "CHANGE_PORT=";',
        new='public static final String PREFIX = "CHANGE_PORTX=";',
        cmd=[MVN, "-B", "test", "-Dtest=HealthControllerTest"],
    ),
    dict(
        name="JSON 键 status 改名（statusx）",
        target="health_reports_ok_without_touching_redis",
        file=HEALTH,
        old='"status", "up"',
        new='"statusx", "up"',
        cmd=[MVN, "-B", "test", "-Dtest=HealthControllerTest"],
    ),
    dict(
        name='JSON 值 "up" 改成 "down"',
        target="health_reports_ok_without_touching_redis",
        file=HEALTH,
        old='"status", "up"',
        new='"status", "down"',
        cmd=[MVN, "-B", "test", "-Dtest=HealthControllerTest"],
    ),
    dict(
        name="JSON 键 cache 改名（cachex）",
        target="health_reports_ok_without_touching_redis",
        file=HEALTH,
        old='"cache", cacheStatus.describe()',
        new='"cachex", cacheStatus.describe()',
        cmd=[MVN, "-B", "test", "-Dtest=HealthControllerTest"],
    ),
    dict(
        name="random_port_was_assigned 断言改成 port > 65535",
        target="random_port_was_assigned",
        file=TEST,
        old="assertThat(port).isGreaterThan(0);",
        new="assertThat(port).isGreaterThan(65535);",
        cmd=[MVN, "-B", "test", "-Dtest=HealthControllerTest"],
    ),
    dict(
        name="去掉 RANDOM_PORT（改回 MOCK 环境）",
        target="RANDOM_PORT 缺失时 @LocalServerPort 会怎样",
        file=TEST,
        old="@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)",
        new="@SpringBootTest",
        cmd=[MVN, "-B", "test", "-Dtest=HealthControllerTest"],
    ),
    dict(
        name="测试文件被篡改（对照：非变异体，确认方法名/命令能跑通）",
        target="(对照组) 无害改动：注释里加一个空格",
        file=TEST,
        old="// 这一行是外壳与后端的协议",
        new="// 这一行是外壳与后端的协议 ",
        cmd=[MVN, "-B", "test", "-Dtest=HealthControllerTest"],
    ),
    dict(
        name="去掉 RANDOM_PORT 后的**全量**套件（看谁在兜底）",
        target="random_port_was_assigned 恒真性",
        file=TEST,
        old="@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)",
        new="@SpringBootTest",
        cmd=[MVN, "-B", "test"],
    ),
]


def read(p):
    with io.open(p, "rb") as f:
        return f.read()


def write(p, b):
    with open(p, "wb") as f:
        f.write(b)


def run(cmd):
    p = subprocess.run(cmd, cwd=BACKEND, env=ENV, capture_output=True)
    return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")


def summarize(out, err):
    interesting = []
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("[ERROR]") and ("Tests run:" in s or "Test" in s or "FAILURE" in s):
            interesting.append(s)
        elif "Tests run:" in s and s.startswith("[INFO]"):
            interesting.append(s)
        elif s.startswith("[INFO] BUILD"):
            interesting.append(s)
    return interesting


results = []
try:
    for i, m in enumerate(MUTANTS, 1):
        path = m["file"]
        original = read(path)
        text = original.decode("utf-8")
        if text.count(m["old"]) != 1:
            print("!! 锚点不唯一/找不到，跳过：%s (count=%d)" % (m["name"], text.count(m["old"])))
            continue
        mutated = text.replace(m["old"], m["new"])
        write(path, mutated.encode("utf-8"))
        print("=" * 78)
        print("变异体 %d: %s" % (i, m["name"]))
        print("  文件: %s" % os.path.relpath(path, BACKEND))
        print("  命令: %s" % " ".join(m["cmd"]))
        print("  - %s" % m["old"])
        print("  + %s" % m["new"])
        try:
            code, out, err = run(m["cmd"])
            lines = summarize(out, err)
            for l in lines:
                print("  |", l)
            red = ("BUILD FAILURE" in out) or ("Failures: 0, Errors: 0" not in out and "Tests run:" in out)
            results.append((m["name"], m["target"], "RED" if code != 0 else "GREEN(没抓住!)", code))
            print("  ==> exit=%d  %s" % (code, "变红（抓住了）" if code != 0 else "仍然绿（没抓住！）"))
        finally:
            write(path, original)
            assert read(path) == original, "恢复失败: %s" % path
            print("  (已恢复原文, %d bytes)" % len(original))

    print("=" * 78)
    print("汇总：")
    for name, target, verdict, code in results:
        print("  %-46s -> %-14s (exit %d)  守的断言: %s" % (name[:46], verdict, code, target))
finally:
    for p in (REPORTER, HEALTH, TEST):
        pass
    print("=" * 78)
    print("收尾检查（git status 应为空/仅预期文件）：")
    code, out, err = run(["git", "status", "--short"])
    print(out)
