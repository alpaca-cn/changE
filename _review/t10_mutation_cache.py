"""Task 10 / D 续：专攻最弱断言。

问题：health_reports_ok_without_touching_redis 里的
      assertThat(r.getBody()).contains("\"cache\"")
只查**键名存在**，不查值。造两个变异体看它能不能抓住：
  1. cacheStatus() 返回空串  -> JSON 变成 "cache":""，键还在
  2. cacheStatus() 返回假值   -> JSON 值完全不对，键还在
两个变异体下，只有「查值」的断言才能杀掉它们。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

BACKEND = r"E:\idea\program\change\backend"
MVN = r"E:\maven\apache-maven-3.9.4-bin\apache-maven-3.9.4\bin\mvn.cmd"
ENV = dict(os.environ)
ENV["JAVA_HOME"] = r"C:\Users\alpaca\.jdks\openjdk-25.0.2"
_u = {k.upper(): v for k, v in ENV.items()}
ENV["Path"] = ENV["JAVA_HOME"] + r"\bin;" + _u.get("PATH", "")
ENV["PATH"] = ENV["Path"]

CACHE = os.path.join(BACKEND, r"src\main\java\com\change\config\CacheConfig.java")

MUTANTS = [
    ("cache 的值为空串（键还在）", 'return () -> "disabled(placeholder)";', 'return () -> "";'),
    ("cache 的值换成假状态（键还在）", 'return () -> "disabled(placeholder)";',
     'return () -> "缓存：已连接 1.2.3.4:6379";'),
    ("没有 CacheStatus bean（健康端点也就不该起来）",
     '''    @Bean
    public HealthController.CacheStatus cacheStatus() {
        return () -> "disabled(placeholder)";
    }''',
     '''    public String nothing() {
        return "x";
    }'''),
]


def read(p):
    with io.open(p, "rb") as f:
        return f.read()


def write(p, b):
    with open(p, "wb") as f:
        f.write(b)


original = read(CACHE)
try:
    for name, old, new in MUTANTS:
        text = original.decode("utf-8")
        if old is None:
            # 把 @Bean 方法体掏空 -> 没有 CacheStatus bean，HealthController 起不来
            mutated = text
        else:
            assert text.count(old) == 1, name
            mutated = text.replace(old, new)
        write(CACHE, mutated.encode("utf-8"))
        print("=" * 74)
        print("变异体:", name)
        print("  命令: mvn -B test -Dtest=HealthControllerTest")
        try:
            p = subprocess.run([MVN, "-B", "test", "-Dtest=HealthControllerTest"],
                               cwd=BACKEND, env=ENV, capture_output=True)
            out = p.stdout.decode("utf-8", "replace")
            for line in out.splitlines():
                s = line.strip()
                if ("Tests run:" in s and ("[INFO]" in s or "[ERROR]" in s)) or s.startswith("[INFO] BUILD"):
                    print("  |", s)
            # 额外：把返回体里的 cache 值打出来
            print("  ==> exit=%d %s" % (p.returncode, "变红（抓住了）" if p.returncode else "仍然绿（没抓住！）"))
        finally:
            write(CACHE, original)
finally:
    write(CACHE, original)
    assert read(CACHE) == original
    print("=" * 74)
    print("CacheConfig.java 已恢复原文，%d bytes" % len(original))
