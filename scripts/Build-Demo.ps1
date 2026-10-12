$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$work = Join-Path $root 'demo-work'
if(-not (Test-Path (Join-Path $work 'gradlew.bat'))) { throw 'Demo working copy missing' }
if(-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
 $candidate = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
 if(-not (Test-Path (Join-Path $candidate 'bin\javac.exe'))) { throw 'Set JAVA_HOME to JDK 17' }
 $env:JAVA_HOME=$candidate
}
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
& java -version
& (Join-Path $PSScriptRoot 'Test-Client.ps1')
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if(-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-29\android.jar'))) { throw 'Android platform 29 missing' }
[IO.File]::WriteAllText((Join-Path $work 'local.properties'),'sdk.dir='+$env:ANDROID_HOME.Replace('\','/')+"`n",(New-Object Text.UTF8Encoding($false)))
& (Join-Path $PSScriptRoot 'Apply-Demo.ps1')
$signing = Join-Path $root 'signing.properties'
if(-not (Test-Path -LiteralPath $signing)) {
 $dir = Join-Path $env:USERPROFILE '.yikatong-signing'
 New-Item -ItemType Directory -Force -Path $dir | Out-Null
 $key = Join-Path $dir 'terminal-release.jks'
 if(Test-Path -LiteralPath $key) { throw 'Existing key found. Restore signing.properties; do not replace the key.' }
 $bytes = New-Object byte[] 32
 $rng = [Security.Cryptography.RandomNumberGenerator]::Create();$rng.GetBytes($bytes);$rng.Dispose()
 $password = [Convert]::ToBase64String($bytes);$env:YKT_SIGN_PASSWORD=$password
 try {
  & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $key -storetype JKS -alias yikatong-terminal -keyalg RSA -keysize 3072 -validity 10000 -storepass:env YKT_SIGN_PASSWORD -keypass:env YKT_SIGN_PASSWORD -dname 'CN=Lingluren Terminal, O=Lingluren, C=CN'
  if($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
  $content = 'storeFile='+$key.Replace('\','/')+"`nstorePassword=$password`nkeyAlias=yikatong-terminal`nkeyPassword=$password`n"
  [IO.File]::WriteAllText($signing,$content,(New-Object Text.UTF8Encoding($false)))
  $backup = Join-Path $dir 'signing.properties'
  [IO.File]::WriteAllText($backup,$content,(New-Object Text.UTF8Encoding($false)))
  $identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
  foreach($file in @($key,$signing,$backup)) {
   & icacls.exe $file /inheritance:r /grant:r "${identity}:(F)" | Out-Null
   if($LASTEXITCODE -ne 0) { throw 'Signing-file permissions failed' }
  }
  Write-Host ('Keep a secure backup of: '+$dir)
 } finally { Remove-Item Env:YKT_SIGN_PASSWORD -ErrorAction SilentlyContinue;$password=$null }
}
Push-Location $work
try {
 & .\gradlew.bat --no-daemon :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
 if($LASTEXITCODE -ne 0) { throw 'Demo build failed; do not install an older APK' }
 $apk = Join-Path $work 'app\build\outputs\apk\release\app-release.apk'
 if(-not (Test-Path $apk)) { throw 'APK not found' }
 $tools = @(Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Recurse -Filter 'apksigner.bat' | Sort-Object FullName -Descending)
 if($tools.Count -eq 0) { throw 'apksigner missing' }
 & $tools[0].FullName verify --verbose --print-certs $apk
 if($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
 Get-FileHash -LiteralPath $apk -Algorithm SHA256
 Write-Host ('APK: '+$apk)
 Write-Host 'Device login, binding and heartbeat build. Deploy matching platform version first. Face synchronization and admission remain pending.'
} finally { Pop-Location }
