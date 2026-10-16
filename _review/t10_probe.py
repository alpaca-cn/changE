"""Task 10 / A: 生产形态实测。

模拟 Tauri 外壳的行为：从**另一个工作目录**起 jar 子进程，
按前缀读它的 stdout 找 CHANGE_PORT=，然后访问那个端口，
再看它到底 listen 在哪个地址上。

stdout / stderr 分别重定向到两个文件，以便回答
「日志走 stdout 还是 stderr」「协议行是否恰好一行且无污染」。
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error

sys.stdout.reconfigure(encoding="utf-8")

JAVA = r"C:\Users\alpaca\.jdks\openjdk-25.0.2\bin\java.exe"
JAR = r"E:\idea\program\change\backend\target\change-backend-0.1.0-SNAPSHOT.jar"
RUNDIR = os.path.join(os.environ["TEMP"], "change-t10-probe")

shutil.rmtree(RUNDIR, ignore_errors=True)
os.makedirs(RUNDIR, exist_ok=True)

out_path = os.path.join(RUNDIR, "stdout.txt")
err_path = os.path.join(RUNDIR, "stderr.txt")
out = open(out_path, "wb")
err = open(err_path, "wb")

print("=== 启动 ===")
print("cwd      =", RUNDIR)
print("java     =", JAVA)
print("jar      =", JAR)
proc = subprocess.Popen([JAVA, "-jar", JAR], cwd=RUNDIR,
                        stdout=out, stderr=err, stdin=subprocess.DEVNULL)
print("PID      =", proc.pid)

# ---- 1) 按前缀读 stdout 找协议行（外壳要做的动作）----
start = time.time()
protocol = None
while time.time() - start < 120:
    if proc.poll() is not None:
        print("!! 进程提前退出，exitcode =", proc.returncode)
        break
    out.flush()
    try:
        with open(out_path, "rb") as f:
            data = f.read().decode("utf-8", errors="replace")
    except OSError:
        data = ""
    for line in data.splitlines():
        if line.startswith("CHANGE_PORT="):
            protocol = line
            break
    if protocol:
        break
    time.sleep(0.2)

elapsed = time.time() - start
print()
print("=== 1) stdout 上的协议行 ===")
print("发现耗时 = %.2f s" % elapsed)
print("原始行   = [%s]" % protocol)
if protocol is None:
    print("!! 没找到 CHANGE_PORT= 行")
    proc.kill()
    sys.exit(2)

port_str = protocol[len("CHANGE_PORT="):]
print("后缀     = [%s]  isdigit=%s  长度=%d" % (port_str, port_str.isdigit(), len(port_str)))
port = int(port_str)

# 那一行的原始字节，确认没有 BOM / CR / 日志前缀混入
with open(out_path, "rb") as f:
    raw = f.read()
for i, bline in enumerate(raw.split(b"\n")):
    if bline.startswith(b"CHANGE_PORT="):
        print("该行原始字节 =", bline)
        print("该行字节数   =", len(bline), " (应为 %d)" % (len("CHANGE_PORT=") + len(port_str)))

# ---- 2) 端口真的能访问 ----
print()
print("=== 2) curl 等价：GET /api/health ===")
req = urllib.request.Request("http://127.0.0.1:%d/api/health" % port,
                            headers={"Accept": "application/json"})
try:
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = resp.read().decode("utf-8")
        print("HTTP 状态 =", resp.status)
        print("HTTP 头   =", dict(resp.headers))
        print("HTTP body =", body)
        print("JSON      =", json.loads(body))
except urllib.error.URLError as e:
    print("!! 访问失败:", e)

# ---- 3) 监听地址 ----
print()
print("=== 3) 监听地址 ===")
try:
    ns = subprocess.run(["netstat", "-ano"], capture_output=True, text=True,
                        encoding="utf-8", errors="replace").stdout
    print("--- netstat -ano 里属于 PID %d 的行 ---" % proc.pid)
    for line in ns.splitlines():
        parts = line.split()
        if parts and parts[-1] == str(proc.pid):
            print(line.rstrip())
except Exception as e:
    print("netstat 失败:", e)

try:
    ps = subprocess.run(["powershell", "-NoProfile", "-Command",
                         "Get-NetTCPConnection -OwningProcess %d -State Listen | "
                         "Select-Object LocalAddress,LocalPort,State,OwningProcess | "
                         "Format-Table -AutoSize | Out-String" % proc.pid],
                        capture_output=True, text=True, encoding="utf-8",
                        errors="replace").stdout
    print("--- Get-NetTCPConnection -OwningProcess %d -State Listen ---" % proc.pid)
    print(ps.rstrip())
except Exception as e:
    print("Get-NetTCPConnection 失败:", e)

# 直接连 127.0.0.1 与 0.0.0.0 语义对比：确认不是绑在 :: / 0.0.0.0
print()
print("--- 端口 %d 的对外可达性探测 ---" % port)
for host in ("127.0.0.1",):
    s = socket.socket()
    s.settimeout(3)
    try:
        s.connect((host, port))
        print("  %-10s connect OK" % host)
    except Exception as e:
        print("  %-10s connect FAIL %s" % (host, e))
    finally:
        s.close()

# 本机非回环地址是否可达（若绑 0.0.0.0/:: 则应当可达）
local_ips = []
try:
    for info in socket.getaddrinfo(socket.gethostname(), None):
        ip = info[4][0]
        if ip not in local_ips and not ip.startswith("127."):
            local_ips.append(ip)
except Exception:
    pass
for ip in local_ips:
    s = socket.socket()
    s.settimeout(3)
    try:
        s.connect((ip, port))
        print("  %-10s connect OK   <-- 说明绑到了非回环地址！" % ip)
    except Exception as e:
        print("  %-10s connect FAIL (期望失败) %s" % (ip, type(e).__name__))
    finally:
        s.close()

# ---- 4) stdout / stderr 归属与协议行纯净度 ----
out.flush()
err.flush()
time.sleep(0.5)
with open(out_path, "rb") as f:
    stdout_text = f.read().decode("utf-8", errors="replace")
with open(err_path, "rb") as f:
    stderr_text = f.read().decode("utf-8", errors="replace")

print()
print("=== 4) stdout / stderr 归属 ===")
print("stdout 行数 =", len(stdout_text.splitlines()))
print("stderr 行数 =", len(stderr_text.splitlines()))
proto_in_out = [l for l in stdout_text.splitlines() if l.startswith("CHANGE_PORT=")]
proto_in_err = [l for l in stderr_text.splitlines() if l.startswith("CHANGE_PORT=")]
print("stdout 里以 CHANGE_PORT= 开头的行数 =", len(proto_in_out), proto_in_out)
print("stderr 里以 CHANGE_PORT= 开头的行数 =", len(proto_in_err))
print("stdout 里包含 CHANGE_PORT 的行数 =",
      len([l for l in stdout_text.splitlines() if "CHANGE_PORT" in l]))
print()
print("--- stdout 全文（%d 行）---" % len(stdout_text.splitlines()))
print(stdout_text)
print("--- stderr 全文（%d 行）---" % len(stderr_text.splitlines()))
print(stderr_text)

proc.kill()
proc.wait()
out.close()
err.close()
print("=== KILLED exitcode =", proc.returncode, "===")
