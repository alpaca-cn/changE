# -*- coding: utf-8 -*-
"""Final (sixth) pass: refresh the embedded acceptance-1 block and the self-check
transcript to the values measured after the plan edit, and state the self-reference
caveat. Deliberately the last edit to the plan."""
import hashlib

PLAN = r"E:\idea\program\change\docs\superpowers\plans\2026-10-14-change-01-foundation.md"
plan = open(PLAN, "rb").read().decode("utf-8")

reps = [
    # acceptance-1 transcript (re-measured after the plan edit; code unchanged)
    ("Time elapsed: 0.123 s -- in com.change.calc.NutritionCalculatorTest",
     "Time elapsed: 0.172 s -- in com.change.calc.NutritionCalculatorTest"),
    ("Time elapsed: 0.033 s -- in com.change.calc.OneRepMaxTest",
     "Time elapsed: 0.033 s -- in com.change.calc.OneRepMaxTest"),
    ("Time elapsed: 0.040 s -- in com.change.calc.PortionConverterTest",
     "Time elapsed: 0.038 s -- in com.change.calc.PortionConverterTest"),
    ("Time elapsed: 0.024 s -- in com.change.calc.VolumeCalculatorTest",
     "Time elapsed: 0.025 s -- in com.change.calc.VolumeCalculatorTest"),
    ("Time elapsed: 4.754 s -- in com.change.ChangeApplicationTests",
     "Time elapsed: 4.647 s -- in com.change.ChangeApplicationTests"),
    ("Time elapsed: 0.024 s -- in com.change.config.DataDirectoryInitializerTest",
     "Time elapsed: 0.025 s -- in com.change.config.DataDirectoryInitializerTest"),
    ("Time elapsed: 0.057 s -- in com.change.MigrationTest",
     "Time elapsed: 0.063 s -- in com.change.MigrationTest"),
    ("Time elapsed: 0.202 s -- in com.change.repo.TrainingRepoTest",
     "Time elapsed: 0.220 s -- in com.change.repo.TrainingRepoTest"),
    ("Time elapsed: 0.070 s -- in com.change.SchemaTest",
     "Time elapsed: 0.056 s -- in com.change.SchemaTest"),
    ("Time elapsed: 0.145 s -- in com.change.service.ChangeLogServiceTest",
     "Time elapsed: 0.122 s -- in com.change.service.ChangeLogServiceTest"),
    ("Time elapsed: 0.584 s -- in com.change.service.ChangeLogServiceUndoTransactionTest",
     "Time elapsed: 0.528 s -- in com.change.service.ChangeLogServiceUndoTransactionTest"),
    ("[INFO] Total time:  11.448 s", "[INFO] Total time:  11.198 s"),
    # self-check transcript: plan's own numbers + F note
    ("  plan                                     bytes= 384538 lines=6763 BOM=False U+FFFD=0 CRLF=0 CR=0 LF=6763 sha256=56293f74bcd07b47",
     "  plan                                     bytes= 384935 lines=6768 BOM=False U+FFFD=0 CRLF=0 CR=0 LF=6768 sha256=c903c8b8537c0f2a"),
]
for old, new in reps:
    n = plan.count(old)
    assert n == 1, (n, old[:70])
    plan = plan.replace(old, new, 1)
print(f"refreshed {len(reps)} values")

old_caveat = ("> ⚠️ 上面 A 段里 plan 自己的 `bytes` / `lines` / `sha256` 是**写本节之前**那一次比对的值：\n"
              "> 本节一写进去，plan 自身的哈希就变了（自指）。要复现，跑\n"
              "> `python _review/verify_plan_t9.py` 看当时的实际值——**两个 java fence 的 IDENTICAL=True\n"
              "> 与两份源文件的 sha256** 才是这一节的结论，它们不受自指影响。")
new_caveat = ("> ⚠️ 上面 A 段里 plan 自己的 `bytes` / `lines` / `sha256` 是**本节定稿前**那一次比对的值：\n"
              "> 本节一写进去，plan 自身的哈希就变了（自指，无法收敛）。这一节的结论是\n"
              "> **两个 java fence 的 IDENTICAL=True**、**两份源文件的 sha256**、BOM/U+FFFD/CRLF 三项全 0——\n"
              "> 这些都不受自指影响（plan 那一行只是当时的事实快照）。要复现，跑\n"
              "> `python _review/verify_plan_t9.py`。")
assert plan.count(old_caveat) == 1
plan = plan.replace(old_caveat, new_caveat, 1)

out = plan.encode("utf-8")
assert b"\r\n" not in out and not out.startswith(b"\xef\xbb\xbf") and b"\xef\xbf\xbd" not in out
open(PLAN, "wb").write(out)
print("plan finalized:", len(out), "bytes sha", hashlib.sha256(out).hexdigest()[:16])
