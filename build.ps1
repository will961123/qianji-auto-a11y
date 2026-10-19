﻿param(
    [switch]$Install,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# 工具链：优先用仓库内的 tools/，没有就退回环境变量（README 有说明）
$localJdk = Join-Path $root 'tools\jdk21'
$localSdk = Join-Path $root 'tools\android-sdk'
$localGradle = Join-Path $root 'tools\gradle-8.9\bin\gradle.bat'

$jdk = if (Test-Path $localJdk) { $localJdk } else { $env:JAVA_HOME }
$sdk = if (Test-Path $localSdk) { $localSdk } else { $env:ANDROID_HOME }
$gradle = if (Test-Path $localGradle) { $localGradle } else { 'gradle' }

if (-not $jdk -or -not (Test-Path $jdk)) {
    throw "找不到 JDK。请放到 tools\jdk21，或设置 JAVA_HOME。"
}
if (-not $sdk -or -not (Test-Path $sdk)) {
    throw "找不到 Android SDK。请放到 tools\android-sdk，或设置 ANDROID_HOME。"
}

$env:JAVA_HOME        = $jdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = Join-Path $root 'build\android-user'
$env:GRADLE_USER_HOME  = Join-Path $root 'build\gradle-home'
$env:TEMP              = Join-Path $root 'build\tmp'
$env:TMP               = $env:TEMP
$env:GRADLE_OPTS       = "-Djava.io.tmpdir=$($env:TEMP)"

New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

if ($Clean) {
    & $gradle -p "$root" --no-daemon --console=plain clean
}

# -p 指定工程根目录：gradle 默认用当前工作目录，从别处调用会构建到错误的项目
& $gradle -p "$root" --no-daemon --console=plain :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host "APK: $apk"

if ($Install) {
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { $adb = 'adb' }
    & $adb devices
    & $adb install -r $apk
    Write-Host "安装完成后需要在「系统设置 → 无障碍」里重新开启本服务（MIUI 会在覆盖安装后关闭它）。"
}
