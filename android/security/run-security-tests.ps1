#Requires -Version 7.0
<#
.SYNOPSIS
    LiveMonitor Android —— 安全测试编排脚本（单测 + lint + 源码加固 + APK 加固 + 依赖清单 + 汇总）。

.DESCRIPTION
    一条命令跑完本环境可执行的全部安全验证（不含安装/真机动态测试），输出统一报告到
    android/security/reports/：安全汇总（JSON+MD）、各步骤原始日志、子报告（源码/APK）、依赖清单。

    设计约束（团队并行构建）：
    - 不执行 clean、不执行 assembleRelease（APK 由 Lead 构建）；
    - Gradle 步骤遇到文件锁/守护进程竞争时自动等待重试，不并发跑多个 Gradle 任务；
    - 任意步骤失败都会记录证据，但继续执行其余步骤，最后统一给出退出码。

    退出码：0=无 FAIL；1=存在 FAIL；2=参数/环境错误（如工程目录不存在）。

.PARAMETER ApkPath
    release APK 路径。缺省自动查找 android/dist/*.apk，再找 app/build/outputs/apk/release/*.apk。
    若都找不到且未指定 -RequireApk，则跳过 APK 检查（阶段一允许）。

.PARAMETER ExpectedSha256
    Lead 提供的 APK SHA-256；提供时会核对并给出 delivery.sha256 结果。

.PARAMETER ExpectedVersionName
    期望的 versionName（如 1.1.0）；提供时透传给 APK 检查并核对版本。

.PARAMETER RequireApk
    APK 不存在时判 FAIL（阶段二交付复检应使用）。

.PARAMETER SkipGradle
    跳过单测与 lint（仍做源码/APK 静态检查）。

.PARAMETER SkipLint
    只跳过 lint（单测照跑）。

.PARAMETER SkipDependencies
    跳过依赖版本清单。

.PARAMETER LintVariant
    auto（有 release APK 用 lintRelease，否则 lintDebug）| debug | release。

.PARAMETER MaxRetries
    Gradle 锁竞争重试次数，缺省 3。

.PARAMETER OutDir
    报告目录，缺省 <脚本目录>/reports。

.EXAMPLE
    pwsh -NoProfile -File android/security/run-security-tests.ps1
    pwsh -NoProfile -File android/security/run-security-tests.ps1 -ApkPath android/dist/xxx-unsigned.apk -ExpectedSha256 ABCD... -RequireApk
#>
[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$ExpectedSha256,
    [string]$ExpectedVersionName,
    [switch]$RequireApk,
    [switch]$SkipGradle,
    [switch]$SkipLint,
    [switch]$SkipDependencies,
    [ValidateSet('auto', 'debug', 'release')][string]$LintVariant = 'auto',
    [int]$MaxRetries = 3,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
$script:Version = '1.3.0'
$script:Results = [System.Collections.Generic.List[object]]::new()
$script:Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

$androidRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot 'reports' }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

function Add-Result {
    param([string]$Id, [ValidateSet('PASS', 'WARN', 'FAIL', 'INFO')][string]$Severity, [string]$Message, [string]$Evidence = '')
    $t = $Message -replace "`r?`n", ' '
    $e = $Evidence -replace "`r?`n", ' | '
    if ($t.Length -gt 800) { $t = $t.Substring(0, 800) + '…' }
    if ($e.Length -gt 1500) { $e = $e.Substring(0, 1500) + '…' }
    $script:Results.Add([pscustomobject]@{ id = $Id; severity = $Severity; message = $t; evidence = $e })
}

function Write-Section { param([string]$Title) Write-Host ''; Write-Host "==== $Title ====" -ForegroundColor Cyan }

function Format-EvidenceText {
    param([string]$Text, [int]$MaxLen = 1200)
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    $t = $Text -replace "`r?`n", ' | '
    $t = $t.Trim()
    if ($t.Length -gt $MaxLen) { $t = $t.Substring(0, $MaxLen) + '…' }
    return $t
}

function Get-TailText { param([string]$Text, [int]$Lines = 40) (($Text -split "`n") | Select-Object -Last $Lines) -join "`n" }

# ------------------------------------------------------------------ 环境

$jdkHome = $env:JAVA_HOME
if (-not $jdkHome -or -not (Test-Path (Join-Path $jdkHome 'bin\java.exe'))) {
    foreach ($cand in @('D:\DevTools\jdk17')) { if (Test-Path (Join-Path $cand 'bin\java.exe')) { $jdkHome = $cand; break } }
}
if ($jdkHome) { $env:JAVA_HOME = $jdkHome }
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif (Test-Path 'D:\DevTools\android-sdk') { 'D:\DevTools\android-sdk' } else { $env:ANDROID_SDK_ROOT }
if ($sdkRoot) { $env:ANDROID_HOME = $sdkRoot; $env:ANDROID_SDK_ROOT = $sdkRoot }
$devGradle = Join-Path $androidRoot 'scripts\dev-gradle.ps1'
$sourceScript = Join-Path $PSScriptRoot 'check-source-hardening.ps1'
$apkScript = Join-Path $PSScriptRoot 'check-apk-hardening.ps1'
$pwshExe = (Get-Process -Id $PID).Path
if (-not $pwshExe) { $pwshExe = 'pwsh' }

if (-not (Test-Path (Join-Path $androidRoot 'app'))) { throw "Android 工程目录不存在: $androidRoot" }

Write-Host ''
Write-Host '############################################################' -ForegroundColor Cyan
Write-Host '# 直播监测 Android —— 安全测试编排' -ForegroundColor Cyan
Write-Host '# 说明：本脚本覆盖静态检查+JVM单测+lint；动态测试（安装/运行/抓包/UI）不由本脚本执行。' -ForegroundColor Yellow
Write-Host '#       阶段三动态测试由 Lead 在 LDPlayer 14（Android 14/API34）执行，证据见 docs/安全测试报告.md 第8节。' -ForegroundColor Yellow
Write-Host '############################################################' -ForegroundColor Cyan
Write-Host "工程根   : $androidRoot"
Write-Host "输出目录 : $OutDir"
Write-Host "JDK      : $jdkHome"
Write-Host "SDK      : $sdkRoot"
Write-Host ""

$env:PATH = "$(Join-Path $jdkHome 'bin');$env:PATH"

# ------------------------------------------------------------------ 通用：跑 Gradle 步骤（带锁等待重试）

function Invoke-GradleStep {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string[]]$GradleArgs,
        [Parameter(Mandatory)][string]$LogPath
    )
    $attempt = 0
    $lastText = ''
    $lastCode = -1
    $lockPattern = 'Timeout waiting to lock|Waiting to acquire|Could not create service of type|has already been locked|another process|Unable to acquire|Gradle build daemon disappeared|Unable to start the daemon|File lock on build directory|is in use by another process|Timeout waiting for connection|Could not receive a message'
    while ($attempt -lt $MaxRetries) {
        $attempt++
        Write-Host "  -> 第 $attempt/$MaxRetries 次尝试: gradle $($GradleArgs -join ' ')" -ForegroundColor Gray
        $out = & $pwshExe -NoProfile -File $devGradle @GradleArgs 2>&1
        $lastCode = $LASTEXITCODE
        $lastText = (($out | ForEach-Object { "$_" }) -join "`n")
        Set-Content -Path $LogPath -Value $lastText -Encoding utf8
        if ($lastCode -eq 0) {
            return [pscustomobject]@{ Ok = $true; Code = 0; Text = $lastText; Attempts = $attempt }
        }
        if ($lastText -match $lockPattern) {
            $wait = 15 * $attempt
            Write-Host "     Gradle 锁竞争（可能有队友在构建），${wait}s 后重试…" -ForegroundColor Yellow
            Start-Sleep -Seconds $wait
            continue
        }
        break
    }
    return [pscustomobject]@{ Ok = $false; Code = $lastCode; Text = $lastText; Attempts = $attempt }
}

# ------------------------------------------------------------------ 1. 单元测试

$testSummary = [ordered]@{ ran = $false; ok = $false; total = 0; failures = 0; errors = 0; skipped = 0; classes = 0; reportPath = ''; logPath = '' }
if (-not $SkipGradle) {
    Write-Section '1. 单元测试 :app:testDebugUnitTest'
    $logPath = Join-Path $OutDir "gradle-testDebugUnitTest-$($script:Stamp).log"
    $r = Invoke-GradleStep -Name 'testDebugUnitTest' -GradleArgs @(':app:testDebugUnitTest', '--console=plain') -LogPath $logPath
    $testSummary.ran = $true
    $testSummary.ok = $r.Ok
    $testSummary.logPath = $logPath
    $resultsDir = Join-Path $androidRoot 'app\build\test-results\testDebugUnitTest'
    if (Test-Path $resultsDir) {
        $xmlFiles = @(Get-ChildItem $resultsDir -Filter '*.xml' -File -ErrorAction SilentlyContinue)
        $testSummary.classes = $xmlFiles.Count
        foreach ($xf in $xmlFiles) {
            try {
                [xml]$x = Get-Content -Raw $xf.FullName
                $suite = $x.testsuite
                if ($null -eq $suite) { continue }
                $testSummary.total += [int]$suite.tests
                $testSummary.failures += [int]$suite.failures
                $testSummary.errors += [int]$suite.errors
                $testSummary.skipped += [int]$suite.skipped
            }
            catch { }
        }
        $testSummary.reportPath = Join-Path $androidRoot 'app\build\reports\tests\testDebugUnitTest\index.html'
    }
    $failedCount = $testSummary.failures + $testSummary.errors
    if (-not $r.Ok) {
        Add-Result -Id 'tests.unit' -Severity 'FAIL' -Message "单元测试任务失败（Gradle exit $($r.Code)）" -Evidence (Get-TailText $r.Text 30)
    }
    elseif ($failedCount -gt 0) {
        Add-Result -Id 'tests.unit' -Severity 'FAIL' -Message "单元测试失败：$failedCount / $($testSummary.total)" -Evidence "failures=$($testSummary.failures), errors=$($testSummary.errors)"
    }
    else {
        Add-Result -Id 'tests.unit' -Severity 'PASS' -Message "单元测试通过：$($testSummary.total) 个用例 / $($testSummary.classes) 个测试类，0 失败" -Evidence "skipped=$($testSummary.skipped)"
    }
    Write-Host ("  单测: total={0}, failures={1}, errors={2}, skipped={3}, classes={4}" -f $testSummary.total, $testSummary.failures, $testSummary.errors, $testSummary.skipped, $testSummary.classes)
}
else {
    Add-Result -Id 'tests.unit' -Severity 'INFO' -Message '已按参数跳过单元测试（-SkipGradle）'
}

# ------------------------------------------------------------------ 2. Lint

$lintSummary = [ordered]@{ ran = $false; ok = $false; variant = ''; errors = 0; warnings = 0; fatal = 0; reportPath = ''; logPath = '' }
if (-not $SkipGradle -and -not $SkipLint) {
    Write-Section '2. Android Lint'
    $variant = $LintVariant
    if ($variant -eq 'auto') {
        $releaseApks = @()
        $releaseApks += @(Get-ChildItem (Join-Path $androidRoot 'dist') -Filter '*.apk' -File -ErrorAction SilentlyContinue)
        $releaseApks += @(Get-ChildItem (Join-Path $androidRoot 'app\build\outputs\apk\release') -Filter '*.apk' -File -ErrorAction SilentlyContinue)
        $variant = if ($releaseApks.Count -gt 0) { 'release' } else { 'debug' }
    }
    $lintSummary.variant = $variant
    $task = ":app:lint$($variant.Substring(0,1).ToUpper())$($variant.Substring(1))"
    $logPath = Join-Path $OutDir "gradle-lint-$variant-$($script:Stamp).log"
    $r = Invoke-GradleStep -Name "lint$variant" -GradleArgs @($task, '--console=plain') -LogPath $logPath
    $lintSummary.ran = $true
    $lintSummary.ok = $r.Ok
    $lintSummary.logPath = $logPath
    # 查找 lint 报告（AGP 版本间路径不同，取最新）
    $reportCandidates = @()
    foreach ($pat in @("app\build\reports\lint-results-$variant.xml", "app\build\reports\lint-results-$variant.html", "app\build\reports\lint-results.xml")) {
        $p = Join-Path $androidRoot $pat
        if (Test-Path $p) { $reportCandidates += Get-Item $p }
    }
    $recursive = @(Get-ChildItem (Join-Path $androidRoot 'app\build') -Recurse -File -Filter "lint-results-$variant.xml" -ErrorAction SilentlyContinue)
    $reportCandidates += $recursive
    $xmlReport = $reportCandidates | Where-Object { $_.Extension -eq '.xml' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($xmlReport) {
        $lintSummary.reportPath = $xmlReport.FullName
        try {
            [xml]$lx = Get-Content -Raw $xmlReport.FullName
            foreach ($issue in $lx.SelectNodes("//*[local-name()='issue']")) {
                switch ($issue.GetAttribute('severity')) {
                    'Fatal' { $lintSummary.fatal++ }
                    'Error' { $lintSummary.errors++ }
                    'Warning' { $lintSummary.warnings++ }
                }
            }
        }
        catch { }
    }
    else {
        # 从控制台输出兜底统计
        $m = [regex]::Match($r.Text, '(\d+)\s+errors?,\s*(\d+)\s+warnings?')
        if ($m.Success) { $lintSummary.errors = [int]$m.Groups[1].Value; $lintSummary.warnings = [int]$m.Groups[2].Value }
    }
    $lintProblems = $lintSummary.errors + $lintSummary.fatal
    if (-not $r.Ok) {
        if ($lintProblems -gt 0) {
            Add-Result -Id 'lint' -Severity 'FAIL' -Message "Lint 失败：$($lintSummary.errors) error / $($lintSummary.warnings) warning（构建 abortOnError=true）" -Evidence (Get-TailText $r.Text 25)
        }
        else {
            Add-Result -Id 'lint' -Severity 'FAIL' -Message "Lint 任务失败（Gradle exit $($r.Code)），见报告" -Evidence (Get-TailText $r.Text 25)
        }
    }
    elseif ($lintProblems -gt 0) {
        Add-Result -Id 'lint' -Severity 'FAIL' -Message "Lint 存在 $lintProblems 个 error/fatal"
    }
    else {
        Add-Result -Id 'lint' -Severity 'PASS' -Message "Lint（$variant）通过：0 error / $($lintSummary.warnings) warning" -Evidence "报告: $($lintSummary.reportPath)"
    }
    Write-Host ("  Lint($variant): errors={0}, warnings={1}, report={2}" -f $lintSummary.errors, $lintSummary.warnings, $lintSummary.reportPath)
}
else {
    Add-Result -Id 'lint' -Severity 'INFO' -Message '已按参数跳过 Lint（-SkipGradle/-SkipLint）'
}

# ------------------------------------------------------------------ 3. 源码加固扫描

Write-Section '3. 源码加固扫描（check-source-hardening.ps1）'
$sourceReportPath = Join-Path $OutDir "source-hardening-$($script:Stamp).json"
$sourceLatest = Join-Path $OutDir 'source-hardening-latest.json'
$sourceOut = & $pwshExe -NoProfile -File $sourceScript -SourceDir (Join-Path $androidRoot 'app\src') -ProjectRoot $androidRoot -OutDir $OutDir 2>&1
$sourceCode = $LASTEXITCODE
$sourceText = (($sourceOut | ForEach-Object { "$_" }) -join "`n")
$sourceSummary = [ordered]@{ exitCode = $sourceCode; reportPath = ''; fail = 0; warn = 0; pass = 0; info = 0 }
if (Test-Path $sourceLatest) {
    Copy-Item $sourceLatest $sourceReportPath -Force
    $sourceSummary.reportPath = $sourceReportPath
    $sj = Get-Content -Raw $sourceLatest | ConvertFrom-Json
    $sourceSummary.fail = $sj.summary.fail
    $sourceSummary.warn = $sj.summary.warn
    $sourceSummary.pass = $sj.summary.pass
    $sourceSummary.info = $sj.summary.info
    if ($sourceCode -ne 0) {
        $failIds = @($sj.results | Where-Object { $_.severity -eq 'FAIL' } | ForEach-Object { $_.id })
        Add-Result -Id 'source.hardening' -Severity 'FAIL' -Message "源码扫描发现 $($sj.summary.fail) 个 FAIL 项：$($failIds -join ', ')" -Evidence (Format-EvidenceText -Text ((@($sj.results | Where-Object { $_.severity -eq 'FAIL' } | ForEach-Object { "$($_.id): $($_.message)" })) -join ' || ') 900)
    }
    else {
        Add-Result -Id 'source.hardening' -Severity 'PASS' -Message "源码扫描通过：0 FAIL / $($sj.summary.warn) WARN / $($sj.summary.pass) PASS"
    }
}
else {
    Add-Result -Id 'source.hardening' -Severity 'FAIL' -Message '源码扫描未生成报告' -Evidence (Get-TailText $sourceText 20)
}
Write-Host ("  源码扫描: PASS={0}, WARN={1}, FAIL={2}, 报告={3}" -f $sourceSummary.pass, $sourceSummary.warn, $sourceSummary.fail, $sourceSummary.reportPath)

# ------------------------------------------------------------------ 4. APK 加固检查

Write-Section '4. APK 加固检查（check-apk-hardening.ps1）'
$apkSummary = [ordered]@{
    provided = $false; path = ''; sha256 = ''; expectedSha256 = ''; sha256Match = $null
    exitCode = $null; reportPath = ''; fail = 0; warn = 0; signingState = ''; buildKind = ''; deliverability = ''
}
if (-not $ApkPath) {
    $candidates = @()
    foreach ($pat in @('dist\*.apk', 'app\build\outputs\apk\release\*.apk')) {
        $g = @(Get-ChildItem (Join-Path $androidRoot $pat) -File -ErrorAction SilentlyContinue)
        if ($g.Count -gt 0) { $candidates += $g }
    }
    if ($candidates.Count -gt 0) { $ApkPath = ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName }
}
if ($ApkPath -and (Test-Path $ApkPath)) {
    $apkSummary.provided = $true
    $apkSummary.path = (Resolve-Path $ApkPath).Path
    $apkSummary.sha256 = (Get-FileHash -Algorithm SHA256 -Path $apkSummary.path).Hash.ToLowerInvariant()
    $apkArgs = @('-ApkPath', $apkSummary.path, '-OutDir', $OutDir)
    if ($ExpectedVersionName) { $apkArgs += @('-ExpectedVersionName', $ExpectedVersionName) }
    $apkOut = & $pwshExe -NoProfile -File $apkScript @apkArgs 2>&1
    $apkSummary.exitCode = $LASTEXITCODE
    $apkText = (($apkOut | ForEach-Object { "$_" }) -join "`n")
    $apkLatest = Join-Path $OutDir 'apk-hardening-latest.json'
    if (Test-Path $apkLatest) {
        $apkReportPath = Join-Path $OutDir "apk-hardening-$($script:Stamp).json"
        Copy-Item $apkLatest $apkReportPath -Force
        $apkSummary.reportPath = $apkReportPath
        $aj = Get-Content -Raw $apkLatest | ConvertFrom-Json
        $apkSummary.fail = $aj.summary.fail
        $apkSummary.warn = $aj.summary.warn
        $apkSummary.signingState = $aj.apk.signingState
        $apkSummary.buildKind = $aj.apk.buildKind
        $apkSummary.deliverability = $aj.deliverability
        if ($apkSummary.sha256Match -eq $null) { $apkSummary.sha256Match = $null }
        if ($apkSummary.exitCode -ne 0) {
            $failIds = @($aj.results | Where-Object { $_.severity -eq 'FAIL' } | ForEach-Object { $_.id })
            Add-Result -Id 'apk.hardening' -Severity 'FAIL' -Message "APK 加固检查发现 $($aj.summary.fail) 个 FAIL：$($failIds -join ', ')" -Evidence (Format-EvidenceText -Text ((@($aj.results | Where-Object { $_.severity -eq 'FAIL' } | ForEach-Object { "$($_.id): $($_.message)" })) -join ' || ') 900)
        }
        else {
            Add-Result -Id 'apk.hardening' -Severity 'PASS' -Message "APK 加固检查通过：0 FAIL / $($aj.summary.warn) WARN（签名状态 $($aj.apk.signingState)）"
        }
    }
    else {
        Add-Result -Id 'apk.hardening' -Severity 'FAIL' -Message 'APK 检查未生成报告' -Evidence (Get-TailText $apkText 20)
    }
    # SHA-256 核对
    if ($ExpectedSha256) {
        $apkSummary.expectedSha256 = $ExpectedSha256.Trim().ToLowerInvariant()
        $apkSummary.sha256Match = ($apkSummary.sha256 -eq $apkSummary.expectedSha256)
        if ($apkSummary.sha256Match) {
            Add-Result -Id 'delivery.sha256' -Severity 'PASS' -Message "APK SHA-256 与 Lead 提供值一致：$($apkSummary.sha256)"
        }
        else {
            Add-Result -Id 'delivery.sha256' -Severity 'FAIL' -Message 'APK SHA-256 与 Lead 提供值不一致（可能构建产物被替换/未同步）' -Evidence "actual=$($apkSummary.sha256) expected=$($apkSummary.expectedSha256)"
        }
    }
    else {
        Add-Result -Id 'delivery.sha256' -Severity 'INFO' -Message "未提供 -ExpectedSha256，仅记录实测 SHA-256：$($apkSummary.sha256)"
    }
    Write-Host ("  APK: {0}" -f $apkSummary.path)
    Write-Host ("  SHA-256: {0}" -f $apkSummary.sha256)
    Write-Host ("  签名状态: {0} / 构建类型: {1}" -f $apkSummary.signingState, $apkSummary.buildKind)
    Write-Host ("  FAIL={0} WARN={1} 报告={2}" -f $apkSummary.fail, $apkSummary.warn, $apkSummary.reportPath)
}
else {
    if ($RequireApk) {
        Add-Result -Id 'apk.hardening' -Severity 'FAIL' -Message '未找到 APK（-RequireApk 要求必须存在）'
    }
    else {
        Add-Result -Id 'apk.hardening' -Severity 'INFO' -Message 'APK 尚未就绪（android/dist 与 build/outputs/apk/release 均无产物），跳过 APK 检查。'
    }
    Write-Host '  未找到 APK（阶段一允许；阶段二交付复检请加 -RequireApk 或 -ApkPath）' -ForegroundColor Yellow
}

# ------------------------------------------------------------------ 5. 依赖版本清单

$depSummary = [ordered]@{ ran = $false; ok = $false; reportPath = ''; directCount = 0; moduleCount = 0; versions = [ordered]@{} }
if (-not $SkipDependencies) {
    Write-Section '5. 依赖版本清单 :app:dependencies (releaseRuntimeClasspath)'
    $depLog = Join-Path $OutDir "dependencies-releaseRuntimeClasspath-$($script:Stamp).txt"
    $r = Invoke-GradleStep -Name 'dependencies' -GradleArgs @(':app:dependencies', '--configuration', 'releaseRuntimeClasspath', '--console=plain') -LogPath $depLog
    $depSummary.ran = $true
    $depSummary.ok = $r.Ok
    if ($r.Ok) {
        $depSummary.reportPath = $depLog
        $lines = $r.Text -split "`n"
        $direct = @($lines | Where-Object { $_ -match '^[+\\]---\s+[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+' })
        $allCoords = @([regex]::Matches($r.Text, '(?m)^[|+\\ ]*[+\\]---\s+([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?::([A-Za-z0-9_.\-]+))?') |
                ForEach-Object { $_.Groups[1].Value + ':' + $_.Groups[2].Value })
        $depSummary.directCount = $direct.Count
        $depSummary.moduleCount = @($allCoords | Select-Object -Unique).Count
        Add-Result -Id 'dependencies' -Severity 'PASS' -Message "依赖清单已生成：直接依赖 $($depSummary.directCount) 个，去重模块 $($depSummary.moduleCount) 个" -Evidence "报告: $depLog"
    }
    else {
        Add-Result -Id 'dependencies' -Severity 'WARN' -Message '依赖清单任务失败（可能是构建被占用/网络问题），见日志' -Evidence (Get-TailText $r.Text 20)
    }
    Write-Host ("  依赖: direct={0}, modules={1}, 报告={2}" -f $depSummary.directCount, $depSummary.moduleCount, $depSummary.reportPath)
}
else {
    Add-Result -Id 'dependencies' -Severity 'INFO' -Message '已按参数跳过依赖清单（-SkipDependencies）'
}
# 记录声明版本（来自 libs.versions.toml，供报告与复核）
$toml = Join-Path $androidRoot 'gradle\libs.versions.toml'
if (Test-Path $toml) {
    $verSection = $false
    foreach ($line in (Get-Content $toml)) {
        if ($line -match '^\[versions\]') { $verSection = $true; continue }
        if ($line -match '^\[') { $verSection = $false; continue }
        if ($verSection -and $line -match '^([A-Za-z0-9_-]+)\s*=\s*"([^"]+)"') {
            $depSummary.versions[$Matches[1]] = $Matches[2]
        }
    }
}

# ------------------------------------------------------------------ 6. 汇总

$fail = @($script:Results | Where-Object { $_.severity -eq 'FAIL' }).Count
$warn = @($script:Results | Where-Object { $_.severity -eq 'WARN' }).Count
$pass = @($script:Results | Where-Object { $_.severity -eq 'PASS' }).Count
$info = @($script:Results | Where-Object { $_.severity -eq 'INFO' }).Count
$exitCode = if ($fail -gt 0) { 1 } else { 0 }

$conclusion = if ($fail -gt 0) {
    "不通过：存在 $fail 项 FAIL，需修复后复检（详见报告）。"
}
elseif ($apkSummary.provided -and $apkSummary.signingState -eq 'UNSIGNED') {
    '静态检查通过；但交付 APK 为 UNSIGNED，发布前必须由用户生成 keystore 重签；动态测试不由本脚本执行。'
}
elseif (-not $apkSummary.provided) {
    '源码/单测/lint 静态检查通过；APK 尚未就绪，未做 APK 级检查（阶段一）。'
}
else {
    '静态检查通过（APK 签名状态：' + $apkSummary.signingState + '）；动态测试由 Lead 在 LDPlayer 14 执行，证据见 docs/安全测试报告.md 第8节；本脚本未重复执行动态测试。'
}

$summary = [ordered]@{
    script        = 'run-security-tests.ps1'
    scriptVersion = $script:Version
    generatedAt   = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz')
    environment   = [ordered]@{
        androidRoot = $androidRoot
        javaHome    = $jdkHome
        sdkRoot     = $sdkRoot
        powershell  = $PSVersionTable.PSVersion.ToString()
        os          = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
        dynamicTest = 'not-run-by-this-script; stage-3 dynamic round executed by Lead on LDPlayer 14 (Android 14/API34), see android/docs/安全测试报告.md §8'
    }
    gradle        = [ordered]@{ tests = $testSummary; lint = $lintSummary }
    source        = $sourceSummary
    apk           = $apkSummary
    dependencies  = $depSummary
    summary       = [ordered]@{ total = $script:Results.Count; pass = $pass; warn = $warn; fail = $fail; info = $info; exitCode = $exitCode }
    conclusion    = $conclusion
    results       = $script:Results
    notes         = @(
        '本报告汇总静态检查与 JVM 单元测试；动态测试由 Lead 在 LDPlayer 14 上执行（安装/服务/通知/暂停/后台/证书固定实网验证），本脚本未重复执行，证据见 android/docs/安全测试报告.md 第8节。'
        '单测/lint 结果来自 Gradle 任务；若失败，日志已保存在 reports/ 对应文件中。'
        'APK 为 unsigned 是当前密钥生成受限导致的交付状态，已在 APK 报告中记录，发布前必须由用户自行签名。'
    )
}
$summaryJson = Join-Path $OutDir "security-summary-$($script:Stamp).json"
$summary | ConvertTo-Json -Depth 9 | Set-Content -Path $summaryJson -Encoding utf8

$tick = '`'
$testCell = if (-not $testSummary.ran) { '跳过' }
elseif ($testSummary.ok -and (($testSummary.failures + $testSummary.errors) -eq 0)) { '通过（' + $testSummary.total + ' 用例）' }
else { '失败（' + ($testSummary.failures + $testSummary.errors) + '/' + $testSummary.total + '）' }
$lintCell = if (-not $lintSummary.ran) { '跳过' } else { 'errors=' + $lintSummary.errors + ', warnings=' + $lintSummary.warnings }
$sourceCell = if ($sourceSummary.reportPath) { 'FAIL=' + $sourceSummary.fail + ', WARN=' + $sourceSummary.warn } else { '未生成' }
$apkCell = if ($apkSummary.provided) { 'FAIL=' + $apkSummary.fail + ', WARN=' + $apkSummary.warn + ', 签名=' + $apkSummary.signingState } else { 'APK 未就绪/跳过' }
$shaCell = if ($apkSummary.sha256) { $apkSummary.sha256 } else { '—' }
$deliverCell = if ($apkSummary.deliverability) { $apkSummary.deliverability } else { '—' }

$md = [System.Text.StringBuilder]::new()
[void]$md.AppendLine('# 安全测试汇总报告')
[void]$md.AppendLine('')
[void]$md.AppendLine('> 生成时间：' + $summary.generatedAt + '　脚本版本：' + $script:Version + '　执行环境：' + $summary.environment.os + ' / PowerShell ' + $summary.environment.powershell)
[void]$md.AppendLine('> **动态测试**：不由本脚本执行；阶段三动态测试由 Lead 在 LDPlayer 14（Android 14/API 34）执行，证据（截图/命令输出摘录）见 `android/docs/安全测试报告.md` 第 8 节。')
[void]$md.AppendLine('')
[void]$md.AppendLine('## 1. 结论')
[void]$md.AppendLine('')
[void]$md.AppendLine('**' + $conclusion + '**')
[void]$md.AppendLine('')
[void]$md.AppendLine('| 项目 | 结果 |')
[void]$md.AppendLine('| --- | --- |')
[void]$md.AppendLine('| 单元测试 | ' + $testCell + ' |')
[void]$md.AppendLine('| Lint（' + $lintSummary.variant + '） | ' + $lintCell + ' |')
[void]$md.AppendLine('| 源码加固扫描 | ' + $sourceCell + ' |')
[void]$md.AppendLine('| APK 加固检查 | ' + $apkCell + ' |')
[void]$md.AppendLine('| APK SHA-256 | ' + $shaCell + ' |')
[void]$md.AppendLine('| 交付状态 | ' + $deliverCell + ' |')
[void]$md.AppendLine('| 总统计 | PASS ' + $pass + ' / WARN ' + $warn + ' / FAIL ' + $fail + ' / INFO ' + $info + ' |')
[void]$md.AppendLine('')
[void]$md.AppendLine('## 2. 各步骤结果明细')
[void]$md.AppendLine('')
[void]$md.AppendLine('| 检查项 | 级别 | 说明 |')
[void]$md.AppendLine('| --- | --- | --- |')
foreach ($r in $script:Results) {
    $msgCell = ($r.message -replace '\|', '\|')
    [void]$md.AppendLine('| ' + $tick + $r.id + $tick + ' | ' + $r.severity + ' | ' + $msgCell + ' |')
}
[void]$md.AppendLine('')
[void]$md.AppendLine('## 3. 产物与证据文件')
[void]$md.AppendLine('')
[void]$md.AppendLine('| 内容 | 路径 |')
[void]$md.AppendLine('| --- | --- |')
if ($testSummary.logPath) { [void]$md.AppendLine('| 单测 Gradle 日志 | ' + $tick + $testSummary.logPath + $tick + ' |') }
if ($testSummary.reportPath) { [void]$md.AppendLine('| 单测 HTML 报告 | ' + $tick + $testSummary.reportPath + $tick + ' |') }
if ($lintSummary.logPath) { [void]$md.AppendLine('| Lint Gradle 日志 | ' + $tick + $lintSummary.logPath + $tick + ' |') }
if ($lintSummary.reportPath) { [void]$md.AppendLine('| Lint 报告 | ' + $tick + $lintSummary.reportPath + $tick + ' |') }
if ($sourceSummary.reportPath) { [void]$md.AppendLine('| 源码加固报告（JSON） | ' + $tick + $sourceSummary.reportPath + $tick + ' |') }
if ($apkSummary.reportPath) { [void]$md.AppendLine('| APK 加固报告（JSON） | ' + $tick + $apkSummary.reportPath + $tick + ' |') }
if ($depSummary.reportPath) { [void]$md.AppendLine('| 依赖清单 | ' + $tick + $depSummary.reportPath + $tick + ' |') }
[void]$md.AppendLine('| 本汇总 JSON | ' + $tick + $summaryJson + $tick + ' |')
[void]$md.AppendLine('')
[void]$md.AppendLine('## 4. 声明版本（libs.versions.toml）')
[void]$md.AppendLine('')
if ($depSummary.versions.Count -gt 0) {
    [void]$md.AppendLine('| 组件 | 版本 |')
    [void]$md.AppendLine('| --- | --- |')
    foreach ($k in $depSummary.versions.Keys) { [void]$md.AppendLine('| ' + $k + ' | ' + $depSummary.versions[$k] + ' |') }
}
else { [void]$md.AppendLine('（未读取到 libs.versions.toml）') }
[void]$md.AppendLine('')
[void]$md.AppendLine('## 5. 已知限制')
[void]$md.AppendLine('')
foreach ($n in $summary.notes) { [void]$md.AppendLine('- ' + $n) }
$mdPath = Join-Path $OutDir "security-summary-$($script:Stamp).md"
$md.ToString() | Set-Content -Path $mdPath -Encoding utf8
$summaryJson | Copy-Item -Destination (Join-Path $OutDir 'security-summary-latest.json') -Force
$mdPath | Copy-Item -Destination (Join-Path $OutDir 'security-summary-latest.md') -Force

Write-Host ''
Write-Host '==================== 汇总 ====================' -ForegroundColor Cyan
foreach ($r in $script:Results) {
    $color = switch ($r.severity) { 'FAIL' { 'Red' } 'WARN' { 'Yellow' } 'PASS' { 'Green' } default { 'Gray' } }
    Write-Host ("[{0,-4}] {1,-24} {2}" -f $r.severity, $r.id, $r.message) -ForegroundColor $color
}
Write-Host ''
Write-Host ("PASS $pass / WARN $warn / FAIL $fail / INFO $info") -ForegroundColor Cyan
Write-Host "结论: $conclusion"
Write-Host "汇总 JSON: $summaryJson"
Write-Host "汇总 MD  : $mdPath"
Write-Host ''
exit $exitCode
