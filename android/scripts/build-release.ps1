# Repland release pipeline: lint -> unit tests -> assembleRelease -> version/hash report.
# Signing is never uploaded anywhere by this script; the release build only proceeds
# when the existing REPLAND_RELEASE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS /
# _KEY_PASSWORD are configured (gradle property, env var or keystore.properties) —
# :app:validateReleaseSigning fails packaging otherwise. See docs/RELEASE.md.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1
$ErrorActionPreference = 'Stop'

$JavaHome = 'D:\jdk21'
$RepoRoot = Split-Path -Parent $PSScriptRoot   # android\
$GradleProps = Join-Path $RepoRoot 'gradle.properties'

# -- 1. version single source: read what the build will actually stamp ---------
$props = @{}
Get-Content -LiteralPath $GradleProps | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
}
$expectedCode = $props['REPLAND_VERSION_CODE']
$expectedName = $props['REPLAND_VERSION_NAME']
if (-not $expectedCode -or -not $expectedName) {
    throw "[release] REPLAND_VERSION_CODE / REPLAND_VERSION_NAME missing in gradle.properties"
}
Write-Host "[release] target version: $expectedName ($expectedCode)" -ForegroundColor Cyan

# -- 2. gates, in order; single Gradle call at a time (never run Gradle in parallel elsewhere)
Push-Location $RepoRoot
try {
    Write-Host "[release] :app:lintDebug ..." -ForegroundColor Cyan
    & .\gradlew.bat :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "[release] lint failed" }

    Write-Host "[release] :app:testDebugUnitTest ..." -ForegroundColor Cyan
    & .\gradlew.bat :app:testDebugUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "[release] unit tests failed" }

    Write-Host "[release] :app:assembleRelease ..." -ForegroundColor Cyan
    & .\gradlew.bat :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "[release] assembleRelease failed (release signing configured? see docs/RELEASE.md)" }

    # Rewrites app/build/outputs/build-manifest.txt AFTER packaging (version + sha256 of all APKs).
    & .\gradlew.bat :app:recordBuildArtifacts --console=plain
    if ($LASTEXITCODE -ne 0) { throw "[release] recordBuildArtifacts failed" }
} finally {
    Pop-Location
}

# -- 3. verify the built version matches gradle.properties ---------------------
$manifestPath = Join-Path $RepoRoot 'app\build\outputs\build-manifest.txt'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "[release] build manifest missing: $manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath
$manifestVersion     = ($manifest | Where-Object { $_ -like 'version=*' })     -replace '^version=', ''
$manifestVersionCode = ($manifest | Where-Object { $_ -like 'versionCode=*' }) -replace '^versionCode=', ''
if ($manifestVersion -ne $expectedName -or $manifestVersionCode -ne $expectedCode) {
    throw "[release] VERSION MISMATCH: gradle.properties=$expectedName/$expectedCode, " +
        "build-manifest=$manifestVersion/$manifestVersionCode"
}

# -- 4. report release APK path + sha256 ----------------------------------------
$releaseDir = Join-Path $RepoRoot 'app\build\outputs\apk\release'
$apks = @(Get-ChildItem -LiteralPath $releaseDir -Filter *.apk -ErrorAction SilentlyContinue)
if ($apks.Count -eq 0) { throw "[release] no APK found under $releaseDir" }

Write-Host ""
Write-Host "==== RELEASE ARTIFACTS (version $expectedName / $expectedCode) ====" -ForegroundColor Cyan
foreach ($apk in $apks) {
    $hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host $apk.FullName
    Write-Host "  sha256: $hash"
}
Write-Host "[release] build manifest: $manifestPath"
Write-Host "[release] OK" -ForegroundColor Green
exit 0
