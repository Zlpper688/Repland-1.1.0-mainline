param(
    [string]$ApkPath = (Join-Path $PSScriptRoot '..\android\app\build\outputs\apk\debug\app-debug.apk'),
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$Avd = 'Repland_1_1_0_API_36'
)

$ErrorActionPreference = 'Stop'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$statePath = Join-Path (Join-Path $env:LOCALAPPDATA 'Repland\QaLauncher') $Avd
$installedHashPath = Join-Path $statePath 'qa-installed.sha256'
$package = 'com.swan1127.repland.qa'
$apk = [IO.Path]::GetFullPath($ApkPath)

function Find-ReplandEmulator {
    foreach ($device in (& $adb devices)) {
        if ($device -match '^(emulator-\d+)\s+device\s*$') {
            $serial = $Matches[1]
            if ((& $adb -s $serial emu avd name) -contains $Avd) { return $serial }
        }
    }
}

$launchLock = New-Object System.Threading.Mutex($false, 'Local\ReplandDesktopLauncher')
if (!$launchLock.WaitOne(0)) {
    $launchLock.Dispose()
    return
}

try {
    New-Item -ItemType Directory -Path $statePath -Force | Out-Null
    if (!(Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw 'The Repland QA APK is missing. Build :app:assembleDebug or pass -ApkPath.'
    }
    if (!(Test-Path -LiteralPath $adb -PathType Leaf) -or !(Test-Path -LiteralPath $emulator -PathType Leaf)) {
        throw 'Android SDK Platform-Tools or Emulator is missing. Check ANDROID_HOME.'
    }
    $serial = Find-ReplandEmulator
    if (!$serial) {
        if ((& $emulator -list-avds) -notcontains $Avd) {
            throw "Android virtual device '$Avd' is missing. Create it in Android Studio Device Manager or pass -Avd."
        }
        $emulatorProcess = Start-Process -FilePath $emulator `
            -ArgumentList @('-avd', $Avd, '-port', '5560', '-no-snapshot', '-gpu', 'swiftshader_indirect') `
            -WindowStyle Normal -PassThru `
            -RedirectStandardOutput (Join-Path $statePath 'emulator.stdout.log') `
            -RedirectStandardError (Join-Path $statePath 'emulator.stderr.log')
    }

    $ready = $false
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        if (!$serial) { $serial = Find-ReplandEmulator }
        if ($serial -and ((& $adb -s $serial shell getprop sys.boot_completed) -contains '1')) {
            $ready = $true
            break
        }
        if (!$serial -and $emulatorProcess -and $emulatorProcess.HasExited) {
            throw "The Repland Android emulator could not start. See $statePath\emulator.stderr.log."
        }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'The Repland Android emulator did not finish starting.' }

    $installed = & $adb -s $serial shell pm path $package
    $sha256 = [Security.Cryptography.SHA256]::Create()
    $apkStream = [IO.File]::OpenRead($apk)
    try {
        $apkHash = [BitConverter]::ToString($sha256.ComputeHash($apkStream)).Replace('-', '')
    } finally {
        $apkStream.Dispose()
        $sha256.Dispose()
    }
    $installedHash = if (Test-Path -LiteralPath $installedHashPath) {
        (Get-Content -LiteralPath $installedHashPath -Raw).Trim()
    }
    if (($installed -join "`n") -notmatch '(?m)^package:' -or $installedHash -ne $apkHash) {
        $result = & $adb -s $serial install -r $apk
        if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') {
            throw "Could not install Repland. $($result -join ' ')"
        }
        $apkHash | Set-Content -LiteralPath $installedHashPath -Encoding Ascii
    }

    & $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb -s $serial shell wm dismiss-keyguard | Out-Null
    $result = & $adb -s $serial shell am start -W -n "$package/com.swan1127.repland.MainActivity"
    if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Status: ok') {
        throw "Could not open Repland. $($result -join ' ')"
    }
    $port = $serial.Substring('emulator-'.Length)
    $window = Get-Process -Name 'qemu-system-x86_64' -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -eq "Android Emulator - ${Avd}:$port" } | Select-Object -First 1
    if ($window) {
        try {
            Add-Type -AssemblyName System.Windows.Forms
            Add-Type -Name EmulatorWindow -Namespace Repland -MemberDefinition @'
            [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
            public struct Rectangle { public int Left, Top, Right, Bottom; }
            [System.Runtime.InteropServices.DllImport("user32.dll")]
            public static extern bool IsIconic(System.IntPtr window);
            [System.Runtime.InteropServices.DllImport("user32.dll")]
            public static extern bool ShowWindow(System.IntPtr window, int command);
            [System.Runtime.InteropServices.DllImport("user32.dll")]
            public static extern bool GetWindowRect(System.IntPtr window, out Rectangle rectangle);
            [System.Runtime.InteropServices.DllImport("user32.dll")]
            public static extern bool SetWindowPos(System.IntPtr window, System.IntPtr insertAfter, int x, int y, int width, int height, uint flags);
'@
            $handle = $window.MainWindowHandle
            if ([Repland.EmulatorWindow]::IsIconic($handle)) { [void][Repland.EmulatorWindow]::ShowWindow($handle, 9) }
            $rect = New-Object 'Repland.EmulatorWindow+Rectangle'
            if ([Repland.EmulatorWindow]::GetWindowRect($handle, [ref]$rect)) {
                $visible = [System.Windows.Forms.Screen]::AllScreens | Where-Object {
                    $area = $_.WorkingArea
                    $rect.Left -lt $area.Right -and $rect.Right -gt $area.Left -and
                        $rect.Top -lt $area.Bottom -and $rect.Bottom -gt $area.Top
                }
                if (!$visible) {
                    $area = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
                    [void][Repland.EmulatorWindow]::SetWindowPos($handle, [IntPtr]::Zero, ($area.Left + 24), ($area.Top + 24), 0, 0, 0x15)
                }
            }
            $shell = New-Object -ComObject WScript.Shell
            [void]$shell.AppActivate($window.Id)
        } catch {
            Write-Warning ("Could not focus the emulator window: " + $_.Exception.Message)
        }
    }
} catch {
    $_.Exception.Message | Set-Content -LiteralPath (Join-Path $statePath 'launcher-error.log') -Encoding UTF8
    Add-Type -AssemblyName PresentationFramework
    [System.Windows.MessageBox]::Show($_.Exception.Message, 'Repland', 'OK', 'Error') | Out-Null
} finally {
    $launchLock.ReleaseMutex()
    $launchLock.Dispose()
}
