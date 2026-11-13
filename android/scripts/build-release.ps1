param(
    [switch]$SkipTests,
    [switch]$SkipLint
)

# Lead/CI 使用的 release 构建 + 交付打包脚本。
# 产物：
#   android/app/build/outputs/apk/release/app-release-unsigned.apk
#   android/dist/直播监测-Android-v<versionName>-unsigned-release.apk
#   android/dist/*.sha256
#
# 注意：本环境无法生成本地签名密钥（安全策略限制），因此 release APK 为 unsigned。
# 正式分发前请按 android/README.md 的说明生成自己的 keystore 并运行 scripts/sign-release.ps1 重签。

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$distDir = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Force $distDir | Out-Null

function Invoke-Gradle([string[]]$Tasks) {
    # dev-gradle.ps1 会自动追加 -p <android 工程目录>，这里只传任务与选项。
    $gradleArgs = @('--console=plain') + $Tasks
    & (Join-Path $PSScriptRoot 'dev-gradle.ps1') @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle 任务失败：$($Tasks -join ' ')" }
}

if (-not $SkipTests) { Invoke-Gradle @(':app:testDebugUnitTest') }
if (-not $SkipLint) { Invoke-Gradle @(':app:lintRelease') }
Invoke-Gradle @(':app:assembleRelease')

$apk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
if (-not (Test-Path $apk)) {
    $apk = Get-ChildItem (Join-Path $projectRoot 'app/build/outputs/apk/release') -Filter '*.apk' |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $apk -or -not (Test-Path $apk)) { throw '未找到 release APK 产物' }

$version = (Select-String -Path (Join-Path $projectRoot 'app/build.gradle.kts') -Pattern 'versionName\s*=\s*"([^"]+)"' |
    Select-Object -First 1).Matches[0].Groups[1].Value
if (-not $version) { $version = '1.0.0' }

$target = Join-Path $distDir "直播监测-Android-v$version-unsigned-release.apk"
Copy-Item $apk $target -Force
$hash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText("$target.sha256", "$hash  $([System.IO.Path]::GetFileName($target))`n")
Write-Host "交付 APK：$target"
Write-Host "SHA-256：$hash"
