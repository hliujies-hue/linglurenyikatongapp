param([string]$Serial)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
Initialize-YktEnvironment
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if(-not (Test-Path $apk)) { throw 'Build a release APK first.' }
if(-not $Serial) {
 $devices = @((& $adb devices) | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
 if($devices.Count -ne 1) { & $adb devices -l; throw 'Connect and authorize exactly one device or provide -Serial.' }
 $Serial=$devices[0]
}
& $adb -s $Serial install -r $apk
if($LASTEXITCODE -ne 0) { throw 'Installation failed. Do not uninstall: that would erase authorization cache and pending records. Check package/signature conflict.' }
& $adb -s $Serial shell am start -n com.lingluren.yikatong.terminal/.MainActivity
if($LASTEXITCODE -ne 0) { throw 'Application launch failed.' }
