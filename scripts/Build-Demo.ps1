param([switch]$DeferTests,[string]$QueueJob = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$work = Join-Path $root 'demo-work'
if($QueueJob -and -not (Test-Path -LiteralPath (Join-Path $root 'signing.properties') -PathType Leaf)) { throw 'Queued builds require ORIGINAL signing.properties; key generation is forbidden' }
$verified = Join-Path $work 'app\build\outputs\apk\release\app-release.verified.json'
if(Test-Path -LiteralPath $verified) { Remove-Item -LiteralPath $verified -Force }
if(-not (Test-Path (Join-Path $work 'gradlew.bat'))) { throw 'Demo working copy missing' }
if(-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
 $candidate = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
 if(-not (Test-Path (Join-Path $candidate 'bin\javac.exe'))) { throw 'Set JAVA_HOME to JDK 17' }
 $env:JAVA_HOME=$candidate
}
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
& java -version
if($DeferTests) {
 Write-Warning 'TESTS_DEFERRED：仅构建 APK，并校验签名和必要权限；主机测试、Lint、单元测试延后，尚未验收。'
} else {
 & (Join-Path $PSScriptRoot 'Test-Client.ps1')
}
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if(-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-29\android.jar'))) { throw 'Android platform 29 missing' }
[IO.File]::WriteAllText((Join-Path $work 'local.properties'),'sdk.dir='+$env:ANDROID_HOME.Replace('\','/')+"`n",(New-Object Text.UTF8Encoding($false)))
& (Join-Path $PSScriptRoot 'Apply-Demo.ps1')
if($QueueJob) { & (Join-Path $PSScriptRoot 'Apply-QueuedBuild.ps1') -JobFile $QueueJob }
$signing = Join-Path $root 'signing.properties'
if(-not (Test-Path -LiteralPath $signing)) {
 if($QueueJob) { throw 'Original signing disappeared; queued build cannot generate a key' }
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
 $tasks = @(':app:assembleRelease')
 if(-not $DeferTests) { $tasks += @(':app:lintRelease', ':app:testReleaseUnitTest') }
 & .\gradlew.bat --no-daemon @tasks
 if($LASTEXITCODE -ne 0) {
  $lintReport = Join-Path $work 'app\build\intermediates\lint_intermediate_text_report\release\lint-results-release.txt'
  if(-not $DeferTests -and (Test-Path -LiteralPath $lintReport)) {
   Write-Host ('Lint report (may include previous run results): '+$lintReport)
   $lintErrors = @(Select-String -LiteralPath $lintReport -Encoding UTF8 -Pattern '\bError:' -Context 0,2)
   foreach($issue in $lintErrors) {
    Write-Host $issue.Line
    foreach($line in $issue.Context.PostContext) { Write-Host $line }
    Write-Host ''
   }
   Select-String -LiteralPath $lintReport -Encoding UTF8 -Pattern '^\s*\d+ errors?,.*warnings?' | ForEach-Object { Write-Host $_.Line }
   if($lintErrors.Count -eq 0) { Write-Host 'No error entry in this Lint report; check the Gradle failure above.' }
  }
  throw 'Demo build failed; do not install an older APK'
 }
 $apk = Join-Path $work 'app\build\outputs\apk\release\app-release.apk'
 if(-not (Test-Path $apk)) { throw 'APK not found' }
 $tools = @(Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Recurse -Filter 'apksigner.bat' | Sort-Object FullName -Descending)
 if($tools.Count -eq 0) { throw 'apksigner missing' }
 & $tools[0].FullName verify --verbose --print-certs $apk
 if($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
 $aapt = Join-Path $tools[0].DirectoryName 'aapt.exe'
 if(-not (Test-Path -LiteralPath $aapt)) { throw 'aapt missing; cannot verify final APK permissions' }
 $permissions = & $aapt dump permissions $apk
 if($LASTEXITCODE -ne 0) { throw 'Cannot inspect final APK permissions' }
 if(($permissions | Out-String) -match 'android\.permission\.READ_PHONE_STATE') { throw 'READ_PHONE_STATE still present in final APK; fingerprint compatibility fix was not applied' }
 $apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
 $testStatus = if($DeferTests) { 'DEFERRED' } else { 'COMPLETED' }
 $receipt = @{ sha256=$apkHash; verifiedAt=[DateTimeOffset]::Now.ToString('o'); phonePermissionRemoved=$true; testsStatus=$testStatus; businessAcceptance='NOT_VERIFIED'; deviceAcceptance='NOT_VERIFIED' } | ConvertTo-Json
 [IO.File]::WriteAllText($verified,$receipt,(New-Object Text.UTF8Encoding($false)))
 Get-FileHash -LiteralPath $apk -Algorithm SHA256
 Write-Host ('APK: '+$apk)
 if($DeferTests) { Write-Warning 'TESTS_DEFERRED：APK 已构建；未执行自动化、业务、性能或真机验收。' }
 Write-Host 'Session V2 APK built. Deploy matching platform first; complete real-phone activation, sync, admission, restart, offline retry and account-switch checks.'
} finally { Pop-Location }
