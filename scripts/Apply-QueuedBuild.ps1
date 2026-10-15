param([Parameter(Mandatory=$true)][string]$JobFile)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$job=Get-Content -LiteralPath $JobFile -Raw -Encoding UTF8 | ConvertFrom-Json
if([string]$job.versionName -notmatch '^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})(-[A-Za-z0-9._-]+)?$' -or ([string]$job.versionName).Length -gt 64) { throw 'Queued versionName invalid' }
if([string]$job.versionCode -notmatch '^[0-9]+$' -or [long]$job.versionCode -le 131 -or [long]$job.versionCode -gt 2100000000) { throw 'Queued versionCode invalid' }
if([string]$job.sourceCommit -notmatch '^[a-f0-9]{40}$') { throw 'Queued source commit invalid' }
$head=(& git -C $root rev-parse HEAD | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $head -ne $job.sourceCommit) { throw 'Queued source commit does not match checkout' }
$dirty=(& git -C $root status --porcelain | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $dirty) { throw 'Queued build requires a clean dedicated checkout' }
if(-not (Test-Path -LiteralPath (Join-Path $root 'signing.properties') -PathType Leaf)) { throw 'Restore ORIGINAL signing.properties; automatic key generation is forbidden' }
$gradlePath=Join-Path $root 'demo-work\app\build.gradle'
$gradle=[IO.File]::ReadAllText($gradlePath)
foreach($pattern in @('(?m)^\s*versionCode\s+[0-9]+\s*$','(?m)^\s*versionName\s+"[^"\r\n]+"\s*$')) {
 if([regex]::Matches($gradle,$pattern).Count -ne 1) { throw 'Version declaration missing or ambiguous' }
}
$gradle=[regex]::Replace($gradle,'(?m)^\s*versionCode\s+[0-9]+\s*$',('        versionCode '+$job.versionCode))
$gradle=[regex]::Replace($gradle,'(?m)^\s*versionName\s+"[^"\r\n]+"\s*$',('        versionName "'+$job.versionName+'"'))
[IO.File]::WriteAllText($gradlePath,$gradle,(New-Object Text.UTF8Encoding($false)))
$protocolPath=Join-Path $root 'demo-work\app\src\main\java\com\lingluren\yikatong\terminal\TerminalProtocol.java'
$protocol=[IO.File]::ReadAllText($protocolPath)
$pattern='public static final String VERSION="[A-Za-z0-9._-]+";'
if([regex]::Matches($protocol,$pattern).Count -ne 1) { throw 'Protocol version declaration missing or ambiguous' }
$protocol=[regex]::Replace($protocol,$pattern,('public static final String VERSION="'+$job.versionName+'";'))
[IO.File]::WriteAllText($protocolPath,$protocol,(New-Object Text.UTF8Encoding($false)))
