# -*- coding: utf-8 -*-
"""Scripted comparison: plan fences vs disk, checkboxes, encodings, diff scope."""
import hashlib, os, re, subprocess, sys

ROOT = r"E:\idea\program\change"
PLAN = os.path.join(ROOT, r"docs\superpowers\plans\2026-10-14-change-01-foundation.md")
SVC  = os.path.join(ROOT, r"backend\src\main\java\com\change\service\ChangeLogService.java")
TST  = os.path.join(ROOT, r"backend\src\test\java\com\change\service\ChangeLogServiceTest.java")

plan_bytes = open(PLAN, "rb").read()
plan = plan_bytes.decode("utf-8")

def enc_report(name, path):
    b = open(path, "rb").read()
    t = b.decode("utf-8")
    print(f"  {name:52} bytes={len(b):6} lines={len(t.splitlines()):4} "
          f"BOM={b[:3]==b'\xef\xbb\xbf'} U+FFFD={b.count(b'\xef\xbf\xbd'.replace(b'',b'')) if False else t.count(chr(0xFFFD))} "
          f"CRLF={b.count(b'\r\n')} CR={b.count(b'\r')} LF={b.count(b'\n')} sha256={hashlib.sha256(b).hexdigest()[:16]}")

print("=== A. encoding of plan and deliverables ===")
enc_report("plan", PLAN)
enc_report("ChangeLogService.java", SVC)
enc_report("ChangeLogServiceTest.java", TST)

print("\n=== B. locate Task 9 section and its java fences ===")
start = plan.index("## Task 9: ChangeLog")
nxt = plan.find("\n## Task 10", start)
end = nxt if nxt != -1 else len(plan)
section = plan[start:end]
print(f"  Task 9 section: chars {start}..{end} (len {len(section)})")
fences = [(m.start(), m.end()) for m in re.finditer(r"```java\r?\n(.*?)```", section, re.S)]
print(f"  java fences found in section: {len(fences)}")
disk_tst = open(TST, "r", encoding="utf-8", newline="").read()
disk_svc = open(SVC, "r", encoding="utf-8", newline="").read()
for i, (a, b) in enumerate(fences, 1):
    body = section[a:b]
    m = re.match(r"```java\r?\n(.*?)```", body, re.S)
    body = m.group(1)
    first = body.strip().splitlines()[0] if body.strip() else ""
    is_test = "class ChangeLogServiceTest" in body
    is_svc = "public class ChangeLogService" in body
    kind = "TEST" if is_test else ("SERVICE" if is_svc else "OTHER:" + first[:40])
    disk = disk_tst if is_test else (disk_svc if is_svc else None)
    if disk is None:
        print(f"  fence {i} ({kind}) -> not a deliverable file")
        continue
    same = body == disk
    print(f"  fence {i} ({kind}): fence_lines={len(body.splitlines())} disk_lines={len(disk.splitlines())} "
          f"fence_sha={hashlib.sha256(body.encode()).hexdigest()[:16]} disk_sha={hashlib.sha256(disk.encode()).hexdigest()[:16]} IDENTICAL={same}")
    if not same:
        fl, dl = body.split("\n"), disk.split("\n")
        for j in range(max(len(fl), len(dl))):
            x = fl[j] if j < len(fl) else "<EOF>"
            y = dl[j] if j < len(dl) else "<EOF>"
            if x != y:
                print(f"      first diff at line {j+1}:\n        fence: {x[:110]!r}\n        disk : {y[:110]!r}")
                break

print("\n=== C. Task 9 checkboxes vs Task 10+ ===")
def cb(txt):
    return len(re.findall(r"^\s*- \[x\]", txt, re.M)), len(re.findall(r"^\s*- \[ \]", txt, re.M))
t9x, t9e = cb(section)
print(f"  Task 9 : checked={t9x} unchecked={t9e}")
rest = plan[end:] if end > start else ""
rx, re_ = cb(rest)
print(f"  Task 10+ (to EOF): checked={rx} unchecked={re_}")
# also split per task heading for transparency
heads = [(m.start(), m.group(1)) for m in re.finditer(r"^## (Task \d+[^\n]*)", plan, re.M)]
for k, (pos, name) in enumerate(heads):
    stop = heads[k+1][0] if k+1 < len(heads) else len(plan)
    x, e = cb(plan[pos:stop])
    if name.startswith("Task 9") or name.startswith("Task 1") and "0" not in name:
        pass
    if re.match(r"Task (\d+)", name):
        num = int(re.match(r"Task (\d+)", name).group(1))
        if num >= 8:
            print(f"    {name[:46]:48} checked={x:3} unchecked={e:3}")

print("\n=== D. test method inventory (names + assertion count) ===")
tests = re.findall(r"@Test\s+(?:@\w+\s+)*void\s+(\w+)\s*\(\s*\)\s*\{", disk_tst)
print(f"  @Test methods ({len(tests)}):")
for t in tests:
    print("   -", t)
body_assert = len(re.findall(r"assertThat|assertThatThrownBy", disk_tst))
print(f"  assertThat/assertThatThrownBy call sites: {body_assert}")

print("\n=== E. git diff scope ===")
for args in (["diff", "--name-status", "1527987..3fef831"],
             ["diff", "--stat", "1527987..3fef831"],
             ["status", "--short"]):
    out = subprocess.run(["git"] + args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8").stdout
    print(f"  $ git {' '.join(args)}\n{out.rstrip()}")
print("\n  forbidden-path presence in 1527987..3fef831:")
out = subprocess.run(["git", "diff", "--name-only", "1527987..3fef831"], cwd=ROOT,
                     capture_output=True, text=True, encoding="utf-8").stdout.split()
for pat in ["db/migration", "application.yml", "pom.xml", "spike/", "calc/", "V1__", "V2__", "V3__", "V4__"]:
    hits = [f for f in out if pat in f]
    print(f"    {pat:18} -> {hits if hits else 'ABSENT'}")
