$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
Initialize-YktEnvironment
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try { & .\gradlew.bat --no-daemon :core:check; if($LASTEXITCODE -ne 0) { throw 'Core tests failed.' } }
finally { Pop-Location }
