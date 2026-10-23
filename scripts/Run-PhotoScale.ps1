#requires -Version 7.0
param(
 [Parameter(Mandatory)][string]$SourceFolder,
 [Parameter(Mandatory)][string]$WorkFolder,
 [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
 [ValidateRange(1,200000)][int]$ExpectedPhotos=125754,
 [ValidateRange(100,2000)][int]$BatchSize=2000,
 [switch]$AllowPause
)
$ErrorActionPreference='Stop'
if(-not $AllowPause){throw 'Use -AllowPause on an idle test phone with no pending business records.'}
$SourceFolder=(Resolve-Path -LiteralPath $SourceFolder).Path
$WorkFolder=[IO.Path]::GetFullPath($WorkFolder)
if($WorkFolder.StartsWith($SourceFolder.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -or $WorkFolder -eq $SourceFolder){throw 'WorkFolder must be outside SourceFolder'}
New-Item -ItemType Directory -Path $WorkFolder -Force | Out-Null
$lock=[IO.File]::Open((Join-Path $WorkFolder 'run.lock'),'OpenOrCreate','ReadWrite','None')
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$package='com.lingluren.yikatong.terminal'
$phoneTouched=$false
function Adb([string[]]$A){
 $o=@(& $adb -s $Serial @A 2>&1)
 if($LASTEXITCODE -ne 0){throw ($o -join "`n")}
 return $o
}
function SaveJson($Value,[string]$Path){
 $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$Path.tmp" -Encoding utf8NoBOM
 Move-Item -LiteralPath "$Path.tmp" -Destination $Path -Force
}
function ValidateBatch([string]$Folder,[int]$Expected){
 $m=Get-Content -LiteralPath (Join-Path $Folder 'manifest.json') -Raw | ConvertFrom-Json
 $c=Get-Content -LiteralPath (Join-Path $Folder 'checkpoint.json') -Raw | ConvertFrom-Json
 $f=Join-Path $Folder 'features.bin'
 $hash=(Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash.ToLowerInvariant()
 if($m.sdkVersion -ne '8.5' -or $m.featureType -ne 'LIVE_PHOTO' -or $m.synthetic -ne $true -or $m.genuineDistinctIdentities -ne $false -or $m.count -lt 1 -or $m.count -gt $Expected -or (Get-Item -LiteralPath $f).Length -ne ([long]$m.count*512) -or $hash -cne $m.sha256 -or $c.status -ne 'COMPLETE' -or $c.next -ne $Expected -or $c.processedLimit -ne $Expected -or $c.accepted -ne $m.count -or $c.corpusSha256 -cne $m.corpusSha256 -or $c.datasetMode -ne 'synthetic' -or ($c.accepted+$c.duplicates+$c.rejected) -ne $Expected){throw "Invalid/incomplete batch: $Folder"}
 return $m
}
function Instrument([string]$Method,[string]$Id,[int]$Limit,[string]$Log){
 $o=Adb @('shell','am','instrument','-w','-r','-e','datasetId',$Id,'-e','class',"$package.TerminalFeatureExtractTest#$Method",'-e','extractPhotos','yes','-e','datasetMode','synthetic','-e','maxPhotos',"$Limit","$package.test/$package.TerminalScaleRunner")
 $o | Set-Content -LiteralPath $Log -Encoding utf8NoBOM
 if(($o -join "`n") -notmatch 'OK \(1 test\)' -or ($o -join "`n") -match 'FAILURES!!!|Process crashed'){throw "Instrumentation failed: $Log"}
}
try {
 $photos=@(Get-ChildItem -LiteralPath $SourceFolder -File | Where-Object {$_.Extension -match '^\.(jpg|jpeg|png|webp)$'} | Sort-Object Name)
 if($photos.Count -ne $ExpectedPhotos){throw "Expected $ExpectedPhotos photos, found $($photos.Count)"}
 if(@($photos | Where-Object {$_.Length -le 0 -or $_.Length -gt 16777216 -or $_.Name -notmatch '^[A-Za-z0-9_.-]+$'}).Count){throw 'Invalid photo name or size'}
 Write-Host 'Checking source hashes (read-only); progress every 1000 photos...'
 $digest=[Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
 $hashes=[System.Collections.Generic.List[string]]::new()
 try {
  for($i=0;$i -lt $photos.Count;$i++){
   $h=(Get-FileHash -LiteralPath $photos[$i].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
   $hashes.Add($h)
   $digest.AppendData([Text.Encoding]::UTF8.GetBytes($photos[$i].Name+"`n"+$h+"`n"))
   if(($i+1)%1000 -eq 0){Write-Host "Source check: $($i+1)/$($photos.Count)"}
  }
  $corpus=[Convert]::ToHexString($digest.GetHashAndReset()).ToLowerInvariant()
 } finally {$digest.Dispose()}
 $planPath=Join-Path $WorkFolder 'plan.json'
 if(Test-Path -LiteralPath $planPath){
  $plan=Get-Content -LiteralPath $planPath -Raw | ConvertFrom-Json
  if($plan.corpusSha256 -cne $corpus -or $plan.batchSize -ne $BatchSize -or $plan.photos -ne $ExpectedPhotos -or $plan.serial -ne $Serial -or $plan.runId -notmatch '^bulk-[a-f0-9]{16}$'){throw 'Source/phone/batch settings changed; preserve this work folder and use a new one'}
 } else {
  if(@(Get-ChildItem -LiteralPath $WorkFolder -Force | Where-Object Name -ne 'run.lock').Count){throw 'New work folder must be empty'}
  $plan=[ordered]@{runId=('bulk-'+[Guid]::NewGuid().ToString('N').Substring(0,16));corpusSha256=$corpus;photos=$ExpectedPhotos;batchSize=$BatchSize;serial=$Serial}
  SaveJson $plan $planPath
 }
 Adb @('get-state') | Out-Host
 $installed=(Adb @('shell','dumpsys','package',$package)) -join "`n"
 if($installed -notmatch 'versionCode=(136|137|138|139|140)\b'){throw 'Production versionCode 136, 137, 138, 139 or 140 required; no production APK will be installed'}
 & (Join-Path $PSScriptRoot 'Test-NativeScale.ps1') -PrepareOnly
 $apk=Join-Path (Split-Path $PSScriptRoot -Parent) 'demo-work\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
 Adb @('install','-r',$apk) | Out-Host
 $batches=[int][Math]::Ceiling($photos.Count/[double]$BatchSize)
 $results=[System.Collections.Generic.List[string]]::new()
 for($b=0;$b -lt $batches;$b++){
  if(Test-Path -LiteralPath (Join-Path $WorkFolder 'STOP')){throw 'Stopped between batches; remove STOP and repeat the same command to resume'}
  $id='{0}-{1:D4}' -f $plan.runId,($b+1)
  $folder=Join-Path $WorkFolder $id
  New-Item -ItemType Directory -Path $folder -Force | Out-Null
  $first=$b*$BatchSize
  $n=[Math]::Min($BatchSize,$photos.Count-$first)
  $batchDigest=[Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
  try {
   for($j=0;$j -lt $n;$j++){$batchDigest.AppendData([Text.Encoding]::UTF8.GetBytes($photos[$first+$j].Name+"`n"+$hashes[$first+$j]+"`n"))}
   $batchHash=[Convert]::ToHexString($batchDigest.GetHashAndReset()).ToLowerInvariant()
  } finally {$batchDigest.Dispose()}
  $remote="/sdcard/Android/data/$package/files/ykt-test-v2/feature-batches/$id"
  $marker=Join-Path $folder 'verified.json'
  if(Test-Path -LiteralPath $marker){
   $m=ValidateBatch $folder $n
   if($m.corpusSha256 -cne $batchHash){throw 'Completed batch does not match source photos'}
   Write-Host "Batch $($b+1)/$batches already verified: $($m.count) templates; skipping extraction"
  } else {
   Write-Host "Batch $($b+1)/$batches : $n photos. Preparing upload..."
   $stage=Join-Path $folder 'images'
   New-Item -ItemType Directory -Path $stage -Force | Out-Null
   for($j=0;$j -lt $n;$j++){
    $p=$photos[$first+$j]
    Copy-Item -LiteralPath $p.FullName -Destination (Join-Path $stage $p.Name) -Force
    if((Get-FileHash -LiteralPath (Join-Path $stage $p.Name) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hashes[$first+$j]){throw 'Source changed while staging'}
   }
   try {
    $phoneTouched=$true
    Instrument 'testPrepareStorage' $id $n (Join-Path $folder 'prepare.txt')
    Adb @('push',"$stage/.","$remote/images/") | Out-Host
    Instrument 'testValidateImages' $id $n (Join-Path $folder 'validate.txt')
    Adb @('shell','rm','-f',"$remote/STOP") | Out-Null
    Write-Host "Batch $($b+1)/$batches extracting; output follows when this batch finishes."
    Instrument 'testExtractPhotos' $id $n (Join-Path $folder 'instrumentation.txt')
    foreach($name in @('features.bin','manifest.json','checkpoint.json','rejections.jsonl')){Adb @('pull',"$remote/$name",(Join-Path $folder $name)) | Out-Null}
    $m=ValidateBatch $folder $n
    if($m.corpusSha256 -cne $batchHash){throw 'Phone corpus does not match source photos'}
    SaveJson @{sha256=$m.sha256;count=$m.count} $marker
    Write-Host "Batch $($b+1)/$batches verified: $($m.count) templates"
   } catch {
    & $adb -s $Serial pull "$remote/checkpoint.json" (Join-Path $folder 'checkpoint.json') 2>&1 | Out-Host
    & $adb -s $Serial logcat -b crash -d 2>&1 | Set-Content -LiteralPath (Join-Path $folder 'crash.txt')
    throw
   }
  }
  # Only this run's verified batch pictures are removed. Features/checkpoints stay on phone and PC.
  Adb @('shell','rm','-rf',"$remote/images") | Out-Null
  $stage=Join-Path $folder 'images'
  if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Recurse -Force}
  $results.Add($folder)
 }
 Write-Host 'All photos processed. Merging only this run; deduplicating exact SDK templates...'
 $mergedDir=Join-Path $WorkFolder 'merged'
 New-Item -ItemType Directory -Path $mergedDir -Force | Out-Null
 $file=Join-Path $mergedDir 'features.bin'
 $stream=[IO.File]::Open("$file.tmp",'Create','Write','None')
 $seen=[System.Collections.Generic.HashSet[string]]::new()
 $duplicates=0
 $rejected=0
 $withinDuplicates=0
 try {
  foreach($folder in $results){
   $c=Get-Content -LiteralPath (Join-Path $folder 'checkpoint.json') -Raw | ConvertFrom-Json
   $rejected+=$c.rejected; $withinDuplicates+=$c.duplicates
   $data=[IO.File]::ReadAllBytes((Join-Path $folder 'features.bin'))
   for($i=0;$i -lt $data.Length;$i+=512){
    $key=[Convert]::ToBase64String($data,$i,512)
    if($seen.Add($key)){$stream.Write($data,$i,512)}else{$duplicates++}
   }
  }
  $stream.Flush($true)
 } finally {$stream.Dispose()}
 if($seen.Count -lt 1){throw 'No valid templates'}
 if(($seen.Count+$duplicates+$withinDuplicates+$rejected) -ne $ExpectedPhotos){throw 'Photo accounting mismatch'}
 Move-Item -LiteralPath "$file.tmp" -Destination $file -Force
 $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
 SaveJson ([ordered]@{sdkVersion='8.5';featureType='LIVE_PHOTO';count=$seen.Count;synthetic=$true;genuineDistinctIdentities=$false;sha256=$hash;corpusSha256=$corpus;extraction='TerminalFaceEngine.upsert';sourcePhotos=$ExpectedPhotos;rejected=$rejected;withinBatchDuplicates=$withinDuplicates;crossBatchDuplicates=$duplicates;runId=$plan.runId}) (Join-Path $mergedDir 'manifest.json')
 Write-Host "Ready: $($seen.Count) valid templates from $ExpectedPhotos photos. Starting native index test."
 & (Join-Path $PSScriptRoot 'Test-NativeScale.ps1') -Dataset $file -Serial $Serial -DatasetMode synthetic -Count $seen.Count -AllowPause
 Write-Host "Completed. Dataset and batch records: $WorkFolder"
} finally {
 if($phoneTouched){
  & $adb -s $Serial shell am force-stop $package
  & $adb -s $Serial shell am start -W -n "$package/$package.TerminalEntryActivity"
 }
 $lock.Dispose()
}

