# Hanson x Google Artemis Mobile-Use AI — One-Shot ADB Injector & Full Permission Grantor
$ErrorActionPreference = "Continue"
$adb = "C:\Users\SAMSUNG\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apk = "$PSScriptRoot\hanson-mobile-use-v1.0.0-release.apk"
$pkg = "com.hanson.mobileuse"

Write-Host "1. Checking connected ADB devices..." -ForegroundColor Cyan
& $adb wait-for-device
& $adb devices -l

Write-Host "2. Installing signed APK ($apk)..." -ForegroundColor Cyan
& $adb install -r -g $apk

Write-Host "3. Granting Special AppOps (Overlay HUD, Write Settings, Usage Stats)..." -ForegroundColor Cyan
& $adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
& $adb shell appops set $pkg WRITE_SETTINGS allow
& $adb shell appops set $pkg GET_USAGE_STATS allow
& $adb shell dumpsys deviceidle whitelist +$pkg

Write-Host "4. Enabling Tier 1 HansonAccessibilityService & Tier 2 NotificationListenerService..." -ForegroundColor Cyan
& $adb shell settings put secure enabled_accessibility_services "$pkg/com.hanson.mobileuse.accessibility.HansonAccessibilityService"
& $adb shell settings put secure accessibility_enabled 1
& $adb shell cmd notification allow_listener "$pkg/com.hanson.mobileuse.notification.HansonNotificationListenerService"

Write-Host "5. Setting up Google Artemis Loopback Bridge (:18888) port forward..." -ForegroundColor Cyan
& $adb forward tcp:18888 tcp:18888

Write-Host "6. Launching Hanson Mobile-Use AI..." -ForegroundColor Green
& $adb shell am start -n "$pkg/com.hanson.mobileuse.ui.MainActivity"
