# Orphan string gate: every <string> declared in app\src\main\res\values\strings.xml
# must be referenced somewhere under app\src (Kotlin/Java `R.string.xxx` or XML
# `@string/xxx`, including layouts, manifests and androidTest).
# A string carrying tools:ignore="UnusedResources" is treated as intentionally kept
# and is skipped. Pure text scanning — never invokes Gradle, safe next to other builds.
# Exit code: 0 = clean, 1 = orphan strings found (CI gate fails).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\check-orphan-strings.ps1
param(
    [string]$StringsFile = (Join-Path $PSScriptRoot '..\app\src\main\res\values\strings.xml'),
    [string]$SourceRoot  = (Join-Path $PSScriptRoot '..\app\src'),
    # Known legacy orphans, one name per line ('#' starts a comment). Every entry is
    # reviewed debt that must be cleaned up eventually; NEW orphans must NOT be added
    # here — they fail the gate and must be fixed by deletion, a real reference,
    # or tools:ignore="UnusedResources" if intentionally kept.
    [string]$AllowListFile = (Join-Path $PSScriptRoot 'orphan-strings-allowlist.txt')
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $StringsFile)) { throw "[orphan-strings] strings file not found: $StringsFile" }
if (-not (Test-Path -LiteralPath $SourceRoot))  { throw "[orphan-strings] source root not found: $SourceRoot" }

# -- 1. declared strings (XML-aware so attributes like tools:ignore are reliable) --
# ReadAllText defaults to UTF-8 (BOM-aware) on both Windows PowerShell 5.1 and pwsh;
# Get-Content -Raw would decode a BOM-less UTF-8 file as ANSI on Windows PowerShell.
[xml]$doc = [System.IO.File]::ReadAllText($StringsFile)
$declared = @{}   # name -> $true when tools:ignore mentions UnusedResources, else $false
foreach ($node in @($doc.resources.string)) {
    if ($null -eq $node) { continue }
    $name = [string]$node.name
    if ([string]::IsNullOrWhiteSpace($name)) { continue }
    $isIgnored = $false
    foreach ($attr in $node.Attributes) {
        if ($attr.LocalName -eq 'ignore' -and $attr.Value -match 'UnusedResources') { $isIgnored = $true }
    }
    $declared[$name] = $isIgnored
}
if ($declared.Count -eq 0) { throw "[orphan-strings] no <string> entries parsed from $StringsFile" }

# -- 2. referenced names across every source set (main/test/androidTest/remote/designPreview)
$allowList = New-Object 'System.Collections.Generic.HashSet[string]'
if (Test-Path -LiteralPath $AllowListFile) {
    foreach ($line in [System.IO.File]::ReadAllLines($AllowListFile)) {
        $entry = ($line -replace '#.*$', '').Trim()
        if ($entry) { [void]$allowList.Add($entry) }
    }
}
$stringsFileFull = (Resolve-Path -LiteralPath $StringsFile).Path
$files = Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -Include *.kt, *.java, *.xml |
    Where-Object { $_.FullName -ne $stringsFileFull }
$referenced = New-Object 'System.Collections.Generic.HashSet[string]'
$rxCode = [regex]'R\.string\.([A-Za-z0-9_]+)'
$rxXml  = [regex]'@string/([A-Za-z0-9_]+)'
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName)
    foreach ($m in $rxCode.Matches($text)) { [void]$referenced.Add($m.Groups[1].Value) }
    foreach ($m in $rxXml.Matches($text))  { [void]$referenced.Add($m.Groups[1].Value) }
}

# -- 3. verdict ----------------------------------------------------------------
$staleAllowList = @($allowList | Where-Object { -not $declared.ContainsKey($_) } | Sort-Object)
if ($staleAllowList.Count -gt 0) {
    Write-Host '[orphan-strings] WARNING: allowlist entries no longer declared (remove them):' -ForegroundColor DarkYellow
    $staleAllowList | ForEach-Object { Write-Host "[orphan-strings]   stale: $_" -ForegroundColor DarkYellow }
}
$keptByIgnore = @($declared.Keys | Where-Object { $declared[$_] })
$orphans = @($declared.Keys | Where-Object {
    -not $declared[$_] -and -not $allowList.Contains($_) -and -not $referenced.Contains($_)
} | Sort-Object)

Write-Host ("[orphan-strings] declared: {0}, referenced: {1}, allowlisted: {2}, tools:ignore-kept: {3}" -f `
    $declared.Count, $referenced.Count, $allowList.Count, $keptByIgnore.Count)
if ($keptByIgnore.Count -gt 0) {
    $keptByIgnore | Sort-Object | ForEach-Object { Write-Host "[orphan-strings]   kept: $_" -ForegroundColor DarkYellow }
}
if ($orphans.Count -gt 0) {
    Write-Host "[orphan-strings] ORPHAN STRINGS FOUND (declared but referenced nowhere):" -ForegroundColor Red
    $orphans | ForEach-Object { Write-Host "[orphan-strings]   orphan: $_" -ForegroundColor Red }
    Write-Host ("[orphan-strings] total: {0}. Fix by deleting the entry, adding a real reference, " -f $orphans.Count) -NoNewline
    Write-Host 'or marking tools:ignore="UnusedResources" if intentionally kept.'
    exit 1
}
Write-Host '[orphan-strings] OK: no orphan strings.' -ForegroundColor Green
exit 0
