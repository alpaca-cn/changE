# 中文做法（`instructions_zh`）· 分批与合并

> 这份文件是 **Task 3 / Task 4 派活的依据**，也是「哪条在哪批」的唯一权威。
> 批边界写错的时候，页面上看不出来——所以这里用整数而不是散文，并且有脚本逐批机器校验。

## 这一层怎么走

```
seed/free-exercise-db/exercises.json        ← 英文原文（vendor 好的，876 条）
        ↓  按数据集顺序切成 22 批
seed/instructions-zh/part-01.json … part-22.json   ← 每批译文的落盘位置（可审计、可续跑）
        ↓  合并 + 校验（scripts/merge-instructions-zh.py）
seed/instructions-zh.json                   ← 合并产物（生成物，871 条）
        ↓  classpath
启动时写库 → exercise.instructions_zh
```

## 数据集顺序是什么

**「数据集顺序」= `seed/free-exercise-db/exercises.json` 里那个 JSON 数组的顺序，1-based。**

- 共 **876** 条；其中 **871** 条有英文原文，**5** 条原文为空。
- 这一版的顺序**已实测与 `seed/name-zh.json` 的键顺序完全一致**（876 个键逐一对应），
  所以「数据集第 N 条」同时也就是「`name-zh.json` 的第 N 个键」。
- 这个顺序是 vendor 文件的物理顺序，不是运行时排序。**不要**按字母序、中文名、分类重新排——
  重排会让所有批边界失效。数据集换版本时，先重跑合并脚本再谈后面的批。

## `part-NN.json` 的形状

```json
{
  "batch": 1,
  "from": 1,
  "to": 40,
  "items": {
    "3/4 Sit-Up": ["躺在地板上，脚勾住固定物。双腿屈膝。", "双手置于脑后或头两侧。……"],
    "90/90 Hamstring": ["……"]
  }
}
```

| 字段 | 规则 |
|---|---|
| `batch` | 整数，**必须等于文件名 `part-NN.json` 的 NN**（合并脚本会核） |
| `from` / `to` | 整数，1-based 闭区间，指**数据集顺序**里的下标 |
| `items` | `{nameEn: [中文段, ...]}`，键与 `seed/name-zh.json` 同一套 |

**为什么 `from`/`to` 必须是整数**：早期计划里写的是 `"range": "aliases 1-40 of the dataset order"`
这种散文——散文没法机器校验，而「批边界写错、某条漏在缝里」正是最容易出的事。
`from`/`to` 让脚本能逐批核对「这批该有哪 40 条」。

`items` 的规则（合并脚本逐条检查，任何一条不满足都不写产物）：

1. **键集合必须正好是「数据集第 `from..to` 条里有英文原文的那些」**。多一条、少一条都报错，
   并列出差异（多出来的那条还会标出它在数据集里是第几条）。
2. **值是中文段落数组**，不是一整段用 `\n` 拼起来——段数就是步骤序号，前端要渲染成有序列表。
3. **每条的段数必须与英文原文一致**。少一段 = 丢一步做法，而页面上看不出来。
4. **英文原文为空的那 5 条不要写进 part 文件**（没有可翻的东西，也不进产物）。
5. 只允许 `batch` / `from` / `to` / `items` 四个字段（拼错的字段名不会当成注释被忽略）。

### 两个必须知道的原文边角

- **空段**：`Barbell Squat To A Bench`（数据集第 65 条）与 `Clean`（第 163 条）的第 2 段
  在英文原文里就是空串。为了段数对齐，中文对应段也**必须留空**（`""`）；合并脚本会拒绝
  往英文空段里写内容（不增不减）。
- **段内换行**：`Straight-Arm Dumbbell Pullover`（第 797 条）的某一段**内部**本来就有换行。
  它在段数上仍然是 **1 段**；写进 JSON 时用 `\n` 转义，**不要**拆成两段。

## 批次表（22 批：40 × 21 + 36）

| 批次 | 文件 | from | to | 该范围可翻条数 | 首条 | 末条 |
|---|---|---|---|---|---|---|
| 1 | `part-01.json` | 1 | 40 | 40 | 3/4 Sit-Up | Band Pull Apart |
| 2 | `part-02.json` | 41 | 80 | 40 | Band Skull Crusher | Bent Over Barbell Row |
| 3 | `part-03.json` | 81 | 120 | 40 | Bent Over Dumbbell Rear Delt Raise With Head On Bench | Cable Judo Flip |
| 4 | `part-04.json` | 121 | 160 | 40 | Cable Lying Triceps Extension | Chin-Up |
| 5 | `part-05.json` | 161 | 200 | 40 | Chin To Chest Stretch | Decline Dumbbell Triceps Extension |
| 6 | `part-06.json` | 201 | 240 | 40 | Decline EZ Bar Triceps Extension | Dumbbell Rear Lunge |
| 7 | `part-07.json` | 241 | 280 | 40 | Dumbbell Scaption | Forward Drag with Press |
| 8 | `part-08.json` | 281 | 320 | 40 | Frankenstein Squat | Hanging Pike |
| 9 | `part-09.json` | 321 | 360 | **39** | Heaving Snatch Balance | Isometric Chest Squeezes |
| 10 | `part-10.json` | 361 | 400 | 40 | Isometric Neck Exercise - Front And Back | Kneeling Hip Flexor |
| 11 | `part-11.json` | 401 | 440 | 40 | Kneeling Jump Squat | Lying Cambered Barbell Row |
| 12 | `part-12.json` | 441 | 480 | 40 | Lying Close-Grip Bar Curl On High Pulley | Neck Press |
| 13 | `part-13.json` | 481 | 520 | **39** | Oblique Crunches | One Leg Barbell Squat |
| 14 | `part-14.json` | 521 | 560 | 40 | Open Palm Kettlebell Clean | Prone Manual Hamstring |
| 15 | `part-15.json` | 561 | 600 | **39** | Prowler Sprint | Rhomboids-SMR |
| 16 | `part-16.json` | 601 | 640 | 40 | Rickshaw Carry | Seated Floor Hamstring Stretch |
| 17 | `part-17.json` | 641 | 680 | **38** | Seated Front Deltoid | Single-Cone Sprint Drill |
| 18 | `part-18.json` | 681 | 720 | 40 | Single-Leg High Box Squat | Snatch Balance |
| 19 | `part-19.json` | 721 | 760 | 40 | Snatch Deadlift | Standing Dumbbell Straight-Arm Front Delt Raise Above Head |
| 20 | `part-20.json` | 761 | 800 | 40 | Standing Dumbbell Triceps Extension | Straight Raises on Incline Bench |
| 21 | `part-21.json` | 801 | 840 | 40 | Stride Jump Crossover | Upper Back Stretch |
| 22 | `part-22.json` | 841 | 876 | 36 | Upright Barbell Row | Zottman Preacher Curl |

合计：40 × 17 + 39 × 3 + 38 + 36 = **871** 条可翻（876 − 5 条原文为空）。

「可翻条数」少于 `to - from + 1` 的批次，是因为范围内有原文为空的动作（见下一节）。

## 原文为空的 5 条（不翻译、不进产物）

| 数据集下标 | nameEn | 落在哪批的范围内 |
|---|---|---|
| 358 | Iron Cross | 第 9 批（321–360） |
| 501 | One-Arm Kettlebell Swings | 第 13 批（481–520） |
| 568 | Push Press | 第 15 批（561–600） |
| 665 | Side Bridge | 第 17 批（641–680） |
| 667 | Side Jackknife | 第 17 批（641–680） |

它们**不该出现在任何 `part-NN.json` 里**，也不在合并产物里——没有英文原文就没有可翻的东西。

## 怎么重新合并（一条命令）

在仓库根目录（只需要 Python 3，脚本只用标准库）：

```powershell
python scripts/merge-instructions-zh.py
```

脚本位置：`scripts/merge-instructions-zh.py`。**它进库**——合并是可复现的一部分，
不是某次会话里的临时脚本。

脚本会依次校验：part 形状 → 批边界 → 重复键 → 段数/空段 → 范围覆盖，**全部通过才写产物**。
范围覆盖只接受**从第 1 条开始、中间没有洞**的连续前缀：前缀之外还没认领只是「还没翻完」（退出码 `2`），
中间有洞或范围重叠才是错误（退出码 `1`）。

| 退出码 | 含义 |
|---|---|
| `0` | 全部校验通过，且 `1..876` 全部被认领（翻完了），产物已写入 |
| `1` | 校验失败，**未写产物**（part 形状 / 批边界 / 重复键 / 段数 / 空段 / **范围中间有洞** / 范围重叠；输出会指出哪一批、哪一条、差多少） |
| `2` | 校验通过但**还没翻完**，**未写产物**——已认领的批次范围构成 `1..k`（`k < 876`）的连续前缀，或还没有任何 `part-NN.json`（翻译进行中这是正常状态） |

成功时的输出形如：

```
part 文件：22 个（part-01.json … part-22.json）
总条数：871（数据集 876 条 − 原文为空 5 条）
总段落数：3738
覆盖率：876/876（已翻 871 + 原文为空跳过 5）
跳过条数：5（英文原文为空，不进产物：'Iron Cross', ...）
已写入：backend/src/main/resources/seed/instructions-zh.json（…字节）
```

**检查自己那一批**：写完 `part-NN.json` 先跑一次合并脚本。批边界、段数的错误会立刻列出来
（退出码 `1`）——那就是「我这批有没有漏/多/段数不对」的反馈。全部无误但还没翻完时退出码是 `2`，
属于正常中间状态。

## 产物是生成的：不要手改

`backend/src/main/resources/seed/instructions-zh.json` 是**生成物**。

- 改译文 → 改 `part-NN.json` → 重跑合并。
- 直接手改 `instructions-zh.json` 会在下次合并时被**整体覆盖**——静默丢改动。

## 翻译约定（Task 3 定稿，Task 4 的 21 批按此执行）

### 术语

| 英文 | 中文 | 备注 |
|---|---|---|
| barbell / dumbbell / cable / kettlebell / bands / medicine ball / exercise ball / foam roll / e-z curl bar | 杠铃 / 哑铃 / 绳索 / 壶铃 / 弹力带 / 药球 / 瑞士球 / 泡沫轴 / 曲杠 | 器械通行译法 |
| machine（笼统） | 器械 | 见下「专名优先」 |
| reps / repetitions | 次数 | **不要**译成「反复」 |
| set | 组 | |
| eccentric / concentric | 离心 / 向心 | |
| peak contraction | 顶峰收缩 | |
| range of motion | 活动范围 | |
| spotter / partner | 保护者 / 搭档 | |
| lats / traps / delts / hamstrings / quadriceps / shoulder blades | 背阔肌 / 斜方肌 / 三角肌 / 腘绳肌 / 股四头肌 / 肩胛骨 | |
| starting position **与** initial position | **起始姿势** | 同一个词，全篇统一。**不要**出现「起始位置」 |
| bar / axle（硬拉、卧推等） | 横杠 | 专名优先，不译「杠铃杆」 |

**专名优先于笼统规则**：`ab machine → 腹肌机`、`cable tower → 拉力架`、`bar/axle → 横杠`。笼统的 `machine` 仍是「器械」。

### 数字

- **数值与单位原样保留**：`90 度`、`10-20 次`、`18-24 英寸`、`10-30 秒`、`1 英尺`、`¾`（保留原字形）。
- **英文字数词落成阿拉伯数字**（Task 3 定）：`forty-five → 45`、`a second → 1 秒`、`ten → 10 秒`、`one repetition → 1 次`、`90-degree → 90 度`。
  理由：单位已经中文化了，数字再保留英文数词会读成「四十五度」，反而不统一。

### 风格

- **第二人称祈使句**：「躺下…」「保持…」「重复…」。不用「你应该…」，不用「您」。
- **段数与英文一一对应**：段数就是步骤序号。
- **英文某段是空串时，中文也必须是空串**（`scripts/merge-instructions-zh.py` 会校验）。已知这种空段在第 65、163 条。
- **不增不减内容**：不补充安全提示、不省略步骤。
  - 「看起来像安全提示」的句子**如果英文原文里有**，照译——那不是夹带。
  - **英文原文把同一句话重复两遍时，可以合并成一遍**，但**每处都要在报告里列出**（否则译者就变成了编辑）。
- **英文原文本身有错时**（拼写、语法、单复数），**按本意译，不要在译文里加修正说明**。已知的错：`if` 应为 `of`、`guage`、`be driving` 应为 `by`、`You feet` 应为 `Your`、`torso.This` 缺空格、`your right legs`。

### 怎么用这套约定

Task 4 的每一批都**逐条**遵守上面三节。批与批之间**风格必须一致**——不一致的代价是 800 多条返工。

## 进度

- [ ] `part-01.json`（1–40）
- [ ] `part-02.json`（41–80）
- [ ] `part-03.json`（81–120）
- [ ] `part-04.json`（121–160）
- [ ] `part-05.json`（161–200）
- [ ] `part-06.json`（201–240）
- [ ] `part-07.json`（241–280）
- [ ] `part-08.json`（281–320）
- [ ] `part-09.json`（321–360，39 条）
- [ ] `part-10.json`（361–400）
- [ ] `part-11.json`（401–440）
- [ ] `part-12.json`（441–480）
- [ ] `part-13.json`（481–520，39 条）
- [ ] `part-14.json`（521–560）
- [ ] `part-15.json`（561–600，39 条）
- [ ] `part-16.json`（601–640）
- [ ] `part-17.json`（641–680，38 条）
- [ ] `part-18.json`（681–720）
- [ ] `part-19.json`（721–760）
- [ ] `part-20.json`（761–800）
- [ ] `part-21.json`（801–840）
- [ ] `part-22.json`（841–876，36 条）
