# 发一个版本（自动更新靠这一步转起来）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/release.ps1 -Notes "这一版改了什么"
#
# 只重发一个已有的构建（不重新编译）：
#   powershell -ExecutionPolicy Bypass -File scripts/release.ps1 -Notes "..." -SkipBuild
#
# 需要先做的两件事（只有第一次）：
#   winget install GitHub.cli
#   gh auth login
#
# 为什么需要这个脚本而不是手敲：
#   1. 签名产物（.exe.sig）必须和 exe 一起发，漏一个更新器就装不上
#   2. latest.json 里的 signature 必须与 .sig **逐字节**一致（多一个换行都会验签失败）
#   3. 版本号必须比用户手上那个高，否则更新器认为「已是最新」
# 这三件事手敲都会出错，而且出错了界面上只会说「检查失败」。

param(
    [Parameter(Mandatory = $true)][string]$Notes,
    [switch]$SkipBuild,
    [string]$Repo = 'alpaca-cn/alpaca'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($msg) {
    Write-Host ""
    Write-Host "错误: $msg" -ForegroundColor Red
    exit 1
}

# ── 1. 版本号 ──────────────────────────────────────────────
$confPath = 'frontend/src-tauri/tauri.conf.json'
$conf = Get-Content $confPath -Raw -Encoding UTF8 | ConvertFrom-Json
$version = $conf.version
$pkg = Get-Content 'frontend/package.json' -Raw -Encoding UTF8 | ConvertFrom-Json
if ($pkg.version -ne $version) {
    Fail "版本号不一致：tauri.conf.json 是 $version，package.json 是 $($pkg.version)。两处必须相同。"
}
Write-Host "版本: $version" -ForegroundColor Cyan

# ── 2. 发布条件 ────────────────────────────────────────────
$remote = git remote -v 2>$null
if (-not $remote) {
    Fail "还没有配 git remote。先跑：git remote add origin https://github.com/$Repo.git && git push -u origin main"
}
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    Fail "没有安装 GitHub CLI。先跑：winget install GitHub.cli，再跑：gh auth login"
}

# ── 3. 构建 ────────────────────────────────────────────────
$nsis = 'frontend/src-tauri/target/release/bundle/nsis'
$exe = Join-Path $nsis "changE_${version}_x64-setup.exe"
$sig = "$exe.sig"

if (-not $SkipBuild) {
    $keyPath = 'keys/changE-updater.key'
    if (-not (Test-Path $keyPath)) {
        Fail "找不到签名私钥 $keyPath。没有它就签不出更新包（这一步不可跳过）。"
    }
    $env:TAURI_SIGNING_PRIVATE_KEY = Get-Content $keyPath -Raw
    $env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD = ''

    Write-Host "打包后端…" -ForegroundColor Cyan
    Push-Location backend
    & mvn -B -q clean -DskipTests package
    if ($LASTEXITCODE -ne 0) { Pop-Location; Fail "mvn package 失败" }
    $jars = Get-ChildItem 'target/change-backend-*.jar' | Where-Object { $_.Name -notmatch 'original' }
    if ($jars.Count -ne 1) { Pop-Location; Fail "target 里应当只有一个 jar，实际 $($jars.Count) 个" }
    if ($jars[0].Length -lt 20MB) { Pop-Location; Fail "jar 只有 $([math]::Round($jars[0].Length/1MB,1)) MB —— 这是瘦 jar，repackage 失败了" }
    Copy-Item $jars[0].FullName '../frontend/src-tauri/resources/change-backend.jar' -Force
    Pop-Location

    Write-Host "打包安装包并签名…" -ForegroundColor Cyan
    Push-Location frontend
    Remove-Item "$nsis/*.exe" -Force -ErrorAction SilentlyContinue
    & pnpm tauri build
    if ($LASTEXITCODE -ne 0) { Pop-Location; Fail "tauri build 失败" }
    Pop-Location
}

if (-not (Test-Path $exe)) { Fail "找不到 $exe" }
if (-not (Test-Path $sig)) { Fail "找不到 $sig —— 没有签名，更新器会拒绝安装" }
Write-Host ("安装包: {0:N2} MB" -f ((Get-Item $exe).Length / 1MB)) -ForegroundColor Cyan

# ── 4. latest.json ────────────────────────────────────────
$signature = (Get-Content $sig -Raw).Trim()
if ($signature.Length -lt 20) { Fail ".sig 内容看起来不对（长度 $($signature.Length)）" }

$tag = "v$version"
$assetUrl = "https://github.com/$Repo/releases/download/$tag/changE_${version}_x64-setup.exe"

$latest = [ordered]@{
    version   = $version
    notes     = $Notes
    pub_date  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    platforms = [ordered]@{
        'windows-x86_64' = [ordered]@{
            signature = $signature
            url       = $assetUrl
        }
    }
}
$latestPath = Join-Path $nsis 'latest.json'
# 无 BOM：更新器的 JSON 解析器对 BOM 不一定宽容
[System.IO.File]::WriteAllText(
    (Resolve-Path $nsis).Path + '/latest.json',
    ($latest | ConvertTo-Json -Depth 5),
    (New-Object System.Text.UTF8Encoding $false))
Write-Host "latest.json 已写: $latestPath" -ForegroundColor Cyan

# ── 5. 发 release ─────────────────────────────────────────
$exists = gh release view $tag --repo $Repo 2>$null
if ($exists) {
    Write-Host "release $tag 已存在，上传（覆盖同名资产）…" -ForegroundColor Yellow
    gh release upload $tag "$exe" "$sig" "$latestPath" --repo $Repo --clobber
} else {
    Write-Host "创建 release $tag…" -ForegroundColor Cyan
    gh release create $tag "$exe" "$sig" "$latestPath" `
        --repo $Repo --title "changE $tag" --notes $Notes
}
if ($LASTEXITCODE -ne 0) { Fail "gh release 失败" }

# ── 6. 提示 ───────────────────────────────────────────────
Write-Host ""
Write-Host "发布完成: https://github.com/$Repo/releases/tag/$tag" -ForegroundColor Green
Write-Host ""
Write-Host "下一步：打开**旧版**应用，关于页点「检查更新」。" -ForegroundColor Cyan
Write-Host "  - 显示「有新版本 $version」→ 成功，点「下载并安装」"
Write-Host "  - 显示「检查失败: 404」→ latest.json 没上传成功，或 tag 不是 latest"
Write-Host "  - 显示「签名不匹配」→ latest.json 里的 signature 与 .sig 不一致"
Write-Host ""
Write-Host "注意：这次发布的版本必须比用户手上那个**高**。同一个版本号重复发，更新器会说「已是最新」。" -ForegroundColor Yellow
