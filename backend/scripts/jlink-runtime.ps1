#requires -Version 5.1
<#
.SYNOPSIS
  Build a trimmed JRE with jlink, for bundling into the installer.

.DESCRIPTION
  Why this script exists: the installer goes to friends' machines, and those
  machines do NOT have a JDK. We cannot assume Java is installed, and we cannot
  ask them to install it.

  Why the module list is hard-coded here: it is the exact set that was verified
  on 2026-10-20 to actually run the whole backend (startup, Flyway, SQLite,
  Hikari, Jackson, Tomcat, the 876-exercise import):

      Started ChangeApplication in 6.832 seconds
      imported 876 exercises / 876 Chinese names / 871 Chinese instructions / 35 foods
      CHANGE_PORT=55317 -> GET /api/health -> 200

  If you change this list, you MUST re-run a real startup against the produced
  runtime. A missing module shows up as NoClassDefFoundError at startup -- and
  that will happen on a friend's machine, not yours.

  NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads a
  BOM-less UTF-8 script as ANSI, which mangles non-ASCII text and breaks quoting.
  Keeping it ASCII means it runs the same under both 5.1 and 7.

.PARAMETER OutDir
  Output directory. Defaults to frontend/src-tauri/resources/runtime, which is
  where bundle.resources points.

.PARAMETER Force
  Delete the output directory first. Without this, an existing directory is an
  error, so we never end up with a half-old/half-new JRE.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File backend/scripts/jlink-runtime.ps1
#>
[CmdletBinding()]
param(
    [string] $OutDir = "frontend/src-tauri/resources/runtime",
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

# ---- 1. locate jlink -------------------------------------------------------
# Do NOT use `Get-Command jlink`: that finds whatever JDK happens to be on PATH,
# which may differ from the JDK that built the fat jar. jlink's module names must
# match that JDK.
if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set. This script needs it to locate jlink (jlink's version must match the JDK that built the jar)."
}
$jlink = Join-Path $env:JAVA_HOME 'bin\jlink.exe'
if (-not (Test-Path $jlink)) {
    throw "jlink not found under JAVA_HOME: $jlink`nJAVA_HOME=$env:JAVA_HOME"
}
Write-Host "jlink: $jlink" -ForegroundColor Cyan
& $jlink --version

# ---- 2. module list (verified -- see .DESCRIPTION) --------------------------
$modules = @(
    'java.base',            # everything
    'java.sql',             # JDBC (SQLite)
    'java.naming',          # JNDI (some Spring paths touch it)
    'java.desktop',         # java.awt (pulled in by Hikari / graphics deps)
    'java.management',      # JMX
    'java.instrument',      # agents (Spring Boot startup path touches it)
    'java.security.jgss',   # security (Tomcat / SSL paths)
    'java.xml',             # XML (Spring config)
    'jdk.unsupported',      # sun.misc.Unsafe (netty / jackson)
    'jdk.crypto.ec',        # EC crypto (TLS)
    'java.net.http',        # HTTP client
    'jdk.management',       # platform MBeans
    'java.logging',         # java.util.logging
    'java.transaction.xa',  # XA transactions (referenced by JDBC interfaces)
    'java.compiler'         # javax.tools (a few reflective paths)
) -join ','

# ---- 3. output directory ---------------------------------------------------
$out = Join-Path (Get-Location) $OutDir
if (Test-Path $out) {
    if (-not $Force) {
        throw "Output directory already exists: $out`nPass -Force to delete and rebuild it. Refusing by default so we never ship a half-old/half-new JRE."
    }
    Write-Host "removing old output: $out" -ForegroundColor Yellow
    Remove-Item -Recurse -Force $out
}
New-Item -ItemType Directory -Force -Path (Split-Path $out -Parent) | Out-Null

# ---- 4. run jlink ----------------------------------------------------------
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $jlink `
    --add-modules $modules `
    --output $out `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=zip-6
if ($LASTEXITCODE -ne 0) { throw "jlink failed (exit $LASTEXITCODE)" }
$sw.Stop()

# ---- 5. verify the output --------------------------------------------------
$javaExe = Join-Path $out 'bin\java.exe'
if (-not (Test-Path $javaExe)) { throw "jlink finished but there is no bin\java.exe: $javaExe" }

$size = (Get-ChildItem $out -Recurse -File | Measure-Object -Property Length -Sum).Sum
$mb = [math]::Round($size / 1MB, 1)

Write-Host ""
Write-Host "output: $out" -ForegroundColor Green
Write-Host "size:   $mb MB    took: $($sw.Elapsed.TotalSeconds.ToString('F1'))s"
# java -version writes to STDERR. Under $ErrorActionPreference = 'Stop' that
# turns into a terminating error, so it has to be relaxed around this one call.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = (& $javaExe -version 2>&1 | Select-Object -First 1)
$ErrorActionPreference = $prevEap
Write-Host "java:   $javaVersion"

# Warn on size growth. The most common rot for a script like this is silent
# growth: a few modules get added, nobody notices, until the installer goes
# from 130 MB to 200 MB.
if ($mb -gt 60) {
    Write-Warning "Trimmed JRE is $mb MB, above the 60 MB warning line (2026-10-20 baseline was 48.8 MB). Check whether unnecessary modules crept in."
}

Write-Host ""
Write-Host "Next: actually start the backend with this runtime to prove no module is missing --" -ForegroundColor Cyan
Write-Host "  `$env:CHANGE_DATA_DIR = `"`$env:TEMP\jlink-verify`""
Write-Host "  `$env:CHANGE_REDIS_ENABLED = 'false'"
Write-Host "  & `"$javaExe`" -jar backend/target/change-backend-0.1.0-SNAPSHOT.jar"
Write-Host "  wait for CHANGE_PORT, then GET /api/health."
Write-Host ""
Write-Host "Changing the module list above means re-running that check." -ForegroundColor Yellow
Write-Host "A missing module shows up as NoClassDefFoundError at startup -- on a friend's machine." -ForegroundColor Yellow

# Exit 0 explicitly. Without this, the script's exit code is whatever the last
# native command left in $LASTEXITCODE -- observed as -1 after `java -version`.
# A build script needs a reliable contract because it gets chained from Tauri's
# beforeBuildCommand, where a nonzero exit aborts the build.
exit 0
