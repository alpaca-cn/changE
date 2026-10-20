"""生成 7 张界面原型的 HTML，用 Edge headless 截图成 PNG。

为什么用 HTML 而不是在 PPT 里用形状拼：这 7 张就是「界面长什么样」本身，
手绘形状拼出来的东西一看就是假的，反而干扰判断。HTML 能一比一画出来。
幻灯片自己的文字（标题/标注/页码）仍然是 PPT 的原生文本。
"""
import pathlib
import re

ROOT = pathlib.Path(r"E:\idea\program\change")
ASSET = ROOT / "frontend/src/assets/body-map/data"
OUT = pathlib.Path(r"E:\devtools\cache\ui_mock")
OUT.mkdir(parents=True, exist_ok=True)

# ── 解析 vendored 素材的真实 SVG path ──────────────────────────────
ENTRY = re.compile(r'id:\s*"([^"]+)"[\s\S]*?view:\s*ViewSide\.(FRONT|BACK)[\s\S]*?path:\s*"([^"]+)"')
def load(name):
    t = (ASSET / name).read_text(encoding="utf-8")
    return [(i, v, p) for i, v, p in ENTRY.findall(t)]

FRONT = load("muscles.front.ts")
BACK = load("muscles.back.ts")
print(f"素材: 正面 {len(FRONT)} / 背面 {len(BACK)}")

# 与后端 V10 一致的映射（17 块肌群 -> 65 个区域）
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
# 演示数据：一次卧推 3 组 + 一次深蹲 4 组，算出来大概是这样
DEMO = {"chest": 3.0, "triceps": 1.5, "shoulders": 1.5,
        "quadriceps": 4.0, "glutes": 2.0, "hamstrings": 2.0, "lower back": 2.0}
REGION_SETS = {}
for m, sets in DEMO.items():
    for r in MUSCLE_REGIONS[m]:
        REGION_SETS[r] = max(REGION_SETS.get(r, 0), sets)

BASE = "#3a424b"          # 未练：与 app 的 --muted-strong 一致
ACCENT = "#e0453a"


def fill_for(rid):
    s = REGION_SETS.get(rid, 0)
    if s <= 0:
        return BASE
    t = min(1.0, s / 10.0)
    a = 0.36 + 0.64 * t
    # 用 accent 与 base 按强度混合
    r1, g1, b1 = int(ACCENT[1:3], 16), int(ACCENT[3:5], 16), int(ACCENT[5:7], 16)
    r0, g0, b0 = int(BASE[1:3], 16), int(BASE[3:5], 16), int(BASE[5:7], 16)
    r = int(r0 + (r1 - r0) * a); g = int(g0 + (g1 - g0) * a); b = int(b0 + (b1 - b0) * a)
    return f"#{r:02x}{g:02x}{b:02x}"


def body_svg(entries, viewbox, width):
    parts = [f'<svg viewBox="{viewbox}" width="{width}" xmlns="http://www.w3.org/2000/svg">']
    for rid, _v, d in entries:
        parts.append(f'<path d="{d}" fill="{fill_for(rid)}" stroke="#0b0e11" stroke-width="0.15"/>')
    parts.append("</svg>")
    return "".join(parts)


CSS = """
*{box-sizing:border-box;margin:0;padding:0}
:root{
  --bg:#0b0e11; --surface:#11151a; --raised:#171c22; --raised2:#1d242b;
  --line:#222a32; --line-hi:#2f3944;
  --text:#e9edf1; --dim:#7f8a95; --dim2:#4d5762;
  --accent:#e0453a; --accent-soft:rgba(224,69,58,.14);
  --ok:#3fb950; --warn:#d29922;
  --mono:'Consolas','Cascadia Mono',monospace;
  --sans:'Microsoft YaHei','Segoe UI',sans-serif;
}
body{font-family:var(--sans);background:var(--bg);color:var(--text);
     -webkit-font-smoothing:antialiased;overflow:hidden}
.screen{width:1280px;height:820px;display:flex;background:var(--bg);position:relative}
.screen::before{content:"";position:absolute;inset:0;pointer-events:none;
  background-image:radial-gradient(#1a2028 1px, transparent 1px);
  background-size:22px 22px;opacity:.35}
/* ── 侧边栏 ── */
.side{width:214px;flex:0 0 214px;background:var(--surface);border-right:1px solid var(--line);
      padding:22px 0;display:flex;flex-direction:column;position:relative;z-index:2}
.brand{display:flex;align-items:center;gap:10px;padding:0 20px 26px}
.brand .mark{width:30px;height:30px;border-radius:9px;background:var(--accent);
  display:flex;align-items:center;justify-content:center;font-weight:700;font-size:15px;color:#fff}
.brand .nm{font-size:15px;font-weight:600;letter-spacing:.3px}
.brand .nm small{display:block;font-size:10px;color:var(--dim2);font-weight:400;letter-spacing:1.4px}
.grp{padding:0 12px;margin-bottom:16px}
.grp>label{display:block;font-size:10px;color:var(--dim2);letter-spacing:2px;padding:0 8px 8px}
.item{display:flex;align-items:center;gap:10px;padding:8px 10px;border-radius:8px;
      font-size:13px;color:var(--dim);position:relative}
.item .g{width:22px;height:22px;border-radius:6px;background:var(--raised);
        display:flex;align-items:center;justify-content:center;font-size:11px;color:var(--dim)}
.item.on{background:var(--raised);color:var(--text)}
.item.on .g{background:var(--accent-soft);color:var(--accent)}
.item.on::before{content:"";position:absolute;left:-12px;top:8px;bottom:8px;width:2px;
  background:var(--accent);border-radius:2px}
.side .foot{margin-top:auto;padding:14px 20px 0;border-top:1px solid var(--line);
  font-size:11px;color:var(--dim2);display:flex;align-items:center;gap:7px}
.dot{width:6px;height:6px;border-radius:50%;background:var(--ok);box-shadow:0 0 8px var(--ok)}
/* ── 主区 ── */
.main{flex:1;display:flex;flex-direction:column;min-width:0;position:relative;z-index:1}
.top{height:52px;flex:0 0 52px;border-bottom:1px solid var(--line);
     display:flex;align-items:center;padding:0 26px;gap:12px;font-size:12px;color:var(--dim)}
.top .sep{color:var(--dim2)}
.top .right{margin-left:auto;display:flex;align-items:center;gap:16px}
.pill{border:1px solid var(--line-hi);border-radius:999px;padding:4px 12px;font-size:11px;color:var(--dim)}
.body{flex:1;padding:24px 26px;overflow:hidden;display:flex;flex-direction:column;gap:16px}
h1{font-size:26px;font-weight:600;letter-spacing:.5px}
.sub{font-size:12px;color:var(--dim);margin-top:4px}
.row{display:flex;gap:14px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:14px;padding:18px 20px;
      position:relative;overflow:hidden}
.card::before{content:"";position:absolute;left:0;right:0;top:0;height:1px;
  background:linear-gradient(90deg,transparent,var(--line-hi),transparent)}
.card .cap{font-size:11px;color:var(--dim);letter-spacing:1.2px;margin-bottom:10px}
.big{font-family:var(--mono);font-size:46px;font-weight:700;line-height:1;letter-spacing:-1px}
.big small{font-size:15px;color:var(--dim);font-weight:400;margin-left:5px;letter-spacing:0}
.mid{font-family:var(--mono);font-size:26px;font-weight:700}
.k{font-size:12px;color:var(--dim)}
.tag{display:inline-flex;align-items:center;gap:5px;font-size:11px;padding:3px 9px;border-radius:999px;
     border:1px solid var(--line-hi);color:var(--dim)}
.tag.a{border-color:var(--accent);color:var(--accent);background:var(--accent-soft)}
.bar{height:6px;border-radius:3px;background:var(--raised2);overflow:hidden}
.bar i{display:block;height:100%;border-radius:3px;background:var(--accent)}
.bar i.g{background:var(--ok)} .bar i.w{background:var(--warn)}
.hl{color:var(--accent)}
.dim{color:var(--dim)}
.mono{font-family:var(--mono)}
table{width:100%;border-collapse:collapse;font-size:12px}
th{text-align:left;font-weight:400;color:var(--dim2);font-size:10px;letter-spacing:1.2px;
   padding:0 0 8px;border-bottom:1px solid var(--line)}
td{padding:9px 0;border-bottom:1px solid #191f26}
tr:last-child td{border-bottom:none}
"""


def wrap(title, active, inner, extra_style=""):
    """套上侧边栏 + 顶栏的窗口外壳。"""
    groups = [("记录", [("今", "今日", "today"), ("训", "训练记录", "training"),
                        ("食", "饮食记录", "diet")]),
              ("分析", [("体", "人体图", "bodymap"), ("曲", "进步曲线", "progress"),
                        ("数", "身体数据", "body")]),
              ("助手", [("智", "智能体对话", "agent")]),
              ("我的", [("设", "设置", "settings"), ("谢", "关于与致谢", "about")])]
    side = ['<div class="side"><div class="brand"><div class="mark">ch</div>'
            '<div class="nm">changE<small>TRAINING LOG</small></div></div>']
    for label, items in groups:
        side.append(f'<div class="grp"><label>{label}</label>')
        for g, name, key in items:
            on = " on" if key == active else ""
            side.append(f'<div class="item{on}"><span class="g">{g}</span>{name}</div>')
        side.append("</div>")
    side.append('<div class="foot"><span class="dot"></span>本地服务 · 已连接</div></div>')
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<style>{CSS}{extra_style}</style></head><body>
<div class="screen">{''.join(side)}
<div class="main">
  <div class="top"><span>changE</span><span class="sep">/</span><span class="hl">{title}</span>
    <div class="right"><span class="pill">2026-10-20 周二</span>
      <span class="dim">本周 第 43 周</span></div></div>
  <div class="body">{inner}</div>
</div></div></body></html>"""


# ── 每日 ────────────────────────────────────────────────────────
def screen_today():
    blocks = ["胸", "腹", "背", "中", "下", "斜", "肩", "二", "三", "前", "股", "腘", "臀", "小", "内", "外", "颈"]
    vals = [3, 0, 0, 0, 2, 0, 1.5, 0, 1.5, 0, 4, 2, 2, 0, 0, 0, 0]
    heat = "".join(
        f'<div style="flex:1;text-align:center"><div style="height:26px;border-radius:4px;'
        f'background:{fill_for(MUSCLE_REGIONS[k][0]) if v else BASE};margin-bottom:5px"></div>'
        f'<div style="font-size:9px;color:var(--dim2)">{b}</div></div>'
        for b, v, k in zip(blocks, vals, MUSCLE_REGIONS))
    inner = f"""
    <div><h1>今日</h1><div class="sub">练一块，就点亮一块。本周已记 6 次训练。</div></div>
    <div class="row" style="flex:0 0 auto">
      <div class="card" style="flex:1.5">
        <div class="cap">本周容量</div>
        <div style="display:flex;align-items:flex-end;gap:18px">
          <div><div class="big">16<small>组</small></div>
            <div class="k" style="margin-top:8px">折合 <span class="hl mono">42.5</span> 组（主 +1 / 次 +0.5）</div></div>
          <div style="flex:1">
            <div style="display:flex;justify-content:space-between;font-size:10px;color:var(--dim2);
                        letter-spacing:1px;margin-bottom:6px"><span>参考区间</span><span class="mono">10 – 20</span></div>
            <div class="bar"><i style="width:82%"></i></div>
            <div style="display:flex;justify-content:space-between;margin-top:6px">
              <span class="tag a">在参考区间内</span><span class="k">距上限还差 3.5 组</span></div>
          </div>
        </div>
        <div style="margin-top:16px;padding-top:14px;border-top:1px solid var(--line)">
          <div style="display:flex;gap:5px">{heat}</div>
        </div>
      </div>
      <div class="card" style="flex:1">
        <div class="cap">今天吃了多少</div>
        <div class="big">1 840<small>千卡</small></div>
        <div style="margin-top:16px;display:flex;flex-direction:column;gap:10px">
          <div><div style="display:flex;justify-content:space-between;font-size:11px;color:var(--dim)">
            <span>蛋白</span><span class="mono">118 / 140 g</span></div>
            <div class="bar" style="margin-top:5px"><i class="g" style="width:84%"></i></div></div>
          <div><div style="display:flex;justify-content:space-between;font-size:11px;color:var(--dim)">
            <span>碳水</span><span class="mono">196 / 240 g</span></div>
            <div class="bar" style="margin-top:5px"><i style="width:82%"></i></div></div>
          <div><div style="display:flex;justify-content:space-between;font-size:11px;color:var(--dim)">
            <span>脂肪</span><span class="mono">52 / 65 g</span></div>
            <div class="bar" style="margin-top:5px"><i class="w" style="width:80%"></i></div></div>
        </div>
      </div>
    </div>
    <div class="card" style="flex:1">
      <div class="cap">最近训练</div>
      <table><tr><th>日期</th><th>标题</th><th>动作</th><th>组数</th><th>状态</th></tr>
      <tr><td class="mono">10-20</td><td>胸 · 三头</td><td>杠铃卧推、窄距卧推</td><td class="mono">7</td>
          <td><span class="tag a">今天</span></td></tr>
      <tr><td class="mono">10-19</td><td>腿</td><td>深蹲、罗马尼亚硬拉</td><td class="mono">9</td>
          <td><span class="tag">昨天</span></td></tr>
      <tr><td class="mono">10-17</td><td>背 · 二头</td><td>引体、杠铃划船</td><td class="mono">8</td>
          <td><span class="tag">3 天前</span></td></tr>
      <tr><td class="mono">10-15</td><td>肩</td><td>站姿推举、侧平举</td><td class="mono">9</td>
          <td><span class="tag">5 天前</span></td></tr>
      <tr><td class="mono">10-13</td><td>胸 · 三头</td><td>上斜哑铃卧推</td><td class="mono">6</td>
          <td><span class="tag">1 周前</span></td></tr>
      </table>
      <div style="display:flex;gap:26px;margin-top:16px;padding-top:14px;border-top:1px solid var(--line)">
        <div><div class="k">本周训练</div><div class="mid">6 <span class="k">次</span></div></div>
        <div><div class="k">总组数</div><div class="mid">49</div></div>
        <div><div class="k">最常练</div><div class="mid" style="font-size:17px">胸部</div></div>
        <div><div class="k">漏练</div><div class="mid" style="font-size:17px">小腿</div></div>
        <div style="margin-left:auto;align-self:flex-end"><span class="tag">查看全部记录 ›</span></div>
      </div>
    </div>"""
    return wrap("今日", "today", inner)


# ── 训练记录（含动作详情抽屉）───────────────────────────────────
def screen_training():
    extra = """
    .shade{position:absolute;inset:0;background:rgba(6,9,12,.72);z-index:3}
    .drawer{position:absolute;right:0;top:0;bottom:0;width:452px;background:var(--surface);
      border-left:1px solid var(--line-hi);z-index:4;padding:22px 24px;display:flex;
      flex-direction:column;gap:16px;box-shadow:-30px 0 60px rgba(0,0,0,.55)}
    .drawer h3{font-size:19px;font-weight:600}
    .drawer .en{font-size:11px;color:var(--dim);letter-spacing:.6px;margin-top:3px}
    .steps{display:flex;flex-direction:column;gap:11px}
    .step{display:flex;gap:11px;font-size:12.5px;line-height:1.65;color:#c8d0d8}
    .step .n{flex:0 0 20px;height:20px;border-radius:50%;background:var(--raised2);
      color:var(--dim);font-size:10px;font-family:var(--mono);display:flex;
      align-items:center;justify-content:center;margin-top:2px}
    .search{display:flex;gap:8px}
    .sbtn{flex:1;border:1px solid var(--line-hi);border-radius:9px;padding:9px 10px;font-size:11.5px;
      color:var(--dim);display:flex;align-items:center;justify-content:space-between}
    .sbtn span{font-family:var(--mono);color:var(--dim2)}
    """
    inner = f"""
    <div><h1>训练记录</h1><div class="sub">选肌群 → 选动作 → 填组。点「看做法」不会离开这一页。</div></div>
    <div class="row" style="flex:0 0 auto">
      <span class="tag a">胸</span><span class="tag">背</span><span class="tag">腿</span>
      <span class="tag">肩</span><span class="tag">手臂</span><span class="tag">核心</span>
      <span class="tag" style="margin-left:auto">复合动作</span>
    </div>
    <div class="row" style="flex:1">
      <div class="card" style="flex:1.05;align-self:flex-start;width:100%">
        <div class="cap">动作</div>
        <div style="display:flex;flex-direction:column;gap:6px;font-size:13px">
          <div style="padding:9px 10px;border-radius:8px;background:var(--raised);
            border-left:2px solid var(--accent)">杠铃卧推 <span class="dim" style="font-size:11px">· 胸部</span></div>
          <div style="padding:9px 10px;border-radius:8px;color:var(--dim)">上斜哑铃卧推</div>
          <div style="padding:9px 10px;border-radius:8px;color:var(--dim)">双杠臂屈伸</div>
          <div style="padding:9px 10px;border-radius:8px;color:var(--dim)">蝴蝶机夹胸</div>
          <div style="padding:9px 10px;border-radius:8px;color:var(--dim)">龙门架夹胸</div>
          <div style="padding:9px 10px;border-radius:8px;color:var(--dim)">俯卧撑</div>
        </div>
        <div style="margin-top:14px;padding-top:12px;border-top:1px solid var(--line);
                    display:flex;justify-content:space-between;font-size:11px;color:var(--dim2)">
          <span>共 24 个胸部动作</span><span>按使用频率排 ›</span></div>
      </div>
      <div class="card" style="flex:1.35">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
          <div class="cap" style="margin:0">杠铃卧推 · 本次</div>
          <span class="tag a">看做法 ›</span>
        </div>
        <table><tr><th>#</th><th>重量</th><th>次数</th><th>完成</th></tr>
        <tr><td class="mono dim">1</td><td class="mono">60 kg</td><td class="mono">8</td><td class="hl">✓</td></tr>
        <tr><td class="mono dim">2</td><td class="mono">62.5 kg</td><td class="mono">8</td><td class="hl">✓</td></tr>
        <tr><td class="mono dim">3</td><td class="mono">62.5 kg</td><td class="mono">6</td><td class="dim">—</td></tr>
        </table>
        <div style="margin-top:14px;display:flex;gap:8px">
          <span class="tag">上次 62.5 × 8</span><span class="tag">估算 1RM <b class="hl mono">76.5</b></span>
        </div>
      </div>
    </div>
    <div class="shade"></div>
    <div class="drawer">
      <div>
        <div style="display:flex;justify-content:space-between;align-items:flex-start">
          <div><h3>杠铃卧推</h3><div class="en">BARBELL BENCH PRESS · MEDIUM GRIP</div></div>
          <span class="dim" style="font-size:18px">×</span>
        </div>
        <div style="margin-top:11px;display:flex;gap:6px">
          <span class="tag">杠铃</span><span class="tag">力量</span><span class="tag">复合</span>
        </div>
      </div>
      <div>
        <div class="cap">目标肌群</div>
        <div style="display:flex;gap:6px;flex-wrap:wrap">
          <span class="tag a">胸部 · 主要</span><span class="tag">肩部 · 次要</span><span class="tag">肱三头肌 · 次要</span>
        </div>
      </div>
      <div>
        <div class="cap">分步做法</div>
        <div class="steps">
          <div class="step"><span class="n">1</span><span>仰卧在平凳上，双脚踩实地面。掌心朝前握杠，握距略宽于肩。</span></div>
          <div class="step"><span class="n">2</span><span>把横杠从架上取下，双臂锁定，将横杠笔直举在身体上方。这是起始姿势。</span></div>
          <div class="step"><span class="n">3</span><span>吸气并缓慢下放，直到横杠触到胸部中间。</span></div>
          <div class="step"><span class="n">4</span><span>稍作停顿后，呼气的同时把横杠推回起始姿势。在顶端锁定手臂并挤压胸部。</span></div>
        </div>
      </div>
      <div style="margin-top:auto">
        <div class="cap">找教学视频</div>
        <div class="search">
          <div class="sbtn">B 站 · 杠铃卧推<span>复制</span></div>
          <div class="sbtn">YouTube · form<span>复制</span></div>
        </div>
      </div>
    </div>"""
    return wrap("训练记录", "training", inner, extra)


# ── 人体图 ──────────────────────────────────────────────────────
def screen_bodymap():
    extra = """
    .modes{display:flex;gap:8px}
    .mode{border:1px solid var(--line-hi);border-radius:9px;padding:7px 15px;font-size:12px;color:var(--dim)}
    .mode.on{border-color:var(--accent);color:var(--text);background:var(--accent-soft)}
    .figs{display:flex;gap:36px;justify-content:center;align-items:flex-end;flex:1}
    .figcap{text-align:center;font-size:11px;color:var(--dim2);letter-spacing:2px;margin-bottom:8px}
    .panel{width:298px;flex:0 0 298px;background:var(--surface);border:1px solid var(--line);
      border-radius:14px;padding:18px;display:flex;flex-direction:column;gap:13px}
    """
    inner = f"""
    <div style="display:flex;align-items:flex-end;justify-content:space-between">
      <div><h1>人体图</h1><div class="sub">练了哪块、漏了哪块，一眼看出来。</div></div>
      <div class="modes"><span class="mode on">本周组数</span><span class="mode">恢复中</span>
        <span class="mode">长期未练</span></div>
    </div>
    <div class="row" style="flex:1;align-items:stretch">
      <div class="card" style="flex:1;display:flex;flex-direction:column">
        <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--dim2)">
          <span class="mono">2026-10-19 → 2026-10-25</span>
          <span>颜色越深 = 本周越多 · 10 组封顶</span></div>
        <div class="figs" style="margin-top:8px">
          <div><div class="figcap">正 面</div>{body_svg(FRONT, '0 0 33 93', 214)}</div>
          <div><div class="figcap">背 面</div>{body_svg(BACK, '36 0 33 93', 214)}</div>
        </div>
      </div>
      <div class="panel">
        <div>
          <div style="font-size:15px;font-weight:600">胸部</div>
          <div class="dim" style="font-size:11px;margin-top:2px">CHEST</div>
        </div>
        <div><div class="cap" style="margin-bottom:6px">本周</div>
          <div class="big">3<small>组</small></div></div>
        <div>
          <div style="display:flex;justify-content:space-between;font-size:10px;color:var(--dim2);margin-bottom:6px">
            <span>参考区间</span><span class="mono">10 – 20 组</span></div>
          <div class="bar"><i style="width:30%"></i></div>
          <div style="margin-top:8px"><span class="tag a">低于参考区间</span></div>
        </div>
        <div style="margin-top:4px;padding-top:12px;border-top:1px solid var(--line);flex:1">
          <div class="cap">这个肌群的动作</div>
          <div style="display:flex;flex-direction:column;gap:7px;font-size:12px">
            <div style="display:flex;justify-content:space-between"><span>杠铃卧推</span><span class="mono dim">1RM 76.5</span></div>
            <div style="display:flex;justify-content:space-between"><span>上斜哑铃卧推</span><span class="mono dim">1RM 32</span></div>
            <div style="display:flex;justify-content:space-between"><span>双杠臂屈伸</span><span class="mono dim">自重</span></div>
          </div>
        </div>
      </div>
    </div>"""
    return wrap("人体图", "bodymap", inner, extra)


# ── 进步曲线 ────────────────────────────────────────────────────
def spark(vals, w=238, h=250, color=ACCENT):
    lo, hi = min(vals), max(vals)
    span = (hi - lo) or 1
    pts = []
    for i, v in enumerate(vals):
        x = 6 + i * (w - 12) / max(1, len(vals) - 1)
        y = h - 8 - (v - lo) / span * (h - 18)
        pts.append(f"{x:.1f},{y:.1f}")
    return (f'<svg width="{w}" height="{h}"><polyline points="{" ".join(pts)}" fill="none" '
            f'stroke="{color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>'
            f'<circle cx="{float(pts[-1].split(",")[0]):.1f}" cy="{float(pts[-1].split(",")[1]):.1f}" '
            f'r="3.4" fill="{color}"/></svg>')


def screen_progress():
    extra = """
    .chart{background:var(--surface);border:1px solid var(--line);border-radius:14px;
      padding:15px 17px;flex:1;display:flex;flex-direction:column;position:relative;overflow:hidden}
    .chart::before{content:"";position:absolute;left:0;right:0;top:0;height:1px;
      background:linear-gradient(90deg,transparent,var(--line-hi),transparent)}
    """
    inner = f"""
    <div><h1>进步曲线</h1><div class="sub">同一个动作，三条曲线分开画 —— 量纲不同，叠在一起会互相压平。</div></div>
    <div class="row" style="flex:0 0 auto;align-items:center">
      <span class="tag a">杠铃卧推</span><span class="tag">上斜哑铃卧推</span><span class="tag">双杠臂屈伸</span>
      <span class="tag" style="margin-left:auto">最近 6 次训练</span>
    </div>
    <div class="row" style="flex:1">
      <div class="chart">
        <div class="cap">估算 1RM</div>
        <div class="big" style="font-size:34px">78.0<small>kg</small></div>
        <div style="font-size:11px;color:var(--ok);margin-top:4px">▲ 2.0 · 六周</div>
        <div style="margin-top:auto">{spark([71.5, 73.0, 74.0, 75.0, 76.0, 78.0])}</div>
        <div style="display:flex;justify-content:space-between;font-size:10px;color:var(--dim2);margin-top:4px">
          <span class="mono">09-08</span><span class="mono">10-20</span></div>
      </div>
      <div class="chart">
        <div class="cap">最大重量</div>
        <div class="big" style="font-size:34px">65.0<small>kg</small></div>
        <div style="font-size:11px;color:var(--ok);margin-top:4px">▲ 5.0 · 六周</div>
        <div style="margin-top:auto">{spark([60, 60, 62.5, 62.5, 62.5, 65])}</div>
        <div style="display:flex;justify-content:space-between;font-size:10px;color:var(--dim2);margin-top:4px">
          <span class="mono">09-08</span><span class="mono">10-20</span></div>
      </div>
      <div class="chart">
        <div class="cap">总容量（吨位 × 次）</div>
        <div class="big" style="font-size:34px">1 920<small>kg</small></div>
        <div style="font-size:11px;color:var(--dim);margin-top:4px">◆ 持平 · 六周</div>
        <div style="margin-top:auto">{spark([1440, 1620, 1500, 1750, 1875, 1920])}</div>
        <div style="display:flex;justify-content:space-between;font-size:10px;color:var(--dim2);margin-top:4px">
          <span class="mono">09-08</span><span class="mono">10-20</span></div>
      </div>
    </div>
    <div class="card">
      <div style="display:flex;gap:26px;font-size:12px">
        <div><div class="k">估算 1RM</div><div class="mid">78.0 <span class="k">kg</span></div></div>
        <div><div class="k">-best 单组</div><div class="mid">65×6</div></div>
        <div><div class="k">总吨位</div><div class="mid">7 480 <span class="k">kg</span></div></div>
        <div><div class="k">估算口径</div><div class="k" style="margin-top:6px">Epley · 只算 12 次以内的组</div></div>
      </div>
    </div>"""
    return wrap("进步曲线", "progress", inner, extra)


# ── 身体数据 ────────────────────────────────────────────────────
def screen_body():
    cards = [("体重", "72.4", "kg", "▼ 0.6", "g"), ("体脂", "15.8", "%", "▼ 0.3", "g"),
             ("腰围", "80.0", "cm", "— 0", ""), ("胸围", "101", "cm", "▲ 0.5", "a"),
             ("臀围", "95.0", "cm", "— 0", ""), ("大腿", "57.5", "cm", "▲ 0.5", "a")]
    grid = "".join(
        f'<div class="card" style="flex:1"><div class="cap">{n}</div>'
        f'<div class="big" style="font-size:32px">{v}<small>{u}</small></div>'
        f'<div style="font-size:11px;margin-top:6px;color:var(--{"ok" if c == "g" else "accent" if c == "a" else "dim"})">'
        f'{d}</div></div>'
        for n, v, u, d, c in cards)
    inner = f"""
    <div><h1>身体数据</h1><div class="sub">九项指标，每周记一两次就够。没记过的显示「—」，不是 0。</div></div>
    <div class="row" style="flex:0 0 auto">{grid}</div>
    <div class="card" style="flex:1">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div class="cap" style="margin:0">时间线</div>
        <span class="tag">全部指标</span>
      </div>
      <table><tr><th>日期</th><th>指标</th><th>数值</th><th>备注</th></tr>
      <tr><td class="mono">10-19</td><td>体重</td><td class="mono hl">72.4 kg</td><td class="dim">早上空腹</td></tr>
      <tr><td class="mono">10-19</td><td>腰围</td><td class="mono">80.0 cm</td><td class="dim">—</td></tr>
      <tr><td class="mono">10-12</td><td>体重</td><td class="mono">73.0 kg</td><td class="dim">训练后</td></tr>
      <tr><td class="mono">10-12</td><td>体脂</td><td class="mono">16.1 %</td><td class="dim">—</td></tr>
      </table>
    </div>"""
    return wrap("身体数据", "body", inner)


# ── 饮食记录 ────────────────────────────────────────────────────
def screen_diet():
    def macro(name, val, tot, cls):
        pct = int(val / tot * 100)
        return (f'<div style="flex:1"><div style="display:flex;justify-content:space-between;'
                f'font-size:11px;color:var(--dim)"><span>{name}</span>'
                f'<span class="mono">{val} / {tot} g</span></div>'
                f'<div class="bar" style="margin-top:6px"><i class="{cls}" style="width:{pct}%"></i></div></div>')
    meals = [("早餐", "08:10", "燕麦 60g · 鸡蛋 2 个 · 黑咖啡", 520),
             ("午餐", "12:30", "米饭 200g · 鸡胸 150g · 西兰花", 760),
             ("加餐", "16:00", "希腊酸奶 150g · 蓝莓 80g", 210),
             ("晚餐", "19:20", "—（还没记）", 0)]
    rows = "".join(
        f'<tr><td><b>{n}</b><div class="dim" style="font-size:10px;margin-top:2px">{t}</div></td>'
        f'<td class="dim">{items}</td>'
        f'<td class="mono" style="text-align:right;{"color:var(--dim2)" if kcal == 0 else "color:var(--text)"}">'
        f'{("—" if kcal == 0 else str(kcal) + " kcal")}</td></tr>'
        for n, t, items, kcal in meals)
    inner = f"""
    <div style="display:flex;align-items:flex-end;justify-content:space-between">
      <div><h1>饮食记录</h1><div class="sub">记个大概就行 —— 精度不是目标，可修正是。</div></div>
      <div style="display:flex;gap:8px">
        <span class="tag">‹ 前一天</span><span class="tag a">10-20 周二</span><span class="tag">后一天 ›</span></div>
    </div>
    <div class="row" style="flex:0 0 auto">
      <div class="card" style="flex:1">
        <div class="cap">今日摄入</div>
        <div style="display:flex;align-items:center;gap:22px">
          <div class="big">1 490<small>千卡</small></div>
          <div style="flex:1;display:flex;gap:16px">
            {macro("蛋白", 96, 140, "g")}{macro("碳水", 158, 240, "")}{macro("脂肪", 44, 65, "w")}
          </div>
        </div>
      </div>
    </div>
    <div class="card" style="flex:1">
      <div class="cap">今天吃了什么</div>
      <table><tr><th style="width:130px">餐次</th><th>条目</th><th style="text-align:right">热量</th></tr>
      {rows}</table>
    </div>"""
    return wrap("饮食记录", "diet", inner)


# ── 智能体对话 ──────────────────────────────────────────────────
def screen_agent():
    extra = """
    .chat{flex:1;display:flex;flex-direction:column;gap:14px;overflow:hidden}
    .msg{max-width:74%;font-size:13px;line-height:1.7;padding:11px 15px;border-radius:13px}
    .me{align-self:flex-end;background:var(--raised2);border:1px solid var(--line-hi);border-bottom-right-radius:4px}
    .ai{align-self:flex-start;background:var(--surface);border:1px solid var(--line);border-bottom-left-radius:4px}
    .tool{align-self:flex-start;background:var(--raised);border:1px solid var(--line-hi);
      border-radius:11px;padding:12px 15px;font-size:12px;width:76%;position:relative}
    .tool .hd{display:flex;align-items:center;gap:8px;font-size:11px;color:var(--dim2);
      letter-spacing:1px;margin-bottom:8px}
    .tool .hd b{color:var(--ok);font-weight:400}
    .inbox{margin-top:auto;border:1px solid var(--line-hi);border-radius:13px;padding:12px 14px;
      display:flex;align-items:center;gap:12px;background:var(--surface)}
    """
    inner = f"""
    <div><h1>智能体对话</h1><div class="sub">说一句就记下来。写操作一律先给你看摘要，确认了才落库。</div></div>
    <div class="chat">
      <div class="msg me">今天卧推 60 公斤做了 3 组，每组 8 个</div>
      <div class="msg ai">看到了。我准备记一条：</div>
      <div class="tool">
        <div class="hd">准备写入 · <b>待确认</b></div>
        <div style="display:flex;flex-direction:column;gap:6px">
          <div style="display:flex;justify-content:space-between"><span class="dim">日期</span><span class="mono">2026-10-20</span></div>
          <div style="display:flex;justify-content:space-between"><span class="dim">动作</span><span>杠铃卧推 · 胸部</span></div>
          <div style="display:flex;justify-content:space-between"><span class="dim">组数</span><span class="mono">3 × 8 次 @ 60 kg</span></div>
          <div style="display:flex;justify-content:space-between"><span class="dim">估算 1RM</span><span class="mono hl">76.0 kg</span></div>
        </div>
        <div style="display:flex;gap:8px;margin-top:12px">
          <span class="tag a">确认写入</span><span class="tag">改一下</span><span class="tag">取消</span></div>
      </div>
      <div class="msg me">确认</div>
      <div class="tool">
        <div class="hd">已写入 · <b>已记录</b></div>
        <div class="dim" style="font-size:11.5px">训练 #43 · 3 组 · 胸部折合 3.0 组。人体图上的胸部已更新。</div>
      </div>
    </div>
    <div class="inbox">
      <span class="dim" style="font-size:13px;flex:1">说一句话，或者…</span>
      <span class="tag">拍照</span><span class="tag">语音</span>
      <span style="width:30px;height:30px;border-radius:9px;background:var(--accent);
        display:flex;align-items:center;justify-content:center;font-size:13px;color:#fff">↑</span>
    </div>"""
    return wrap("智能体对话", "agent", inner, extra)


SCREENS = {
    "01-today": screen_today,
    "02-training": screen_training,
    "03-bodymap": screen_bodymap,
    "04-progress": screen_progress,
    "05-body": screen_body,
    "06-diet": screen_diet,
    "07-agent": screen_agent,
}

for slug, fn in SCREENS.items():
    html = fn()
    p = OUT / f"{slug}.html"
    p.write_bytes(html.encode("utf-8"))
    print(f"  {p.name}  {len(html):,} 字符")
print(f"\n输出目录: {OUT}")
