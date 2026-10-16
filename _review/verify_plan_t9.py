# -*- coding: utf-8 -*-
"""
Acceptance 7 self-check for the plan document after the security-review round.

- encoding: BOM / U+FFFD / CRLF
- Task 9 java fences vs disk, byte-exact
- the ## 回退的语义（契约） block exists on disk and inside the plan's service fence
- checkboxes: Task 9 all checked, Task 10+ all unchecked
- concern 6 recorded in the plan
- no fence is left unterminated
"""
import hashlib
import os
import re

ROOT = r"E:\idea\program\change"
PLAN = os.path.join(ROOT, r"docs\superpowers\plans\2026-10-14-change-01-foundation.md")
SVC = os.path.join(ROOT, r"backend\src\main\java\com\change\service\ChangeLogService.java")
TST = os.path.join(ROOT, r"backend\src\test\java\com\change\service\ChangeLogServiceTest.java")
TXT = os.path.join(ROOT, r"backend\src\test\java\com\change\service"
                          r"\ChangeLogServiceUndoTransactionTest.java")

print("=== A. encoding ===")
for name, path in (("plan", PLAN), ("ChangeLogService.java", SVC),
                   ("ChangeLogServiceTest.java", TST),
                   ("ChangeLogServiceUndoTransactionTest.java", TXT)):
    b = open(path, "rb").read()
    t = b.decode("utf-8")
    print(f"  {name:40} bytes={len(b):7} lines={len(t.splitlines()):4} "
          f"BOM={b[:3] == b'\xef\xbb\xbf'} U+FFFD={t.count(chr(0xFFFD))} "
          f"CRLF={b.count(b'\r\n')} CR={b.count(b'\r')} LF={b.count(b'\n')} "
          f"sha256={hashlib.sha256(b).hexdigest()[:16]}")

plan_b = open(PLAN, "rb").read()
plan = plan_b.decode("utf-8")

print("\n=== B. Task 9 java fences vs disk (byte-exact) ===")
start = plan.index("## Task 9:")
end = plan.index("## Task 10:")
section = plan[start:end]
lines = section.split("\n")
fences = []
i = 0
while i < len(lines):
    if lines[i].strip() == "```java":
        j = i + 1
        while lines[j].strip() != "```":
            j += 1
        fences.append("\n".join(lines[i + 1:j]))
        i = j + 1
    else:
        i += 1
print("  java fences in Task 9:", len(fences))
disk = {"ChangeLogServiceTest": open(TST, "rb").read().decode("utf-8").rstrip("\n"),
        "ChangeLogService": open(SVC, "rb").read().decode("utf-8").rstrip("\n")}
ok = True
for f in fences:
    if "class ChangeLogServiceTest" in f:
        kind = "ChangeLogServiceTest"
    elif "public class ChangeLogService" in f:
        kind = "ChangeLogService"
    else:
        kind = "?"
    same = f == disk.get(kind)
    ok &= same
    print(f"  {kind:24} fence_lines={len(f.splitlines()):4} "
          f"disk_lines={len(disk.get(kind, '').splitlines()):4} "
          f"fence_sha={hashlib.sha256(f.encode()).hexdigest()[:16]} "
          f"disk_sha={hashlib.sha256(disk.get(kind, '').encode()).hexdigest()[:16]} "
          f"IDENTICAL={same}")
    if not same:
        fl, dl = f.split("\n"), disk[kind].split("\n")
        for k in range(max(len(fl), len(dl))):
            x = fl[k] if k < len(fl) else "<EOF>"
            y = dl[k] if k < len(dl) else "<EOF>"
            if x != y:
                print(f"      first diff line {k+1}:\n        fence: {x[:100]!r}\n"
                      f"        disk : {y[:100]!r}")
                break
print("  ALL FENCES IDENTICAL =", ok)

print("\n=== C. 契约 (contract) present on disk and inside the plan's service fence ===")
svc_text = open(SVC, "rb").read().decode("utf-8")
need = ["## 回退的语义（契约）",
        "update 的回退是按列写回",
        "列名必须真实存在于目标表且不是主键",
        "撤销一个被外键 CASCADE 引用的父行会连带删掉子行",
        "动作编辑功能落地时必须扩展快照（记下 muscle_map）或改为整组重建 join 行"]
svc_fence = next(f for f in fences if "public class ChangeLogService" in f)
for n in need:
    print(f"  {'OK ' if (n in svc_text and n in svc_fence) else 'MISS'}  {n[:48]}")

print("\n=== D. checkboxes ===")


def cb(txt):
    return (len(re.findall(r"^\s*- \[x\]", txt, re.M)),
            len(re.findall(r"^\s*- \[ \]", txt, re.M)))


t9x, t9e = cb(section)
print(f"  Task 9 section   : checked={t9x} unchecked={t9e}")
print(f"  Task 10+ (to EOF): checked={cb(plan[end:])[0]} unchecked={cb(plan[end:])[1]}")
for m in re.finditer(r"^## Task (\d+)[^\n]*", plan, re.M):
    num = int(m.group(1))
    if num >= 8:
        stop = plan.find("\n## ", m.end())
        stop = stop if stop != -1 else len(plan)
        x, e = cb(plan[m.start():stop])
        print(f"    Task {num:<3} checked={x:3} unchecked={e:3}")

print("\n=== E. concern 6 recorded ===")
for n in ["concern 6", "after undo = 0", "既不在日志里、也回不来",
          "6.7 的 concern 6 与 6.8"]:
    print(f"  {'OK ' if n in plan else 'MISS'}  {n}")

print("\n=== F. fences balanced / markdown sanity ===")
print("  ``` count in plan :", plan.count("```"), "(must be even)")
print("  ### task headings :", len(re.findall(r"^## Task \d+", plan, re.M)))
print("  NEW sections 6.7/6.8 present:", "#### 6.7 " in plan, "#### 6.8 " in plan)
print("  last line of file :", repr(plan.splitlines()[-1][:60]))
