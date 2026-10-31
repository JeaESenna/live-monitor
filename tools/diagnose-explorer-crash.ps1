#requires -Version 2
<#
.SYNOPSIS
  Collects Windows evidence for the recurring "explorer.exe - 系统错误:
  系统在此应用程序中检测到基于堆栈的缓冲区溢出" crash and (optionally)
  enables crash dumps for explorer.exe.

.DESCRIPTION
  explorer.exe hosts Windows shell code plus every third-party shell extension
  DLL loaded into it.  A /GS (0xC0000409) fail-fast inside explorer.exe is
  therefore caused by code running inside explorer.exe, not by a normal managed
  application that merely talks to the shell through Shell_NotifyIcon or
  ShellExecute.  This script gathers the evidence needed to identify the culprit:
    * the exact crash / shell-restart / GDI-leak event history
    * the non-Microsoft DLLs currently loaded in explorer.exe
    * registered third-party icon-overlay / context-menu shell extensions
    * versions of common suspects (IDM, AV, cloud drives, ...)
    * Windows build / update history
  Run it, then follow the "recommended actions" printed at the end.

  With -SetupDumps (administrator required) it also configures Windows Error
  Reporting to write a full crash dump for explorer.exe into
  %LOCALAPPDATA%\LiveMonitor_ExplorerDumps.  The NEXT explorer crash will then
  produce a .dmp file that can be analyzed with WinDbg:
      * use !analyze -v, then `k` to see the faulting stack
      * the faulting module (e.g. a shell extension DLL) is the culprit

.PARAMETER SetupDumps
  Add HKLM\SOFTWARE\Microsoft\Windows\Windows Error Reporting\LocalDumps\explorer.exe
  so the next explorer.exe crash is captured (requires elevation).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\diagnose-explorer-crash.ps1
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\diagnose-explorer-crash.ps1 -SetupDumps
#>
param(
    [switch]$SetupDumps
)

$ErrorActionPreference = 'SilentlyContinue'

# ---- helpers -------------------------------------------------------------
try {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class LMGuiRes
{
    [DllImport("user32.dll")]
    public static extern uint GetGuiResources(IntPtr hProcess, uint uiFlags);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll")]
    public static extern bool CloseHandle(IntPtr h);
}
'@
} catch { }

function Get-GdiCount([int]$processId, [int]$flags) {
    try {
        $h = [LMGuiRes]::OpenProcess(0x1000, $false, $processId)
        if ($h -eq [IntPtr]::Zero) { return -1 }
        $n = [LMGuiRes]::GetGuiResources($h, $flags)
        [LMGuiRes]::CloseHandle($h) | Out-Null
        return $n
    } catch { return -1 }
}

function Compare-VersionText([string]$a, [string]$b) {
    # returns -1 / 0 / 1 comparing dotted/comma separated versions
    try {
        $pa = ($a -replace ',', '.') -split '\.'
        $pb = ($b -replace ',', '.') -split '\.'
        $n = [Math]::Max($pa.Count, $pb.Count)
        for ($i = 0; $i -lt $n; $i++) {
            $va = 0; $vb = 0
            if ($i -lt $pa.Count) { [void][int]::TryParse(($pa[$i] -replace '\D', ''), [ref]$va) }
            if ($i -lt $pb.Count) { [void][int]::TryParse(($pb[$i] -replace '\D', ''), [ref]$vb) }
            if ($va -lt $vb) { return -1 }
            if ($va -gt $vb) { return 1 }
        }
        return 0
    } catch { return 0 }
}

# ---- report --------------------------------------------------------------
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path ([Environment]::GetFolderPath('Desktop')) ('LiveMonitor_诊断报告_' + $stamp)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$report = Join-Path $outDir 'explorer-crash-report.txt'
$sb = New-Object System.Text.StringBuilder

function W([string]$text) {
    Write-Host $text
    [void]$sb.AppendLine($text)
}
function Section([string]$title) {
    W ''
    W ('===== ' + $title + ' =====')
}

W ('诊断时间: ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
W ('用户: ' + $env:USERNAME + '   计算机: ' + $env:COMPUTERNAME)

Section '1. 系统版本 / 启动时间'
$os = Get-CimInstance Win32_OperatingSystem
$cv = Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
W ('OS: ' + $os.Caption + ' ' + $cv.DisplayVersion + ' build ' + $cv.CurrentBuild + '.' + $cv.UBR)
W ('启动时间: ' + $os.LastBootUpTime + '   运行时长: ' + ((Get-Date) - $os.LastBootUpTime))

Section '2. explorer.exe 崩溃历史（System 日志 Event ID 26 = 弹出的系统错误框）'
$popups = Get-WinEvent -FilterHashtable @{LogName='System'; Id=26} -ErrorAction SilentlyContinue |
    Where-Object { $_.Message -match 'explorer' } | Sort-Object TimeCreated
if (-not $popups) { W '（无记录；可能日志已被清理，或从未崩溃）' }
foreach ($e in $popups) { W ($e.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss') + '  ' + $e.Message.Trim()) }

Section '3. 外壳 / explorer 重启历史（Application 日志 Winlogon 1002）'
$restarts = Get-WinEvent -FilterHashtable @{LogName='Application'; StartTime=(Get-Date).AddDays(-90)} -ErrorAction SilentlyContinue |
    Where-Object { $_.Id -eq 1002 -and $_.ProviderName -match 'Winlogon' } | Sort-Object TimeCreated
if (-not $restarts) { W '（无记录）' }
foreach ($e in $restarts) { W ($e.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss') + '  ' + $e.Message.Trim()) }

Section '4. explorer GDI 对象泄漏遥测（WER NonCritical GDIObjectLeak）'
$leaks = Get-WinEvent -FilterHashtable @{LogName='Application'; Id=1001; StartTime=(Get-Date).AddDays(-90)} -ErrorAction SilentlyContinue |
    Where-Object { $_.Message -match 'GDIObjectLeak' } | Sort-Object TimeCreated
if (-not $leaks) { W '（无记录）' }
foreach ($e in $leaks) {
    $m = $e.Message -replace "`r`n", ' | '
    W ($e.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss') + '  ' + $m.Substring(0, [Math]::Min(320, $m.Length)))
}

Section '5. explorer.exe 进程内加载的非 Windows DLL（重点：第三方 shell 扩展）'
$explorer = Get-Process explorer -ErrorAction SilentlyContinue | Select-Object -First 1
if ($explorer) {
    W ('explorer pid=' + $explorer.Id + '  启动=' + $explorer.StartTime)
    W ('  GDI对象=' + (Get-GdiCount $explorer.Id 0) + '  USER对象=' + (Get-GdiCount $explorer.Id 1) + '  句柄=' + $explorer.HandleCount)
    $explorer.Modules | Where-Object { $_.FileName -and $_.FileName -notlike 'C:\Windows\*' } |
        Select-Object ModuleName, FileName, CompanyName, FileVersion |
        Sort-Object FileName | Format-Table -AutoSize | Out-String -Width 260 | ForEach-Object { W $_ }
} else {
    W '（未找到 explorer.exe 进程?!）'
}

Section '6. 已注册的第三方图标叠加(icon overlay) shell 扩展'
$ovKey = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\ShellIconOverlayIdentifiers'
Get-ChildItem $ovKey -ErrorAction SilentlyContinue | ForEach-Object {
    $clsid = (Get-ItemProperty -LiteralPath $_.PSPath).'(default)'
    $dll = ''
    if ($clsid) {
        foreach ($base in @('HKLM:\SOFTWARE\Classes\CLSID', 'HKLM:\SOFTWARE\WOW6432Node\Classes\CLSID', 'HKCU:\SOFTWARE\Classes\CLSID')) {
            $p = Join-Path $base ($clsid + '\InprocServer32')
            $v = (Get-ItemProperty -LiteralPath $p -ErrorAction SilentlyContinue).'(default)'
            if ($v) { $dll = $v; break }
        }
    }
    if ($dll -and $dll -notlike 'C:\Windows\*') {
        W ($_.PSChildName.Trim() + '  ->  ' + $dll)
    }
}

Section '7. 已注册的第三方右键菜单(context menu) shell 扩展'
foreach ($scope in @('HKLM:\SOFTWARE\Classes\*\shellex\ContextMenuHandlers', 'HKLM:\SOFTWARE\Classes\Directory\shellex\ContextMenuHandlers', 'HKLM:\SOFTWARE\Classes\Directory\Background\shellex\ContextMenuHandlers')) {
    Get-ChildItem -LiteralPath $scope -ErrorAction SilentlyContinue | ForEach-Object {
        $clsid = (Get-ItemProperty -LiteralPath $_.PSPath).'(default)'
        if (-not $clsid) { return }
        foreach ($base in @('HKLM:\SOFTWARE\Classes\CLSID', 'HKLM:\SOFTWARE\WOW6432Node\Classes\CLSID')) {
            $p = Join-Path $base ($clsid + '\InprocServer32')
            $v = (Get-ItemProperty -LiteralPath $p -ErrorAction SilentlyContinue).'(default)'
            if ($v -and $v -notlike 'C:\Windows\*') {
                W ($_.PSChildName + '  ->  ' + $v)
                break
            }
        }
    }
}

Section '8. 常见嫌疑软件版本'
$idmExe = 'C:\Program Files (x86)\Internet Download Manager\IDMan.exe'
$idmDll = 'C:\Program Files (x86)\Internet Download Manager\IDMShellExt64.dll'
$idmVer = ''
if (Test-Path $idmExe) {
    $idmVer = (Get-Item $idmExe).VersionInfo.FileVersion
    W ('Internet Download Manager (IDMan.exe): ' + $idmVer)
    if (Compare-VersionText $idmVer '6.43.5.0' -lt 0) {
        W '  ★ 该版本早于 6.43 Build 5 —— IDM 官方更新日志明确写明 6.43 Build 5'
        W '    修复了“有时导致 Windows 资源管理器崩溃”的 bug，强烈建议升级到 6.43 Build 5 或更新版本。'
    } else {
        W '  （版本已包含官方 explorer 崩溃修复；若仍崩溃请继续排查其他 shell 扩展）'
    }
} else {
    W 'Internet Download Manager: 未在默认路径找到'
}
if (Test-Path $idmDll) {
    $idmDllInfo = Get-Item $idmDll
    W ('  IDMShellExt64.dll: ' + $idmDllInfo.VersionInfo.FileVersion + '  文件时间=' + $idmDllInfo.LastWriteTime)
}

$others = @(
    @{ Name = '火绒 Huorong'; Path = 'D:\Program Files\Huorong\Sysdiag\bin\HipsMain.exe'; Hint = '保持最新版本；可用 Autoruns 临时关闭其 shell 扩展做对照测试' },
    @{ Name = '百度网盘'; Path = 'D:\BaiduYun\BaiduNetdisk\BaiduNetdisk.exe'; Hint = '升级到最新版本' },
    @{ Name = 'Bandizip'; Path = 'C:\Program Files\Bandizip\Bandizip.exe'; Hint = '升级到最新版本' },
    @{ Name = 'Adobe Acrobat'; Path = 'C:\Program Files\Adobe\Acrobat DC\Acrobat\Acrobat.exe'; Hint = '升级到最新版本' }
)
foreach ($c in $others) {
    $ver = ''
    if ($c.Path -and (Test-Path $c.Path)) { $ver = (Get-Item $c.Path).VersionInfo.FileVersion }
    W ($c.Name + ': ' + $(if ($ver) { $ver } else { '(未在预期路径找到)' }) + '   建议: ' + $c.Hint)
}
$qqShell = Get-ChildItem 'D:\Program Files\Tencent\QQNT\qq_shell_extension_64.dll', 'C:\Program Files\Tencent\QQNT\qq_shell_extension_64.dll' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($qqShell) { W ('腾讯QQ shell 扩展: ' + $qqShell.FullName + '  ' + $qqShell.VersionInfo.FileVersion + '   建议: 升级 QQ 最新版') }

Section '9. 最近的 Windows 更新'
$hotfix = Get-HotFix -ErrorAction SilentlyContinue | Sort-Object InstalledOn -Descending | Select-Object -First 8
if (-not $hotfix) { W '（无记录）' }
foreach ($h in $hotfix) { W ($h.HotFixID + '  ' + $h.Description + '  ' + $h.InstalledOn) }

Section '10. 崩溃转储配置'
$dumpKey = 'HKLM:\SOFTWARE\Microsoft\Windows\Windows Error Reporting\LocalDumps\explorer.exe'
if (Test-Path $dumpKey) {
    $dp = Get-ItemProperty $dumpKey
    W ('已配置: DumpFolder=' + $dp.DumpFolder + '  DumpType=' + $dp.DumpType + '  DumpCount=' + $dp.DumpCount)
} else {
    W '未配置 explorer.exe 本地崩溃转储。可用本脚本的 -SetupDumps 参数（需管理员权限）开启，'
    W '下次崩溃会生成 .dmp 文件用于定位具体 shell 扩展。'
}

# ---- optional: enable WER local dumps for explorer.exe ----
if ($SetupDumps) {
    Section '11. 开启 explorer.exe 崩溃转储'
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) {
        W '需要管理员权限：请右键“以管理员身份运行” PowerShell 后重新执行 -SetupDumps。'
    } else {
        $dumpFolder = Join-Path $env:LOCALAPPDATA 'LiveMonitor_ExplorerDumps'
        New-Item -ItemType Directory -Force -Path $dumpFolder | Out-Null
        New-Item -Path $dumpKey -Force | Out-Null
        Set-ItemProperty -Path $dumpKey -Name 'DumpFolder' -Value $dumpFolder -Type ExpandString
        Set-ItemProperty -Path $dumpKey -Name 'DumpType' -Value 2 -Type DWord
        Set-ItemProperty -Path $dumpKey -Name 'DumpCount' -Value 5 -Type DWord
        W ('已开启: ' + $dumpKey)
        W ('转储目录: ' + $dumpFolder)
        W '下次 explorer.exe 崩溃后会生成 .dmp 文件。请用 WinDbg 打开并执行 !analyze -v 和 k，'
        W '故障栈顶的非 Microsoft DLL（例如某个 shell 扩展）就是崩溃元凶。'
    }
}

W ''
W '建议按以下顺序处理（从最可能、最省事开始）：'
W '  1) 升级 IDM 到 6.43 Build 5+（该版本专门修复了“偶尔导致 Windows 资源管理器崩溃”的 bug）；'
W '     若暂时不想升级，可先用 Autoruns 禁用 IDM 的 shell 扩展（IDMShellExt64.dll）做对照测试。'
W '  2) 升级火绒、QQ、百度网盘、Bandizip、Adobe 等带 shell 扩展的软件到最新版。'
W '  3) 用管理员 PowerShell 执行: sfc /scannow 和 DISM /Online /Cleanup-Image /RestoreHealth。'
W '  4) 若仍复现：用 msconfig/Autoruns 做干净启动对照（只保留微软服务），确认是哪个第三方组件后卸载/更新它。'
W '  5) 若需要明确证据：以管理员身份重新运行本脚本并加 -SetupDumps，等下次崩溃后用 WinDbg 分析转储。'
W ''
W ('报告已保存: ' + $report)

try { [System.IO.File]::WriteAllText($report, $sb.ToString(), (New-Object System.Text.UTF8Encoding($true))) } catch { }
