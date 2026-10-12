$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$demo = Join-Path $root 'demo-local'
if(-not (Test-Path (Join-Path $demo 'settings.gradle'))) { throw 'Full Demo not imported' }
Write-Host 'Demo modules:'
Get-Content (Join-Path $demo 'settings.gradle')
Write-Host 'Demo build versions:'
Get-ChildItem -LiteralPath $demo -Filter 'build.gradle' -Recurse -File |
 Where-Object { $_.FullName -notmatch '\\build\\|\\\.gradle\\' } |
 Select-String -Pattern 'compileSdkVersion|targetSdkVersion|com.android.tools.build:gradle'
Write-Host 'Gradle wrapper:'
Get-Content (Join-Path $demo 'gradle\wrapper\gradle-wrapper.properties')
Write-Host 'Android platforms installed:'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if(Test-Path (Join-Path $sdk 'platforms')) { Get-ChildItem (Join-Path $sdk 'platforms') -Directory | Select-Object -ExpandProperty Name }
Write-Host 'This is inspection only. No license or signature activation, no build, no device changes.'
