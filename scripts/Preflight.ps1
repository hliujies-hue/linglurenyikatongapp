$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
Initialize-YktEnvironment
$root = Split-Path $PSScriptRoot -Parent
$checks = @{
 'Android platform 35' = (Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar')
 'SDK AAR' = (Join-Path $root 'vendor\face-sdk.aar')
 'SDK models' = (Join-Path $root 'app\src\main\assets\face-sdk-models')
}
$missing = @()
foreach ($name in $checks.Keys) {
 if (Test-Path -LiteralPath $checks[$name]) { Write-Host ('OK: ' + $name) }
 else { $missing += $name; Write-Host ('MISSING: ' + $name) }
}
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (Test-Path -LiteralPath $adb) { & $adb devices -l }
else { Write-Host 'MISSING: Android SDK Platform-Tools (adb)' }
if ($missing.Count) { throw ('Install missing components: ' + ($missing -join ', ')) }
Write-Host 'Preflight passed. This is not an APK build or device acceptance result.'
