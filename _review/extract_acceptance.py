# -*- coding: utf-8 -*-
"""Extract the acceptance-run summary lines from the full mvn log."""
import re, sys

LOG = r"E:\idea\program\change\_review\acceptance1_full.log"
OUT = r"E:\idea\program\change\_review\acceptance1_summary.txt"
raw = open(LOG, "rb").read()
# PowerShell's `>` redirect writes UTF-16LE with BOM — decode accordingly.
text = raw.decode("utf-16" if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else "utf-8")
lines = text.splitlines()
keep = [l for l in lines
        if re.search(r"Tests run:|BUILD |Total time|Building changE", l)]
open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(keep))
print(f"kept {len(keep)} lines -> {OUT}")
