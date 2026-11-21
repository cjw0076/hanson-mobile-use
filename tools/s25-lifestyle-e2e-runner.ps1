# Hanson Mobile-Use — Physical Android Device Verification Harness
param (
    [string]$DeviceSerial = $env:ANDROID_SERIAL,
    [string]$AdbPath = "",
    [string]$PackageName = "com.hanson.mobileuse",
    [string]$ScreenshotOut = "$PSScriptRoot\device_verification.png"
)

$ErrorActionPreference = "Continue"

if (-not $AdbPath) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) {
        $AdbPath = $adbCmd.Source
    } elseif ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) {
        $AdbPath = "$env:ANDROID_HOME\platform-tools\adb.exe"
    } elseif ($env:LOCALAPPDATA -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
        $AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    } else {
        $AdbPath = "adb"
    }
}

$serialArgs = if ($DeviceSerial) { @("-s", $DeviceSerial) } else { @() }
$targetLabel = if ($DeviceSerial) { $DeviceSerial } else { "(default connected device)" }

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " [HANSON MOBILE-USE] PHYSICAL DEVICE VERIFICATION HARNESS" -ForegroundColor Cyan
Write-Host " Target Device: $targetLabel | Package: $PackageName" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Check ADB connection
Write-Host "`n[Step 1/6] Verifying ADB connection to $targetLabel..." -ForegroundColor Yellow
$deviceState = (& $AdbPath @serialArgs get-state 2>&1).Trim()
if ($deviceState -ne "device") {
    Write-Warning "Device $targetLabel is not currently authorized or connected ($deviceState). Checking adb devices..."
    & $AdbPath devices -l
} else {
    Write-Host "  -> Device $targetLabel is CONNECTED and ONLINE." -ForegroundColor Green
}

# 2. Query Display Metrics
Write-Host "`n[Step 2/6] Querying physical display metrics & density..." -ForegroundColor Yellow
$wmSize = & $AdbPath @serialArgs shell wm size 2>&1
$wmDensity = & $AdbPath @serialArgs shell wm density 2>&1
Write-Host "  -> Resolution: $wmSize" -ForegroundColor Gray
Write-Host "  -> Density:    $wmDensity" -ForegroundColor Gray

# 3. Check App Installation
Write-Host "`n[Step 3/6] Checking package installation for $PackageName..." -ForegroundColor Yellow
$pkgCheck = & $AdbPath @serialArgs shell pm path $PackageName 2>&1
if ($pkgCheck -match "package:") {
    Write-Host "  -> Package installed: $pkgCheck" -ForegroundColor Green
} else {
    Write-Warning "  -> Package $PackageName not yet installed on device."
}

# 4. Launch Activity
$activityComponent = if ($PackageName.EndsWith(".debug")) { "$PackageName/com.hanson.mobileuse.ui.MainActivity" } else { "$PackageName/.ui.MainActivity" }
$launchRes = & $AdbPath @serialArgs shell am start -n $activityComponent 2>&1
Write-Host "  -> $launchRes" -ForegroundColor Gray
Start-Sleep -Seconds 2

# 5. UI Dump & Developer Jargon Scan
Write-Host "`n[Step 5/6] Dumping UI hierarchy and verifying Jargon Elimination..." -ForegroundColor Yellow
& $AdbPath @serialArgs shell uiautomator dump /sdcard/ui_dump.xml | Out-Null
$uiDump = & $AdbPath @serialArgs shell cat /sdcard/ui_dump.xml 2>&1

$bannedJargon = @(
    "RAW·S25-NPU-NA",
    "120KB · 2399V/1404T",
    "SOLID / WIRE / PTS",
    "MEM 45M·2기억",
    "YOUTUBE IN-APP"
)

$violations = 0
foreach ($jargon in $bannedJargon) {
    if ($uiDump -match [regex]::Escape($jargon)) {
        Write-Host "  [FAIL] Found banned developer jargon: '$jargon'" -ForegroundColor Red
        $violations++
    }
}

if ($violations -eq 0) {
    Write-Host "  [PASS] Zero developer badges found on active viewport! Clean editorial UI." -ForegroundColor Green
} else {
    Write-Warning "  [WARNING] $violations developer badges detected in current UI hierarchy."
}

# 6. Capture Verification Screenshot
Write-Host "`n[Step 6/6] Capturing verification screenshot..." -ForegroundColor Yellow
& $AdbPath @serialArgs shell screencap -p /sdcard/hanson_device_screenshot.png
& $AdbPath @serialArgs pull /sdcard/hanson_device_screenshot.png $ScreenshotOut 2>&1 | Out-Null

if (Test-Path $ScreenshotOut) {
    $imgSize = (Get-Item $ScreenshotOut).Length
    Write-Host "  -> Screenshot successfully captured to $ScreenshotOut ($imgSize bytes)" -ForegroundColor Green
} else {
    Write-Warning "  -> Screenshot pull failed or file missing."
}

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host " Physical Device Verification Completed." -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
