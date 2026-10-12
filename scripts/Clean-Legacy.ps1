$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
 $remote = & git remote get-url origin
 if($LASTEXITCODE -ne 0 -or $remote -ne 'git@github.com:hliujies-hue/linglurenyikatongapp.git') { throw 'Repository mismatch' }
 foreach($name in @('app','core','vendor','gradle','.gradle')) {
  $tracked = & git ls-files -- $name
  if($LASTEXITCODE -ne 0 -or $tracked) { throw ('Refusing to remove tracked files: ' + $name) }
 }
 foreach($name in @('app','core','vendor','gradle','.gradle')) {
  $path = Join-Path $root $name
  if(Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force; Write-Host ('Removed old prototype directory: '+$name) }
 }
 Write-Host 'Original archives, demo-local, SSH keys and signing files retained.'
} finally { Pop-Location }
