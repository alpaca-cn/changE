#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 seed/instructions-zh/part-*.json 合并成 seed/instructions-zh.json。

用法（在仓库根目录执行；只用标准库，不需要装任何东西）：

    python scripts/merge-instructions-zh.py

**产物是生成的**：要改译文请改 `part-*.json` 再重跑这条命令，不要手改
`instructions-zh.json`——手改会在下次合并时被覆盖，是静默丢改动。

合并前依次校验（任何一条不通过都**不写产物**）：

1. **part 文件的形状**：恰好 `batch` / `from` / `to` / `items` 四个字段；
   `batch` 是整数且与文件名 `part-NN.json` 的编号一致；`from`/`to` 是 1-based 闭区间整数；
   `items` 是 `{动作英文名: [中文段, ...]}`。
2. **批边界**：`items` 的键集合必须正好是「数据集顺序里第 from..to 条」中**有英文原文**的那些。
   多一条、少一条都报错并列出差异（以及多出来的那条在数据集里是第几条）。
3. **重复键**：同一个动作不能出现在两个 part 里（否则后一批会静默覆盖前一批）。
4. **段数**：每条的段数必须与英文原文一致。少一段 = 丢一步做法，而页面上看不出来。
   英文原文那一段是空的时，中文也必须留空（不增不减）。
5. **覆盖**：被所有 part 的 `[from, to]` 并集认领的下标，必须构成 **从第 1 条开始、
   中间没有洞**的连续前缀。前缀之外还没认领的只是「还没翻完」（退出码 2，正常中间状态）；
   中间有洞、或者被多个批次重叠认领，是错误（退出码 1）。前缀一直延伸到第 876 条
   才算翻完（退出码 0），此时所有有原文的动作都在产物里。

英文原文读仓库里 vendor 好的那一份：

    backend/src/main/resources/seed/free-exercise-db/exercises.json

**不要**改成读开发机上的临时缓存——那个目录随时可能被清掉，而且把一个绝对的开发机路径
写进要提交的脚本里，换台机器就跑不了。

退出码：

    0  全部校验通过，且 1..876 全部被认领（翻完了），产物已写入
    1  校验失败，未写产物（part 形状 / 批边界 / 重复键 / 段数 / 空段 / 范围中间有洞 /
       范围重叠；输出会指出哪一批、哪一条、差多少）
    2  校验通过但还没翻完，未写产物——已认领的批次范围构成 1..k（k < 876）的连续前缀，
       或还没有任何 part-NN.json（翻译进行中这是正常状态）
"""

import json
import re
import sys
from pathlib import Path

# Windows 控制台默认按 GBK 编码，中文 print 会直接抛 UnicodeEncodeError。
# 这个脚本的输出会被贴进交接报告，所以强制 UTF-8 而不是依赖环境变量。
for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8")
    except AttributeError:  # pragma: no cover - 只在极老的 Python 上走不到
        pass

REPO_ROOT = Path(__file__).resolve().parent.parent
SEED_DIR = REPO_ROOT / "backend" / "src" / "main" / "resources" / "seed"
DATASET_PATH = SEED_DIR / "free-exercise-db" / "exercises.json"
PARTS_DIR = SEED_DIR / "instructions-zh"
OUTPUT_PATH = SEED_DIR / "instructions-zh.json"

# 两位数字是为了让「按文件名排序」与「按批次号排序」一致（part-2 与 part-10 的字典序陷阱）。
PART_FILE_NAME = re.compile(r"part-(\d{2})\.json\Z")

REQUIRED_FIELDS = ("batch", "from", "to", "items")


class Part:
    """一个批次文件。字段已通过形状校验，可以直接用。"""

    def __init__(self, name, batch, start, end, items):
        self.name = name
        self.batch = batch
        self.start = start
        self.end = end
        self.items = items


def load_dataset():
    """读数据集，返回 (按数据集顺序的名字列表, {名字: [英文段, ...]})。"""
    if not DATASET_PATH.is_file():
        sys.exit("找不到数据集：%s" % DATASET_PATH)

    try:
        with DATASET_PATH.open(encoding="utf-8") as handle:
            records = json.load(handle)
    except ValueError as error:
        sys.exit("数据集不是合法 JSON：%s（%s）" % (DATASET_PATH, error))

    if not isinstance(records, list):
        sys.exit("数据集顶层应当是一个 JSON 数组：%s" % DATASET_PATH)

    names = []
    paragraphs = {}
    for index, record in enumerate(records, 1):
        if not isinstance(record, dict) or not isinstance(record.get("name"), str):
            sys.exit("数据集第 %d 条缺少 name：%s" % (index, DATASET_PATH))
        name = record["name"]
        if name in paragraphs:
            # 动作名是 part 文件的键，重名就再也分不清哪条是哪条。
            sys.exit("数据集里动作名重复：%r（第 %d 条）" % (name, index))

        instructions = record.get("instructions")
        if instructions is None:
            instructions = []
        if not isinstance(instructions, list) or not all(isinstance(p, str) for p in instructions):
            sys.exit("数据集第 %d 条（%r）的 instructions 应当是字符串数组" % (index, name))

        names.append(name)
        paragraphs[name] = instructions

    return names, paragraphs


def read_part(path, errors):
    """读并校验一个 part 文件的形状。形状不对时记错误并返回 None。"""
    duplicates = []

    def collect(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                # json 默认会静默丢掉同名的前一个，那样「少一条」看起来只是批边界不符。
                duplicates.append(key)
            result[key] = value
        return result

    try:
        data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=collect)
    except (OSError, ValueError) as error:
        errors.append(("part 文件形状", "%s 读不出来：%s" % (path.name, error)))
        return None

    if duplicates:
        errors.append(("part 文件形状", "%s 的 JSON 里有重复的键：%r" % (path.name, duplicates)))
        return None

    if not isinstance(data, dict):
        errors.append(("part 文件形状", "%s 顶层应当是一个 JSON 对象" % path.name))
        return None

    missing = [field for field in REQUIRED_FIELDS if field not in data]
    if missing:
        errors.append(("part 文件形状", "%s 缺少字段：%s" % (path.name, missing)))
        return None
    unexpected = [field for field in data if field not in REQUIRED_FIELDS]
    if unexpected:
        # 故意不放过未知字段：把 "fromm" 之类的笔误当注释忽略，批边界就会悄悄退回散文。
        errors.append(("part 文件形状", "%s 有不认识的字段：%s（只允许 %s）"
                       % (path.name, unexpected, list(REQUIRED_FIELDS))))
        return None

    match = PART_FILE_NAME.match(path.name)
    if match is None:
        errors.append(("part 文件形状", "%s 的文件名应当形如 part-NN.json（两位数字）" % path.name))
        return None
    file_batch = int(match.group(1))

    batch = data["batch"]
    if not isinstance(batch, int) or isinstance(batch, bool):
        errors.append(("part 文件形状", "%s 的 batch 应当是整数，实际是 %r" % (path.name, batch)))
        return None
    if batch != file_batch:
        errors.append(("part 文件形状", "%s 的 batch=%d 与文件名里的编号 %d 不一致"
                       % (path.name, batch, file_batch)))
        return None

    start, end = data["from"], data["to"]
    for field, value in (("from", start), ("to", end)):
        if not isinstance(value, int) or isinstance(value, bool):
            errors.append(("part 文件形状", "%s 的 %s 应当是整数，实际是 %r"
                           % (path.name, field, value)))
            return None
    if start < 1 or end < start:
        errors.append(("part 文件形状", "%s 的 from=%d / to=%d 不是合法的 1-based 闭区间"
                       % (path.name, start, end)))
        return None

    items = data["items"]
    if not isinstance(items, dict):
        errors.append(("part 文件形状", "%s 的 items 应当是对象 {动作名: [中文段]}" % path.name))
        return None
    for name, segments in items.items():
        if not isinstance(segments, list) or not all(isinstance(s, str) for s in segments):
            errors.append(("part 文件形状", "%s: %r 的值应当是字符串数组" % (path.name, name)))
            return None

    return Part(path.name, batch, start, end, items)


def check_batch_boundary(part, names, paragraphs, errors):
    """批边界：这批的键集合必须正好是数据集第 from..to 条里有英文原文的那些。"""
    if part.end > len(names):
        errors.append(("part 文件形状", "%s 的 to=%d 超出数据集范围（共 %d 条）"
                       % (part.name, part.end, len(names))))
        return

    index_of = {}
    expected = []
    without_source = []
    for index in range(part.start, part.end + 1):
        name = names[index - 1]
        index_of[name] = index
        if paragraphs[name]:
            expected.append(name)
        else:
            without_source.append(name)

    actual = list(part.items)
    expected_set = set(expected)
    actual_set = set(actual)
    missing = [name for name in expected if name not in actual_set]
    extra = [name for name in actual if name not in expected_set]

    if not missing and not extra:
        return

    details = []
    if missing:
        details.append("缺 %d 条：%s" % (len(missing), ", ".join(repr(n) for n in missing)))
    if extra:
        labelled = []
        for name in extra:
            index = index_of.get(name)
            if index is None:
                labelled.append("%r（不在第 %d..%d 条里）" % (name, part.start, part.end))
            else:
                labelled.append("%r（数据集第 %d 条）" % (name, index))
        details.append("多 %d 条：%s" % (len(extra), ", ".join(labelled)))

    note = ""
    if without_source:
        note = "（该范围里有 %d 条英文原文为空：%s，不参与）" % (
            len(without_source), ", ".join(repr(n) for n in without_source))

    errors.append(("批边界不符", "%s: 第 %d..%d 条应包含 %d 条，实际 %d 条%s；%s"
                   % (part.name, part.start, part.end, len(expected), len(actual),
                      note, "；".join(details))))


def check_no_empty_source_items(part, without_source, errors):
    """英文原文为空的动作没有可翻的东西，不该出现在 part 文件里。"""
    bad = [name for name in part.items if name in without_source]
    if bad:
        errors.append(("原文为空", "%s 里有 %d 条英文原文为空（%s）：没有可翻的内容，"
                       "它们不该出现在 part 文件里（也不进产物）"
                       % (part.name, len(bad), ", ".join(repr(n) for n in bad))))


def check_paragraph_counts(part, paragraphs, errors):
    """段数必须与英文原文一一对应；英文空段处中文也必须留空。"""
    for name, segments in part.items.items():
        source = paragraphs.get(name)
        if source is None:
            continue  # 数据集中不存在：由批边界那一类错误来报
        if len(segments) != len(source):
            errors.append(("段数不符", "%s: %r 期望 %d 段，实际 %d 段"
                           % (part.name, name, len(source), len(segments))))
            continue
        for number, (english, chinese) in enumerate(zip(source, segments), 1):
            if english.strip() and not chinese.strip():
                errors.append(("空段", "%s: %r 第 %d 段是空的，而英文原文这一段有内容"
                               % (part.name, name, number)))
            elif not english.strip() and chinese.strip():
                errors.append(("空段", "%s: %r 第 %d 段：英文原文这段是空的，中文也应留空（不增不减）"
                               % (part.name, name, number)))


def check_duplicate_keys(part, seen, errors):
    """同一个动作不能出现在两个 part 里。"""
    for name in part.items:
        owner = seen.get(name)
        if owner is None:
            seen[name] = part.name
        else:
            errors.append(("重复键", "%r 同时出现在 %s 与 %s" % (name, owner, part.name)))


def describe_index(index, names):
    """把数据集下标写成「第 N 条（'动作名'）」；超出范围时不要抛 IndexError。"""
    if 1 <= index <= len(names):
        return "第 %d 条（%r）" % (index, names[index - 1])
    return "第 %d 条（超出数据集范围）" % index


def format_ranges(indices):
    """把离散下标压成连续区间：`第 3–4 条、第 9 条`。"""
    ranges = []
    for index in indices:
        if ranges and index == ranges[-1][1] + 1:
            ranges[-1][1] = index
        else:
            ranges.append([index, index])

    shown = ["第 %d 条" % start if start == end else "第 %d–%d 条" % (start, end)
             for start, end in ranges[:5]]
    tail = "" if len(ranges) <= 5 else "，……还有 %d 段" % (len(ranges) - 5)
    return "、".join(shown) + tail


def format_names(indices, names, limit=5):
    """列出下标对应的动作名（最多 `limit` 个）；超出数据集范围的下标跳过。"""
    shown = [repr(names[index - 1]) for index in indices[:limit]
             if 1 <= index <= len(names)]
    if not shown:
        return ""
    tail = "" if len(indices) <= limit else "，……"
    return "、".join(shown) + tail


def check_range_coverage(parts, names, errors):
    """检查批次范围的认领：只接受从第 1 条开始、中间没有洞的连续前缀。

    - 前缀之外还没认领：还没翻完，不是错误（由调用方按退出码 2 处理）。
    - 被认领的范围中间有洞：说明有批次漏了，是错误。
    - 同一条被多个批次认领：重叠，是错误。

    返回已认领前缀的末尾下标（0 表示第 1 条就没被认领）；中间有洞或重叠时返回 0，
    并把错误记进 `errors`。
    """
    claims = {}
    for part in parts:
        for index in range(part.start, part.end + 1):
            claims.setdefault(index, []).append(part.name)

    overlaps = [index for index in sorted(claims) if len(claims[index]) > 1]
    overlapped = bool(overlaps)
    if overlapped:
        shown = ["%s被 %s 同时认领"
                 % (describe_index(index, names), " 与 ".join(claims[index]))
                 for index in overlaps[:10]]
        tail = "" if len(overlaps) <= 10 else "，……还有 %d 条" % (len(overlaps) - 10)
        errors.append(("批次范围重叠", "%d 条被多个批次认领：%s%s"
                       % (len(overlaps), "、".join(shown), tail)))

    covered = set(claims)
    if not covered:
        return 0

    prefix_end = 0
    while prefix_end + 1 in covered:
        prefix_end += 1

    highest = max(covered)
    holes = [index for index in range(prefix_end + 1, highest + 1)
             if index not in covered]
    if holes:
        detail = format_names(holes, names)
        suffix = "（%s）" % detail if detail else ""
        errors.append(("批次范围缺口（中间有洞）",
                       "%s没有任何批次认领%s——已认领的批次范围必须构成从第 1 条开始、"
                       "中间没有洞的连续前缀；中间有洞说明有批次漏了"
                       % (format_ranges(holes), suffix)))
        return 0

    return 0 if overlapped else prefix_end


def report_failures(errors):
    """按类别分组打印，返回退出码 1。"""
    print("合并失败：以下校验不通过，未写入产物。")
    order = []
    grouped = {}
    for category, message in errors:
        if category not in grouped:
            grouped[category] = []
            order.append(category)
        grouped[category].append(message)
    for category in order:
        messages = grouped[category]
        print("")
        print("[错误] %s（%d 处）" % (category, len(messages)))
        for message in messages:
            print("  - " + message)
    return 1


def main():
    names, paragraphs = load_dataset()
    translatable = [name for name in names if paragraphs[name]]
    without_source = [name for name in names if not paragraphs[name]]

    part_paths = sorted(PARTS_DIR.glob("part-*.json")) if PARTS_DIR.is_dir() else []
    if not part_paths:
        print("还没有任何 part-NN.json（翻译尚未开始），未生成产物。")
        print("数据集 %d 条：有英文原文 %d 条、原文为空 %d 条。"
              % (len(names), len(translatable), len(without_source)))
        return 2

    errors = []
    parts = []
    for path in part_paths:
        part = read_part(path, errors)
        if part is not None:
            parts.append(part)

    if len(parts) != len(part_paths):
        # 形状坏掉的文件不再参与后面的检查，否则后面的报错会变成噪声。
        return report_failures(errors)

    batches = sorted(part.batch for part in parts)
    if batches != list(range(1, len(batches) + 1)):
        errors.append(("part 文件形状", "批次号应当从 1 连续到 %d，实际是 %s"
                       % (len(batches), batches)))

    without_source_set = set(without_source)
    seen = {}
    for part in parts:
        check_batch_boundary(part, names, paragraphs, errors)
        check_no_empty_source_items(part, without_source_set, errors)
        check_paragraph_counts(part, paragraphs, errors)
        check_duplicate_keys(part, seen, errors)
    prefix_end = check_range_coverage(parts, names, errors)

    if errors:
        return report_failures(errors)

    if prefix_end < len(names):
        # 这不是错误：已认领的范围恰好是 1..prefix_end 的连续前缀，翻译还在进行中。
        # 产物只在 1..876 全部被认领后才写。
        print("还没翻完，未生成产物。")
        print("已认领的批次范围构成 1..%d 的连续前缀，还差第 %d..%d 条（%d 条）。"
              % (prefix_end, prefix_end + 1, len(names), len(names) - prefix_end))
        print("数据集 %d 条：有英文原文 %d 条、原文为空 %d 条。"
              % (len(names), len(translatable), len(without_source)))
        return 2

    merged = {}
    for part in parts:
        for name, segments in part.items.items():
            merged[name] = segments

    missing = [name for name in translatable if name not in merged]
    extra = [name for name in merged if name not in set(translatable)]
    if extra:
        # 正常走不到：批边界与范围校验都过了还多出键，说明上面的校验被改坏了。
        print("合并失败：产物里出现了数据集之外的动作：%r" % extra)
        return 1
    if missing:
        print("还没翻完，未生成产物。")
        print("有英文原文的动作共 %d 条，已覆盖 %d 条，还差 %d 条。"
              % (len(translatable), len(translatable) - len(missing), len(missing)))
        print("还缺（最多列 10 条）：%s" % ", ".join(repr(n) for n in missing[:10]))
        return 2

    # 键按数据集顺序（不是按 part 文件顺序）——产物与 name-zh.json 才能逐键对齐。
    ordered = {name: merged[name] for name in translatable}

    text = json.dumps(ordered, ensure_ascii=False, indent=2) + "\n"
    with OUTPUT_PATH.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)

    total_paragraphs = sum(len(segments) for segments in ordered.values())
    print("part 文件：%d 个（%s … %s）" % (len(parts), parts[0].name, parts[-1].name))
    print("总条数：%d（数据集 %d 条 − 原文为空 %d 条）"
          % (len(ordered), len(names), len(without_source)))
    print("总段落数：%d" % total_paragraphs)
    print("覆盖率：%d/%d（已翻 %d + 原文为空跳过 %d）"
          % (len(ordered) + len(without_source), len(names),
             len(ordered), len(without_source)))
    print("跳过条数：%d（英文原文为空，不进产物：%s）"
          % (len(without_source), ", ".join(repr(n) for n in without_source)))
    print("已写入：%s（%d 字节）"
          % (OUTPUT_PATH.relative_to(REPO_ROOT).as_posix(), len(text.encode("utf-8"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
