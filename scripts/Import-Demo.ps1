param([Parameter(Mandatory=$true)][string]$DemoZip)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $root 'demo-local'
if (Test-Path -LiteralPath $destination) { throw 'demo-local already exists. Do not overwrite an imported or edited Demo.' }
$DemoZip = (Resolve-Path -LiteralPath $DemoZip).Path
$expected = '8ebb3ed7cada8b39ed1256277094f08266af8dbfa76c72e5232b84feda12e19e'
if ((Get-FileHash -LiteralPath $DemoZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
 throw 'Demo archive differs from the reviewed FaceSDKAndroid(2).zip. Stop and report the checksum.'
}
$work = Join-Path ([IO.Path]::GetTempPath()) ('ykt-demo-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
 Expand-Archive -LiteralPath $DemoZip -DestinationPath $work
 foreach($path in @('settings.gradle','app\build.gradle','datalibrary','AuthLibrary','gatelibrary','faceaar')) {
  if(-not (Test-Path -LiteralPath (Join-Path $work $path))) { throw ('Demo component missing: ' + $path) }
 }
 Move-Item -LiteralPath $work -Destination $destination
 Write-Host ('Full original Demo imported to: ' + $destination)
 Write-Host 'Keep this original Demo unchanged. Prepare demo-work, then run Build-Demo.ps1 to apply maintained application code and your release signing.'
} finally {
 if(Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}

