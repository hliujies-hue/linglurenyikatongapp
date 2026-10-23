param([ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$')][string]$DatasetId='legacy', [string]$SourceFolder='', [string]$Serial='', [ValidateSet('real','synthetic')][string]$DatasetMode='', [ValidateRange(1,200000)][int]$MaxPhotos=1000, [switch]$AllowPause, [switch]$Resume, [switch]$AttestDistinctPeople)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(-not $AllowPause -or -not $DatasetMode) { throw 'Specify -AllowPause and -DatasetMode real/synthetic; use an idle phone with no pending records' }
if($DatasetMode -eq 'real' -and -not $AttestDistinctPeople) { throw 'Real mode requires consented data and -AttestDistinctPeople: one input image per distinct person, not multiple photos of the same people' }
if(-not $Resume) {
 if(-not (Test-Path -LiteralPath $SourceFolder -PathType Container)) { throw 'Provide a flat folder of actual JPG/PNG/WebP test images' }
 if(@(Get-ChildItem -LiteralPath $SourceFolder -Directory).Count -gt 0) { throw 'Use a flat image folder with no subfolders' }
 $photos=@(Get-ChildItem -LiteralPath $SourceFolder -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp)$' })
 if($photos.Count -eq 0 -or $photos.Count -gt 200000) { throw 'Require 1..200000 photos' }
 if(@($photos | Where-Object { $_.Length -le 0 -or $_.Length -gt 16777216 }).Count -gt 0) { throw 'Each photo must be 1 byte..16MiB' }
}
& (Join-Path $PSScriptRoot 'Test-NativeScale.ps1') -PrepareOnly
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if(-not $Serial) {
 $devices=@(& $adb devices | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
 if($devices.Count -ne 1) { throw 'Connect exactly one authorized phone or specify -Serial' }
 $Serial=$devices[0]
}
function Invoke-Adb([string[]]$Arguments) {
 $output=@(& $adb -s $Serial @Arguments 2>&1)
 if($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
 return $output
}
$package='com.lingluren.yikatong.terminal'
$installed=(Invoke-Adb @('shell','dumpsys','package',$package)) -join "`n"
if($installed -notmatch 'versionCode=(136|137|138|139|140)\b') { throw 'Original APP versionCode136, 137, 138, 139 or 140 required; no install/downgrade of production app is performed' }
$remote='/sdcard/Android/data/com.lingluren.yikatong.terminal/files/ykt-test-v2/feature-extract'
if($DatasetId -ne 'legacy') { $remote="/sdcard/Android/data/$package/files/ykt-test-v2/feature-batches/$DatasetId" }
$apk=Join-Path $root 'demo-work\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
Invoke-Adb @('install','-r',$apk) | Out-Host
if(-not $Resume) {
 & $adb -s $Serial shell test -d $remote
 if($LASTEXITCODE -eq 0) { throw 'Extraction directory already exists. Use -Resume; export previous results before manually clearing only the test feature-extract directory.' }
 $prepare=Invoke-Adb @('shell','am','instrument','-w','-r','-e','datasetId',$DatasetId,'-e','class',"$package.TerminalFeatureExtractTest#testPrepareStorage","$package.test/$package.TerminalScaleRunner")
 $prepare | Out-Host
 if(($prepare -join "`n") -notmatch 'OK \(1 test\)') { throw 'App-owned storage preparation failed; no photos uploaded' }
 # Push only selected images; unrelated files are not copied.
 foreach($photo in $photos) { Invoke-Adb @('push',$photo.FullName,"$remote/images/$($photo.Name)") | Out-Null }
}
$check=Invoke-Adb @('shell','am','instrument','-w','-r','-e','datasetId',$DatasetId,'-e','class',"$package.TerminalFeatureExtractTest#testValidateImages","$package.test/$package.TerminalScaleRunner")
$check | Out-Host
if(($check -join "`n") -notmatch 'OK \(1 test\)') { throw 'APP cannot read uploaded photos; SDK extraction was not started' }
Invoke-Adb @('shell','rm','-f',"$remote/STOP") | Out-Null
$folder=Join-Path ([Environment]::GetFolderPath('Desktop')) ('Yikatong-FeatureData\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $folder -Force | Out-Null
Invoke-Adb @('shell','am','force-stop',$package) | Out-Null
try {
 $output=Invoke-Adb @('shell','am','instrument','-w','-r','-e','datasetId',$DatasetId,'-e','class',"$package.TerminalFeatureExtractTest#testExtractPhotos",'-e','extractPhotos','yes','-e','datasetMode',$DatasetMode,'-e','maxPhotos',"$MaxPhotos","$package.test/$package.TerminalScaleRunner")
 $output | Set-Content -LiteralPath (Join-Path $folder 'instrumentation.txt') -Encoding UTF8
 $output | Out-Host
 if(($output -join "`n") -notmatch 'OK \(1 test\)') { throw "Instrumentation failed; see $folder\instrumentation.txt." }
 Invoke-Adb @('pull',"$remote/manifest.json",(Join-Path $folder 'manifest.json')) | Out-Host
 Invoke-Adb @('pull',"$remote/features.bin",(Join-Path $folder 'features.bin')) | Out-Host
 $manifest=Get-Content -LiteralPath (Join-Path $folder 'manifest.json') -Raw | ConvertFrom-Json
 $hash=(Get-FileHash -LiteralPath (Join-Path $folder 'features.bin') -Algorithm SHA256).Hash.ToLowerInvariant()
 if(($output -join "`n") -notmatch 'OK \(1 test\)' -or $manifest.sha256 -cne $hash) { throw 'Extraction failed or exported data hash mismatch' }
 Write-Host "Extraction complete: $($manifest.count) unique templates. Data: $folder. This is NOT a capacity-test PASS."
} finally {
 & $adb -s $Serial pull "$remote/checkpoint.json" (Join-Path $folder 'checkpoint.json') 2>&1 | Out-Host
 & $adb -s $Serial pull "$remote/rejections.jsonl" (Join-Path $folder 'rejections.jsonl') 2>&1 | Out-Host
 & $adb -s $Serial shell am force-stop $package
 & $adb -s $Serial shell am start -W -n "$package/$package.TerminalEntryActivity"
 Write-Host "Output: $folder. Images/checkpoint remain in the dedicated ykt-test directory for resume. Verify original 6-person business data after restart."
}

