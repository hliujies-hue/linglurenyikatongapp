$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$source = Join-Path $root 'demo-local'
$destination = Join-Path $root 'demo-work'
if(-not (Test-Path (Join-Path $source 'app\build.gradle'))) { throw 'Original Demo missing' }
if(Test-Path -LiteralPath $destination) { throw 'demo-work exists; refusing to overwrite your changes.' }
$work = Join-Path $root ('demo-stage-' + [guid]::NewGuid())
try {
 Copy-Item -LiteralPath $source -Destination $work -Recurse
 Get-ChildItem -LiteralPath $work -Directory -Recurse -Force |
  Where-Object { $_.Name -in @('build','.gradle','.idea') } |
  Sort-Object { $_.FullName.Length } -Descending |
  ForEach-Object { if(Test-Path -LiteralPath $_.FullName) { Remove-Item -LiteralPath $_.FullName -Recurse -Force } }
 Copy-Item -LiteralPath (Join-Path $root 'overlays\app\build.gradle') -Destination (Join-Path $work 'app\build.gradle') -Force
 $manifestPath = Join-Path $work 'app\src\main\AndroidManifest.xml'
 [xml]$manifest = Get-Content -LiteralPath $manifestPath -Raw
 $android = 'http://schemas.android.com/apk/res/android'
 $manifest.manifest.application.SetAttribute('label',$android,'@string/app_name')
 $manifest.manifest.application.SetAttribute('allowBackup',$android,'false')
 $manifest.Save($manifestPath)
 $stringsPath = Join-Path $work 'app\src\main\res\values\strings.xml'
 [xml]$strings = Get-Content -LiteralPath $stringsPath -Raw
 $name = $strings.SelectSingleNode('/resources/string[@name="app_name"]')
 if($null -eq $name) { throw 'app_name resource missing' }
 $name.InnerText = [char]0x4E00 + [string][char]0x5361 + [char]0x901A + [char]0x666F + [char]0x533A + [char]0x5237 + [char]0x8138 + [char]0x6838 + [char]0x9A8C + [char]0x7AEF
 $strings.Save($stringsPath)
 Move-Item -LiteralPath $work -Destination $destination
 Write-Host ('Demo working copy prepared: ' + $destination)
 Write-Host 'Application ID changed; original Demo modules retained; sample release signing removed.'
 Write-Host 'Activation/login UI and platform integration still pending. Do not activate or use for admission.'
} finally { if(Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force } }
