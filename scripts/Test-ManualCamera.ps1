#requires -Version 7.0
param(
 [Parameter(Mandatory)][string]$Dataset,
 [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
 [switch]$AllowPause
)
$ErrorActionPreference='Stop'
if(-not $AllowPause){throw 'Use -AllowPause only on the idle test phone with no pending business records.'}
$root=Split-Path $PSScriptRoot -Parent
$Dataset=(Resolve-Path -LiteralPath $Dataset).Path
$manifestPath=Join-Path (Split-Path $Dataset -Parent) 'manifest.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$hash=(Get-FileHash -LiteralPath $Dataset -Algorithm SHA256).Hash.ToLowerInvariant()
if($manifest.sdkVersion -ne '8.5' -or $manifest.featureType -ne 'LIVE_PHOTO' -or $manifest.synthetic -ne $true -or $manifest.genuineDistinctIdentities -ne $false -or $manifest.count -lt 1 -or $manifest.count -gt 200000 -or $manifest.sha256 -cne $hash -or (Get-Item -LiteralPath $Dataset).Length -ne ([long]$manifest.count*512)) {throw 'Synthetic SDK dataset manifest/hash/size mismatch'}
if(-not (Test-Path -LiteralPath (Join-Path $root 'signing.properties'))){throw 'Original signing.properties required; never replace the signing key'}
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$package='com.lingluren.yikatong.terminal'
function Adb([string[]]$A){$o=@(& $adb -s $Serial @A 2>&1);if($LASTEXITCODE -ne 0){throw ($o -join "`n")};return $o}
$state=(Adb @('get-state')) -join ''
if($state.Trim() -ne 'device'){throw 'Phone not authorized'}
$installed=(Adb @('shell','dumpsys','package',$package)) -join "`n"
if($installed -notmatch 'versionCode=(136|137)\b'){throw 'Expected existing app versionCode 136 or 137. No downgrade/uninstall will be performed.'}
$lockDir=Join-Path $env:LOCALAPPDATA 'Yikatong-TestLocks'
New-Item -ItemType Directory -Path $lockDir -Force | Out-Null
$lockName=($Serial -replace '[^A-Za-z0-9._-]','_')+'.lock'
$lock=[IO.File]::Open((Join-Path $lockDir $lockName),'OpenOrCreate','ReadWrite','None')
$touched=$false
$folder=Join-Path ([Environment]::GetFolderPath('Desktop')) ('Yikatong-ManualScale\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
$dest="/sdcard/Android/data/$package/files/ykt-test-v2/manual-scale"
try {
 New-Item -ItemType Directory -Path $folder | Out-Null
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $folder 'dataset-manifest.json')
 Write-Host 'Building signed app with private test surface and separate instrumentation APK...'
 & (Join-Path $PSScriptRoot 'Export-Apk.ps1') -DeferTests
 & (Join-Path $PSScriptRoot 'Test-NativeScale.ps1') -PrepareOnly
 $apk=Join-Path $root 'demo-work\app\build\outputs\apk\release\app-release.apk'
 $testApk=Join-Path $root 'demo-work\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
 $touched=$true
 Adb @('install','-r',$apk) | Out-Host
 Adb @('install','-r',$testApk) | Out-Host
 $installed=(Adb @('shell','dumpsys','package',$package)) -join "`n"
 if($installed -notmatch 'versionCode=137\b'){throw 'Manual test app version 137 not installed'}
 Adb @('shell','am','force-stop',$package) | Out-Null
 $runner="$package.test/$package.TerminalScaleRunner"
 $prepare=Adb @('shell','am','instrument','-w','-r','-e','class',"$package.TerminalManualScaleTest#testPrepareManualStorage",$runner)
 $prepare | Set-Content -LiteralPath (Join-Path $folder 'prepare.txt') -Encoding utf8NoBOM
 if(($prepare -join "`n") -notmatch 'OK \(1 test\)'){throw 'App-owned storage preparation failed'}
 Adb @('shell','rm','-f',"$dest/report.json","$dest/report.tmp","$dest/STOP") | Out-Null
 Adb @('push',$Dataset,"$dest/features.bin") | Out-Host
 Adb @('push',$manifestPath,"$dest/manifest.json") | Out-Host
 Adb @('shell','am','force-stop',$package) | Out-Null
 Write-Host "Opening camera test with $($manifest.count) synthetic background templates. Keep USB connected."
 Write-Host 'On phone: wait for ready, check unknown face, enroll yourself, move away and return, then remove yourself and retest.'
 Write-Host 'Tap the END button on the phone to export results and restore the normal app. Maximum session: 30 minutes.'
 Write-Host "Report folder: $folder"
 $output=Adb @('shell','am','instrument','-w','-r','-e','class',"$package.TerminalManualScaleTest#testManualCamera",'-e','datasetSha256',$hash,$runner)
 $output | Set-Content -LiteralPath (Join-Path $folder 'instrumentation.txt') -Encoding utf8NoBOM
 $output | Out-Host
 Adb @('pull',"$dest/report.json",(Join-Path $folder 'report.json')) | Out-Host
 $report=Get-Content -LiteralPath (Join-Path $folder 'report.json') -Raw | ConvertFrom-Json
 if(($output -join "`n") -notmatch 'OK \(1 test\)' -or $report.status -ne 'SESSION_ENDED' -or $report.nativeCleanupCode -ne 0 -or $report.datasetSha256 -cne $hash -or $report.records -ne $manifest.count){throw 'Manual session incomplete; keep report and instrumentation output'}
 Write-Host 'Manual session ended. This is NOT an accuracy, liveness-security or end-to-end acceptance PASS.'
 Get-Content -LiteralPath (Join-Path $folder 'report.json') -Raw | Out-Host
} finally {
 if($touched){
  # Stop the SDK process before deleting only this session's uploaded test files.
  & $adb -s $Serial shell am force-stop $package
  & $adb -s $Serial pull "$dest/report.json" (Join-Path $folder 'report.json') 2>&1 | Out-Host
  & $adb -s $Serial logcat -b crash -d -v threadtime 2>&1 | Set-Content -LiteralPath (Join-Path $folder 'crash.txt') -Encoding utf8NoBOM
  & $adb -s $Serial shell rm -f "$dest/features.bin" "$dest/manifest.json"
  & $adb -s $Serial shell am start -W -n "$package/$package.TerminalEntryActivity"
  Write-Host 'Check original login, 6-person library, pending records and normal verification after restart.'
 }
 $lock.Dispose()
 Write-Host "Local report: $folder"
}
