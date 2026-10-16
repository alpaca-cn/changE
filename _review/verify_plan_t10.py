"""Task 10 元约定核对：计划里的 java fence 与磁盘文件必须逐字一致。

方法：在计划文档的 Task 10 区段里抽取所有 ```java fence，
按 `package X;` 第一行加上类名推断出磁盘路径，然后逐字节比较。
同时检查 BOM / U+FFFD / CRLF 三项。
"""
import io
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

PLAN = r"E:\idea\program\change\docs\superpowers\plans\2026-10-14-change-01-foundation.md"
BACKEND = r"E:\idea\program\change\backend"

raw = open(PLAN, "rb").read()
print("plan 文件:")
print("  bytes      =", len(raw))
print("  有 BOM     =", raw.startswith(b"\xef\xbb\xbf"))
text = raw.decode("utf-8")
print("  U+FFFD 数  =", text.count("\ufffd"))
print("  CRLF 数    =", raw.count(b"\r\n"))

lines = text.splitlines()

# 定位 Task 10 区段
start = next(i for i, l in enumerate(lines) if l.startswith("## Task 10:"))
end = next(i for i, l in enumerate(lines) if l.startswith("## Task 11:"))
print("  Task 10 区段行号 = %d..%d (1-based)" % (start + 1, end))
section = lines[start:end]

files = {
    "PortReporter": os.path.join(BACKEND, r"src\main\java\com\change\config\PortReporter.java"),
    "HealthController": os.path.join(BACKEND, r"src\main\java\com\change\web\HealthController.java"),
    "CacheConfig": os.path.join(BACKEND, r"src\main\java\com\change\config\CacheConfig.java"),
    "HealthControllerTest": os.path.join(BACKEND, r"src\test\java\com\change\web\HealthControllerTest.java"),
}

fences = []
i = 0
while i < len(section):
    if section[i].strip() == "```java":
        j = i + 1
        body = []
        while j < len(section) and section[j].strip() != "```":
            body.append(section[j])
            j += 1
        fences.append((start + i + 2, body))  # 1-based 起始行
        i = j
    i += 1

print()
print("Task 10 区段里找到 %d 个 java fence" % len(fences))

all_ok = True
for lineno, body in fences:
    src = "\n".join(body) + "\n"
    cls = None
    for b in body:
        if b.startswith("public class "):
            cls = b.split()[2].split("{")[0].strip()
            break
    if cls is None:
        for b in body:
            if b.startswith("class "):
                cls = b.split()[1].split("{")[0].strip()
                break
    path = files.get(cls)
    if path is None:
        print("  [SKIP] 第 %d 行起的 fence：认不出类名 %r" % (lineno, cls))
        all_ok = False
        continue
    disk = io.open(path, "r", encoding="utf-8", newline="").read()
    same = disk == src
    print("  [%s] %-22s fence@L%-5d 类=%s" % ("OK " if same else "DIFF", os.path.basename(path), lineno, cls))
    if not same:
        all_ok = False
        d = disk.splitlines()
        s = src.splitlines()
        print("        磁盘 %d 行 / fence %d 行" % (len(d), len(s)))
        limit = max(len(d), len(s))
        shown = 0
        for k in range(limit):
            a = d[k] if k < len(d) else "<无>"
            b = s[k] if k < len(s) else "<无>"
            if a != b:
                print("        L%d 磁盘 : %r" % (k + 1, a))
                print("        L%d fence: %r" % (k + 1, b))
                shown += 1
                if shown >= 8:
                    print("        ... 只显示前 8 处")
                    break
    else:
        rb = open(path, "rb").read()
        print("        磁盘 bytes=%d  BOM=%s  U+FFFD=%d  CRLF=%d"
              % (len(rb), rb.startswith(b"\xef\xbb\xbf"),
                 rb.decode("utf-8").count("\ufffd"), rb.count(b"\r\n")))

print()
print("IDENTICAL =", all_ok)
sys.exit(0 if all_ok else 1)
