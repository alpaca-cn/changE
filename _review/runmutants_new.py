# -*- coding: utf-8 -*-
"""
Re-verify the four NEW defences by mutation: each mutant must be KILLED.

For every mutant: apply one literal text replacement to the disk file (asserting the
anchor is unique, so a silent partial edit is impossible), delete backend/target,
run the four ChangeLog tests, record the verdict, then restore the original bytes and
prove the restore with sha256.

Usage: python runmutants_new.py
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys

ROOT = r"E:\idea\program\change"
BACKEND = os.path.join(ROOT, "backend")
SRC = os.path.join(BACKEND, r"src\main\java\com\change\service\ChangeLogService.java")
TARGET = os.path.join(BACKEND, "target")

MVN = shutil.which("mvn") or r"mvn"

MUTANTS = [
    dict(
        id="MM1",
        desc="删掉 schema 校验（writableColumns）——退回只靠标识符正则",
        old="""                    if (!writable.contains(column)) {
                        throw new IllegalArgumentException(
                                "列名「" + column + "」不是 " + table + " 上可回退写入的列"
                                        + "（必须真实存在且不是主键——SQLite 的 rowid/_rowid_/oid 是主键别名）");
                    }""",
        new="""                    if (false) {
                        throw new IllegalArgumentException("列名「" + column + "」");
                    }""",
    ),
    dict(
        id="MM2",
        desc="删掉 affected == 0 检查（影响 0 行也照样置 undone=1）",
        old="""        if (affected == 0) {""",
        new="""        if (false) {""",
    ),
    dict(
        id="MM3",
        desc="删掉 undo 上的 @Transactional",
        old="""    @Transactional
    public void undo(long logId) {""",
        new="""    public void undo(long logId) {""",
    ),
    dict(
        id="MM4",
        desc="删掉外键翻译（改成一条永不触发的 catch，原样抛出）",
        old="""        } catch (DataAccessException e) {""",
        new="""        } catch (java.lang.ArithmeticException impossible) {
            throw new IllegalStateException("never", impossible);
        } catch (DataAccessException e) {
            if (true) {
                throw e;
            }""",
    ),
]

TEST_FILTER = "ChangeLogServiceTest,ChangeLogServiceUndoTransactionTest"


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def run_maven():
    env = dict(os.environ)
    env["JAVA_HOME"] = r"C:\Users\alpaca\.jdks\openjdk-25.0.2"
    old_path = env.get("Path") or env.get("PATH") or ""
    env["Path"] = env["JAVA_HOME"] + r"\bin;" + old_path
    env["PATH"] = env["Path"]
    proc = subprocess.run(
        [MVN, "-B", "test", "-Dtest=" + TEST_FILTER, "-DfailIfNoSpecifiedTests=false"],
        cwd=BACKEND, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def summarise(raw):
    totals = re.findall(
        r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$",
        raw, re.M)
    failed = re.findall(r"^\[ERROR\]\s+(com\.change\.\S+)\s", raw, re.M)
    perclass = re.findall(
        r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+).*-- in (\S+)", raw)
    return dict(total=totals[-1] if totals else None,
                perclass=perclass,
                failed=sorted(set(failed)))


def main():
    orig_bytes = open(SRC, "rb").read()
    orig_text = orig_bytes.decode("utf-8")
    orig_hash = hashlib.sha256(orig_bytes).hexdigest()
    print("== original sha256 =", orig_hash)
    results = []
    try:
        for m in MUTANTS:
            n = orig_text.count(m["old"])
            if n != 1:
                print(f"{m['id']} ANCHOR-FAIL: {n} hits for the replacement text")
                results.append((m["id"], "ANCHOR-FAIL", m["desc"], {}))
                continue
            mutated = orig_text.replace(m["old"], m["new"], 1)
            assert mutated != orig_text
            open(SRC, "wb").write(mutated.encode("utf-8"))
            shutil.rmtree(TARGET, ignore_errors=True)
            code, raw = run_maven()
            info = summarise(raw)
            verdict = "KILLED" if code != 0 else "SURVIVED"
            print(f"\n### {m['id']} -> {verdict}   ({m['desc']})")
            print("    exit code:", code)
            print("    totals   :", info["total"])
            for pc in info["perclass"]:
                print(f"    per-class: Tests run: {pc[0]}, Failures: {pc[1]}, "
                      f"Errors: {pc[2]}, Skipped: {pc[3]} -- in {pc[4]}")
            for f in info["failed"][:8]:
                print("    failed   :", f)
            results.append((m["id"], verdict, m["desc"], info))
            open(SRC, "wb").write(orig_bytes)
            back = sha(SRC)
            print("    RESTORED ok =", back == orig_hash, "sha =", back[:16])
    finally:
        open(SRC, "wb").write(orig_bytes)

    print("\n== summary ==")
    for mid, verdict, desc, info in results:
        tot = info.get("total")
        print(f"  {mid}: {verdict:12} totals={tot}  {desc}")
    print("final sha =", sha(SRC)[:16], "identical =", sha(SRC) == orig_hash)


if __name__ == "__main__":
    sys.exit(main())
