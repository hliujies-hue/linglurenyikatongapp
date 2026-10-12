$ErrorActionPreference = 'Stop'
function Initialize-YktEnvironment {
 $root = Split-Path $PSScriptRoot -Parent
 $javaCandidates = @(@(
  (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
  $env:JAVA_HOME
 ) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe')) })
 if ($javaCandidates.Count -eq 0) { throw 'JDK not found. Install Android Studio or set JAVA_HOME to JDK 17+.' }
 $env:JAVA_HOME = $javaCandidates[0]
 $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
 $sdkCandidates = @(@($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,(Join-Path $env:LOCALAPPDATA 'Android\Sdk')) | Where-Object { $_ -and (Test-Path $_) })
 if ($sdkCandidates.Count -eq 0) { throw 'Android SDK not found. Open Android Studio > SDK Manager.' }
 $env:ANDROID_HOME = $sdkCandidates[0]
 $sdkEscaped = $env:ANDROID_HOME.Replace('\','/')
 [IO.File]::WriteAllText((Join-Path $root 'local.properties'),"sdk.dir=$sdkEscaped`n",(New-Object Text.UTF8Encoding($false)))
 Write-Host ('JDK: ' + $env:JAVA_HOME)
 Write-Host ('Android SDK: ' + $env:ANDROID_HOME)
 & java -version
 if ($LASTEXITCODE -ne 0) { throw 'Java failed.' }
}
