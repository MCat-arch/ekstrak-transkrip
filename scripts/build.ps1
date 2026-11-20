param(
    [switch]$Release,
    [switch]$Install,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

# Override with env vars if your toolchain lives elsewhere.
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "D:\android-sdk" }
if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "D:\gradle-home" }

$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"

if ($Clean) {
    & .\gradlew.bat clean
}

$task = if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }
& .\gradlew.bat $task
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = if ($Release) {
    "app\build\outputs\apk\release\app-release.apk"
} else {
    "app\build\outputs\apk\debug\app-debug.apk"
}

Write-Host ""
Write-Host "APK: $apk" -ForegroundColor Green

if ($Install) {
    if (-not (Test-Path $adb)) { throw "adb tidak ada di $adb" }
    $devices = & $adb devices | Select-String -Pattern "^\S+\s+device$"
    if (-not $devices) { throw "Tidak ada device terhubung (adb devices kosong)" }
    & $adb install -r $apk
}
