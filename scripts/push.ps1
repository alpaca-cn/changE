# 把本地改动推到 GitHub。
#
# 用法：
#   .\scripts\push.ps1
#   .\scripts\push.ps1 -Message "fix: 修了饮食页的删除按钮"
#
# 不做的事：**不用 -f / --force**。强推会覆盖远端别人的提交，
# 在只有你一个人的仓库里也一样危险 —— 你在另一台机器上的提交会没了。

param(
    [string]$Message = ''
)

$ErrorActionPreference = 'Stop'

# 当前分支名。不硬编码 main：以后开了分支也照样能用。
function Get-CurrentBranch {
    return (git rev-parse --abbrev-ref HEAD).Trim()
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # ── 1. 远端可达吗 ────────────────────────────────────────
    # 先探一下：不然 git add/commit 之后才发现推不上去，
    # 会让人以为「提交丢了」，其实只是没推出去。
    Write-Host '检查网络…' -ForegroundColor DarkGray
    $reachable = $false
    for ($i = 1; $i -le 3; $i++) {
        git ls-remote --heads origin *> $null
        if ($LASTEXITCODE -eq 0) { $reachable = $true; break }
        Write-Host "  第 $i 次连不上 github.com，重试…" -ForegroundColor Yellow
        Start-Sleep -Seconds 3
    }
    if (-not $reachable) {
        Write-Host '连不上 GitHub。VPN / 代理开了吗？网络好了再跑一次。' -ForegroundColor Red
        Write-Host '（本地改动不会丢，提交也还在本地。）' -ForegroundColor DarkGray
        exit 1
    }

    # ── 2. 有没有改动 ────────────────────────────────────────
    $status = @(git status --porcelain)

    if ($status.Count -eq 0) {
        Write-Host '没有新改动。' -ForegroundColor DarkGray
    }
    else {
        Write-Host "有 $($status.Count) 个文件改动：" -ForegroundColor Cyan
        $status | ForEach-Object { Write-Host "  $_" }

        if ($Message -eq '') {
            $Message = Read-Host '写一句这次改了什么（一句话就行）'
        }
        if ($Message -eq '') {
            Write-Host '没有提交信息，取消。' -ForegroundColor Red
            exit 1
        }

        git add -A
        git commit -m $Message
        if ($LASTEXITCODE -ne 0) {
            Write-Host '提交失败，看上面的报错。' -ForegroundColor Red
            exit 1
        }
    }

    # ── 3. 推 ────────────────────────────────────────────────
    $branchName = Get-CurrentBranch
    Write-Host "推送 $branchName …" -ForegroundColor DarkGray
    $pushed = $false
    for ($i = 1; $i -le 5; $i++) {
        git push origin $branchName 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { $pushed = $true; break }
        Write-Host "  第 $i 次失败，重试…" -ForegroundColor Yellow
        Start-Sleep -Seconds 4
    }

    if (-not $pushed) {
        Write-Host '推送失败（网络）。提交已经在本地，网络好了再跑一次这个脚本。' -ForegroundColor Red
        exit 1
    }

    Write-Host ''
    Write-Host '已推送到 GitHub' -ForegroundColor Green
    Write-Host '  https://github.com/alpaca-cn/changE/commits/main' -ForegroundColor Cyan
}
finally {
    Pop-Location
}
