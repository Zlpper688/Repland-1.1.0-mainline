# Repland instrumented-test runner with logical groups, style-aligned with
# scripts/run-e2e-loop.ps1 (same env paths, emulator bootstrap and result parsing).
# Groups:
#   fast  - curated core-loop classes (same set as run-e2e-loop.ps1 steps)
#   slow  - full connectedDebugAndroidTest run (no class filter)
#   step  - explicit classes via -Classes (comma-separated FQCN, supports Class#method)
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\run-instrumented.ps1 -Group fast
#   powershell -ExecutionPolicy Bypass -File scripts\run-instrumented.ps1 -Group step `
#       -Classes com.swan1127.repland.Migration24To25Test
param(
    [ValidateSet('fast', 'slow', 'step')]
    [string]$Group = 'fast',
    [string]$Classes = '',   # required when -Group step
    [switch]$KeepAppData     # by default clear QA app data first for a reproducible cold start
)
$ErrorActionPreference = 'Stop'

$JavaHome = 'D:\jdk21'
$Sdk      = 'D:\A_android_studio-sdk'
$Adb      = Join-Path $Sdk 'platform-tools\adb.exe'
$AvdHome  = 'D:\Android\avd'
$AvdName  = 'Medium_Phone_API_36.1'
$RepoRoot = Split-Path -Parent $PSScriptRoot   # android\
$QaPackage = 'com.swan1127.repland.qa'

# Group -> class list passed via -Pandroid.testInstrumentationRunnerArguments.class.
$FastClasses = @(
    'com.swan1127.repland.CaptureConfirmationFlowTest',
    'com.swan1127.repland.TaskCaptureRepositoryTest',
    'com.swan1127.repland.DraftSaveIntegrityTest',
    'com.swan1127.repland.PlanningOperationServiceTest',
    'com.swan1127.repland.AssistantChangesRepositoryTest',
    'com.swan1127.repland.ExecutionSessionRepositoryTest',
    'com.swan1127.repland.ExecutionWorkflowUiTest',
    'com.swan1127.repland.TaskLifecycleRepositoryTest',
    'com.swan1127.repland.TaskFeedbackWorkflowUiTest',
    'com.swan1127.repland.TaskFeedbackIntegrityTest',
    'com.swan1127.repland.NumericProfileRepositoryTest',
    'com.swan1127.repland.ProfileEvidenceAndDataManagementRepositoryTest',
    'com.swan1127.repland.CoreWorkflowUiTest'
) -join ','

$classArg = switch ($Group) {
    'fast' { $FastClasses }
    'slow' { '' }          # full run: omit the class filter entirely
    'step' {
        $trimmed = $Classes.Trim()
        if (-not $trimmed) { throw "[instrumented] -Group step requires -Classes (comma-separated FQCN)" }
        foreach ($item in ($trimmed -split ',')) {
            if ($item.Trim() -notmatch '^[A-Za-z0-9_.$#]+$') {
                throw "[instrumented] invalid class selector: '$item'"
            }
        }
        $trimmed
    }
}

function Get-Device {
    $out = & $Adb devices 2>$null
    ($out | Select-Object -Skip 1 | Where-Object { $_ -match "emulator-\d+\s+device" } | Select-Object -First 1)
}

# -- 1. ensure emulator online ------------------------------------------------
$device = Get-Device
if (-not $device) {
    Write-Host "[instrumented] no online emulator, booting $AvdName ..." -ForegroundColor Yellow
    $emulator = Join-Path $Sdk 'emulator\emulator.exe'
    Start-Process -FilePath $emulator -ArgumentList "-avd", $AvdName, "-no-snapshot-save" -WindowStyle Hidden
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $device = Get-Device
        if ($device) { break }
    }
    if (-not $device) { throw "[instrumented] emulator not online within 5 minutes" }
}
$serial = ($device -split '\s+')[0]
Write-Host "[instrumented] device: $serial"
& $Adb -s $serial wait-for-device
$bootDeadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $bootDeadline) {
    $booted = (& $Adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($booted -eq '1') { break }
    Start-Sleep -Seconds 5
}
if (-not $KeepAppData) {
    Write-Host "[instrumented] clearing $QaPackage app data for reproducibility"
    & $Adb -s $serial shell pm clear $QaPackage | Out-Null
}

# -- 2. build + run (single Gradle call; never run Gradle in parallel elsewhere) --
Push-Location $RepoRoot
try {
    Write-Host "[instrumented] building app + androidTest APK ..." -ForegroundColor Cyan
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "[instrumented] build failed" }

    if ($classArg) {
        Write-Host "[instrumented] running group '$Group' ($(($classArg -split ',').Count) classes) ..." -ForegroundColor Cyan
        & .\gradlew.bat :app:connectedDebugAndroidTest `
            "-Pandroid.testInstrumentationRunnerArguments.class=$classArg" `
            --console=plain
    } else {
        Write-Host "[instrumented] running FULL connected suite (group 'slow') ..." -ForegroundColor Cyan
        & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain
    }
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}

# -- 3. parse results: pass/fail summary with locatable messages ----------------
$resultsDir = Join-Path $RepoRoot 'app\build\outputs\androidTest-results\connected\debug'
$xmlFiles = Get-ChildItem $resultsDir -Filter *.xml -ErrorAction SilentlyContinue
$total = 0; $failures = 0; $failedCases = @()
foreach ($xml in $xmlFiles) {
    [xml]$doc = Get-Content $xml.FullName -Raw
    $total    += [int]$doc.testsuite.tests
    $failures += [int]$doc.testsuite.failures + [int]$doc.testsuite.errors
    foreach ($case in $doc.testsuite.testcase) {
        if ($case.failure -or $case.error) {
            $msg = if ($case.failure) { $case.failure[0].message } else { $case.error[0].message }
            $failedCases += [pscustomobject]@{ Class = $case.classname; Test = $case.name; Message = $msg }
        }
    }
}

Write-Host ""
Write-Host "==== INSTRUMENTED RESULTS (group: $Group) ====" -ForegroundColor Cyan
if ($classArg) {
    foreach ($cls in ($classArg -split ',')) {
        $clsFailed = $failedCases | Where-Object { $_.Class -eq $cls }
        $mark = if ($clsFailed) { "[FAIL]" } else { "[ OK ]" }
        $color = if ($clsFailed) { 'Red' } else { 'Green' }
        Write-Host "$mark $cls" -ForegroundColor $color
    }
}
foreach ($fc in $failedCases) {
    Write-Host "[FAIL] $($fc.Class) > $($fc.Test)" -ForegroundColor Red
    Write-Host "    reason: $($fc.Message)" -ForegroundColor DarkRed
}
Write-Host "total: $total cases, $failures failures" -ForegroundColor $(if ($failures -eq 0) { 'Green' } else { 'Red' })
if ($failures -gt 0 -or $gradleExit -ne 0) { exit 1 } else { exit 0 }
