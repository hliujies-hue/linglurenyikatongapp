param([Parameter(Mandatory=$true)][string]$JobFile,[Parameter(Mandatory=$true)][string]$ResultFile)
$ErrorActionPreference='Stop'
if(-not $env:YKT_BUILD_BRANDING) { throw 'Queued build requires the worker branding snapshot' }
$root=Split-Path $PSScriptRoot -Parent
$job=Get-Content -LiteralPath $JobFile -Raw -Encoding UTF8 | ConvertFrom-Json
if(Test-Path -LiteralPath $ResultFile) { throw 'Result receipt already exists; refusing stale output' }
if(-not (Test-Path -LiteralPath (Join-Path $root 'signing.properties') -PathType Leaf)) { throw 'Original signing.properties required' }
# This explicit worker entry does not run tests; validation remains deferred.
& (Join-Path $PSScriptRoot 'Build-Demo.ps1') -DeferTests -QueueJob $JobFile
$apk=Join-Path $root 'demo-work\app\build\outputs\apk\release\app-release.apk'
$buildTools=@(Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Recurse -Filter apksigner.bat | Sort-Object FullName -Descending)
if(-not $buildTools.Count) { throw 'apksigner missing' }
$certs=& $buildTools[0].FullName verify --verbose --print-certs $apk 2>&1
if($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$certMatches=[regex]::Matches(($certs | Out-String),'(?m)^Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$')
if($certMatches.Count -ne 1) { throw 'APK must have exactly one approved signing certificate' }
$cert=$certMatches[0].Groups[1].Value.ToLowerInvariant()
if($cert -ne [string]$job.expectedCertificate) { throw 'APK signer does not match ORIGINAL approved certificate' }
$aapt=Join-Path $buildTools[0].DirectoryName 'aapt.exe'
$badging=& $aapt dump badging $apk 2>&1
if($LASTEXITCODE -ne 0) { throw 'Cannot inspect final APK identity' }
$package=[regex]::Match(($badging | Out-String),"(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
if(-not $package.Success -or $package.Groups[1].Value -ne 'com.lingluren.yikatong.terminal' -or $package.Groups[2].Value -ne [string]$job.versionCode -or $package.Groups[3].Value -ne [string]$job.versionName) { throw 'Final APK identity/version mismatch' }
if(($badging | Out-String) -match '(?m)^application-debuggable') { throw 'Debuggable APK refused' }
$permissions=& $aapt dump permissions $apk 2>&1
if($LASTEXITCODE -ne 0) { throw 'Cannot inspect final APK permissions' }
$permissionText=$permissions | Out-String
foreach($required in @('android.permission.CAMERA','android.permission.INTERNET')) {
 if(-not $permissionText.Contains($required)) { throw 'Required APK permission missing' }
}
if($permissionText.Contains('android.permission.READ_PHONE_STATE')) { throw 'Forbidden phone permission remains' }
$receipt=@{sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant();certificate=$cert;packageName='com.lingluren.yikatong.terminal';sourceCommit=$job.sourceCommit;versionName=$job.versionName;versionCode=[long]$job.versionCode;tests='DEFERRED'}
[IO.File]::WriteAllText($ResultFile,($receipt | ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
