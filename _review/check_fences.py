# -*- coding: utf-8 -*-
"""Show every line of the plan whose first non-space run of backticks is exactly 3,
plus the running parity, to prove no fence is left unterminated."""
import re

PLAN = r"E:\idea\program\change\docs\superpowers\plans\2026-10-14-change-01-foundation.md"
lines = open(PLAN, encoding="utf-8").read().split("\n")
parity = 0
open_fence = None
suspicious = []
for i, line in enumerate(lines, 1):
    # Only a line whose FIRST characters are the fence opens/closes a block;
    # blockquote lines like "> ```java" are inside a quoted transcript.
    if line.startswith("```") or line.startswith("    ```"):
        stripped = line.strip()
        if stripped.count("```") == 1:
            parity ^= 1
            if parity:
                open_fence = (i, stripped)
            else:
                open_fence = None
        elif stripped.count("```") == 2:
            pass  # open+close on one line
        else:
            suspicious.append((i, stripped))
print("records whose first non-space run is ``` :", len(
    [1 for l in lines if l.lstrip().startswith("```")]))
print("real fence lines (line-start) :", len(
    [1 for l in lines if l.startswith("```") or l.startswith("    ```")]))
print("opening/closing lines (count(```)==1) :", len(
    [1 for l in lines if (l.startswith("```") or l.startswith("    ```"))
     and l.strip().count("```") == 1]))
print("final parity (0 = balanced) :", parity)
print("still-open fence at EOF      :", open_fence)
print("other ``` records            :", suspicious)
# where are the 3 remaining raw backticks?
total = sum(l.count("`") for l in lines)
triple = sum(l.count("```") for l in lines) * 3
print("total backtick chars:", total, " of which in ``` triples:", triple,
      " leftover single/double:", total - triple)
for i, line in enumerate(lines, 1):
    if "`" in line and not line.lstrip().startswith("```"):
        if re.search(r"`[^`]|`$", line) and line.count("`") % 2 == 1:
            pass
