$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
Initialize-YktEnvironment
$root = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Preflight.ps1')
if (-not (Test-Path (Join-Path $root 'signing.properties'))) { & (Join-Path $PSScriptRoot 'New-Signing.ps1') }
Push-Location $root
try {
 & .\gradlew.bat --no-daemon :core:check :app:assembleRelease :app:lintRelease
 if ($LASTEXITCODE -ne 0) { throw 'Build failed. Do not install an older APK as the new build.' }
 $apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
 if(-not (Test-Path $apk)) { throw 'Release APK not found.' }
 $signers = @(Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Recurse -Filter 'apksigner.bat' | Sort-Object FullName -Descending)
 if($signers.Count -eq 0) { throw 'apksigner not found.' }
 & $signers[0].FullName verify --verbose --print-certs $apk
 if($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
 Get-FileHash -LiteralPath $apk -Algorithm SHA256
 Write-Host ('APK: ' + $apk)
 Write-Host 'Development build only. Production admission is disabled until platform integration and device testing pass.'
} finally { Pop-Location }
