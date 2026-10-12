param([string]$SdkZip)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $SdkZip) {
 Add-Type -AssemblyName System.Windows.Forms
 $dialog = New-Object System.Windows.Forms.OpenFileDialog
 $dialog.Title = 'Select sdk(4).zip supplied for this project'
 $dialog.Filter = 'SDK ZIP (*.zip)|*.zip'
 if ($dialog.ShowDialog() -ne 'OK') { throw 'SDK selection cancelled.' }
 $SdkZip = $dialog.FileName
}
$SdkZip = (Resolve-Path -LiteralPath $SdkZip).Path
$work = Join-Path ([IO.Path]::GetTempPath()) ('ykt-sdk-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
 Expand-Archive -LiteralPath $SdkZip -DestinationPath $work
 $matches = @(Get-ChildItem -LiteralPath $work -Recurse -File -Filter 'facelibrary-release-8.5-composite-20251124.aar')
 if ($matches.Count -ne 1) { throw 'Select sdk(4).zip. Expected exactly one supported AAR.' }
 $aar = $matches[0]
 $expected = 'fc21b97152a37a9ffcd3329f4f94e561463d0e66f2ca6c30d31c363472cd651d'
 if ((Get-FileHash -LiteralPath $aar.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'SDK checksum mismatch. Stop; do not rename a different SDK.' }
 $models = Join-Path $aar.DirectoryName 'assets\face-sdk-models'
 if (-not (Test-Path -LiteralPath $models)) { throw 'SDK models missing. Use sdk(4).zip, not the full Demo ZIP.' }
 $vendor = Join-Path $root 'vendor'
 $assets = Join-Path $root 'app\src\main\assets'
 New-Item -ItemType Directory -Force -Path $vendor,$assets | Out-Null
 Copy-Item -LiteralPath $aar.FullName -Destination (Join-Path $vendor 'face-sdk.aar') -Force
 Copy-Item -LiteralPath $models -Destination $assets -Recurse -Force
 Write-Host 'SDK imported. Proprietary files remain local and are ignored by Git.'
} finally {
 if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
