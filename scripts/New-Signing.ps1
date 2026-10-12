$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
Initialize-YktEnvironment
$root = Split-Path $PSScriptRoot -Parent
$properties = Join-Path $root 'signing.properties'
if (Test-Path -LiteralPath $properties) { Write-Host 'Existing signing configuration retained.'; exit 0 }
$dir = Join-Path $env:USERPROFILE '.yikatong-signing'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$key = Join-Path $dir 'terminal-release.jks'
if (Test-Path -LiteralPath $key) { throw 'Signing key exists without project configuration. Restore signing.properties; do not create a replacement key.' }
$random = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($random); $rng.Dispose()
$password = [Convert]::ToBase64String($random)
$env:YKT_SIGN_PASSWORD = $password
try {
 & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $key -storetype JKS -alias yikatong-terminal -keyalg RSA -keysize 3072 -validity 10000 -storepass:env YKT_SIGN_PASSWORD -keypass:env YKT_SIGN_PASSWORD -dname 'CN=Lingluren Terminal, OU=Engineering, O=Lingluren, C=CN'
 if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
 $keyPath = $key.Replace('\','/')
 $content = "storeFile=$keyPath`nstorePassword=$password`nkeyAlias=yikatong-terminal`nkeyPassword=$password`n"
 [IO.File]::WriteAllText($properties,$content,(New-Object Text.UTF8Encoding($false)))
 $backup = Join-Path $dir 'signing.properties'
 [IO.File]::WriteAllText($backup,$content,(New-Object Text.UTF8Encoding($false)))
 $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
 foreach($p in @($key,$backup,$properties)) {
  & icacls.exe $p /inheritance:r /grant:r "${identity}:(F)" | Out-Null
  if($LASTEXITCODE -ne 0) { throw 'Unable to restrict signing-file permissions.' }
 }
 Write-Host ('Release key created. Back up this directory securely: ' + $dir)
 Write-Host 'Never send the key, passwords or signing.properties in chat or commit them to Git.'
} finally { Remove-Item Env:YKT_SIGN_PASSWORD -ErrorAction SilentlyContinue; $password = $null }
