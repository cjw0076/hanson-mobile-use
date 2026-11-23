# Hanson Mobile-Use — One-Shot ADB Installer, Permission Grantor & Bridge Forwarder
param(
    [string]$DeviceSerial = $env:ANDROID_SERIAL,
    [string]$ApkPath = "",
    [string]$PackageName = ""
)

$ErrorActionPreference = "Continue"

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

# Dynamically resolve target package (supports both com.hanson.mobileuse and com.hanson.mobileuse.debug)
$pkg = $PackageName.Trim()
if (-not $pkg) {
    if ($ApkPath -and ([System.IO.Path]::GetFileName($ApkPath) -match "debug")) {
        $pkg = "com.hanson.mobileuse.debug"
    } elseif ($ApkPath -and ([System.IO.Path]::GetFileName($ApkPath) -match "release")) {
        $pkg = "com.hanson.mobileuse"
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

# Verify/detect installed Hanson package on device when not explicitly forced
$installedPkgs = (& $adb @adbArgsPrefix shell pm list packages com.hanson.mobileuse | Out-String) -split "`r?`n" |
    ForEach-Object { $_.Trim() -replace "^package:", "" } |
    Where-Object { $_ -eq "com.hanson.mobileuse.debug" -or $_ -eq "com.hanson.mobileuse" }

if (-not $pkg) {
    if ($installedPkgs -contains "com.hanson.mobileuse.debug") {
        $pkg = "com.hanson.mobileuse.debug"
    } else {
        $pkg = "com.hanson.mobileuse"
    }
} elseif (-not $PackageName -and ($installedPkgs.Count -gt 0) -and ($installedPkgs -notcontains $pkg)) {
    $pkg = $installedPkgs[0]
}

Write-Host "   Target package: $pkg" -ForegroundColor Gray

Write-Host "3. Granting Special AppOps (Overlay HUD, Write Settings, Usage Stats)..." -ForegroundColor Cyan
& $adb @adbArgsPrefix shell appops set $pkg SYSTEM_ALERT_WINDOW allow
& $adb @adbArgsPrefix shell appops set $pkg WRITE_SETTINGS allow
& $adb @adbArgsPrefix shell appops set $pkg GET_USAGE_STATS allow
& $adb @adbArgsPrefix shell dumpsys deviceidle whitelist +$pkg

Write-Host "4. Enabling HansonAccessibilityService (append + dedupe) & HansonNotificationListenerService..." -ForegroundColor Cyan
$targetA11yService = "$pkg/com.hanson.mobileuse.accessibility.HansonAccessibilityService"
$existingRaw = (& $adb @adbArgsPrefix shell settings get secure enabled_accessibility_services | Out-String).Trim()
$serviceList = New-Object System.Collections.Generic.List[string]
if ($existingRaw -and $existingRaw -ne "null") {
    foreach ($entry in ($existingRaw -split ":")) {
        $clean = $entry.Trim()
        if ($clean -and $clean -ne "null" -and -not $serviceList.Contains($clean)) {
            $serviceList.Add($clean)
        }
    }
}
if (-not $serviceList.Contains($targetA11yService)) {
    $serviceList.Add($targetA11yService)
}
$mergedServices = ($serviceList -join ":")
& $adb @adbArgsPrefix shell settings put secure enabled_accessibility_services $mergedServices
& $adb @adbArgsPrefix shell settings put secure accessibility_enabled 1
& $adb @adbArgsPrefix shell cmd notification allow_listener "$pkg/com.hanson.mobileuse.notification.HansonNotificationListenerService"

Write-Host "5. Setting up MCP Loopback (:18888) & Laptop CLI Subscription Bridge (:18889)..." -ForegroundColor Cyan
& $adb @adbArgsPrefix forward tcp:18888 tcp:18888
& $adb @adbArgsPrefix reverse tcp:18889 tcp:18889

Write-Host "6. Launching Hanson Mobile-Use ($pkg)..." -ForegroundColor Green
& $adb @adbArgsPrefix shell am start -n "$pkg/com.hanson.mobileuse.ui.MainActivity"
