# Repland metacognition-loop E2E: one command replays
# capture -> planning -> execution -> record/review -> calibration.
# Each step is asserted at the DB and UI level via instrumented tests.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-e2e-loop.ps1 [-KeepAppData]
param(
    [switch]$KeepAppData  # by default clear QA app data first for a reproducible cold start
)
$ErrorActionPreference = 'Stop'

$JavaHome = 'D:\jdk21'
$Sdk      = 'D:\A_android_studio-sdk'
$Adb      = Join-Path $Sdk 'platform-tools\adb.exe'
$AvdHome  = 'D:\Android\avd'
$AvdName  = 'Medium_Phone_API_36.1'
$RepoRoot = Split-Path -Parent $PSScriptRoot   # android\
$QaPackage = 'com.swan1127.repland.qa'

# Loop step -> instrumented test classes covering it (single instrumentation run)
$Steps = [ordered]@{
    '01-capture (P0-2 confirm panel groups)' = 'com.swan1127.repland.CaptureConfirmationFlowTest'
    '02-capture persistence integrity'       = 'com.swan1127.repland.TaskCaptureRepositoryTest,com.swan1127.repland.DraftSaveIntegrityTest'
    '03-planning (assistant/group confirm)'  = 'com.swan1127.repland.PlanningOperationServiceTest,com.swan1127.repland.AssistantChangesRepositoryTest'
    '04-execution (schedule-and-start)'      = 'com.swan1127.repland.ExecutionSessionRepositoryTest,com.swan1127.repland.ExecutionWorkflowUiTest'
    '05-record (done/partial/feedback)'      = 'com.swan1127.repland.TaskLifecycleRepositoryTest,com.swan1127.repland.TaskFeedbackWorkflowUiTest'
    '06-review decision actions'             = 'com.swan1127.repland.TaskFeedbackIntegrityTest'
    '07-calibration (planned vs actual)'     = 'com.swan1127.repland.NumericProfileRepositoryTest,com.swan1127.repland.ProfileEvidenceAndDataManagementRepositoryTest'
    '08-full-loop regression'                = 'com.swan1127.repland.CoreWorkflowUiTest'
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_AVD_HOME = $AvdHome

function Get-Device {
    $out = & $Adb devices 2>$null
    ($out | Select-Object -Skip 1 | Where-Object { $_ -match "emulator-\d+\s+device" } | Select-Object -First 1)
}

# -- 1. ensure emulator online ------------------------------------------------
$device = Get-Device
if (-not $device) {
    Write-Host "[E2E] no online emulator, booting $AvdName ..." -ForegroundColor Yellow
    $emulator = Join-Path $Sdk 'emulator\emulator.exe'
    Start-Process -FilePath $emulator -ArgumentList "-avd", $AvdName, "-no-snapshot-save" -WindowStyle Hidden
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $device = Get-Device
        if ($device) { break }
    }
    if (-not $device) { throw "[E2E] emulator not online within 5 minutes" }
}
$serial = ($device -split '\s+')[0]
Write-Host "[E2E] device: $serial"
& $Adb -s $serial wait-for-device
$bootDeadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $bootDeadline) {
    $booted = (& $Adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($booted -eq '1') { break }
    Start-Sleep -Seconds 5
}
if (-not $KeepAppData) {
    Write-Host "[E2E] clearing $QaPackage app data for reproducibility"
    & $Adb -s $serial shell pm clear $QaPackage | Out-Null
}

# -- 2. build (single Gradle call; never run Gradle in parallel elsewhere) ----
Push-Location $RepoRoot
try {
    Write-Host "[E2E] building app + androidTest APK ..." -ForegroundColor Cyan
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "[E2E] build failed" }

    # -- 3. run the loop's instrumented tests in order ------------------------
    $classArg = ($Steps.Values -join ',')
    Write-Host "[E2E] running loop instrumented tests ($($Steps.Count) steps) ..." -ForegroundColor Cyan
    & .\gradlew.bat :app:connectedDebugAndroidTest `
        "-Pandroid.testInstrumentationRunnerArguments.class=$classArg" `
        --console=plain
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}

# -- 4. parse results: per-step pass/fail with locatable messages -------------
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
Write-Host "==== E2E LOOP RESULTS ====" -ForegroundColor Cyan
foreach ($step in $Steps.Keys) {
    $classes = $Steps[$step] -split ','
    $stepFailed = $failedCases | Where-Object { $classes -contains $_.Class }
    $mark = if ($stepFailed) { "[FAIL]" } else { "[ OK ]" }
    $color = if ($stepFailed) { 'Red' } else { 'Green' }
    Write-Host "$mark $step" -ForegroundColor $color
    $stepFailed | ForEach-Object {
        Write-Host "    failed case: $($_.Test)" -ForegroundColor Red
        Write-Host "    reason: $($_.Message)" -ForegroundColor DarkRed
    }
}
Write-Host "total: $total cases, $failures failures" -ForegroundColor $(if ($failures -eq 0) { 'Green' } else { 'Red' })
if ($failures -gt 0 -or $gradleExit -ne 0) { exit 1 } else { exit 0 }
