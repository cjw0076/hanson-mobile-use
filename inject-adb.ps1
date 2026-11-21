# Hanson Mobile-Use — One-Shot ADB Installer, Permission Grantor & Bridge Forwarder
param(
    [string]$DeviceSerial = $env:ANDROID_SERIAL,
    [string]$ApkPath = ""
)

$ErrorActionPreference = "Continue"
$pkg = "com.hanson.mobileuse"

$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
$adb = if ($adbCmd) {
    $adbCmd.Source
} elseif ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) {
    "$env:ANDROID_HOME\platform-tools\adb.exe"
} elseif ($env:LOCALAPPDATA -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
} else {
    "adb"
}

$adbArgsPrefix = if ($DeviceSerial) { @("-s", $DeviceSerial) } else { @() }

if (-not $ApkPath) {
    $debugApk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
    $releaseApk = Get-ChildItem -Path $PSScriptRoot -Filter "hanson-mobile-use*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (Test-Path $debugApk) {
        $ApkPath = $debugApk
    } elseif ($releaseApk) {
        $ApkPath = $releaseApk.FullName
    }
}

Write-Host "1. Checking connected ADB devices..." -ForegroundColor Cyan
& $adb @adbArgsPrefix wait-for-device
& $adb devices -l

if ($ApkPath -and (Test-Path $ApkPath)) {
    Write-Host "2. Installing APK ($ApkPath)..." -ForegroundColor Cyan
    & $adb @adbArgsPrefix install -r -t -g $ApkPath
} else {
    Write-Warning "2. No local APK found yet (run .\gradlew.bat assembleDebug first, or pass -ApkPath)."
}

Write-Host "3. Granting Special AppOps (Overlay HUD, Write Settings, Usage Stats)..." -ForegroundColor Cyan
& $adb @adbArgsPrefix shell appops set $pkg SYSTEM_ALERT_WINDOW allow
& $adb @adbArgsPrefix shell appops set $pkg WRITE_SETTINGS allow
& $adb @adbArgsPrefix shell appops set $pkg GET_USAGE_STATS allow
& $adb @adbArgsPrefix shell dumpsys deviceidle whitelist +$pkg

Write-Host "4. Enabling HansonAccessibilityService & HansonNotificationListenerService..." -ForegroundColor Cyan
& $adb @adbArgsPrefix shell settings put secure enabled_accessibility_services "$pkg/com.hanson.mobileuse.accessibility.HansonAccessibilityService"
& $adb @adbArgsPrefix shell settings put secure accessibility_enabled 1
& $adb @adbArgsPrefix shell cmd notification allow_listener "$pkg/com.hanson.mobileuse.notification.HansonNotificationListenerService"

Write-Host "5. Setting up MCP Loopback (:18888) & Laptop CLI Subscription Bridge (:18889)..." -ForegroundColor Cyan
& $adb @adbArgsPrefix forward tcp:18888 tcp:18888
& $adb @adbArgsPrefix reverse tcp:18889 tcp:18889

Write-Host "6. Launching Hanson Mobile-Use..." -ForegroundColor Green
& $adb @adbArgsPrefix shell am start -n "$pkg/com.hanson.mobileuse.ui.MainActivity"
