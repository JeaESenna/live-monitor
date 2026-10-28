# Build script: generates icon + chime, compiles the WinForms app (GUI),
# the native boot launcher, and a console self-test with the built-in
# .NET Framework compiler (csc.exe).
#
# NOTE: this shell mangles the '+' operator inside array literals, so all
# arguments are assembled line-by-line into a csc response file (ASCII),
# then the temp exe is renamed to the Chinese name (ZhiBoJianCe).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$resDir = Join-Path $root 'res'
$srcDir = Join-Path $root 'src'
$testDir = Join-Path $root 'test'
$distDir = Join-Path $root 'dist'

& (Join-Path $PSScriptRoot 'make_icon.ps1')
& (Join-Path $PSScriptRoot 'make_chime.ps1')

New-Item -ItemType Directory -Force -Path $distDir | Out-Null

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }

$cnName = -join ([char]0x76F4, [char]0x64AD, [char]0x76D1, [char]0x6D4B)   # 直播监测
$tmpExe = Join-Path $distDir 'LiveMonitor.exe'
$finalExe = Join-Path $distDir ($cnName + '.exe')

function Invoke-Csc([string]$rspPath) {
    $arg = '@' + $rspPath
    & $csc $arg
    if ($LASTEXITCODE -ne 0) { throw "csc failed (exit $LASTEXITCODE) for $rspPath" }
}

# ---- main GUI app (excludes BootStarter.cs if present) ----
$srcList = Get-ChildItem -Path $srcDir -Filter *.cs | Sort-Object Name | Where-Object { (Split-Path -Leaf $_.FullName) -ne 'BootStarter.cs' }

$lines = @()
$lines += '/target:winexe'
$lines += '/codepage:65001'
$lines += '/platform:anycpu'
$lines += '/optimize+'
$lines += "/out:$tmpExe"
$lines += "/win32icon:$resDir\app.ico"
$lines += "/win32manifest:$resDir\app.manifest"
$lines += "/resource:$resDir\app.ico,app.ico"
$lines += "/resource:$resDir\app_preview.png,app.png"
$lines += "/resource:$resDir\chime.wav,chime.wav"
$lines += '/r:System.Net.Http.dll'
foreach ($f in $srcList) { $lines += $f.FullName }
$appRsp = Join-Path $distDir 'build_app.rsp'
Set-Content -Path $appRsp -Value $lines -Encoding Ascii
Invoke-Csc $appRsp
Write-Output 'main app compiled'

if (Test-Path $finalExe) { Remove-Item $finalExe -Force }
Rename-Item -Path $tmpExe -NewName ($cnName + '.exe')
Write-Output ("built: " + $finalExe)

# ---- console self-test ----
$lines = @()
$lines += '/target:exe'
$lines += '/codepage:65001'
$lines += '/platform:anycpu'
$lines += '/optimize+'
$lines += "/out:" + (Join-Path $distDir 'LiveMonitorTest.exe')
$lines += "/resource:$resDir\app.ico,app.ico"
$lines += "/resource:$resDir\app_preview.png,app.png"
$lines += "/resource:$resDir\chime.wav,chime.wav"
$lines += '/r:System.Net.Http.dll'
foreach ($f in $srcList) {
    if ((Split-Path -Leaf $f.FullName) -ne 'Program.cs') { $lines += $f.FullName }
}
$lines += (Join-Path $testDir 'TestProgram.cs')
$testRsp = Join-Path $distDir 'build_test.rsp'
Set-Content -Path $testRsp -Value $lines -Encoding Ascii
Invoke-Csc $testRsp
Write-Output 'self-test compiled'
