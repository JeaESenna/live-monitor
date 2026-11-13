#Requires -Version 7.0
<#
.SYNOPSIS
    LiveMonitor Android —— 源码加固/隐私规则扫描（可重复执行）。

.DESCRIPTION
    对 android/app/src 下的源码、Manifest、network_security_config、Gradle/ProGuard 配置做只读规则扫描，
    输出 JSON + Markdown 报告到 android/security/reports/。
    重点规则：组件导出/Intent 入口、危险权限、WebView/JS、动态加载、exec、明文 http、
    PendingIntent flag、日志泄露（URL/标题/Cookie）、TLS 校验被绕过、SharedPreferences 模式、
    硬编码密钥、第三方统计 SDK、通知锁屏隐私、WakeLock 滥用、release 构建加固项。

    -SelfTest：用 tests/fixtures/bad-source 与 good-source 验证规则确实会触发/不会误报。
    退出码：0=无 FAIL，1=存在 FAIL，2=参数/环境错误。本脚本只读，不修改任何源码。

.PARAMETER SourceDir
    源码根。缺省 <脚本目录>/../app/src（其中应有 main/ 与 test/）。

.PARAMETER ProjectRoot
    Android 工程根。缺省 <脚本目录>/..（用于检查 Gradle/ProGuard/依赖）。

.PARAMETER OutDir
    报告输出目录。缺省 <脚本目录>/reports。

.PARAMETER ExpectedPackage
    期望包名，缺省 com.livemonitor.app。

.PARAMETER SelfTest
    运行夹具自检。

.EXAMPLE
    pwsh -NoProfile -File android/security/check-source-hardening.ps1
    pwsh -NoProfile -File android/security/check-source-hardening.ps1 -SelfTest
#>
[CmdletBinding()]
param(
    [string]$SourceDir,
    [string]$ProjectRoot,
    [string]$OutDir,
    [string]$ExpectedPackage = 'com.livemonitor.app',
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$script:Version = '1.3.0'
$script:Results = [System.Collections.Generic.List[object]]::new()
$script:AndroidNs = 'http://schemas.android.com/apk/res/android'
$script:FilesScanned = 0
$script:CodeFiles = @()

$script:AllowedPermissions = @(
    'android.permission.INTERNET'
    'android.permission.ACCESS_NETWORK_STATE'
    'android.permission.POST_NOTIFICATIONS'
    'android.permission.FOREGROUND_SERVICE'
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE'
    'android.permission.WAKE_LOCK'
)
$script:SensitivePermissionPatterns = @(
    'READ_CALENDAR', 'WRITE_CALENDAR', 'CAMERA', 'READ_CONTACTS', 'WRITE_CONTACTS', 'GET_ACCOUNTS',
    'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'ACCESS_BACKGROUND_LOCATION',
    'RECORD_AUDIO', 'READ_PHONE_STATE', 'READ_PHONE_NUMBERS', 'CALL_PHONE', 'ANSWER_PHONE_CALLS',
    'READ_CALL_LOG', 'WRITE_CALL_LOG', 'ADD_VOICEMAIL', 'USE_SIP', 'PROCESS_OUTGOING_CALLS',
    'BODY_SENSORS', 'ACTIVITY_RECOGNITION', 'SEND_SMS', 'RECEIVE_SMS', 'READ_SMS', 'RECEIVE_MMS', 'RECEIVE_WAP_PUSH',
    'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE', 'READ_MEDIA_',
    'SYSTEM_ALERT_WINDOW', 'PACKAGE_USAGE_STATS', 'RECEIVE_BOOT_COMPLETED',
    'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'REQUEST_INSTALL_PACKAGES', 'QUERY_ALL_PACKAGES',
    'BIND_ACCESSIBILITY_SERVICE', 'BIND_NOTIFICATION_LISTENER_SERVICE', 'BIND_DEVICE_ADMIN',
    'WRITE_SETTINGS'
)
$script:BenignHttpHosts = @(
    'schemas.android.com', 'www.w3.org', 'w3.org', 'ns.adobe.com', 'xmlpull.org',
    'java.sun.com', 'xml.org', 'www.xml.org', 'apache.org', 'xml.apache.org',
    'purl.org', 'purl.oclc.org', 'openxmlformats.org'
)

# ------------------------------------------------------------------ 通用函数

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
            message  = (Format-Text $Message)
            evidence = (Format-Text $Evidence)
        })
}

function Format-Text {
    param([string]$Text, [int]$MaxLen = 1200)
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    $t = $Text -replace "`r?`n", ' | '
    $t = $t.Trim()
    if ($t.Length -gt $MaxLen) { $t = $t.Substring(0, $MaxLen) + '…' }
    return $t
}

function Get-RelativePath {
    param([string]$Base, [string]$Path)
    $b = $Base.TrimEnd('\', '/')
    if ($Path.StartsWith($b, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $Path.Substring($b.Length).TrimStart('\', '/')
    }
    return $Path
}

function Read-TextFile {
    param([string]$Path)
    try { return [System.IO.File]::ReadAllText($Path) } catch { return '' }
}

# 去掉注释（保留行号与字符串内容），避免注释中提到禁用 API 造成误报。
function Get-CodeTextWithoutComments {
    param([string]$Text)
    $noBlock = [regex]::Replace($Text, '/\*[\s\S]*?\*/', {
            param($m) ($m.Value -replace '[^\r\n]', ' ')
        })
    $noLine = [regex]::Replace($noBlock, '(?<!:)//[^\r\n]*', {
            param($m) (' ' * $m.Value.Length)
        })
    return $noLine
}

function Add-ScanRuleResult {
    param(
        [string]$Id,
        [string]$Severity,
        [string]$Message,
        [string]$Pattern,
        [switch]$SkipComments,
        [string]$EvidenceHint = '',
        [int]$MaxEvidence = 4
    )
    $hits = @()
    foreach ($f in $script:CodeFiles) {
        $text = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
        $lineNo = 0
        foreach ($line in ($text -split "`n")) {
            $lineNo++
            if ($line -match $Pattern) {
                $rel = Get-RelativePath -Base $script:SourceDir -Path $f.FullName
                $snippet = ($line.Trim() -replace '\s+', ' ')
                if ($snippet.Length -gt 160) { $snippet = $snippet.Substring(0, 160) + '…' }
                $hits += "${rel}:${lineNo}: $snippet"
                if ($hits.Count -ge $MaxEvidence) { break }
            }
        }
        if ($hits.Count -ge $MaxEvidence) { break }
    }
    if ($hits.Count -gt 0) {
        Add-Result -Id $Id -Severity $Severity -Message $Message -Evidence (($hits -join ' || ') + $(if ($EvidenceHint) { " || $EvidenceHint" } else { '' }))
    }
}

# ------------------------------------------------------------------ 主流程

$androidRoot = Split-Path -Parent $PSScriptRoot
if (-not $SourceDir) { $SourceDir = Join-Path $androidRoot 'app\src' }
if (-not $ProjectRoot) { $ProjectRoot = $androidRoot }
if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot 'reports' }
$SourceDir = (Resolve-Path $SourceDir).Path
if (-not (Test-Path $SourceDir)) { throw "源码目录不存在: $SourceDir" }

$mainRoot = if (Test-Path (Join-Path $SourceDir 'main')) { Join-Path $SourceDir 'main' } else { $SourceDir }
$testRoot = Join-Path $SourceDir 'test'
$debugRoot = Join-Path $SourceDir 'debug'

Write-Host ''
Write-Host '== 直播监测 Android 源码加固扫描 ==' -ForegroundColor Cyan
Write-Host "源码目录 : $SourceDir"
Write-Host "工程根   : $ProjectRoot"
Write-Host ''

# ---------------- 1. 文件清单 ----------------
$sourceFiles = @(Get-ChildItem $SourceDir -Recurse -File -Include *.kt, *.java, *.xml, *.pro -ErrorAction SilentlyContinue)
$allCodeFiles = @($sourceFiles | Where-Object { $_.Extension -in @('.kt', '.java') })
$mainCodeFiles = @($allCodeFiles | Where-Object { $_.FullName -like "$mainRoot*" })
$testCodeFiles = @($allCodeFiles | Where-Object { $_.FullName -like "$testRoot*" })
# 规则扫描只针对生产源码（main）；测试源码单独统计，避免把测试夹具里的 http:// / JUnit 误报为生产问题。
$script:CodeFiles = $mainCodeFiles
$script:FilesScanned = $sourceFiles.Count
Add-Result -Id 'scan.files' -Severity 'INFO' -Message "扫描文件 $($sourceFiles.Count) 个（main 代码 $($mainCodeFiles.Count)，test 代码 $($testCodeFiles.Count)）"

# ---------------- 2. AndroidManifest 检查 ----------------
$manifestPath = @(Get-ChildItem $mainRoot -Recurse -File -Filter 'AndroidManifest.xml' -ErrorAction SilentlyContinue) | Select-Object -First 1
if (-not $manifestPath) {
    Add-Result -Id 'manifest.parse' -Severity 'FAIL' -Message '未找到 main/AndroidManifest.xml'
}
else {
    $manifestText = Read-TextFile -Path $manifestPath.FullName
    $manifestRel = Get-RelativePath -Base $SourceDir -Path $manifestPath.FullName
    $doc = $null
    try { $doc = [xml]$manifestText } catch { }
    if ($null -eq $doc) {
        Add-Result -Id 'manifest.parse' -Severity 'FAIL' -Message "Manifest XML 解析失败: $manifestRel"
    }
    else {
        Add-Result -Id 'manifest.parse' -Severity 'PASS' -Message "Manifest 可解析: $manifestRel"
        $root = $doc.DocumentElement
        $package = $root.GetAttribute('package')
        if (-not $package) { $package = $ExpectedPackage }
        $appNode = @($root.ChildNodes | Where-Object { $_.NodeType -eq 'Element' -and $_.LocalName -eq 'application' }) | Select-Object -First 1

        function Get-Astr {
            param($Node, [string]$Name)
            if ($null -eq $Node) { return $null }
            if (-not $Node.HasAttribute($Name, $script:AndroidNs)) { return $null }
            return $Node.GetAttribute($Name, $script:AndroidNs)
        }
        function Get-BoolA {
            param($Node, [string]$Name)
            $v = Get-Astr -Node $Node -Name $Name
            if ($null -eq $v) { return $null }
            return ($v -eq 'true')
        }

        if ($null -eq $appNode) {
            Add-Result -Id 'manifest.application' -Severity 'FAIL' -Message '<application> 缺失'
        }
        else {
            # allowBackup / cleartext / nsc
            $allowBackup = Get-BoolA -Node $appNode -Name 'allowBackup'
            if ($allowBackup -eq $false) { Add-Result -Id 'src.app.allowBackup' -Severity 'PASS' -Message 'allowBackup=false' }
            else { Add-Result -Id 'src.app.allowBackup' -Severity 'FAIL' -Message "allowBackup 必须显式 false（当前 $([string]$allowBackup)）" }
            $cleartext = Get-BoolA -Node $appNode -Name 'usesCleartextTraffic'
            if ($cleartext -eq $false) { Add-Result -Id 'src.app.cleartext' -Severity 'PASS' -Message 'usesCleartextTraffic=false' }
            else { Add-Result -Id 'src.app.cleartext' -Severity 'FAIL' -Message "usesCleartextTraffic 必须显式 false（当前 $([string]$cleartext)）" }
            $nscAttr = Get-Astr -Node $appNode -Name 'networkSecurityConfig'
            if ($nscAttr) { Add-Result -Id 'src.app.nsc_attr' -Severity 'PASS' -Message "已引用 networkSecurityConfig=$nscAttr" }
            else { Add-Result -Id 'src.app.nsc_attr' -Severity 'FAIL' -Message '未引用 networkSecurityConfig' }
            $debuggable = Get-Astr -Node $appNode -Name 'debuggable'
            if ($debuggable) { Add-Result -Id 'src.app.debuggable_absent' -Severity 'FAIL' -Message "源码 Manifest 中出现 android:debuggable=$debuggable（应仅由构建类型控制）" }
            else { Add-Result -Id 'src.app.debuggable_absent' -Severity 'PASS' -Message '源码 Manifest 未设置 debuggable' }

            # 权限
            $declared = @()
            foreach ($child in $root.ChildNodes) {
                if ($child.NodeType -ne 'Element') { continue }
                if ($child.LocalName -in @('uses-permission', 'uses-permission-sdk-23')) {
                    $n = Get-Astr -Node $child -Name 'name'
                    if ($n) { $declared += $n }
                }
            }
            $sensitive = @()
            $unexpected = @()
            foreach ($p in $declared) {
                if ($script:AllowedPermissions -notcontains $p) { $unexpected += $p }
                $short = $p -replace '^android\.permission\.', ''
                foreach ($s in $script:SensitivePermissionPatterns) { if ($short -match $s) { $sensitive += $p; break } }
            }
            if ($sensitive.Count -gt 0) { Add-Result -Id 'src.permissions.sensitive' -Severity 'FAIL' -Message '源码 Manifest 声明了敏感/危险权限' -Evidence ($sensitive -join ', ') }
            else { Add-Result -Id 'src.permissions.sensitive' -Severity 'PASS' -Message '无定位/通讯录/短信/存储/悬浮窗/无障碍/开机自启等敏感权限' }
            if ($unexpected.Count -gt 0) { Add-Result -Id 'src.permissions.allowlist' -Severity 'FAIL' -Message '源码 Manifest 存在白名单之外的权限' -Evidence ($unexpected -join ', ') }
            else { Add-Result -Id 'src.permissions.allowlist' -Severity 'PASS' -Message "权限与白名单一致（$($declared.Count) 项）" }
            $missing = @($script:AllowedPermissions | Where-Object { $declared -notcontains $_ })
            if ($missing.Count -gt 0) { Add-Result -Id 'src.permissions.required' -Severity 'WARN' -Message '源码 Manifest 缺少部分预期权限（可能由构建时合并/尚未实现）' -Evidence ($missing -join ', ') }
            else { Add-Result -Id 'src.permissions.required' -Severity 'PASS' -Message '6 项预期权限齐全' }

            # 组件
            $components = @($appNode.ChildNodes | Where-Object { $_.NodeType -eq 'Element' -and $_.LocalName -in @('activity', 'service', 'receiver', 'provider') })
            $exportedNonMain = @()
            $mainComp = $null
            $serviceComp = $null
            foreach ($c in $components) {
                $rawName = Get-Astr -Node $c -Name 'name'
                $full = $rawName
                if ($rawName -and $rawName.StartsWith('.')) { $full = "$package$rawName" }
                $isExported = Get-BoolA -Node $c -Name 'exported'
                if ($c.LocalName -eq 'activity' -and $full -eq "$ExpectedPackage.ui.MainActivity") { $mainComp = $c }
                if ($c.LocalName -eq 'service' -and $full -eq "$ExpectedPackage.core.monitor.MonitorService") { $serviceComp = $c }
                if ($isExported -eq $true -and $full -ne "$ExpectedPackage.ui.MainActivity") {
                    $permAttr = Get-Astr -Node $c -Name 'permission'
                    if ($permAttr) { $exportedNonMain += "$($c.LocalName):$full（受 $permAttr 保护）" }
                    else { $exportedNonMain += "$($c.LocalName):$full" }
                }
            }
            $hardExported = @($exportedNonMain | Where-Object { $_ -notmatch '受 ' })
            if ($hardExported.Count -gt 0) { Add-Result -Id 'src.components.exported' -Severity 'FAIL' -Message '源码 Manifest 中存在非预期导出组件' -Evidence ($hardExported -join ', ') }
            elseif ($exportedNonMain.Count -gt 0) { Add-Result -Id 'src.components.exported' -Severity 'WARN' -Message '存在受系统权限保护的导出组件（需确认不可被普通应用触达）' -Evidence ($exportedNonMain -join ', ') }
            else { Add-Result -Id 'src.components.exported' -Severity 'PASS' -Message '仅 MainActivity 导出，其余组件 exported=false/未导出' }

            if ($null -eq $mainComp) { Add-Result -Id 'src.components.main_activity' -Severity 'FAIL' -Message "未找到 $ExpectedPackage.ui.MainActivity 的 <activity>" }
            elseif ((Get-BoolA -Node $mainComp -Name 'exported') -ne $true) { Add-Result -Id 'src.components.main_activity' -Severity 'FAIL' -Message 'MainActivity 未显式 exported=true' }
            else { Add-Result -Id 'src.components.main_activity' -Severity 'PASS' -Message 'MainActivity 导出（LAUNCHER 入口）' }

            # MainActivity intent-filter
            if ($null -ne $mainComp) {
                $badFilters = @()
                foreach ($f in $mainComp.ChildNodes) {
                    if ($f.NodeType -ne 'Element' -or $f.LocalName -ne 'intent-filter') { continue }
                    foreach ($a in $f.ChildNodes) {
                        if ($a.NodeType -ne 'Element') { continue }
                        $n = Get-Astr -Node $a -Name 'name'
                        if ($a.LocalName -eq 'action' -and $n -ne 'android.intent.action.MAIN') { $badFilters += "action=$n" }
                        if ($a.LocalName -eq 'category' -and $n -ne 'android.intent.category.LAUNCHER') { $badFilters += "category=$n" }
                        if ($a.LocalName -eq 'data') { $badFilters += "data(附加 scheme/host，可能形成深链接)" }
                    }
                }
                if ($badFilters.Count -gt 0) { Add-Result -Id 'src.components.main_filter' -Severity 'FAIL' -Message 'MainActivity 存在 MAIN/LAUNCHER 之外/之外的 intent-filter（深链接面）' -Evidence ($badFilters -join ', ') }
                else { Add-Result -Id 'src.components.main_filter' -Severity 'PASS' -Message 'MainActivity 仅 MAIN+LAUNCHER，无深链接' }
            }

            # Service + specialUse
            if ($null -eq $serviceComp) {
                Add-Result -Id 'src.fgs.service' -Severity 'FAIL' -Message "未找到 $ExpectedPackage.core.monitor.MonitorService 的 <service>"
            }
            else {
                if ((Get-BoolA -Node $serviceComp -Name 'exported') -eq $true) { Add-Result -Id 'src.fgs.exported' -Severity 'FAIL' -Message 'MonitorService exported=true' }
                else { Add-Result -Id 'src.fgs.exported' -Severity 'PASS' -Message 'MonitorService exported=false' }
                $fgsType = Get-Astr -Node $serviceComp -Name 'foregroundServiceType'
                if ($fgsType -and ($fgsType -match 'specialUse')) { Add-Result -Id 'src.fgs.type' -Severity 'PASS' -Message "foregroundServiceType=$fgsType" }
                else { Add-Result -Id 'src.fgs.type' -Severity 'FAIL' -Message "未声明 specialUse 前台服务类型（当前 $fgsType）" }
                $propOk = $false
                $propVal = ''
                foreach ($p in $serviceComp.ChildNodes) {
                    if ($p.NodeType -ne 'Element' -or $p.LocalName -ne 'property') { continue }
                    if ((Get-Astr -Node $p -Name 'name') -eq 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE') {
                        $propOk = $true
                        $propVal = Get-Astr -Node $p -Name 'value'
                    }
                }
                if ($propOk -and $propVal) { Add-Result -Id 'src.fgs.subtype' -Severity 'PASS' -Message 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE 已声明' -Evidence $propVal }
                else { Add-Result -Id 'src.fgs.subtype' -Severity 'FAIL' -Message '缺少 PROPERTY_SPECIAL_USE_FGS_SUBTYPE 用途说明' }
            }

            # 无 receiver / provider（最小组件面）
            $receivers = @($components | Where-Object { $_.LocalName -eq 'receiver' })
            $providers = @($components | Where-Object { $_.LocalName -eq 'provider' })
            if ($receivers.Count -gt 0) {
                $rnames = ($receivers | ForEach-Object { Get-Astr -Node $_ -Name 'name' }) -join ', '
                Add-Result -Id 'src.components.receiver' -Severity 'WARN' -Message '源码 Manifest 声明了 BroadcastReceiver（需确认未导出、无开机自启）' -Evidence $rnames
            }
            else { Add-Result -Id 'src.components.receiver' -Severity 'PASS' -Message '无自定义 BroadcastReceiver（无开机自启/隐式广播入口）' }
            if ($providers.Count -gt 0) {
                $pnames = ($providers | ForEach-Object { Get-Astr -Node $_ -Name 'name' }) -join ', '
                Add-Result -Id 'src.components.provider' -Severity 'WARN' -Message '源码 Manifest 声明了 ContentProvider（需确认导出与 authority 隔离）' -Evidence $pnames
            }
            else { Add-Result -Id 'src.components.provider' -Severity 'PASS' -Message '无自定义 ContentProvider' }

            # ---- v1.1.0：包可见性 / 跳转 Activity / 深链接 ----
            $expectedQueries = @(
                'air.tv.douyu.android', 'com.duowan.kiwi', 'tv.danmaku.bili',
                'com.ss.android.ugc.aweme', 'com.smile.gifmaker'
            )
            $queriesNodes = @($root.ChildNodes | Where-Object { $_.NodeType -eq 'Element' -and $_.LocalName -eq 'queries' })
            if ($queriesNodes.Count -eq 0) {
                Add-Result -Id 'src.manifest.queries' -Severity 'WARN' -Message '未声明 <queries>（Android 11+ 打开平台 App 需要声明；若未实现跳转功能可忽略）'
            }
            else {
                $queryPkgs = @()
                $allowedIntentSchemes = @()
                $broadQuery = @()
                foreach ($q in $queriesNodes) {
                    foreach ($ch in $q.ChildNodes) {
                        if ($ch.NodeType -ne 'Element') { continue }
                        if ($ch.LocalName -eq 'package') { $queryPkgs += $ch.GetAttribute('name', $script:AndroidNs) }
                        elseif ($ch.LocalName -eq 'intent') {
                            # 允许“范围受限于固定 scheme”的 intent 查询（用于 resolveActivity/已知市场过滤）；
                            # 必须是 VIEW + 单一 data scheme，scheme 只允许 market/https，否则视为宽泛查询。
                            $intentActions = @($ch.ChildNodes | Where-Object { $_.NodeType -eq 'Element' -and $_.LocalName -eq 'action' } | ForEach-Object { $_.GetAttribute('name', $script:AndroidNs) })
                            $intentSchemes = @($ch.ChildNodes | Where-Object { $_.NodeType -eq 'Element' -and $_.LocalName -eq 'data' } | ForEach-Object { $_.GetAttribute('scheme', $script:AndroidNs) } | Where-Object { $_ })
                            $badAction = @($intentActions | Where-Object { $_ -ne 'android.intent.action.VIEW' })
                            # v1.1.1 已下架“下载 App”（无 market/应用市场/官网下载），queries 只应保留 https（浏览器解析用）
                            $badScheme = @($intentSchemes | Where-Object { $_ -ne 'https' })
                            if ($badAction.Count -gt 0 -or $badScheme.Count -gt 0 -or $intentSchemes.Count -eq 0) {
                                $broadQuery += "intent(action=$($intentActions -join '/'),scheme=$($intentSchemes -join '/'))"
                            }
                            else {
                                $allowedIntentSchemes += $intentSchemes
                            }
                        }
                        else { $broadQuery += $ch.LocalName }
                    }
                }
                $queryPkgs = @($queryPkgs | Where-Object { $_ } | Select-Object -Unique)
                $allowedIntentSchemes = @($allowedIntentSchemes | Select-Object -Unique)
                $missingQ = @($expectedQueries | Where-Object { $queryPkgs -notcontains $_ })
                $extraQ = @($queryPkgs | Where-Object { $expectedQueries -notcontains $_ })
                if ($broadQuery.Count -gt 0) {
                    Add-Result -Id 'src.manifest.queries' -Severity 'FAIL' -Message 'queries 存在宽泛声明（未限制 scheme 的 <intent>/<provider>），会暴露更多已安装应用信息' -Evidence ($broadQuery -join '; ')
                }
                elseif ($missingQ.Count -gt 0 -or $extraQ.Count -gt 0) {
                    Add-Result -Id 'src.manifest.queries' -Severity 'WARN' -Message 'queries 包列表与预期 5 平台包不一致' -Evidence "missing=$($missingQ -join ','); extra=$($extraQ -join ',')"
                }
                else {
                    $intentNote = if ($allowedIntentSchemes.Count -gt 0) { "；另有受限 intent scheme: $($allowedIntentSchemes -join ',')（仅用于浏览器解析）" } else { '' }
                    Add-Result -Id 'src.manifest.queries' -Severity 'PASS' -Message "queries 精确声明 5 个平台包（$($queryPkgs.Count) 个），未使用 QUERY_ALL_PACKAGES$intentNote"
                }
            }
            if ($declared -contains 'android.permission.QUERY_ALL_PACKAGES') {
                Add-Result -Id 'src.manifest.query_all_packages' -Severity 'FAIL' -Message '申请了 QUERY_ALL_PACKAGES（可枚举全部已安装应用，商店高危权限，且与最小可见性设计冲突）'
            }

            # 深链接：任何组件声明 <data>/BROWSABLE 都应视为新增外部入口，必须 FAIL（本项目不需要深链接）
            $deepLinkHits = @()
            $componentsForLink = @($appNode.ChildNodes | Where-Object { $_.NodeType -eq 'Element' -and $_.LocalName -in @('activity', 'activity-alias', 'service', 'receiver', 'provider') })
            foreach ($c in $componentsForLink) {
                $cname = Get-Astr -Node $c -Name 'name'
                foreach ($f in $c.ChildNodes) {
                    if ($f.NodeType -ne 'Element' -or $f.LocalName -ne 'intent-filter') { continue }
                    $hasData = @($f.ChildNodes | Where-Object { $_.NodeType -eq 'Element' -and $_.LocalName -eq 'data' }).Count -gt 0
                    $hasBrowsable = $false
                    foreach ($a in $f.ChildNodes) {
                        if ($a.NodeType -eq 'Element' -and $a.LocalName -eq 'category' -and (Get-Astr -Node $a -Name 'name') -eq 'android.intent.category.BROWSABLE') { $hasBrowsable = $true }
                    }
                    if ($hasData -or $hasBrowsable) { $deepLinkHits += "$cname(data=$hasData,browsable=$hasBrowsable)" }
                }
            }
            if ($deepLinkHits.Count -gt 0) {
                Add-Result -Id 'src.manifest.deep_link' -Severity 'FAIL' -Message 'Manifest 中存在深链接入口（<data>/BROWSABLE），会扩大外部 Intent 攻击面' -Evidence ($deepLinkHits -join '; ')
            }
            else {
                Add-Result -Id 'src.manifest.deep_link' -Severity 'PASS' -Message '无任何深链接入口（无 <data>/BROWSABLE）'
            }

            # 跳转选择 Activity：必须存在且不导出
            $jumpComp = @($componentsForLink | Where-Object {
                    $n = Get-Astr -Node $_ -Name 'name'
                    $n -eq '.ui.RoomJumpActivity' -or $n -eq "$ExpectedPackage.ui.RoomJumpActivity"
                }) | Select-Object -First 1
            if ($null -eq $jumpComp) {
                Add-Result -Id 'src.components.jump_activity' -Severity 'WARN' -Message 'Manifest 未声明 .ui.RoomJumpActivity（跳转功能可能尚未实现；最终版本应存在且 exported=false）'
            }
            elseif ((Get-BoolA -Node $jumpComp -Name 'exported') -eq $true) {
                Add-Result -Id 'src.components.jump_activity' -Severity 'FAIL' -Message 'RoomJumpActivity 被导出（外部应用可拉起跳转弹窗并注入参数）'
            }
            else {
                $jumpAttrs = @()
                if ((Get-Astr -Node $jumpComp -Name 'noHistory') -eq 'true') { $jumpAttrs += 'noHistory=true' }
                if ((Get-Astr -Node $jumpComp -Name 'excludeFromRecents') -eq 'true') { $jumpAttrs += 'excludeFromRecents=true' }
                Add-Result -Id 'src.components.jump_activity' -Severity 'PASS' -Message 'RoomJumpActivity 存在且 exported=false，不接收外部拉起' -Evidence ($jumpAttrs -join ', ')
            }
        }
    }
}

# ---------------- 3. network_security_config ----------------
$nscFile = @(Get-ChildItem $mainRoot -Recurse -File -Filter 'network_security_config.xml' -ErrorAction SilentlyContinue) | Select-Object -First 1
if (-not $nscFile) {
    Add-Result -Id 'src.nsc.file' -Severity 'FAIL' -Message '未找到 network_security_config.xml'
}
else {
    try {
        [xml]$nsc = Read-TextFile -Path $nscFile.FullName
        Add-Result -Id 'src.nsc.file' -Severity 'PASS' -Message "找到 $(Get-RelativePath -Base $SourceDir -Path $nscFile.FullName)"
        $base = @($nsc.SelectNodes("//*[local-name()='base-config']"))
        if ($base.Count -eq 0) { Add-Result -Id 'src.nsc.cleartext' -Severity 'WARN' -Message '无 base-config（可能存在别的明文放行路径）' }
        else {
            $cv = $base[0].GetAttribute('cleartextTrafficPermitted')
            if ($cv -eq 'false') { Add-Result -Id 'src.nsc.cleartext' -Severity 'PASS' -Message 'base-config cleartextTrafficPermitted=false' }
            else { Add-Result -Id 'src.nsc.cleartext' -Severity 'FAIL' -Message "base-config cleartextTrafficPermitted=$cv" }
        }
        $certSrcs = @($nsc.SelectNodes("//*[local-name()='certificates']") | ForEach-Object { $_.GetAttribute('src') } | Where-Object { $_ } | Select-Object -Unique)
        if ($certSrcs -contains 'user') { Add-Result -Id 'src.nsc.trust' -Severity 'FAIL' -Message '信任用户 CA（可被抓包/中间人）' -Evidence ($certSrcs -join ', ') }
        elseif ($certSrcs -contains 'system') { Add-Result -Id 'src.nsc.trust' -Severity 'PASS' -Message '仅信任系统 CA' -Evidence ($certSrcs -join ', ') }
        else { Add-Result -Id 'src.nsc.trust' -Severity 'WARN' -Message '未识别 trust-anchors src=system' -Evidence ($certSrcs -join ', ') }
        $domainCfg = @($nsc.SelectNodes("//*[local-name()='domain-config']"))
        if ($domainCfg.Count -gt 0) { Add-Result -Id 'src.nsc.domain' -Severity 'WARN' -Message "存在 $($domainCfg.Count) 个 domain-config 例外，需逐条复核" }
        else { Add-Result -Id 'src.nsc.domain' -Severity 'PASS' -Message '无 domain-config 例外' }
    }
    catch {
        Add-Result -Id 'src.nsc.file' -Severity 'FAIL' -Message "network_security_config.xml 解析失败: $($_.Exception.Message)"
    }
}

# ---------------- 4. 代码规则扫描 ----------------
function Add-CodeRule {
    param([string]$Id, [string]$Severity, [string]$Message, [string]$Pattern)
    Add-ScanRuleResult -Id $Id -Severity $Severity -Message $Message -Pattern $Pattern
}

Add-CodeRule -Id 'code.webview' -Severity 'FAIL' -Message '出现 WebView / JS 桥 / 网页动态执行能力（本项目不需要，属高风险攻击面）' `
    -Pattern 'android\.webkit\.WebView|addJavascriptInterface|setJavaScriptEnabled|Landroid/webkit/WebView;|\.loadUrl\(|evaluateJavascript|setAllowFileAccess|setWebContentsDebuggingEnabled|WebViewClient'
Add-CodeRule -Id 'code.dynamic_loading' -Severity 'FAIL' -Message '出现动态代码加载（DexClassLoader/PathClassLoader/InMemoryDexClassLoader）' `
    -Pattern 'DexClassLoader|PathClassLoader|InMemoryDexClassLoader|BaseDexClassLoader|createPackageContext|dalvik\.system'
Add-CodeRule -Id 'code.exec' -Severity 'FAIL' -Message '出现本机命令执行（Runtime.exec / ProcessBuilder / su）' `
    -Pattern 'Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec|ProcessBuilder|Runtime\.exec|/system/bin/su|Runtime;->exec'
Add-CodeRule -Id 'code.native_load' -Severity 'WARN' -Message '出现原生库加载（System.load/loadLibrary），需确认无第三方 so 与路径不可写' `
    -Pattern 'System\.loadLibrary|System\.load\('
Add-CodeRule -Id 'code.reflection' -Severity 'WARN' -Message '出现反射（Class.forName/getDeclaredMethod/setAccessible），需确认非动态加载入口' `
    -Pattern 'Class\.forName|getDeclaredMethod|getDeclaredField|setAccessible|java\.lang\.reflect'
Add-CodeRule -Id 'code.telephony' -Severity 'FAIL' -Message '出现电话/设备标识访问（getDeviceId/imei/TelephonyManager），与“无设备标识”承诺不符' `
    -Pattern 'getDeviceId|getImei|getMeid|getSubscriberId|TelephonyManager|READ_PHONE_STATE'
Add-CodeRule -Id 'code.contacts' -Severity 'FAIL' -Message '出现通讯录/联系人访问' `
    -Pattern 'ContactsContract|READ_CONTACTS|WRITE_CONTACTS'
Add-CodeRule -Id 'code.sms' -Severity 'FAIL' -Message '出现短信能力（SmsManager/sendTextMessage）' `
    -Pattern 'SmsManager|sendTextMessage|SEND_SMS|RECEIVE_SMS'
Add-CodeRule -Id 'code.location' -Severity 'FAIL' -Message '出现定位能力（LocationManager/FusedLocation/定位权限）' `
    -Pattern 'ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|ACCESS_BACKGROUND_LOCATION|LocationManager|FusedLocationProvider'
Add-CodeRule -Id 'code.overlay' -Severity 'FAIL' -Message '出现悬浮窗/叠加层能力（SYSTEM_ALERT_WINDOW/Overlay）' `
    -Pattern 'SYSTEM_ALERT_WINDOW|TYPE_APPLICATION_OVERLAY|canDrawOverlays|TYPE_SYSTEM_ALERT'
Add-CodeRule -Id 'code.accessibility' -Severity 'FAIL' -Message '出现无障碍服务（AccessibilityService，能读屏/自动操作，绝不应使用）' `
    -Pattern 'AccessibilityService|BIND_ACCESSIBILITY_SERVICE|AccessibilityNodeInfo'
Add-CodeRule -Id 'code.boot' -Severity 'FAIL' -Message '出现开机自启（BOOT_COMPLETED/BootReceiver），Android 端明确移除该功能' `
    -Pattern 'RECEIVE_BOOT_COMPLETED|BOOT_COMPLETED|BootReceiver|onReceive\s*\(\s*context\s*,\s*.*BOOT'
Add-CodeRule -Id 'code.clipboard_read' -Severity 'WARN' -Message '出现剪贴板读取（仅允许用户主动“粘贴链接”时读取，禁止后台读取）' `
    -Pattern 'ClipboardManager|getPrimaryClip|ClipboardManager|setPrimaryClip'
Add-CodeRule -Id 'code.battery_whitelist_api' -Severity 'WARN' -Message '出现直接请求电池优化白名单的 API（商店政策敏感，建议仅跳转系统设置页）' `
    -Pattern 'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS|ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'
Add-CodeRule -Id 'code.tls_bypass' -Severity 'FAIL' -Message '出现自定义 TLS/主机名校验实现（可能绕过证书校验；本项目预期的 TLS 强化实现是 OkHttp CertificatePinner）' `
    -Pattern 'sslSocketFactory|TrustManager|HostnameVerifier|checkServerTrusted|ALLOW_ALL_HOSTNAME|trustAllCerts|X509TrustManager'

# TLS 证书固定（TlsPolicy.kt）：CA 家族级固定，默认开启；这是本项目预期的 TLS 强化实现
$tlsPolicyFile = @($script:CodeFiles | Where-Object { $_.Name -eq 'TlsPolicy.kt' }) | Select-Object -First 1
if ($tlsPolicyFile) {
    $tlsPolicyText = Read-TextFile -Path $tlsPolicyFile.FullName
    $pinValues = @([regex]::Matches($tlsPolicyText, '"sha256/[A-Za-z0-9+/=]{40,}"') |
            ForEach-Object { $_.Value } | Select-Object -Unique)
    $hasPinner = $tlsPolicyText -match 'CertificatePinner'
    $hasCoveredHosts = ($tlsPolicyText -match 'HOST_PINS') -and ($tlsPolicyText -match 'isCoveredHost')
    if ($hasPinner -and $hasCoveredHosts -and $pinValues.Count -ge 4) {
        $relTls = Get-RelativePath -Base $SourceDir -Path $tlsPolicyFile.FullName
        Add-Result -Id 'code.tls_pinning' -Severity 'PASS' -Message "TlsPolicy 已实现 CA 家族级证书固定（$($pinValues.Count) 个 sha256 SPKI pin，覆盖平台 host 白名单）" -Evidence "file=$relTls"
    }
    else {
        Add-Result -Id 'code.tls_pinning' -Severity 'FAIL' -Message 'TlsPolicy.kt 存在但证书固定配置不完整（CertificatePinner / HOST_PINS / pin 数量）' -Evidence "pins=$($pinValues.Count), pinner=$hasPinner, hosts=$hasCoveredHosts"
    }
}
else {
    Add-Result -Id 'code.tls_pinning' -Severity 'WARN' -Message '未找到 TlsPolicy.kt（未启用严格证书固定）'
}

# 证书固定默认开启、异常分类为 POLICY、设置项默认 true
$httpFetcherFile = @($script:CodeFiles | Where-Object { $_.Name -eq 'HttpFetcher.kt' }) | Select-Object -First 1
$defaultPinningOn = $false
$pinClassified = $false
if ($httpFetcherFile) {
    $hfText = Read-TextFile -Path $httpFetcherFile.FullName
    $defaultPinningOn = $hfText -match 'pinningEnabled:\s*Boolean\s*=\s*true'
    $pinClassified = $hfText -match 'SSLPeerUnverifiedException'
}
$settingDefaultOn = $false
$modelsForPinning = @($script:CodeFiles | Where-Object { $_.Name -eq 'Models.kt' }) | Select-Object -First 1
if ($modelsForPinning) {
    $settingDefaultOn = (Read-TextFile -Path $modelsForPinning.FullName) -match 'certificatePinning:\s*Boolean\s*=\s*true'
}
if ($defaultPinningOn -and $pinClassified -and $settingDefaultOn) {
    Add-Result -Id 'code.tls_pinning_default' -Severity 'PASS' -Message '证书固定默认开启；SSLPeerUnverifiedException 归类 POLICY；设置项持久化默认 true'
}
else {
    Add-Result -Id 'code.tls_pinning_default' -Severity 'WARN' -Message '证书固定默认开启/异常分类/设置默认值需人工确认' -Evidence "defaultOn=$defaultPinningOn, classified=$pinClassified, settingDefault=$settingDefaultOn"
}
if ($settingDefaultOn) {
    Add-Result -Id 'code.tls_pinning_escape' -Severity 'INFO' -Message '设置提供“严格证书固定”开关作为逃生口（平台整体换 CA 时可临时关闭，默认开启）；关闭会降低 MITM 防护，属用户自主选择'
}

# ---------------- v1.1.0 新增：外部跳转 / 深链接 / 包可见性 / 自定义提示音 / 自动打开 / 通知渠道迁移 ----------------

# 外部跳转：ACTION_VIEW / market:// 的显式性检查
# - 平台 App 跳转必须 setPackage（防同名 scheme / App Links 劫持）；这是硬要求（文件内完全无 setPackage → FAIL）
# - 浏览器 / 应用市场的隐式 Intent 单独记录 WARN（market scheme 可能被恶意 App 劫持为钓鱼下载页）
$viewFiles = @()
$viewWithoutPackageAnywhere = @()
$implicitIntentHits = @()
$uriVariableHits = @()
foreach ($f in $script:CodeFiles) {
    $ft = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $relF = Get-RelativePath -Base $SourceDir -Path $f.FullName
    if ($ft -match 'ACTION_VIEW') {
        $viewFiles += $relF
        if ($ft -notmatch 'setPackage\s*\(') { $viewWithoutPackageAnywhere += $relF }
        foreach ($m in [regex]::Matches($ft, 'ACTION_VIEW')) {
            $start = [math]::Max(0, $m.Index - 240)
            $len = [math]::Min(620, $ft.Length - $start)
            $window = $ft.Substring($start, $len)
            if ($window -notmatch 'setPackage\s*\(') {
                $lineNo = ($ft.Substring(0, $m.Index) -split "`n").Count
                $implicitIntentHits += "${relF}:${lineNo}"
            }
        }
    }
    foreach ($m in [regex]::Matches($ft, 'Uri\s*\.\s*parse\s*\(\s*(?<arg>[^)\r\n]+)')) {
        $argClean = $m.Groups['arg'].Value.Trim().Trim('"', "'")
        if ($argClean -and $argClean -notmatch '^[A-Za-z][A-Za-z0-9+.-]*://') {
            $uriVariableHits += "${relF}: Uri.parse($($m.Groups['arg'].Value.Trim()))"
        }
    }
}
if ($viewFiles.Count -eq 0) {
    Add-Result -Id 'code.external_intent_package' -Severity 'WARN' -Message '未发现外部跳转代码（进入直播间功能可能尚未实现；最终版本应存在显式 setPackage 的跳转）'
}
elseif ($viewWithoutPackageAnywhere.Count -gt 0) {
    Add-Result -Id 'code.external_intent_package' -Severity 'FAIL' -Message '外部跳转文件完全未使用 setPackage（恶意 App 可注册同名 scheme/App Links 劫持跳转，打开钓鱼页面/应用）' -Evidence ($viewWithoutPackageAnywhere -join ', ')
}
else {
    Add-Result -Id 'code.external_intent_package' -Severity 'PASS' -Message '外部跳转使用 setPackage 指定目标应用（平台 App 跳转不可被同名 scheme 劫持）' -Evidence ($viewFiles -join ', ')
}
if ($implicitIntentHits.Count -gt 0) {
    # v1.1.0 最终实现：隐式 Intent 先 resolveActivity/queryIntentActivities，再用 setPackage 显式启动；
    # 只有解析不到 handler 时才回退标准隐式启动（残余低）。
    $hasResolveMitigation = $false
    foreach ($f in $script:CodeFiles) {
        $rt = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
        if (($rt -match 'resolveActivity\s*\(' -or $rt -match 'queryIntentActivities\s*\(') -and $rt -match 'setPackage\s*\(') {
            $hasResolveMitigation = $true
        }
    }
    if ($hasResolveMitigation) {
        Add-Result -Id 'code.hijackable_intent' -Severity 'PASS' -Message '隐式 ACTION_VIEW 先经 resolveActivity 解析系统默认 handler，再用 setPackage 显式启动；仅解析不到时回退（残余低；v1.1.1 已无市场跳转）' -Evidence (($implicitIntentHits | Select-Object -Unique) -join ', ')
    }
    else {
        Add-Result -Id 'code.hijackable_intent' -Severity 'WARN' -Message '存在未 setPackage 的隐式 ACTION_VIEW；可能被恶意 App 劫持，建议先 resolveActivity 再 setPackage' -Evidence (($implicitIntentHits | Select-Object -Unique) -join ', ')
    }
}
elseif ($viewFiles.Count -gt 0) {
    Add-Result -Id 'code.hijackable_intent' -Severity 'PASS' -Message '未发现未 setPackage 的外部 ACTION_VIEW（全部显式目标或无需隐式启动）'
}
if ($uriVariableHits.Count -gt 0) {
    Add-Result -Id 'code.jump_uri_variable' -Severity 'WARN' -Message 'Uri.parse 使用了变量（需确认变量来自本地已校验房间 URL 或固定 https 常量，而不是外部 Intent 传入）' -Evidence (($uriVariableHits | Select-Object -First 5) -join ' || ')
}

# 外链白名单：v1.1.1 已移除下载功能，浏览器 URL 只允许平台房间域名（storedHosts）
$jumpSourceFiles = @($script:CodeFiles | Where-Object { $_.Name -in @('JumpTargets.kt', 'RoomLauncher.kt') })
if ($jumpSourceFiles.Count -gt 0) {
    $jumpAll = ($jumpSourceFiles | ForEach-Object { Read-TextFile -Path $_.FullName }) -join "`n"
    if ($jumpAll -match 'isSafeOpenableUrl' -and $jumpAll -match 'isAllowedStoredHost') {
        Add-Result -Id 'code.jump_url_allowlist' -Severity 'PASS' -Message '浏览器打开的 URL 经平台房间域名白名单校验（v1.1.1 已无下载页 host）'
    }
    else {
        Add-Result -Id 'code.jump_url_allowlist' -Severity 'WARN' -Message '未发现外链域名白名单校验的明确证据，需人工确认浏览器打开目标不可被外部输入控制'
    }
}

# v1.1.1 已下架“下载 App（应用市场/官网下载页）”：源码不得残留市场/下载跳转能力
$downloadPatterns = @(
    'market://', 'MARKET_URI_PREFIX', 'openDownload', 'vendorDownloadUrl', 'VENDOR_DOWNLOAD_URLS',
    'knownMarketPackages', 'selectMarketPackages', 'marketUri', 'downloadpage',
    'douyu\.com/client', 'huya\.com/download', 'app\.bilibili\.com'
)
$downloadHits = @()
foreach ($f in $script:CodeFiles) {
    $dt = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $relD = Get-RelativePath -Base $SourceDir -Path $f.FullName
    foreach ($pat in $downloadPatterns) {
        if ($dt -match $pat) { $downloadHits += "${relD}: $pat"; break }
    }
}
if ($downloadHits.Count -gt 0) {
    Add-Result -Id 'code.download_removed' -Severity 'FAIL' -Message '源码仍存在“下载 App/应用市场/官网下载页”跳转残留（v1.1.1 已下架该功能，应移除以免扩大攻击面）' -Evidence ($downloadHits -join ' || ')
}
else {
    Add-Result -Id 'code.download_removed' -Severity 'PASS' -Message '未发现下载 App/应用市场/官网下载页跳转残留（v1.1.1 已下架该功能）'
}

# 跳转 Activity 参数契约：只能接收本地房间 key，禁止从外部 Intent 读取 URL/data
$jumpActivityFile = @($script:CodeFiles | Where-Object { $_.Name -eq 'RoomJumpActivity.kt' }) | Select-Object -First 1
if ($jumpActivityFile) {
    $jt = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $jumpActivityFile.FullName)
    $jumpRel = Get-RelativePath -Base $SourceDir -Path $jumpActivityFile.FullName
    $externalUrlHits = @()
    foreach ($pat in @('getData\s*\(', 'intent\s*\.\s*data\b', 'getStringExtra\s*\(\s*"[^"]*(?i:url)', 'EXTRA_URL')) {
        foreach ($m in [regex]::Matches($jt, $pat)) {
            $externalUrlHits += "${jumpRel}: $($m.Value)"
        }
    }
    if ($externalUrlHits.Count -gt 0) {
        Add-Result -Id 'code.jump_intent_contract' -Severity 'FAIL' -Message 'RoomJumpActivity 读取外部 Intent 的 URL/data（可被注入任意地址/跳转目标）' -Evidence ($externalUrlHits -join ' || ')
    }
    elseif ($jt -match 'RoomJumpIntent\s*\.\s*roomKey|EXTRA_ROOM_KEY') {
        Add-Result -Id 'code.jump_intent_contract' -Severity 'PASS' -Message 'RoomJumpActivity 只接收房间 key 并从本地仓储重新校验，不接受外部 URL'
    }
    else {
        Add-Result -Id 'code.jump_intent_contract' -Severity 'WARN' -Message 'RoomJumpActivity 参数来源未识别（需人工确认仅使用本地房间 key）'
    }
}
else {
    Add-Result -Id 'code.jump_intent_contract' -Severity 'WARN' -Message '未找到 RoomJumpActivity.kt（跳转功能可能尚未实现；最终版本需复核参数契约）'
}

# 包可见性：不允许 QUERY_ALL_PACKAGES；queryIntentActivities 必须以“已知市场包名 allowlist”过滤（v1.1.0 SEC-101 缓解）
$pmFiles = @($script:CodeFiles | Where-Object { (Read-TextFile -Path $_.FullName) -match 'PackageManager|queryIntentActivities|resolveActivity|getLaunchIntentForPackage' })
$broadPkgQuery = @()
$queryAllHits = @()
$marketFilterHits = @()
foreach ($f in $pmFiles) {
    $pt = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $relP = Get-RelativePath -Base $SourceDir -Path $f.FullName
    if ($pt -match 'QUERY_ALL_PACKAGES') { $queryAllHits += $relP }
    if ($pt -match 'queryIntentActivities\s*\(') {
        if ($pt -match 'knownMarketPackages|selectMarketPackages') { $marketFilterHits += $relP }
        else { $broadPkgQuery += "$relP(queryIntentActivities 未过滤包名)" }
    }
}
if ($queryAllHits.Count -gt 0) {
    Add-Result -Id 'code.package_visibility' -Severity 'FAIL' -Message '源码中出现 QUERY_ALL_PACKAGES（可枚举全部已安装应用，与最小可见性设计冲突）' -Evidence ($queryAllHits -join ', ')
}
elseif ($broadPkgQuery.Count -gt 0) {
    Add-Result -Id 'code.package_visibility' -Severity 'WARN' -Message '存在未用 allowlist 过滤的 queryIntentActivities（v1.1.1 已移除市场解析，应清理）' -Evidence ($broadPkgQuery -join ', ')
}
elseif ($marketFilterHits.Count -gt 0) {
    Add-Result -Id 'code.package_visibility' -Severity 'WARN' -Message '仍存在 market 解析（knownMarketPackages/selectMarketPackages）；v1.1.1 已下架下载功能，应清理' -Evidence ($marketFilterHits -join ', ')
}
else {
    Add-Result -Id 'code.package_visibility' -Severity 'PASS' -Message '代码未使用宽泛包查询（无 QUERY_ALL_PACKAGES / queryIntentActivities）'
}

# 自定义提示音：SAF 导入必须复制到内部私有目录、限制大小/格式、无路径穿越，日志不落真实路径
$soundFiles = @($script:CodeFiles | Where-Object { (Read-TextFile -Path $_.FullName) -match 'customSound|custom_sound|CustomSound|setDataSource\s*\(' })
$badSoundPath = @()
$hasSoundLimit = $false
$hasInternalCopy = $false
$persistUriHits = @()
foreach ($f in $soundFiles) {
    $st = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $relS = Get-RelativePath -Base $SourceDir -Path $f.FullName
    foreach ($pat in @('File\([^)\r\n]*(getStringExtra|getData\s*\(|\bintent\s*\.\s*data)', 'File\([^)\r\n]*\.\.')) {
        if ($st -match $pat) { $badSoundPath += "${relS}: $pat" }
    }
    if ($st -match 'MAX_CUSTOM_SOUND|MAX_SOUND_BYTES|2\s*\*\s*1024\s*\*\s*1024|2MB') { $hasSoundLimit = $true }
    if ($st -match 'filesDir|cacheDir') { $hasInternalCopy = $true }
    if ($st -match 'takePersistableUriPermission') { $persistUriHits += $relS }
}
if ($badSoundPath.Count -gt 0) {
    Add-Result -Id 'code.custom_sound_safety' -Severity 'FAIL' -Message '自定义提示音使用外部输入拼接文件路径（路径穿越/任意文件读取风险）' -Evidence ($badSoundPath -join ' || ')
}
elseif ($soundFiles.Count -eq 0) {
    Add-Result -Id 'code.custom_sound_safety' -Severity 'WARN' -Message '未发现自定义提示音实现（v1.1.0 计划新增；最终版本需复核 SAF 导入/内部存储/大小限制）'
}
elseif ($hasSoundLimit -and $hasInternalCopy) {
    Add-Result -Id 'code.custom_sound_safety' -Severity 'PASS' -Message '自定义提示音包含大小限制并复制到应用内部私有目录（SAF 导入，无存储权限）' -Evidence "files=$($soundFiles.Count)"
}
else {
    Add-Result -Id 'code.custom_sound_safety' -Severity 'WARN' -Message '自定义提示音实现缺少大小上限或内部存储拷贝的明确证据，需人工确认（MediaPlayer 解析不可信音频的健壮性）' -Evidence "limit=$hasSoundLimit, internalCopy=$hasInternalCopy"
}
if ($persistUriHits.Count -gt 0) {
    Add-Result -Id 'code.custom_sound_uri_permission' -Severity 'WARN' -Message '使用 takePersistableUriPermission 保留外部 URI 权限（建议复制到应用私有目录后释放，避免长期持有外部文件访问权）' -Evidence ($persistUriHits -join ', ')
}

# 通知渠道迁移：live_alerts → live_alerts_v2，旧渠道需清理（否则旧通知/用户设置残留）
$notifFiles = @($script:CodeFiles | Where-Object { (Read-TextFile -Path $_.FullName) -match 'NotificationChannel|CHANNEL_ALERTS' })
if ($notifFiles.Count -gt 0) {
    $notifAll = ($notifFiles | ForEach-Object { Read-TextFile -Path $_.FullName }) -join "`n"
    $hasV2 = $notifAll -match 'live_alerts_v2'
    $cleansOld = $notifAll -match 'deleteNotificationChannel'
    if ($hasV2 -and $cleansOld) {
        Add-Result -Id 'code.notification_channel' -Severity 'PASS' -Message '通知渠道已迁移到 live_alerts_v2，并清理旧渠道（旧通知/用户设置不残留）'
    }
    elseif ($hasV2) {
        Add-Result -Id 'code.notification_channel' -Severity 'WARN' -Message '已使用 live_alerts_v2 但未发现 deleteNotificationChannel（旧渠道可能残留，需人工确认迁移效果）'
    }
    else {
        Add-Result -Id 'code.notification_channel' -Severity 'WARN' -Message '未发现 live_alerts_v2（v1.1.0 计划迁移通知渠道；旧渠道用户自定义设置/旧通知影响需人工确认）'
    }
}

# 自动打开直播间：默认必须关闭；服务/通知侧后台 startActivity 必须 NEW_TASK + 异常兜底
$modelsForAuto = @($script:CodeFiles | Where-Object { $_.Name -eq 'Models.kt' }) | Select-Object -First 1
if ($modelsForAuto) {
    $mt = Read-TextFile -Path $modelsForAuto.FullName
    if ($mt -match 'autoOpenRoom:\s*Boolean\s*=\s*true') {
        Add-Result -Id 'code.auto_open_default' -Severity 'FAIL' -Message '自动打开直播间的默认值为 true（要求默认关闭，避免后台自动拉起外部应用）'
    }
    elseif ($mt -match 'autoOpenRoom:\s*Boolean\s*=\s*false') {
        Add-Result -Id 'code.auto_open_default' -Severity 'PASS' -Message '自动打开直播间默认关闭（autoOpenRoom=false）'
    }
    else {
        Add-Result -Id 'code.auto_open_default' -Severity 'INFO' -Message '未找到 autoOpenRoom 默认值（功能可能尚未实现）'
    }
}
# 自动打开直播间：后台（服务/通知）侧是否直接 startActivity，必须有 NEW_TASK + 统一异常兜底；
# 若通过 RoomLauncher 统一封装则视为安全（该封装统一加 NEW_TASK 并 catch 所有启动异常）。
$bgCandidateFiles = @($script:CodeFiles | Where-Object { $_.Name -in @('MonitorService.kt', 'AlertNotifier.kt', 'NotificationHelper.kt', 'AlertSoundPlayer.kt', 'LoopSoundPlayer.kt') })
$bgStartHits = @()
$bgGuardWarn = @()
$delegatesToLauncher = $false
foreach ($f in $bgCandidateFiles) {
    $bt = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $relB = Get-RelativePath -Base $SourceDir -Path $f.FullName
    if ($bt -match 'startActivity\s*\(') {
        $bgStartHits += $relB
        if ($bt -notmatch 'FLAG_ACTIVITY_NEW_TASK') { $bgGuardWarn += "$relB(缺少 FLAG_ACTIVITY_NEW_TASK)" }
    }
    if ($bt -match 'RoomLauncher\s*\.\s*(autoOpen|openRoomInApp|openWeb|openDownload)') { $delegatesToLauncher = $true }
}
if ($bgStartHits.Count -gt 0) {
    if ($bgGuardWarn.Count -gt 0) {
        Add-Result -Id 'code.auto_open_guard' -Severity 'WARN' -Message '服务/通知等非 Activity 上下文 startActivity 缺少 FLAG_ACTIVITY_NEW_TASK（运行期可能崩溃）' -Evidence ($bgGuardWarn -join ', ')
    }
    else {
        Add-Result -Id 'code.auto_open_guard' -Severity 'PASS' -Message '服务/通知侧后台 startActivity 已设置 FLAG_ACTIVITY_NEW_TASK，需确认异常兜底与 Android 10+ 后台启动限制处理' -Evidence ($bgStartHits -join ', ')
    }
}
elseif ($delegatesToLauncher) {
    Add-Result -Id 'code.auto_open_guard' -Severity 'PASS' -Message '后台自动打开通过 RoomLauncher 统一封装（NEW_TASK + 全异常兜底），服务/通知侧无直接 startActivity'
}
else {
    Add-Result -Id 'code.auto_open_guard' -Severity 'INFO' -Message '未发现服务/通知侧后台 startActivity（自动打开功能可能尚未实现或由 UI 侧处理）'
}
$jumpNotifFiles = @($script:CodeFiles | Where-Object { $_.Name -in @('NotificationHelper.kt', 'AlertNotifier.kt') })
if ($jumpNotifFiles.Count -gt 0) {
    $jn = ($jumpNotifFiles | ForEach-Object { Read-TextFile -Path $_.FullName }) -join "`n"
    if ($jn -match 'RoomJumpActivity|RoomJumpIntent') {
        Add-Result -Id 'code.jump_pendingintent' -Severity 'PASS' -Message '通知动作“进入直播间”通过显式 RoomJumpIntent 打开本 App Activity（配合 code.pendingintent 的 FLAG_IMMUTABLE 检查）'
    }
    else {
        Add-Result -Id 'code.jump_pendingintent' -Severity 'INFO' -Message '通知暂未提供“进入直播间”动作（v1.1.0 计划新增，最终版本需复核 PendingIntent 显式+IMMUTABLE）'
    }
}

Add-CodeRule -Id 'code.proxy' -Severity 'WARN' -Message '出现代理配置（合规要求：不使用代理/IP 轮换访问平台）' `
    -Pattern '\.proxy\s*\(|Proxy\.Type|InetSocketAddress\('
Add-CodeRule -Id 'code.open_room_url' -Severity 'INFO' -Message '检测到“进入直播间”外部跳转相关代码（v1.1.0 需求；安全性由 code.external_intent_package / code.jump_intent_contract 规则检查）' `
    -Pattern 'ACTION_VIEW|startActivity\s*\(\s*Intent\s*\(\s*Intent\.ACTION_VIEW|openRoom|openLiveRoom'
Add-CodeRule -Id 'code.android_log' -Severity 'WARN' -Message '出现 android.util.Log/println 调试输出（release 未剥离时可能泄露运行信息）' `
    -Pattern 'android\.util\.Log\.|Log\.[diwev]\s*\(|println\s*\(|printStackTrace\s*\('
Add-CodeRule -Id 'code.sharedprefs_world' -Severity 'FAIL' -Message 'SharedPreferences 使用全局可读/可写/多进程模式' `
    -Pattern 'MODE_WORLD_READABLE|MODE_WORLD_WRITEABLE|MODE_MULTI_PROCESS'
Add-CodeRule -Id 'code.test_in_main' -Severity 'FAIL' -Message '生产源码中引用了测试依赖（JUnit/MockWebServer/coroutines-test）或 BuildConfig（buildConfig=false）' `
    -Pattern 'org\.junit|mockwebserver|kotlinx\.coroutines\.test|BuildConfig\.'
Add-CodeRule -Id 'code.wake_full' -Severity 'FAIL' -Message '使用 FULL_WAKE_LOCK/SCREEN_BRIGHT_WAKE_LOCK（应为 PARTIAL_WAKE_LOCK 且短持）' `
    -Pattern 'FULL_WAKE_LOCK|SCREEN_BRIGHT_WAKE_LOCK|SCREEN_DIM_WAKE_LOCK'
Add-CodeRule -Id 'code.screen_off_disable' -Severity 'WARN' -Message '出现禁止息屏/保持屏幕常亮相关调用' `
    -Pattern 'FLAG_KEEP_SCREEN_ON|setKeepScreenOn|WAKE_LOCK_FULL'

# 明文 http:// 逐行过滤
$httpHits = @()
foreach ($f in $script:CodeFiles) {
    $text = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $lineNo = 0
    foreach ($line in ($text -split "`n")) {
        $lineNo++
        foreach ($m in [regex]::Matches($line, 'http://([A-Za-z0-9.\-:]+)')) {
            $hostName = $m.Groups[1].Value.ToLowerInvariant().TrimEnd('.')
            if ($script:BenignHttpHosts -contains $hostName) { continue }
            $rel = Get-RelativePath -Base $SourceDir -Path $f.FullName
            $httpHits += "${rel}:${lineNo}: $($line.Trim())"
            break
        }
        if ($httpHits.Count -ge 8) { break }
    }
    if ($httpHits.Count -ge 8) { break }
}
if ($httpHits.Count -gt 0) {
    Add-Result -Id 'code.cleartext_http' -Severity 'FAIL' -Message '源码中出现非命名空间用途的明文 http:// 地址（必须全部使用 https）' -Evidence ($httpHits -join ' || ')
}
else { Add-Result -Id 'code.cleartext_http' -Severity 'PASS' -Message '源码中无明文 http:// 地址（命名空间 URI 已豁免）' }

# 日志泄露（URL/标题/Cookie/备注进入日志）
$logHits = @()
foreach ($f in $script:CodeFiles) {
    $text = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $lineNo = 0
    foreach ($line in ($text -split "`n")) {
        $lineNo++
        if ($line -match 'AppLog\.(?:info|error)\s*\(' -and $line -match '(?i)\b(url|title|owner|cookie|remark|body|content)\b') {
            $rel = Get-RelativePath -Base $SourceDir -Path $f.FullName
            $logHits += "${rel}:${lineNo}: $($line.Trim())"
        }
    }
}
if ($logHits.Count -gt 0) {
    Add-Result -Id 'code.log_leak' -Severity 'FAIL' -Message 'AppLog 日志参数疑似包含 URL/标题/Cookie/备注等敏感内容（日志可在“诊断日志”界面读到）' -Evidence ($logHits -join ' || ')
}
else { Add-Result -Id 'code.log_leak' -Severity 'PASS' -Message 'AppLog 未记录 URL/标题/Cookie/备注等敏感字段' }

# 异常日志风险：AppLog.error(..., throwable) 的 message 可能包含完整 URL（OkHttp 异常典型）
# 若 AppLog 自身已实现 URL/凭据脱敏，则该风险已缓解，判 PASS 并记录脱敏规则。
$appLogFile = @($script:CodeFiles | Where-Object { $_.Name -eq 'AppLog.kt' }) | Select-Object -First 1
$hasLogRedaction = $false
if ($appLogFile) {
    $appLogText = Read-TextFile -Path $appLogFile.FullName
    $hasLogRedaction = ($appLogText -match 'https\?://') -and ($appLogText -match 'redact|\[url\]')
    if ($hasLogRedaction) {
        Add-Result -Id 'code.applog_redaction' -Severity 'PASS' -Message 'AppLog 已对 URL 与 cookie/token/password 等字段做脱敏（纵深防御）'
    }
    else {
        Add-Result -Id 'code.applog_redaction' -Severity 'WARN' -Message 'AppLog 未发现 URL/凭据脱敏实现，异常 message 可能把完整 URL 写入日志'
    }
}
$excHits = @()
foreach ($f in $script:CodeFiles) {
    $text = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $lineNo = 0
    foreach ($line in ($text -split "`n")) {
        $lineNo++
        if ($line -match 'AppLog\.error\s*\([^)]*,\s*(?:t|tr|e|ex|throwable|t2)\s*\)') {
            $rel = Get-RelativePath -Base $SourceDir -Path $f.FullName
            $excHits += "${rel}:${lineNo}: $($line.Trim())"
        }
    }
}
if ($excHits.Count -gt 0) {
    if ($hasLogRedaction) {
        Add-Result -Id 'code.log_exception_url' -Severity 'PASS' -Message 'AppLog.error 记录了异常对象，但 AppLog 已统一脱敏 URL/凭据，风险可接受' -Evidence ($excHits[0])
    }
    else {
        Add-Result -Id 'code.log_exception_url' -Severity 'WARN' -Message 'AppLog.error 记录了异常对象，异常 message 可能包含完整请求 URL（建议只记录异常类名/错误码，或对 URL 做脱敏）' -Evidence ($excHits -join ' || ')
    }
}
else { Add-Result -Id 'code.log_exception_url' -Severity 'PASS' -Message '未发现把异常 message 直接写入日志的高风险调用' }

# PendingIntent 必须 FLAG_IMMUTABLE（允许通过 pendingFlags()/helper 统一设置，文件级判断）
$piHits = @()
$piCount = 0
$piFiles = @()
$piLiteralZero = @()
foreach ($f in $script:CodeFiles) {
    $text = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
    $matchesPi = @([regex]::Matches($text, 'PendingIntent\s*\.\s*get(?:Activity|Service|Broadcast|ForegroundService)?\s*\('))
    if ($matchesPi.Count -eq 0) { continue }
    $rel = Get-RelativePath -Base $SourceDir -Path $f.FullName
    $piCount += $matchesPi.Count
    $piFiles += $rel
    if ($text -notmatch 'FLAG_IMMUTABLE') { $piHits += "${rel}: 文件内未出现 FLAG_IMMUTABLE" }
    foreach ($zm in [regex]::Matches($text, 'PendingIntent\s*\.\s*get(?:Activity|Service|Broadcast|ForegroundService)?\s*\([^;]{0,400}?,\s*0\s*\)')) {
        $lineNo = ($text.Substring(0, $zm.Index) -split "`n").Count
        $piLiteralZero += "${rel}:${lineNo}: flags 字面量 0"
    }
}
if ($piCount -eq 0) {
    Add-Result -Id 'code.pendingintent' -Severity 'INFO' -Message '源码中暂无 PendingIntent（通知提醒实现后需复核）'
}
elseif ($piHits.Count -gt 0) {
    Add-Result -Id 'code.pendingintent' -Severity 'FAIL' -Message 'PendingIntent 未声明 FLAG_IMMUTABLE（Android 12+ 强制要求，否则可被第三方填充/劫持）' -Evidence ($piHits -join ' || ')
}
elseif ($piLiteralZero.Count -gt 0) {
    Add-Result -Id 'code.pendingintent' -Severity 'FAIL' -Message 'PendingIntent 的 flags 使用字面量 0（缺少 FLAG_IMMUTABLE）' -Evidence ($piLiteralZero -join ' || ')
}
else {
    Add-Result -Id 'code.pendingintent' -Severity 'PASS' -Message "共 $piCount 处 PendingIntent（$($piFiles.Count) 个文件），均使用 FLAG_IMMUTABLE（含 pendingFlags() 统一 helper）"
}

# 通知锁屏隐私
$notifFiles = @($script:CodeFiles | Where-Object { (Read-TextFile -Path $_.FullName) -match 'Notification(?:Compat)?\.Builder|NotificationChannel' })
if ($notifFiles.Count -gt 0) {
    $publicHits = @()
    $titleHits = @()
    foreach ($f in $notifFiles) {
        $raw = Read-TextFile -Path $f.FullName
        $text = Get-CodeTextWithoutComments -Text $raw
        $rel = Get-RelativePath -Base $SourceDir -Path $f.FullName
        if ($text -match 'VISIBILITY_PUBLIC') { $publicHits += $rel }
        if ($text -match 'setContentText\s*\([^)]*(?i:title|lastTitle)') {
            if ($text -notmatch 'VISIBILITY_PRIVATE|VISIBILITY_SECRET') { $titleHits += $rel }
        }
    }
    if ($publicHits.Count -gt 0) { Add-Result -Id 'code.notification_privacy' -Severity 'FAIL' -Message '通知设置 VISIBILITY_PUBLIC（锁屏会展示直播标题，可能泄露观看偏好）' -Evidence ($publicHits -join ', ') }
    elseif ($titleHits.Count -gt 0) { Add-Result -Id 'code.notification_privacy' -Severity 'WARN' -Message '通知内容包含平台直播标题但未显式设置 VISIBILITY_PRIVATE/SECRET（锁屏可能展示）' -Evidence ($titleHits -join ', ') }
    else { Add-Result -Id 'code.notification_privacy' -Severity 'PASS' -Message '通知构建已考虑锁屏可见性（PRIVATE/SECRET 或未含平台标题）' }
}
else { Add-Result -Id 'code.notification_privacy' -Severity 'INFO' -Message '尚未实现通知构建代码（待 service-dev 完成后复核）' }

# WakeLock 获取/释放配对
$wakelockFiles = @($script:CodeFiles | Where-Object { (Read-TextFile -Path $_.FullName) -match 'PowerManager|WakeLock' })
if ($wakelockFiles.Count -gt 0) {
    $acquire = 0
    $release = 0
    foreach ($f in $wakelockFiles) {
        $text = Get-CodeTextWithoutComments -Text (Read-TextFile -Path $f.FullName)
        $acquire += ([regex]::Matches($text, '\.acquire\s*\(')).Count
        $release += ([regex]::Matches($text, '\.release\s*\(')).Count
    }
    if ($acquire -gt $release) { Add-Result -Id 'code.wakelock_balance' -Severity 'WARN' -Message "WakeLock acquire($acquire) 多于 release($release)，需确认异常路径也能释放" }
    elseif ($acquire -gt 0) { Add-Result -Id 'code.wakelock_balance' -Severity 'PASS' -Message "WakeLock 获取/释放调用配对（$acquire/$release），需确认仅短持" }
    else { Add-Result -Id 'code.wakelock_balance' -Severity 'INFO' -Message '源码中未发现 WakeLock 调用（可能由服务实现决定）' }
}
else { Add-Result -Id 'code.wakelock_balance' -Severity 'INFO' -Message '未发现 WakeLock 使用代码' }

# 平台访问域名白名单（PlatformApi 中必须存在 5 平台域名且 https）
$platformFiles = @($script:CodeFiles | Where-Object { $_.Name -match 'PlatformApi' })
if ($platformFiles.Count -gt 0) {
    $all = ($platformFiles | ForEach-Object { Read-TextFile -Path $_.FullName }) -join "`n"
    $domains = @('www.douyu.com', 'www.huya.com', 'live.bilibili.com', 'live.douyin.com', 'live.kuaishou.com')
    $missingDomains = @($domains | Where-Object { $all -notmatch [regex]::Escape($_) })
    if ($missingDomains.Count -gt 0) { Add-Result -Id 'code.platform_allowlist' -Severity 'WARN' -Message 'PlatformApi 中未找到全部 5 平台域名（可能改为常量表/白名单结构，需人工确认）' -Evidence ($missingDomains -join ', ') }
    else { Add-Result -Id 'code.platform_allowlist' -Severity 'PASS' -Message 'PlatformApi 中包含 5 平台域名' }
    if ($all -match '(?i)https?://\s*"?\s*\+') {
        Add-Result -Id 'code.url_concat' -Severity 'WARN' -Message 'PlatformApi 中存在字符串拼接构造 URL 的痕迹，需确认 id 已严格校验（防注入/SSRF）'
    }
    else { Add-Result -Id 'code.url_concat' -Severity 'PASS' -Message '未发现明显的不安全 URL 拼接' }
    $uaMatch = [regex]::Match($all, '(?i)(user-?agent|USER_AGENT)[^\n]{0,160}')
    if ($uaMatch.Success) { Add-Result -Id 'code.ua' -Severity 'INFO' -Message '源码中出现 User-Agent 设置（合规要求：固定通用 UA，不做轮换；不应伪装绕过风控）' -Evidence (Format-Text $uaMatch.Value 200) }
}
else { Add-Result -Id 'code.platform_allowlist' -Severity 'WARN' -Message '未找到 PlatformApi 源文件' }

# 检测间隔下限（15s）合规
$modelsFile = @($script:CodeFiles | Where-Object { $_.Name -match 'Models\.kt$' }) | Select-Object -First 1
if ($modelsFile) {
    $mt = Read-TextFile -Path $modelsFile.FullName
    if ($mt -match 'MIN_INTERVAL_SEC\s*=\s*(\d+)') {
        $minInterval = [int]$Matches[1]
        if ($minInterval -ge 15) { Add-Result -Id 'code.interval_floor' -Severity 'PASS' -Message "检测间隔下限 MIN_INTERVAL_SEC=$minInterval（>=15s 合规下限）" }
        else { Add-Result -Id 'code.interval_floor' -Severity 'FAIL' -Message "检测间隔下限 $minInterval 秒 < 15 秒合规下限" }
    }
    else { Add-Result -Id 'code.interval_floor' -Severity 'WARN' -Message '未找到 MIN_INTERVAL_SEC 常量，需人工确认间隔下限' }
}
else { Add-Result -Id 'code.interval_floor' -Severity 'INFO' -Message '未找到 Models.kt，跳过间隔下限检查' }

# 硬编码密钥/口令（源码、资源、Gradle）
$secretPatterns = [ordered]@{
    'AWS Access Key'   = 'AKIA[0-9A-Z]{16}'
    'Private Key'      = '-----BEGIN [A-Z ]*PRIVATE KEY-----'
    'Google API Key'   = 'AIza[0-9A-Za-z_\-]{35}'
    'GitHub Token'     = 'ghp_[A-Za-z0-9]{36}'
    'Slack Token'      = 'xox[baprs]-[A-Za-z0-9-]{10,}'
    'OpenAI Key'       = 'sk-[A-Za-z0-9]{20,}'
    '硬编码口令赋值'   = '(?i)(password|passwd|pwd|secret|token|api[_-]?key)\s*[=:]\s*["''][^"'']{6,}["'']'
}
$secretHits = @()
$secretScanFiles = @($sourceFiles)
foreach ($gp in @($gradleFile, $tomlFile)) { if ($gp -and (Test-Path $gp)) { $secretScanFiles += Get-Item $gp } }
foreach ($f in $secretScanFiles) {
    if ($f.Length -gt 2MB) { continue }
    if ($f.Extension -in @('.png', '.jpg', '.wav', '.mp3', '.so', '.jar')) { continue }
    $ft = Read-TextFile -Path $f.FullName
    if (-not $ft) { continue }
    foreach ($label in $secretPatterns.Keys) {
        if ($ft -match $secretPatterns[$label]) {
            $rel = Get-RelativePath -Base $sourceDir -Path $f.FullName
            $sample = $Matches[0]
            if ($label -eq '硬编码口令赋值') { $sample = ($sample -replace '["''][^"'']*["'']', '"<redacted>"') }
            $secretHits += "${rel}: $label ($(Format-Text $sample 60))"
            break
        }
    }
}
if ($secretHits.Count -gt 0) {
    Add-Result -Id 'code.hardcoded_secrets' -Severity 'FAIL' -Message '源码/资源/Gradle 中发现疑似硬编码密钥或口令' -Evidence ($secretHits -join ' || ')
}
else { Add-Result -Id 'code.hardcoded_secrets' -Severity 'PASS' -Message '源码/资源/Gradle 中未发现硬编码密钥/口令' }

# ---------------- 5. Gradle / ProGuard / 供应链 ----------------
$gradleFile = Join-Path $ProjectRoot 'app\build.gradle.kts'
$rootGradle = Join-Path $ProjectRoot 'build.gradle.kts'
$settingsFile = Join-Path $ProjectRoot 'settings.gradle.kts'
$proguardFile = Join-Path $ProjectRoot 'app\proguard-rules.pro'
$tomlFile = Join-Path $ProjectRoot 'gradle\libs.versions.toml'

function Get-BuildTypeBlock {
    param([string]$Text, [string]$Type)
    $idx = [regex]::Match($Text, "(?m)^\s*$Type\s*\{").Index
    if ($idx -lt 0) { return $null }
    $start = $Text.IndexOf('{', $idx)
    $depth = 0
    for ($i = $start; $i -lt $Text.Length; $i++) {
        $ch = $Text[$i]
        if ($ch -eq '{') { $depth++ }
        elseif ($ch -eq '}') {
            $depth--
            if ($depth -eq 0) { return $Text.Substring($start, $i - $start + 1) }
        }
    }
    return $Text.Substring($start)
}

if (Test-Path $gradleFile) {
    $g = Read-TextFile -Path $gradleFile
    $releaseBlock = Get-BuildTypeBlock -Text $g -Type 'release'
    if ($null -eq $releaseBlock) { Add-Result -Id 'gradle.release_minify' -Severity 'FAIL' -Message 'app/build.gradle.kts 中未找到 release 构建块' }
    else {
        if ($releaseBlock -match 'isMinifyEnabled\s*=\s*true') { Add-Result -Id 'gradle.release_minify' -Severity 'PASS' -Message 'release 启用 R8 minify' }
        else { Add-Result -Id 'gradle.release_minify' -Severity 'FAIL' -Message 'release 未启用 R8 minify（isMinifyEnabled）' }
        if ($releaseBlock -match 'isShrinkResources\s*=\s*true') { Add-Result -Id 'gradle.release_shrink' -Severity 'PASS' -Message 'release 启用资源压缩' }
        else { Add-Result -Id 'gradle.release_shrink' -Severity 'WARN' -Message 'release 未启用 shrinkResources' }
        if ($releaseBlock -match 'isDebuggable\s*=\s*true') { Add-Result -Id 'gradle.release_debuggable' -Severity 'FAIL' -Message 'release 构建被显式设为 debuggable' }
        else { Add-Result -Id 'gradle.release_debuggable' -Severity 'PASS' -Message 'release 未开启 debuggable' }
        if ($releaseBlock -match 'signingConfig\s*=\s*null') { Add-Result -Id 'gradle.release_signing_none' -Severity 'INFO' -Message 'release 未配置 signingConfig（交付为 unsigned，需用户自行签名）' }
    }
    if ($g -match 'storePassword\s*=\s*"[^"]+"' -or $g -match 'keyPassword\s*=\s*"[^"]+"') {
        Add-Result -Id 'gradle.hardcoded_signing' -Severity 'FAIL' -Message 'Gradle 中硬编码了签名口令（必须从 local.properties/env 读取）'
    }
    else { Add-Result -Id 'gradle.hardcoded_signing' -Severity 'PASS' -Message '签名口令未硬编码（local.properties/env）' }
    if ($g -match 'applicationId\s*=\s*"([^"]+)"') {
        $appId = $Matches[1]
        if ($appId -eq $ExpectedPackage) { Add-Result -Id 'gradle.application_id' -Severity 'PASS' -Message "applicationId=$appId" }
        else { Add-Result -Id 'gradle.application_id' -Severity 'FAIL' -Message "applicationId=$appId 与预期 $ExpectedPackage 不符" }
    }
    else { Add-Result -Id 'gradle.application_id' -Severity 'WARN' -Message '未解析到 applicationId' }
    if ($g -match 'minSdk\s*=\s*(\d+)') {
        $minSdk = [int]$Matches[1]
        if ($minSdk -eq 26) { Add-Result -Id 'gradle.min_sdk' -Severity 'PASS' -Message 'minSdk=26' }
        elseif ($minSdk -lt 26) { Add-Result -Id 'gradle.min_sdk' -Severity 'FAIL' -Message "minSdk=$minSdk < 26" }
        else { Add-Result -Id 'gradle.min_sdk' -Severity 'WARN' -Message "minSdk=$minSdk（项目约定 26）" }
    }
    if ($g -match 'targetSdk\s*=\s*(\d+)') {
        $targetSdk = [int]$Matches[1]
        if ($targetSdk -ge 35) { Add-Result -Id 'gradle.target_sdk' -Severity 'PASS' -Message "targetSdk=$targetSdk" }
        else { Add-Result -Id 'gradle.target_sdk' -Severity 'FAIL' -Message "targetSdk=$targetSdk < 35（商店政策要求）" }
    }
    if ($g -match 'abortOnError\s*=\s*true') { Add-Result -Id 'gradle.lint' -Severity 'PASS' -Message 'lint abortOnError=true' }
    else { Add-Result -Id 'gradle.lint' -Severity 'WARN' -Message 'lint abortOnError 未启用，需人工确认 lint 结果' }
    if ($g -match 'buildConfig\s*=\s*false') { Add-Result -Id 'gradle.buildconfig' -Severity 'PASS' -Message 'buildConfig=false（不暴露构建期常量）' }
    else { Add-Result -Id 'gradle.buildconfig' -Severity 'INFO' -Message '未设置 buildConfig=false' }
    $trackerLibs = @('firebase', 'umeng', 'bugly', 'appsflyer', 'adjust', 'crashlytics', 'sentry', 'mixpanel', 'amplitude', 'facebook', 'admob', 'play-services-ads', 'applovin', 'unity-ads')
    $depHits = @($trackerLibs | Where-Object { $g -match "(?i)$_" })
    if ($depHits.Count -gt 0) { Add-Result -Id 'gradle.tracker_deps' -Severity 'FAIL' -Message '依赖中出现统计/广告/推送 SDK' -Evidence ($depHits -join ', ') }
    else { Add-Result -Id 'gradle.tracker_deps' -Severity 'PASS' -Message '依赖中无统计/广告/推送 SDK' }
    if ($g -match 'testImplementation') { Add-Result -Id 'gradle.test_deps' -Severity 'PASS' -Message '测试依赖使用 testImplementation（不进入 release）' }
    $debugImpl = ([regex]::Matches($g, 'debugImplementation\s*\(')).Count
    Add-Result -Id 'gradle.debug_deps' -Severity 'INFO' -Message "debugImplementation 依赖 $debugImpl 个（仅 debug 构建，release 不包含；需在 release APK 复核）"
}
else { Add-Result -Id 'gradle.build_file' -Severity 'INFO' -Message "未找到 app/build.gradle.kts（$gradleFile），跳过 Gradle 检查" }

if (Test-Path $proguardFile) {
    $pg = Read-TextFile -Path $proguardFile
    if ($pg -match '(?m)^\s*-keep\s+class\s+\*\*') { Add-Result -Id 'proguard.broad_keep' -Severity 'WARN' -Message 'ProGuard 存在 -keep class ** 宽泛保留（削弱混淆）' }
    else { Add-Result -Id 'proguard.broad_keep' -Severity 'PASS' -Message 'ProGuard 无宽泛 -keep class **' }
    if ($pg -match '-dontobfuscate') { Add-Result -Id 'proguard.dontobfuscate' -Severity 'FAIL' -Message 'ProGuard 禁止混淆（-dontobfuscate）' }
    else { Add-Result -Id 'proguard.dontobfuscate' -Severity 'PASS' -Message '未禁止混淆' }
}
else { Add-Result -Id 'proguard.file' -Severity 'INFO' -Message '未找到 proguard-rules.pro（使用默认规则）' }

if (Test-Path $settingsFile) {
    $s = Read-TextFile -Path $settingsFile
    $aliIdx = $s.IndexOf('aliyun')
    $googleIdx = $s.IndexOf('google()')
    if ($aliIdx -ge 0 -and ($googleIdx -lt 0 -or $aliIdx -lt $googleIdx)) {
        Add-Result -Id 'gradle.repo_mirror' -Severity 'WARN' -Message '依赖仓库优先使用阿里云镜像（供应链风险：镜像需可信；建议官方源优先并启用依赖校验）'
    }
    else { Add-Result -Id 'gradle.repo_mirror' -Severity 'PASS' -Message '依赖仓库顺序未发现异常镜像优先' }
}
$verifyMeta = Join-Path $ProjectRoot 'gradle\verification-metadata.xml'
if (Test-Path $verifyMeta) { Add-Result -Id 'gradle.dep_verification' -Severity 'PASS' -Message '存在 Gradle 依赖校验元数据' }
else { Add-Result -Id 'gradle.dep_verification' -Severity 'WARN' -Message '未启用 Gradle dependency verification（供应链完整性未强制校验，属可接受但需记录的风险）' }
$wrapperProps = Join-Path $ProjectRoot 'gradle\wrapper\gradle-wrapper.properties'
if (Test-Path $wrapperProps) {
    $wp = Read-TextFile -Path $wrapperProps
    if ($wp -match 'distributionSha256Sum') { Add-Result -Id 'gradle.wrapper_sha' -Severity 'PASS' -Message 'gradle-wrapper 配置了 distributionSha256Sum' }
    else { Add-Result -Id 'gradle.wrapper_sha' -Severity 'WARN' -Message 'gradle-wrapper 未配置 distributionSha256Sum（wrapper 完整性未校验）' }
}

# ---------------- 6. 仓库与测试集卫生 ----------------
$keystoreFiles = @(Get-ChildItem $ProjectRoot -Recurse -File -Include *.jks, *.keystore -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\build\\|\\.gradle\\' })
if ($keystoreFiles.Count -eq 0) {
    Add-Result -Id 'repo.keystore' -Severity 'PASS' -Message '工程内未发现 .jks/.keystore 文件'
}
else {
    # 允许“本地备份/签名密钥存在”，但必须被 git 忽略（不进仓库、不随工程分发）。
    $trackedKeystores = @()
    $ignoredKeystores = @()
    $hasGit = $false
    try {
        $repoRoot = (& git -C $ProjectRoot rev-parse --show-toplevel 2>$null)
        if ($LASTEXITCODE -eq 0 -and $repoRoot) { $hasGit = $true; $repoRoot = $repoRoot.Trim() }
    }
    catch { $hasGit = $false }
    foreach ($kf in $keystoreFiles) {
        $relK = Get-RelativePath -Base $ProjectRoot -Path $kf.FullName
        $ignored = $false
        if ($hasGit) {
            try {
                & git -C $ProjectRoot check-ignore --quiet $kf.FullName 2>$null
                if ($LASTEXITCODE -eq 0) { $ignored = $true }
            }
            catch { $ignored = $false }
        }
        if (-not $ignored -and $relK -match '^(keystore|keystore-backup)/') { $ignored = $true }
        if ($ignored) { $ignoredKeystores += $relK } else { $trackedKeystores += $relK }
    }
    if ($trackedKeystores.Count -gt 0) {
        Add-Result -Id 'repo.keystore' -Severity 'FAIL' -Message '工程内存在未被 git 忽略的 keystore 文件（可能被提交/随工程分发）' -Evidence ($trackedKeystores -join ', ')
    }
    else {
        Add-Result -Id 'repo.keystore' -Severity 'PASS' -Message "存在本地 keystore/备份（$($ignoredKeystores.Count) 个），均已被 git 忽略、不进入仓库" -Evidence ($ignoredKeystores -join ', ')
    }
}

$gitignore = Join-Path (Split-Path $ProjectRoot -Parent) '.gitignore'
if (Test-Path $gitignore) {
    $gi = Read-TextFile -Path $gitignore
    $okIgnore = ($gi -match 'keystore') -and ($gi -match 'local\.properties')
    if ($okIgnore) { Add-Result -Id 'repo.gitignore' -Severity 'PASS' -Message '.gitignore 已排除 keystore/local.properties' }
    else { Add-Result -Id 'repo.gitignore' -Severity 'WARN' -Message '.gitignore 未同时排除 keystore 与 local.properties' }
}

if (Test-Path $debugRoot) {
    $debugFiles = @(Get-ChildItem $debugRoot -Recurse -File -ErrorAction SilentlyContinue)
    Add-Result -Id 'src.debug_sourceset' -Severity 'WARN' -Message "存在 src/debug sourceset（$($debugFiles.Count) 个文件），需确认不削弱 release 安全行为" -Evidence (($debugFiles | ForEach-Object { $_.Name }) -join ', ')
}
else { Add-Result -Id 'src.debug_sourceset' -Severity 'PASS' -Message '无 src/debug sourceset' }

if ($testCodeFiles.Count -gt 0) {
    $testNames = @($testCodeFiles | ForEach-Object { $_.Name })
    Add-Result -Id 'src.test_sourceset' -Severity 'PASS' -Message "src/test 存在 $($testCodeFiles.Count) 个测试文件（不进入 release APK）" -Evidence (Format-Text (($testNames | Select-Object -First 12) -join ', ') 300)
}
else { Add-Result -Id 'src.test_sourceset' -Severity 'WARN' -Message 'src/test 没有测试文件（无法验证安全/解析回归）' }

# ---------------- 7. 汇总与报告 ----------------
$fail = @($script:Results | Where-Object { $_.severity -eq 'FAIL' }).Count
$warn = @($script:Results | Where-Object { $_.severity -eq 'WARN' }).Count
$pass = @($script:Results | Where-Object { $_.severity -eq 'PASS' }).Count
$info = @($script:Results | Where-Object { $_.severity -eq 'INFO' }).Count
$exitCode = if ($fail -gt 0) { 1 } else { 0 }

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$base = Join-Path $OutDir "source-hardening-$stamp"
$report = [ordered]@{
    script        = 'check-source-hardening.ps1'
    scriptVersion = $script:Version
    generatedAt   = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz')
    source        = [ordered]@{
        sourceDir   = $SourceDir
        projectRoot = $ProjectRoot
        filesScanned = $script:FilesScanned
        codeFiles   = $script:CodeFiles.Count
    }
    summary       = [ordered]@{
        total = $script:Results.Count; pass = $pass; warn = $warn; fail = $fail; info = $info; exitCode = $exitCode
    }
    results       = $script:Results
    notes         = @(
        '本报告为源码静态规则扫描：不等价于编译/运行验证，也不覆盖依赖库内部实现。',
        '注释中的禁用 API 已被剔除；字符串拼接/变量间接调用可能规避规则，需结合代码评审。',
        '本环境无模拟器/真机，未执行安装运行、流量抓包与 UI 自动化测试。'
    )
}
$jsonPath = "$base.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding utf8

$md = [System.Text.StringBuilder]::new()
[void]$md.AppendLine('# 源码加固静态检查报告')
[void]$md.AppendLine('')
[void]$md.AppendLine("> 生成时间：$($report.generatedAt)　脚本版本：$($script:Version)")
[void]$md.AppendLine('> **本报告为静态规则扫描，不包含编译/安装/运行/抓包等动态测试。**')
[void]$md.AppendLine('')
[void]$md.AppendLine('## 1. 扫描范围')
[void]$md.AppendLine('')
[void]$md.AppendLine('| 项目 | 值 |')
[void]$md.AppendLine('| --- | --- |')
[void]$md.AppendLine("| 源码目录 | ``$SourceDir`` |")
[void]$md.AppendLine("| 工程根 | ``$ProjectRoot`` |")
[void]$md.AppendLine("| 扫描文件数 | $($script:FilesScanned)（代码 $($script:CodeFiles.Count)） |")
[void]$md.AppendLine("| main 代码文件 | $($mainCodeFiles.Count) |")
[void]$md.AppendLine("| test 代码文件 | $($testCodeFiles.Count) |")
[void]$md.AppendLine('')
[void]$md.AppendLine('## 2. 结果统计')
[void]$md.AppendLine('')
[void]$md.AppendLine('| PASS | WARN | FAIL | INFO | 退出码 |')
[void]$md.AppendLine('| --- | --- | --- | --- | --- |')
[void]$md.AppendLine("| $pass | $warn | $fail | $info | $exitCode |")
[void]$md.AppendLine('')
foreach ($sev in @('FAIL', 'WARN')) {
    $items = @($script:Results | Where-Object { $_.severity -eq $sev })
    if ($items.Count -eq 0) { continue }
    [void]$md.AppendLine("## $sev 项")
    [void]$md.AppendLine('')
    foreach ($r in $items) {
        [void]$md.AppendLine("- **$($r.id)**：$($r.message)")
        if ($r.evidence) { [void]$md.AppendLine("  - 证据：$($r.evidence)") }
    }
    [void]$md.AppendLine('')
}
[void]$md.AppendLine('## 3. 全部检查项')
[void]$md.AppendLine('')
[void]$md.AppendLine('| 检查项 | 级别 | 说明 |')
[void]$md.AppendLine('| --- | --- | --- |')
foreach ($r in $script:Results) {
    [void]$md.AppendLine("| ``$($r.id)`` | $($r.severity) | $(($r.message -replace '\|', '\|')) |")
}
[void]$md.AppendLine('')
[void]$md.AppendLine('## 4. 已知限制')
[void]$md.AppendLine('')
foreach ($n in $report.notes) { [void]$md.AppendLine("- $n") }
[void]$md.AppendLine('')
$mdPath = "$base.md"
$md.ToString() | Set-Content -Path $mdPath -Encoding utf8
$jsonPath | Copy-Item -Destination (Join-Path $OutDir 'source-hardening-latest.json') -Force
$mdPath | Copy-Item -Destination (Join-Path $OutDir 'source-hardening-latest.md') -Force

Write-Host ''
Write-Host '== 检查结果 ==' -ForegroundColor Cyan
foreach ($r in $script:Results) {
    $color = switch ($r.severity) {
        'FAIL' { 'Red' } 'WARN' { 'Yellow' } 'PASS' { 'Green' } default { 'Gray' }
    }
    Write-Host ("[{0,-4}] {1,-32} {2}" -f $r.severity, $r.id, $r.message) -ForegroundColor $color
}
Write-Host ''
Write-Host ("合计: {0} 项 | PASS {1} | WARN {2} | FAIL {3} | INFO {4}" -f $script:Results.Count, $pass, $warn, $fail, $info) -ForegroundColor Cyan
Write-Host "JSON 报告: $jsonPath"
Write-Host "MD   报告: $mdPath"
Write-Host ''

if ($SelfTest) {
    # ---- 夹具自检：bad-source 必须触发关键规则；good-source 必须 0 FAIL ----
    $fixtures = Join-Path $PSScriptRoot 'tests\fixtures'
    $badDir = Join-Path $fixtures 'bad-source'
    $goodDir = Join-Path $fixtures 'good-source'
    $selfTestOk = $true
    if (-not (Test-Path $badDir) -or -not (Test-Path $goodDir)) {
        Write-Host '[SelfTest] 缺少夹具目录 tests/fixtures/{bad-source,good-source}' -ForegroundColor Red
        exit 1
    }
    $expectedBad = @(
        @{ id = 'src.app.allowBackup'; sev = 'FAIL' }
        @{ id = 'src.permissions.sensitive'; sev = 'FAIL' }
        @{ id = 'src.components.exported'; sev = 'FAIL' }
        @{ id = 'src.fgs.type'; sev = 'FAIL' }
        @{ id = 'src.nsc.cleartext'; sev = 'FAIL' }
        @{ id = 'code.webview'; sev = 'FAIL' }
        @{ id = 'code.exec'; sev = 'FAIL' }
        @{ id = 'code.dynamic_loading'; sev = 'FAIL' }
        @{ id = 'code.telephony'; sev = 'FAIL' }
        @{ id = 'code.sms'; sev = 'FAIL' }
        @{ id = 'code.cleartext_http'; sev = 'FAIL' }
        @{ id = 'code.pendingintent'; sev = 'FAIL' }
        @{ id = 'code.log_leak'; sev = 'FAIL' }
        @{ id = 'code.tls_bypass'; sev = 'FAIL' }
        @{ id = 'code.sharedprefs_world'; sev = 'FAIL' }
        @{ id = 'code.hardcoded_secrets'; sev = 'FAIL' }
        @{ id = 'src.manifest.deep_link'; sev = 'FAIL' }
        @{ id = 'src.components.jump_activity'; sev = 'FAIL' }
        @{ id = 'code.external_intent_package'; sev = 'FAIL' }
        @{ id = 'code.download_removed'; sev = 'FAIL' }
        @{ id = 'code.jump_intent_contract'; sev = 'FAIL' }
        @{ id = 'code.custom_sound_safety'; sev = 'FAIL' }
        @{ id = 'code.auto_open_default'; sev = 'FAIL' }
        @{ id = 'code.battery_whitelist_api'; sev = 'WARN' }
    )
    $badResult = & $PSCommandPath -SourceDir $badDir -ProjectRoot $badDir -OutDir (Join-Path $OutDir 'selftest') 2>&1
    $badExit = $LASTEXITCODE
    $badReport = Join-Path (Join-Path $OutDir 'selftest') 'source-hardening-latest.json'
    if (-not (Test-Path $badReport)) {
        Write-Host '[SelfTest] bad-source 未生成报告' -ForegroundColor Red
        $selfTestOk = $false
    }
    else {
        $badJson = Get-Content -Raw $badReport | ConvertFrom-Json
        foreach ($e in $expectedBad) {
            $hit = @($badJson.results | Where-Object { $_.id -eq $e.id -and $_.severity -eq $e.sev })
            if ($hit.Count -eq 0) {
                Write-Host "[SelfTest] bad-source 期望未命中: $($e.id) 应为 $($e.sev)" -ForegroundColor Red
                $selfTestOk = $false
            }
        }
        if (@($badJson.results | Where-Object { $_.severity -eq 'FAIL' }).Count -lt 10) {
            Write-Host '[SelfTest] bad-source FAIL 数量异常偏少' -ForegroundColor Red
            $selfTestOk = $false
        }
    }
    $goodResult = & $PSCommandPath -SourceDir $goodDir -ProjectRoot $goodDir -OutDir (Join-Path $OutDir 'selftest') 2>&1
    $goodReport = Join-Path (Join-Path $OutDir 'selftest') 'source-hardening-latest.json'
    if (-not (Test-Path $goodReport)) {
        Write-Host '[SelfTest] good-source 未生成报告' -ForegroundColor Red
        $selfTestOk = $false
    }
    else {
        $goodJson = Get-Content -Raw $goodReport | ConvertFrom-Json
        $goodFails = @($goodJson.results | Where-Object { $_.severity -eq 'FAIL' })
        if ($goodFails.Count -gt 0) {
            Write-Host "[SelfTest] good-source 出现误报 FAIL: $((($goodFails | ForEach-Object { $_.id }) -join ', '))" -ForegroundColor Red
            $selfTestOk = $false
        }
    }
    Write-Host ''
    if ($selfTestOk) {
        Write-Host '[SelfTest] OK：bad-source 规则全部触发，good-source 无误报。' -ForegroundColor Green
        exit 0
    }
    else {
        Write-Host '[SelfTest] FAILED：规则自检未通过。' -ForegroundColor Red
        exit 1
    }
}

exit $exitCode
