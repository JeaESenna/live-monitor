#Requires -Version 7.0
<#
.SYNOPSIS
    LiveMonitor Android —— release APK 加固/合规静态检查（可重复执行）。

.DESCRIPTION
    对最终交付的 APK 做只读静态核查，输出 JSON + Markdown 报告到 android/security/reports/。
    检查项：
      1. aapt2 dump badging / xmltree：包名、minSdk/targetSdk/version、权限白名单、
         组件导出面、allowBackup、usesCleartextTraffic、networkSecurityConfig、debuggable、testOnly、
         application 异常属性、foregroundServiceType=specialUse 及其 property。
      2. apksigner verify：签名方案 v2/v3、signer 证书、是否 debug 证书、是否 UNSIGNED。
      3. 解包 APK（ZipFile + 二进制字符串扫描 dex）：WebView/JS 桥/动态加载/exec/反射加载/
         危险权限字符串/明文 http URL/硬编码密钥/第三方统计 SDK 痕迹/原生库清单。
      4. network_security_config.xml 实际内容（base-config 禁明文、仅系统 CA）。

    本脚本不安装、不运行 APK；不做动态测试。退出码：0=无 FAIL，1=存在 FAIL，2=参数/环境错误。

.PARAMETER ApkPath
    待检查 APK。缺省自动在 android/dist/ 与 app/build/outputs/apk/{release,debug}/ 里找最新的。

.PARAMETER SdkRoot
    Android SDK 根目录。缺省依次取 ANDROID_HOME / ANDROID_SDK_ROOT / D:\DevTools\android-sdk。

.PARAMETER BuildToolsVersion
    build-tools 版本。缺省 34.0.0（不存在时自动选已安装的最高完整版本）。也可用 env LM_BUILD_TOOLS。

.PARAMETER OutDir
    报告输出目录。缺省 <本脚本目录>/reports。

.PARAMETER ExpectedPackage
    期望包名，缺省 com.livemonitor.app。

.PARAMETER BuildKind
    auto（按文件名自动判断 release/debug）| release | debug。
    release：debuggable/testOnly 必须为 false，否则 FAIL。
    debug  ：debuggable/testOnly 判为 WARN，并注明“仅本机测试，不可分发”。

.PARAMETER SigningExpectation
    auto | release | test | unsigned。
    unsigned：缺签名判为 WARN 并标注“UNSIGNED（预期：本机无法生成签名密钥）”，不判 FAIL。
    release ：缺签名判 FAIL；debug 证书判 WARN（不可发布）。
    test    ：debug 证书视为预期（WARN/INFO）。
    auto    ：文件名含 unsigned → unsigned；含 debug → test；否则 release。

.PARAMETER SelfTest
    用 android/app/build/outputs/apk/debug/app-debug.apk 自检脚本规则（断言 debug 构建能被识别）。

.EXAMPLE
    pwsh -NoProfile -File android/security/check-apk-hardening.ps1
    pwsh -NoProfile -File android/security/check-apk-hardening.ps1 -ApkPath android/dist/xxx.apk -SigningExpectation unsigned
    pwsh -NoProfile -File android/security/check-apk-hardening.ps1 -SelfTest
#>
[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$SdkRoot,
    [string]$BuildToolsVersion,
    [string]$OutDir,
    [string]$ExpectedPackage = 'com.livemonitor.app',
    [string]$ExpectedVersionName = '',
    [ValidateSet('auto', 'release', 'debug')][string]$BuildKind = 'auto',
    [ValidateSet('auto', 'release', 'test', 'unsigned')][string]$SigningExpectation = 'auto',
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$script:Version = '1.3.0'
$script:Results = [System.Collections.Generic.List[object]]::new()
$script:AndroidNs = 'http://schemas.android.com/apk/res/android'

# v1.1.0：“进入直播间”只允许查询的 5 个平台 App 包名（与 Manifest <queries> 对应）
$script:ExpectedQueriesPackages = @(
    'air.tv.douyu.android'
    'com.duowan.kiwi'
    'tv.danmaku.bili'
    'com.ss.android.ugc.aweme'
    'com.smile.gifmaker'
)

# 允许出现在 APK 里的权限（本应用最小权限面）。DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION 由 AndroidX 自动合并。
$script:AllowedPermissions = @(
    'android.permission.INTERNET'
    'android.permission.ACCESS_NETWORK_STATE'
    'android.permission.POST_NOTIFICATIONS'
    'android.permission.FOREGROUND_SERVICE'
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE'
    'android.permission.WAKE_LOCK'
)
# 即使出现在白名单里也应人工复核的高危权限（出现即 FAIL）。
$script:SensitivePermissionPatterns = @(
    'READ_CALENDAR', 'WRITE_CALENDAR', 'CAMERA', 'READ_CONTACTS', 'WRITE_CONTACTS', 'GET_ACCOUNTS',
    'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'ACCESS_BACKGROUND_LOCATION',
    'RECORD_AUDIO', 'READ_PHONE_STATE', 'READ_PHONE_NUMBERS', 'CALL_PHONE', 'ANSWER_PHONE_CALLS',
    'READ_CALL_LOG', 'WRITE_CALL_LOG', 'ADD_VOICEMAIL', 'USE_SIP', 'PROCESS_OUTGOING_CALLS',
    'BODY_SENSORS', 'ACTIVITY_RECOGNITION', 'SEND_SMS', 'RECEIVE_SMS', 'READ_SMS', 'RECEIVE_MMS', 'RECEIVE_WAP_PUSH',
    'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE', 'READ_MEDIA_',
    'SYSTEM_ALERT_WINDOW', 'PACKAGE_USAGE_STATS', 'GET_TASKS', 'REAL_GET_TASKS',
    'RECEIVE_BOOT_COMPLETED', 'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'REQUEST_INSTALL_PACKAGES',
    'QUERY_ALL_PACKAGES', 'BIND_ACCESSIBILITY_SERVICE', 'BIND_NOTIFICATION_LISTENER_SERVICE',
    'BIND_DEVICE_ADMIN', 'REQUEST_DELETE_PACKAGES', 'WRITE_SETTINGS', 'MODIFY_AUDIO_SETTINGS',
    'BLUETOOTH_CONNECT', 'NFC', 'READ_MEDIA_VISUAL_USER_SELECTED'
)
# 预期导出的组件（全限定名）；除此之外任何 exported=true 组件均为 FAIL。
$script:ExpectedExportedComponents = @('com.livemonitor.app.ui.MainActivity')
$script:ExpectedService = 'com.livemonitor.app.core.monitor.MonitorService'
$script:ExpectedFgsTypeBit = 0x40000000L  # FOREGROUND_SERVICE_TYPE_SPECIAL_USE
$script:FgsSubtypeProperty = 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE'
# dex 中的明文 http:// 允许例外：仅 XML 命名空间/文档 URI（不会被当作网络请求）。
$script:BenignHttpHosts = @(
    'schemas.android.com', 'www.w3.org', 'w3.org', 'ns.adobe.com', 'xmlpull.org',
    'java.sun.com', 'xml.org', 'www.xml.org', 'apache.org', 'xml.apache.org',
    'purl.org', 'www.w3.org', 'openxmlformats.org', 'purl.oclc.org'
)

# ------------------------------------------------------------------ 基础工具函数

function Add-Result {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][ValidateSet('PASS', 'WARN', 'FAIL', 'INFO')][string]$Severity,
        [Parameter(Mandatory)][string]$Message,
        [string]$Evidence = ''
    )
    $script:Results.Add([pscustomobject]@{
            id       = $Id
            severity = $Severity
            message  = (Format-EvidenceText $Message)
            evidence = (Format-EvidenceText $Evidence)
        })
}

function Format-EvidenceText {
    param([string]$Text, [int]$MaxLen = 1200)
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    $t = $Text -replace "`r?`n", ' '
    $t = $t.Trim()
    if ($t.Length -gt $MaxLen) { $t = $t.Substring(0, $MaxLen) + '…' }
    return $t
}

function Invoke-Exe {
    param([Parameter(Mandatory)][string]$FilePath, [string[]]$Arguments = @())
    $out = & $FilePath @Arguments 2>&1
    $code = $LASTEXITCODE
    $text = (($out | ForEach-Object { "$_" }) -join "`n")
    return [pscustomobject]@{ ExitCode = $code; Text = $text }
}

function Read-AllBytes {
    param([Parameter(Mandatory)]$Stream)
    $ms = [System.IO.MemoryStream]::new()
    $Stream.CopyTo($ms)
    return $ms.ToArray()
}

function Get-BoolAttr {
    param($Value)
    if ($null -eq $Value) { return $null }
    $v = "$Value".Trim()
    if ($v -eq 'true' -or $v -eq '1') { return $true }
    if ($v -eq 'false' -or $v -eq '0') { return $false }
    if ($v -match '^0x[0-9a-fA-F]+$') { return ([Convert]::ToInt64($v, 16) -ne 0) }
    return $null
}

# ------------------------------------------------------------------ 路径解析

function Resolve-SdkRoot {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    elseif ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    $candidates += 'D:\DevTools\android-sdk'
    foreach ($c in $candidates) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        if (Test-Path (Join-Path $c 'build-tools')) { return (Resolve-Path $c).Path }
    }
    throw "未找到 Android SDK（尝试过: $($candidates -join ', ')）。请用 -SdkRoot 或设置 ANDROID_HOME。"
}

function Resolve-BuildTools {
    param([string]$Sdk, [string]$Explicit)
    $root = Join-Path $Sdk 'build-tools'
    $preferred = if ($Explicit) { $Explicit } elseif ($env:LM_BUILD_TOOLS) { $env:LM_BUILD_TOOLS } else { '34.0.0' }
    $complete = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^\d+(\.\d+){1,3}$' -and
            (Test-Path (Join-Path $_.FullName 'aapt2.exe')) -and
            (Test-Path (Join-Path $_.FullName 'apksigner.bat'))
        }
    if (-not $complete) { throw "在 $root 下没有找到含 aapt2.exe/apksigner.bat 的 build-tools。" }
    $pref = $complete | Where-Object { $_.Name -eq $preferred } | Select-Object -First 1
    if ($pref) { return $pref.FullName }
    $best = $complete | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    return $best.FullName
}

function Resolve-ApkPath {
    param([string]$Explicit, [string]$AndroidRoot)
    if ($Explicit) {
        if (-not (Test-Path $Explicit)) { throw "APK 不存在: $Explicit" }
        return (Resolve-Path $Explicit).Path
    }
    $patterns = @(
        (Join-Path $AndroidRoot 'dist\*.apk'),
        (Join-Path $AndroidRoot 'app\build\outputs\apk\release\*.apk'),
        (Join-Path $AndroidRoot 'app\build\outputs\apk\debug\*.apk')
    )
    $files = @()
    foreach ($p in $patterns) {
        $g = @(Get-ChildItem $p -File -ErrorAction SilentlyContinue)
        if ($g.Count -gt 0) { $files += $g }
    }
    if ($files.Count -eq 0) { throw "未找到任何 APK（android/dist 或 app/build/outputs/apk）。请等 Lead 构建完成或用 -ApkPath 指定。" }
    # 优先 dist/ 下最新的；否则 release 优先，再 debug
    $dist = @($files | Where-Object { $_.FullName -match '\\dist\\' })
    if ($dist.Count -gt 0) { return ($dist | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName }
    $release = @($files | Where-Object { $_.FullName -match '\\release\\' })
    if ($release.Count -gt 0) { return ($release | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName }
    return ($files | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

# ------------------------------------------------------------------ aapt2 xmltree 解析

function ConvertFrom-AaptXmlTree {
    param([Parameter(Mandatory)][string]$Text)
    $nodes = [System.Collections.Generic.List[object]]::new()
    $stack = [System.Collections.Generic.List[object]]::new()
    foreach ($rawLine in ($Text -split "`n")) {
        $line = $rawLine.TrimEnd("`r")
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -match '^\s*N:\s+') { continue }
        $em = [regex]::Match($line, '^(?<indent>\s*)E:\s+(?<name>[A-Za-z0-9_.:-]+)')
        if ($em.Success) {
            $indent = $em.Groups['indent'].Value.Length
            while ($stack.Count -gt 0 -and $stack[$stack.Count - 1].indent -ge $indent) {
                $stack.RemoveAt($stack.Count - 1)
            }
            $parent = $null
            if ($stack.Count -gt 0) { $parent = $stack[$stack.Count - 1] }
            $node = [pscustomobject]@{
                name     = $em.Groups['name'].Value
                indent   = $indent
                parent   = $parent
                attrs    = @{}
                children = [System.Collections.Generic.List[object]]::new()
            }
            if ($null -ne $parent) { $parent.children.Add($node) }
            $nodes.Add($node)
            $stack.Add($node)
            continue
        }
        $am = [regex]::Match($line, '^(?<indent>\s*)A:\s+(?<head>.+?)(?:\(0x[0-9a-fA-F]+\))?=(?<val>.*)$')
        if ($am.Success -and $stack.Count -gt 0) {
            $head = $am.Groups['head'].Value.Trim()
            $key = $head
            $lastColon = $head.LastIndexOf(':')
            if ($lastColon -ge 0) { $key = $head.Substring($lastColon + 1) }
            $val = $am.Groups['val'].Value
            $val = [regex]::Replace($val, '\s+\(Raw: [^)]*\)\s*$', '')
            $val = $val.Trim()
            if ($val.Length -ge 2 -and $val.StartsWith('"') -and $val.EndsWith('"')) {
                $val = $val.Substring(1, $val.Length - 2)
            }
            $tm = [regex]::Match($val, '^\(type 0x[0-9a-fA-F]+\)(?<num>0x[0-9a-fA-F]+|\d+)$')
            if ($tm.Success) { $val = $tm.Groups['num'].Value }
            $stack[$stack.Count - 1].attrs[$key] = $val
        }
    }
    return $nodes
}

function Get-AttrValue {
    param($Node, [string]$Key)
    if ($null -eq $Node) { return $null }
    if ($Node.attrs.ContainsKey($Key)) { return $Node.attrs[$Key] }
    return $null
}

function Get-ChildNodes {
    param($Node, [string[]]$Names)
    if ($null -eq $Node) { return @() }
    return @($Node.children | Where-Object { $Names -contains $_.name })
}

# ------------------------------------------------------------------ 报告输出

function Write-Reports {
    param(
        $Meta,
        [string]$OutDir,
        [string]$ToolName
    )
    $fail = @($script:Results | Where-Object { $_.severity -eq 'FAIL' }).Count
    $warn = @($script:Results | Where-Object { $_.severity -eq 'WARN' }).Count
    $pass = @($script:Results | Where-Object { $_.severity -eq 'PASS' }).Count
    $info = @($script:Results | Where-Object { $_.severity -eq 'INFO' }).Count
    $exitCode = if ($fail -gt 0) { 1 } else { 0 }

    if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $base = Join-Path $OutDir "$ToolName-$stamp"

    $report = [ordered]@{
        script         = 'check-apk-hardening.ps1'
        scriptVersion  = $script:Version
        generatedAt    = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz')
        toolName       = $ToolName
        apk            = $Meta.Apk
        environment    = $Meta.Environment
        summary        = [ordered]@{
            total    = $script:Results.Count
            pass     = $pass
            warn     = $warn
            fail     = $fail
            info     = $info
            exitCode = $exitCode
        }
        deliverability = $Meta.Deliverability
        results        = $script:Results
        notes          = $Meta.Notes
    }
    $jsonPath = "$base.json"
    $report | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding utf8

    $md = [System.Text.StringBuilder]::new()
    [void]$md.AppendLine("# APK 加固静态检查报告（$ToolName）")
    [void]$md.AppendLine('')
    [void]$md.AppendLine("> 生成时间：$($report.generatedAt)　脚本版本：$($script:Version)")
    [void]$md.AppendLine("> **本报告为静态检查，不包含安装/运行/流量抓包等动态测试。**")
    [void]$md.AppendLine('')
    [void]$md.AppendLine('## 1. 受检对象')
    [void]$md.AppendLine('')
    [void]$md.AppendLine('| 项目 | 值 |')
    [void]$md.AppendLine('| --- | --- |')
    [void]$md.AppendLine("| APK 路径 | ``$($Meta.Apk.path)`` |")
    [void]$md.AppendLine("| SHA-256 | ``$($Meta.Apk.sha256)`` |")
    [void]$md.AppendLine("| 大小 | $($Meta.Apk.sizeBytes) 字节 / $([math]::Round($Meta.Apk.sizeBytes / 1MB, 2)) MiB |")
    [void]$md.AppendLine("| 文件时间 | $($Meta.Apk.lastWriteTime) |")
    [void]$md.AppendLine("| 构建类型判定 | $($Meta.Apk.buildKind) |")
    [void]$md.AppendLine("| 签名判定 | $($Meta.Apk.signingState) |")
    [void]$md.AppendLine("| 交付签名说明 | $($Meta.Deliverability) |")
    [void]$md.AppendLine('')
    [void]$md.AppendLine('## 2. 环境')
    [void]$md.AppendLine('')
    [void]$md.AppendLine('| 项目 | 值 |')
    [void]$md.AppendLine('| --- | --- |')
    [void]$md.AppendLine("| SDK 根目录 | ``$($Meta.Environment.sdkRoot)`` |")
    [void]$md.AppendLine("| build-tools | $($Meta.Environment.buildToolsVersion)（``$($Meta.Environment.buildToolsDir)``） |")
    [void]$md.AppendLine("| aapt2 | ``$($Meta.Environment.aapt2)`` |")
    [void]$md.AppendLine("| apksigner | ``$($Meta.Environment.apksigner)`` |")
    [void]$md.AppendLine("| JAVA_HOME | ``$($Meta.Environment.javaHome)`` |")
    [void]$md.AppendLine('')
    [void]$md.AppendLine('## 3. 结果统计')
    [void]$md.AppendLine('')
    [void]$md.AppendLine('| PASS | WARN | FAIL | INFO | 退出码 |')
    [void]$md.AppendLine('| --- | --- | --- | --- | --- |')
    [void]$md.AppendLine("| $pass | $warn | $fail | $info | $exitCode |")
    [void]$md.AppendLine('')
    if ($fail -gt 0) {
        [void]$md.AppendLine('### FAIL 项（必须修复/必须说明）')
        [void]$md.AppendLine('')
        foreach ($r in ($script:Results | Where-Object { $_.severity -eq 'FAIL' })) {
            [void]$md.AppendLine("- **$($r.id)**：$($r.message)")
            if ($r.evidence) { [void]$md.AppendLine("  - 证据：$($r.evidence)") }
        }
        [void]$md.AppendLine('')
    }
    if ($warn -gt 0) {
        [void]$md.AppendLine('### WARN 项（需人工确认/记录为残留风险）')
        [void]$md.AppendLine('')
        foreach ($r in ($script:Results | Where-Object { $_.severity -eq 'WARN' })) {
            [void]$md.AppendLine("- **$($r.id)**：$($r.message)")
            if ($r.evidence) { [void]$md.AppendLine("  - 证据：$($r.evidence)") }
        }
        [void]$md.AppendLine('')
    }
    [void]$md.AppendLine('## 4. 全部检查项')
    [void]$md.AppendLine('')
    [void]$md.AppendLine('| 检查项 | 级别 | 说明 |')
    [void]$md.AppendLine('| --- | --- | --- |')
    foreach ($r in $script:Results) {
        $msg = $r.message -replace '\|', '\|'
        [void]$md.AppendLine("| ``$($r.id)`` | $($r.severity) | $msg |")
    }
    [void]$md.AppendLine('')
    [void]$md.AppendLine('## 5. 备注与已知限制')
    [void]$md.AppendLine('')
    foreach ($n in $Meta.Notes) { [void]$md.AppendLine("- $n") }
    [void]$md.AppendLine('- 本环境无模拟器/真机，未执行安装运行、界面自动化、流量抓包、运行时行为验证。')
    [void]$md.AppendLine('- dex 字符串扫描为启发式：命中代表存在相关 API/字符串常量，不能证明运行路径一定可达；未命中也不等于绝对不存在（R8 混淆/字符串拼接可能规避）。')
    [void]$md.AppendLine('')
    $mdPath = "$base.md"
    $md.ToString() | Set-Content -Path $mdPath -Encoding utf8

    # 便于引用的 latest 副本
    $jsonPath | Copy-Item -Destination (Join-Path $OutDir "$ToolName-latest.json") -Force
    $mdPath | Copy-Item -Destination (Join-Path $OutDir "$ToolName-latest.md") -Force

    return [pscustomobject]@{ JsonPath = $jsonPath; MdPath = $mdPath; Fail = $fail; Warn = $warn; ExitCode = $exitCode }
}

# ==================================================================
# 主流程
# ==================================================================

$androidRoot = Split-Path -Parent $PSScriptRoot
if ($SelfTest) {
    $debugApk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $debugApk)) {
        # 允许 AGP 生成的其它命名
        $found = Get-ChildItem (Join-Path $androidRoot 'app\build\outputs\apk\debug') -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $debugApk = $found.FullName }
    }
    if (-not (Test-Path $debugApk)) {
        Write-Host '[SelfTest] 跳过：未找到 debug APK（先跑 :app:assembleDebug）。' -ForegroundColor Yellow
        exit 2
    }
    if (-not $PSBoundParameters.ContainsKey('BuildKind')) { $BuildKind = 'debug' }
    if (-not $PSBoundParameters.ContainsKey('SigningExpectation')) { $SigningExpectation = 'test' }
    if (-not $ApkPath) { $ApkPath = $debugApk }
    Write-Host "[SelfTest] 使用 debug APK 自检规则: $debugApk" -ForegroundColor Cyan
}

$sdk = Resolve-SdkRoot -Explicit $SdkRoot
$btDir = Resolve-BuildTools -Sdk $sdk -Explicit $BuildToolsVersion
$btVersion = Split-Path $btDir -Leaf
$aapt2 = Join-Path $btDir 'aapt2.exe'
$apksigner = Join-Path $btDir 'apksigner.bat'
$zipalign = Join-Path $btDir 'zipalign.exe'
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    foreach ($jdk in @($env:JAVA_HOME, 'D:\DevTools\jdk17') | Where-Object { $_ }) {
        if (Test-Path (Join-Path $jdk 'bin\java.exe')) { $env:JAVA_HOME = $jdk; break }
    }
}
if ($env:JAVA_HOME) { $env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH" }
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Write-Host '警告：未找到 JAVA_HOME，apksigner 可能无法运行（签名检查将被跳过）。' -ForegroundColor Yellow
}

$apk = Resolve-ApkPath -Explicit $ApkPath -AndroidRoot $androidRoot
$apkItem = Get-Item $apk
$sha256 = (Get-FileHash -Algorithm SHA256 -Path $apk).Hash.ToLowerInvariant()
$fileName = $apkItem.Name

if ($BuildKind -eq 'auto') {
    if ($fileName -match '(?i)debug') { $BuildKind = 'debug' } else { $BuildKind = 'release' }
}
$signingState = 'unknown'
if ($SigningExpectation -eq 'auto') {
    if ($fileName -match '(?i)unsigned') { $SigningExpectation = 'unsigned' }
    elseif ($fileName -match '(?i)debug|test') { $SigningExpectation = 'test' }
    else { $SigningExpectation = 'release' }
}

Write-Host ''
Write-Host "== 直播监测 Android APK 加固检查 ==" -ForegroundColor Cyan
Write-Host "APK      : $apk"
Write-Host "SHA-256  : $sha256"
Write-Host "大小     : $($apkItem.Length) 字节"
Write-Host "构建类型 : $BuildKind（签名预期: $SigningExpectation）"
Write-Host "SDK      : $sdk"
Write-Host "build-tools: $btVersion ($btDir)"
Write-Host ''

# ---------------- 1. aapt2 dump badging ----------------
$badging = Invoke-Exe -FilePath $aapt2 -Arguments @('dump', 'badging', $apk)
$xmltree = Invoke-Exe -FilePath $aapt2 -Arguments @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $apk)
if ($badging.ExitCode -ne 0) { throw "aapt2 dump badging 失败: $($badging.Text)" }
if ($xmltree.ExitCode -ne 0) { throw "aapt2 dump xmltree 失败: $($xmltree.Text)" }

$package = $null
if ($badging.Text -match "package: name='([^']+)'") { $package = $Matches[1] }
if ($package -ne $ExpectedPackage) {
    if ($package -eq "$ExpectedPackage.debug") {
        $sev = if ($BuildKind -eq 'debug') { 'WARN' } else { 'FAIL' }
        Add-Result -Id 'manifest.package' -Severity $sev -Message "包名为 $package（debug 变体后缀 .debug）" -Evidence "expected=$ExpectedPackage actual=$package"
    }
    else {
        Add-Result -Id 'manifest.package' -Severity 'FAIL' -Message "包名与预期不符：$package" -Evidence "expected=$ExpectedPackage actual=$package"
    }
}
else {
    Add-Result -Id 'manifest.package' -Severity 'PASS' -Message "包名正确：$package"
}

if ($badging.Text -match "(?:min)?SdkVersion:'(\d+)'") {
    $minSdk = [int]$Matches[1]
    if ($minSdk -lt 26) { Add-Result -Id 'sdk.min' -Severity 'FAIL' -Message "minSdk=$minSdk 低于项目约定 26" }
    elseif ($minSdk -ne 26) { Add-Result -Id 'sdk.min' -Severity 'WARN' -Message "minSdk=$minSdk（项目约定 26）" }
    else { Add-Result -Id 'sdk.min' -Severity 'PASS' -Message "minSdk=26" }
}
else { Add-Result -Id 'sdk.min' -Severity 'FAIL' -Message '未解析到 minSdkVersion/sdkVersion' }
if ($badging.Text -match "targetSdkVersion:'(\d+)'") {
    $targetSdk = [int]$Matches[1]
    if ($targetSdk -lt 35) { Add-Result -Id 'sdk.target' -Severity 'FAIL' -Message "targetSdk=$targetSdk 低于要求的 35（Google Play 目标 API 要求）" }
    else { Add-Result -Id 'sdk.target' -Severity 'PASS' -Message "targetSdk=$targetSdk（>=35）" }
}
else { Add-Result -Id 'sdk.target' -Severity 'FAIL' -Message '未解析到 targetSdkVersion' }
if ($badging.Text -match "versionName='([^']*)'") {
    $versionName = $Matches[1]
    if ($ExpectedVersionName) {
        if ($versionName -eq $ExpectedVersionName) {
            Add-Result -Id 'manifest.version' -Severity 'PASS' -Message "versionName=$versionName（与预期一致）"
        }
        else {
            Add-Result -Id 'manifest.version' -Severity 'FAIL' -Message "versionName=$versionName 与预期 $ExpectedVersionName 不一致" -Evidence "actual=$versionName expected=$ExpectedVersionName"
        }
    }
    else {
        Add-Result -Id 'manifest.version' -Severity 'PASS' -Message "versionName=$versionName"
    }
}
else { Add-Result -Id 'manifest.version' -Severity 'WARN' -Message '未解析到 versionName' }
if ($badging.Text -match "launchable-activity: name='([^']+)'") {
    $launch = $Matches[1]
    if ($launch -eq 'com.livemonitor.app.ui.MainActivity') { Add-Result -Id 'manifest.launcher' -Severity 'PASS' -Message "启动 Activity 正确：$launch" }
    else { Add-Result -Id 'manifest.launcher' -Severity 'WARN' -Message "启动 Activity 与预期不同：$launch" }
}
if ($badging.Text -match 'application-debuggable') { $badgingDebuggable = $true } else { $badgingDebuggable = $false }

$manifestNodes = ConvertFrom-AaptXmlTree -Text $xmltree.Text
$manifestNode = $manifestNodes | Where-Object { $_.name -eq 'manifest' } | Select-Object -First 1
if (-not $manifestNode) { throw 'aapt2 xmltree 未输出 manifest 节点' }
$usesSdk = Get-ChildNodes -Node $manifestNode -Names @('uses-sdk') | Select-Object -First 1
$appNode = Get-ChildNodes -Node $manifestNode -Names @('application') | Select-Object -First 1
if (-not $appNode) { throw 'AndroidManifest 中缺少 <application>' }

# ---------------- 2. 权限检查 ----------------
$declaredPermissions = [System.Collections.Generic.List[string]]::new()
foreach ($p in (Get-ChildNodes -Node $manifestNode -Names @('uses-permission', 'uses-permission-sdk-23', 'uses-permission-sdk-m'))) {
    $n = Get-AttrValue -Node $p -Key 'name'
    if ($n) { $declaredPermissions.Add($n) }
}
$unexpectedPermissions = @()
$sensitiveFound = @()
foreach ($perm in $declaredPermissions) {
    $short = $perm -replace '^android\.permission\.', ''
    $isDynamicPkgPerm = $perm -eq "$package.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" -or
        $perm -match '\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$'
    if ($isDynamicPkgPerm) { continue }
    if ($script:AllowedPermissions -notcontains $perm) { $unexpectedPermissions += $perm }
    foreach ($s in $script:SensitivePermissionPatterns) {
        if ($short -match $s) { $sensitiveFound += $perm; break }
    }
}
if ($sensitiveFound.Count -gt 0) {
    Add-Result -Id 'permissions.sensitive' -Severity 'FAIL' -Message '出现敏感/危险权限' -Evidence (($sensitiveFound | Select-Object -Unique) -join ', ')
}
else { Add-Result -Id 'permissions.sensitive' -Severity 'PASS' -Message '未出现定位/通讯录/短信/存储/悬浮窗/无障碍/开机自启等敏感权限' }
if ($unexpectedPermissions.Count -gt 0) {
    Add-Result -Id 'permissions.allowlist' -Severity 'FAIL' -Message '存在白名单之外的权限' -Evidence ($unexpectedPermissions -join ', ')
}
else { Add-Result -Id 'permissions.allowlist' -Severity 'PASS' -Message "权限与白名单一致（$($declaredPermissions.Count) 项，含 AndroidX 动态接收器权限）" }
$missingPermissions = @($script:AllowedPermissions | Where-Object { $declaredPermissions -notcontains $_ })
if ($missingPermissions.Count -gt 0) {
    Add-Result -Id 'permissions.required' -Severity 'FAIL' -Message '缺少预期权限（功能或 FGS 类型声明可能不完整）' -Evidence ($missingPermissions -join ', ')
}
else { Add-Result -Id 'permissions.required' -Severity 'PASS' -Message '预期 6 项权限全部声明' }

$features = @(Get-ChildNodes -Node $manifestNode -Names @('uses-feature'))
if ($features.Count -gt 0) {
    $f = ($features | ForEach-Object { Get-AttrValue -Node $_ -Key 'name' }) -join ', '
    Add-Result -Id 'manifest.uses_feature' -Severity 'WARN' -Message '声明了 uses-feature（会限制部分设备安装）' -Evidence $f
}
else { Add-Result -Id 'manifest.uses_feature' -Severity 'PASS' -Message '无硬件功能依赖声明' }

# ---------------- 3. application 属性 ----------------
$appAttrs = $appNode.attrs
$allowBackup = Get-AttrValue -Node $appNode -Key 'allowBackup'
if ((Get-BoolAttr $allowBackup) -eq $false) { Add-Result -Id 'app.allowBackup' -Severity 'PASS' -Message 'allowBackup=false（数据不进入云备份）' }
elseif ($null -eq $allowBackup) { Add-Result -Id 'app.allowBackup' -Severity 'FAIL' -Message '未显式声明 allowBackup=false（targetSdk 35 缺省允许备份）' }
else { Add-Result -Id 'app.allowBackup' -Severity 'FAIL' -Message "allowBackup=$allowBackup，直播列表可能被云备份" }

$cleartext = Get-AttrValue -Node $appNode -Key 'usesCleartextTraffic'
if ((Get-BoolAttr $cleartext) -eq $false) { Add-Result -Id 'app.cleartext' -Severity 'PASS' -Message 'usesCleartextTraffic=false' }
elseif ($null -eq $cleartext) { Add-Result -Id 'app.cleartext' -Severity 'WARN' -Message '未显式声明 usesCleartextTraffic=false（targetSdk>=28 缺省为 false，但建议显式）' }
else { Add-Result -Id 'app.cleartext' -Severity 'FAIL' -Message "usesCleartextTraffic=$cleartext，允许明文 HTTP" }

$nscAttr = Get-AttrValue -Node $appNode -Key 'networkSecurityConfig'
if ($nscAttr) { Add-Result -Id 'app.nsc_attr' -Severity 'PASS' -Message '已声明 networkSecurityConfig' -Evidence "ref=$nscAttr" }
else { Add-Result -Id 'app.nsc_attr' -Severity 'FAIL' -Message '未声明 networkSecurityConfig' }

$debuggableAttr = Get-AttrValue -Node $appNode -Key 'debuggable'
$isDebuggable = (Get-BoolAttr $debuggableAttr) -eq $true
if ($isDebuggable) {
    $sev = if ($BuildKind -eq 'debug') { 'WARN' } else { 'FAIL' }
    Add-Result -Id 'app.debuggable' -Severity $sev -Message 'APK 是 debuggable 构建（可被调试/注入，禁止对外分发）' -Evidence "android:debuggable=$debuggableAttr"
}
else { Add-Result -Id 'app.debuggable' -Severity 'PASS' -Message 'debuggable 未开启' }

$testOnlyAttr = Get-AttrValue -Node $appNode -Key 'testOnly'
if ((Get-BoolAttr $testOnlyAttr) -eq $true) {
    $sev = if ($BuildKind -eq 'debug') { 'WARN' } else { 'FAIL' }
    Add-Result -Id 'app.testOnly' -Severity $sev -Message 'testOnly=true（仅能通过 adb 安装，不可分发）'
}
else { Add-Result -Id 'app.testOnly' -Severity 'PASS' -Message '未设置 testOnly' }

# application 属性异常：陌生/削弱安全的属性
$knownAppAttrs = @('name', 'allowBackup', 'fullBackupContent', 'icon', 'roundIcon', 'label', 'networkSecurityConfig',
    'supportsRtl', 'theme', 'usesCleartextTraffic', 'debuggable', 'testOnly', 'appComponentFactory',
    'extractNativeLibs', 'enableOnBackInvokedCallback', 'dataExtractionRules', 'localeConfig',
    'requestLegacyExternalStorage', 'fullBackupOnly', 'hasCode', 'allowTaskReparenting', 'backupAgent',
    'killAfterRestore', 'restoreAnyVersion', 'vmSafeMode', 'usesNonSdkApi', 'enableFreeformWindows',
    'resizeableActivity', 'directBootAware', 'defaultToDeviceProtectedStorage', 'persistent')
$weakeningAttrs = @{
    'requestLegacyExternalStorage' = '申请旧版外部存储兼容模式（targetSdk 35 无意义但异常）'
    'fullBackupOnly'               = 'fullBackupOnly=true 语义务必复核'
    'usesNonSdkApi'                = 'usesNonSdkApi=true 会使用非 SDK 接口（商店政策风险）'
    'persistent'                   = 'persistent=true 异常（系统级应用属性）'
    'vmSafeMode'                   = 'vmSafeMode=true 异常'
}
$unknownAttrs = @()
foreach ($k in $appAttrs.Keys) {
    if ($knownAppAttrs -notcontains $k) { $unknownAttrs += $k }
    if ($weakeningAttrs.ContainsKey($k)) {
        Add-Result -Id "app.attr.$k" -Severity 'FAIL' -Message $weakeningAttrs[$k] -Evidence "$k=$($appAttrs[$k])"
    }
}
if ($unknownAttrs.Count -gt 0) {
    Add-Result -Id 'app.attrs_unknown' -Severity 'WARN' -Message 'application 存在未预期的属性，需人工确认' -Evidence ($unknownAttrs -join ', ')
}
else { Add-Result -Id 'app.attrs_unknown' -Severity 'PASS' -Message 'application 属性均在预期集合内' }

$appFactory = Get-AttrValue -Node $appNode -Key 'appComponentFactory'
if (-not $appFactory) { Add-Result -Id 'app.component_factory' -Severity 'PASS' -Message '未自定义 appComponentFactory' }
elseif ($appFactory -like 'androidx.*') { Add-Result -Id 'app.component_factory' -Severity 'PASS' -Message "appComponentFactory=$appFactory（AndroidX 标准实现）" }
else { Add-Result -Id 'app.component_factory' -Severity 'WARN' -Message "自定义 appComponentFactory=$appFactory，需人工确认其不改变组件加载行为" }

$fullBackup = Get-AttrValue -Node $appNode -Key 'fullBackupContent'
if ($fullBackup -and (Get-BoolAttr $fullBackup) -eq $true) {
    Add-Result -Id 'app.fullBackupContent' -Severity 'FAIL' -Message 'fullBackupContent=true，允许全量备份（与 allowBackup=false 冲突）'
}
else { Add-Result -Id 'app.fullBackupContent' -Severity 'PASS' -Message "fullBackupContent=$fullBackup" }

# ---------------- 4. 组件导出面 ----------------
$componentTypes = @('activity', 'service', 'receiver', 'provider')
$allComponents = @()
foreach ($t in $componentTypes) {
    foreach ($c in (Get-ChildNodes -Node $appNode -Names @($t))) {
        $allComponents += [pscustomobject]@{
            type     = $t
            name     = (Get-AttrValue -Node $c -Key 'name')
            exported = (Get-BoolAttr (Get-AttrValue -Node $c -Key 'exported'))
            node     = $c
        }
    }
}
$resolvedComponents = foreach ($c in $allComponents) {
    $name = $c.name
    if ($name -and $name.StartsWith('.')) { $name = "$package$name" }
    [pscustomobject]@{ type = $c.type; name = $name; exported = $c.exported; node = $c.node }
}
$exportedComponents = @($resolvedComponents | Where-Object { $_.exported -eq $true })
$unexpectedExported = @($exportedComponents | Where-Object { $script:ExpectedExportedComponents -notcontains $_.name })
$hardExported = @()
$protectedExported = @()
$debugToolingExported = @()
foreach ($c in $unexpectedExported) {
    $permAttr = Get-AttrValue -Node $c.node -Key 'permission'
    if ($permAttr) { $protectedExported += "$($c.type):$($c.name)（permission=$permAttr）" }
    elseif ($BuildKind -eq 'debug' -and $c.name -like 'androidx.*') { $debugToolingExported += "$($c.type):$($c.name)" }
    else { $hardExported += "$($c.type):$($c.name)" }
}
if ($hardExported.Count -gt 0) {
    Add-Result -Id 'components.exported' -Severity 'FAIL' -Message '存在预期之外导出的组件（任意应用可调用）' -Evidence ($hardExported -join ', ')
}
elseif ($debugToolingExported.Count -gt 0) {
    Add-Result -Id 'components.exported' -Severity 'WARN' -Message 'debug 构建中包含 AndroidX 调试工具组件（release 不应存在，需对 release APK 复核）' -Evidence ($debugToolingExported -join ', ')
}
elseif ($protectedExported.Count -gt 0) {
    Add-Result -Id 'components.exported' -Severity 'WARN' -Message '存在受系统签名权限保护的导出组件（第三方普通应用不可调用，需人工确认）' -Evidence ($protectedExported -join ', ')
}
else { Add-Result -Id 'components.exported' -Severity 'PASS' -Message "只有预期组件导出（$((@($exportedComponents | ForEach-Object { $_.name }) ) -join ', ')）" }

$mainActivity = $resolvedComponents | Where-Object { $_.name -eq 'com.livemonitor.app.ui.MainActivity' } | Select-Object -First 1
if (-not $mainActivity) { Add-Result -Id 'components.main_activity' -Severity 'FAIL' -Message '未找到 .ui.MainActivity 组件' }
elseif ($mainActivity.exported -ne $true) { Add-Result -Id 'components.main_activity' -Severity 'FAIL' -Message '启动 Activity 未导出（无法从桌面启动）' }
else { Add-Result -Id 'components.main_activity' -Severity 'PASS' -Message 'MainActivity 已导出（唯一业务导出组件）' }

# 非主 Activity 的组件是否带 intent-filter 且 exported 非 false 且无系统权限保护
$intentFilterIssues = @()
foreach ($c in $resolvedComponents) {
    if ($c.name -eq 'com.livemonitor.app.ui.MainActivity') { continue }
    $ifs = @(Get-ChildNodes -Node $c.node -Names @('intent-filter'))
    if ($ifs.Count -eq 0) { continue }
    $permAttr = Get-AttrValue -Node $c.node -Key 'permission'
    if ($c.exported -eq $true -and -not $permAttr) {
        $intentFilterIssues += "$($c.type):$($c.name)"
    }
}
if ($intentFilterIssues.Count -gt 0) {
    Add-Result -Id 'components.filter_nonmain' -Severity 'FAIL' -Message '非主组件声明 intent-filter、导出且无权限保护（外部可拉起）' -Evidence ($intentFilterIssues -join ', ')
}
else { Add-Result -Id 'components.filter_nonmain' -Severity 'PASS' -Message '非主组件无无需权限即可触达的 Intent 入口' }

# MainActivity 的 intent-filter 只能 MAIN/LAUNCHER（无深链接/BROWSABLE）
if ($mainActivity) {
    $actions = @()
    $categories = @()
    $dataSchemes = @()
    foreach ($f in (Get-ChildNodes -Node $mainActivity.node -Names @('intent-filter'))) {
        foreach ($a in (Get-ChildNodes -Node $f -Names @('action'))) { $actions += Get-AttrValue -Node $a -Key 'name' }
        foreach ($c in (Get-ChildNodes -Node $f -Names @('category'))) { $categories += Get-AttrValue -Node $c -Key 'name' }
        foreach ($d in (Get-ChildNodes -Node $f -Names @('data'))) { $dataSchemes += Get-AttrValue -Node $d -Key 'scheme' }
    }
    $badMainFilters = @()
    $badMainFilters += @($actions | Where-Object { $_ -ne 'android.intent.action.MAIN' })
    $badMainFilters += @($categories | Where-Object { $_ -ne 'android.intent.category.LAUNCHER' })
    $badMainFilters += @($dataSchemes | Where-Object { $_ })
    if ($badMainFilters.Count -gt 0) {
        Add-Result -Id 'components.main_filter' -Severity 'FAIL' -Message 'MainActivity 存在 MAIN/LAUNCHER 之外的 intent-filter（深链接/外部拉起面）' -Evidence ($badMainFilters -join ', ')
    }
    else { Add-Result -Id 'components.main_filter' -Severity 'PASS' -Message 'MainActivity 仅有 MAIN/LAUNCHER，无深链接' }
}

# ---------------- 4b. v1.1.0：包可见性 / 跳转 Activity / 深链接 ----------------
if ($declaredPermissions -contains 'android.permission.QUERY_ALL_PACKAGES') {
    Add-Result -Id 'permissions.query_all_packages' -Severity 'FAIL' -Message 'APK 申请了 QUERY_ALL_PACKAGES（可枚举全部已安装应用，商店高危权限，与最小可见性设计冲突）'
}
else {
    Add-Result -Id 'permissions.query_all_packages' -Severity 'PASS' -Message '未申请 QUERY_ALL_PACKAGES'
}

$queriesNode = @($manifestNodes | Where-Object { $_.name -eq 'queries' }) | Select-Object -First 1
if ($null -eq $queriesNode) {
    Add-Result -Id 'apk.queries' -Severity 'WARN' -Message 'Manifest 未声明 <queries>（Android 11+ 打开平台 App 需要；若该版本未实现跳转功能可忽略）'
}
else {
    $queryPkgs = @()
    $allowedIntentSchemes = @()
    $unexpectedIntentSchemes = @()
    $broadQuery = @()
    foreach ($ch in $queriesNode.children) {
        if ($ch.name -eq 'package') { $queryPkgs += (Get-AttrValue -Node $ch -Key 'name') }
        elseif ($ch.name -eq 'intent') {
            # v1.1.1：下载 App 功能已下架，queries 只应保留 VIEW + https（浏览器解析用）
            $intentActions = @($ch.children | Where-Object { $_.name -eq 'action' } | ForEach-Object { Get-AttrValue -Node $_ -Key 'name' })
            $intentSchemes = @($ch.children | Where-Object { $_.name -eq 'data' } | ForEach-Object { Get-AttrValue -Node $_ -Key 'scheme' } | Where-Object { $_ })
            $badAction = @($intentActions | Where-Object { $_ -ne 'android.intent.action.VIEW' })
            $unexpectedSchemes = @($intentSchemes | Where-Object { $_ -ne 'https' })
            if ($badAction.Count -gt 0 -or $intentSchemes.Count -eq 0) {
                $broadQuery += "intent(action=$($intentActions -join '/'),scheme=$($intentSchemes -join '/'))"
            }
            elseif ($unexpectedSchemes.Count -gt 0) {
                $unexpectedIntentSchemes += $unexpectedSchemes
            }
            else { $allowedIntentSchemes += $intentSchemes }
        }
        else { $broadQuery += $ch.name }
    }
    $queryPkgs = @($queryPkgs | Where-Object { $_ } | Select-Object -Unique)
    $allowedIntentSchemes = @($allowedIntentSchemes | Select-Object -Unique)
    $unexpectedIntentSchemes = @($unexpectedIntentSchemes | Select-Object -Unique)
    $missingQ = @($script:ExpectedQueriesPackages | Where-Object { $queryPkgs -notcontains $_ })
    $extraQ = @($queryPkgs | Where-Object { $script:ExpectedQueriesPackages -notcontains $_ })
    if ($broadQuery.Count -gt 0) {
        Add-Result -Id 'apk.queries' -Severity 'FAIL' -Message 'queries 存在宽泛声明（未限制 scheme 的 <intent>/<provider>），暴露更多已安装应用信息' -Evidence ($broadQuery -join '; ')
    }
    elseif ($unexpectedIntentSchemes.Count -gt 0) {
        Add-Result -Id 'apk.queries' -Severity 'WARN' -Message 'queries 含非 https 的 intent scheme（v1.1.1 已下架“下载 App”，若含 market 应清理）' -Evidence ($unexpectedIntentSchemes -join ',')
    }
    elseif ($missingQ.Count -gt 0 -or $extraQ.Count -gt 0) {
        Add-Result -Id 'apk.queries' -Severity 'WARN' -Message 'queries 包列表与预期 5 平台包不一致' -Evidence "missing=$($missingQ -join ','); extra=$($extraQ -join ',')"
    }
    else {
        $intentNote = if ($allowedIntentSchemes.Count -gt 0) { "；受限 intent scheme: $($allowedIntentSchemes -join ',')（仅浏览器解析）" } else { '' }
        Add-Result -Id 'apk.queries' -Severity 'PASS' -Message "queries 精确声明 5 个平台包（$($queryPkgs.Count) 个）$intentNote"
    }
}

$jumpComp = @($resolvedComponents | Where-Object { $_.name -eq 'com.livemonitor.app.ui.RoomJumpActivity' }) | Select-Object -First 1
if ($null -eq $jumpComp) {
    Add-Result -Id 'components.jump_activity' -Severity 'WARN' -Message 'APK 未声明 .ui.RoomJumpActivity（跳转功能可能未包含；最终版本应存在且 exported=false）'
}
elseif ($jumpComp.exported -eq $true) {
    Add-Result -Id 'components.jump_activity' -Severity 'FAIL' -Message 'RoomJumpActivity 被导出（外部应用可拉起跳转弹窗并注入参数）'
}
else {
    Add-Result -Id 'components.jump_activity' -Severity 'PASS' -Message 'RoomJumpActivity 存在且 exported=false（仅本 App/通知 PendingIntent 可启动）'
}

# 深链接：任何组件 intent-filter 含 <data> 或 BROWSABLE 都视为外部入口
$deepLinkHits = @()
foreach ($f in @($manifestNodes | Where-Object { $_.name -eq 'intent-filter' })) {
    $hasData = @($f.children | Where-Object { $_.name -eq 'data' }).Count -gt 0
    $hasBrowsable = $false
    foreach ($c in $f.children) {
        if ($c.name -eq 'category' -and (Get-AttrValue -Node $c -Key 'name') -eq 'android.intent.category.BROWSABLE') { $hasBrowsable = $true }
    }
    if ($hasData -or $hasBrowsable) {
        $owner = if ($f.parent) { $f.parent.name } else { 'unknown' }
        $flat = @($f.children | Where-Object { $_.name -eq 'action' } | ForEach-Object { Get-AttrValue -Node $_ -Key 'name' }) -join '/'
        $deepLinkHits += "$owner($flat,data=$hasData,browsable=$hasBrowsable)"
    }
}
if ($deepLinkHits.Count -gt 0) {
    Add-Result -Id 'components.deep_link' -Severity 'FAIL' -Message 'APK 中存在深链接入口（<data>/BROWSABLE），扩大外部 Intent 攻击面' -Evidence ($deepLinkHits -join '; ')
}
else { Add-Result -Id 'components.deep_link' -Severity 'PASS' -Message '无任何深链接入口（无 <data>/BROWSABLE）' }

# ---------------- 5. 前台服务 specialUse ----------------
$services = @($resolvedComponents | Where-Object { $_.type -eq 'service' })
if ($services.Count -eq 0) { Add-Result -Id 'fgs.service' -Severity 'FAIL' -Message 'Manifest 中未声明任何 Service（监测服务缺失）' }
foreach ($svc in $services) {
    if ($svc.name -eq $script:ExpectedService) {
        if ($svc.exported -eq $true) { Add-Result -Id 'fgs.service_exported' -Severity 'FAIL' -Message '前台监测 Service 被导出（外部应用可启动/注入 Intent）' }
        else { Add-Result -Id 'fgs.service_exported' -Severity 'PASS' -Message '监测 Service 未导出' }
        $fgsType = Get-AttrValue -Node $svc.node -Key 'foregroundServiceType'
        $fgsVal = 0L
        if ($fgsType) {
            if ($fgsType -match '^0x[0-9a-fA-F]+$') { $fgsVal = [Convert]::ToInt64($fgsType, 16) }
            elseif ($fgsType -match '^\d+$') { $fgsVal = [int64]$fgsType }
        }
        if (($fgsVal -band $script:ExpectedFgsTypeBit) -ne 0) {
            if ($fgsVal -eq $script:ExpectedFgsTypeBit) {
                Add-Result -Id 'fgs.type' -Severity 'PASS' -Message 'foregroundServiceType=specialUse（单独声明）'
            }
            else {
                Add-Result -Id 'fgs.type' -Severity 'WARN' -Message 'foregroundServiceType 含 specialUse 之外的类型，需说明用途' -Evidence "value=$fgsType (0x$($fgsVal.ToString('x')))"
            }
        }
        else { Add-Result -Id 'fgs.type' -Severity 'FAIL' -Message 'Service 未声明 specialUse 前台服务类型（Android 14+ 会启动失败）' -Evidence "foregroundServiceType=$fgsType" }

        $props = @(Get-ChildNodes -Node $svc.node -Names @('property'))
        $subtype = $props | Where-Object { (Get-AttrValue -Node $_ -Key 'name') -eq $script:FgsSubtypeProperty } | Select-Object -First 1
        $subtypeVal = Get-AttrValue -Node $subtype -Key 'value'
        if ($subtypeVal) {
            Add-Result -Id 'fgs.subtype_property' -Severity 'PASS' -Message '已声明 PROPERTY_SPECIAL_USE_FGS_SUBTYPE 用途说明' -Evidence $subtypeVal
        }
        else { Add-Result -Id 'fgs.subtype_property' -Severity 'FAIL' -Message '缺少 PROPERTY_SPECIAL_USE_FGS_SUBTYPE（Android 14+ specialUse 必填用途说明）' }
    }
    else {
        Add-Result -Id 'fgs.service_unknown' -Severity 'WARN' -Message "存在额外 Service：$($svc.name)" -Evidence "exported=$($svc.exported)"
    }
}

# ---------------- 6. network_security_config 实际内容 ----------------
# 注意：release 包启用 R8 + shrinkResources 后，AGP 会缩短资源文件路径
# （例如 res/xml/network_security_config.xml → res/8G.xml），因此不能假设原路径存在。
# 解析顺序：原路径 → 用 Manifest 中 networkSecurityConfig 的资源 ID 从 resources 表映射
#           → 遍历 APK 内 res/*.xml 探测根元素为 network-security-config 的文件。
$nscEntry = 'res/xml/network_security_config.xml'
$nscResolveNotes = 'res/xml/network_security_config.xml（原路径）'
$nscTree = Invoke-Exe -FilePath $aapt2 -Arguments @('dump', 'xmltree', '--file', $nscEntry, $apk)
if ($nscTree.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($nscTree.Text)) {
    # 1) 通过资源 ID 映射（最可靠：直接来自 AndroidManifest 的 @0x... 引用）
    if ($nscAttr -match '0x([0-9a-fA-F]+)') {
        $nscId = '0x' + $Matches[1].ToLowerInvariant()
        $resDump = Invoke-Exe -FilePath $aapt2 -Arguments @('dump', 'resources', $apk)
        if ($resDump.ExitCode -eq 0) {
            $resLines = $resDump.Text -split "`n"
            for ($ri = 0; $ri -lt $resLines.Count; $ri++) {
                if ($resLines[$ri] -match "resource\s+$([regex]::Escape($nscId))\s+xml/network_security_config") {
                    for ($rj = $ri; $rj -lt [math]::Min($ri + 4, $resLines.Count); $rj++) {
                        $fileMatch = [regex]::Match($resLines[$rj], '\(file\)\s+(\S+)\s+type=')
                        if ($fileMatch.Success) {
                            $nscEntry = $fileMatch.Groups[1].Value
                            $nscResolveNotes = "$nscEntry（由资源 ID $nscId 映射，release 资源路径缩短）"
                            break
                        }
                    }
                    break
                }
            }
        }
    }
    # 2) 兜底：遍历 APK 内 res/*.xml，找根元素是 network-security-config 的那个
    if ($nscEntry -eq 'res/xml/network_security_config.xml') {
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
            $probeZip = [System.IO.Compression.ZipFile]::OpenRead($apk)
            try {
                $xmlEntries = @($probeZip.Entries | Where-Object { $_.FullName -match '^res/.*\.xml$' } | ForEach-Object { $_.FullName })
            }
            finally { $probeZip.Dispose() }
            foreach ($cand in $xmlEntries) {
                $candTree = Invoke-Exe -FilePath $aapt2 -Arguments @('dump', 'xmltree', '--file', $cand, $apk)
                if ($candTree.ExitCode -eq 0 -and $candTree.Text -match 'network-security-config') {
                    $nscEntry = $cand
                    $nscResolveNotes = "$cand（遍历 res/*.xml 探测，release 资源路径缩短）"
                    break
                }
            }
        }
        catch { }
    }
    if ($nscEntry -ne 'res/xml/network_security_config.xml') {
        $nscTree = Invoke-Exe -FilePath $aapt2 -Arguments @('dump', 'xmltree', '--file', $nscEntry, $apk)
    }
}
if ($nscTree.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($nscTree.Text)) {
    Add-Result -Id 'nsc.content' -Severity 'FAIL' -Message '无法在 APK 中定位/读取 network_security_config 资源' -Evidence (Format-EvidenceText ("尝试路径: $nscEntry; " + $nscTree.Text))
}
else {
    Add-Result -Id 'nsc.resolve' -Severity 'PASS' -Message '已定位 APK 内的 network_security_config 资源' -Evidence $nscResolveNotes
    $nscNodes = ConvertFrom-AaptXmlTree -Text $nscTree.Text
    $baseCfg = $nscNodes | Where-Object { $_.name -eq 'base-config' } | Select-Object -First 1
    $baseCleartext = Get-AttrValue -Node $baseCfg -Key 'cleartextTrafficPermitted'
    if ((Get-BoolAttr $baseCleartext) -eq $false) { Add-Result -Id 'nsc.cleartext' -Severity 'PASS' -Message 'base-config 禁止明文（cleartextTrafficPermitted=false）' }
    else { Add-Result -Id 'nsc.cleartext' -Severity 'FAIL' -Message 'base-config 允许明文或未配置' -Evidence "cleartextTrafficPermitted=$baseCleartext" }
    $certs = @($nscNodes | Where-Object { $_.name -eq 'certificates' })
    $certSrcs = @($certs | ForEach-Object { Get-AttrValue -Node $_ -Key 'src' } | Where-Object { $_ } | Select-Object -Unique)
    if (@($certSrcs | Where-Object { $_ -eq 'user' }).Count -gt 0) {
        Add-Result -Id 'nsc.trust_anchors' -Severity 'FAIL' -Message '信任用户安装的 CA（可被中间人抓包/篡改）' -Evidence ($certSrcs -join ', ')
    }
    elseif ($certSrcs -contains 'system') {
        Add-Result -Id 'nsc.trust_anchors' -Severity 'PASS' -Message "仅信任系统 CA（$($certSrcs -join ', ')）"
    }
    else { Add-Result -Id 'nsc.trust_anchors' -Severity 'WARN' -Message '未识别到 trust-anchors src=system' -Evidence ($certSrcs -join ', ') }
    $domainCfgs = @($nscNodes | Where-Object { $_.name -eq 'domain-config' })
    if ($domainCfgs.Count -gt 0) { Add-Result -Id 'nsc.domain_config' -Severity 'WARN' -Message "存在 $($domainCfgs.Count) 个 domain-config，需逐条复核是否放宽了校验" }
    else { Add-Result -Id 'nsc.domain_config' -Severity 'PASS' -Message '无 domain-config 例外' }
}

# ---------------- 7. 签名检查 ----------------
$signing = [ordered]@{
    signed         = $false
    schemes        = ''
    v1             = $false
    v2             = $false
    v3             = $false
    v4             = $false
    signerDn       = ''
    debugCert      = $false
    certSha256     = ''
    keySize        = 0
    error          = ''
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Add-Result -Id 'signing.verify' -Severity 'WARN' -Message '未找到 JDK，跳过 apksigner 校验（迁移环境限制）'
}
elseif (-not (Test-Path $apksigner)) {
    Add-Result -Id 'signing.verify' -Severity 'WARN' -Message "未找到 apksigner.bat：$apksigner"
}
else {
    $sigRes = Invoke-Exe -FilePath $apksigner -Arguments @('verify', '--verbose', '--print-certs', $apk)
    $sigText = $sigRes.Text
    if ($sigRes.ExitCode -eq 0) {
        $signing.signed = $true
        $signing.v1 = $sigText -match 'Verified using v1 scheme \(JAR signing\):\s*true'
        $signing.v2 = $sigText -match 'Verified using v2 scheme \(APK Signature Scheme v2\):\s*true'
        $signing.v3 = $sigText -match 'Verified using v3 scheme \(APK Signature Scheme v3\):\s*true'
        $signing.v4 = $sigText -match 'Verified using v4 scheme \(APK Signature Scheme v4\):\s*true'
        if ($sigText -match 'Signer #1 certificate DN: ([^\r\n]+)') { $signing.signerDn = $Matches[1].Trim() }
        if ($sigText -match 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)') { $signing.certSha256 = $Matches[1].ToLowerInvariant() }
        if ($sigText -match 'Signer #1 key size \(bits\):\s*(\d+)') { $signing.keySize = [int]$Matches[1] }
        $signing.debugCert = ($signing.signerDn -match '(?i)CN=Android Debug')
        $schemes = @()
        if ($signing.v1) { $schemes += 'v1' }
        if ($signing.v2) { $schemes += 'v2' }
        if ($signing.v3) { $schemes += 'v3' }
        if ($signing.v4) { $schemes += 'v4' }
        $signing.schemes = ($schemes -join '+')
        Add-Result -Id 'signing.verify' -Severity 'PASS' -Message "签名校验通过（$($signing.schemes)）" -Evidence "DN=$($signing.signerDn); certSha256=$($signing.certSha256); keySize=$($signing.keySize)"
        if (-not $signing.v2 -and -not $signing.v3) {
            Add-Result -Id 'signing.scheme2plus' -Severity 'FAIL' -Message '缺少 v2/v3 签名方案（Android 7+ 要求 v2+，且 v1 可被篡改）'
        }
        elseif ($signing.v2) { Add-Result -Id 'signing.scheme2plus' -Severity 'PASS' -Message "使用 v2/v3 签名方案（$($signing.schemes)）" }
        if ($signing.v1) {
            Add-Result -Id 'signing.v1' -Severity 'WARN' -Message '仍启用 v1（JAR）签名；release 交付建议 v2+v3（minSdk 26 不需要 v1）'
        }
        else {
            Add-Result -Id 'signing.v1' -Severity 'PASS' -Message '未启用 v1（JAR）签名，符合 minSdk 26 的 v2+ 要求'
        }
        if ($signing.keySize -ge 2048) {
            Add-Result -Id 'signing.key_size' -Severity 'PASS' -Message "签名密钥长度 $($signing.keySize) bit（RSA >=2048 合规）" -Evidence "DN=$($signing.signerDn)"
        }
        elseif ($signing.keySize -gt 0) {
            Add-Result -Id 'signing.key_size' -Severity 'FAIL' -Message "签名密钥长度 $($signing.keySize) bit < 2048，不满足安全要求"
        }
        else {
            Add-Result -Id 'signing.key_size' -Severity 'INFO' -Message '未从 apksigner 输出解析到密钥长度（部分版本不输出），需人工确认'
        }
        if ($signing.debugCert) {
            $sev = switch ($SigningExpectation) {
                'unsigned' { 'INFO' }
                'test' { 'WARN' }
                default { 'WARN' }
            }
            Add-Result -Id 'signing.debug_cert' -Severity $sev -Message '使用 Android Debug 测试证书签名（公钥公开，禁止作为正式发布签名）' -Evidence "DN=$($signing.signerDn)"
        }
        else {
            Add-Result -Id 'signing.debug_cert' -Severity 'PASS' -Message "非 debug 证书签名（DN=$($signing.signerDn)）"
        }
        $signingState = if ($signing.debugCert) { 'test-signed (debug key)' } else { 'release-signed' }
    }
    else {
        $signing.error = Format-EvidenceText $sigText 400
        if ($SigningExpectation -eq 'unsigned' -or $sigText -match '(?i)does not contain a signature|Missing signature|not signed') {
            $signingState = 'UNSIGNED'
            $sev = if ($SigningExpectation -eq 'release') { 'FAIL' } else { 'WARN' }
            $unsignedNote = if ($SigningExpectation -ne 'release') {
                '按当前交付策略（本机无法生成签名密钥）判为预期，但发布前必须由用户自行生成 keystore 并重签。'
            }
            else { 'release 交付必须签名。' }
            Add-Result -Id 'signing.verify' -Severity $sev -Message "APK 未签名（UNSIGNED）。$unsignedNote" -Evidence $signing.error
            Add-Result -Id 'signing.scheme2plus' -Severity $(if ($SigningExpectation -eq 'release') { 'FAIL' } else { 'WARN' }) -Message '未签名，无法验证 v2/v3 签名方案'
        }
        else {
            $signingState = 'INVALID'
            Add-Result -Id 'signing.verify' -Severity 'FAIL' -Message 'apksigner 校验失败（签名损坏或格式异常）' -Evidence $signing.error
            Add-Result -Id 'signing.scheme2plus' -Severity 'FAIL' -Message '签名无效'
        }
    }
}

# ---------------- 8. 解包扫描（dex / 资源 / 原生库） ----------------
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $entryNames = @($zip.Entries | ForEach-Object { $_.FullName })
    $dexEntries = @($zip.Entries | Where-Object { $_.FullName -match '^classes[0-9]*\.dex$' })
    $nativeLibs = @($entryNames | Where-Object { $_ -match '^lib/.+\.so$' })
    if ($dexEntries.Count -eq 0) { Add-Result -Id 'apk.dex' -Severity 'FAIL' -Message 'APK 内未找到 classes*.dex' }
    else {
        $dexSizes = @($dexEntries | ForEach-Object { $_.Length })
        Add-Result -Id 'apk.dex' -Severity 'PASS' -Message "包含 $($dexEntries.Count) 个 dex（$([math]::Round(($dexSizes | Measure-Object -Sum).Sum / 1MB, 2)) MiB）"
    }
    if ($nativeLibs.Count -gt 0) {
        Add-Result -Id 'apk.native_libs' -Severity 'INFO' -Message "包含原生库（来自 AndroidX/Compose，非自研 JNI）：$($nativeLibs -join ', ')" -Evidence "共 $($nativeLibs.Count) 个"
    }
    else { Add-Result -Id 'apk.native_libs' -Severity 'PASS' -Message '无原生 .so 库' }

    # --- dex 二进制字符串扫描 ---
    $dexText = [System.Text.StringBuilder]::new()
    # 中文/UTF-8 文案单独用 UTF-8 解码（dex 字符串是 MUTF-8；Latin1 解码无法匹配中文）
    $dexUtf8Text = [System.Text.StringBuilder]::new()
    $dexBytesTotal = 0
    foreach ($de in $dexEntries) {
        $st = $de.Open()
        try { $bytes = Read-AllBytes -Stream $st } finally { $st.Dispose() }
        $dexBytesTotal += $bytes.Length
        [void]$dexText.Append([System.Text.Encoding]::Latin1.GetString($bytes))
        [void]$dexText.Append("`n")
        [void]$dexUtf8Text.Append([System.Text.Encoding]::UTF8.GetString($bytes))
        [void]$dexUtf8Text.Append("`n")
    }
    $dex = $dexText.ToString()
    $dexUtf8 = $dexUtf8Text.ToString()

    function Find-Token {
        param([string]$Text, [string]$Token)
        $idx = $Text.IndexOf($Token, [System.StringComparison]::Ordinal)
        if ($idx -lt 0) { return $null }
        $start = [math]::Max(0, $idx - 60)
        $len = [math]::Min(160, $Text.Length - $start)
        return (Format-EvidenceText $Text.Substring($start, $len) 200)
    }

    $bannedApis = [ordered]@{
        'WebView 组件'                 = @('Landroid/webkit/WebView;')
        'JS 桥 addJavascriptInterface' = @('Landroid/webkit/WebView;->addJavascriptInterface', 'addJavascriptInterface')
        'WebView setJavaScriptEnabled' = @('->setJavaScriptEnabled')
        'WebView setAllowFileAccess'   = @('Landroid/webkit/WebSettings;->setAllowFileAccess', 'Landroid/webkit/WebSettings;->setAllowContentAccess')
        'WebView 调试'                 = @('WebView;->setWebContentsDebuggingEnabled')
        '动态加载 DexClassLoader'      = @('Ldalvik/system/DexClassLoader;', 'Ldalvik/system/InMemoryDexClassLoader;', 'Ldalvik/system/BaseDexClassLoader;')
        '动态加载 PathClassLoader'     = @('Ldalvik/system/PathClassLoader;')
        '进程执行 Runtime.exec'        = @('Ljava/lang/Runtime;->exec')
        '进程执行 ProcessBuilder'      = @('Ljava/lang/ProcessBuilder;')
        '原生库加载 System.load'       = @('Ljava/lang/System;->loadLibrary', 'Ljava/lang/System;->load(')
        '设备标识 getDeviceId'         = @('Landroid/telephony/TelephonyManager;->getDeviceId', 'Landroid/telephony/TelephonyManager;->getImei', 'Landroid/telephony/TelephonyManager;->getMeid', 'Landroid/telephony/TelephonyManager;->getSubscriberId')
        '短信 SmsManager'              = @('Landroid/telephony/SmsManager;', 'Landroid/telephony/SmsManager;->sendTextMessage')
        '悬浮窗'                       = @('SYSTEM_ALERT_WINDOW', 'Landroid/view/WindowManager$LayoutParams;->TYPE_APPLICATION_OVERLAY')
        '无障碍服务'                   = @('Landroid/accessibilityservice/AccessibilityService;', 'BIND_ACCESSIBILITY_SERVICE')
        '通知监听'                     = @('Landroid/service/notification/NotificationListenerService;', 'BIND_NOTIFICATION_LISTENER_SERVICE')
        '设备管理器'                   = @('Landroid/app/admin/DeviceAdminReceiver;', 'BIND_DEVICE_ADMIN')
        '开机自启'                     = @('RECEIVE_BOOT_COMPLETED', 'android.intent.action.BOOT_COMPLETED')
        '屏幕录制'                     = @('Landroid/media/projection/MediaProjection;', 'createScreenCaptureIntent')
    }
    foreach ($label in $bannedApis.Keys) {
        $tokens = $bannedApis[$label]
        $hits = @()
        foreach ($t in $tokens) {
            if ($dex.Contains($t)) { $hits += $t }
        }
        if ($hits.Count -gt 0) {
            $ev = Find-Token -Text $dex -Token $hits[0]
            $sev = if ($BuildKind -eq 'debug') { 'WARN' } else { 'FAIL' }
            $note = if ($BuildKind -eq 'debug') { '（debug 构建可能含调试工具链，需对 release APK 复核）' } else { '' }
            Add-Result -Id "dex.banned.$label" -Severity $sev -Message "dex 中出现被禁 API/字符串：$label$note" -Evidence "命中=$($hits -join ','); 上下文=$ev"
        }
    }
    $bannedLabels = @($bannedApis.Keys | ForEach-Object { "dex.banned.$_" })
    $fired = @($script:Results | Where-Object { $bannedLabels -contains $_.id })
    if ($fired.Count -eq 0) { Add-Result -Id 'dex.banned' -Severity 'PASS' -Message 'dex 中未发现 WebView/JS 桥/动态加载/exec/设备标识/短信/悬浮窗/无障碍/开机自启等禁用 API' }

    # 反射：仅允许库内部平台探测（OkHttp Conscrypt/BouncyCastle 探测），其余需人工确认
    $reflectionHits = @()
    foreach ($t in @('Ljava/lang/reflect/Method;', 'Ljava/lang/reflect/Field;', 'Class.forName', 'getDeclaredMethod', 'getDeclaredField', 'setAccessible')) {
        if ($dex.Contains($t)) { $reflectionHits += $t }
    }
    if ($reflectionHits.Count -gt 0) {
        Add-Result -Id 'dex.reflection' -Severity 'WARN' -Message 'dex 中存在反射 API（需确认仅为依赖库的平台探测，非动态代码加载）' -Evidence (($reflectionHits | Select-Object -Unique) -join ', ')
    }
    else { Add-Result -Id 'dex.reflection' -Severity 'PASS' -Message 'dex 中无反射 API 痕迹' }

    # 电池优化白名单：REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 属商店政策敏感，需说明
    if ($dex.Contains('REQUEST_IGNORE_BATTERY_OPTIMIZATIONS') -or $dex.Contains('ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS')) {
        Add-Result -Id 'dex.battery_whitelist' -Severity 'WARN' -Message '代码中出现直接请求加入电池优化白名单的 API（Google Play 政策需说明；建议改为跳转系统设置页由用户操作）'
    }
    else { Add-Result -Id 'dex.battery_whitelist' -Severity 'PASS' -Message '未直接请求电池优化白名单（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）' }

    # 剪贴板（桌面版“复制链接”已移除；移动端不应读取剪贴板）
    if ($dex.Contains('Landroid/content/ClipboardManager;') -or $dex.Contains('Landroid/content/ClipboardManager;->getPrimaryClip')) {
        Add-Result -Id 'dex.clipboard' -Severity 'WARN' -Message 'dex 中出现 ClipboardManager（若用于“粘贴链接”需确认仅用户主动操作时读取，不做后台读取）'
    }
    else { Add-Result -Id 'dex.clipboard' -Severity 'PASS' -Message '无剪贴板访问痕迹' }

    # 明文 http URL 扫描
    $httpMatches = [regex]::Matches($dex, 'http://[A-Za-z0-9._~:/?#\[\]@!$&''()*+,;=%-]{4,200}')
    $httpHosts = @()
    $httpSamples = @()
    foreach ($m in $httpMatches) {
        $u = $m.Value
        $h = ''
        if ($u -match '^http://([^/?#:]+)') { $h = $Matches[1].ToLowerInvariant() }
        if ($h) { $httpHosts += $h }
        if ($httpSamples.Count -lt 5) { $httpSamples += $u }
    }
    $httpHosts = @($httpHosts | Select-Object -Unique)
    $badHttpHosts = @($httpHosts | Where-Object { $script:BenignHttpHosts -notcontains $_ })
    if ($badHttpHosts.Count -gt 0) {
        Add-Result -Id 'dex.cleartext_url' -Severity 'FAIL' -Message 'dex 中包含非命名空间用途的明文 http:// URL（应全部为 https）' -Evidence "hosts=$($badHttpHosts -join ', '); samples=$($httpSamples -join ' | ')"
    }
    elseif ($httpHosts.Count -gt 0) {
        Add-Result -Id 'dex.cleartext_url' -Severity 'PASS' -Message 'dex 中的 http:// 仅为 XML 命名空间等文档 URI（非网络请求）' -Evidence "hosts=$($httpHosts -join ', ')"
    }
    else { Add-Result -Id 'dex.cleartext_url' -Severity 'PASS' -Message 'dex 中无 http:// 明文 URL' }

    # 硬编码密钥（dex + 资源/配置文本）
    $secretPatterns = [ordered]@{
        'AWS Access Key'      = 'AKIA[0-9A-Z]{16}'
        'Private Key'         = '-----BEGIN [A-Z ]*PRIVATE KEY-----'
        'Google API Key'      = 'AIza[0-9A-Za-z_\-]{35}'
        'GitHub Token'        = 'ghp_[A-Za-z0-9]{36}'
        'Slack Token'         = 'xox[baprs]-[A-Za-z0-9-]{10,}'
        'OpenAI Key'          = 'sk-[A-Za-z0-9]{20,}'
        '硬编码口令赋值'      = '(?i)(password|passwd|pwd|secret|token|api[_-]?key)\s*[=:]\s*["''][^"'']{6,}["'']'
    }
    $secretHits = @()
    $scanTexts = [System.Collections.Generic.List[object]]::new()
    $scanTexts.Add([pscustomobject]@{ Name = 'classes*.dex'; Text = $dex })
    $entryBudget = 64MB
    $entryBytes = 0
    foreach ($e in $zip.Entries) {
        $n = $e.FullName
        if ($n -match '^classes[0-9]*\.dex$') { continue }
        if ($n -match '^META-INF/.*\.(RSA|DSA|EC|SF|MF)$') { continue }
        if ($e.Length -gt 4MB) { continue }
        if ($entryBytes -ge $entryBudget) { break }
        if ($n -match '\.(so|png|jpg|jpeg|webp|wav|mp3|ttf|otf|bin|flat|zip)$') { continue }
        try {
            $st = $e.Open()
            try { $bytes = Read-AllBytes -Stream $st } finally { $st.Dispose() }
            $entryBytes += $bytes.Length
            $scanTexts.Add([pscustomobject]@{ Name = $n; Text = [System.Text.Encoding]::Latin1.GetString($bytes) })
        }
        catch { }
    }
    foreach ($item in $scanTexts) {
        foreach ($label in $secretPatterns.Keys) {
            $ms = [regex]::Matches($item.Text, $secretPatterns[$label])
            if ($ms.Count -gt 0) {
                $sev = if ($item.Name -eq 'classes*.dex') { 'FAIL' } else { 'WARN' }
                $sample = Format-EvidenceText $ms[0].Value 80
                if ($label -eq '硬编码口令赋值') { $sample = ($ms[0].Value -replace '["''][^"'']*["'']', '"<redacted>"') }
                $secretHits += [pscustomobject]@{ label = $label; where = $item.Name; severity = $sev; sample = $sample }
                break
            }
        }
    }
    if ($secretHits.Count -gt 0) {
        $worst = if (@($secretHits | Where-Object severity -eq 'FAIL').Count -gt 0) { 'FAIL' } else { 'WARN' }
        Add-Result -Id 'apk.hardcoded_secrets' -Severity $worst -Message '疑似硬编码密钥/口令' -Evidence (($secretHits | ForEach-Object { "$($_.label)@$($_.where):$($_.sample)" }) -join ' ; ')
    }
    else { Add-Result -Id 'apk.hardcoded_secrets' -Severity 'PASS' -Message '未发现硬编码密钥/口令' }

    # 第三方统计/广告/崩溃 SDK 痕迹
    $trackerTokens = @('com.google.firebase', 'com.google.android.gms.analytics', 'com.google.android.gms.ads',
        'com.umeng', 'com.tencent.bugly', 'com.tencent.stat', 'com.facebook.appevents', 'com.facebook.ads',
        'com.appsflyer', 'io.branch', 'com.adjust.sdk', 'com.flurry', 'com.crashlytics', 'io.sentry',
        'com.amplitude', 'com.mixpanel', 'com.bytedance.applog', 'com.igexin', 'com.xiaomi.mipush',
        'com.huawei.hms.analytics', 'com.qq.e', 'com.baidu.mobstat', 'com.google.android.play.core')
    $trackerHits = @($trackerTokens | Where-Object { $dex.Contains($_) })
    if ($trackerHits.Count -gt 0) {
        Add-Result -Id 'apk.thirdparty_tracker' -Severity 'FAIL' -Message 'dex 中出现第三方统计/广告/推送 SDK 包名（与“无第三方上报”承诺不符）' -Evidence ($trackerHits -join ', ')
    }
    else { Add-Result -Id 'apk.thirdparty_tracker' -Severity 'PASS' -Message '未发现第三方统计/广告/推送 SDK 痕迹' }

    # ---- v1.1.0/1.1.1：外部跳转（进入直播间；v1.1.1 起已下架“下载 App”）/ 自定义提示音 / 通知渠道 v2 ----
    $hasActionView = $dex.Contains('android.intent.action.VIEW')
    $hasSetPackage = $dex.Contains('setPackage')
    $hasMarketUri = $dex.Contains('market://details?id=')
    if ($hasActionView -and $hasSetPackage -and -not $hasMarketUri) {
        Add-Result -Id 'dex.external_intent' -Severity 'PASS' -Message 'dex 包含 ACTION_VIEW + setPackage（进入直播间/浏览器跳转已编译，平台 App 跳转经 setPackage）'
    }
    elseif ($hasActionView -and $hasSetPackage -and $hasMarketUri) {
        Add-Result -Id 'dex.external_intent' -Severity 'WARN' -Message 'dex 含 market:// 常量（v1.1.1 已下架“下载 App”，应确认无下载/市场跳转残留）'
    }
    elseif ($hasActionView) {
        Add-Result -Id 'dex.external_intent' -Severity 'WARN' -Message 'dex 仅见 ACTION_VIEW，未找到 setPackage（需人工确认外部跳转是否显式目标）'
    }
    else {
        Add-Result -Id 'dex.external_intent' -Severity 'WARN' -Message 'dex 未发现 ACTION_VIEW（该版本可能未包含“进入直播间”外部跳转）'
    }

    # v1.1.1 已下架“下载 App”：dex 不应再包含市场/应用商店列表下载特征
    $downloadMarkers = @()
    foreach ($marker in @('market://details?id=', 'play.google.com/store/apps/details', 'com.android.vending', 'knownMarketPackages')) {
        if ($dex.Contains($marker)) { $downloadMarkers += $marker }
    }
    if ($downloadMarkers.Count -gt 0) {
        Add-Result -Id 'apk.no_market_download' -Severity 'WARN' -Message 'dex 仍含应用市场/下载相关特征（v1.1.1 已下架“下载 App”，应确认无残留）' -Evidence ($downloadMarkers -join ', ')
    }
    else {
        Add-Result -Id 'apk.no_market_download' -Severity 'PASS' -Message 'dex 无应用市场/商店下载特征（v1.1.1 已下架“下载 App”功能）'
    }

    $hasCustomSoundMarker = $dex.Contains('custom_alert') -or $dex.Contains('sounds')
    $hasMediaSource = $dex.Contains('setDataSource')
    if ($hasCustomSoundMarker -and $hasMediaSource) {
        Add-Result -Id 'dex.custom_sound' -Severity 'PASS' -Message 'dex 含自定义提示音固定文件名/目录与 MediaPlayer.setDataSource（自定义音已编译）'
    }
    elseif ($hasCustomSoundMarker -or $hasMediaSource) {
        Add-Result -Id 'dex.custom_sound' -Severity 'WARN' -Message 'dex 仅部分命中自定义提示音特征（custom_alert/sounds/setDataSource），需人工确认'
    }
    else {
        Add-Result -Id 'dex.custom_sound' -Severity 'WARN' -Message 'dex 未发现自定义提示音特征（该版本可能未包含该功能）'
    }

    if ($dexUtf8.Contains('live_alerts_v2') -or $dex.Contains('live_alerts_v2')) {
        Add-Result -Id 'dex.notification_channel' -Severity 'PASS' -Message 'dex 含 live_alerts_v2（通知渠道迁移已编译）'
    }
    else {
        Add-Result -Id 'dex.notification_channel' -Severity 'WARN' -Message 'dex 未发现 live_alerts_v2（v1.1.0 计划迁移通知渠道，需人工确认）'
    }

    if ($dex.Contains('RoomJumpActivity') -or $dex.Contains('com/livemonitor/app/ui/RoomJumpActivity')) {
        Add-Result -Id 'dex.jump_activity' -Severity 'PASS' -Message 'dex 含 RoomJumpActivity 类名（通知/UI 以显式 Intent 启动本 App 跳转 Activity）'
    }
    else {
        Add-Result -Id 'dex.jump_activity' -Severity 'WARN' -Message 'dex 未找到 RoomJumpActivity 类名（可能被 R8 重命名/未包含，需人工确认跳转入口）'
    }

    if ($dex.Contains('QUERY_ALL_PACKAGES')) {
        Add-Result -Id 'dex.query_all_packages' -Severity 'FAIL' -Message 'dex 中出现 QUERY_ALL_PACKAGES 字符串（与最小包可见性设计冲突）'
    }
    else {
        Add-Result -Id 'dex.query_all_packages' -Severity 'PASS' -Message 'dex 中无 QUERY_ALL_PACKAGES 痕迹'
    }

    # TLS 证书固定（TlsPolicy）：OkHttp CertificatePinner 会把 pin 以 "sha256/<base64>" 常量编译进 dex；
    # R8 混淆后类名可能不可见，因此以 pin 字符串数量 + 固定失败提示文案作为证据。
    $pinStrings = @([regex]::Matches($dex, 'sha256/[A-Za-z0-9+/]{43}=') | ForEach-Object { $_.Value } | Select-Object -Unique)
    $hasPinFailureText = $dexUtf8.Contains('证书固定校验失败') -or $dexUtf8.Contains('中间人劫持')
    if ($pinStrings.Count -ge 4 -and $hasPinFailureText) {
        Add-Result -Id 'apk.tls_pinning' -Severity 'PASS' -Message "dex 包含 $($pinStrings.Count) 个 sha256 证书 pin 与固定失败提示文案（CertificatePinning 已编译进包）" -Evidence (($pinStrings | Select-Object -First 3) -join ', ')
    }
    elseif ($pinStrings.Count -ge 4) {
        Add-Result -Id 'apk.tls_pinning' -Severity 'WARN' -Message "dex 包含 $($pinStrings.Count) 个 sha256 pin，但未找到固定失败提示文案（需人工确认异常分类）"
    }
    else {
        Add-Result -Id 'apk.tls_pinning' -Severity 'WARN' -Message 'dex 中未发现证书 pin 字符串（未启用或已被优化），需对照源码 TlsPolicy 人工确认'
    }

    # 权威域名/预期组件存在性
    $expectedTokens = @('com.livemonitor.app', 'okhttp3', 'www.douyu.com', 'www.huya.com', 'live.bilibili.com', 'live.douyin.com', 'live.kuaishou.com')
    $missingTokens = @($expectedTokens | Where-Object { -not $dex.Contains($_) })
    if ($missingTokens.Count -gt 0) {
        Add-Result -Id 'apk.expected_tokens' -Severity 'WARN' -Message 'dex 中未找到预期的平台域名/包名（可能被 R8 字符串合并或实现变更，需人工确认）' -Evidence ($missingTokens -join ', ')
    }
    else { Add-Result -Id 'apk.expected_tokens' -Severity 'PASS' -Message '5 平台域名与包名均在 dex 中出现' }
}
finally {
    $zip.Dispose()
}

# ---------------- 9. zipalign（信息项） ----------------
if (Test-Path $zipalign) {
    $align = Invoke-Exe -FilePath $zipalign -Arguments @('-c', '4', $apk)
    if ($align.ExitCode -eq 0) { Add-Result -Id 'apk.zipalign' -Severity 'PASS' -Message 'zipalign 校验通过（4 字节对齐）' }
    else { Add-Result -Id 'apk.zipalign' -Severity 'WARN' -Message 'zipalign 校验未通过（可能影响加载性能/16KB 页设备）' -Evidence (Format-EvidenceText $align.Text 300) }
}

# ---------------- 10. 汇总 ----------------
$deliverability = if ($signingState -eq 'UNSIGNED') {
    'unsigned-release：已完成静态加固核查；发布前必须由用户自行生成 keystore 签名（见 android/README.md 与 android/scripts/sign-release.ps1）'
}
elseif ($signingState -like 'test-signed*') {
    'test-signed-debug-key：仅供本机安装验证，禁止对外分发；正式发布需用户自有 keystore 重签'
}
elseif ($signingState -eq 'release-signed') { 'release-signed：签名可用' }
else { $signingState }

$meta = [ordered]@{
    Apk = [ordered]@{
        path          = $apk
        sha256        = $sha256
        sizeBytes     = $apkItem.Length
        lastWriteTime = $apkItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
        buildKind     = $BuildKind
        signingState  = $signingState
        signing       = $signing
    }
    Environment = [ordered]@{
        sdkRoot          = $sdk
        buildToolsVersion = $btVersion
        buildToolsDir    = $btDir
        aapt2            = $aapt2
        apksigner        = $apksigner
        javaHome         = $env:JAVA_HOME
    }
    Deliverability = $deliverability
    Notes          = @(
        '本报告为 APK 静态检查：未安装、未运行、未抓包，不覆盖运行时行为与真实网络请求。'
        'dex 字符串扫描是启发式证据，仅说明常量/API 引用存在，不代表运行路径可达。'
        'v1.1.0 起额外检查：<queries> 包可见性、RoomJumpActivity 导出面、深链接、ACTION_VIEW/setPackage、自定义提示音特征、通知渠道 v2、APK 版本号。'
        'v1.1.1 起：queries 仅允许 VIEW+https（浏览器解析）；dex 不得含 market:///商店下载等“下载 App”残留（已下架该功能）。'
        'release 包启用 R8 + shrinkResources 后资源路径会被缩短（如 res/xml/network_security_config.xml → res/8G.xml），脚本通过 Manifest 资源 ID 映射定位 NSC，属正常构建行为。'
        '签名缺失/使用 debug 证书是本机密钥生成受限导致的交付状态，已在“交付签名说明”中如实记录。'
        '最终发布前建议在可联网真机上复测：通知展示/锁屏可见性、后台存活、进入直播间（平台 App/浏览器）、自定义提示音播放、自动打开限制、流量仅 5 平台 HTTPS。'
    )
}

$report = Write-Reports -Meta $meta -OutDir $(if ($OutDir) { $OutDir } else { Join-Path $PSScriptRoot 'reports' }) -ToolName 'apk-hardening'

Write-Host ''
Write-Host '== 检查结果 ==' -ForegroundColor Cyan
foreach ($r in $script:Results) {
    $color = switch ($r.severity) {
        'FAIL' { 'Red' }
        'WARN' { 'Yellow' }
        'PASS' { 'Green' }
        default { 'Gray' }
    }
    Write-Host ("[{0,-4}] {1,-38} {2}" -f $r.severity, $r.id, $r.message) -ForegroundColor $color
}
Write-Host ''
$passCount = @($script:Results | Where-Object { $_.severity -eq 'PASS' }).Count
$warnCount = @($script:Results | Where-Object { $_.severity -eq 'WARN' }).Count
$failCount = @($script:Results | Where-Object { $_.severity -eq 'FAIL' }).Count
Write-Host ("合计: {0} 项 | PASS {1} | WARN {2} | FAIL {3}" -f $script:Results.Count, $passCount, $warnCount, $failCount) -ForegroundColor Cyan
Write-Host "JSON 报告: $($report.JsonPath)"
Write-Host "MD   报告: $($report.MdPath)"
Write-Host "SHA-256  : $sha256"
Write-Host "签名状态 : $signingState"
Write-Host "交付说明 : $deliverability"
Write-Host ''

if ($SelfTest) {
    # 断言：脚本能在 debug APK 上识别出 debug 构建、testOnly、debug 证书等预期项
    $ok = $true
    $expect = @(
        @{ id = 'app.debuggable'; sev = 'WARN' }
        @{ id = 'signing.debug_cert'; sev = 'WARN' }
        @{ id = 'permissions.allowlist'; sev = 'PASS' }
        @{ id = 'components.main_activity'; sev = 'PASS' }
        @{ id = 'fgs.type'; sev = 'PASS' }
        @{ id = 'app.allowBackup'; sev = 'PASS' }
        @{ id = 'app.nsc_attr'; sev = 'PASS' }
    )
    foreach ($e in $expect) {
        $hit = $script:Results | Where-Object { $_.id -eq $e.id -and $_.severity -eq $e.sev } | Select-Object -First 1
        if (-not $hit) {
            Write-Host "[SelfTest] 期望未命中: $($e.id) 应为 $($e.sev)" -ForegroundColor Red
            $ok = $false
        }
    }
    if (-not ($script:Results | Where-Object { $_.id -eq 'manifest.package' -and $_.severity -in @('PASS', 'WARN') })) {
        Write-Host '[SelfTest] 包名检查未按 debug 后缀处理' -ForegroundColor Red
        $ok = $false
    }
    Write-Host ''
    if ($ok) {
        Write-Host '[SelfTest] OK：debug 构建/证书/权限/包名规则均按预期触发。' -ForegroundColor Green
        exit 0
    }
    else {
        Write-Host '[SelfTest] FAILED：脚本规则自检未通过。' -ForegroundColor Red
        exit 1
    }
}

exit $report.ExitCode
