"""准备一个可以直接在 GitHub 网页上拖拽上传的发版包。

## 为什么需要这个

`gh` 装不上（winget 的网络被挡，0x80072efd），但 github.com 本身是通的
（git push 成功过）。所以第一次发版走网页手动上传：把这三个文件拖进 release 页就行。

## 三个文件都要

- `changE_<ver>_x64-setup.exe`      安装包本体（新用户装它，更新器也下载它）
- `.exe.sig`                        签名。更新器验签用它，漏了直接拒绝安装
- `latest.json`                     清单。更新器先拉它，再决定要不要下载

## latest.json 里最容易错的一处

`signature` 必须与 `.sig` **逐字节一致**（多一个换行就验签失败），
`url` 必须指向这个 release 下的**资产原始链接**（不是 release 页面）。
"""
import json
import pathlib
import shutil

ROOT = pathlib.Path(r"E:\idea\program\change")
NSIS = ROOT / "frontend/src-tauri/target/release/bundle/nsis"
# 两个投放位置，各要一份清单：
#   oss/     url -> 阿里云 OSS（主下载源，国内快）
#   github/  url -> GitHub release 资产（同时是**给朋友直接下载**的页面，
#            也是 OSS 出问题时的兜底）
#
# 为什么必须两份而不是一份：更新器先读 OSS 的清单，读不到才读 GitHub 的。
# 如果 GitHub 那份的 url 也指向 OSS，那么 OSS 一挂，兜底清单指向的还是挂的那个 ——
# 备份就名存实亡。
OSS_BASE = "https://change-release.oss-cn-beijing.aliyuncs.com"
REPO = "alpaca-cn/changE"

conf = json.loads((ROOT / "frontend/src-tauri/tauri.conf.json").read_text(encoding="utf-8"))
version = conf["version"]
tag = f"v{version}"
exe_name = f"changE_{version}_x64-setup.exe"

exe = NSIS / exe_name
sig = NSIS / f"{exe_name}.sig"
assert exe.exists(), f"找不到 {exe}"
assert sig.exists(), f"找不到 {sig} —— 没有签名，更新器会拒绝安装"

signature = sig.read_text(encoding="utf-8").strip()
assert len(signature) > 20, f".sig 内容可疑（长度 {len(signature)}）"

def write_release(dest_name: str, url: str) -> pathlib.Path:
    """把一个投放位置的文件写好，返回目录。"""
    dest = ROOT / "artifacts" / "release" / dest_name
    dest.mkdir(parents=True, exist_ok=True)
    for f in dest.iterdir():
        if f.is_file():
            f.unlink()

    shutil.copy2(exe, dest / exe_name)
    shutil.copy2(sig, dest / f"{exe_name}.sig")

    latest = {
        "version": version,
        "notes": notes,
        "pub_date": "2026-10-23T11:10:00Z",
        "platforms": {"windows-x86_64": {"signature": signature, "url": url}},
    }
    # 无 BOM：更新器的 JSON 解析对 BOM 不一定宽容
    (dest / "latest.json").write_text(
        json.dumps(latest, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    return dest


notes = ((ROOT / "docs" / "release-notes.txt").read_text(encoding="utf-8").strip()
         if (ROOT / "docs" / "release-notes.txt").exists()
         else "首个带自动更新的版本。")

oss_dir = write_release("oss", f"{OSS_BASE}/{exe_name}")
gh_dir = write_release("github",
                       f"https://github.com/{REPO}/releases/download/{tag}/{exe_name}")

print()
for label, d in (("上传到 OSS", oss_dir), ("上传到 GitHub release", gh_dir)):
    print(f"  {label}:  {d}")
    for f in sorted(d.iterdir()):
        print(f"    {f.name:<42} {f.stat().st_size:>11,} B")
print()
print("  两份 latest.json 的差别只在 url：")
print(f"    OSS    版本 -> {OSS_BASE}/{exe_name}")
print(f"    GitHub 版本 -> https://github.com/{REPO}/releases/download/{tag}/{exe_name}")
