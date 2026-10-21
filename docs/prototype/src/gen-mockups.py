"""浅色版界面原型：暖米白 + 墨色 + 黄铜。

改动依据（都是用户反馈与参考项目里抽的事实）：
- 纯黑太压抑 → 换成暖米白底
- 参考项目的 :root 本体就是浅色（--bg:#f6f6f4 --ink:#1a1a1a --line:#e6e6e3）
- 它的 --hero:#161616 说明「浅色页 + 一块深色 hero」是这套语言本来就有的手法
- 用户发了两张图：深夜人像（横）与油画海报（竖），分别用于 今日 hero 与 deck 封面
"""
import pathlib
import re

ROOT = pathlib.Path(r"E:\idea\program\change")
ASSET = ROOT / "frontend/src/assets/body-map/data"
IMG = ROOT / "docs/prototype/assets"
OUT = pathlib.Path(r"E:\devtools\cache\ui_mock")
OUT.mkdir(parents=True, exist_ok=True)

ENTRY = re.compile(r'id:\s*"([^"]+)"[\s\S]*?view:\s*ViewSide\.(FRONT|BACK)[\s\S]*?path:\s*"([^"]+)"')
def load(n):
    return [(i, v, p) for i, v, p in ENTRY.findall((ASSET / n).read_text(encoding="utf-8"))]

FRONT, BACK = load("muscles.front.ts"), load("muscles.back.ts")

MUSCLE_REGIONS = {
    "chest": ["chest-upper-left", "chest-lower-left", "chest-upper-right", "chest-lower-right"],
    "abdominals": ["abs-upper-left", "abs-lower-left", "abs-upper-right", "abs-lower-right",
                   "obliques-left", "obliques-right"],
    "lats": ["lats-upper-left", "lats-mid-left", "lats-lower-left",
             "lats-upper-right", "lats-mid-right", "lats-lower-right"],
    "middle back": ["traps-mid-left", "traps-mid-right"],
    "lower back": ["lower-back-erectors-left", "lower-back-ql-left",
                   "lower-back-erectors-right", "lower-back-ql-right"],
    "traps": ["traps-upper-left", "traps-lower-left", "traps-upper-right", "traps-lower-right"],
    "shoulders": ["shoulder-front-left", "shoulder-side-left", "deltoid-rear-left",
                  "shoulder-front-right", "shoulder-side-right", "deltoid-rear-right"],
    "biceps": ["biceps-left", "biceps-right"],
    "triceps": ["triceps-long-left", "triceps-lateral-left",
                "triceps-long-right", "triceps-lateral-right"],
    "forearms": ["forearm-left", "forearm-flexors-left", "forearm-extensors-left",
                 "forearm-right", "forearm-flexors-right", "forearm-extensors-right"],
    "quadriceps": ["quads-left", "quads-right"],
    "hamstrings": ["hamstrings-medial-left", "hamstrings-lateral-left",
                   "hamstrings-medial-right", "hamstrings-lateral-right"],
    "glutes": ["gluteus-maximus-left", "gluteus-maximus-right"],
    "calves": ["calves-gastroc-medial-left", "calves-gastroc-lateral-left", "calves-soleus-left",
               "calves-gastroc-medial-right", "calves-gastroc-lateral-right", "calves-soleus-right"],
    "adductors": ["adductors-left", "adductors-right"],
    "abductors": ["gluteus-medius-left", "gluteus-medius-right"],
    "neck": ["neck-left", "neck-right", "nape"],
}
DEMO = {"chest": 3.0, "triceps": 1.5, "shoulders": 1.5,
        "quadriceps": 4.0, "glutes": 2.0, "hamstrings": 2.0, "lower back": 2.0}
REGION_SETS = {}
for m, s in DEMO.items():
    for r in MUSCLE_REGIONS[m]:
        REGION_SETS[r] = max(REGION_SETS.get(r, 0), s)

BASE = "#2e2b26"          # 未练：暖墨色（深剪影，浅底上才立得住）
HOT = (227, 192, 122)     # 亮黄铜 #e3c07a：强度即亮度


def fill_for(rid):
    s = REGION_SETS.get(rid, 0)
    if s <= 0:
        return BASE
    a = 0.30 + 0.70 * min(1.0, s / 10.0)
    r0, g0, b0 = int(BASE[1:3], 16), int(BASE[3:5], 16), int(BASE[5:7], 16)
    return "rgb({},{},{})".format(int(r0 + (HOT[0] - r0) * a),
                                  int(g0 + (HOT[1] - g0) * a),
                                  int(b0 + (HOT[2] - b0) * a))


def body_svg(entries, viewbox, width):
    parts = [f'<svg viewBox="{viewbox}" width="{width}" xmlns="http://www.w3.org/2000/svg">']
    for rid, _v, d in entries:
        parts.append(f'<path d="{d}" fill="{fill_for(rid)}" stroke="#f4f2ed" stroke-width="0.18"/>')
    parts.append("</svg>")
    return "".join(parts)


CSS = """
*{box-sizing:border-box;margin:0;padding:0}
:root{
  --bg:#f4f2ed; --panel:#efece3; --card:#ffffff; --raise:#f8f6f0;
  --ink:#1a1a1a; --muted:#6b6559; --faint:#9c968a;
  --line:#e3dfd5; --line-soft:#eceae1;
  --accent:#b5893a; --pill:#1a1a1a; --pill-ink:#f6f4ef;
  --hero:#161412;
  --sans:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;
  --serif:'Songti SC','Noto Serif SC','SimSun',serif;
  --mono:ui-monospace,'Consolas','Cascadia Mono',monospace;
  --r:16px; --r-sm:10px;
}
body{font-family:var(--sans);background:var(--bg);color:var(--ink);
  -webkit-font-smoothing:antialiased;overflow:hidden}
.screen{width:1280px;height:820px;display:flex;background:var(--bg)}

.side{width:224px;flex:0 0 224px;background:var(--panel);border-right:1px solid var(--line);
  padding:34px 22px;display:flex;flex-direction:column}
.brand{padding:0 8px 34px}
.brand .nm{font-size:17px;font-weight:720;line-height:1.02;letter-spacing:-.01em}
.brand .nm i{font-style:normal;color:var(--accent)}
.brand .cap{font-size:8px;font-weight:650;letter-spacing:.15em;color:var(--faint);
  text-transform:uppercase;margin-top:6px}
.nav{display:flex;flex-direction:column;gap:2px;flex:1;justify-content:center}
.nav .it{display:flex;align-items:center;gap:12px;padding:12px 14px;border-radius:13px;
  color:var(--muted);font-size:13px;position:relative}
.nav .it .no{font-family:var(--mono);font-size:10px;color:var(--faint);width:16px;flex:none}
.nav .it .tw{display:flex;flex-direction:column;gap:2px;min-width:0}
.nav .it .tw b{font-weight:500;font-size:13px;color:inherit}
.nav .it .tw em{font-style:normal;font-size:8px;font-weight:650;letter-spacing:.15em;
  color:var(--faint);text-transform:uppercase}
.nav .it.on{background:var(--pill);color:var(--pill-ink)}
.nav .it.on .no{color:rgba(246,244,239,.5)}
.nav .it.on .tw em{color:rgba(246,244,239,.5)}
.nav .it.on::before{content:"";position:absolute;left:-8px;top:50%;transform:translateY(-50%);
  width:4px;height:18px;border-radius:2px;background:var(--accent)}
.side .foot{margin-top:auto;padding:16px 8px 0;border-top:1px solid var(--line);
  font-size:10px;color:var(--faint);letter-spacing:.04em;display:flex;align-items:center;gap:7px}
.side .foot .d{width:5px;height:5px;border-radius:50%;background:var(--accent)}

.main{flex:1;display:flex;flex-direction:column;min-width:0}
.top{height:66px;flex:0 0 66px;border-bottom:1px solid var(--line);display:flex;
  align-items:center;padding:0 26px;gap:10px;background:rgba(244,242,237,.86)}
.top h1{font-size:15px;font-weight:650;letter-spacing:-.01em}
.top h1::before{content:"changE / ";color:var(--faint);font-weight:500;letter-spacing:.08em}
.top .right{margin-left:auto;display:flex;align-items:center;gap:14px;font-size:11px;color:var(--faint)}
.top .right b{font-weight:500;color:var(--muted)}
.body{flex:1;padding:22px 30px;display:flex;flex-direction:column;overflow:hidden}

.eyebrow{font-size:11px;color:var(--faint);letter-spacing:.08em;margin-bottom:6px}
.hi{font-family:var(--serif);font-size:34px;font-weight:400;line-height:1.28;letter-spacing:-.01em}
.sub{font-size:12px;color:var(--muted);margin-top:8px;letter-spacing:.01em}
h2{font-size:15px;font-weight:500;margin:0 0 10px;display:flex;align-items:baseline;gap:8px}
h2 .t{color:var(--faint);font-size:12px;font-weight:400}
hr{border:none;border-top:1px solid var(--line);margin:16px 0}
.big{font-size:46px;font-weight:500;line-height:1;letter-spacing:-.02em;
  font-variant-numeric:tabular-nums}
.big small{font-size:13px;font-weight:400;color:var(--faint);margin-left:6px;letter-spacing:0}
.mid{font-size:22px;font-weight:500;font-variant-numeric:tabular-nums;letter-spacing:-.01em}
.k{font-size:11px;color:var(--faint);letter-spacing:.04em}
.row{display:flex;gap:18px}
.chip{display:inline-flex;align-items:center;gap:5px;font-size:11px;padding:4px 10px;
  border-radius:999px;border:1px solid var(--line);color:var(--muted);background:#fff}
.chip.on{border-color:transparent;background:var(--pill);color:var(--pill-ink);font-weight:500}
.chip.hot{border-color:rgba(181,137,58,.42);color:var(--accent);background:rgba(181,137,58,.07)}
.card{background:var(--card);border:1px solid var(--line);border-radius:var(--r);
  overflow:hidden;box-shadow:0 1px 2px rgba(26,26,26,.035)}
.rows{display:flex;flex-direction:column}
.r{display:flex;align-items:center;justify-content:space-between;gap:14px;
  padding:14px 18px;border-bottom:1px solid var(--line-soft)}
.r:last-child{border-bottom:none}
.r .l .n{font-size:14px;font-weight:500}
.r .l .s{font-size:11px;color:var(--muted);margin-top:3px}
.r .v{font-size:19px;font-weight:500;font-variant-numeric:tabular-nums}
.r .v small{font-size:11px;color:var(--faint);font-weight:400;margin-left:3px}
.bar{height:3px;background:#e7e3d9;border-radius:2px;overflow:hidden}
.bar i{display:block;height:100%;background:var(--accent)}
.bar i.neutral{background:#b9b3a5}
.mono{font-family:var(--mono);font-variant-numeric:tabular-nums}
/* 深色 hero：参考项目的 --hero:#161616 */
.hero{position:relative;border-radius:var(--r);overflow:hidden;background:var(--hero);
  background-image:url('__HERO__');background-size:cover;background-position:68% 32%}
.hero .scrim{position:absolute;inset:0;
  background:linear-gradient(96deg,rgba(12,11,9,.94) 0%,rgba(12,11,9,.80) 34%,
  rgba(12,11,9,.28) 62%,rgba(12,11,9,.05) 100%)}
.hero .ct{position:relative;padding:22px 28px;color:#f4f2ed}
.hero .ct .eyebrow{color:rgba(244,242,237,.52)}
.hero .ct .hi{color:#f7f5f0}
.hero .ct .sub{color:rgba(244,242,237,.62)}
"""

NAV = [
    ("01", "今日", "Today", "today"), ("02", "训练记录", "Training", "training"),
    ("03", "饮食记录", "Nutrition", "diet"), ("04", "人体图", "Body Map", "bodymap"),
    ("05", "进步曲线", "Progress", "progress"), ("06", "身体数据", "Body", "body"),
    ("07", "智能体对话", "Agent", "agent"), ("08", "设置", "Settings", "settings"),
]
HERO_URI = (IMG / "pose-night.jpg").as_uri()


def wrap(title_cn, active, inner, extra=""):
    items = "".join(
        f'<div class="it{" on" if k == active else ""}"><span class="no">{no}</span>'
        f'<span class="tw"><b>{cn}</b><em>{en}</em></span></div>'
        for no, cn, en, k in NAV)
    css = (CSS + extra).replace("__HERO__", HERO_URI)
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<style>{css}</style></head><body>
<div class="screen">
  <aside class="side">
    <div class="brand"><div class="nm">chang<i>E</i></div>
      <div class="cap">Personal Training System</div></div>
    <nav class="nav">{items}</nav>
    <div class="foot"><span class="d"></span>本地服务 · 已连接</div>
  </aside>
  <div class="main">
    <div class="top"><h1>{title_cn}</h1>
      <div class="right"><b>2026-10-20</b><span>周二</span><span>第 43 周</span></div></div>
    <div class="body">{inner}</div>
  </div>
</div></body></html>"""


# ═══ 01 今日 ═══
def today():
    names = list(MUSCLE_REGIONS)
    blocks = [("胸", 3), ("腹", 0), ("背", 0), ("中", 0), ("下", 2), ("斜", 0), ("肩", 1.5),
              ("二", 0), ("三", 1.5), ("前", 0), ("股", 4), ("腘", 2), ("臀", 2),
              ("小", 0), ("内", 0), ("外", 0), ("颈", 0)]
    strip = "".join(
        f'<div style="flex:1;text-align:center">'
        f'<div style="height:22px;border-radius:3px;border:1px solid var(--line);background:'
        f'{fill_for(MUSCLE_REGIONS[names[i]][0]) if v else "#e6e1d5"}"></div>'
        f'<div style="font-size:8px;color:var(--faint);margin-top:6px">{b}</div></div>'
        for i, (b, v) in enumerate(blocks))
    inner = f"""
    <div class="hero" style="height:156px;flex:0 0 auto">
      <div class="scrim"></div>
      <div class="ct">
        <div class="eyebrow">9 月 12 日 星期六 · 第 7 周</div>
        <div class="hi" style="font-size:29px">今天，专注这一练。</div>
        <div class="sub">本周已记 6 次训练 · 还差 3.5 组到胸部上限</div>
      </div>
    </div>
    <div class="row" style="flex:1;margin-top:18px">
      <div style="flex:1.35;display:flex;flex-direction:column;gap:16px;min-width:0">
        <div>
          <div class="eyebrow">Week Volume</div>
          <div class="big">16<small>组</small></div>
          <div class="k" style="margin-top:8px">折合 42.5 组 · 主肌群 +1 / 次肌群 +0.5</div>
          <div class="bar" style="margin-top:13px"><i style="width:82%"></i></div>
          <div style="display:flex;justify-content:space-between;margin-top:8px">
            <span class="k">参考区间</span><span class="k mono">10 – 20</span></div>
        </div>
        <div>
          <div class="eyebrow">Muscle Strip · 17</div>
          <div style="display:flex;gap:5px;margin-top:8px">{strip}</div>
        </div>
        <div style="border-top:1px solid var(--line);padding-top:14px;display:flex;
                    align-items:center;gap:22px">
          <div style="position:relative;width:82px;height:82px;flex:none">
            <svg width="82" height="82" style="transform:rotate(-90deg)">
              <circle cx="41" cy="41" r="35" fill="none" stroke="#e7e3d9" stroke-width="3"/>
              <circle cx="41" cy="41" r="35" fill="none" stroke="#b5893a" stroke-width="3"
                      stroke-dasharray="220" stroke-dashoffset="62" stroke-linecap="round"/>
            </svg>
            <div style="position:absolute;inset:0;display:flex;flex-direction:column;
                        align-items:center;justify-content:center">
              <div style="font-size:18px;font-weight:500;font-variant-numeric:tabular-nums">72%</div>
              <div style="font-size:8px;color:var(--faint);letter-spacing:.1em">WEEK</div>
            </div>
          </div>
          <div style="flex:1;min-width:0">
            <div style="display:flex;justify-content:space-between;padding:6px 0;
                        border-bottom:1px solid var(--line-soft)">
              <span class="k">本周训练</span><span style="font-size:12px;font-weight:500">6 次</span></div>
            <div style="display:flex;justify-content:space-between;padding:6px 0;
                        border-bottom:1px solid var(--line-soft)">
              <span class="k">总组数</span><span style="font-size:12px;font-weight:500">49</span></div>
            <div style="display:flex;justify-content:space-between;padding:6px 0">
              <span class="k">漏练</span><span style="font-size:12px;font-weight:500">小腿</span></div>
          </div>
        </div>
      </div>
      <div style="flex:1;display:flex;flex-direction:column;gap:16px;min-width:0">
        <div>
          <div class="eyebrow">Today Intake</div>
          <div class="big">1 840<small>千卡</small></div>
          <div style="display:flex;flex-direction:column;gap:10px;margin-top:13px">
            <div><div style="display:flex;justify-content:space-between"><span class="k">蛋白</span>
              <span class="k mono">118 / 140 g</span></div>
              <div class="bar" style="margin-top:5px"><i style="width:84%"></i></div></div>
            <div><div style="display:flex;justify-content:space-between"><span class="k">碳水</span>
              <span class="k mono">196 / 240 g</span></div>
              <div class="bar" style="margin-top:5px"><i class="neutral" style="width:82%"></i></div></div>
            <div><div style="display:flex;justify-content:space-between"><span class="k">脂肪</span>
              <span class="k mono">52 / 65 g</span></div>
              <div class="bar" style="margin-top:5px"><i class="neutral" style="width:80%"></i></div></div>
          </div>
        </div>
        <div style="border-top:1px solid var(--line);padding-top:14px">
          <div class="eyebrow">Meals</div>
          <div style="display:flex;flex-direction:column">
            <div style="display:flex;justify-content:space-between;padding:8px 0;
                        border-bottom:1px solid var(--line-soft)">
              <span style="font-size:12px">早餐<span class="k mono" style="margin-left:10px">08:10</span></span>
              <span style="font-size:13px;font-weight:500" class="mono">520</span></div>
            <div style="display:flex;justify-content:space-between;padding:8px 0;
                        border-bottom:1px solid var(--line-soft)">
              <span style="font-size:12px">午餐<span class="k mono" style="margin-left:10px">12:30</span></span>
              <span style="font-size:13px;font-weight:500" class="mono">760</span></div>
            <div style="display:flex;justify-content:space-between;padding:8px 0;
                        border-bottom:1px solid var(--line-soft)">
              <span style="font-size:12px">加餐<span class="k mono" style="margin-left:10px">16:00</span></span>
              <span style="font-size:13px;font-weight:500" class="mono">210</span></div>
            <div style="display:flex;justify-content:space-between;padding:8px 0">
              <span style="font-size:12px;color:var(--faint)">晚餐<span class="k mono"
                style="margin-left:10px">还没记</span></span>
              <span style="font-size:13px;color:var(--faint)">—</span></div>
          </div>
        </div>
      </div>
    </div>
    <hr style="margin:14px 0 10px">
    <div>
      <h2>最近训练 <span class="t">Recent Sessions</span></h2>
      <div class="rows" style="margin:0 -18px">
        <div class="r"><div class="l"><div class="n">胸 · 三头</div>
          <div class="s mono">10-20 · 杠铃卧推、窄距卧推</div></div>
          <div style="display:flex;align-items:center;gap:14px">
            <span class="chip hot">今天</span><span class="v">7<small>组</small></span></div></div>
        <div class="r"><div class="l"><div class="n">腿</div>
          <div class="s mono">10-19 · 深蹲、罗马尼亚硬拉</div></div>
          <div style="display:flex;align-items:center;gap:14px">
            <span class="chip">昨天</span><span class="v">9<small>组</small></span></div></div>
      </div>
    </div>"""
    return wrap("今日", "today", inner)


# ═══ 02 训练记录 ═══
def training():
    extra = """
    .shade{position:absolute;inset:0;background:rgba(20,18,15,.34);z-index:3}
    .drawer{position:absolute;right:0;top:0;bottom:0;width:436px;background:#fff;
      border-left:1px solid var(--line);z-index:4;padding:26px 24px;display:flex;
      flex-direction:column;gap:18px;box-shadow:-40px 0 80px rgba(26,26,26,.16)}
    .drawer .en{font-size:8px;font-weight:650;letter-spacing:.15em;color:var(--faint);
      text-transform:uppercase;margin-top:6px}
    .steps{display:flex;flex-direction:column;gap:12px}
    .step{display:flex;gap:11px;font-size:12.5px;line-height:1.7;color:#3d3a33}
    .step .n{flex:0 0 18px;font-family:var(--mono);font-size:10px;color:var(--accent);
      padding-top:3px}
    .sbtn{flex:1;border:1px solid var(--line);border-radius:var(--r-sm);padding:10px 12px;
      font-size:11px;color:var(--muted);display:flex;align-items:center;justify-content:space-between}
    .sbtn span{font-family:var(--mono);color:var(--faint)}
    """
    inner = f"""
    <div style="display:flex;align-items:flex-end;justify-content:space-between">
      <div><div class="eyebrow">Session</div><div class="hi" style="font-size:26px">今日训练</div></div>
      <div style="display:flex;gap:6px">
        <span class="chip on">胸</span><span class="chip">背</span><span class="chip">腿</span>
        <span class="chip">肩</span><span class="chip">手臂</span></div>
    </div>
    <hr>
    <div class="row" style="flex:1">
      <div style="flex:1;min-width:0">
        <h2>动作 <span class="t">Exercises</span></h2>
        <div class="card"><div class="rows">
          <div class="r"><div class="l"><div class="n">杠铃卧推</div><div class="s">胸部 · 复合</div></div>
            <span class="k">看做法 →</span></div>
          <div class="r"><div class="l"><div class="n">上斜哑铃卧推</div>
            <div class="s">胸部 · 复合</div></div><span class="k">看做法 →</span></div>
          <div class="r"><div class="l"><div class="n">双杠臂屈伸</div>
            <div class="s">胸部 · 自重</div></div><span class="k">看做法 →</span></div>
          <div class="r"><div class="l"><div class="n">蝴蝶机夹胸</div>
            <div class="s">胸部 · 孤立</div></div><span class="k">看做法 →</span></div>
        </div></div>
      </div>
      <div style="flex:1.15;min-width:0">
        <h2>本次 <span class="t">Sets</span></h2>
        <div class="card"><div class="rows">
          <div class="r"><div class="l"><div class="n mono">01</div></div>
            <div style="display:flex;gap:22px;align-items:baseline">
              <span class="v">60<small>kg</small></span><span class="v">8<small>次</small></span>
              <span class="mono" style="color:var(--accent);font-size:14px">✓</span></div></div>
          <div class="r"><div class="l"><div class="n mono">02</div></div>
            <div style="display:flex;gap:22px;align-items:baseline">
              <span class="v">62.5<small>kg</small></span><span class="v">8<small>次</small></span>
              <span class="mono" style="color:var(--accent);font-size:14px">✓</span></div></div>
          <div class="r"><div class="l"><div class="n mono">03</div></div>
            <div style="display:flex;gap:22px;align-items:baseline">
              <span class="v" style="color:var(--faint)">62.5<small>kg</small></span>
              <span class="v" style="color:var(--faint)">6<small>次</small></span>
              <span class="mono" style="color:var(--faint);font-size:14px">—</span></div></div>
        </div></div>
        <div style="display:flex;gap:8px;margin-top:14px">
          <span class="chip">上次 62.5 × 8</span><span class="chip hot">估算 1RM 76.5</span></div>
      </div>
    </div>
    <div class="shade"></div>
    <div class="drawer">
      <div>
        <div style="display:flex;justify-content:space-between;align-items:flex-start">
          <div><div style="font-size:21px;font-weight:650;letter-spacing:-.01em">杠铃卧推</div>
            <div class="en">Barbell Bench Press · Medium Grip</div></div>
          <span style="color:var(--faint);font-size:18px">×</span></div>
        <div style="display:flex;gap:6px;margin-top:14px">
          <span class="chip">杠铃</span><span class="chip">力量</span><span class="chip">复合</span></div>
      </div>
      <div>
        <div class="eyebrow">Target Muscles</div>
        <div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:6px">
          <span class="chip hot">胸部 · 主要</span><span class="chip">肩部 · 次要</span>
          <span class="chip">肱三头肌 · 次要</span></div>
      </div>
      <hr style="margin:2px 0">
      <div>
        <div class="eyebrow">Steps</div>
        <div class="steps" style="margin-top:10px">
          <div class="step"><span class="n">01</span><span>仰卧在平凳上，双脚踩实地面。
            掌心朝前握杠，握距略宽于肩。</span></div>
          <div class="step"><span class="n">02</span><span>把横杠从架上取下，双臂锁定，
            将横杠笔直举在身体上方。这是起始姿势。</span></div>
          <div class="step"><span class="n">03</span><span>吸气并缓慢下放，直到横杠触到胸部中间。</span></div>
          <div class="step"><span class="n">04</span><span>稍作停顿后，呼气的同时把横杠推回起始姿势。</span></div>
        </div>
      </div>
      <div style="margin-top:auto">
        <div class="eyebrow">Video Search</div>
        <div style="display:flex;gap:8px;margin-top:8px">
          <div class="sbtn">B 站 · 杠铃卧推<span>复制</span></div>
          <div class="sbtn">YouTube<span>复制</span></div></div>
      </div>
    </div>"""
    return wrap("训练记录", "training", inner, extra)


# ═══ 03 人体图 ═══
def bodymap():
    extra = """
    .figs{display:flex;gap:44px;justify-content:center;align-items:flex-end;flex:1}
    .figcap{text-align:center;font-size:8px;font-weight:650;letter-spacing:.15em;
      color:var(--faint);text-transform:uppercase;margin-bottom:10px}
    """
    inner = f"""
    <div style="display:flex;align-items:flex-end;justify-content:space-between">
      <div><div class="eyebrow">Balance</div>
        <div class="hi" style="font-size:26px">练了哪块，漏了哪块</div></div>
      <div style="display:flex;gap:6px">
        <span class="chip on">本周组数</span><span class="chip">恢复中</span>
        <span class="chip">长期未练</span></div>
    </div>
    <hr>
    <div class="row" style="flex:1;align-items:stretch">
      <div style="flex:1;min-width:0;display:flex;flex-direction:column">
        <div style="display:flex;justify-content:space-between">
          <span class="k mono">2026-10-19 → 2026-10-25</span>
          <span class="k">颜色越深 = 本周越多 · 10 组封顶</span></div>
        <div class="figs" style="margin-top:10px">
          <div><div class="figcap">Front</div>{body_svg(FRONT, '0 0 33 93', 208)}</div>
          <div><div class="figcap">Back</div>{body_svg(BACK, '36 0 33 93', 208)}</div>
        </div>
      </div>
      <div style="width:284px;flex:0 0 284px;border-left:1px solid var(--line);
                  padding-left:22px;display:flex;flex-direction:column">
        <div class="eyebrow">Selected</div>
        <div style="font-size:21px;font-weight:650;letter-spacing:-.01em">胸部</div>
        <div class="mono" style="font-size:8px;letter-spacing:.15em;color:var(--faint);
             text-transform:uppercase;margin-top:4px">Chest</div>
        <hr style="margin:16px 0">
        <div class="eyebrow">This Week</div>
        <div class="big" style="margin-top:4px">3<small>组</small></div>
        <div class="bar" style="margin-top:14px"><i style="width:30%"></i></div>
        <div style="display:flex;justify-content:space-between;margin-top:8px">
          <span class="chip">低于参考区间</span><span class="k mono">10 – 20</span></div>
        <hr style="margin:16px 0">
        <div class="eyebrow">Exercises</div>
        <div style="display:flex;flex-direction:column">
          <div style="display:flex;justify-content:space-between;padding:11px 0;
                      border-bottom:1px solid var(--line-soft)">
            <span style="font-size:13px">杠铃卧推</span><span class="k mono">1RM 76.5</span></div>
          <div style="display:flex;justify-content:space-between;padding:11px 0;
                      border-bottom:1px solid var(--line-soft)">
            <span style="font-size:13px">上斜哑铃卧推</span><span class="k mono">1RM 32</span></div>
          <div style="display:flex;justify-content:space-between;padding:11px 0">
            <span style="font-size:13px">双杠臂屈伸</span><span class="k mono">自重</span></div>
        </div>
      </div>
    </div>"""
    return wrap("人体图", "bodymap", inner, extra)


# ═══ 04 进步曲线 ═══
def spark(vals, w=250, h=96):
    lo, hi = min(vals), max(vals)
    span = (hi - lo) or 1
    pts = []
    for i, v in enumerate(vals):
        x = 3 + i * (w - 6) / max(1, len(vals) - 1)
        y = h - 6 - (v - lo) / span * (h - 14)
        pts.append(f"{x:.1f},{y:.1f}")
    last = pts[-1].split(",")
    return (f'<svg width="{w}" height="{h}"><polyline points="{" ".join(pts)}" fill="none" '
            f'stroke="#b5893a" stroke-width="1.8" stroke-linejoin="round"/>'
            f'<circle cx="{float(last[0]):.1f}" cy="{float(last[1]):.1f}" r="3" fill="#b5893a"/></svg>')


def progress():
    def chart(cap, en, num, unit, delta, vals, dc="var(--accent)", last=False):
        br = "" if last else "border-right:1px solid var(--line);padding-right:20px"
        return f"""
        <div style="flex:1;min-width:0;{br}">
          <div class="eyebrow">{en}</div>
          <div style="font-size:13px;font-weight:500;margin-bottom:10px">{cap}</div>
          <div class="big" style="font-size:38px">{num}<small>{unit}</small></div>
          <div class="k mono" style="margin-top:6px;color:{dc}">{delta}</div>
          <div style="margin-top:14px">{spark(vals)}</div>
          <div style="display:flex;justify-content:space-between;margin-top:4px">
            <span class="k mono">09-08</span><span class="k mono">10-20</span></div>
        </div>"""
    inner = f"""
    <div style="display:flex;align-items:flex-end;justify-content:space-between">
      <div><div class="eyebrow">Progress</div>
        <div class="hi" style="font-size:26px">同一个动作，三条曲线</div></div>
      <div style="display:flex;gap:6px">
        <span class="chip on">杠铃卧推</span><span class="chip">上斜哑铃卧推</span></div>
    </div>
    <hr>
    <div style="flex:1;display:flex;gap:20px;margin-top:4px">
      {chart("估算 1RM", "Estimated 1RM", "78.0", "kg", "▲ 2.0 · 六周", [71.5, 73.0, 74.0, 75.0, 76.0, 78.0])}
      {chart("最大重量", "Max Weight", "65.0", "kg", "▲ 5.0 · 六周", [60, 60, 62.5, 62.5, 62.5, 65])}
      {chart("总吨位", "Tonnage", "1 920", "kg", "◆ 持平 · 六周",
             [1440, 1620, 1500, 1750, 1875, 1920], "var(--faint)", True)}
    </div>
    <hr>
    <div style="display:flex;gap:44px">
      <div><div class="eyebrow">1RM</div><div class="mid">78.0 <span class="k">kg</span></div></div>
      <div><div class="eyebrow">Best Set</div><div class="mid">65×6</div></div>
      <div><div class="eyebrow">Tonnage</div><div class="mid">7 480 <span class="k">kg</span></div></div>
      <div style="margin-left:auto;align-self:flex-end">
        <span class="k">Epley 口径 · 只算 12 次以内的组</span></div>
    </div>"""
    return wrap("进步曲线", "progress", inner)


# ═══ 05 身体数据 ═══
def body():
    items = [("体重", "Weight", "72.4", "kg", "▼ 0.6", "var(--accent)"),
             ("体脂", "Body Fat", "15.8", "%", "▼ 0.3", "var(--accent)"),
             ("腰围", "Waist", "80.0", "cm", "— 持平", "var(--faint)"),
             ("胸围", "Chest", "101", "cm", "▲ 0.5", "var(--accent)"),
             ("臀围", "Hip", "95.0", "cm", "— 持平", "var(--faint)"),
             ("大腿", "Thigh", "57.5", "cm", "▲ 0.5", "var(--accent)")]
    cells = "".join(
        f'<div style="flex:1;min-width:0;border-right:1px solid var(--line);padding-right:18px">'
        f'<div class="eyebrow">{en}</div><div style="font-size:13px;font-weight:500">{cn}</div>'
        f'<div class="big" style="font-size:32px;margin-top:8px">{v}<small>{u}</small></div>'
        f'<div class="k mono" style="margin-top:6px;color:{c}">{d}</div></div>'
        for cn, en, v, u, d, c in items)
    rows = [("体重", "10-19 · 早上空腹", "72.4", "kg"), ("腰围", "10-19", "80.0", "cm"),
            ("体重", "10-12 · 训练后", "73.0", "kg"), ("体脂", "10-12", "16.1", "%")]
    tl = "".join(
        f'<div class="r"><div class="l"><div class="n">{n}</div>'
        f'<div class="s mono">{s}</div></div>'
        f'<span class="v">{v}<small>{u}</small></span></div>'
        for n, s, v, u in rows)
    inner = f"""
    <div><div class="eyebrow">Body Metrics</div>
      <div class="hi" style="font-size:26px">九项指标，每周记一两次</div>
      <div class="sub">没记过的显示「—」，不是 0。</div></div>
    <hr>
    <div class="row" style="gap:18px">{cells}</div>
    <hr>
    <div style="flex:1">
      <h2>时间线 <span class="t">Timeline</span></h2>
      <div class="card"><div class="rows">{tl}</div></div>
    </div>"""
    return wrap("身体数据", "body", inner)


# ═══ 06 饮食记录 ═══
def diet():
    meals = [("早餐", "08:10", "燕麦 60g · 鸡蛋 2 个 · 黑咖啡", 520),
             ("午餐", "12:30", "米饭 200g · 鸡胸 150g · 西兰花", 760),
             ("加餐", "16:00", "希腊酸奶 150g · 蓝莓 80g", 210),
             ("晚餐", "—", "还没记", 0)]
    rows = "".join(
        f'<div class="r"><div class="l"><div class="n">{cn}'
        f'<span class="k mono" style="margin-left:10px">{t}</span></div>'
        f'<div class="s">{items}</div></div>'
        f'<span class="v" style="font-size:17px;{"color:var(--faint)" if kcal == 0 else ""}">'
        f'{("—" if kcal == 0 else str(kcal))}<small>{"kcal" if kcal else ""}</small></span></div>'
        for cn, t, items, kcal in meals)
    inner = f"""
    <div style="display:flex;align-items:flex-end;justify-content:space-between">
      <div><div class="eyebrow">Nutrition</div>
        <div class="hi" style="font-size:26px">记个大概就行</div>
        <div class="sub">精度不是目标，可修正是。</div></div>
      <div style="display:flex;gap:6px">
        <span class="chip">‹ 前一天</span><span class="chip on">10-20 周二</span>
        <span class="chip">后一天 ›</span></div>
    </div>
    <hr>
    <div class="row" style="align-items:flex-start">
      <div style="flex:1"><div class="eyebrow">Intake</div>
        <div class="big">1 490<small>千卡</small></div></div>
      <div style="flex:1.6;display:flex;gap:20px">
        <div style="flex:1"><div style="display:flex;justify-content:space-between">
          <span class="k">蛋白</span><span class="k mono">96 / 140 g</span></div>
          <div class="bar" style="margin-top:6px"><i style="width:69%"></i></div></div>
        <div style="flex:1"><div style="display:flex;justify-content:space-between">
          <span class="k">碳水</span><span class="k mono">158 / 240 g</span></div>
          <div class="bar" style="margin-top:6px"><i class="neutral" style="width:66%"></i></div></div>
        <div style="flex:1"><div style="display:flex;justify-content:space-between">
          <span class="k">脂肪</span><span class="k mono">44 / 65 g</span></div>
          <div class="bar" style="margin-top:6px"><i class="neutral" style="width:68%"></i></div></div>
      </div>
    </div>
    <hr>
    <div style="flex:1">
      <h2>今天吃了什么 <span class="t">Meals</span></h2>
      <div class="card"><div class="rows">{rows}</div></div>
    </div>"""
    return wrap("饮食记录", "diet", inner)


# ═══ 07 智能体 ═══
def agent():
    extra = """
    .chat{flex:1;display:flex;flex-direction:column;gap:14px;overflow:hidden}
    .msg{max-width:70%;font-size:13px;line-height:1.75;padding:12px 16px;border-radius:14px}
    .me{align-self:flex-end;background:var(--pill);color:var(--pill-ink)}
    .ai{align-self:flex-start;background:#fff;border:1px solid var(--line)}
    .tool{align-self:flex-start;width:74%;border:1px solid var(--line);border-radius:14px;
      padding:14px 16px;background:#fff}
    .tool .hd{display:flex;align-items:center;gap:8px;font-size:8px;font-weight:650;
      letter-spacing:.15em;color:var(--faint);text-transform:uppercase;margin-bottom:10px}
    .tool .hd b{color:var(--accent);font-weight:650}
    .tool .ln{display:flex;justify-content:space-between;padding:7px 0;
      border-bottom:1px solid var(--line-soft);font-size:12px}
    .tool .ln:last-of-type{border-bottom:none}
    .tool .ln span:first-child{color:var(--muted)}
    .inbox{border:1px solid var(--line);border-radius:14px;padding:14px 16px;
      display:flex;align-items:center;gap:12px;background:#fff}
    """
    inner = f"""
    <div><div class="eyebrow">Agent</div>
      <div class="hi" style="font-size:26px">说一句就记下来</div>
      <div class="sub">写操作一律先给摘要，确认了才落库。</div></div>
    <hr>
    <div class="chat">
      <div class="msg me">今天卧推 60 公斤做了 3 组，每组 8 个</div>
      <div class="msg ai">看到了。我准备记一条：</div>
      <div class="tool">
        <div class="hd">准备写入 · <b>待确认</b></div>
        <div class="ln"><span>日期</span><span class="mono">2026-10-20</span></div>
        <div class="ln"><span>动作</span><span>杠铃卧推 · 胸部</span></div>
        <div class="ln"><span>组数</span><span class="mono">3 × 8 次 @ 60 kg</span></div>
        <div class="ln"><span>估算 1RM</span><span class="mono" style="color:var(--accent)">76.0 kg</span></div>
        <div style="display:flex;gap:8px;margin-top:14px">
          <span class="chip on">确认写入</span><span class="chip">改一下</span><span class="chip">取消</span></div>
      </div>
      <div class="msg me">确认</div>
      <div class="tool">
        <div class="hd">已写入 · <b>已记录</b></div>
        <div style="font-size:12px;color:var(--muted);line-height:1.7">
          训练 #43 · 3 组 · 胸部折合 3.0 组。人体图上的胸部已更新。</div>
      </div>
    </div>
    <div class="inbox">
      <span style="font-size:13px;color:var(--faint);flex:1">说一句话，或者…</span>
      <span class="chip">拍照</span><span class="chip">语音</span>
      <span style="width:30px;height:30px;border-radius:9px;background:var(--accent);
        display:flex;align-items:center;justify-content:center;color:#fff;
        font-weight:650;font-size:14px">↑</span>
    </div>"""
    return wrap("智能体对话", "agent", inner, extra)


SCREENS = {"01-today": today, "02-training": training, "03-bodymap": bodymap,
           "04-progress": progress, "05-body": body, "06-diet": diet, "07-agent": agent}

for slug, fn in SCREENS.items():
    html = fn()
    (OUT / f"{slug}.html").write_bytes(html.encode("utf-8"))
    print(f"  {slug}.html  {len(html):,} 字符")
print(f"素材: 正 {len(FRONT)} / 背 {len(BACK)}")
