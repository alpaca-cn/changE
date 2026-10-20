#!/usr/bin/env python3
"""从 vendored 素材里抽出区域 id，生成 region-ids.ts。

为什么要有这个脚本：素材（`data/muscles.front.ts` / `data/muscles.back.ts`）是
区域 id 的唯一权威。手抄 89 个 id 到一份 TypeScript 常量里，几乎一定会错一个；
而错的那一个不会报错——前端拿 id 去 SVG 里找不到同 id 的 path，那块肌肉只是
永远灰着，没人会注意到。

用法（在仓库根目录执行）：

    python frontend/scripts/generate-region-ids.py

它读：

    frontend/src/assets/body-map/data/muscles.front.ts
    frontend/src/assets/body-map/data/muscles.back.ts

只匹配每行形如 `id: "chest-upper-left",` 的字面量，按文件内出现顺序输出：

    frontend/src/assets/body-map/region-ids.ts

生成前会断言正面 40 / 背面 49 / 合计 89 且三者无重复；数字对不上时直接失败，
不会写出一份「看起来生成了」的坏文件。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# frontend/scripts/generate-region-ids.py -> 仓库根目录
ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "frontend" / "src" / "assets" / "body-map" / "data"
OUT = ROOT / "frontend" / "src" / "assets" / "body-map" / "region-ids.ts"

# 只认 `id: "..."`，且允许行首缩进；素材里每一行都是这个形状。
ID_PATTERN = re.compile(r'^\s*id:\s*"([^"]+)",\s*$', re.MULTILINE)

EXPECTED_FRONT = 40
EXPECTED_BACK = 49
EXPECTED_TOTAL = EXPECTED_FRONT + EXPECTED_BACK


def extract(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    ids = ID_PATTERN.findall(text)
    if not ids:
        raise SystemExit(f"没有从 {path} 里抽到任何 `id: \"...\"`")
    bad = [i for i in ids if not re.fullmatch(r"[a-z0-9-]+", i)]
    if bad:
        raise SystemExit(f"{path.name} 里有不符合 [a-z0-9-] 的 id: {bad}")
    return ids


def render(front: list[str], back: list[str]) -> str:
    lines = [
        "// 从 vendored 的 muscles.front.ts / muscles.back.ts 里**脚本抽出**的 id 清单。",
        "// 不要手改：改素材就重新生成。手抄 89 个 id 一定会错一个，",
        "// 而错了的那一个会让某块肌肉永远灰着。",
        "//",
        "// 生成方式（在仓库根目录执行）：",
        "//   python frontend/scripts/generate-region-ids.py",
        "//",
        "// 脚本直接读 data/muscles.{front,back}.ts 里 `id: \"...\"` 的字面量，按文件内",
        "// 出现顺序输出；同时断言正面 40 / 背面 49 / 合计 89 且无重复。素材升级后",
        "// 重跑本脚本，再跑前端测试与后端 MuscleRegionMappingTest 做交叉校验。",
        "",
        "export const FRONT_REGION_IDS = [",
    ]
    lines += [f'  "{i}",' for i in front]
    lines += [
        "] as const",
        "",
        "export const BACK_REGION_IDS = [",
    ]
    lines += [f'  "{i}",' for i in back]
    lines += [
        "] as const",
        "",
        "export const ALL_REGION_IDS = [...FRONT_REGION_IDS, ...BACK_REGION_IDS] as const",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    front = extract(DATA_DIR / "muscles.front.ts")
    back = extract(DATA_DIR / "muscles.back.ts")

    problems: list[str] = []
    if len(front) != EXPECTED_FRONT:
        problems.append(f"正面 {len(front)} 个区域（应为 {EXPECTED_FRONT}）")
    if len(back) != EXPECTED_BACK:
        problems.append(f"背面 {len(back)} 个区域（应为 {EXPECTED_BACK}）")
    if len(front) + len(back) != EXPECTED_TOTAL:
        problems.append(f"合计 {len(front) + len(back)} 个区域（应为 {EXPECTED_TOTAL}）")

    duplicates = sorted({i for i in front if front.count(i) > 1}
                        | {i for i in back if back.count(i) > 1}
                        | (set(front) & set(back)))
    if duplicates:
        problems.append(f"重复的 id: {duplicates}")

    if problems:
        print("拒绝生成 region-ids.ts：", file=sys.stderr)
        for p in problems:
            print("  - " + p, file=sys.stderr)
        return 1

    content = render(front, back)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)

    # 落盘后读回来比对：编码 / 换行 / 路径写错时立刻失败，而不是留下坏文件。
    if OUT.read_text(encoding="utf-8") != content:
        print(f"写入后读回的内容不一致: {OUT}", file=sys.stderr)
        return 1

    print(f"已生成 {OUT.relative_to(ROOT)}：正面 {len(front)} / 背面 {len(back)} / "
          f"合计 {len(front) + len(back)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
