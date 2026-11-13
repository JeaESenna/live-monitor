param(
    [Parameter(Mandatory = $true, HelpMessage = 'AGP 生成的 unsigned release APK 路径')]
    [string]$UnsignedApk,

    [string]$Keystore = '',
    [string]$KeyAlias = '',
    [string]$OutApk = ''
)

# 正式发布签名辅助脚本。
#
# 本仓库不会提交任何 keystore 或口令。请先自行生成发布密钥（在 android/ 目录执行）：
#
#   keytool -genkeypair -v -keystore keystore/livemonitor-release.jks `
#     -storetype PKCS12 -alias livemonitor -keyalg RSA -keysize 4096 -validity 10000 `
#     -dname "CN=<你的组织>, O=<你的组织>, C=CN"
#
# 然后把下面四项写入 android/local.properties（该文件已被 .gitignore 忽略），
# 或者用同名环境变量传入：
#   RELEASE_STORE_FILE=keystore/livemonitor-release.jks
#   RELEASE_STORE_PASSWORD=***
#   RELEASE_KEY_ALIAS=livemonitor
#   RELEASE_KEY_PASSWORD=***
#
# 用法示例：
#   pwsh -NoProfile -File scripts/sign-release.ps1 `
#     -UnsignedApk app/build/outputs/apk/release/app-release-unsigned.apk `
#     -OutApk dist/直播监测-Android-v1.0.0.apk

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$localPropsPath = Join-Path $projectRoot 'local.properties'
$localProps = @{}
if (Test-Path $localPropsPath) {
    foreach ($line in Get-Content $localPropsPath) {
        if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') {
            $localProps[$matches[1]] = $matches[2]
        }
    }
}

function Resolve-Setting([string]$name, [string]$override) {
    if ($override) { return $override }
    if ($localProps.ContainsKey($name)) { return $localProps[$name] }
    if ($env:$name) { return $env:$name }
    throw "缺少签名参数 $name：请写入 android/local.properties 或设置同名环境变量。"
}

if (-not $Keystore) { $Keystore = Resolve-Setting 'RELEASE_STORE_FILE' '' }
if ($Keystore -and -not [System.IO.Path]::IsPathRooted($Keystore)) {
    $Keystore = Join-Path $projectRoot $Keystore
}
if (-not (Test-Path $Keystore)) { throw "找不到 keystore：$Keystore" }
if (-not $KeyAlias) { $KeyAlias = Resolve-Setting 'RELEASE_KEY_ALIAS' '' }
$storePass = Resolve-Setting 'RELEASE_STORE_PASSWORD' ''
$keyPass = Resolve-Setting 'RELEASE_KEY_PASSWORD' ''
if (-not (Test-Path $UnsignedApk)) { throw "找不到 APK：$UnsignedApk" }

# 自动定位 Android SDK build-tools（取最高版本）
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$btRoot = Join-Path $sdk 'build-tools'
if (-not (Test-Path $btRoot)) { throw "找不到 build-tools：$btRoot（请设置 ANDROID_HOME）" }
$buildTools = Get-ChildItem $btRoot -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
if (-not (Test-Path $apksigner)) { throw "找不到 apksigner：$apksigner" }

if (-not $OutApk) {
    $OutApk = Join-Path $projectRoot ('dist/' + [System.IO.Path]::GetFileNameWithoutExtension($UnsignedApk) + '-signed.apk')
}
New-Item -ItemType Directory -Force (Split-Path -Parent $OutApk) | Out-Null
if ((Resolve-Path $UnsignedApk).Path -ne (Join-Path (Resolve-Path (Split-Path -Parent $OutApk)).Path (Split-Path -Leaf $OutApk))) {
    Copy-Item $UnsignedApk $OutApk -Force
}

if (Test-Path $zipalign) {
    $check = & $zipalign -c -v 4 $OutApk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK 未按 4 字节对齐，请先执行 zipalign -f 4。`n$check" }
}

$signArgs = @('sign', '--ks', $Keystore, '--ks-key-alias', $KeyAlias, '--ks-pass', "pass:$storePass", '--key-pass', "pass:$keyPass", $OutApk)
& $apksigner @signArgs
if ($LASTEXITCODE -ne 0) { throw "apksigner 签名失败（退出码 $LASTEXITCODE）" }

& $apksigner verify --verbose --print-certs $OutApk
if ($LASTEXITCODE -ne 0) { throw "签名校验失败" }

$hash = (Get-FileHash $OutApk -Algorithm SHA256).Hash.ToLowerInvariant()
$shaFile = "$OutApk.sha256"
[System.IO.File]::WriteAllText($shaFile, "$hash  $([System.IO.Path]::GetFileName($OutApk))`n")
Write-Host "已签名：$OutApk"
Write-Host "SHA-256：$hash"
Write-Host "校验文件：$shaFile"
