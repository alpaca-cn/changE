# -*- coding: utf-8 -*-
"""把 18 页 PNG 拼成一张联系表，供快速总览。"""
import os
from PIL import Image

d = os.path.dirname(os.path.abspath(__file__))
png_dir = os.path.join(d, "render")
files = sorted(f for f in os.listdir(png_dir) if f.startswith("slide") and f.endswith(".png"))
imgs = [Image.open(os.path.join(png_dir, f)) for f in files]

cols, rows = 6, 3
tw, th = 400, 225
pad, gap = 16, 10
W = pad * 2 + cols * tw + (cols - 1) * gap
H = pad * 2 + rows * th + (rows - 1) * gap

sheet = Image.new("RGB", (W, H), (13, 15, 18))
for i, im in enumerate(imgs):
    im = im.convert("RGB").resize((tw, th), Image.LANCZOS)
    r, c = divmod(i, cols)
    sheet.paste(im, (pad + c * (tw + gap), pad + r * (th + gap)))

out = os.path.join(d, "contact-sheet.png")
sheet.save(out, optimize=True)
print("[ok]", out, f"{W}x{H}", f"{os.path.getsize(out):,} bytes", f"{len(imgs)} pages")
