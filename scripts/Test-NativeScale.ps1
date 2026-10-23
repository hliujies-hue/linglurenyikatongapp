param([string]$Dataset = '', [string]$Serial = '', [switch]$PrepareOnly, [switch]$AllowPause, [ValidateRange(1,200000)][int]$Count=1000, [ValidateSet("real","synthetic")][string]$DatasetMode="")
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(-not (Test-Path (Join-Path $root 'signing.properties'))) { throw 'Original signing.properties required; do not create another key' }
if(-not $PrepareOnly) {
 if(-not $AllowPause) { throw 'This stops verification during the test. Use -AllowPause on an idle test phone with no pending records.' }
 if(-not $Dataset -or -not (Test-Path -LiteralPath $Dataset -PathType Leaf)) { throw 'Supply SDK-extracted features.bin; arbitrary random feature bytes are not allowed' }
 if(-not $DatasetMode) { throw 'Specify -DatasetMode real or synthetic explicitly' }
 $datasetHash=(Get-FileHash -LiteralPath $Dataset -Algorithm SHA256).Hash.ToLowerInvariant()
 $manifestPath=Join-Path (Split-Path (Resolve-Path -LiteralPath $Dataset).Path -Parent) 'manifest.json'
 if(-not (Test-Path -LiteralPath $manifestPath)) { throw 'manifest.json required beside features.bin; see docs/testing/NATIVE-SCALE.md' }
 $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
 if((Get-Item -LiteralPath $Dataset).Length -ne ([long]$manifest.count*512)) { throw 'Dataset size does not match manifest' }
 if($manifest.sdkVersion -ne '8.5' -or $manifest.featureType -ne 'LIVE_PHOTO' -or $manifest.count -lt $Count -or $manifest.count -gt 200000 -or ($DatasetMode -eq 'real' -and ($manifest.genuineDistinctIdentities -ne $true -or $manifest.synthetic -ne $false)) -or ($DatasetMode -eq 'synthetic' -and $manifest.synthetic -ne $true) -or $manifest.sha256 -cne $datasetHash) { throw 'Dataset manifest mismatch or genuine distinct SDK data not attested' }
}
if(-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
 $env:JAVA_HOME=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
}
if(-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { throw 'JDK 17 required' }
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
& (Join-Path $PSScriptRoot 'Apply-Demo.ps1')
Push-Location (Join-Path $root 'demo-work')
try {
 & .\gradlew.bat --no-daemon :app:assembleReleaseAndroidTest
 if($LASTEXITCODE -ne 0) { throw 'Instrumentation APK build failed' }
} finally { Pop-Location }
$testApk=Join-Path $root 'demo-work\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
if(-not (Test-Path $testApk)) { throw 'Instrumentation APK not found' }
if($PrepareOnly) { Write-Host "Test APK prepared: $testApk; NOT executed on phone"; return }
$adb=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
function AdbChecked([string[]]$Arguments) {
 $output=@(& $adb -s $Serial @Arguments 2>&1)
 if($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
 return $output
}
if(-not $Serial) {
 $devices=@(& $adb devices | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
 if($devices.Count -ne 1) { throw 'Connect exactly one authorized phone or specify -Serial' }
 $Serial=$devices[0]
}
if($Serial -notmatch '^[A-Za-z0-9._:-]+$') { throw 'Invalid device serial' }
$package='com.lingluren.yikatong.terminal'
$installed=(AdbChecked @('shell','dumpsys','package',$package)) -join "`n"
if($installed -notmatch 'versionCode=(136|137|138|139|140|141|142|143|144)\b') { throw 'Install the verified versionCode 136, 137, 138, 139, 140, 141, 142, 143 or 144 production APK first; no downgrade or data reset is performed' }
$dest='/sdcard/Android/data/com.lingluren.yikatong.terminal/files/ykt-test-v2/native-scale'
$folder=Join-Path ([Environment]::GetFolderPath('Desktop')) ('Yikatong-NativeScale\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $folder -Force | Out-Null
AdbChecked @('install','-r',$testApk) | Out-Host
$prepare=AdbChecked @('shell','am','instrument','-w','-r','-e','class',"$package.TerminalFeatureExtractTest#testPrepareStorage","$package.test/$package.TerminalScaleRunner")
$prepare | Out-Host
if(($prepare -join "`n") -notmatch 'OK \(1 test\)') { throw 'App-owned storage preparation failed' }
AdbChecked @('shell','rm','-f',"$dest/STOP","$dest/report.json","$dest/report.tmp") | Out-Null
AdbChecked @('push',$Dataset,"$dest/features.bin") | Out-Host
AdbChecked @('push',$manifestPath,"$dest/manifest.json") | Out-Host
AdbChecked @('shell','am','force-stop',$package) | Out-Null
try {
 $output=AdbChecked @('shell','am','instrument','-w','-r','-e','class',"$package.TerminalNativeScaleTest",'-e','nativeScale',"$Count",'-e','datasetMode',$DatasetMode,'-e','datasetSha256',$datasetHash,"$package.test/$package.TerminalScaleRunner")
 $output | Set-Content -LiteralPath (Join-Path $folder 'instrumentation.txt') -Encoding UTF8
 $output | Out-Host
 if(($output -join "`n") -notmatch 'OK \(1 test\)') { throw "Instrumentation failed; see $folder\instrumentation.txt." }
 AdbChecked @('pull',"$dest/report.json",(Join-Path $folder 'report.json')) | Out-Host
 $report=Get-Content -LiteralPath (Join-Path $folder 'report.json') -Raw | ConvertFrom-Json
 if(($output -join "`n") -notmatch 'OK \(1 test\)' -or $report.status -ne 'PASS' -or $report.nativeCleanupCode -ne 0 -or $report.datasetSha256 -ne $datasetHash -or $report.records -ne $Count -or $report.datasetMode -ne $DatasetMode) { throw "Native test did not pass. Report: $folder" }
 Write-Host "Native index self-query PASS. NOT real-face accuracy or end-to-end acceptance. Report: $folder"
} finally {
 # Capture native crash evidence before restarting production; never clear device logs.
 & $adb -s $Serial logcat -b crash -d -v threadtime 2>&1 | Set-Content -LiteralPath (Join-Path $folder 'crash.txt') -Encoding UTF8
 & $adb -s $Serial logcat -d -v threadtime -t 12000 2>&1 | Set-Content -LiteralPath (Join-Path $folder 'logcat.txt') -Encoding UTF8
 # Recover diagnostics even when instrumentation crashes. Keep report on phone.
 & $adb -s $Serial pull "$dest/report.json" (Join-Path $folder 'report.json') 2>&1 | Out-Host
 & $adb -s $Serial shell rm -f "$dest/features.bin" "$dest/manifest.json" 2>&1 | Out-Host
 & $adb -s $Serial shell am force-stop $package
 & $adb -s $Serial shell am start -W -n "$package/$package.TerminalEntryActivity"
 Write-Host "Report directory: $folder. Check phone login, original 6-person library and verification after restart."
}

