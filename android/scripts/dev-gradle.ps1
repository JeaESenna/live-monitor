# 本地开发构建入口：统一注入 JDK / Android SDK 路径并调用 Gradle。
# 仅用于本机开发环境；对外交付的构建方式见 android/README.md（gradlew）。
#
# 用法（所有参数原样转发给 Gradle）：
#   pwsh -NoProfile -File scripts/dev-gradle.ps1 :app:testDebugUnitTest --console=plain
#   pwsh -NoProfile -File scripts/dev-gradle.ps1 :app:assembleRelease
# 说明：脚本不声明 param 块，因此 -p / --console=plain 这类以 - 开头的参数也会被原样转发。
$ErrorActionPreference = 'Stop'

$jdkCandidate = $env:JAVA_HOME
if (-not $jdkCandidate -or -not (Test-Path (Join-Path $jdkCandidate 'bin\java.exe'))) {
    $jdkCandidate = 'D:\DevTools\jdk17'
    if (-not (Test-Path (Join-Path $jdkCandidate 'bin\java.exe'))) {
        throw '未找到 JDK 17：请设置 JAVA_HOME 或安装 JDK 17。'
    }
}
$env:JAVA_HOME = $jdkCandidate
$env:PATH = "$jdkCandidate\bin;$env:PATH"

if (-not $env:ANDROID_HOME) {
    $sdkCandidate = 'D:\DevTools\android-sdk'
    if (Test-Path $sdkCandidate) { $env:ANDROID_HOME = $sdkCandidate }
}
if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }

$scriptDir = $PSScriptRoot
$projectDir = Split-Path -Parent $scriptDir
$gradleExe = 'D:\DevTools\gradle-8.9\bin\gradle.bat'
if (-not (Test-Path $gradleExe)) {
    # 回退到 gradle wrapper（首次会下载 Gradle 发行版）
    $gradleExe = Join-Path $projectDir 'gradlew.bat'
}
if (-not (Test-Path $gradleExe)) { throw '未找到 Gradle：请安装 gradle 8.9 或使用 gradlew。' }

$gradleArguments = $args
if ($gradleArguments.Count -eq 0) {
    # 未指定任务时给一个安全的默认值，避免误触发 assembleRelease 之类的大任务
    $gradleArguments = @(':app:compileDebugKotlin')
}
& $gradleExe -p $projectDir @gradleArguments
exit $LASTEXITCODE
