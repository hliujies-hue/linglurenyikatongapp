param([string]$OutputDirectory = '', [switch]$DeferTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if(-not (Test-Path -LiteralPath (Join-Path $root 'signing.properties'))) {
 throw 'Restore the existing signing.properties before exporting an update; do not generate a different signing key.'
}
# Build-Demo throws on test, Lint, build or APK signature verification failures.
& (Join-Path $PSScriptRoot 'Build-Demo.ps1') -DeferTests:$DeferTests
$source = Join-Path $root 'demo-work\app\build\outputs\apk\release\app-release.apk'
if(-not (Test-Path -LiteralPath $source)) { throw 'Release APK missing after build' }
if(-not $OutputDirectory) {
 $OutputDirectory = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Yikatong-APK'
}
$gradle = Get-Content -LiteralPath (Join-Path $root 'overlays\app\build.gradle') -Raw
$versionMatch = [regex]::Match($gradle, 'versionName\s+"([A-Za-z0-9._-]+)"')
if(-not $versionMatch.Success) { throw 'Cannot read APK version name' }
$version = $versionMatch.Groups[1].Value
$folder = Join-Path $OutputDirectory ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $folder -ErrorAction Stop | Out-Null
$destination = Join-Path $folder ("yikatong-$version.apk")
try {
 Copy-Item -LiteralPath $source -Destination $destination -ErrorAction Stop
 $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
 $exportHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
 if($sourceHash -ne $exportHash) { throw 'Exported APK hash mismatch' }
 $revision = 'unavailable'
 $workingTree = 'unavailable'
 if(Get-Command git -ErrorAction SilentlyContinue) {
  $head = & git -C $root rev-parse HEAD
  if($LASTEXITCODE -eq 0) { $revision = ($head | Out-String).Trim() }
  $changes = & git -C $root status --porcelain
  if($LASTEXITCODE -eq 0) { if($changes) { $workingTree='modified' } else { $workingTree='clean' } }
 }
 $testStatus = if($DeferTests) { 'DEFERRED' } else { 'COMPLETED' }
 $buildGate = if($DeferTests) { 'Release build, signature and permission checks only; tests/Lint deferred' } else { 'Host tests, release build, Lint, unit tests, signature and permission checks completed' }
 $receipt = @(
  "APK=$([IO.Path]::GetFileName($destination))",
  "Version=$version",
  "SHA256=$exportHash",
  "SourceCommit=$revision",
  "WorkingTree=$workingTree",
  "BuiltAt=$([DateTimeOffset]::Now.ToString('o'))",
  "BuildGate=$buildGate",
  "Tests=$testStatus",
  'BusinessAcceptance=NOT VERIFIED',
  'DeviceAcceptance=NOT VERIFIED',
  'Signing keys, passwords and authorization data are NOT included.'
 ) -join "`r`n"
 [IO.File]::WriteAllText((Join-Path $folder 'build-info.txt'), $receipt, (New-Object Text.UTF8Encoding($false)))
} catch {
 if(Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Force }
 throw
}
Write-Host "APK exported: $destination" -ForegroundColor Green
Write-Host "SHA256: $exportHash"
if($DeferTests) { Write-Warning 'TESTS_DEFERRED：导出的 APK 尚未通过测试与业务验收。' }
try { Start-Process explorer.exe -ArgumentList ('"' + $folder + '"') } catch { Write-Warning 'Could not open Explorer; use the output path above.' }
