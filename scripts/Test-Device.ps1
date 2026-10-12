param([string]$Serial = '', [switch]$Install)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if(-not (Test-Path $adb)) { throw 'ADB missing from Android SDK' }
if(-not $Serial) {
 $devices = @(& $adb devices | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
 if($devices.Count -ne 1) { throw 'Connect exactly one authorized phone, or supply -Serial' }
 $Serial = $devices[0]
}
if($Install) {
 $apk = Join-Path $root 'demo-work\app\build\outputs\apk\release\app-release.apk'
 if(-not (Test-Path $apk)) { throw 'Build release APK first' }
 $signers = @(Get-ChildItem (Join-Path $sdk 'build-tools') -Recurse -Filter apksigner.bat | Sort-Object FullName -Descending)
 if($signers.Count -eq 0) { throw 'apksigner missing' }
 & $signers[0].FullName verify --verbose $apk
 if($LASTEXITCODE -ne 0) { throw 'Release signature verification failed' }
 & $adb -s $Serial install -r $apk
 if($LASTEXITCODE -ne 0) { throw 'APK installation failed; existing app and data retained' }
}
$package = 'com.lingluren.yikatong.terminal'
& $adb -s $Serial shell am force-stop $package
& $adb -s $Serial shell am start -W -n "$package/$package.TerminalEntryActivity"
if($LASTEXITCODE -ne 0) { throw 'Terminal launch failed' }
Start-Sleep -Seconds 5
$processId = (& $adb -s $Serial shell pidof $package | Out-String).Trim()
if(-not $processId) { throw 'Terminal process exited; inspect Android Studio Logcat' }
Write-Host "PASS: installed terminal launched and process remains alive (PID $processId)."
Write-Host 'On phone: activate, login with bound staff account, confirm device binding and platform heartbeat. This smoke test does not certify face matching or admission.'
