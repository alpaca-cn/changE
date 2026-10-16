#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 changE 的占位图标源图（1024×1024）。

**这是「图标从哪来」的答案。** 在它之前，源图是临时造的、放在 `%TEMP%`、
没有入库，于是「重新生成一次图标」这件事不可复现。现在源图是代码。

用法（在 `frontend/` 下）：

    python scripts/make-icon.py          # 产出 scripts/icon-source.png
    pnpm tauri icon scripts/icon-source.png   # 铺开成各平台的尺寸

然后**把 scripts/icon-source.png 一起提交**——它是可复现链条的输入。

设计说明：这张图刻意只是占位，不是 logo。它用主题里的两个值
（深色底 `#0d0f12` + 强调色 `#e0453a`）画一个红色圆环，用途是：

1. 让小尺寸下也认得出是这个应用（而不是一片纯色）；
2. 在深浅两种任务栏上都看得见（底是深的，环是亮的）。

真正的 logo 是以后的事：换掉这个脚本画的几何，重跑上面两条命令即可。
"""

import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover - 只在没装 Pillow 时走到
    sys.exit(
        "需要 Pillow：pip install Pillow\n"
        "（本仓库不需要把它写进 package.json——图标是手工生成一次就入库的产物，"
        "不是构建期依赖。）"
    )

SIZE = 1024

# 与 frontend/src/styles/theme.css 的 token 同值。刻意写死：图标是离线生成的
# 一次性产物，不该为了读两个色值去解析 CSS。
BG = "#0d0f12"
ACCENT = "#e0453a"

OUT = Path(__file__).resolve().parent / "icon-source.png"


def main() -> None:
    # 先画 4 倍大的图再缩下来 —— Pillow 的 draw 不做抗锯齿，
    # 直接按 1024 画圆环边缘会有明显的锯齿。
    scale = 4
    n = SIZE * scale
    img = Image.new("RGB", (n, n), BG)
    d = ImageDraw.Draw(img)

    # 红色圆环（居中）。外径 620/1024，环宽 96/1024，比例控制得粗一些，
    # 缩到 32×32 时还看得见一圈红。
    cx = cy = n // 2
    outer = int(620 / 1024 * n)
    ring = int(96 / 1024 * n)
    box = (cx - outer // 2, cy - outer // 2, cx + outer // 2, cy + outer // 2)
    d.ellipse(box, fill=ACCENT)

    # 内圈用底色挖空 —— 得到圆环。挖空而不是描边，是为了不依赖 line join 的行为。
    inner = outer - 2 * ring
    ibox = (cx - inner // 2, cy - inner // 2, cx + inner // 2, cy + inner // 2)
    d.ellipse(ibox, fill=BG)

    img = img.resize((SIZE, SIZE), Image.LANCZOS)
    img.save(OUT, "PNG", optimize=True)
    print(f"wrote {OUT} ({SIZE}x{SIZE}, {OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
